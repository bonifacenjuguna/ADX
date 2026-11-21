package com.androx.app.core.engine

import com.androx.app.core.model.Blocks
import com.androx.app.core.model.Flow
import com.androx.app.core.model.FlowEditor
import com.androx.app.core.model.Op
import com.androx.app.core.model.OpIds
import com.androx.app.core.model.P
import com.androx.app.core.model.Step
import com.androx.app.core.model.isInTrash
import com.androx.app.core.model.params
import com.androx.app.core.value.TemplateEngine
import com.androx.app.core.value.Value
import com.androx.app.core.value.at
import com.androx.app.engine.DeviceBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Signals used for control flow. They are never retried and never swallowed by "continue on error". */
internal sealed class FlowSignal : RuntimeException(null, null, false, false)
internal class BreakSignal : FlowSignal()
internal class ContinueSignal : FlowSignal()
internal class StopSignal(val reason: String?) : FlowSignal()
internal class ReturnSignal(val value: Value?) : FlowSignal()
internal class LimitSignal(val reason: String) : FlowSignal()

/** A step failed and nothing handled it. */
internal class StepFailed(message: String) : Exception(message)

/**
 * The Flow engine. Pure Kotlin: everything about the device goes through [DeviceBridge], everything about
 * time through [clock] and [sleeper], so the whole thing is unit tested without Android.
 *
 * Runs a Flow's step tree with If / Repeat / For each / While / Break / Continue / Try / Wait until / Stop / Run Flow,
 * per-step retry, timeout and continue-on-error, a step budget, a wait budget and a run timeout.
 */
