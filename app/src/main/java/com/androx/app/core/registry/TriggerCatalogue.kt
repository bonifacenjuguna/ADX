package com.androx.app.core.registry

import com.androx.app.core.model.TriggerIds
import com.androx.app.core.value.Value
import com.androx.app.core.value.ValueType

/** Triggers added in 0.0.2. Ready ones have a detector; the rest are visible "Coming later" or "Not possible" slots. */
object TriggerCatalogue {
    private fun choices(vararg pairs: Pair<String, String>) = pairs.map { Choice(it.first, it.second) }
    private val any = Choice("any", "Either")

    private fun stateTrigger(
        id: String, category: String, name: String, icon: String, desc: String, syn: List<String>,
        on: Pair<String, String>, off: Pair<String, String>, label: String = "When", cost: BatteryCost = BatteryCost.LOW,
        requires: List<Requirement> = listOf(Requirement.Listener), summary: (String) -> String,
    ) = TriggerSpec(
        id = id, category = category, name = name, icon = icon, description = desc, synonyms = syn,
        requires = requires, reliability = Reliability.NEEDS_LISTENER, batteryCost = cost,
        fields = listOf(FieldSpec("state", label, FieldType.CHOICE, Value.Text("any"), choices = listOf(any, Choice(on.first, on.second), Choice(off.first, off.second)))),
        outputs = listOf(OutputSpec("state", "State", ValueType.TEXT)),
    ) { p -> summary(p.str("state", "any")) }

    private fun internalTrigger(id: String, name: String, icon: String, desc: String, syn: List<String>, fields: List<FieldSpec> = emptyList(), outputs: List<OutputSpec> = emptyList(), summary: (com.androx.app.core.model.P) -> String) =
        TriggerSpec(id = id, category = "Androx", name = name, icon = icon, description = desc, synonyms = syn, fields = fields, outputs = outputs, summary = summary)

    private fun later(id: String, category: String, name: String, icon: String, desc: String, note: String, requires: List<Requirement> = emptyList(), editions: Set<Edition> = setOf(Edition.FULL, Edition.PLAY_SAFE)) =
        TriggerSpec(id = id, category = category, name = name, icon = icon, description = desc, requires = requires, editions = editions,
            availability = Availability.LATER, note = note) { "Coming later" }

    private fun no(id: String, category: String, name: String, desc: String, note: String) =
        TriggerSpec(id = id, category = category, name = name, icon = "block-helper", description = desc,
            availability = Availability.NOT_POSSIBLE, note = note) { "Not possible on Android" }

    private val perm = { p: String, l: String -> listOf<Requirement>(Requirement.RuntimePermission(p, l)) }

