package com.androx.app.core

import com.androx.app.core.engine.CheckEnv
import com.androx.app.core.engine.ConstraintEvaluator
import com.androx.app.core.engine.MemoryVariables
import com.androx.app.core.engine.TriggerInfo
import com.androx.app.core.model.Check
import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.ConstraintNode
import com.androx.app.core.model.Event
import com.androx.app.core.model.Flow
import com.androx.app.core.model.TriggerEntry
import com.androx.app.core.model.TriggerIds
import com.androx.app.core.model.params
import com.androx.app.core.registry.Availability
import com.androx.app.core.registry.BuiltinSpecs
import com.androx.app.core.registry.summaryOf
import com.androx.app.core.trigger.ListenerPlan
import com.androx.app.core.trigger.Outcome
import com.androx.app.core.trigger.Schedules
import com.androx.app.core.trigger.TriggerHub
import com.androx.app.core.trigger.TriggerLogEntry
import com.androx.app.core.value.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class TriggerTest {
    private var now = 1_700_000_000_000L
    private val device = FakeDevice(battery = 50)
    private val vars = MemoryVariables()
    private var paused = false
    private val flows = mutableListOf<Flow>()
    private val fired = mutableListOf<Pair<String, TriggerInfo>>()
    private val log = mutableListOf<TriggerLogEntry>()

    private val evaluator get() = ConstraintEvaluator(device, vars, { now }, ZoneOffset.UTC)

    private fun hub() = TriggerHub(
        clock = { now },
        flows = { flows.toList() },
        env = { CheckEnv(device, vars, ZonedDateTime.now(ZoneOffset.UTC)) },
        constraints = { node, _ -> evaluator.evaluate(node) },
        isPaused = { paused },
        dispatch = { f, _, info -> fired += f.id to info },
        log = { log += it },
    )

    private fun entry(id: String, typeId: String, p: kotlinx.serialization.json.JsonObject = params(), cooldown: Int = 0, hold: Int = 0, constraints: ConstraintNode? = null) =
        TriggerEntry(id, Event(typeId, p), constraints, cooldown, hold)

    private fun addFlow(id: String, vararg triggers: TriggerEntry, enabled: Boolean = true, deleted: Boolean = false) =
        Flow(id = id, name = "Flow $id", enabled = enabled, triggers = triggers.toList(), deletedAt = if (deleted) 1L else null).also { flows += it }

    private fun screen(state: String) = mapOf("state" to Value.Text(state))

    @Test
    fun matchingEventFiresAndOthersAreIgnored() {
        addFlow("a", entry("t1", TriggerIds.SCREEN, params("state" to "on")))
        val h = hub()
        assertEquals(Outcome.NOT_MATCHING, h.onEvent(TriggerIds.SCREEN, screen("off")).single().outcome)
        assertTrue(fired.isEmpty())
        assertEquals(Outcome.FIRED, h.onEvent(TriggerIds.SCREEN, screen("on")).single().outcome)
        assertEquals("a", fired.single().first)
        assertEquals("on", fired.single().second.data["state"]!!.asText())
        assertEquals("t1", fired.single().second.triggerId)
    }

    @Test
    fun disabledAndTrashedFlowsAndTriggersAreIgnored() {
        addFlow("off", entry("t1", TriggerIds.SCREEN), enabled = false)
        addFlow("trash", entry("t2", TriggerIds.SCREEN), deleted = true)
        addFlow("muted", entry("t3", TriggerIds.SCREEN).copy(enabled = false))
        assertTrue(hub().onEvent(TriggerIds.SCREEN, screen("on")).isEmpty())
        assertTrue(fired.isEmpty())
    }

    @Test
    fun pauseBlocksEverything() {
        addFlow("a", entry("t1", TriggerIds.SCREEN))
        paused = true
        assertEquals(Outcome.PAUSED, hub().onEvent(TriggerIds.SCREEN, screen("on")).single().outcome)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun cooldownSpacesOutFirings() {
        addFlow("a", entry("t1", TriggerIds.SCREEN, cooldown = 60))
        val h = hub()
        assertEquals(Outcome.FIRED, h.onEvent(TriggerIds.SCREEN, screen("on")).single().outcome)
        now += 10_000
        val second = h.onEvent(TriggerIds.SCREEN, screen("on")).single()
        assertEquals(Outcome.COOLDOWN, second.outcome)
        assertTrue(second.reason.contains("50"))
        now += 51_000
        assertEquals(Outcome.FIRED, h.onEvent(TriggerIds.SCREEN, screen("on")).single().outcome)
        assertEquals(2, fired.size)
    }

    @Test
    fun triggerConstraintsGateTheFiring() {
        val lowOnly = ConstraintNode.Rule(Check(CheckIds.BATTERY_LEVEL, params("op" to "LT", "percent" to 20)))
        addFlow("a", entry("t1", TriggerIds.SCREEN, constraints = lowOnly))
        val h = hub()
        assertEquals(Outcome.CONSTRAINT, h.onEvent(TriggerIds.SCREEN, screen("on")).single().outcome)
        device.battery = 10
        assertEquals(Outcome.FIRED, h.onEvent(TriggerIds.SCREEN, screen("on")).single().outcome)
    }

    @Test
    fun batteryCrossingOnlyOnTheCrossing() {
        addFlow("a", entry("t1", TriggerIds.BATTERY_CROSSING, params("direction" to "below", "percent" to 20)))
        val h = hub()
        fun level(now: Int, previous: Int) = mapOf("level" to Value.Num(now.toDouble()), "previous" to Value.Num(previous.toDouble()))
        assertEquals(Outcome.NOT_MATCHING, h.onEvent(TriggerIds.BATTERY_CROSSING, level(30, 31)).single().outcome)
        assertEquals(Outcome.FIRED, h.onEvent(TriggerIds.BATTERY_CROSSING, level(19, 20)).single().outcome)
        assertEquals(Outcome.NOT_MATCHING, h.onEvent(TriggerIds.BATTERY_CROSSING, level(18, 19)).single().outcome)
    }

    @Test
    fun holdFiresAfterTheTimeIfTheConditionStillHolds() {
        addFlow("a", entry("t1", TriggerIds.BATTERY_CROSSING, params("direction" to "below", "percent" to 20), hold = 5))
        val h = hub()
        device.battery = 19
        val d = h.onEvent(TriggerIds.BATTERY_CROSSING, mapOf("level" to Value.Num(19.0), "previous" to Value.Num(20.0))).single()
        assertEquals(Outcome.HOLDING, d.outcome)
        assertEquals(now + 5 * 60_000L, h.nextDeadline())
        now += 4 * 60_000L
        assertTrue(h.tick().isEmpty())
        now += 60_000L
        assertEquals(Outcome.FIRED, h.tick().single().outcome)
        assertEquals(1, fired.size)
        assertNull(h.nextDeadline())
    }

    @Test
    fun holdIsDroppedWhenTheConditionEndsFirst() {
        addFlow("a", entry("t1", TriggerIds.BATTERY_CROSSING, params("direction" to "below", "percent" to 20), hold = 5))
        val h = hub()
        device.battery = 19
        h.onEvent(TriggerIds.BATTERY_CROSSING, mapOf("level" to Value.Num(19.0), "previous" to Value.Num(20.0)))
        device.battery = 50
        now += 5 * 60_000L
        assertEquals(Outcome.HOLD_BROKEN, h.tick().single().outcome)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun aMatchingEventThatEndsTheConditionCancelsTheHold() {
        addFlow("a", entry("t1", TriggerIds.POWER, params("state" to "connected"), hold = 3))
        val h = hub()
        h.onEvent(TriggerIds.POWER, screen("connected"))
        assertEquals(Outcome.HOLD_BROKEN, h.onEvent(TriggerIds.POWER, screen("disconnected")).single().outcome)
        assertNull(h.nextDeadline())
    }

    @Test
    fun everyEventIsLoggedEvenWhenNothingListens() {
        hub().onEvent(TriggerIds.DND, mapOf("state" to Value.Text("on")))
        assertEquals(1, log.size)
        assertEquals(TriggerIds.DND, log[0].typeId)
        assertEquals("on", log[0].data["state"])
        assertTrue(log[0].decisions.isEmpty())
    }

    @Test
    fun severalTriggersAreAnyOf() {
        addFlow("a", entry("t1", TriggerIds.SCREEN), entry("t2", TriggerIds.POWER))
        val h = hub()
        h.onEvent(TriggerIds.POWER, screen("connected"))
        assertEquals(listOf("a"), fired.map { it.first })
        assertEquals("t2", fired[0].second.triggerId)
    }

    @Test
    fun listenerPlanNeedsOnlyWhatEnabledFlowsUse() {
        val registry = BuiltinSpecs.triggerRegistry
        val timeOnly = ListenerPlan.compute(listOf(addFlow("t", entry("t1", TriggerIds.SCHEDULE), entry("t2", TriggerIds.MANUAL), entry("t3", TriggerIds.BOOT))), registry)
        assertFalse(timeOnly.needed)
        flows.clear()
        val a = addFlow("a", entry("t1", TriggerIds.SCREEN), entry("t2", TriggerIds.BATTERY_CROSSING))
        val off = addFlow("b", entry("t3", TriggerIds.NETWORK), enabled = false)
        val plan = ListenerPlan.compute(listOf(a, off), registry)
        assertTrue(plan.needed)
        assertEquals(setOf("screen", "battery"), plan.sources)
        assertEquals("Screen on or off", plan.needs.first { it.source == "screen" }.triggerName)
    }

    // ---- schedules ----

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun scheduleFindsTheNextMatchingDay() {
        val after = utc(2026, 11, 21, 10, 0) // a Saturday
        val daily = Event(TriggerIds.SCHEDULE, params("hour" to 8, "minute" to 0))
        assertEquals(utc(2026, 11, 22, 8, 0), Schedules.nextFire(daily, after, ZoneOffset.UTC))
        val mondays = Event(TriggerIds.SCHEDULE, params("hour" to 8, "minute" to 0, "days" to listOf(1)))
        assertEquals(utc(2026, 11, 23, 8, 0), Schedules.nextFire(mondays, after, ZoneOffset.UTC))
        val laterToday = Event(TriggerIds.SCHEDULE, params("hour" to 18, "minute" to 30))
        assertEquals(utc(2026, 11, 21, 18, 30), Schedules.nextFire(laterToday, after, ZoneOffset.UTC))
    }

    @Test
    fun intervalKeepsItsRhythm() {
        val start = utc(2026, 11, 21, 9, 0)
        val e = Event(TriggerIds.INTERVAL, params("minutes" to 90, "startAt" to start))
        assertEquals(start + 90 * 60_000L, Schedules.nextFire(e, start, ZoneOffset.UTC))
        assertEquals(start + 180 * 60_000L, Schedules.nextFire(e, start + 100 * 60_000L, ZoneOffset.UTC))
    }

    @Test
    fun onceExpires() {
        val at = utc(2026, 11, 21, 12, 0)
        val e = Event(TriggerIds.ONCE, params("atMillis" to at))
        assertEquals(at, Schedules.nextFire(e, at - 1, ZoneOffset.UTC))
        assertNull(Schedules.nextFire(e, at, ZoneOffset.UTC))
    }

    @Test
    fun monthlyClampsToShortMonths() {
        val e = Event(TriggerIds.MONTHLY, params("day" to 31, "hour" to 8, "minute" to 0))
        assertEquals(utc(2027, 2, 28, 8, 0), Schedules.nextFire(e, utc(2027, 2, 10, 0, 0), ZoneOffset.UTC))
        assertEquals(utc(2027, 3, 31, 8, 0), Schedules.nextFire(e, utc(2027, 2, 28, 9, 0), ZoneOffset.UTC))
    }

    @Test
    fun nonTimedTriggersHaveNoNextFire() {
        assertNull(Schedules.nextFire(Event(TriggerIds.SCREEN), 0, ZoneOffset.UTC))
        assertNull(Schedules.nextFire(Event(TriggerIds.MANUAL), 0, ZoneOffset.UTC))
    }

    @Test
    fun nextAcrossFlowsPicksTheEarliestEnabledOne() {
        val after = utc(2026, 11, 21, 10, 0)
        val a = addFlow("a", entry("t1", TriggerIds.SCHEDULE, params("hour" to 20, "minute" to 0)))
        val b = addFlow("b", entry("t2", TriggerIds.SCHEDULE, params("hour" to 12, "minute" to 0)))
        val c = addFlow("c", entry("t3", TriggerIds.SCHEDULE, params("hour" to 11, "minute" to 0)), enabled = false)
        val next = Schedules.next(listOf(a, b, c), after, ZoneOffset.UTC)!!
        assertEquals("b", next.first.id)
        assertEquals(utc(2026, 11, 21, 12, 0), next.third)
    }

    // ---- catalogue invariants ----

    @Test
    fun everyReadyTriggerIsTimedInternalOrHasASource() {
        val internal = setOf(
            TriggerIds.MANUAL, TriggerIds.BOOT, TriggerIds.TIME_CHANGED, TriggerIds.INTENT, TriggerIds.SHARE, TriggerIds.FLOW_FINISHED,
            TriggerIds.FLOW_ENABLED, TriggerIds.VARIABLE_CHANGED, TriggerIds.CALLED, TriggerIds.APP_OPENED,
        )
        for (spec in BuiltinSpecs.triggerRegistry.all.filter { it.availability == Availability.READY }) {
            val ok = Schedules.isTimed(spec.id) || spec.id in internal || spec.id in com.androx.app.core.trigger.EventMatchers.sources
            assertTrue(spec.id, ok)
            assertEquals(spec.id, spec.timed, Schedules.isTimed(spec.id))
        }
    }

    @Test
    fun listenerTriggersAreLabelledAndOthersAreNot() {
        for (spec in BuiltinSpecs.triggerRegistry.all.filter { it.availability == Availability.READY }) {
            val needs = spec.id in com.androx.app.core.trigger.EventMatchers.sources
            assertEquals(spec.id, needs, spec.reliability == com.androx.app.core.registry.Reliability.NEEDS_LISTENER)
        }
    }

    @Test
    fun everyReadySpecHasASummaryAndEverySlotHasANote() {
        fun check(id: String, availability: Availability, note: String, summary: String) {
            if (availability == Availability.READY) assertTrue("$id summary", summary.isNotBlank()) else assertTrue("$id note", note.isNotBlank())
        }
        BuiltinSpecs.triggerRegistry.all.forEach { check(it.id, it.availability, it.note, it.summaryOf(params())) }
        BuiltinSpecs.constraintRegistry.all.forEach { check(it.id, it.availability, it.note, it.summaryOf(params())) }
        BuiltinSpecs.actionRegistry.all.forEach { check(it.id, it.availability, it.note, it.summaryOf(params())) }
    }
}
