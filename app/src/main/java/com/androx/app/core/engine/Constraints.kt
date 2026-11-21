package com.androx.app.core.engine

import com.androx.app.core.model.Check
import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.ConstraintNode
import com.androx.app.core.model.GroupMode
import com.androx.app.core.model.P
import com.androx.app.core.value.Value
import com.androx.app.engine.DeviceBridge
import com.androx.app.model.Comparison
import com.androx.app.model.RingerMode
import com.androx.app.model.describeDays
import com.androx.app.model.formatClock
import com.androx.app.model.ringerWord
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reads one piece of device state by key (`battery.temp_c`, `net.type`, `dnd.on` …). Null means "not available here". */
fun interface StateReader {
    fun read(key: String): Value?
}

/** A fixed set of states. Used by tests and the Simulator ("what if the battery were 10%?"). */
class MapState(private val values: Map<String, Value>) : StateReader {
    override fun read(key: String): Value? = values[key]
}

/** What a check can look at. */
class CheckEnv(
    val device: DeviceBridge,
    val variables: Variables,
    val now: ZonedDateTime,
    val state: StateReader = StateReader { null },
    /** Fills `{{templates}}` in a check's values (the Compare values check). */
    val render: (String) -> String = { it },
    /** Type id of the trigger that started this run, when there is one. */
    val triggerTypeId: String? = null,
)

/** Returns whether the check holds and a plain-English reason that includes the value seen ("battery is 66%"). */
typealias CheckFn = (P, CheckEnv) -> Pair<Boolean, String>

data class Eval(val passed: Boolean, val why: String, val lines: List<ConstraintLine>)

/**
 * Evaluates constraint trees. Every rule explains itself, which powers "Would it run now?",
 * "Why did this run?" and "Why didn't it run?". Extra checks can be passed in, so a new constraint is one function.
 */
