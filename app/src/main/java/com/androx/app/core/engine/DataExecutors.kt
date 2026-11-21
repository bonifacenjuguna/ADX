package com.androx.app.core.engine

import com.androx.app.core.engine.BuiltinExecutors.read
import com.androx.app.core.engine.BuiltinExecutors.store
import com.androx.app.core.model.OpIds
import com.androx.app.core.value.Value
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.regex.PatternSyntaxException

/** Text, list, dictionary, date, random and Flow-management actions. None of them touches the device. */
object DataExecutors {
    private const val MAX_TEXT = 200_000

    private fun asList(v: Value?): List<Value> = when (v) {
        is Value.ListV -> v.items
        null -> emptyList()
        is Value.Text -> if (v.v.isBlank()) emptyList() else (Value.parseJson(v.v) as? Value.ListV)?.items ?: listOf(v)
        else -> listOf(v)
    }

    private fun asDict(v: Value?): Map<String, Value> = when (v) {
        is Value.Dict -> v.entries
        is Value.Text -> (Value.parseJson(v.v) as? Value.Dict)?.entries ?: emptyMap()
        else -> emptyMap()
    }

    private fun regex(source: String): Regex = try {
        Regex(source)
    } catch (e: PatternSyntaxException) {
        throw FlowException("The pattern isn't valid: ${e.description}")
    }

