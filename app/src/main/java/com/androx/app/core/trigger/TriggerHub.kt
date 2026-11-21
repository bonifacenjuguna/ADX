package com.androx.app.core.trigger

import com.androx.app.core.engine.CheckEnv
import com.androx.app.core.engine.Eval
import com.androx.app.core.engine.TriggerInfo
import com.androx.app.core.model.ConstraintNode
import com.androx.app.core.model.Flow
import com.androx.app.core.model.P
import com.androx.app.core.model.TriggerEntry
import com.androx.app.core.registry.Registry
import com.androx.app.core.registry.TriggerSpec
import com.androx.app.core.value.Value

enum class Outcome { FIRED, PAUSED, NOT_MATCHING, COOLDOWN, CONSTRAINT, HOLDING, HOLD_BROKEN }

/** What the hub decided for one trigger entry of one Flow. [reason] is plain English for the Trigger log. */
data class Decision(val flowId: String, val flowName: String, val entryId: String, val outcome: Outcome, val reason: String)

/** One line of the Trigger log: an event was received, even if no Flow ran. */
data class TriggerLogEntry(val at: Long, val typeId: String, val data: Map<String, String>, val decisions: List<Decision>)

/**
 * The single place all events arrive. For each event it finds the Flows that listen for it and applies the
 * trigger rules: master Pause, enabled switches, matching, "only after holding N minutes", cooldown and the
 * trigger's own constraints. Flow-level constraints are checked by the engine when the run starts.
 */
