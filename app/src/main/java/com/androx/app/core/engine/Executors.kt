package com.androx.app.core.engine

import com.androx.app.core.model.OpIds
import com.androx.app.core.model.P
import com.androx.app.core.model.Step
import com.androx.app.core.value.MathExpr
import com.androx.app.core.value.MathException
import com.androx.app.core.value.Value
import com.androx.app.core.value.at
import com.androx.app.engine.DeviceBridge
import com.androx.app.engine.ScriptHost
import com.androx.app.engine.ScriptResult
import com.androx.app.engine.ScriptRunner
import com.androx.app.model.FlashlightMode
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Parameters of one step run. [p] is rendered (templates filled in); [raw] is as stored. */
class StepCall(
    val p: P,
    val params: JsonObject,
    val raw: P,
    val ctx: ExecContext,
    val step: Step,
)

/** Runs one kind of leaf step. A new action is one executor plus one spec. */
interface ActionExecutor {
    /** False for steps that only touch variables or logs, so the Simulator can run them for real (on its overlay). */
    val sideEffect: Boolean get() = true

    /** False when the params must reach the executor untouched (script source). */
    val renderParams: Boolean get() = true

    suspend fun execute(call: StepCall): Value?
}

fun executor(
    sideEffect: Boolean = true,
    renderParams: Boolean = true,
    body: suspend (StepCall) -> Value?,
): ActionExecutor {
    val se = sideEffect
    val rp = renderParams
    return object : ActionExecutor {
        override val sideEffect: Boolean get() = se
        override val renderParams: Boolean get() = rp
        override suspend fun execute(call: StepCall): Value? = body(call)
    }
}

/** Executors for every action Androx had in v0.0.1, plus the typed-data actions. */
object BuiltinExecutors {
    const val LOCAL_PREFIX = "local:"

