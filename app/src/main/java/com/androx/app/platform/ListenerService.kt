package com.androx.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.androx.app.MainActivity
import com.androx.app.R
import com.androx.app.core.trigger.ListenerPlan

/**
 * The opt-in Listener: one foreground service with a quiet notification. It only runs while an enabled Flow
 * has a trigger that needs live events, and only watches the sources those triggers need.
 */
class ListenerService : Service() {
    companion object {
        const val EXTRA_SOURCES = "sources"
        const val CHANNEL_ID = "listener"
        private const val NOTIFICATION_ID = 4001

        fun intent(context: Context, sources: Set<String>): Intent =
            Intent(context, ListenerService::class.java).putExtra(EXTRA_SOURCES, sources.toTypedArray())
    }

    private var source: SystemEventSource? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sources = intent?.getStringArrayExtra(EXTRA_SOURCES)?.toSet().orEmpty()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(sources.size),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (sources.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val s = source ?: SystemEventSource(applicationContext) { type, data -> EventBus.emit(type, data) }.also { source = it }
        s.update(sources)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        source?.stop()
        source = null
        super.onDestroy()
    }

    private fun buildNotification(count: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.listener_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.listener_channel_description)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_androx)
            .setContentTitle(getString(R.string.listener_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.listener_notification_text, count, count))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }
}

/** Starts and stops the Listener to match what the enabled Flows need. */
class ListenerController(private val context: Context) {
    enum class Result { STARTED, STOPPED, BLOCKED }

    /**
     * Call when Flows change, from the app or from a receiver. Android 12+ may refuse to start a foreground
     * service from the background; that is reported as [Result.BLOCKED] so the UI can offer a retry when the app is open.
     */
    fun apply(plan: ListenerPlan): Result {
        if (!plan.needed) {
            context.stopService(Intent(context, ListenerService::class.java))
            return Result.STOPPED
        }
        return try {
            ContextCompat.startForegroundService(context, ListenerService.intent(context, plan.sources))
            Result.STARTED
        } catch (e: Exception) {
            Result.BLOCKED
        }
    }
}