    val specs: List<TriggerSpec> = listOf(
        TriggerSpec(
            id = TriggerIds.MONTHLY, category = "Date and time", name = "Day of month", icon = "calendar-month", timed = true,
            description = "Runs on a day of the month at a time. Days past the end of a short month run on its last day.",
            synonyms = listOf("monthly", "first of the month", "payday", "bill"),
            fields = listOf(
                FieldSpec("day", "Day (1-31)", FieldType.NUMBER, Value.Num(1.0), min = 1.0, max = 31.0),
                FieldSpec("hour", "Hour", FieldType.TIME, Value.Num(8.0)),
                FieldSpec("minute", "Minute", FieldType.TIME, Value.Num(0.0)),
            ),
        ) { p -> "Day ${p.int("day", 1)} of each month at ${com.androx.app.model.formatClock(p.int("hour", 8), p.int("minute", 0))}" },
        TriggerSpec(
            id = TriggerIds.TIME_CHANGED, category = "Date and time", name = "Time or time zone changed", icon = "clock-alert-outline",
            description = "Runs when the system clock or time zone changes.", synonyms = listOf("timezone", "travel", "daylight saving", "clock set"),
            outputs = listOf(OutputSpec("timezone", "Time zone", ValueType.TEXT)),
        ) { "When the time or time zone changes" },
        stateTrigger(TriggerIds.SCREEN, "Device", "Screen on or off", "cellphone", "Runs when the screen turns on or off.", listOf("display", "wake", "sleep"),
            "on" to "Turns on", "off" to "Turns off", cost = BatteryCost.LOW) { s -> if (s == "any") "When the screen turns on or off" else "When the screen turns $s" },
        TriggerSpec(
            id = TriggerIds.UNLOCKED, category = "Device", name = "Device unlocked", icon = "lock-open-outline",
            description = "Runs when you unlock the phone.", synonyms = listOf("unlock", "keyguard", "user present"),
            requires = listOf(Requirement.Listener), reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
        ) { "When the device is unlocked" },
        stateTrigger(TriggerIds.POWER, "Battery and power", "Power connected or disconnected", "power-plug", "Runs when a charger is plugged in or removed.", listOf("charger", "plug", "unplug", "charging"),
            "connected" to "Connected", "disconnected" to "Disconnected") { s -> if (s == "any") "When power is connected or removed" else "When power is $s" },
        TriggerSpec(
            id = TriggerIds.BATTERY_CROSSING, category = "Battery and power", name = "Battery level crossing", icon = "battery-alert",
            description = "Runs when the battery drops below, or rises to, a level. Use \"hold\" to require it for some minutes first.",
            synonyms = listOf("low battery", "full", "percent", "20%"),
            requires = listOf(Requirement.Listener), reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            fields = listOf(
                FieldSpec("direction", "When it", FieldType.CHOICE, Value.Text("below"), choices = choices("below" to "Drops below", "above" to "Rises to or above")),
                FieldSpec("percent", "Percent", FieldType.NUMBER, Value.Num(20.0), min = 1.0, max = 100.0),
            ),
            outputs = listOf(OutputSpec("level", "Battery level", ValueType.NUMBER), OutputSpec("previous", "Previous level", ValueType.NUMBER)),
        ) { p -> "Battery ${if (p.str("direction", "below") == "below") "drops below" else "rises to"} ${p.int("percent", 20)}%" },
        stateTrigger(TriggerIds.BATTERY_SAVER, "Battery and power", "Battery Saver on or off", "battery-alert", "Runs when Battery Saver changes.", listOf("power saver", "low power"),
            "on" to "Turns on", "off" to "Turns off") { s -> if (s == "any") "When Battery Saver changes" else "When Battery Saver turns $s" },
        stateTrigger(TriggerIds.AIRPLANE, "Connectivity", "Airplane mode", "airplane", "Runs when Airplane mode changes.", listOf("flight mode"),
            "on" to "Turns on", "off" to "Turns off") { s -> if (s == "any") "When Airplane mode changes" else "When Airplane mode turns $s" },
        stateTrigger(TriggerIds.HEADPHONES, "Device", "Headphones inserted or removed", "headphones", "Runs when headphones or a headset connect or disconnect.", listOf("earphones", "headset", "plug", "bluetooth audio"),
            "plugged" to "Connected", "unplugged" to "Disconnected") { s -> if (s == "any") "When headphones connect or disconnect" else "When headphones are $s" },
        TriggerSpec(
            id = TriggerIds.NETWORK, category = "Connectivity", name = "Network connected or lost", icon = "wifi",
            description = "Runs when the phone gets or loses its default network. {{trigger.type}} says wifi, mobile or other.",
            synonyms = listOf("internet", "wifi", "offline", "online", "connection"),
            requires = listOf(Requirement.Listener, Requirement.RuntimePermission("android.permission.ACCESS_NETWORK_STATE", "Network state")),
            reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            fields = listOf(FieldSpec("state", "When", FieldType.CHOICE, Value.Text("any"), choices = listOf(any, Choice("connected", "Connected"), Choice("lost", "Lost")))),
            outputs = listOf(OutputSpec("state", "State", ValueType.TEXT), OutputSpec("type", "Network type", ValueType.TEXT)),
        ) { p -> when (p.str("state", "any")) { "connected" -> "When a network connects"; "lost" -> "When the network is lost"; else -> "When the network changes" } },
        stateTrigger(TriggerIds.VPN, "Connectivity", "VPN state", "vpn", "Runs when a VPN connects or disconnects.", listOf("tunnel", "wireguard"),
            "on" to "Connects", "off" to "Disconnects") { s -> if (s == "any") "When a VPN connects or disconnects" else "When a VPN ${if (s == "on") "connects" else "disconnects"}" },
        TriggerSpec(
            id = TriggerIds.RINGER, category = "Device", name = "Ringer mode changed", icon = "volume-off",
            description = "Runs when the ringer switches between normal, vibrate and silent.", synonyms = listOf("silent", "mute", "vibrate"),
            requires = listOf(Requirement.Listener), reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            fields = listOf(FieldSpec("mode", "Mode", FieldType.CHOICE, Value.Text("any"), choices = listOf(Choice("any", "Any change"), Choice("normal", "Normal"), Choice("vibrate", "Vibrate"), Choice("silent", "Silent")))),
            outputs = listOf(OutputSpec("mode", "Mode", ValueType.TEXT)),
        ) { p -> if (p.str("mode", "any") == "any") "When the ringer mode changes" else "When the ringer becomes ${p.str("mode")}" },
        stateTrigger(TriggerIds.DND, "Device", "Do Not Disturb changed", "bell-off-outline", "Runs when Do Not Disturb turns on or off.", listOf("dnd", "quiet", "focus"),
            "on" to "Turns on", "off" to "Turns off") { s -> if (s == "any") "When Do Not Disturb changes" else "When Do Not Disturb turns $s" },
        stateTrigger(TriggerIds.TORCH, "Device", "Flashlight on or off", "flashlight", "Runs when the flashlight changes.", listOf("torch"),
            "on" to "Turns on", "off" to "Turns off") { s -> if (s == "any") "When the flashlight changes" else "When the flashlight turns $s" },
        internalTrigger(TriggerIds.INTENT, "Intent received", "send", "Runs when another app (Tasker, adb, a shortcut) sends Androx's trigger intent with the secret token. Off until External access is enabled.",
            listOf("broadcast", "tasker", "adb", "external"),
            fields = listOf(FieldSpec("name", "Name (optional)", FieldType.TEXT, Value.Text(""))),
            outputs = listOf(OutputSpec("name", "Name", ValueType.TEXT), OutputSpec("extra", "Extra text", ValueType.TEXT))) { p -> if (p.str("name").isBlank()) "When another app sends a trigger" else "When another app sends \"${p.str("name")}\"" },
        internalTrigger(TriggerIds.SHARE, "Share to Androx", "share-variant", "Runs when you share text or a file to Androx from another app.",
            listOf("share sheet", "send to"), outputs = listOf(OutputSpec("text", "Shared text", ValueType.TEXT), OutputSpec("uri", "Shared file", ValueType.TEXT))) { "When you share something to Androx" },
        internalTrigger(TriggerIds.FLOW_FINISHED, "Flow finished or failed", "flag-checkered", "Runs when another Flow finishes.", listOf("after", "chain", "failed", "completed"),
            fields = listOf(
                FieldSpec("flowId", "Flow (empty = any)", FieldType.FLOW, Value.Text("")),
                FieldSpec("outcome", "Result", FieldType.CHOICE, Value.Text("any"), choices = choices("any" to "Any", "success" to "Succeeded", "failed" to "Failed")),
            ),
            outputs = listOf(OutputSpec("flow.name", "Flow name", ValueType.TEXT), OutputSpec("outcome", "Result", ValueType.TEXT), OutputSpec("message", "Message", ValueType.TEXT))) { p ->
            "When ${p.str("flowId").ifBlank { "a Flow" }} ${when (p.str("outcome", "any")) { "success" -> "succeeds"; "failed" -> "fails"; else -> "finishes" }}"
        },
        internalTrigger(TriggerIds.FLOW_ENABLED, "Flow turned on or off", "toggle-switch-outline", "Runs when a Flow is switched on or off.", listOf("enabled", "disabled"),
            fields = listOf(
                FieldSpec("flowId", "Flow (empty = any)", FieldType.FLOW, Value.Text("")),
                FieldSpec("state", "When", FieldType.CHOICE, Value.Text("any"), choices = choices("any" to "Either", "on" to "Turned on", "off" to "Turned off")),
            )) { "When a Flow is switched on or off" },
        internalTrigger(TriggerIds.VARIABLE_CHANGED, "Variable changed", "variable", "Runs when a variable gets a new value.", listOf("watch", "set", "update"),
            fields = listOf(FieldSpec("name", "Variable (empty = any)", FieldType.VARIABLE_NAME, Value.Text(""))),
            outputs = listOf(OutputSpec("name", "Variable", ValueType.TEXT), OutputSpec("value", "New value", ValueType.TEXT), OutputSpec("old", "Old value", ValueType.TEXT))) { p ->
            "When ${p.str("name").ifBlank { "any variable" }} changes"
        },
        internalTrigger(TriggerIds.CALLED, "Called by another Flow", "call-received", "Only runs when another Flow uses Run Flow on it. Inputs arrive as {{input.name}}.", listOf("subflow", "function", "helper")) { "Only when another Flow runs it" },
        internalTrigger(TriggerIds.APP_OPENED, "Androx opened", "open-in-app", "Runs when you open Androx.", listOf("launch", "start")) { "When Androx is opened" },

        later("ui.tile", "Androx", "Quick Settings tile", "dock-window", "Starts when you tap a tile.", "Built with the integration layer (three tiles, each bound to a Flow)."),
        later("ui.widget", "Androx", "Widget button", "widgets", "Starts when you tap a home-screen widget.", "Built with the integration layer (Glance widgets)."),
        later("ui.notification_button", "Androx", "Notification button", "gesture-tap-button", "Starts from a button in a notification.", "Built with the notification builder."),
        later("ui.shortcut", "Androx", "App shortcut", "cursor-default-click", "Starts from a launcher shortcut.", "Built with the integration layer."),
        later("time.stopwatch", "Date and time", "Stopwatch", "timer-outline", "Runs when a stopwatch reaches a time.", "Needs the stopwatch store."),
        later("notification.received", "Apps", "Notification received", "bell-badge-outline", "Reads other apps' notifications.", "Needs the notification-access flow and a listener service.", listOf(Requirement.SpecialAccess("notification_listener", "Notification access"))),
        later("media.track", "Apps", "Media track changed", "music", "Runs when the playing track changes.", "Needs notification access.", listOf(Requirement.SpecialAccess("notification_listener", "Notification access"))),
        later("bluetooth.device", "Connectivity", "Bluetooth device", "bluetooth", "Connect or disconnect of a device.", "Needs the Bluetooth permission flow.", perm("android.permission.BLUETOOTH_CONNECT", "Bluetooth")),
        later("net.wifi_name", "Connectivity", "Wi-Fi name", "wifi", "Joins or leaves a named Wi-Fi.", "Needs the location permission flow.", perm("android.permission.ACCESS_FINE_LOCATION", "Location")),
        later("app.launched", "Apps", "App launched or closed", "apps", "Runs when an app opens.", "Needs usage access.", listOf(Requirement.SpecialAccess("usage_access", "Usage access"))),
        later("calendar.event", "Date and time", "Calendar event", "calendar-check", "Runs at the start or end of an event.", "Needs the calendar permission flow.", perm("android.permission.READ_CALENDAR", "Calendar")),
        later("photo.taken", "Device", "Photo taken", "camera", "Runs when a new photo appears.", "Needs media access.", perm("android.permission.READ_MEDIA_IMAGES", "Photos")),
        later("camera.in_use", "Device", "Camera in use", "camera-outline", "Runs when an app uses the camera.", "Needs the camera-availability listener."),
        later("mic.recording", "Device", "Microphone recording", "microphone", "Runs when an app records audio.", "Needs the audio-callback listener."),
        later("audio.playing", "Device", "Music or sound playing", "music-note", "Runs when audio starts or stops.", "Needs the playback listener."),
        later("ui.floating_button", "Androx", "Floating button", "circle-outline", "A button drawn over other apps.", "Needs display-over-other-apps access.", listOf(Requirement.SpecialAccess("overlay", "Display over other apps"))),
        later("media.button", "Device", "Media button", "skip-next", "Headset or remote buttons.", "Needs a media session."),
        later(TriggerIdsLater.SMS, "Phone", "SMS received", "message-text", "Runs when a text arrives.", "Full edition only; Play restricts SMS.", perm("android.permission.RECEIVE_SMS", "SMS"), setOf(Edition.FULL)),
        later(TriggerIdsLater.CALL, "Phone", "Call incoming, active, ended, missed", "phone", "Phone-call events.", "Full edition only; Play restricts call logs.", perm("android.permission.READ_PHONE_STATE", "Phone"), setOf(Edition.FULL)),
        later(TriggerIdsLater.PACKAGES, "Apps", "App installed, removed, updated", "package-variant", "Package changes.", "Full edition only; needs package visibility.", emptyList(), setOf(Edition.FULL)),
        later("location.geofence", "Location", "Geofence", "map-marker-radius", "Enter or leave a place.", "Needs background location and a map; planned with the location layer."),
        later("sensor.motion", "Sensors", "Shake, flip, light, proximity", "vibrate", "Motion and light sensors.", "Continuous sensors need the Listener; planned after the sensor layer."),
        later("sun.event", "Date and time", "Sunrise and sunset", "weather-sunset", "At sunrise or sunset.", "Needs manual coordinates."),
        later("a11y.event", "Accessibility", "UI click, screen content, keyboard, fingerprint gesture", "gesture-tap", "Accessibility-based triggers.", "Full edition only; needs the accessibility service.", listOf(Requirement.SpecialAccess("accessibility", "Accessibility service")), setOf(Edition.FULL)),
        no("logcat.message", "Device", "Logcat message", "Reads the system log.", "Needs root."),
        no("tasker.plugin", "Androx", "Tasker / Locale plugin", "Plugin protocol.", "Use the secured trigger intent (Intent received) instead."),
        no("clipboard.changed", "Device", "Clipboard changed", "Background clipboard watching.", "Android blocks this since version 10. Use Share to Androx."),
        no("button.long_press", "Device", "Home or Power button long-press", "Hardware gesture.", "Only possible through accessibility."),
        no("net.tethering", "Connectivity", "Hotspot / USB tethering state", "Tethering changes.", "There is no reliable public API."),
        no("device.failed_login", "Device", "Failed login attempt", "Unlock failures.", "Needs a device-admin app, which is heavy and risky."),
        no("shizuku.stopped", "Androx", "Shizuku stopped", "Shizuku state.", "Shizuku support is a later, optional tier."),
    )
}

internal object TriggerIdsLater {
    const val SMS = "sms.received"
    const val CALL = "call.event"
    const val PACKAGES = "app.packages"
}