class FlowEngine(
    private val device: DeviceBridge,
    private val variables: Variables,
    private val findFlow: (String) -> Flow?,
    private val executors: Map<String, ActionExecutor>,
    private val describe: (Op) -> String = { it.typeId },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val extraChecks: Map<String, CheckFn> = emptyMap(),
    private val state: StateReader = StateReader { null },
    private val flowControl: FlowControl? = null,
) {
    companion object {
        val CONTROL_OPS: Set<String> = setOf(
            OpIds.IF, OpIds.REPEAT, OpIds.FOR_EACH, OpIds.WHILE, OpIds.BREAK, OpIds.CONTINUE, OpIds.STOP,
            OpIds.RETURN, OpIds.WAIT, OpIds.WAIT_UNTIL, OpIds.TRY, OpIds.RUN_FLOW,
        )
        const val MAX_REPEAT = 1000
        const val MAX_SINGLE_WAIT_SEC = 10
        const val MAX_FLOW_DEPTH = 4
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
        private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", java.util.Locale.ENGLISH)
    }

    private class Frame(
        val flow: Flow,
        val locals: MutableMap<String, Value>,
        val depth: Int,
        val stack: List<String>,
    ) {
        fun deeper() = Frame(flow, locals, depth + 1, stack)
    }

    private class Run(
        val flow: Flow,
        val device: DeviceBridge,
        val vars: Variables,
        val trigger: TriggerInfo,
        val sim: Boolean,
        val options: ExecuteOptions,
        val started: Long,
        val evaluator: ConstraintEvaluator,
    ) {
        val logs = mutableListOf<String>()
        val steps = mutableListOf<StepLog>()
        val constraints = mutableListOf<ConstraintLine>()
        var stepCount = 0
        var waitedMs = 0L
        val maxSteps = flow.run.stepBudget.coerceIn(1, 100_000)
        val maxWaitMs = flow.run.waitBudgetSec.coerceIn(0, 3_600) * 1_000L
        val timeoutSec = flow.run.timeoutSec.coerceIn(1, 3_600)
        val deadline = started + timeoutSec * 1_000L
    }

    suspend fun execute(flow: Flow, trigger: TriggerInfo = TriggerInfo(), options: ExecuteOptions = ExecuteOptions()): RunRecord {
        val sim = options.simulation
        val dev = sim?.device ?: device
        val vars = sim?.variables ?: variables
        val started = clock()
        val run = Run(flow, dev, vars, trigger, sim != null, options, started, ConstraintEvaluator(dev, vars, clock, zone, extraChecks, state))
        val frame = Frame(flow, mutableMapOf(), 0, listOf(flow.id))
        var status = RunStatus.SUCCESS
        var message: String? = null
        try {
            if (!options.ignoreConstraints) {
                val e = evalIn(run, frame, flow.constraints)
                run.constraints += e.lines
                if (!e.passed) {
                    status = RunStatus.SKIPPED
                    message = "Skipped: ${e.why}."
                    run.logs += message
                }
            }
            if (status == RunStatus.SUCCESS) {
                runSteps(slice(bodyOf(flow), options), frame, run)
                message = "Done."
            }
        } catch (e: StopSignal) {
            message = e.reason ?: "Stopped by the Flow."
        } catch (e: ReturnSignal) {
            message = "Finished."
        } catch (e: LimitSignal) {
            status = RunStatus.LIMIT
            message = e.reason
        } catch (e: BreakSignal) {
            status = RunStatus.FAILED
            message = "Break was used outside a loop."
        } catch (e: ContinueSignal) {
            status = RunStatus.FAILED
            message = "Continue was used outside a loop."
        } catch (e: StepFailed) {
            status = RunStatus.FAILED
            message = e.message
        } catch (e: CancellationException) {
            val partial = record(run, RunStatus.CANCELLED, "Cancelled.")
            withContext(NonCancellable) { options.onCancelled?.invoke(partial) }
            throw e
        } catch (e: Exception) {
            status = RunStatus.FAILED
            message = e.message ?: e.javaClass.simpleName
        }
        if (status != RunStatus.SUCCESS) run.logs += message ?: ""
        return record(run, status, message)
    }

    private fun record(run: Run, status: RunStatus, message: String?) = RunRecord(
        id = UUID.randomUUID().toString(),
        flowId = run.flow.id,
        flowName = run.flow.name,
        startedAt = run.started,
        durationMs = (clock() - run.started).coerceAtLeast(0),
        status = status,
        triggerTypeId = run.trigger.typeId,
        triggerId = run.trigger.triggerId,
        simulated = run.sim,
        message = message,
        constraints = run.constraints.toList(),
        steps = run.steps.toList(),
        logs = run.logs.toList(),
        stepCount = run.stepCount,
    )

    /** "Would it run now?" without running anything. */
    fun wouldRun(flow: Flow): Eval =
        ConstraintEvaluator(device, variables, clock, zone, extraChecks, state).evaluate(flow.constraints)

    private fun bodyOf(flow: Flow): List<Step> =
        if (flow.editor == FlowEditor.CODE && flow.script != null)
            listOf(Step("script", Op(OpIds.SCRIPT, params("source" to flow.script))))
        else flow.steps

    /** Applies "Run from here" / "Run until here" to the top-level list. */
    private fun slice(steps: List<Step>, options: ExecuteOptions): List<Step> {
        fun contains(step: Step, id: String): Boolean =
            step.id == id || step.blocks.values.any { list -> list.any { contains(it, id) } }
        var from = 0
        var to = steps.size
        options.fromStepId?.let { id -> steps.indexOfFirst { contains(it, id) }.takeIf { it >= 0 }?.let { from = it } }
        options.untilStepId?.let { id -> steps.indexOfFirst { contains(it, id) }.takeIf { it >= 0 }?.let { to = it + 1 } }
        return if (from < to) steps.subList(from, to) else emptyList()
    }

    // ---- scope ---------------------------------------------------------------------------------------------

    private fun builtin(run: Run, frame: Frame, name: String): Value? {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone)
        return when (name) {
            "battery" -> Value.Num(run.device.batteryLevel().toDouble())
            "charging" -> Value.Bool(run.device.isCharging())
            "time" -> Value.Text(now.format(TIME))
            "date" -> Value.Text(now.format(DATE))
            "datetime" -> Value.Text(now.format(DATETIME))
            "weekday" -> Value.Text(now.format(WEEKDAY))
            "timestamp" -> Value.Text(clock().toString())
            "wifi" -> Value.Bool(run.device.isWifiConnected())
            "screen" -> Value.Bool(run.device.isScreenOn())
            "ringer" -> Value.Text(run.device.ringerMode().name.lowercase())
            "random" -> Value.Num((0..99).random().toDouble())
            "flow" -> Value.Text(frame.flow.name)
            "trigger" -> Value.Dict(run.trigger.data + ("type" to Value.Text(run.trigger.typeId)))
            else -> null
        }
    }

    private fun lookupFor(run: Run, frame: Frame): (String) -> Value? {
        val layered = TemplateEngine.scope(frame.locals, run.trigger.data)
        return { name ->
            layered(name) ?: builtin(run, frame, name) ?: run.vars.get(name) ?: inValue(run, frame, name)
        }
    }

    /** `{{config.url}}`, `{{items[0]}}` or `{{trigger.type}}`: a path into a list or dictionary value. */
    private fun inValue(run: Run, frame: Frame, name: String): Value? {
        val cut = name.indexOfFirst { it == '.' || it == '[' }
        if (cut <= 0) return null
        val head = name.substring(0, cut)
        val rest = name.substring(cut).removePrefix(".")
        return (builtin(run, frame, head) ?: run.vars.get(head))?.at(rest)
    }

    private fun evalIn(run: Run, frame: Frame, node: com.androx.app.core.model.ConstraintNode?): Eval =
        run.evaluator.evaluate(node, lookupFor(run, frame).let { look -> { text: String -> TemplateEngine.render(text, look) } }, run.trigger.typeId)

    private fun ctxFor(run: Run, frame: Frame) = ExecContext(
        device = run.device,
        variables = run.vars,
        flow = frame.flow,
        locals = frame.locals,
        lookupFn = lookupFor(run, frame),
        logFn = { run.logs += "  ".repeat(frame.depth + 1) + it },
        now = clock,
        simulated = run.sim,
        flows = flowControl,
    )

    private fun renderJson(el: JsonElement, render: (String) -> String): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { renderJson(it.value, render) })
        is JsonArray -> JsonArray(el.map { renderJson(it, render) })
        is JsonPrimitive -> if (el.isString) JsonPrimitive(render(el.content)) else el
        else -> el
    }

    // ---- running steps -------------------------------------------------------------------------------------

    private fun tick(run: Run) {
        run.stepCount++
        if (run.stepCount > run.maxSteps) {
            throw LimitSignal("Stopped: loop limit reached (more than ${run.maxSteps} steps in one run).")
        }
        if (clock() > run.deadline) {
            throw LimitSignal("Stopped: the run took longer than ${run.timeoutSec} s.")
        }
    }

    private suspend fun runSteps(steps: List<Step>, frame: Frame, run: Run) {
        for (step in steps) {
            currentCoroutineContext().ensureActive()
            runStep(step, frame, run)
        }
    }

    private fun addLog(run: Run, step: Step, label: String, status: StepStatus, t0: Long, depth: Int, output: String? = null, message: String? = null) {
        val log = StepLog(step.id, label, status, t0, (clock() - t0).coerceAtLeast(0), depth, output, message)
        run.steps += log
        run.options.listener?.onStepEnd(log)
    }

    private suspend fun runStep(step: Step, frame: Frame, run: Run) {
        val label = describe(step.op)
        val typeId = step.op.typeId
        val indent = "  ".repeat(frame.depth)
        val t0 = clock()
        if (step.options.disabled) {
            run.logs += "$indent• $label (disabled)"
            addLog(run, step, label, StepStatus.DISABLED, t0, frame.depth)
            return
        }
        tick(run)
        val isControl = typeId in CONTROL_OPS
        if (!isControl && step.condition != null) {
            val e = evalIn(run, frame, step.condition)
            if (!e.passed) {
                run.logs += "$indent• $label skipped (only if: ${e.why})"
                addLog(run, step, label, StepStatus.SKIPPED, t0, frame.depth, message = e.why)
                return
            }
        }
        run.logs += "$indent${run.stepCount}. $label"
        run.options.listener?.onStepStart(step.id, label)
        val maxAttempts = if (isControl) 1 else 1 + step.options.retries.coerceIn(0, 10)
        var attempt = 0
        while (true) {
            attempt++
            var failure: String? = null
            try {
                val outcome = if (isControl) Outcome(runControl(step, frame, run), false) else runLeaf(step, frame, run)
                val out = outcome.output
                if (out != null && step.options.saveResultTo.isNotBlank()) {
                    val target = step.options.saveResultTo.trim()
                    if (target.startsWith(BuiltinExecutors.LOCAL_PREFIX)) frame.locals[target.removePrefix(BuiltinExecutors.LOCAL_PREFIX)] = out
                    else run.vars.set(target, out)
                }
                addLog(
                    run, step, label, if (outcome.simulated) StepStatus.SIMULATED else StepStatus.OK, t0, frame.depth,
                    output = out?.asText()?.take(500),
                )
                return
            } catch (e: TimeoutCancellationException) {
                failure = "Timed out after ${step.options.timeoutMs} ms."
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlowSignal) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            }
            if (attempt < maxAttempts) {
                run.logs += "$indent   failed ($failure). Retrying ($attempt of ${maxAttempts - 1})…"
                if (!run.sim) sleeper(step.options.retryDelayMs.coerceIn(0, 60_000))
                continue
            }
            run.logs += "$indent   ✗ $failure"
            if (step.options.continueOnError || run.flow.run.onError == com.androx.app.core.model.ErrorPolicy.CONTINUE) {
                frame.locals["error"] = Value.Dict(mapOf("message" to Value.Text(failure ?: ""), "step" to Value.Text(label)))
                addLog(run, step, label, StepStatus.ERROR_IGNORED, t0, frame.depth, message = failure)
                return
            }
            addLog(run, step, label, StepStatus.FAILED, t0, frame.depth, message = failure)
            throw StepFailed(failure ?: "The step failed.")
        }
    }

    private class Outcome(val output: Value?, val simulated: Boolean)

    private suspend fun runLeaf(step: Step, frame: Frame, run: Run): Outcome {
        val ex = executors[step.op.typeId]
            ?: throw FlowException("\"${step.op.typeId}\" isn't available in this version of Androx.")
        val ctx = ctxFor(run, frame)
        val raw = step.op.params
        val rendered = if (ex.renderParams) renderJson(raw) { ctx.render(it) } as JsonObject else raw
        if (run.sim && ex.sideEffect) {
            run.logs += "  ".repeat(frame.depth + 1) + "(simulated) would: ${describe(step.op)}"
            return Outcome(null, true)
        }
        val call = StepCall(P(rendered), rendered, P(raw), ctx, step)
        val timeout = step.options.timeoutMs
        val out = if (timeout > 0) withTimeout(timeout) { ex.execute(call) } else ex.execute(call)
        return Outcome(out, false)
    }

    // ---- control flow --------------------------------------------------------------------------------------

    private suspend fun waitFor(run: Run, ms: Long) {
        if (run.waitedMs + ms > run.maxWaitMs) {
            throw FlowException("A run can wait at most ${run.maxWaitMs / 1000} s in total. Raise the wait budget in the Flow's settings if you need more.")
        }
        run.waitedMs += ms
        if (!run.sim) sleeper(ms)
    }

    private suspend fun runControl(step: Step, frame: Frame, run: Run): Value? {
        val p = P(step.op.params)
        val inner = frame.deeper()
        val ctx = ctxFor(run, frame)
        fun block(name: String) = step.blocks[name].orEmpty()
        fun need(): com.androx.app.core.model.ConstraintNode =
            step.condition ?: throw FlowException("This step has no condition.")

        suspend fun loopBody(body: List<Step>) {
            try {
                runSteps(body, inner, run)
            } catch (e: ContinueSignal) {
                // next round
            }
        }

        when (step.op.typeId) {
            OpIds.IF -> {
                val e = evalIn(run, frame, need())
                run.logs += "  ".repeat(frame.depth + 1) + (if (e.passed) "then: " else "else: ") + e.why
                runSteps(block(if (e.passed) Blocks.THEN else Blocks.ELSE), inner, run)
            }
            OpIds.REPEAT -> {
                val times = p.int("times", 3).coerceIn(1, MAX_REPEAT)
                val saved = frame.locals["index"]
                try {
                    for (round in 1..times) {
                        currentCoroutineContext().ensureActive()
                        tick(run)
                        frame.locals["index"] = Value.Num(round.toDouble())
                        loopBody(block(Blocks.BODY))
                    }
                } catch (e: BreakSignal) {
                    // leave the loop
                } finally {
                    if (saved != null) frame.locals["index"] = saved else frame.locals.remove("index")
                }
            }
            OpIds.FOR_EACH -> {
                var source = ctx.value(p.str("source"))
                if (source is Value.Text) {
                    val t = source.v.trim()
                    source = if (t.startsWith("[") || t.startsWith("{")) Value.parseJson(t) ?: source
                    else Value.ListV(t.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { Value.Text(it) })
                }
                val items: List<Pair<String?, Value>> = when (val s = source) {
                    is Value.ListV -> s.items.map { null to it }
                    is Value.Dict -> s.entries.map { it.key to it.value }
                    else -> listOf(null to s)
                }
                val itemVar = p.str("itemVar", "item").ifBlank { "item" }
                val saved = listOf("index", "key", itemVar).associateWith { frame.locals[it] }
                try {
                    items.forEachIndexed { i, (key, value) ->
                        currentCoroutineContext().ensureActive()
                        tick(run)
                        frame.locals["index"] = Value.Num((i + 1).toDouble())
                        frame.locals[itemVar] = value
                        if (key != null) frame.locals["key"] = Value.Text(key) else frame.locals.remove("key")
                        loopBody(block(Blocks.BODY))
                    }
                } catch (e: BreakSignal) {
                    // leave the loop
                } finally {
                    saved.forEach { (k, v) -> if (v != null) frame.locals[k] = v else frame.locals.remove(k) }
                }
            }
            OpIds.WHILE -> {
                val maxRounds = p.int("maxIterations", 1000).coerceIn(1, 100_000)
                var rounds = 0
                try {
                    while (evalIn(run, frame, need()).passed) {
                        currentCoroutineContext().ensureActive()
                        rounds++
                        if (rounds > maxRounds) throw LimitSignal("Stopped: loop limit reached (more than $maxRounds rounds).")
                        tick(run)
                        frame.locals["index"] = Value.Num(rounds.toDouble())
                        loopBody(block(Blocks.BODY))
                    }
                } catch (e: BreakSignal) {
                    // leave the loop
                }
            }
            OpIds.BREAK -> throw BreakSignal()
            OpIds.CONTINUE -> throw ContinueSignal()
            OpIds.STOP -> throw StopSignal(ctx.render(p.str("message")).ifBlank { null })
            OpIds.RETURN -> throw ReturnSignal(ctx.value(p.str("value")))
            OpIds.WAIT -> {
                val seconds = p.int("seconds", 2).coerceIn(1, MAX_SINGLE_WAIT_SEC)
                waitFor(run, seconds * 1000L)
            }
            OpIds.WAIT_UNTIL -> {
                val timeoutMs = p.int("timeoutSec", 10).coerceIn(1, 600) * 1000L
                val poll = p.int("pollMs", 500).coerceIn(100, 10_000).toLong()
                var waited = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (evalIn(run, frame, need()).passed) break
                    if (waited >= timeoutMs) {
                        if (p.bool("continueOnTimeout", false)) {
                            run.logs += "  ".repeat(frame.depth + 1) + "timed out, continuing"
                            break
                        }
                        throw FlowException("Timed out after ${timeoutMs / 1000} s waiting for the condition.")
                    }
                    waitFor(run, poll)
                    waited += poll
                }
            }
            OpIds.TRY -> {
                try {
                    try {
                        runSteps(block(Blocks.TRY), inner, run)
                    } catch (e: StepFailed) {
                        if (!step.blocks.containsKey(Blocks.CATCH)) throw e
                        frame.locals["error"] = Value.Dict(mapOf("message" to Value.Text(e.message ?: "")))
                        run.logs += "  ".repeat(frame.depth + 1) + "caught: ${e.message}"
                        runSteps(block(Blocks.CATCH), inner, run)
                    }
                } finally {
                    if (block(Blocks.FINALLY).isNotEmpty()) runSteps(block(Blocks.FINALLY), inner, run)
                }
            }
            OpIds.RUN_FLOW -> return runChild(step, frame, run, ctx)
        }
        return null
    }

    private suspend fun runChild(step: Step, frame: Frame, run: Run, ctx: ExecContext): Value? {
        val p = P(step.op.params)
        val id = p.str("flowId")
        val target = findFlow(id)
        if (target == null || target.isInTrash()) {
            throw FlowException("Flow \"${p.str("flowName").ifBlank { id }}\" no longer exists.")
        }
        if (target.id in frame.stack) throw FlowException("\"${target.name}\" would call itself.")
        if (frame.stack.size >= MAX_FLOW_DEPTH) throw FlowException("Flows can only call each other $MAX_FLOW_DEPTH levels deep.")
        val e = evalIn(run, frame, target.constraints)
        if (!e.passed) {
            run.logs += "  ".repeat(frame.depth + 1) + "skipped \"${target.name}\": ${e.why}"
            return null
        }
        val inputs = HashMap<String, Value>()
        p.obj("inputs")?.forEach { (k, v) ->
            inputs[k] = if (v is JsonPrimitive && v.isString) ctx.value(v.content) else Value.fromJson(v)
        }
        val locals = HashMap<String, Value>(inputs)
        locals["input"] = Value.Dict(inputs)
        val child = Frame(target, locals, frame.depth + 1, frame.stack + target.id)
        return try {
            runSteps(bodyOf(target), child, run)
            null
        } catch (s: ReturnSignal) {
            s.value
        } catch (s: StopSignal) {
            null
        }
    }
}
