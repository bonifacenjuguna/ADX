package com.androx.app.core.engine

import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.P
import com.androx.app.core.value.Value
import com.androx.app.model.Comparison
import java.time.LocalDate

/** The constraints added in 0.0.2. Each reads device state through [StateReader], so they work without any service. */
object ExtraChecks {
    private fun check(fn: CheckFn): CheckFn = fn

    private fun op(p: P, d: Comparison = Comparison.GT) = p.enum("op", Comparison.entries.toTypedArray(), d)

    private fun read(e: CheckEnv, key: String): Value =
        e.state.read(key) ?: throw IllegalStateException("this device doesn't report $key")

    private fun cmp(op: Comparison, left: Double, right: Double) = op.test(left.compareTo(right))

    /** "is on / is off" style checks. */
    private fun flag(key: String, wantKey: String, on: String, off: String) = check { p, e ->
        val want = p.bool(wantKey, true)
        val now = read(e, key).asBool()
        (now == want) to (if (now) on else off)
    }

    private fun number(key: String, label: String, unit: String, defaultLimit: Int, limitKey: String) = check { p, e ->
        val o = op(p)
        val limit = p.double(limitKey, defaultLimit.toDouble())
        val v = read(e, key).asNumber() ?: throw IllegalStateException("$key is not a number")
        cmp(o, v, limit) to "$label is ${com.androx.app.core.value.formatNumber(v)}$unit (needs ${o.symbol} ${com.androx.app.core.value.formatNumber(limit)}$unit)"
    }

    private fun parseDate(text: String): LocalDate? = try {
        if (text.isBlank()) null else LocalDate.parse(text.trim())
    } catch (e: Exception) {
        null
    }

    val all: Map<String, CheckFn> = mapOf(
        CheckIds.BATTERY_TEMP to number("battery.temp_c", "battery temperature", " °C", 40, "degrees"),
        CheckIds.CHARGER to check { p, e ->
            val want = p.str("type", "any")
            val now = read(e, "battery.charger").asText()
            val ok = when (want) {
                "any" -> now != "none"
                else -> now == want
            }
            ok to (if (now == "none") "no charger is connected" else "charging from $now")
        },
        CheckIds.NET_STATE to check { p, e ->
            val kind = p.str("kind", "wifi")
            val type = read(e, "net.type").asText()
            val vpn = e.state.read("net.vpn")?.asBool() ?: false
            val metered = e.state.read("net.metered")?.asBool() ?: false
            val ok = when (kind) {
                "wifi" -> type == "wifi"
                "mobile" -> type == "mobile"
                "vpn" -> vpn
                "metered" -> metered
                "offline" -> type == "none"
                else -> false
            }
            ok to "network is $type${if (vpn) " with VPN" else ""}${if (metered) ", metered" else ""}"
        },
        CheckIds.HEADPHONES to flag("audio.headphones", "connected", "headphones are connected", "no headphones are connected"),
        CheckIds.DND to flag("dnd.on", "on", "Do Not Disturb is on", "Do Not Disturb is off"),
        CheckIds.LOCKED to flag("device.locked", "locked", "the phone is locked", "the phone is unlocked"),
        CheckIds.TORCH to flag("torch.on", "on", "the flashlight is on", "the flashlight is off"),
        CheckIds.DARK_THEME to flag("ui.dark", "on", "dark theme is on", "dark theme is off"),
        CheckIds.STORAGE to number("storage.free_mb", "free storage", " MB", 1000, "megabytes"),
        CheckIds.UPTIME to number("uptime.minutes", "time since boot", " min", 10, "minutes"),
        CheckIds.VOLUME to check { p, e ->
            val stream = p.str("stream", "media").lowercase()
            val o = op(p, Comparison.GTE)
            val limit = p.int("percent", 50)
            val v = read(e, "volume.$stream").asNumber() ?: throw IllegalStateException("volume.$stream is not a number")
            cmp(o, v, limit.toDouble()) to "$stream volume is ${v.toInt()}% (needs ${o.symbol} $limit%)"
        },
        CheckIds.MONTH_DAY to check { p, e ->
            val days = p.ints("days")
            (e.now.dayOfMonth in days) to "today is day ${e.now.dayOfMonth} of the month (needs ${days.sorted().joinToString(", ")})"
        },
        CheckIds.MONTH to check { p, e ->
            val months = p.ints("months")
            (e.now.monthValue in months) to "this is month ${e.now.monthValue} (needs ${months.sorted().joinToString(", ")})"
        },
        CheckIds.DATE_RANGE to check { p, e ->
            val from = parseDate(p.str("from"))
            val to = parseDate(p.str("to"))
            val today = e.now.toLocalDate()
            val ok = (from == null || !today.isBefore(from)) && (to == null || !today.isAfter(to))
            ok to "today is $today (range ${from ?: "any"} to ${to ?: "any"})"
        },
        CheckIds.COMPARE to check { p, e ->
            val left = e.render(p.str("left"))
            val right = e.render(p.str("right"))
            val o = op(p, Comparison.EQ)
            val ln = Value.Text(left).asNumber()
            val rn = Value.Text(right).asNumber()
            val result = if (ln != null && rn != null) cmp(o, ln, rn) else o.test(left.compareTo(right))
            result to "\"$left\" ${o.symbol} \"$right\""
        },
        CheckIds.FLOW_STATE to check { p, e ->
            val id = p.str("flowId")
            when (p.str("kind", "running")) {
                "running" -> {
                    val now = read(e, "flow.$id.running").asBool()
                    now to (if (now) "that Flow is running" else "that Flow is not running")
                }
                "enabled" -> {
                    val now = read(e, "flow.$id.enabled").asBool()
                    now to (if (now) "that Flow is on" else "that Flow is off")
                }
                "ran_within" -> {
                    val limit = p.int("minutes", 60)
                    val ago = read(e, "flow.$id.last_run_ago_min").asNumber() ?: Double.MAX_VALUE
                    (ago <= limit) to "that Flow last ran ${if (ago == Double.MAX_VALUE) "never" else "${ago.toInt()} min ago"}"
                }
                else -> {
                    val o = op(p)
                    val count = p.int("count", 0)
                    val runs = read(e, "flow.$id.runs_today").asNumber() ?: 0.0
                    cmp(o, runs, count.toDouble()) to "that Flow ran ${runs.toInt()} times today (needs ${o.symbol} $count)"
                }
            }
        },
        CheckIds.TRIGGERED_BY to check { p, e ->
            val want = p.str("type")
            val got = e.triggerTypeId
            (got == want) to "this run was started by ${got ?: "nothing in particular"}"
        },
    )
}
