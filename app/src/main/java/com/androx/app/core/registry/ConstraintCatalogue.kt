package com.androx.app.core.registry

import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.P
import com.androx.app.core.value.Value
import com.androx.app.model.Comparison

/** Constraints added in 0.0.2. */
object ConstraintCatalogue {
    private fun cmpField() = FieldSpec("op", "Comparison", FieldType.COMPARISON, Value.Text("GT"), choices = Comparison.entries.map { Choice(it.name, it.symbol) })
    private fun opOf(p: P, d: Comparison) = p.enum("op", Comparison.entries.toTypedArray(), d)
    private fun num(p: P, k: String, d: Double) = com.androx.app.core.value.formatNumber(p.double(k, d))

    private fun flag(id: String, category: String, name: String, icon: String, desc: String, syn: List<String>, field: String, label: String, onText: String, offText: String) =
        ConstraintSpec(
            id = id, category = category, name = name, icon = icon, description = desc, synonyms = syn,
            fields = listOf(FieldSpec(field, label, FieldType.BOOLEAN, Value.Bool(true))),
        ) { p -> if (p.bool(field, true)) onText else offText }

    private fun later(id: String, category: String, name: String, icon: String, desc: String, note: String, requires: List<Requirement> = emptyList()) =
        ConstraintSpec(id = id, category = category, name = name, icon = icon, description = desc, requires = requires,
            availability = Availability.LATER, note = note) { "Coming later" }

    private fun no(id: String, category: String, name: String, desc: String, note: String) =
        ConstraintSpec(id = id, category = category, name = name, icon = "block-helper", description = desc,
            availability = Availability.NOT_POSSIBLE, note = note) { "Not possible on Android" }

