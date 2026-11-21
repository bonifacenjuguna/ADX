package com.androx.app.core

import com.androx.app.core.engine.BuiltinExecutors
import com.androx.app.core.engine.ActionExecutor
import com.androx.app.core.engine.ConstraintEvaluator
import com.androx.app.core.engine.ExecuteOptions
import com.androx.app.core.engine.FlowEngine
import com.androx.app.core.engine.FlowException
import com.androx.app.core.engine.MemoryVariables
import com.androx.app.core.engine.RunRecord
import com.androx.app.core.engine.RunStatus
import com.androx.app.core.engine.Simulation
import com.androx.app.core.engine.StepStatus
import com.androx.app.core.engine.TriggerInfo
import com.androx.app.core.engine.OverlayVariables
import com.androx.app.core.engine.executor
import com.androx.app.core.model.Blocks
import com.androx.app.core.model.Check
import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.ConstraintNode
import com.androx.app.core.model.ErrorPolicy
import com.androx.app.core.model.Flow
import com.androx.app.core.model.GroupMode
import com.androx.app.core.model.Op
import com.androx.app.core.model.OpIds
import com.androx.app.core.model.RunOptions
import com.androx.app.core.model.Step
import com.androx.app.core.model.StepOptions
import com.androx.app.core.model.params
import com.androx.app.core.registry.BuiltinSpecs
import com.androx.app.core.registry.summaryOf
import com.androx.app.core.value.Value
import com.androx.app.engine.DeviceBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class FakeDevice(var battery: Int = 66, var charging: Boolean = false) : DeviceBridge {
    val notifications = mutableListOf<Pair<String, String>>()
    val toasts = mutableListOf<String>()
    override fun batteryLevel() = battery
    override fun isCharging() = charging
    override fun showNotification(title: String, message: String) { notifications += title to message }
    override fun showToast(message: String) { toasts += message }
}

class EngineTest {
    private var now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
    private val sleeps = mutableListOf<Long>()
    private val device = FakeDevice()
    private val vars = MemoryVariables()
    private val flows = HashMap<String, Flow>()
    private val failures = HashMap<String, Int>()
    private var ids = 0

    private fun executors(): Map<String, ActionExecutor> = BuiltinExecutors.create(null) + mapOf(
        "test.fail" to executor { throw FlowException("boom") },
        "test.flaky" to executor { c ->
            val n = (failures[c.step.id] ?: 0) + 1
            failures[c.step.id] = n
            if (n < 3) throw FlowException("flaky $n")
            Value.Text("ok")
        },
    )

    private fun engine() = FlowEngine(
        device, vars, { flows[it] }, executors(),
        describe = { op -> BuiltinSpecs.actionRegistry[op.typeId]?.summaryOf(op.params) ?: op.typeId },
        clock = { now }, zone = ZoneOffset.UTC,
        sleeper = { ms -> sleeps += ms; now += ms },
    )

    private fun step(typeId: String, p: kotlinx.serialization.json.JsonObject = params(), options: StepOptions = StepOptions(),
                     condition: ConstraintNode? = null, blocks: Map<String, List<Step>> = emptyMap()) =
        Step("s${++ids}", Op(typeId, p), options, condition, blocks)

    private fun setVar(name: String, value: String, mode: String = "SET") =
        step(OpIds.VARIABLE_SET, params("name" to name, "value" to value, "mode" to mode))

    private fun flow(vararg steps: Step, run: RunOptions = RunOptions(), constraints: ConstraintNode? = null, id: String = "f1") =
        Flow(id = id, name = "Test", steps = steps.toList(), run = run, constraints = constraints).also { flows[id] = it }

    private fun run(f: Flow, options: ExecuteOptions = ExecuteOptions(), trigger: TriggerInfo = TriggerInfo()): RunRecord =
        runBlocking { engine().execute(f, trigger, options) }

    private fun rule(typeId: String, p: kotlinx.serialization.json.JsonObject, negate: Boolean = false) =
        ConstraintNode.Rule(Check(typeId, p, negate))

    @Test
    fun runsStepsAndRendersTemplates() {
        val r = run(flow(step(OpIds.NOTIFY, params("title" to "Hi", "message" to "Battery {{battery}} on {{flow}}"))))
        assertEquals(RunStatus.SUCCESS, r.status)
        assertEquals("Hi" to "Battery 66 on Test", device.notifications.single())
        assertEquals(1, r.steps.size)
    }

