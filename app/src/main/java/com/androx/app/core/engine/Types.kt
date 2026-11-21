package com.androx.app.core.engine

import com.androx.app.core.model.Flow
import com.androx.app.core.model.TriggerIds
import com.androx.app.core.value.TemplateEngine
import com.androx.app.core.value.Value
import com.androx.app.engine.DeviceBridge
import com.androx.app.model.ExecutionStatus
import kotlinx.serialization.Serializable

@Serializable
enum class RunStatus {
    SUCCESS, FAILED, SKIPPED, CANCELLED,

    /** Stopped by a safety limit: step budget, loop cap or run timeout. */
    LIMIT;

    /** Daily stats and the v0.0.1 history use the three-state status. */
    fun toLegacy(): ExecutionStatus = when (this) {
        SUCCESS -> ExecutionStatus.SUCCESS
        FAILED, LIMIT -> ExecutionStatus.FAILED
        SKIPPED, CANCELLED -> ExecutionStatus.SKIPPED
    }
}

@Serializable
enum class StepStatus { OK, FAILED, SKIPPED, DISABLED, SIMULATED, ERROR_IGNORED }

@Serializable
data class StepLog(
    val stepId: String,
    val label: String,
    val status: StepStatus,
    val startedAt: Long,
    val durationMs: Long,
    val depth: Int,
    val output: String? = null,
    val message: String? = null,
)

/** One line of "Why did this run?": a constraint, the value that was seen, and whether it passed. */
@Serializable
data class ConstraintLine(val text: String, val passed: Boolean)

@Serializable
data class RunRecord(
    val id: String,
    val flowId: String,
    val flowName: String,
    val startedAt: Long,
    val durationMs: Long,
    val status: RunStatus,
    val triggerTypeId: String,
    val triggerId: String? = null,
    val simulated: Boolean = false,
    val message: String? = null,
    val constraints: List<ConstraintLine> = emptyList(),
    val steps: List<StepLog> = emptyList(),
    val logs: List<String> = emptyList(),
    val stepCount: Int = 0,
)

/** What started a run. [data] becomes `{{name}}` values, e.g. `sms.sender`. */
data class TriggerInfo(
    val typeId: String = TriggerIds.MANUAL,
    val triggerId: String? = null,
    val data: Map<String, Value> = emptyMap(),
)

interface RunListener {
    fun onStepStart(stepId: String, label: String) {}
    fun onStepEnd(log: StepLog) {}
}

/** Typed variable storage the engine reads and writes. */
interface Variables {
    fun get(name: String): Value?
    fun set(name: String, value: Value)
    fun remove(name: String)
}

class MemoryVariables(initial: Map<String, Value> = emptyMap()) : Variables {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Value>(initial)
    override fun get(name: String): Value? = map[name]
    override fun set(name: String, value: Value) { map[name] = value }
    override fun remove(name: String) { map.remove(name) }
    fun all(): Map<String, Value> = map.toMap()
}

/** Reads fall through to [base]; writes stay here. Used by the Simulator so a dry run never changes real variables. */
class OverlayVariables(private val base: Variables) : Variables {
    private val written = HashMap<String, Value>()
    private val removed = HashSet<String>()

    @Synchronized
    override fun get(name: String): Value? = when {
        name in removed -> null
        else -> written[name] ?: base.get(name)
    }

    @Synchronized
    override fun set(name: String, value: Value) {
        removed.remove(name)
        written[name] = value
    }

    @Synchronized
    override fun remove(name: String) {
        written.remove(name)
        removed.add(name)
    }
}

/** Fake world for a dry run. */
class Simulation(val device: DeviceBridge, val variables: Variables)

data class ExecuteOptions(
    val simulation: Simulation? = null,
    /** Start at the top-level step containing this id ("Run from here"). */
    val fromStepId: String? = null,
    /** Stop after the top-level step containing this id ("Run until here"). */
    val untilStepId: String? = null,
    val ignoreConstraints: Boolean = false,
    val listener: RunListener? = null,
    /** Called with the partial record when a run is cancelled (the cancellation is then re-thrown). */
    val onCancelled: ((RunRecord) -> Unit)? = null,
)

/** A step failed. Carries the message that ends up in History. */
class FlowException(message: String) : Exception(message)

/** What actions may ask of the app about other Flows (enable, disable, cancel). */
interface FlowControl {
    fun setEnabled(flowId: String, enabled: Boolean): Boolean
    fun cancel(flowId: String): Boolean
    fun isRunning(flowId: String): Boolean
}

/** Everything a leaf step executor can see. */
class ExecContext(
    val device: DeviceBridge,
    val variables: Variables,
    val flow: Flow,
    val locals: MutableMap<String, Value>,
    private val lookupFn: (String) -> Value?,
    private val logFn: (String) -> Unit,
    val now: () -> Long,
    val simulated: Boolean,
    val flows: FlowControl? = null,
) {
    fun lookup(name: String): Value? = lookupFn(name) ?: TemplateEngine.aliases[name]?.let(lookupFn)

    fun render(text: String): String = TemplateEngine.render(text, lookupFn)

    /** Like [render], but a text that is exactly one `{{value}}` keeps its type (number, list, dictionary…). */
    fun value(text: String): Value {
        val m = SINGLE.matchEntire(text.trim())
        return if (m != null) lookup(m.groupValues[1]) ?: Value.EMPTY else Value.Text(render(text))
    }

    fun log(message: String) = logFn(message)

    private companion object {
        val SINGLE = Regex("\\{\\{\\s*([A-Za-z0-9_.\\[\\]-]+)\\s*}}")
    }
}
