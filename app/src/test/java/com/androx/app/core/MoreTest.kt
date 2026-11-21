package com.androx.app.core

import com.androx.app.core.engine.BuiltinExecutors
import com.androx.app.core.engine.ConstraintEvaluator
import com.androx.app.core.engine.FlowControl
import com.androx.app.core.engine.FlowEngine
import com.androx.app.core.engine.MapState
import com.androx.app.core.engine.MemoryVariables
import com.androx.app.core.engine.RunStatus
import com.androx.app.core.model.Check
import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.ConstraintNode
import com.androx.app.core.model.Flow
import com.androx.app.core.model.Op
import com.androx.app.core.model.OpIds
import com.androx.app.core.model.Step
import com.androx.app.core.model.params
import com.androx.app.core.value.Value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ExtraChecksTest {
    private val now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC, a Tuesday
    private val device = FakeDevice(battery = 66)
    private val vars = MemoryVariables()

    private fun eval(typeId: String, p: kotlinx.serialization.json.JsonObject, state: Map<String, Value> = emptyMap(), negate: Boolean = false,
                     render: (String) -> String = { it }, trigger: String? = null) =
        ConstraintEvaluator(device, vars, { now }, ZoneOffset.UTC, state = MapState(state))
            .evaluate(ConstraintNode.Rule(Check(typeId, p, negate)), render, trigger)

    @Test
    fun numbersFromState() {
        val s = mapOf("battery.temp_c" to Value.Num(45.5), "storage.free_mb" to Value.Num(800.0), "uptime.minutes" to Value.Num(3.0))
        assertTrue(eval(CheckIds.BATTERY_TEMP, params("op" to "GT", "degrees" to 40), s).passed)
        assertFalse(eval(CheckIds.BATTERY_TEMP, params("op" to "LT", "degrees" to 40), s).passed)
        assertTrue(eval(CheckIds.STORAGE, params("op" to "LT", "megabytes" to 1000), s).passed)
        assertTrue(eval(CheckIds.UPTIME, params("op" to "LT", "minutes" to 10), s).passed)
    }

    @Test
    fun flagsFromState() {
        val s = mapOf("audio.headphones" to Value.Bool(true), "dnd.on" to Value.Bool(false), "device.locked" to Value.Bool(true))
        assertTrue(eval(CheckIds.HEADPHONES, params("connected" to true), s).passed)
        assertTrue(eval(CheckIds.DND, params("on" to false), s).passed)
        assertFalse(eval(CheckIds.LOCKED, params("locked" to false), s).passed)
        assertTrue(eval(CheckIds.LOCKED, params("locked" to false), s, negate = true).passed)
    }

    @Test
    fun chargerAndNetwork() {
        val s = mapOf("battery.charger" to Value.Text("usb"), "net.type" to Value.Text("wifi"), "net.vpn" to Value.Bool(false), "net.metered" to Value.Bool(true))
        assertTrue(eval(CheckIds.CHARGER, params("type" to "usb"), s).passed)
        assertTrue(eval(CheckIds.CHARGER, params("type" to "any"), s).passed)
        assertFalse(eval(CheckIds.CHARGER, params("type" to "wireless"), s).passed)
        assertTrue(eval(CheckIds.NET_STATE, params("kind" to "wifi"), s).passed)
        assertTrue(eval(CheckIds.NET_STATE, params("kind" to "metered"), s).passed)
        assertFalse(eval(CheckIds.NET_STATE, params("kind" to "vpn"), s).passed)
        assertFalse(eval(CheckIds.NET_STATE, params("kind" to "offline"), s).passed)
    }

    @Test
    fun missingStateFailsWithAnExplanation() {
        val e = eval(CheckIds.HEADPHONES, params("connected" to true))
        assertFalse(e.passed)
        assertTrue(e.why.contains("couldn't read"))
    }

    @Test
    fun volumeByStream() {
        val s = mapOf("volume.media" to Value.Num(30.0))
        assertTrue(eval(CheckIds.VOLUME, params("stream" to "media", "op" to "LT", "percent" to 50), s).passed)
        assertFalse(eval(CheckIds.VOLUME, params("stream" to "media", "op" to "GTE", "percent" to 50), s).passed)
    }

    @Test
    fun calendarChecks() {
        assertTrue(eval(CheckIds.MONTH_DAY, params("days" to listOf(14, 15))).passed)
        assertFalse(eval(CheckIds.MONTH_DAY, params("days" to listOf(1))).passed)
        assertTrue(eval(CheckIds.MONTH, params("months" to listOf(11, 12))).passed)
        assertTrue(eval(CheckIds.DATE_RANGE, params("from" to "2023-11-01", "to" to "2023-11-30")).passed)
        assertFalse(eval(CheckIds.DATE_RANGE, params("from" to "2023-11-15")).passed)
        assertTrue(eval(CheckIds.DATE_RANGE, params("to" to "2024-01-01")).passed)
        assertTrue(eval(CheckIds.DATE_RANGE, params()).passed)
    }

    @Test
    fun compareValuesUsesTemplatesAndNumbers() {
        val render = { t: String -> t.replace("{{x}}", "10") }
        assertTrue(eval(CheckIds.COMPARE, params("left" to "{{x}}", "op" to "GT", "right" to "9"), render = render).passed)
        assertTrue(eval(CheckIds.COMPARE, params("left" to "abc", "op" to "EQ", "right" to "abc")).passed)
        assertFalse(eval(CheckIds.COMPARE, params("left" to "a", "op" to "EQ", "right" to "b")).passed)
    }

    @Test
    fun triggeredBy() {
        assertTrue(eval(CheckIds.TRIGGERED_BY, params("type" to "manual"), trigger = "manual").passed)
        assertFalse(eval(CheckIds.TRIGGERED_BY, params("type" to "manual"), trigger = "device.screen").passed)
        assertFalse(eval(CheckIds.TRIGGERED_BY, params("type" to "manual")).passed)
    }

    @Test
    fun flowState() {
        val s = mapOf("flow.a.running" to Value.Bool(true), "flow.a.runs_today" to Value.Num(4.0), "flow.a.last_run_ago_min" to Value.Num(15.0))
        assertTrue(eval(CheckIds.FLOW_STATE, params("flowId" to "a", "kind" to "running"), s).passed)
        assertTrue(eval(CheckIds.FLOW_STATE, params("flowId" to "a", "kind" to "ran_within", "minutes" to 30), s).passed)
        assertFalse(eval(CheckIds.FLOW_STATE, params("flowId" to "a", "kind" to "ran_within", "minutes" to 10), s).passed)
        assertTrue(eval(CheckIds.FLOW_STATE, params("flowId" to "a", "kind" to "runs_today", "op" to "GTE", "count" to 4), s).passed)
    }
}