    @Test
    fun typedVariableMaths() {
        val r = run(flow(setVar("n", "5"), setVar("n", "2.5", "ADD"), setVar("n", "2", "MULTIPLY"), setVar("m", "{{n}}")))
        assertEquals(RunStatus.SUCCESS, r.status)
        assertEquals(15.0, vars.get("n")!!.asNumber()!!, 0.0)
        assertEquals(Value.Num(15.0), vars.get("m")) // a lone {{value}} keeps its type
    }

    @Test
    fun addingToNonNumberFails() {
        val r = run(flow(setVar("t", "abc"), setVar("t", "1", "ADD")))
        assertEquals(RunStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("not a number"))
    }

    @Test
    fun appendToList() {
        val seed = step(OpIds.VARIABLE_SET, params("name" to "l", "value" to "[1,2]", "type" to "list"))
        run(flow(seed, setVar("l", "3", "APPEND")))
        assertEquals(3, (vars.get("l") as Value.ListV).items.size)
    }

    @Test
    fun ifElseUsesConstraint() {
        val gate = rule(CheckIds.BATTERY_LEVEL, params("op" to "LT", "percent" to 20))
        val f = flow(step(OpIds.IF, condition = gate, blocks = mapOf(
            Blocks.THEN to listOf(setVar("r", "low")), Blocks.ELSE to listOf(setVar("r", "ok")))))
        run(f)
        assertEquals("ok", vars.get("r")!!.asText())
        device.battery = 10
        run(f)
        assertEquals("low", vars.get("r")!!.asText())
    }

    @Test
    fun repeatRunsBodyAndIndexIsTemplated() {
        val f = flow(
            setVar("log", ""),
            step(OpIds.REPEAT, params("times" to 3), blocks = mapOf(Blocks.BODY to listOf(setVar("log", "{{index}},", "APPEND")))),
        )
        assertEquals(RunStatus.SUCCESS, run(f).status)
        assertEquals("1,2,3,", vars.get("log")!!.asText())
    }

    @Test
    fun breakLeavesTheLoop() {
        val f = flow(
            setVar("log", ""),
            step(OpIds.REPEAT, params("times" to 5), blocks = mapOf(Blocks.BODY to listOf(
                setVar("log", "{{index}}", "APPEND"),
                step(OpIds.IF, condition = rule(CheckIds.BATTERY_LEVEL, params("op" to "LTE", "percent" to 100)),
                    blocks = mapOf(Blocks.THEN to listOf(step(OpIds.BREAK)))),
            ))),
        )
        assertEquals(RunStatus.SUCCESS, run(f).status)
        assertEquals("1", vars.get("log")!!.asText())
    }

    @Test
    fun continueSkipsRestOfRound() {
        val f = flow(
            setVar("log", ""),
            step(OpIds.REPEAT, params("times" to 3), blocks = mapOf(Blocks.BODY to listOf(
                step(OpIds.CONTINUE),
                setVar("log", "never", "APPEND"),
            ))),
        )
        assertEquals(RunStatus.SUCCESS, run(f).status)
        assertEquals("", vars.get("log")!!.asText())
    }

    @Test
    fun forEachOverListAndDictionary() {
        vars.set("names", Value.parseJson("""["a","b","c"]""")!!)
        vars.set("cfg", Value.parseJson("""{"x":1,"y":2}""")!!)
        val f = flow(
            setVar("out", ""),
            step(OpIds.FOR_EACH, params("source" to "{{names}}"), blocks = mapOf(Blocks.BODY to listOf(setVar("out", "{{index}}{{item}} ", "APPEND")))),
            step(OpIds.FOR_EACH, params("source" to "{{cfg}}"), blocks = mapOf(Blocks.BODY to listOf(setVar("out", "{{key}}={{item}} ", "APPEND")))),
            step(OpIds.FOR_EACH, params("source" to "one\ntwo"), blocks = mapOf(Blocks.BODY to listOf(setVar("out", "{{item}};", "APPEND")))),
        )
        assertEquals(RunStatus.SUCCESS, run(f).status)
        assertEquals("1a 2b 3c x=1 y=2 one;two;", vars.get("out")!!.asText())
    }

