package com.androx.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.androx.app.core.model.TriggerIds
import com.androx.app.core.value.Value

/**
 * Watches live system events, but only the sources the enabled Flows need. Each source is registered when first
 * needed and unregistered when no Flow needs it any more, so nothing runs for triggers nobody uses.
 */
class SystemEventSource(
    private val context: Context,
    private val sink: (typeId: String, data: Map<String, Value>) -> Unit,
) {
    private val running = HashMap<String, () -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized
    fun update(sources: Set<String>) {
        running.keys.filter { it !in sources }.forEach { key -> running.remove(key)?.invoke() }
        for (source in sources) {
            if (source in running) continue
            val stopper = try {
                start(source)
            } catch (e: Exception) {
                null
            }
            if (stopper != null) running[source] = stopper
        }
    }

    @Synchronized
    fun stop() {
        running.values.forEach { runCatching { it() } }
        running.clear()
    }

    @Synchronized
    fun activeSources(): Set<String> = running.keys.toSet()

    private fun text(s: String) = Value.Text(s)

    private fun receiver(filter: IntentFilter, deliver: (Intent) -> Unit): Pair<() -> Unit, Intent?> {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = deliver(intent)
        }
        val sticky = ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return { context.unregisterReceiver(r) } to sticky
    }

    private fun start(source: String): (() -> Unit)? = when (source) {
        "screen" -> receiver(IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }) {
            sink(TriggerIds.SCREEN, mapOf("state" to text(if (it.action == Intent.ACTION_SCREEN_ON) "on" else "off")))
        }.first
        "unlock" -> receiver(IntentFilter(Intent.ACTION_USER_PRESENT)) { sink(TriggerIds.UNLOCKED, emptyMap()) }.first
        "power" -> receiver(IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }) {
            sink(TriggerIds.POWER, mapOf("state" to text(if (it.action == Intent.ACTION_POWER_CONNECTED) "connected" else "disconnected")))
        }.first
        "battery" -> {
            var last = -1
            fun level(i: Intent): Int {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                return if (l < 0 || s <= 0) -1 else l * 100 / s
            }
            val (stop, sticky) = receiver(IntentFilter(Intent.ACTION_BATTERY_CHANGED)) { i ->
                val now = level(i)
                if (now >= 0 && last >= 0 && now != last) {
                    sink(TriggerIds.BATTERY_CROSSING, mapOf("level" to Value.Num(now.toDouble()), "previous" to Value.Num(last.toDouble())))
                }
                if (now >= 0) last = now
            }
            sticky?.let { last = level(it) }
            stop
        }
        "saver" -> receiver(IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) {
            val on = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            sink(TriggerIds.BATTERY_SAVER, mapOf("state" to text(if (on) "on" else "off")))
        }.first
        "airplane" -> receiver(IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
            sink(TriggerIds.AIRPLANE, mapOf("state" to text(if (it.getBooleanExtra("state", false)) "on" else "off")))
        }.first
        "headphones" -> {
            val am = context.getSystemService(AudioManager::class.java)
            var last = headphonesConnected(context)
            val cb = object : AudioDeviceCallback() {
                private fun check() {
                    val now = headphonesConnected(context)
                    if (now != last) {
                        last = now
                        sink(TriggerIds.HEADPHONES, mapOf("state" to text(if (now) "plugged" else "unplugged")))
                    }
                }
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = check()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = check()
            }
            am.registerAudioDeviceCallback(cb, handler)
            ({ am.unregisterAudioDeviceCallback(cb) })
        }
        "network" -> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    sink(TriggerIds.NETWORK, mapOf("state" to text("connected"), "type" to text(networkTypeOf(cm.getNetworkCapabilities(network)))))
                }
                override fun onLost(network: Network) {
                    sink(TriggerIds.NETWORK, mapOf("state" to text("lost"), "type" to text("none")))
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            ({ cm.unregisterNetworkCallback(cb) })
        }
        "vpn" -> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { sink(TriggerIds.VPN, mapOf("state" to text("on"))) }
                override fun onLost(network: Network) { sink(TriggerIds.VPN, mapOf("state" to text("off"))) }
            }
            cm.registerNetworkCallback(request, cb)
            ({ cm.unregisterNetworkCallback(cb) })
        }
        "ringer" -> receiver(IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
            val mode = when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            sink(TriggerIds.RINGER, mapOf("mode" to text(mode)))
        }.first
        "dnd" -> receiver(IntentFilter(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)) {
            val f = context.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
            val on = f != null && f != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN && f != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            sink(TriggerIds.DND, mapOf("state" to text(if (on) "on" else "off")))
        }.first
        "torch" -> {
            val cm = context.getSystemService(CameraManager::class.java)
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (TorchState.value != enabled) {
                        val first = TorchState.value == null
                        TorchState.value = enabled
                        // The first callback only reports the current state; it is not a change.
                        if (!first) sink(TriggerIds.TORCH, mapOf("state" to text(if (enabled) "on" else "off")))
                    }
                }
            }
            cm.registerTorchCallback(cb, handler)
            ({ cm.unregisterTorchCallback(cb); TorchState.value = null })
        }
        else -> null
    }
}