class DataActionsTest {
    private var now = 1_700_000_000_000L
    private val vars = MemoryVariables()
    private val enabled = HashMap<String, Boolean>()
    private val cancelled = mutableListOf<String>()
    private var n = 0

    private val control = object : FlowControl {
        override fun setEnabled(flowId: String, enabled: Boolean): Boolean { this@DataActionsTest.enabled[flowId] = enabled; return flowId != "missing" }
        override fun cancel(flowId: String): Boolean { cancelled += flowId; return true }
        override fun isRunning(flowId: String) = false
    }

    private fun run(vararg steps: Step): com.androx.app.core.engine.RunRecord {
        val flow = Flow(id = "f", name = "F", steps = steps.toList())
        val engine = FlowEngine(
            FakeDevice(), vars, { null }, BuiltinExecutors.create(null, ZoneOffset.UTC),
            clock = { now }, zone = ZoneOffset.UTC, sleeper = { }, flowControl = control,
        )
        return runBlocking { engine.execute(flow) }
    }

    private fun op(typeId: String, vararg p: Pair<String, Any?>) = Step("s${++n}", Op(typeId, params(*p)))
    private fun text(operation: String, input: String, name: String = "out", vararg extra: Pair<String, Any?>) =
        op(OpIds.TEXT, "operation" to operation, "input" to input, "name" to name, *extra)

    @Test
    fun textOperations() {
        val r = run(
            text("upper", "abc", "a"),
            text("replace", "a-b-c", "b", "find" to "-", "with" to "+"),
            text("regex_match", "order #4821 shipped", "c", "find" to "#(\\d+)", "group" to 1),
            text("substring", "abcdef", "d", "start" to 2, "length" to 3),
            text("length", "hello", "e"),
            text("split", "x, y ,z", "f", "find" to ","),
            text("regex_replace", "a1b22", "g", "find" to "\\d+", "with" to "#"),
            text("contains", "Hello", "h", "find" to "ELL"),
        )
        assertEquals(RunStatus.SUCCESS, r.status)
        assertEquals("ABC", vars.get("a")!!.asText())
        assertEquals("a+b+c", vars.get("b")!!.asText())
        assertEquals("4821", vars.get("c")!!.asText())
        assertEquals("cde", vars.get("d")!!.asText())
        assertEquals(5.0, vars.get("e")!!.asNumber()!!, 0.0)
        assertEquals(3, (vars.get("f") as Value.ListV).items.size)
        assertEquals("a#b#", vars.get("g")!!.asText())
        assertEquals(Value.Bool(true), vars.get("h"))
    }