    @Test
    fun whileWithBreakAndLoopLimit() {
        val counting = flow(
            setVar("i", "0"),
            step(OpIds.WHILE, condition = rule(CheckIds.VARIABLE_COMPARE, params("name" to "i", "op" to "LT", "value" to "3")),
                blocks = mapOf(Blocks.BODY to listOf(setVar("i", "1", "ADD")))),
        )
        assertEquals(RunStatus.SUCCESS, run(counting).status)
        assertEquals(3.0, vars.get("i")!!.asNumber()!!, 0.0)

        val forever = flow(
            step(OpIds.WHILE, params("maxIterations" to 50), condition = rule(CheckIds.BATTERY_LEVEL, params("op" to "GT", "percent" to 0)),
                blocks = mapOf(Blocks.BODY to listOf(setVar("x", "1")))), id = "f2",
        )
        val r = run(forever)
        assertEquals(RunStatus.LIMIT, r.status)
        assertTrue(r.message!!.contains("loop limit"))
    }

    @Test
    fun stepBudgetStopsRunawayRuns() {
        val f = flow(
            step(OpIds.WHILE, condition = rule(CheckIds.BATTERY_LEVEL, params("op" to "GT", "percent" to 0)),
                blocks = mapOf(Blocks.BODY to listOf(setVar("x", "1")))),
            run = RunOptions(stepBudget = 100),
        )
        val r = run(f)
        assertEquals(RunStatus.LIMIT, r.status)
        assertTrue(r.stepCount > 100)
    }

    @Test
    fun tryCatchSetsErrorAndFinallyAlwaysRuns() {
        val f = flow(step(OpIds.TRY, blocks = mapOf(
            Blocks.TRY to listOf(step("test.fail")),
            Blocks.CATCH to listOf(setVar("caught", "{{error.message}}")),
            Blocks.FINALLY to listOf(setVar("done", "yes")),
        )))
        assertEquals(RunStatus.SUCCESS, run(f).status)
        assertEquals("boom", vars.get("caught")!!.asText())
        assertEquals("yes", vars.get("done")!!.asText())
    }

    @Test
    fun failureStopsTheFlowUnlessContinueOnError() {
        val stop = run(flow(step("test.fail"), setVar("after", "1")))
        assertEquals(RunStatus.FAILED, stop.status)
        assertEquals("boom", stop.message)
        assertTrue(vars.get("after") == null)

        val cont = run(flow(step("test.fail", options = StepOptions(continueOnError = true)), setVar("after", "1"), id = "f2"))
        assertEquals(RunStatus.SUCCESS, cont.status)
        assertEquals(StepStatus.ERROR_IGNORED, cont.steps[0].status)
        assertEquals("1", vars.get("after")!!.asText())

        val policy = run(flow(step("test.fail"), setVar("p", "1"), run = RunOptions(onError = ErrorPolicy.CONTINUE), id = "f3"))
        assertEquals(RunStatus.SUCCESS, policy.status)
    }

    @Test
    fun retriesThenSucceeds() {
        val r = run(flow(step("test.flaky", options = StepOptions(retries = 2, retryDelayMs = 100))))
        assertEquals(RunStatus.SUCCESS, r.status)
        assertEquals(listOf(100L, 100L), sleeps)
        val gone = run(flow(step("test.flaky", options = StepOptions(retries = 1)), id = "f2").copy())
        assertEquals(RunStatus.FAILED, gone.status)
    }

    @Test
    fun disabledAndGatedStepsDoNotRun() {
        val never = rule(CheckIds.BATTERY_LEVEL, params("op" to "LT", "percent" to 10))
        val r = run(flow(
            setVar("a", "1").copy(options = StepOptions(disabled = true)),
            setVar("b", "1").copy(condition = never),
            setVar("c", "1"),
        ))
        assertEquals(RunStatus.SUCCESS, r.status)
        assertTrue(vars.get("a") == null && vars.get("b") == null)
        assertEquals("1", vars.get("c")!!.asText())
        assertEquals(listOf(StepStatus.DISABLED, StepStatus.SKIPPED, StepStatus.OK), r.steps.map { it.status })
    }

    @Test
    fun saveResultToVariable() {
        run(flow(step(OpIds.CALC, params("expression" to "{{battery}} / 2")).copy(options = StepOptions(saveResultTo = "half"))))
        assertEquals(33.0, vars.get("half")!!.asNumber()!!, 0.0)
    }