    fun create(zone: ZoneId): Map<String, ActionExecutor> = mapOf(
        OpIds.TEXT to executor(sideEffect = false, renderParams = false) { c ->
            val input = c.ctx.value(c.raw.str("input"))
            val text = input.asText()
            if (text.length > MAX_TEXT) throw FlowException("The text is too long to process (over $MAX_TEXT characters).")
            fun arg(key: String) = c.ctx.render(c.raw.str(key))
            val result: Value = when (c.raw.str("operation", "upper")) {
                "upper" -> Value.Text(text.uppercase())
                "lower" -> Value.Text(text.lowercase())
                "trim" -> Value.Text(text.trim())
                "length" -> Value.Num(input.length().toDouble())
                "contains" -> Value.Bool(text.contains(arg("find"), ignoreCase = c.raw.bool("ignoreCase", true)))
                "replace" -> Value.Text(text.replace(arg("find"), arg("with")))
                "regex_replace" -> Value.Text(text.replace(regex(c.raw.str("find")), arg("with")))
                "regex_match" -> {
                    val m = regex(c.raw.str("find")).find(text)
                    val group = c.raw.int("group", 0)
                    Value.Text(m?.groupValues?.getOrNull(group) ?: "")
                }
                "substring" -> {
                    val start = c.raw.int("start", 0).coerceIn(0, text.length)
                    val len = c.raw.int("length", text.length)
                    Value.Text(text.substring(start, (start + len.coerceAtLeast(0)).coerceAtMost(text.length)))
                }
                "split" -> {
                    val delimiter = arg("find").ifEmpty { "," }
                    Value.ListV(text.split(delimiter).map { Value.Text(it.trim()) })
                }
                "join" -> Value.Text(asList(input).joinToString(arg("find").ifEmpty { ", " }) { it.asText() })
                else -> throw FlowException("Unknown text operation \"${c.raw.str("operation")}\".")
            }
            store(c, c.raw.str("name"), result)
            result
        },
        OpIds.LIST to executor(sideEffect = false, renderParams = false) { c ->
            val name = c.raw.str("name").trim()
            if (name.isEmpty()) throw FlowException("The list variable name is empty.")
            val list = asList(read(c, name))
            val value = c.ctx.value(c.raw.str("value"))
            val index = c.raw.int("index", 0)
            fun save(items: List<Value>): Value { val v = Value.ListV(items); store(c, name, v); return v }
            when (c.raw.str("operation", "add")) {
                "add" -> save(list + value)
                "insert" -> save(list.toMutableList().also { it.add(index.coerceIn(0, list.size), value) })
                "remove_at" -> {
                    if (index !in list.indices) throw FlowException("There is no item $index (the list has ${list.size}).")
                    save(list.toMutableList().also { it.removeAt(index) })
                }
                "remove_value" -> save(list.filter { it.asText() != value.asText() })
                "get" -> list.getOrNull(if (index < 0) list.size + index else index)
                    ?: throw FlowException("There is no item $index (the list has ${list.size}).")
                "length" -> Value.Num(list.size.toDouble())
                "contains" -> Value.Bool(list.any { it.asText() == value.asText() })
                "sort" -> save(list.sortedWith { a, b ->
                    val x = a.asNumber()
                    val y = b.asNumber()
                    if (x != null && y != null) x.compareTo(y) else a.asText().compareTo(b.asText())
                })
                "reverse" -> save(list.reversed())
                "unique" -> save(list.distinctBy { it.asText() })
                "clear" -> save(emptyList())
                else -> throw FlowException("Unknown list operation \"${c.raw.str("operation")}\".")
            }
        },
        OpIds.DICT to executor(sideEffect = false, renderParams = false) { c ->
            val name = c.raw.str("name").trim()
            if (name.isEmpty()) throw FlowException("The dictionary variable name is empty.")
            val dict = asDict(read(c, name))
            val key = c.ctx.render(c.raw.str("key"))
            val value = c.ctx.value(c.raw.str("value"))
            fun save(entries: Map<String, Value>): Value { val v = Value.Dict(entries); store(c, name, v); return v }
            when (c.raw.str("operation", "set")) {
                "set" -> save(dict + (key to value))
                "remove" -> save(dict - key)
                "get" -> dict[key] ?: throw FlowException("There is no key \"$key\".")
                "has" -> Value.Bool(key in dict)
                "keys" -> Value.ListV(dict.keys.map { Value.Text(it) })
                "values" -> Value.ListV(dict.values.toList())
                "count" -> Value.Num(dict.size.toDouble())
                "clear" -> save(emptyMap())
                else -> throw FlowException("Unknown dictionary operation \"${c.raw.str("operation")}\".")
            }
        },
        OpIds.DATE_FORMAT to executor(sideEffect = false) { c ->
            val pattern = c.p.str("pattern", "yyyy-MM-dd HH:mm").ifBlank { "yyyy-MM-dd HH:mm" }
            val formatter = try {
                DateTimeFormatter.ofPattern(pattern)
            } catch (e: IllegalArgumentException) {
                throw FlowException("The date pattern isn't valid: ${e.message}")
            }
            val base = c.p.str("timestamp").trim().toLongOrNull() ?: c.ctx.now()
            val at = Instant.ofEpochMilli(base + c.p.long("offsetMinutes", 0L) * 60_000L).atZone(zone)
            val v = Value.Text(at.format(formatter))
            store(c, c.p.str("name"), v)
            v
        },
        OpIds.RANDOM to executor(sideEffect = false, renderParams = false) { c ->
            val v: Value = when (c.raw.str("mode", "number")) {
                "number" -> {
                    val lo = c.raw.int("min", 1)
                    val hi = c.raw.int("max", 100)
                    if (hi < lo) throw FlowException("The largest number is smaller than the smallest.")
                    Value.Num((lo..hi).random().toDouble())
                }
                "pick" -> {
                    val items = asList(c.ctx.value(c.raw.str("source")))
                    if (items.isEmpty()) throw FlowException("There is nothing to pick from.")
                    items.random()
                }
                "uuid" -> Value.Text(UUID.randomUUID().toString())
                else -> throw FlowException("Unknown random mode \"${c.raw.str("mode")}\".")
            }
            store(c, c.raw.str("name"), v)
            v
        },
        OpIds.FLOW_ENABLE to executor { c ->
            val control = c.ctx.flows ?: throw FlowException("Changing other Flows isn't available here.")
            val id = c.p.str("flowId")
            val state = when (c.p.str("state", "off")) {
                "on" -> true
                "off" -> false
                else -> throw FlowException("Choose on or off.")
            }
            if (!control.setEnabled(id, state)) throw FlowException("That Flow no longer exists.")
            Value.Bool(state)
        },
        OpIds.FLOW_CANCEL to executor { c ->
            val control = c.ctx.flows ?: throw FlowException("Cancelling Flows isn't available here.")
            Value.Bool(control.cancel(c.p.str("flowId")))
        },
    )
}
