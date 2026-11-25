package com.savahbot.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.RemoteViews

/**
 * Keeps the connection and the floating window alive while the overlay is open, and nothing else.
 * Close the overlay (the X, the notification's Stop, or Stop in the app) and the service ends, the connection closes and nothing runs in the background.
 * When the screen goes off the stream is closed too, and it reopens when the screen comes back on.
 */
class OverlayService : Service(), Feed.Listener {
    companion object {
        const val ACTION_START = "com.savahbot.live.START"
        const val ACTION_STOP = "com.savahbot.live.STOP"
        const val ACTION_LOCK = "com.savahbot.live.LOCK"
        const val ACTION_TOGGLE = "com.savahbot.live.TOGGLE"
        const val ACTION_RESET = "com.savahbot.live.RESET"
        const val ACTION_PRESET = "com.savahbot.live.PRESET"
        private const val NOTE_ID = Controls.NOTE_ID

        @Volatile var running = false
        @Volatile var status = ""       // read by the main screen

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private var overlay: Overlay? = null
    private var feed: Feed? = null
    private var locked = false
    private var noteText = ""
    private var receiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var finished = false
    private val tally = StreamTally()
    private var lastViewers: Long? = null
    private var lastLikes: Long? = null
    private val autoStop = Runnable { shutdown() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Controls.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                // same values as the app's "Reset size and position"; the open overlay follows the change live
                store.panelWdp = 270
                store.panelHdp = 300
                store.expanded = false
                store.panelFx = 500
                store.panelPlaced = false
                store.panelFy = 300
                store.tabEdge = Edge.RIGHT.ordinal
                store.tabAlong = 400
                return START_NOT_STICKY
            }
            ACTION_PRESET -> {
                // the next saved look and layout; the open overlay follows the change live, and the notification names it
                val name = Presets.next(store)
                if (name != null) note(noteText)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                overlay?.togglePanel()
                return START_NOT_STICKY
            }
            ACTION_LOCK -> {
                if (overlay != null) {
                    locked = !locked
                    overlay?.setLocked(locked)
                    note(noteText)
                }
                return START_NOT_STICKY
            }
        }
        // Android requires this within a few seconds of startForegroundService, whatever happens next.
        noteText = "Starting\u2026"
        val n = notification(noteText)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTE_ID, n)
        if (overlay == null) begin()
        return START_NOT_STICKY
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this) || !store.isPaired) {
            shutdown()
            return
        }
        val o = Overlay(this, store, { shutdown() }, { on ->
            locked = on
            note(noteText)
        }, { if (overlay != null) note(noteText) })
        if (!o.show()) {
            shutdown()
            return
        }
        overlay = o
        running = true
        tally.startedAt = System.currentTimeMillis()
        status = "Starting\u2026"
        val f = Feed(store, this)
        feed = f
        f.start()
        watchScreen(f)
        watchNetwork(f)
    }

    private fun watchScreen(f: Feed) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == Intent.ACTION_SCREEN_OFF) f.pause() else if (i?.action == Intent.ACTION_SCREEN_ON) f.resume()
            }
        }
        receiver = r
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        registerReceiver(r, filter)
    }

    private fun watchNetwork(f: Feed) {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    f.wake()
                }
            }
            netCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            netCallback = null      // reconnecting still works, just on its own timer
        }
    }

    private fun shutdown() {
        if (finished) return
        finished = true
        saveSummary()
        main.removeCallbacksAndMessages(null)
        feed?.stop()
        feed = null
        try {
            receiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            // not registered
        }
        receiver = null
        try {
            netCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // not registered
        }
        netCallback = null
        overlay?.hide()
        overlay = null
        running = false
        status = ""
        if (store.controlsAlways) {
            // keep the notification and turn it into the "Start" one in place (no flash, no race with removal)
            stopForeground(Service.STOP_FOREGROUND_DETACH)
            Controls.refreshIdle(this, true)
            // once more a moment later, from the application context: whatever the system does while it tears the service down, the Start notification ends up there
            val app = applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Controls.refreshIdle(app, true) }, 400)
        } else {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    /** One short record of the stream that just ended, for the home screen. Never throws: a summary is not worth a crash. */
    private fun saveSummary() {
        try {
            if (!store.summaryOn || !tally.hasData) return
            val top = org.json.JSONArray()
            for ((name, n) in tally.top()) top.put(org.json.JSONArray().put(name).put(n))
            store.summaryJson = org.json.JSONObject()
                .put("ended", System.currentTimeMillis())
                .put("seconds", (System.currentTimeMillis() - tally.startedAt) / 1000)
                .put("peak", tally.peakViewers)
                .put("likes", tally.lastLikes)
                .put("messages", tally.messages)
                .put("top", top)
                .toString()
        } catch (e: Exception) {
            // keep going: stopping must always work
        }
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    // ---------------------------------------------------------------------------------------------- notification
    private fun notification(text: String): Notification = Controls.build(this, text, locked, overlay?.panelOpen ?: true, true)

    /** Only called when the connection state changes (never per chat line or per stat), so it costs almost nothing. */
    private fun note(text: String) {
        noteText = text
        status = text
        if (finished) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_ID, notification(text))
    }

    // ---------------------------------------------------------------------------------------------- Feed.Listener (main thread)
    override fun onLink(link: Link, detail: String) {
        if (finished) return
        overlay?.setLink(link, detail)
        note(
            when (link) {
                Link.ONLINE -> "Connected to SavahBot"
                Link.CONNECTING -> "Connecting\u2026"
                Link.RECONNECTING -> "Reconnecting\u2026"
                Link.WAITING -> detail.ifEmpty { "Waiting for SavahBot" }
                Link.PAUSED -> "Paused while the screen is off"
                Link.UNPAIRED -> "Unpaired"
            }
        )
    }

    override fun onState(state: State) {
        if (finished) return
        overlay?.setState(state)
        if (state.live) {
            tally.sawLive = true
            main.removeCallbacks(autoStop)
        } else if (tally.sawLive && store.autoStopMin > 0) {
            main.removeCallbacks(autoStop)
            main.postDelayed(autoStop, store.autoStopMin * 60_000L)       // stops the overlay a while after the stream ends, unless it goes live again
        }
    }

    override fun onStats(stats: Stats) {
        if (finished) return
        overlay?.setStats(stats)
        if (!stats.enabled) return
        tally.viewers(stats.viewers)
        tally.likes(if (stats.likesHidden) null else stats.likes)
        val step = store.milestoneStep
        val l = if (stats.likesHidden) null else Milestones.crossed(lastLikes, stats.likes, step)
        val v = Milestones.crossed(lastViewers, stats.viewers, step)
        if (stats.viewers != null) lastViewers = stats.viewers
        if (stats.likes != null && !stats.likesHidden) lastLikes = stats.likes
        if (l != null) overlay?.celebrate("${Fmt.count(l)} likes")
        else if (v != null) overlay?.celebrate("${Fmt.count(v)} watching")
    }

    override fun onSettings(prefs: Prefs) {
        if (!finished) overlay?.setPrefs(prefs)
    }

    override fun onChat(lines: List<Line>, skipped: Int) {
        if (finished) return
        for (l in lines) tally.message(l)
        overlay?.addChat(lines, skipped)
    }

    /** SavahBot says which version is current (it is kept equal to this app's version). The home screen compares it with the installed one. */
    override fun onUpdate(info: UpdateInfo) {
        if (finished || !store.updateCheck) return
        store.updateJson = org.json.JSONObject().put("version", info.version).put("url", info.url).toString()
    }

    override fun onPurge(uids: List<String>, ids: List<Long>) {
        if (!finished) overlay?.purge(uids, ids)
    }

    /** SavahBot cancelled this phone's pairing. Forget it, say so on the overlay for a few seconds, then stop. */
    override fun onUnpaired(code: String) {
        if (finished) return
        val message = when (code) {
            "expired" -> "The pairing for this phone has ended. Open SavahLive to pair again."
            "idle" -> "This phone was unpaired because it was not used for a while. Open SavahLive to pair again."
            "unpaired" -> "SavahBot does not know this phone. Open SavahLive to pair again."
            else -> "This phone was unpaired from SavahBot. Open SavahLive to pair again."
        }
        store.clearPairing(message)
        feed?.stop()
        overlay?.setLink(Link.UNPAIRED, "")
        overlay?.showNotice(message)
        note("Unpaired")
        main.postDelayed({ shutdown() }, 8_000)
    }
}