    @Test
    fun waitBudgetIsEnforced() {
        val ok = run(flow(step(OpIds.WAIT, params("seconds" to 10)), step(OpIds.WAIT, params("seconds" to 10))))
        assertEquals(RunStatus.SUCCESS, ok.status)
        assertEquals(listOf(10_000L, 10_000L), sleeps)
        val tooMuch = run(flow(
            step(OpIds.WAIT, params("seconds" to 10)), step(OpIds.WAIT, params("seconds" to 10)),
            step(OpIds.WAIT, params("seconds" to 10)), step(OpIds.WAIT, params("seconds" to 10)), id = "f2",
        ))
        assertEquals(RunStatus.FAILED, tooMuch.status)
        assertTrue(tooMuch.message!!.contains("wait at most 30"))
    }

    @Test
    fun waitUntilSeesStateChangeOrTimesOut() {
        val chargingGate = rule(CheckIds.BATTERY_CHARGING, params("charging" to true))
        val times = run(flow(step(OpIds.WAIT_UNTIL, params("timeoutSec" to 2), condition = chargingGate)))
        assertEquals(RunStatus.FAILED, times.status)
        assertTrue(times.message!!.contains("Timed out"))
        device.charging = true
        val ok = run(flow(step(OpIds.WAIT_UNTIL, params("timeoutSec" to 2), condition = chargingGate), id = "f2"))
        assertEquals(RunStatus.SUCCESS, ok.status)
    }

    @Test
    fun runTimeoutStopsTheRun() {
        val f = flow(
            step(OpIds.WAIT, params("seconds" to 10)), step(OpIds.WAIT, params("seconds" to 10)), setVar("x", "1"),
            run = RunOptions(timeoutSec = 5, waitBudgetSec = 600),
        )
        val r = run(f)
        assertEquals(RunStatus.LIMIT, r.status)
        assertTrue(vars.get("x") == null)
    }

    @Test
    fun stopEndsTheRunAsSuccess() {
        val r = run(flow(setVar("a", "1"), step(OpIds.STOP, params("message" to "enough")), setVar("b", "1")))
        assertEquals(RunStatus.SUCCESS, r.status)
        assertEquals("enough", r.message)
        assertTrue(vars.get("b") == null)
    }

    @Test
    fun flowConstraintsSkipWithAReason() {
        val f = flow(setVar("a", "1"), constraints = rule(CheckIds.BATTERY_CHARGING, params("charging" to true)))
        val r = run(f)
        assertEquals(RunStatus.SKIPPED, r.status)
        assertTrue(r.message!!.contains("not charging"))
        assertEquals(1, r.constraints.size)
        assertFalse(r.constraints[0].passed)
        assertEquals(RunStatus.SUCCESS, run(f, ExecuteOptions(ignoreConstraints = true)).status)
    }

    @Test
    fun runFlowPassesInputsAndReturnsAValue() {
        flow(step(OpIds.RETURN, params("value" to "{{input.n}}-done")), id = "child")
        val parent = flow(
            step(OpIds.RUN_FLOW, params("flowId" to "child", "inputs" to params("n" to "{{battery}}"))).copy(options = StepOptions(saveResultTo = "res")),
            id = "parent",
        )
        assertEquals(RunStatus.SUCCESS, run(parent).status)
        assertEquals("66-done", vars.get("res")!!.asText())
    }

    @Test
    fun flowsCannotCallThemselvesOrMissingFlows() {
        val loop = flow(step(OpIds.RUN_FLOW, params("flowId" to "f1")))
        val r = run(loop)
        assertEquals(RunStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("call itself"))
        val missing = run(flow(step(OpIds.RUN_FLOW, params("flowId" to "nope")), id = "f2"))
        assertTrue(missing.message!!.contains("no longer exists"))
    }

    @Test
    fun simulatorNeverActsOrChangesRealVariables() {
        vars.set("keep", Value.Text("real"))
        val fake = FakeDevice(battery = 5)
        val overlay = OverlayVariables(vars)
        val f = flow(
            step(OpIds.NOTIFY, params("title" to "T", "message" to "{{battery}}")),
            setVar("keep", "changed"),
            setVar("seen", "{{keep}}"),
            step(OpIds.WAIT, params("seconds" to 10)),
        )
        val r = run(f, ExecuteOptions(simulation = Simulation(fake, overlay)))
        assertEquals(RunStatus.SUCCESS, r.status)
        assertTrue(r.simulated)
        assertTrue(device.notifications.isEmpty() && fake.notifications.isEmpty())
        assertEquals("real", vars.get("keep")!!.asText())
        assertEquals("changed", overlay.get("seen")!!.asText())
        assertTrue(sleeps.isEmpty())
        assertEquals(StepStatus.SIMULATED, r.steps[0].status)
    }