    val specs: List<ConstraintSpec> = listOf(
        ConstraintSpec(
            id = CheckIds.BATTERY_TEMP, category = "Battery and power", name = "Battery temperature", icon = "thermometer",
            description = "Compares the battery temperature in °C.", synonyms = listOf("hot", "overheating", "warm"),
            fields = listOf(cmpField(), FieldSpec("degrees", "Degrees °C", FieldType.NUMBER, Value.Num(40.0), min = -20.0, max = 100.0)),
        ) { p -> "Battery temperature ${opOf(p, Comparison.GT).symbol} ${num(p, "degrees", 40.0)} °C" },
        ConstraintSpec(
            id = CheckIds.CHARGER, category = "Battery and power", name = "Charger type", icon = "power-plug",
            description = "Which kind of charger is connected.", synonyms = listOf("usb", "wireless charging", "wall charger", "plugged in"),
            fields = listOf(FieldSpec("type", "Charger", FieldType.CHOICE, Value.Text("any"),
                choices = listOf(Choice("any", "Any charger"), Choice("ac", "Wall (AC)"), Choice("usb", "USB"), Choice("wireless", "Wireless"), Choice("none", "None")))),
        ) { p -> "Charger: ${p.str("type", "any")}" },
        ConstraintSpec(
            id = CheckIds.NET_STATE, category = "Connectivity", name = "Network type", icon = "wifi",
            description = "Wi-Fi, mobile data, VPN, metered or offline.", synonyms = listOf("internet", "online", "offline", "data", "metered", "vpn"),
            requires = listOf(Requirement.RuntimePermission("android.permission.ACCESS_NETWORK_STATE", "Network state")),
            fields = listOf(FieldSpec("kind", "Network", FieldType.CHOICE, Value.Text("wifi"),
                choices = listOf(Choice("wifi", "On Wi-Fi"), Choice("mobile", "On mobile data"), Choice("vpn", "VPN active"), Choice("metered", "Metered connection"), Choice("offline", "Offline")))),
        ) { p -> "Network is ${p.str("kind", "wifi")}" },
        flag(CheckIds.HEADPHONES, "Device state", "Headphones", "headphones", "Whether headphones or a headset are connected.", listOf("earphones", "headset", "bluetooth audio"), "connected", "Connected", "Headphones connected", "No headphones"),
        flag(CheckIds.DND, "Device state", "Do Not Disturb", "bell-off-outline", "Whether Do Not Disturb is on.", listOf("dnd", "quiet", "priority only"), "on", "On", "Do Not Disturb is on", "Do Not Disturb is off"),
        flag(CheckIds.LOCKED, "Device state", "Lock state", "lock-outline", "Whether the phone is locked.", listOf("unlocked", "keyguard", "pin"), "locked", "Locked", "Phone is locked", "Phone is unlocked"),
        flag(CheckIds.TORCH, "Device state", "Flashlight", "flashlight", "Whether the flashlight is on.", listOf("torch"), "on", "On", "Flashlight is on", "Flashlight is off"),
        flag(CheckIds.DARK_THEME, "Device state", "Dark theme", "theme-light-dark", "Whether the system dark theme is on.", listOf("night mode", "appearance"), "on", "On", "Dark theme is on", "Dark theme is off"),
        ConstraintSpec(
            id = CheckIds.STORAGE, category = "Device state", name = "Free storage", icon = "harddisk",
            description = "Compares free internal storage in megabytes.", synonyms = listOf("space", "disk", "full"),
            fields = listOf(cmpField().copy(default = Value.Text("LT")), FieldSpec("megabytes", "Megabytes", FieldType.NUMBER, Value.Num(1000.0), min = 0.0)),
        ) { p -> "Free storage ${opOf(p, Comparison.GT).symbol} ${num(p, "megabytes", 1000.0)} MB" },
        ConstraintSpec(
            id = CheckIds.VOLUME, category = "Volume", name = "Volume level", icon = "volume-medium",
            description = "Compares the volume of a stream, in percent.", synonyms = listOf("loud", "quiet", "media volume", "ringer volume"),
            fields = listOf(
                FieldSpec("stream", "Stream", FieldType.CHOICE, Value.Text("media"),
                    choices = listOf(Choice("media", "Media"), Choice("ring", "Ring"), Choice("alarm", "Alarm"), Choice("notification", "Notification"))),
                cmpField().copy(default = Value.Text("GTE")),
                FieldSpec("percent", "Percent", FieldType.NUMBER, Value.Num(50.0), min = 0.0, max = 100.0),
            ),
        ) { p -> "${p.str("stream", "media").replaceFirstChar { it.uppercase() }} volume ${opOf(p, Comparison.GTE).symbol} ${p.int("percent", 50)}%" },
        ConstraintSpec(
            id = CheckIds.UPTIME, category = "Date and time", name = "Time since boot", icon = "timer-outline",
            description = "Compares how long the phone has been on, in minutes.", synonyms = listOf("uptime", "restarted"),
            fields = listOf(cmpField().copy(default = Value.Text("GT")), FieldSpec("minutes", "Minutes", FieldType.NUMBER, Value.Num(10.0), min = 0.0)),
        ) { p -> "Up for ${opOf(p, Comparison.GT).symbol} ${num(p, "minutes", 10.0)} min" },
        ConstraintSpec(
            id = CheckIds.MONTH_DAY, category = "Date and time", name = "Day of month", icon = "calendar-month",
            description = "True on the chosen days of the month.", synonyms = listOf("first of the month", "payday", "1st"),
            fields = listOf(FieldSpec("days", "Days (1-31)", FieldType.JSON, null, required = true)),
        ) { p -> "On day ${p.ints("days").sorted().joinToString(", ")} of the month" },
        ConstraintSpec(
            id = CheckIds.MONTH, category = "Date and time", name = "Month of year", icon = "calendar-range",
            description = "True in the chosen months.", synonyms = listOf("season", "winter", "summer"),
            fields = listOf(FieldSpec("months", "Months (1-12)", FieldType.JSON, null, required = true)),
        ) { p -> "In month ${p.ints("months").sorted().joinToString(", ")}" },
        ConstraintSpec(
            id = CheckIds.DATE_RANGE, category = "Date and time", name = "Date range", icon = "calendar-range",
            description = "True between two dates, inclusive. Leave one side empty for no limit.", synonyms = listOf("holiday", "from", "until", "between dates"),
            fields = listOf(FieldSpec("from", "From (YYYY-MM-DD)", FieldType.TEXT, Value.Text("")), FieldSpec("to", "To (YYYY-MM-DD)", FieldType.TEXT, Value.Text(""))),
        ) { p -> "Between ${p.str("from").ifBlank { "any date" }} and ${p.str("to").ifBlank { "any date" }}" },
        ConstraintSpec(
            id = CheckIds.COMPARE, category = "Logic and Flow", name = "Compare values", icon = "code-not-equal-variant",
            description = "Compares two values. Both sides can use {{values}}. Numbers are compared as numbers.", synonyms = listOf("equals", "greater", "less", "if", "match"),
            fields = listOf(
                FieldSpec("left", "Left", FieldType.TEXT, Value.Text(""), templated = true),
                FieldSpec("op", "Comparison", FieldType.COMPARISON, Value.Text("EQ"), choices = Comparison.entries.map { Choice(it.name, it.symbol) }),
                FieldSpec("right", "Right", FieldType.TEXT, Value.Text(""), templated = true),
            ),
        ) { p -> "${p.str("left")} ${opOf(p, Comparison.EQ).symbol} ${p.str("right")}" },
        ConstraintSpec(
            id = CheckIds.FLOW_STATE, category = "Logic and Flow", name = "Flow state", icon = "state-machine",
            description = "Whether another Flow is running or on, ran recently, or ran N times today.", synonyms = listOf("already ran", "running", "recently"),
            fields = listOf(
                FieldSpec("flowId", "Flow", FieldType.FLOW, null, required = true),
                FieldSpec("kind", "Check", FieldType.CHOICE, Value.Text("running"),
                    choices = listOf(Choice("running", "Is running"), Choice("enabled", "Is on"), Choice("ran_within", "Ran within N minutes"), Choice("runs_today", "Runs today"))),
                FieldSpec("minutes", "Minutes", FieldType.NUMBER, Value.Num(60.0), min = 1.0, advanced = true),
                FieldSpec("count", "Count", FieldType.NUMBER, Value.Num(0.0), min = 0.0, advanced = true),
            ),
        ) { p -> "Flow ${p.str("flowId")}: ${p.str("kind", "running")}" },
        ConstraintSpec(
            id = CheckIds.TRIGGERED_BY, category = "Logic and Flow", name = "Triggered by", icon = "gesture-tap",
            description = "True when the run was started by a particular kind of trigger.", synonyms = listOf("started by", "from widget", "from tile", "manual"),
            fields = listOf(FieldSpec("type", "Trigger type id", FieldType.TEXT, Value.Text("manual"), required = true)),
        ) { p -> "Started by ${p.str("type")}" },

        later("sensor.light", "Sensors", "Light level", "brightness-5", "Compares ambient light.", "Needs the sensor layer; light and proximity can be read in the background."),
        later("sensor.orientation", "Sensors", "Face up / down", "screen-rotation", "Orientation of the phone.", "Continuous sensors need the Listener; planned after the sensor layer."),
        later("calendar.entry", "Date and time", "Calendar entry", "calendar-check", "Whether an event is on now.", "Needs the calendar permission flow.", listOf(Requirement.RuntimePermission("android.permission.READ_CALENDAR", "Calendar"))),
        later("sun.state", "Date and time", "Sunrise and sunset", "weather-sunset", "Before or after sunrise or sunset.", "Needs manual coordinates; planned with the location layer."),
        later("net.wifi_name", "Connectivity", "Wi-Fi name", "wifi", "The name of the connected Wi-Fi.", "Needs the location permission flow.", listOf(Requirement.RuntimePermission("android.permission.ACCESS_FINE_LOCATION", "Location"))),
        later("bluetooth.state", "Connectivity", "Bluetooth", "bluetooth", "Bluetooth on or a device connected.", "Needs the Bluetooth permission flow.", listOf(Requirement.RuntimePermission("android.permission.BLUETOOTH_CONNECT", "Bluetooth"))),
        no("ui.keyboard_showing", "Device state", "Keyboard showing", "Whether the keyboard is open.", "Android doesn't tell other apps. No alternative."),
        no("net.hotspot", "Connectivity", "Hotspot / USB tethering", "Whether tethering is on.", "There is no reliable public API. With Shizuku (later tier) this could be read."),
    )
}