class TriggerHub(
    private val clock: () -> Long,
    private val flows: () -> List<Flow>,
    private val env: () -> CheckEnv,
    private val constraints: (ConstraintNode?, TriggerInfo) -> Eval,
    private val isPaused: () -> Boolean,
    private val dispatch: (Flow, TriggerEntry, TriggerInfo) -> Unit,
    private val log: (TriggerLogEntry) -> Unit = {},
    private val matchers: Map<String, EventMatcher> = EventMatchers.matchers,
    private val holds: Map<String, HoldCheck> = EventMatchers.holds,
) {
    private class Pending(val flow: Flow, val entry: TriggerEntry, val info: TriggerInfo, val dueAt: Long)

    private val lock = Any()
    private val lastFired = HashMap<String, Long>()
    private val pending = HashMap<String, Pending>()

    /** Delivers an event. Returns what happened to every trigger that listens for this type. */
    fun onEvent(typeId: String, data: Map<String, Value> = emptyMap()): List<Decision> {
        val toFire = ArrayList<Triple<Flow, TriggerEntry, TriggerInfo>>()
        val decisions = ArrayList<Decision>()
        val now = clock()
        synchronized(lock) {
            for (flow in flows()) {
                if (!flow.enabled || flow.deletedAt != null) continue
                for (entry in flow.triggers) {
                    if (!entry.enabled || entry.event.typeId != typeId) continue
                    fun add(o: Outcome, why: String) { decisions += Decision(flow.id, flow.name, entry.id, o, why) }
                    val p = P(entry.event.params)
                    val matches = matchers[typeId]?.matches(p, data) ?: true
                    if (!matches) {
                        if (pending.remove(entry.id) != null) add(Outcome.HOLD_BROKEN, "the condition ended before the hold time")
                        else add(Outcome.NOT_MATCHING, "the event did not match this trigger's settings")
                        continue
                    }
                    if (isPaused()) { add(Outcome.PAUSED, "Androx is paused"); continue }
                    val info = TriggerInfo(typeId, entry.id, data)
                    if (entry.holdMinutes > 0) {
                        if (!pending.containsKey(entry.id)) {
                            pending[entry.id] = Pending(flow, entry, info, now + entry.holdMinutes * 60_000L)
                        }
                        add(Outcome.HOLDING, "waiting ${entry.holdMinutes} min to confirm it holds")
                        continue
                    }
                    val verdict = gate(flow, entry, info, now)
                    if (verdict == null) {
                        lastFired[entry.id] = now
                        toFire += Triple(flow, entry, info)
                        add(Outcome.FIRED, "started")
                    } else decisions += verdict
                }
            }
        }
        log(TriggerLogEntry(now, typeId, data.mapValues { it.value.asText() }, decisions))
        toFire.forEach { (f, e, i) -> dispatch(f, e, i) }
        return decisions
    }

    /** Cooldown and the trigger's own constraints. Null means "go ahead". */
    private fun gate(flow: Flow, entry: TriggerEntry, info: TriggerInfo, now: Long): Decision? {
        val last = lastFired[entry.id]
        if (entry.cooldownSec > 0 && last != null && now - last < entry.cooldownSec * 1000L) {
            val left = (entry.cooldownSec * 1000L - (now - last) + 999) / 1000
            return Decision(flow.id, flow.name, entry.id, Outcome.COOLDOWN, "cooling down for $left more s")
        }
        val e = constraints(entry.constraints, info)
        if (!e.passed) return Decision(flow.id, flow.name, entry.id, Outcome.CONSTRAINT, "this trigger's constraint failed: ${e.why}")
        return null
    }

    /** Call regularly (an alarm at [nextDeadline]). Fires holds whose time has come and whose condition still holds. */
    fun tick(): List<Decision> {
        val now = clock()
        val toFire = ArrayList<Triple<Flow, TriggerEntry, TriggerInfo>>()
        val decisions = ArrayList<Decision>()
        synchronized(lock) {
            val due = pending.values.filter { it.dueAt <= now }
            for (d in due) {
                pending.remove(d.entry.id)
                val check = holds[d.entry.event.typeId]
                if (check != null && !check.holds(P(d.entry.event.params), env())) {
                    decisions += Decision(d.flow.id, d.flow.name, d.entry.id, Outcome.HOLD_BROKEN, "the condition no longer holds")
                    continue
                }
                if (isPaused()) {
                    decisions += Decision(d.flow.id, d.flow.name, d.entry.id, Outcome.PAUSED, "Androx is paused")
                    continue
                }
                val verdict = gate(d.flow, d.entry, d.info, now)
                if (verdict == null) {
                    lastFired[d.entry.id] = now
                    toFire += Triple(d.flow, d.entry, d.info)
                    decisions += Decision(d.flow.id, d.flow.name, d.entry.id, Outcome.FIRED, "held for ${d.entry.holdMinutes} min")
                } else decisions += verdict
            }
        }
        toFire.forEach { (f, e, i) -> dispatch(f, e, i) }
        return decisions
    }

    /** When [tick] next has something to do, or null. */
    fun nextDeadline(): Long? = synchronized(lock) { pending.values.minOfOrNull { it.dueAt } }

    /** Forget waiting holds (when Androx is paused or a Flow is disabled). */
    fun clearPending(flowId: String? = null) = synchronized(lock) {
        if (flowId == null) pending.clear() else pending.values.removeAll { it.flow.id == flowId }
    }
}

/** What the opt-in Listener service must watch for the current set of enabled Flows. */
data class ListenerNeed(val flowId: String, val flowName: String, val triggerName: String, val source: String)

data class ListenerPlan(val needs: List<ListenerNeed>) {
    val needed: Boolean get() = needs.isNotEmpty()
    val sources: Set<String> get() = needs.map { it.source }.toSet()

    companion object {
        fun compute(flows: List<Flow>, triggers: Registry<TriggerSpec>, sources: Map<String, String> = EventMatchers.sources): ListenerPlan =
            ListenerPlan(
                flows.filter { it.enabled && it.deletedAt == null }.flatMap { f ->
                    f.triggers.filter { it.enabled }.mapNotNull { t ->
                        sources[t.event.typeId]?.let { src ->
                            ListenerNeed(f.id, f.name, triggers[t.event.typeId]?.name ?: t.event.typeId, src)
                        }
                    }
                },
            )
    }
}
