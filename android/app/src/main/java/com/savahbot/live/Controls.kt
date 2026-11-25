package com.savahbot.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews

/**
 * The notification with the overlay's buttons. It has two forms that share one id, so one replaces the other in place:
 *  - while the overlay is open: Hide/Show, Lock/Unlock, Reset, Open, Stop (posted by [OverlayService] as its foreground notification);
 *  - while it is not (if "Keep controls in notifications" is on): Start, Open, so the overlay can be switched on without opening the app.
 * Buttons only: the connection status is already shown by the floating window, so the notification does not repeat it.
 */
object Controls {
    const val CHANNEL = "savahlive_overlay"
    const val NOTE_ID = 1
    const val ACTION_OFF = "com.savahbot.live.CONTROLS_OFF"

    /**
     * The notification's Off button: takes the "overlay is off" notification down, and keeps it down until the overlay has been used again (Start, then Stop
     * brings it back). It does not change the Settings choice, so one tap never switches the controls off for good by accident.
     */
    fun turnOff(ctx: Context) {
        Store(ctx).idleHidden = true
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
    }

    private const val GREEN = 0xFF2ECC71.toInt()
    private const val AMBER = 0xFFF5B301.toInt()
    private const val GREY = 0xFF8E9AAF.toInt()
    private const val RED = 0xFFE5242B.toInt()

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "SavahLive overlay", NotificationManager.IMPORTANCE_LOW))
    }

    /**
     * Puts the "overlay is off" notification up (or takes it down), following the setting. Never touches the notification of a running overlay.
     * [force] is for when the overlay has just been stopped or the setting was just switched on: it also overrides a earlier tap on the Off button.
     */
    fun refreshIdle(ctx: Context, force: Boolean = false) {
        try {
            if (OverlayService.running) return
            val store = Store(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (force) store.idleHidden = false
            if (store.controlsAlways && !store.idleHidden) {
                ensureChannel(ctx)
                nm.notify(NOTE_ID, build(ctx, "Offline", false, false, false))
            } else {
                nm.cancel(NOTE_ID)
            }
        } catch (e: Exception) {
            // a missing notification must never crash anything
        }
    }

    /** One short word for the expanded card, from the status text the app keeps. */
    private fun word(text: String): String = when {
        text.startsWith("Connected") -> "Online"
        text.startsWith("Demo") -> "Demo"
        text.startsWith("Starting") || text.startsWith("Connecting") -> "Connecting"
        text.startsWith("Reconnect") -> "Reconnecting"
        text.startsWith("Waiting") -> "Waiting"
        text.startsWith("Paused") -> "Paused"
        text.startsWith("Unpaired") -> "Unpaired"
        else -> "Offline"
    }

    /** Never fails: if the custom layout cannot be built for some reason, a plain notification (title and status word) is shown instead. */
    fun build(ctx: Context, text: String, locked: Boolean, panelOpen: Boolean, running: Boolean): Notification {
        return try {
            buildCustom(ctx, text, locked, panelOpen, running)
        } catch (e: Exception) {
            android.util.Log.e("SavahLive", "notification layout failed", e)
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("SavahLive")
                .setContentText(word(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
        }
    }

    private fun buildCustom(ctx: Context, text: String, locked: Boolean, panelOpen: Boolean, running: Boolean): Notification {
        val store = Store(ctx)
        val pkg = ctx.packageName
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun svc(code: Int, action: String) = PendingIntent.getService(ctx, code, Intent(ctx, OverlayService::class.java).setAction(action), flags)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
        val offI = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, BootReceiver::class.java).setAction(ACTION_OFF), flags)
        val stopI = svc(1, OverlayService.ACTION_STOP)
        val lockI = svc(2, OverlayService.ACTION_LOCK)
        val toggleI = svc(3, OverlayService.ACTION_TOGGLE)
        val resetI = svc(4, OverlayService.ACTION_RESET)
        // Start: straight from the notification when the phone is ready, otherwise open the app so the missing step can be done
        val ready = Settings.canDrawOverlays(ctx) && store.isPaired
        val startI = if (ready) PendingIntent.getForegroundService(ctx, 6, Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_START), flags) else open

        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val ink = if (night) 0xFFE8EAED.toInt() else 0xFF3C4043.toInt()
        val chip = if (night) R.drawable.nc_chip_dark else R.drawable.nc_chip_light
        val on = AMBER                                   // a button that is showing a non-default state (locked, hidden)
        val state = word(text)

        val lockIcon = if (locked) R.drawable.ic_unlock else R.drawable.ic_lock
        val lockWord = if (locked) "Unlock" else "Lock"
        val lockTint = if (locked) on else ink
        val hideIcon = if (panelOpen) R.drawable.ic_eye_off else R.drawable.ic_eye
        val hideWord = if (panelOpen) "Hide" else "Show"
        val hideTint = if (panelOpen) ink else on

        // ---- compact row: round icon buttons only
        val small = RemoteViews(pkg, R.layout.notif_compact)
        fun round(id: Int, icon: Int, label: String, pi: PendingIntent, tint: Int, red: Boolean = false) {
            small.setViewVisibility(id, View.VISIBLE)
            small.setImageViewResource(id, icon)
            small.setInt(id, "setColorFilter", if (red) RED else tint)
            small.setInt(id, "setBackgroundResource", if (red) R.drawable.nc_chip_red else chip)
            small.setContentDescription(id, label)
            small.setOnClickPendingIntent(id, pi)
        }
        for (id in intArrayOf(R.id.nc_toggle, R.id.nc_lock, R.id.nc_reset, R.id.nc_open, R.id.nc_stop, R.id.nc_start, R.id.nc_off)) small.setViewVisibility(id, View.GONE)

        // ---- expanded card: labelled buttons only
        val big = RemoteViews(pkg, R.layout.notif_big)
        fun card(id: Int, iconId: Int, labelId: Int, icon: Int, word: String, desc: String, pi: PendingIntent, tint: Int, red: Boolean = false) {
            val c = if (red) RED else tint
            big.setViewVisibility(id, View.VISIBLE)
            big.setImageViewResource(iconId, icon)
            big.setInt(iconId, "setColorFilter", c)
            big.setTextViewText(labelId, word)
            big.setTextColor(labelId, c)
            big.setInt(id, "setBackgroundResource", if (red) R.drawable.nc_chip_red else chip)
            big.setContentDescription(id, desc)
            big.setOnClickPendingIntent(id, pi)
        }
        for (id in intArrayOf(R.id.nb_toggle, R.id.nb_lock, R.id.nb_reset, R.id.nb_open, R.id.nb_preset, R.id.nb_stop, R.id.nb_start, R.id.nb_off)) big.setViewVisibility(id, View.GONE)

        if (running) {
            round(R.id.nc_toggle, hideIcon, if (panelOpen) "Hide the panel" else "Show the panel", toggleI, hideTint)
            round(R.id.nc_lock, lockIcon, "$lockWord touch", lockI, lockTint)
            round(R.id.nc_reset, R.drawable.ic_move, "Reset position", resetI, ink)
            round(R.id.nc_open, R.drawable.ic_phone, "Open SavahLive", open, ink)
            round(R.id.nc_stop, R.drawable.ic_stop, "Stop", stopI, ink, true)

            card(R.id.nb_toggle, R.id.nbi_toggle, R.id.nbt_toggle, hideIcon, hideWord, if (panelOpen) "Hide the panel" else "Show the panel", toggleI, hideTint)
            card(R.id.nb_lock, R.id.nbi_lock, R.id.nbt_lock, lockIcon, lockWord, "$lockWord touch", lockI, lockTint)
            card(R.id.nb_reset, R.id.nbi_reset, R.id.nbt_reset, R.drawable.ic_move, "Reset", "Reset position", resetI, ink)
            card(R.id.nb_open, R.id.nbi_open, R.id.nbt_open, R.drawable.ic_phone, "Open", "Open SavahLive", open, ink)
            val presets = Presets.list(store)
            if (presets.isNotEmpty()) {
                val current = presets.getOrNull(store.presetIndex)
                card(R.id.nb_preset, R.id.nbi_preset, R.id.nbt_preset, R.drawable.ic_palette, (current?.name ?: "Look").take(7), "Next saved look: ${current?.name ?: "none yet"}", svc(5, OverlayService.ACTION_PRESET), ink)
            }
            card(R.id.nb_stop, R.id.nbi_stop, R.id.nbt_stop, R.drawable.ic_stop, "Stop", "Stop", stopI, ink, true)
        } else {
            round(R.id.nc_start, R.drawable.ic_play, "Start the overlay", startI, ink)
            round(R.id.nc_open, R.drawable.ic_phone, "Open SavahLive", open, ink)
            card(R.id.nb_start, R.id.nbi_start, R.id.nbt_start, R.drawable.ic_play, "Start", "Start the overlay", startI, ink)
            card(R.id.nb_open, R.id.nbi_open, R.id.nbt_open, R.drawable.ic_phone, "Open", "Open SavahLive", open, ink)
            // the third button: take this notification away (it comes back from Settings > Controls in notifications)
            round(R.id.nc_off, R.drawable.ic_bell_off, "Turn off this notification", offI, ink)
            card(R.id.nb_off, R.id.nbi_off, R.id.nbt_off, R.drawable.ic_bell_off, "Off", "Turn off this notification", offI, ink)
        }

        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("SavahLive")
            .setContentText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(RED)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }
}