class ConstraintEvaluator(
    private val device: DeviceBridge,
    private val variables: Variables,
    private val clock: () -> Long,
    private val zone: ZoneId,
    private val extra: Map<String, CheckFn> = emptyMap(),
    private val state: StateReader = StateReader { null },
) {
    fun evaluate(node: ConstraintNode?, render: (String) -> String = { it }, triggerTypeId: String? = null): Eval {
        if (node == null) return Eval(true, "no constraints", emptyList())
        val env = CheckEnv(device, variables, ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone), state, render, triggerTypeId)
        return eval(node, env)
    }

    private fun eval(node: ConstraintNode, env: CheckEnv): Eval = when (node) {
        is ConstraintNode.Rule -> rule(node.check, env)
        is ConstraintNode.Group -> {
            val parts = node.items.map { eval(it, env) }
            val lines = parts.flatMap { it.lines }
            when (node.mode) {
                GroupMode.ALL -> {
                    val failed = parts.firstOrNull { !it.passed }
                    if (failed == null) Eval(true, parts.joinToString(" and ") { it.why }.ifBlank { "no constraints" }, lines)
                    else Eval(false, failed.why, lines)
                }
                GroupMode.ANY -> {
                    if (parts.isEmpty()) Eval(true, "no constraints", lines)
                    else {
                        val ok = parts.firstOrNull { it.passed }
                        if (ok != null) Eval(true, ok.why, lines)
                        else Eval(false, parts.joinToString(" and ") { it.why }, lines)
                    }
                }
                GroupMode.NONE -> {
                    val hit = parts.firstOrNull { it.passed }
                    if (hit == null) Eval(true, "none of the listed conditions hold", lines)
                    else Eval(false, "but ${hit.why}", lines)
                }
            }
        }
    }

    private fun rule(check: Check, env: CheckEnv): Eval {
        val fn = BUILTIN[check.typeId] ?: extra[check.typeId]
            ?: return Eval(false, "\"${check.typeId}\" isn't available in this version", listOf(ConstraintLine("\"${check.typeId}\" isn't available", false)))
        val (ok, why) = try {
            fn(P(check.params), env)
        } catch (e: Exception) {
            false to "couldn't read it (${e.message ?: "unknown error"})"
        }
        val passed = ok != check.negate
        val text = if (check.negate) "not ($why)" else why
        return Eval(passed, text, listOf(ConstraintLine(text, passed)))
    }

    companion object {
        private fun check(fn: CheckFn): CheckFn = fn

        private fun cmp(op: Comparison, left: Double, right: Double) = op.test(left.compareTo(right))

        val BUILTIN: Map<String, CheckFn> = ExtraChecks.all + mapOf(
            CheckIds.BATTERY_LEVEL to check { p, e ->
                val op = p.enum("op", Comparison.entries.toTypedArray(), Comparison.LT)
                val limit = p.int("percent", 20)
                val level = e.device.batteryLevel()
                cmp(op, level.toDouble(), limit.toDouble()) to "battery is $level% (needs ${op.symbol} $limit%)"
            },
            CheckIds.BATTERY_CHARGING to check { p, e ->
                val want = p.bool("charging", true)
                val now = e.device.isCharging()
                (now == want) to (if (now) "the phone is charging" else "the phone is not charging")
            },
            CheckIds.BATTERY_SAVER to check { p, e ->
                val want = p.bool("on", true)
                val now = e.device.isPowerSaver()
                (now == want) to "Battery Saver is ${if (now) "on" else "off"}"
            },
            CheckIds.VARIABLE_COMPARE to check { p, e ->
                val name = p.str("name")
                val op = p.enum("op", Comparison.entries.toTypedArray(), Comparison.EQ)
                val current = e.variables.get(name)
                val left = current?.asText() ?: ""
                val right = p.str("value")
                val ln = current?.asNumber()
                val rn = Value.Text(right).asNumber()
                val result = if (ln != null && rn != null) cmp(op, ln, rn) else op.test(left.compareTo(right))
                result to "$name is \"$left\" (needs ${op.symbol} \"$right\")"
            },
            CheckIds.TIME_WINDOW to check { p, e ->
                val from = p.int("fromHour", 22) * 60 + p.int("fromMinute", 0)
                val to = p.int("toHour", 7) * 60 + p.int("toMinute", 0)
                val now = e.now.hour * 60 + e.now.minute
                val inside = when {
                    from == to -> true
                    from < to -> now in from until to
                    else -> now >= from || now < to
                }
                inside to "it is ${formatClock(e.now.hour, e.now.minute)} (window ${formatClock(from / 60, from % 60)}–${formatClock(to / 60, to % 60)})"
            },
            CheckIds.WEEKDAY to check { p, e ->
                val days = p.ints("days")
                val today = e.now.dayOfWeek.value
                (days.isEmpty() || today in days) to "today is ${e.now.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} (needs ${describeDays(days)})"
            },
            CheckIds.WIFI to check { p, e ->
                val want = p.bool("connected", true)
                val now = e.device.isWifiConnected()
                (now == want) to (if (now) "Wi-Fi is connected" else "Wi-Fi is not connected")
            },
            CheckIds.SCREEN to check { p, e ->
                val want = p.bool("on", true)
                val now = e.device.isScreenOn()
                (now == want) to "the screen is ${if (now) "on" else "off"}"
            },
            CheckIds.RINGER to check { p, e ->
                val want = p.enum("mode", RingerMode.entries.toTypedArray(), RingerMode.SILENT)
                val now = e.device.ringerMode()
                (now == want) to "the ringer is ${ringerWord(now).lowercase()}"
            },
            CheckIds.AIRPLANE to check { p, e ->
                val want = p.bool("on", true)
                val now = e.device.isAirplaneMode()
                (now == want) to "Airplane mode is ${if (now) "on" else "off"}"
            },
        )
    }
}

/** Asks each reader in turn; the first one that knows the key answers. */
class CompositeState(private vararg val readers: StateReader) : StateReader {
    override fun read(key: String): Value? {
        for (r in readers) {
            val v = r.read(key)
            if (v != null) return v
        }
        return null
    }
}
