package com.androx.app.platform

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.androx.app.core.engine.StateReader
import com.androx.app.core.value.Value

/** True when wired, USB or Bluetooth (A2DP) audio is connected. Needs no permission. */
internal fun headphonesConnected(context: Context): Boolean {
    val am = context.getSystemService(AudioManager::class.java) ?: return false
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        when (it.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            -> true
            else -> false
        }
    }
}

internal fun networkTypeOf(caps: NetworkCapabilities?): String = when {
    caps == null -> "none"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
    else -> "other"
}

/** Device state for constraints. Every read is a quick system call; nothing runs in the background. */
class AndroidStateReader(private val context: Context) : StateReader {
    private fun battery(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun read(key: String): Value? = try {
        readUnsafe(key)
    } catch (e: Exception) {
        null
    }

    private fun readUnsafe(key: String): Value? = when (key) {
        "battery.temp_c" -> battery()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { Value.Num(it / 10.0) }
        "battery.charger" -> Value.Text(
            when (battery()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "none"
                else -> "ac"
            },
        )
        "net.type" -> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            Value.Text(networkTypeOf(cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }))
        }
        "net.vpn" -> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            Value.Bool(caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        }
        "net.metered" -> Value.Bool(context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true)
        "audio.headphones" -> Value.Bool(headphonesConnected(context))
        "dnd.on" -> {
            val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
            Value.Bool(filter != null && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN && filter != NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        "device.locked" -> Value.Bool(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
        "torch.on" -> TorchState.value?.let { Value.Bool(it) }
        "ui.dark" -> Value.Bool(
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
        "storage.free_mb" -> Value.Num(StatFs(Environment.getDataDirectory().path).availableBytes / (1024.0 * 1024.0))
        "uptime.minutes" -> Value.Num(SystemClock.elapsedRealtime() / 60_000.0)
        "volume.media", "volume.ring", "volume.alarm", "volume.notification" -> {
            val am = context.getSystemService(AudioManager::class.java)
            val stream = when (key.removePrefix("volume.")) {
                "media" -> AudioManager.STREAM_MUSIC
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_NOTIFICATION
            }
            val max = am?.getStreamMaxVolume(stream) ?: 0
            if (am == null || max <= 0) null else Value.Num(am.getStreamVolume(stream) * 100.0 / max)
        }
        else -> null
    }
}