    @Test
    fun runFromHereAndUntilHere() {
        val a = setVar("a", "1"); val b = setVar("b", "1"); val c = setVar("c", "1")
        val f = flow(a, b, c)
        run(f, ExecuteOptions(fromStepId = b.id))
        assertTrue(vars.get("a") == null && vars.get("b") != null && vars.get("c") != null)
        val vars2 = vars
        listOf("a", "b", "c").forEach { vars2.remove(it) }
        run(f, ExecuteOptions(untilStepId = b.id))
        assertTrue(vars.get("a") != null && vars.get("b") != null && vars.get("c") == null)
    }

    @Test
    fun triggerDataIsAvailableAsValues() {
        val f = flow(setVar("who", "{{sms.sender}} via {{trigger.type}}"))
        run(f, trigger = TriggerInfo("sms.received", "t1", mapOf("sms" to Value.parseJson("""{"sender":"Mum"}""")!!)))
        assertEquals("Mum via sms.received", vars.get("who")!!.asText())
    }

    @Test
    fun unknownActionFailsClearly() {
        val r = run(flow(step("future.thing")))
        assertEquals(RunStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("isn't available"))
    }

    @Test
    fun codeFlowRunsItsScriptThroughTheScriptExecutor() {
        val f = Flow(id = "c", name = "Code", editor = com.androx.app.core.model.FlowEditor.CODE, script = "androx.log('x')")
        val r = run(f) // no script runner is configured in tests
        assertEquals(RunStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("Scripts aren't available"))
    }

    @Test
    fun everyRegisteredActionHasAnExecutorOrIsControlFlow() {
        val ex = BuiltinExecutors.create(null)
        for (spec in BuiltinSpecs.actionRegistry.all.filter { it.availability == com.androx.app.core.registry.Availability.READY }) {
            assertTrue(spec.id, spec.id in ex || spec.id in FlowEngine.CONTROL_OPS)
        }
    }

    @Test
    fun constraintGroupsAndNegation() {
        val ev = ConstraintEvaluator(device, vars, { now }, ZoneOffset.UTC)
        val low = rule(CheckIds.BATTERY_LEVEL, params("op" to "LT", "percent" to 20)) // false at 66
        val charging = rule(CheckIds.BATTERY_CHARGING, params("charging" to false))   // true
        assertTrue(ev.evaluate(ConstraintNode.Group(GroupMode.ANY, listOf(low, charging))).passed)
        assertFalse(ev.evaluate(ConstraintNode.Group(GroupMode.ALL, listOf(low, charging))).passed)
        assertTrue(ev.evaluate(ConstraintNode.Group(GroupMode.NONE, listOf(low))).passed)
        assertFalse(ev.evaluate(ConstraintNode.Group(GroupMode.NONE, listOf(charging))).passed)
        assertTrue(ev.evaluate(rule(CheckIds.BATTERY_LEVEL, params("op" to "LT", "percent" to 20), negate = true)).passed)
        assertTrue(ev.evaluate(null).passed)
        assertTrue(ev.evaluate(ConstraintNode.Group(GroupMode.ALL, emptyList())).passed)
        val unknown = ev.evaluate(rule("future.check", params()))
        assertFalse(unknown.passed)
    }

    @Test
    fun timeWindowWrapsPastMidnight() {
        // now = 22:13 UTC
        val ev = ConstraintEvaluator(device, vars, { now }, ZoneOffset.UTC)
        val night = rule(CheckIds.TIME_WINDOW, params("fromHour" to 22, "fromMinute" to 0, "toHour" to 7, "toMinute" to 0))
        val day = rule(CheckIds.TIME_WINDOW, params("fromHour" to 9, "fromMinute" to 0, "toHour" to 17, "toMinute" to 0))
        assertTrue(ev.evaluate(night).passed)
        assertFalse(ev.evaluate(day).passed)
    }

    @Test
    fun variableCompareIsNumericWhenBothAreNumbers() {
        val ev = ConstraintEvaluator(device, vars, { now }, ZoneOffset.UTC)
        vars.set("n", Value.Text("10"))
        assertTrue(ev.evaluate(rule(CheckIds.VARIABLE_COMPARE, params("name" to "n", "op" to "GT", "value" to "9"))).passed)
        vars.set("s", Value.Text("apple"))
        assertTrue(ev.evaluate(rule(CheckIds.VARIABLE_COMPARE, params("name" to "s", "op" to "LT", "value" to "banana"))).passed)
    }
}
