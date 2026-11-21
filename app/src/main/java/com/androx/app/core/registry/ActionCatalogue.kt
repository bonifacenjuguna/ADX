package com.androx.app.core.registry

import com.androx.app.core.model.OpIds
import com.androx.app.core.value.Value
import com.androx.app.core.value.ValueType

/** Actions added in 0.0.2 that need nothing from the device. */
object ActionCatalogue {
    private val name = FieldSpec("name", "Save to variable", FieldType.VARIABLE_NAME, Value.Text(""))
    private fun choices(vararg pairs: Pair<String, String>) = pairs.map { Choice(it.first, it.second) }
    private fun later(id: String, category: String, label: String, icon: String, desc: String, note: String, requires: List<Requirement> = emptyList(), editions: Set<Edition> = setOf(Edition.FULL, Edition.PLAY_SAFE)) =
        ActionSpec(id = id, category = category, name = label, icon = icon, description = desc, requires = requires, editions = editions,
            availability = Availability.LATER, note = note) { "Coming later" }

    private fun no(id: String, category: String, label: String, desc: String, note: String) =
        ActionSpec(id = id, category = category, name = label, icon = "block-helper", description = desc,
            availability = Availability.NOT_POSSIBLE, note = note) { "Not possible on Android" }

    val specs: List<ActionSpec> = listOf(
        ActionSpec(
            id = OpIds.TEXT, category = "Variables and data", name = "Text tools", icon = "format-text",
            description = "Upper or lower case, trim, replace, regex, substring, split into a list, join a list, contains, length.",
            synonyms = listOf("string", "regex", "replace", "split", "join", "uppercase", "substring", "trim"),
            fields = listOf(
                FieldSpec("input", "Text", FieldType.MULTILINE, Value.Text(""), templated = true),
                FieldSpec("operation", "Operation", FieldType.CHOICE, Value.Text("upper"), choices = choices(
                    "upper" to "UPPER CASE", "lower" to "lower case", "trim" to "Trim spaces", "length" to "Length", "contains" to "Contains",
                    "replace" to "Replace", "regex_replace" to "Replace (regex)", "regex_match" to "Find (regex)", "substring" to "Part of the text",
                    "split" to "Split into a list", "join" to "Join a list")),
                FieldSpec("find", "Find / separator / pattern", FieldType.TEXT, Value.Text(""), templated = true),
                FieldSpec("with", "Replace with", FieldType.TEXT, Value.Text(""), templated = true),
                FieldSpec("start", "Start", FieldType.NUMBER, Value.Num(0.0), min = 0.0, advanced = true),
                FieldSpec("length", "Length", FieldType.NUMBER, Value.Num(10.0), min = 0.0, advanced = true),
                name,
            ),
            outputs = listOf(OutputSpec("result", "Result", ValueType.TEXT)),
        ) { p -> "Text: ${p.str("operation", "upper")}" },
        ActionSpec(
            id = OpIds.LIST, category = "Variables and data", name = "List tools", icon = "format-list-numbered",
            description = "Add, insert, remove, get, sort, reverse, remove duplicates, count or clear a list variable.",
            synonyms = listOf("array", "append", "queue", "stack", "sort", "unique"),
            fields = listOf(
                FieldSpec("name", "List variable", FieldType.VARIABLE_NAME, null, required = true),
                FieldSpec("operation", "Operation", FieldType.CHOICE, Value.Text("add"), choices = choices(
                    "add" to "Add to the end", "insert" to "Insert at position", "remove_at" to "Remove at position", "remove_value" to "Remove a value",
                    "get" to "Get item", "length" to "Count", "contains" to "Contains", "sort" to "Sort", "reverse" to "Reverse", "unique" to "Remove duplicates", "clear" to "Clear")),
                FieldSpec("value", "Value", FieldType.TEXT, Value.Text(""), templated = true),
                FieldSpec("index", "Position (0 is first, -1 is last)", FieldType.NUMBER, Value.Num(0.0)),
            ),
            outputs = listOf(OutputSpec("result", "Result", ValueType.LIST)),
        ) { p -> "List ${p.str("name")}: ${p.str("operation", "add")}" },
        ActionSpec(
            id = OpIds.DICT, category = "Variables and data", name = "Dictionary tools", icon = "code-braces-box",
            description = "Set, get, remove or list the keys of a dictionary variable.",
            synonyms = listOf("map", "object", "key value", "json object"),
            fields = listOf(
                FieldSpec("name", "Dictionary variable", FieldType.VARIABLE_NAME, null, required = true),
                FieldSpec("operation", "Operation", FieldType.CHOICE, Value.Text("set"), choices = choices(
                    "set" to "Set a key", "get" to "Get a key", "remove" to "Remove a key", "has" to "Has key", "keys" to "List keys", "values" to "List values", "count" to "Count", "clear" to "Clear")),
                FieldSpec("key", "Key", FieldType.TEXT, Value.Text(""), templated = true),
                FieldSpec("value", "Value", FieldType.TEXT, Value.Text(""), templated = true),
            ),
            outputs = listOf(OutputSpec("result", "Result", ValueType.DICTIONARY)),
        ) { p -> "Dictionary ${p.str("name")}: ${p.str("operation", "set")}" },
        ActionSpec(
            id = OpIds.DATE_FORMAT, category = "Variables and data", name = "Format date and time", icon = "calendar-clock",
            description = "Turns the current time (or a timestamp, plus an offset) into text such as 2026-11-21 09:30.",
            synonyms = listOf("date", "time", "clock", "timestamp", "tomorrow", "yesterday"),
            fields = listOf(
                FieldSpec("pattern", "Pattern", FieldType.TEXT, Value.Text("yyyy-MM-dd HH:mm"), help = "yyyy year, MM month, dd day, HH hour, mm minute, EEEE weekday"),
                FieldSpec("offsetMinutes", "Add minutes", FieldType.NUMBER, Value.Num(0.0), help = "Use 1440 for tomorrow, -1440 for yesterday."),
                FieldSpec("timestamp", "From timestamp (ms, optional)", FieldType.TEXT, Value.Text(""), templated = true, advanced = true),
                name,
            ),
            outputs = listOf(OutputSpec("result", "Result", ValueType.TEXT)),
        ) { p -> "Format the time as ${p.str("pattern", "yyyy-MM-dd HH:mm")}" },
        ActionSpec(
            id = OpIds.RANDOM, category = "Variables and data", name = "Random", icon = "dice-multiple",
            description = "A random number, a random item from a list, or a unique id.",
            synonyms = listOf("dice", "shuffle", "pick", "uuid", "chance"),
            fields = listOf(
                FieldSpec("mode", "What", FieldType.CHOICE, Value.Text("number"), choices = choices("number" to "Number", "pick" to "Item from a list", "uuid" to "Unique id")),
                FieldSpec("min", "Smallest", FieldType.NUMBER, Value.Num(1.0)),
                FieldSpec("max", "Largest", FieldType.NUMBER, Value.Num(100.0)),
                FieldSpec("source", "List", FieldType.TEXT, Value.Text(""), templated = true),
                name,
            ),
            outputs = listOf(OutputSpec("result", "Result", ValueType.TEXT)),
        ) { p -> "Random ${p.str("mode", "number")}" },
        ActionSpec(
            id = OpIds.FLOW_ENABLE, category = "Flow control", name = "Turn a Flow on or off", icon = "toggle-switch-outline",
            description = "Enables or disables another Flow.", synonyms = listOf("enable", "disable", "switch"),
            fields = listOf(
                FieldSpec("flowId", "Flow", FieldType.FLOW, null, required = true),
                FieldSpec("state", "Set to", FieldType.CHOICE, Value.Text("off"), choices = choices("on" to "On", "off" to "Off")),
            ),
        ) { p -> "Turn Flow ${p.str("flowId")} ${p.str("state", "off")}" },
        ActionSpec(
            id = OpIds.FLOW_CANCEL, category = "Flow control", name = "Cancel a running Flow", icon = "cancel",
            description = "Stops another Flow that is running right now.", synonyms = listOf("stop", "abort", "kill"),
            fields = listOf(FieldSpec("flowId", "Flow", FieldType.FLOW, null, required = true)),
        ) { p -> "Cancel Flow ${p.str("flowId")}" },

        later("ui.dialog", "Notify and tell", "Ask with a dialog", "message-question", "Asks you for input when you are present.", "Needs the in-app prompt UI (editor layer)."),
        later("notify.builder", "Notify and tell", "Notification builder", "bell-ring-outline", "Channels, priority, buttons that start Flows, progress, auto-dismiss.", "Needs the notification-button receiver (integration layer)."),
        later("device.ringer_mode", "Device and settings", "Set ringer mode / Do Not Disturb", "volume-off", "Normal, vibrate or silent.", "Needs Do Not Disturb access flow.", listOf(Requirement.SpecialAccess("dnd", "Do Not Disturb access"))),
        later("device.brightness", "Device and settings", "Brightness, screen timeout, auto-rotate", "brightness-6", "Changes display settings.", "Needs the Modify system settings flow.", listOf(Requirement.SpecialAccess("write_settings", "Modify system settings"))),
        later("files.read", "Files", "Read, write, copy, move, zip", "folder-outline", "Works inside a folder you pick.", "Needs the folder picker with persisted access."),
        later("clock.alarm", "Clock", "Set alarm or timer", "alarm", "Sets an alarm or timer in the clock app.", "Android blocks background launches; planned via notification tap."),
        later("sms.send", "Phone", "Send SMS", "message-text", "Sends a text message.", "Full edition only; Play restricts SMS.", listOf(Requirement.RuntimePermission("android.permission.SEND_SMS", "SMS")), setOf(Edition.FULL)),
        later("call.phone", "Phone", "Call, answer, hang up", "phone", "Phone control.", "Full edition only; Play restricts call permissions.", listOf(Requirement.RuntimePermission("android.permission.CALL_PHONE", "Phone")), setOf(Edition.FULL)),
        later("a11y.click", "Accessibility", "Click, read screen, OCR, screenshot", "gesture-tap", "Acts on other apps' screens.", "Full edition only; needs the accessibility service.", listOf(Requirement.SpecialAccess("accessibility", "Accessibility service")), setOf(Edition.FULL)),
        no("wifi.toggle", "Device and settings", "Turn Wi-Fi, Bluetooth, mobile data or airplane mode on or off", "Android blocks apps from doing this.", "With Shizuku (later tier) it could work. For now: open the system panel."),
        no("app.kill", "Apps and links", "Kill another app", "Needs root.", "No alternative without root or Shizuku."),
        no("flow.goto", "Flow control", "Goto", "Jumping to an arbitrary step.", "Only in the free-form Canvas, by design."),
    )
}
