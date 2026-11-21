package com.androx.app.core.trigger

import com.androx.app.core.engine.CheckEnv
import com.androx.app.core.model.P
import com.androx.app.core.model.TriggerIds
import com.androx.app.core.value.Value

/** Decides whether an incoming event is the one a trigger entry is waiting for. */
fun interface EventMatcher {
    fun matches(p: P, data: Map<String, Value>): Boolean
}

/** For "only after holding N minutes": is the condition behind the trigger still true right now? */
fun interface HoldCheck {
    fun holds(p: P, env: CheckEnv): Boolean
}

object EventMatchers {
    private fun state() = EventMatcher { p, d ->
        val want = p.str("state", "any")
        want == "any" || want == d["state"]?.asText()
    }

    val matchers: Map<String, EventMatcher> = mapOf(
        TriggerIds.SCREEN to state(),
        TriggerIds.POWER to state(),
        TriggerIds.BATTERY_SAVER to state(),
        TriggerIds.AIRPLANE to state(),
        TriggerIds.HEADPHONES to state(),
        TriggerIds.NETWORK to state(),
        TriggerIds.VPN to state(),
        TriggerIds.DND to state(),
        TriggerIds.TORCH to state(),
        TriggerIds.RINGER to EventMatcher { p, d ->
            val want = p.str("mode", "any")
            want == "any" || want == d["mode"]?.asText()
        },
        TriggerIds.BATTERY_CROSSING to EventMatcher { p, d ->
            val limit = p.int("percent", 20)
            val level = d["level"]?.asNumber()?.toInt() ?: return@EventMatcher false
            val previous = d["previous"]?.asNumber()?.toInt() ?: return@EventMatcher false
            if (p.str("direction", "below") == "below") previous >= limit && level < limit
            else previous < limit && level >= limit
        },
        TriggerIds.FLOW_FINISHED to EventMatcher { p, d ->
            val flowId = p.str("flowId")
            val outcome = p.str("outcome", "any")
            (flowId.isBlank() || flowId == d["flow.id"]?.asText()) && (outcome == "any" || outcome == d["outcome"]?.asText())
        },
        TriggerIds.FLOW_ENABLED to EventMatcher { p, d ->
            val flowId = p.str("flowId")
            val want = p.str("state", "any")
            (flowId.isBlank() || flowId == d["flow.id"]?.asText()) && (want == "any" || want == d["state"]?.asText())
        },
        TriggerIds.VARIABLE_CHANGED to EventMatcher { p, d -> p.str("name").isBlank() || p.str("name") == d["name"]?.asText() },
        TriggerIds.INTENT to EventMatcher { p, d -> p.str("name").isBlank() || p.str("name") == d["name"]?.asText() },
    )

    val holds: Map<String, HoldCheck> = mapOf(
        TriggerIds.BATTERY_CROSSING to HoldCheck { p, env ->
            val level = env.device.batteryLevel()
            val limit = p.int("percent", 20)
            if (p.str("direction", "below") == "below") level < limit else level >= limit
        },
        TriggerIds.POWER to HoldCheck { p, env ->
            when (p.str("state", "any")) {
                "connected" -> env.device.isCharging()
                "disconnected" -> !env.device.isCharging()
                else -> true
            }
        },
        TriggerIds.BATTERY_SAVER to HoldCheck { p, env ->
            when (p.str("state", "any")) {
                "on" -> env.device.isPowerSaver()
                "off" -> !env.device.isPowerSaver()
                else -> true
            }
        },
        TriggerIds.SCREEN to HoldCheck { p, env ->
            when (p.str("state", "any")) {
                "on" -> env.device.isScreenOn()
                "off" -> !env.device.isScreenOn()
                else -> true
            }
        },
    )

    /**
     * Which live system source feeds each trigger. Triggers listed here need the Listener service;
     * triggers that are not listed (time, boot, internal events) work without it.
     */
    val sources: Map<String, String> = mapOf(
        TriggerIds.SCREEN to "screen",
        TriggerIds.UNLOCKED to "unlock",
        TriggerIds.POWER to "power",
        TriggerIds.BATTERY_CROSSING to "battery",
        TriggerIds.BATTERY_SAVER to "saver",
        TriggerIds.AIRPLANE to "airplane",
        TriggerIds.HEADPHONES to "headphones",
        TriggerIds.NETWORK to "network",
        TriggerIds.VPN to "vpn",
        TriggerIds.RINGER to "ringer",
        TriggerIds.DND to "dnd",
        TriggerIds.TORCH to "torch",
    )
}