    @Test
    fun joinAList() {
        vars.set("names", Value.parseJson("""["a","b","c"]""")!!)
        run(text("join", "{{names}}", "j", "find" to " | "))
        assertEquals("a | b | c", vars.get("j")!!.asText())
    }

    @Test
    fun badRegexFailsClearly() {
        val r = run(text("regex_match", "x", "o", "find" to "("))
        assertEquals(RunStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("pattern"))
    }

    @Test
    fun listOperations() {
        val r = run(
            op(OpIds.LIST, "name" to "l", "operation" to "add", "value" to "3"),
            op(OpIds.LIST, "name" to "l", "operation" to "add", "value" to "1"),
            op(OpIds.LIST, "name" to "l", "operation" to "add", "value" to "2"),
            op(OpIds.LIST, "name" to "l", "operation" to "sort"),
            op(OpIds.LIST, "name" to "l", "operation" to "get", "index" to -1).copy(options = com.androx.app.core.model.StepOptions(saveResultTo = "last")),
            op(OpIds.LIST, "name" to "l", "operation" to "remove_at", "index" to 0),
        )
        assertEquals(RunStatus.SUCCESS, r.status)
        assertEquals("3", vars.get("last")!!.asText())
        assertEquals(listOf("2", "3"), (vars.get("l") as Value.ListV).items.map { it.asText() })
        val bad = run(op(OpIds.LIST, "name" to "l", "operation" to "remove_at", "index" to 9))
        assertEquals(RunStatus.FAILED, bad.status)
    }

    @Test
    fun dictionaryOperations() {
        run(
            op(OpIds.DICT, "name" to "d", "operation" to "set", "key" to "a", "value" to "1"),
            op(OpIds.DICT, "name" to "d", "operation" to "set", "key" to "b", "value" to "2"),
            op(OpIds.DICT, "name" to "d", "operation" to "remove", "key" to "a"),
            op(OpIds.DICT, "name" to "d", "operation" to "keys").copy(options = com.androx.app.core.model.StepOptions(saveResultTo = "k")),
        )
        assertEquals(listOf("b"), (vars.get("k") as Value.ListV).items.map { it.asText() })
        assertEquals(1, (vars.get("d") as Value.Dict).entries.size)
    }

    @Test
    fun dateFormatting() {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH)
        run(
            op(OpIds.DATE_FORMAT, "pattern" to "yyyy-MM-dd HH:mm", "name" to "now"),
            op(OpIds.DATE_FORMAT, "pattern" to "yyyy-MM-dd", "offsetMinutes" to 1440, "name" to "tomorrow"),
            op(OpIds.DATE_FORMAT, "pattern" to "EEEE", "name" to "day"),
        )
        assertEquals("2023-11-14 22:13", vars.get("now")!!.asText())
        assertEquals("2023-11-15", vars.get("tomorrow")!!.asText())
        assertEquals("Tuesday", vars.get("day")!!.asText())
        val bad = run(op(OpIds.DATE_FORMAT, "pattern" to "qqqqqqqq"))
        assertEquals(RunStatus.FAILED, bad.status)
    }

    @Test
    fun randomValues() {
        run(
            op(OpIds.RANDOM, "mode" to "number", "min" to 5, "max" to 5, "name" to "n"),
            op(OpIds.RANDOM, "mode" to "uuid", "name" to "u"),
        )
        assertEquals(5.0, vars.get("n")!!.asNumber()!!, 0.0)
        assertEquals(36, vars.get("u")!!.asText().length)
        assertEquals(RunStatus.FAILED, run(op(OpIds.RANDOM, "mode" to "number", "min" to 9, "max" to 1)).status)
    }

    @Test
    fun manageOtherFlows() {
        val r = run(
            op(OpIds.FLOW_ENABLE, "flowId" to "x", "state" to "off"),
            op(OpIds.FLOW_CANCEL, "flowId" to "y"),
        )
        assertEquals(RunStatus.SUCCESS, r.status)
        assertEquals(false, enabled["x"])
        assertEquals(listOf("y"), cancelled)
        assertEquals(RunStatus.FAILED, run(op(OpIds.FLOW_ENABLE, "flowId" to "missing", "state" to "on")).status)
    }
}