    fun create(scripts: ScriptRunner?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Map<String, ActionExecutor> = DataExecutors.create(zone) + mapOf(
        OpIds.NOTIFY to executor { c ->
            c.ctx.device.showNotification(c.p.str("title", "Androx"), c.p.str("message")); null
        },
        OpIds.TOAST to executor { c ->
            c.ctx.device.showToast(c.p.str("message")); null
        },
        OpIds.SPEAK to executor { c ->
            c.ctx.device.speak(c.p.str("text")); null
        },
        OpIds.SOUND to executor { c ->
            c.ctx.device.playNotificationSound(); null
        },
        OpIds.VIBRATE to executor { c ->
            c.ctx.device.vibrate(c.p.int("durationMs", 400).coerceIn(50, 5000)); null
        },
        OpIds.CLIPBOARD to executor { c ->
            c.ctx.device.copyToClipboard(c.p.str("text")); null
        },
        OpIds.FLASHLIGHT to executor { c ->
            val mode = c.p.enum("mode", FlashlightMode.entries.toTypedArray(), FlashlightMode.TOGGLE)
            Value.Bool(c.ctx.device.setFlashlight(mode))
        },
        OpIds.VOLUME to executor { c ->
            val stream = c.p.enum("stream", VolumeStream.entries.toTypedArray(), VolumeStream.MEDIA)
            c.ctx.device.setVolume(stream, c.p.int("percent", 50).coerceIn(0, 100)); null
        },
        OpIds.LOG to executor(sideEffect = false) { c ->
            c.ctx.log("log: ${c.p.str("message")}"); null
        },
        OpIds.VARIABLE_SET to executor(sideEffect = false, renderParams = false) { c -> setVariable(c) },
        OpIds.CALC to executor(sideEffect = false) { c ->
            val expr = c.p.str("expression")
            val result = try {
                MathExpr.eval(expr) { name -> c.ctx.lookup(name)?.asNumber() }
            } catch (e: MathException) {
                throw FlowException(e.message ?: "The calculation failed.")
            }
            val v = Value.Num(result)
            store(c, c.p.str("name"), v)
            v
        },
        OpIds.JSON_GET to executor(sideEffect = false, renderParams = false) { c ->
            val source = c.ctx.value(c.raw.str("source"))
            val root = if (source is Value.Text) Value.parseJson(source.v) ?: throw FlowException("The source is not valid JSON.") else source
            val found = root.at(c.ctx.render(c.raw.str("path")))
                ?: throw FlowException("\"${c.raw.str("path")}\" wasn't found in the data.")
            store(c, c.p.str("name"), found)
            found
        },
        OpIds.SCRIPT to executor(renderParams = false) { c -> runScript(c, scripts) },
    )

    internal fun scopeIsLocal(c: StepCall) = c.raw.str("scope", "global").equals("local", ignoreCase = true)

    internal fun store(c: StepCall, name: String, value: Value) {
        if (name.isBlank()) return
        if (name.startsWith(LOCAL_PREFIX)) c.ctx.locals[name.removePrefix(LOCAL_PREFIX)] = value
        else if (scopeIsLocal(c)) c.ctx.locals[name] = value
        else c.ctx.variables.set(name, value)
    }

    internal fun read(c: StepCall, name: String): Value? =
        if (scopeIsLocal(c)) c.ctx.locals[name] else c.ctx.variables.get(name)

    private fun convert(value: Value, type: String): Value = when (type.lowercase()) {
        "text" -> Value.Text(value.asText())
        "number" -> Value.Num(value.asNumber() ?: throw FlowException("\"${value.asText()}\" is not a number."))
        "boolean" -> Value.Bool(value.asBool())
        "list" -> value as? Value.ListV
            ?: (Value.parseJson(value.asText()) as? Value.ListV)
            ?: throw FlowException("That isn't a list. Use JSON like [1, 2, 3].")
        "dictionary" -> value as? Value.Dict
            ?: (Value.parseJson(value.asText()) as? Value.Dict)
            ?: throw FlowException("That isn't a dictionary. Use JSON like {\"a\": 1}.")
        else -> value
    }

    private fun setVariable(c: StepCall): Value? {
        val name = c.raw.str("name").trim()
        if (name.isEmpty()) throw FlowException("The variable name is empty.")
        val mode = c.raw.enum("mode", VariableMode.entries.toTypedArray(), VariableMode.SET)
        val incoming = c.ctx.value(c.raw.str("value"))
        val current = read(c, name)
        fun number(label: String, v: Value?): Double =
            if (v == null) 0.0 else v.asNumber() ?: throw FlowException("$label \"${v.asText()}\" is not a number.")
        val result: Value? = when (mode) {
            VariableMode.SET -> convert(incoming, c.raw.str("type", "auto"))
            VariableMode.ADD -> Value.Num(number("The variable $name is", current) + number("The value", incoming))
            VariableMode.SUBTRACT -> Value.Num(number("The variable $name is", current) - number("The value", incoming))
            VariableMode.MULTIPLY -> Value.Num(number("The variable $name is", current) * number("The value", incoming))
            VariableMode.APPEND -> when (current) {
                is Value.ListV -> Value.ListV(current.items + incoming)
                else -> Value.Text((current?.asText() ?: "") + incoming.asText())
            }
            VariableMode.DELETE -> null
        }
        if (result == null) {
            if (scopeIsLocal(c)) c.ctx.locals.remove(name) else c.ctx.variables.remove(name)
        } else {
            if (scopeIsLocal(c)) c.ctx.locals[name] = result else c.ctx.variables.set(name, result)
        }
        return result
    }

    private suspend fun runScript(c: StepCall, scripts: ScriptRunner?): Value? {
        val runner = scripts ?: throw FlowException("Scripts aren't available here.")
        val device: DeviceBridge = c.ctx.device
        val host = object : ScriptHost {
            override fun log(message: String) = c.ctx.log("script: $message")
            override fun notify(title: String, message: String) = device.showNotification(title, message)
            override fun getVar(name: String): String? = c.ctx.variables.get(name)?.asText()
            override fun setVar(name: String, value: String) = c.ctx.variables.set(name, Value.Text(value))
            override fun deleteVar(name: String) = c.ctx.variables.remove(name)
            override fun batteryLevel(): Int = device.batteryLevel()
            override fun isCharging(): Boolean = device.isCharging()
            override fun nowMillis(): Long = c.ctx.now()
            override fun toast(message: String) = device.showToast(message)
            override fun vibrate(durationMs: Int) = device.vibrate(durationMs)
            override fun copy(text: String) = device.copyToClipboard(text)
            override fun speak(text: String) = device.speak(text)
            override fun isWifiConnected(): Boolean = device.isWifiConnected()
            override fun isScreenOn(): Boolean = device.isScreenOn()
            override fun ringer(): String = device.ringerMode().name.lowercase()
        }
        val result = withContext(Dispatchers.Default) { runner.run(c.raw.str("source"), host) }
        if (result is ScriptResult.Failure) throw FlowException(result.message)
        return null
    }
}
