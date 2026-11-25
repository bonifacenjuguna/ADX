package com.savahbot.live

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * The only screen, in three stages: Pair (once), Allow (the floating-window permission, once), Go (start and stop the overlay, and choose how the overlay looks).
 * No accounts and no dashboard: SavahBot (Manage > SavahLive) holds what the stream shows. Everything about how the panel, tab and pop-outs look and behave is here,
 * so the panel itself only carries what you need while playing. Changes apply to the open overlay at once.
 *
 * Go has four tabs: Home (run it), Look (how it looks, with a live preview), Chat (what you can do with chat), Settings (automation, connection, this phone).
 * Every screen is built from [Ui]; the icons are the outline set in res/drawable (see scripts/gen_savahlive_icons.py).
 */
class MainActivity : Activity() {
    private companion object {
        const val ASK_NOTIFICATIONS = 7
    }

    private lateinit var store: Store
    private lateinit var ui: Ui
    private val main = Handler(Looper.getMainLooper())
    private var typedCode = ""
    private var typedServer = ""
    private var showServer = false
    private var message = ""
    private var messageIsError = true
    private var pairing = false
    private var autoPair: PairInput? = null
    private var autoOpened = false
    private var presetName = ""
    private var tab = 0
    private var scrollKeep = 0
    private var scrollView: ScrollView? = null
    private var preview: Preview? = null
    private var pvView = 0                                  // the Look preview: 0 = Panel, 1 = Pop-out (kept while the screen redraws)
    private var pvEdge = Edge.RIGHT.ordinal
    private var pvBack = 0                                  // 0 night game, 1 daylight, 2 busy
    private var pvTune = false

    private val matchWidth = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        ui = Ui(this)
        Ui.use(AppThemes.palette(store.appTheme, this))
        take(intent)
        try {
            render()
            styleSystemBars()
        } catch (e: Throwable) {
            // Whatever went wrong with a theme, never lock the person out of the app: go back to the dark look.
            android.util.Log.e("SavahLive", "start-up failed with theme " + store.appTheme, e)
            store.appTheme = AppThemes.DARK
            Ui.use(AppThemes.dark)
            render()
            styleSystemBars()
        }
    }

    private var swipeX = 0f
    private var swipeY = 0f
    private var swipeOk = false

    /** A horizontal swipe on any screen moves to the next or previous tab (not from the screen edges, which belong to the back gesture, nor from sliders). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeX = ev.rawX
                swipeY = ev.rawY
                val w = resources.displayMetrics.widthPixels
                swipeOk = stage() == 2 && ev.rawX > ui.dp(26) && ev.rawX < w - ui.dp(26) &&
                    !blocksSwipe(deepest(window.decorView, ev.rawX.toInt(), ev.rawY.toInt()))
            }
            android.view.MotionEvent.ACTION_CANCEL -> swipeOk = false
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && swipeOk) {
            swipeOk = false
            val dx = ev.rawX - swipeX
            val dy = ev.rawY - swipeY
            if (Math.abs(dx) > ui.dp(96) && Math.abs(dx) > Math.abs(dy) * 2f) {
                val to = tab + if (dx < 0) 1 else -1
                if (to in 0..3) goTab(to)
            }
        }
        return handled
    }

    private fun deepest(v: View, x: Int, y: Int): View? {
        if (v.visibility != View.VISIBLE) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (x < loc[0] || x > loc[0] + v.width || y < loc[1] || y > loc[1] + v.height) return null
        if (v is ViewGroup) {
            for (k in v.childCount - 1 downTo 0) {
                val r = deepest(v.getChildAt(k), x, y)
                if (r != null) return r
            }
        }
        return v
    }

    private fun blocksSwipe(v: View?): Boolean {
        var p: View? = v
        while (p != null) {
            if (p is LineSlider || p is android.widget.HorizontalScrollView || p is android.widget.EditText) return true
            p = p.parent as? View
        }
        return false
    }

    /** Status and navigation bar follow the theme, and their icons go dark on a light theme. */
    private fun styleSystemBars() {
        try {
            window.statusBarColor = Ui.BG
            window.navigationBarColor = Ui.BG
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ui.BG))
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val m = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (Ui.LIGHT) m else 0, m)
            } else {
                @Suppress("DEPRECATION")
                var f = window.decorView.systemUiVisibility
                val bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
                f = if (Ui.LIGHT) f or bits else f and bits.inv()
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = f
            }
        } catch (e: Throwable) {
            android.util.Log.w("SavahLive", "system bars", e)
        }
    }

    /** Change the app's theme with the sideways swipe: a theme later in the list arrives from the right, an earlier one from the left. */
    private fun switchTheme(to: Int) {
        val from = store.appTheme
        if (from == to) return
        slideRender(if (to > from) 1f else -1f, 440) {
            store.appTheme = to
            Ui.use(AppThemes.palette(to, this))
            styleSystemBars()
        }
    }

    /** Move to another tab with the same swipe: a tab further along arrives from the right. */
    private fun goTab(i: Int) {
        if (i == tab) return
        slideRender(if (i > tab) 1f else -1f, 320) {
            tab = i
            scrollKeep = 0
        }
    }

    /**
     * Runs [change] and redraws the screen with a horizontal swipe: a picture of the old screen slides out while the new one slides in
     * (dir 1: new one from the right, -1: from the left). Skipped when the phone has animations turned off.
     */
    private fun slideRender(dir: Float, ms: Long, change: () -> Unit) {
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val w = content.width
        val h = content.height
        val animate = w > 0 && h > 0 && android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        var snap: android.graphics.Bitmap? = null
        if (animate) {
            try {
                snap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                content.draw(android.graphics.Canvas(snap))
            } catch (e: Throwable) {
                snap = null
            }
        }
        val oldBg = Ui.BG
        val oldNav = window.navigationBarColor
        change()
        render()
        val shot = snap ?: return
        val newNav = window.navigationBarColor
        val fresh = content.getChildAt(0)
        val old = ImageView(this)
        old.setImageBitmap(shot)
        content.addView(old, FrameLayout.LayoutParams(w, h))
        // a soft shadow along the edge of the incoming screen so it reads as a sheet sliding over
        val edge = View(this)
        val shadowW = ui.dp(28)
        edge.background = GradientDrawable(
            if (dir > 0) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(0x00000000, if (Ui.LIGHT) 0x1A0B1220 else 0x66000000)
        )
        content.addView(edge, FrameLayout.LayoutParams(shadowW, h))
        fresh?.bringToFront()          // the new screen slides OVER the old picture
        window.statusBarColor = oldBg
        window.navigationBarColor = oldNav
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = ms
        anim.interpolator = android.view.animation.DecelerateInterpolator(1.8f)
        anim.addUpdateListener {
            val p = it.animatedValue as Float
            fresh?.translationX = dir * w * (1f - p)
            old.translationX = -dir * w * p * 0.35f
            old.alpha = 1f - p * 0.6f
            val leading = dir * w * (1f - p)
            edge.translationX = if (dir > 0) leading - shadowW else leading + w
            edge.alpha = 1f - p
            window.statusBarColor = AppThemes.blend(oldBg, Ui.BG, p)
            window.navigationBarColor = AppThemes.blend(oldNav, newNav, p)
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                fresh?.translationX = 0f
                content.removeView(old)
                content.removeView(edge)
                window.statusBarColor = Ui.BG
                window.navigationBarColor = newNav
                shot.recycle()
            }
        })
        fresh?.translationX = dir * w
        anim.start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        // "Start the overlay when this app opens": once per launch of the app, and never behind the person's back after they pressed Stop
        if (store.autoOpen && !autoOpened && store.isPaired && Settings.canDrawOverlays(this) && !OverlayService.running) {
            autoOpened = true
            OverlayService.start(this)
        }
        Controls.refreshIdle(this)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    /** A savahlive://pair?... link (from the button on SavahBot's pairing page) pairs straight away. */
    private fun take(i: Intent?) {
        if (i == null) return
        val link = i.data ?: return
        if (i.action == Intent.ACTION_VIEW && link.scheme == "savahlive") {
            val input = Pairing.parse(link.toString())
            if (input != null) {
                autoPair = input
            } else {
                message = "That pairing link is not complete. Make a new one in SavahBot."
                messageIsError = true
            }
            i.data = null
        }
    }

    private fun stage(): Int = when {
        !store.isPaired -> 0
        !Settings.canDrawOverlays(this) -> 1
        else -> 2
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------
    private fun render() {
        val stage = stage()
        scrollView?.let { scrollKeep = it.scrollY }
        preview = null
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ui.dp(20), ui.dp(if (stage == 2) 16 else 30), ui.dp(20), ui.dp(28))

        if (stage == 2) {
            appBar(col)
            when (tab) {
                1 -> lookTab(col)
                2 -> chatTab(col)
                3 -> settingsTab(col)
                else -> homeTab(col)
            }
        } else {
            brand(col)
            col.addView(ui.stepBar(stage))
            if (stage == 0) pairStage(col) else allowStage(col)
        }
        if (stage < 2 || tab == 3) {
            col.addView(ui.space(8))
            col.addView(ui.text("SavahLive " + BuildConfig.VERSION_NAME + "  ·  SavahBot does the work, SavahLive shows the results.", 12f, Ui.MUTED))
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        if (Ui.SOFT_UI) {
            // the Soft theme's shadows fall outside each surface, so nothing may clip them
            col.clipChildren = false
            col.clipToPadding = false
            scroll.clipChildren = false
            scroll.clipToPadding = false
        }
        scroll.addView(col)
        scrollView = scroll
        if (stage == 2) {
            val root = LinearLayout(this)
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundColor(Ui.BG)
            if (tab == 1) preview?.let { root.addView(it.root, LinearLayout.LayoutParams(matchWidth, wrap)) }
            root.addView(scroll, LinearLayout.LayoutParams(matchWidth, 0, 1f))
            root.addView(bottomNav())
            setContentView(root)
            window.navigationBarColor = Ui.CARD
        } else {
            setContentView(scroll)
            window.navigationBarColor = Ui.BG
        }
        val keep = scrollKeep
        scroll.post { scroll.scrollTo(0, keep) }

        val auto = autoPair
        if (auto != null) {
            autoPair = null
            if (!store.isPaired) pair(auto)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- the frame of the main screen: a slim top bar and a bottom bar with four tabs -------------------------------------------------------
    private fun appBar(col: LinearLayout) {
        val running = OverlayService.running
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_icon)
        row.addView(logo, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
        row.addView(ui.wordmark(21f), LinearLayout.LayoutParams(0, wrap, 1f).also { it.leftMargin = ui.dp(10) })
        row.addView(ui.statusChip(if (running) "On air" else "Ready", if (running) Ui.GREEN else Ui.MUTED))
        row.layoutParams = LinearLayout.LayoutParams(matchWidth, wrap).also { it.bottomMargin = ui.dp(12) }
        col.addView(row)
    }

    private fun bottomNav(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = ui.round(Ui.CARD, 0, Ui.LINE, 1)
        val items = listOf(Triple("Home", R.drawable.ic_home, 0), Triple("Look", R.drawable.ic_palette, 1), Triple("Chat", R.drawable.ic_chat, 2), Triple("Settings", R.drawable.ic_settings, 3))
        for ((label, res, index) in items) {
            val on = index == tab
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            val holder = FrameLayout(this)
            holder.background = if (on) ui.round(ui.tint(Ui.RED, 24), 16) else null
            holder.addView(ui.icon(res, if (on) Ui.INK else Ui.MUTED, 22), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
            cell.addView(holder, LinearLayout.LayoutParams(ui.dp(58), ui.dp(32)))
            cell.addView(ui.text(label, 11f, if (on) Ui.INK else Ui.MUTED, on), LinearLayout.LayoutParams(wrap, wrap).also { it.topMargin = ui.dp(3) })
            cell.isClickable = true
            cell.isFocusable = true
            cell.contentDescription = label
            cell.setOnClickListener {
                goTab(index)
            }
            bar.addView(cell, LinearLayout.LayoutParams(0, ui.dp(68), 1f))
        }
        return bar
    }

    private fun pageTitle(col: LinearLayout, title: String, sub: String) {
        col.addView(ui.text(title, 24f, Ui.INK, true))
        col.addView(ui.text(sub, 14f, Ui.MUTED), LinearLayout.LayoutParams(wrap, wrap).also {
            it.topMargin = ui.dp(2)
            it.bottomMargin = ui.dp(6)
        })
    }

    /** A small caps label above a group, with an optional line of help under it. */
    private fun sectionLabel(col: LinearLayout, title: String, hint: String = "") {
        val t = ui.text(title.uppercase(), 12f, Ui.MUTED, true)
        t.letterSpacing = 0.1f
        t.setPadding(ui.dp(4), ui.dp(12), 0, ui.dp(if (hint.isEmpty()) 6 else 2))
        col.addView(t)
        if (hint.isNotEmpty()) {
            val h = ui.text(hint, 13f, Ui.MUTED)
            h.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8))
            col.addView(h)
        }
    }

    /** Related settings share one card, separated by hairlines that start under the text (not under the icon). */
    private fun group(col: LinearLayout, vararg rows: View, inset: Int = 52) {
        val g = ui.card(16)
        g.setPadding(ui.dp(14), ui.dp(0), ui.dp(14), ui.dp(0))
        for ((i, r) in rows.withIndex()) {
            if (i > 0) g.addView(ui.hairline(inset))
            g.addView(r)
        }
        col.addView(g)
    }

    private fun brand(col: LinearLayout) {
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_icon)
        col.addView(logo, LinearLayout.LayoutParams(ui.dp(68), ui.dp(68)))
        col.addView(ui.space(16))
        col.addView(ui.wordmark(34f))
        col.addView(ui.space(6))
        col.addView(ui.text("Your stream chat, viewers and likes, floating over any game.", 16f, Ui.MUTED))
        col.addView(ui.space(24))
    }

    // ---- stage 0: pair ----------------------------------------------------------------------------------------------------------------------
    private fun pairStage(col: LinearLayout) {
        if (store.notice.isNotEmpty()) {
            val n = ui.card(16)
            n.background = ui.round(ui.tint(Ui.AMBER, 15), 20, ui.tint(Ui.AMBER, 35), 1)
            n.addView(ui.text(store.notice, 14f, Ui.AMBER))
            col.addView(n)
        }
        val c = ui.card()
        c.addView(ui.colorTile(R.drawable.ic_link, Ui.RED, 52))
        c.addView(ui.space(14))
        c.addView(ui.text("Link your SavahBot", 22f, Ui.INK, true))
        c.addView(ui.space(4))
        c.addView(ui.text("You only do this once.", 14f, Ui.MUTED))
        c.addView(ui.space(12))
        c.addView(ui.numberedStep(1, "In SavahBot open Manage, then SavahLive, then Pair a phone."))
        c.addView(ui.numberedStep(2, "Scan the QR with your camera and tap Open SavahLive."))
        c.addView(ui.numberedStep(3, "Or paste the link, or type the code, below."))
        c.addView(ui.space(14))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val code = ui.field("Pairing link or code", typedCode) { typedCode = it }
        code.letterSpacing = 0.04f
        row.addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val paste = FrameLayout(this)
        paste.background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), ui.plate(Ui.RAISED, 16, Ui.LINE, 1), null)
        paste.addView(ui.icon(R.drawable.ic_paste, Ui.INK, 22), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
        paste.isClickable = true
        paste.contentDescription = "Paste the pairing link"
        paste.setOnClickListener { pasteFromClipboard() }
        row.addView(paste, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).also { it.leftMargin = ui.dp(8) })
        c.addView(row)

        if (showServer) {
            c.addView(ui.space(8))
            c.addView(ui.field("SavahBot address, like https://yourbot.up.railway.app", typedServer) { typedServer = it })
        } else {
            val more = ui.button("Typing only the code? Add the address", null, Ui.QUIET) {
                showServer = true
                render()
            }
            c.addView(more)
        }
        val go = ui.button(if (pairing) "Pairing…" else "Pair this phone", R.drawable.ic_check, Ui.PRIMARY) { onPairClicked() }
        ui.setEnabled(go, !pairing)
        c.addView(go)
        if (message.isNotEmpty()) {
            val m = ui.text(message, 14f, if (messageIsError && !pairing) Ui.AMBER else Ui.MUTED)
            m.setPadding(0, ui.dp(12), 0, 0)
            c.addView(m)
        }
        col.addView(c)
    }

    // ---- stage 1: allow the floating window -------------------------------------------------------------------------------------------------
    private fun allowStage(col: LinearLayout) {
        val c = ui.card()
        c.addView(ui.colorTile(R.drawable.ic_layers, Ui.RED, 52))
        c.addView(ui.space(14))
        c.addView(ui.text("Let SavahLive float", 24f, Ui.INK, true))
        c.addView(ui.space(8))
        c.addView(ui.text("Turn on “Display over other apps” for SavahLive so it can sit above your game.", 15f, Ui.MUTED))
        c.addView(ui.space(12))
        c.addView(ui.numberedStep(1, "Tap the button. Android opens a settings page."))
        c.addView(ui.numberedStep(2, "Find SavahLive and turn the switch on."))
        c.addView(ui.numberedStep(3, "Press back. You land here, ready to go."))
        c.addView(ui.button("Open settings", R.drawable.ic_settings, Ui.PRIMARY) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(c)
        setupCard(col)
    }

    /** Notifications and battery: nice to have, never blocking. Shown only while something is still to do. */
    private fun setupCard(col: LinearLayout) {
        val rows = ArrayList<View>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            rows.add(setupRow(R.drawable.ic_bell, "Notifications", "Shows the Stop button while the overlay runs.", "Allow") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFICATIONS)
            })
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            rows.add(setupRow(R.drawable.ic_battery, "Keep it running", "Some phones stop background apps during long games. Choose Unrestricted for SavahLive.", "Open") {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })
        }
        if (rows.isEmpty()) return
        sectionLabel(col, "Finish setup", "Optional, but it keeps long games smooth.")
        group(col, *rows.toTypedArray())
    }

    private fun setupRow(icon: Int, title: String, body: String, action: String, onClick: () -> Unit): LinearLayout {
        val row = ui.head(icon, title, body, ui.pill(action, Ui.INK) { onClick() }, Ui.AMBER, ui.tint(Ui.AMBER, 16))
        row.setPadding(0, ui.dp(14), 0, ui.dp(14))
        return row
    }

    // ---- stage 2: four tabs. Home = run it. Look = how it looks. Chat = what you can do with chat. Settings = automation, connection, this phone ---
    private fun homeTab(col: LinearLayout) {
        val running = OverlayService.running
        // status strip: what is happening, and the one button you need
        val strip = ui.card(14)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(ui.colorTile(if (running) R.drawable.ic_live else R.drawable.ic_layers, if (running) Ui.GREEN else Ui.RED, 46))
        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        words.addView(ui.text(if (running) "On air" else "Ready", 19f, Ui.INK, true))
        val sub = if (running && OverlayService.status.isNotEmpty()) OverlayService.status else "Starts on top of PUBG Mobile or any app."
        val subTv = ui.text(sub, 13f, Ui.MUTED)
        subTv.maxLines = 2
        subTv.ellipsize = android.text.TextUtils.TruncateAt.END
        words.addView(subTv)
        top.addView(words, LinearLayout.LayoutParams(0, wrap, 1f).also { it.leftMargin = ui.dp(12); it.rightMargin = ui.dp(10) })
        top.addView(if (running) ui.pill("Stop") {
            OverlayService.stop(this)
            main.postDelayed({ render() }, 500)
        } else ui.pillRed("Start") { startOverlay() })
        strip.addView(top)
        if (message.isNotEmpty()) {
            val m = ui.text(message, 14f, Ui.AMBER)
            m.setPadding(0, ui.dp(10), 0, 0)
            strip.addView(m)
        }
        col.addView(strip)

        // quick tiles: go somewhere, or flip a switch without opening Settings
        val tiles = listOf(
            ui.quickTile(R.drawable.ic_palette, "Look", "Colours, tab, pop-out", false, Ui.RED) { goTab(1) },
            ui.quickTile(R.drawable.ic_chat, "Chat", "How messages look", false, Ui.RED) { goTab(2) },
            ui.quickTile(if (store.controlsAlways) R.drawable.ic_bell else R.drawable.ic_bell_off, "Notification", if (store.controlsAlways) "Always there" else "Only while open", store.controlsAlways, Ui.RED) {
                store.controlsAlways = !store.controlsAlways
                Controls.refreshIdle(this, true)
                render()
            },
            ui.quickTile(R.drawable.ic_play, "Auto-start", if (store.autoOpen) "On" else "Off", store.autoOpen, Ui.RED) {
                store.autoOpen = !store.autoOpen
                render()
            },
            ui.quickTile(if (store.statusBanner) R.drawable.ic_wifi else R.drawable.ic_wifi_off, "Connection", if (store.statusBanner) "Shown when it drops" else "Hidden", store.statusBanner, Ui.RED) {
                store.statusBanner = !store.statusBanner
                render()
            },
            ui.quickTile(R.drawable.ic_settings, "Settings", "Theme, stop timer", false, Ui.RED) { goTab(3) }
        )
        for (pair in tiles.chunked(2)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for ((k, t) in pair.withIndex()) {
                row.addView(t, LinearLayout.LayoutParams(0, ui.dp(98), 1f).also { if (k == 0) it.rightMargin = ui.dp(5) else it.leftMargin = ui.dp(5) })
            }
            row.layoutParams = LinearLayout.LayoutParams(matchWidth, wrap).also { it.bottomMargin = ui.dp(10) }
            col.addView(row)
        }

        updateCard(col)
        summaryCard(col)
        setupCard(col)

        sectionLabel(col, "How the panel works")
        fun tip(icon: Int, title: String, body: String): LinearLayout {
            val r = ui.head(icon, title, body, compact = true)
            r.setPadding(0, ui.dp(10), 0, ui.dp(10))
            return r
        }
        group(col,
            tip(R.drawable.ic_move, "Move it", "Drag the top row, where the viewers and likes are."),
            tip(R.drawable.ic_expand, "Resize it", "Drag any edge or corner, or tap the expand button for a big window."),
            tip(R.drawable.ic_dock, "Tuck it away", "Tuck the panel into a small tab. Tap the tab to open it, or drag it to another edge."),
            tip(R.drawable.ic_pin, "It remembers", "The panel reopens exactly where you last left it, in portrait and in landscape.")
        )
    }

    private fun lookTab(col: LinearLayout) {
        pageTitle(col, "Look", "Make the panel and the tab yours.")
        preview = Preview()          // pinned above the scrolling settings by render()

        sectionLabel(col, "Panel", "The window with the chat.")
        group(col,
            toggleRow(R.drawable.ic_layers, "Glass look", "Frosted, see-through panel, tab and pop-outs.", store.glass) {
                store.glass = it
                preview?.refresh()
            },
            sliderRow(R.drawable.ic_opacity, "Opacity", "How see-through the background is. Text stays clear.", 20, 100, store.panelOpacity, "%") {
                store.panelOpacity = it
                preview?.focus(0)
            },
            sliderRow(R.drawable.ic_text, "Chat text size", "", 80, 140, store.textScale, "%") {
                store.textScale = it
                preview?.focus(0)
            },
            themeRow()
        )

        sectionLabel(col, "Tab", "The small box on the screen edge. It is the same size on every edge.")
        group(col,
            toggleRow(R.drawable.ic_expand, "Open next to the tab", "It reopens exactly where you left it.", store.openNearTab) {
                store.openNearTab = it
            },
            choiceRow(Icons.tuck(store.tuckTo), "Tuck to", "Where the tab appears when you tuck the panel.", listOf(Pair("Where it was", 5), Pair("Right", 1), Pair("Left", 0), Pair("Top", 2), Pair("Bottom", 3), Pair("Nearest", 4)), store.tuckTo) {
                store.tuckTo = it
                if (it in 0..3) pvEdge = it          // the preview shows the tab on that side
                preview?.focus(1)
            },
            sliderRow(R.drawable.ic_opacity, "Opacity", "Never fainter than 40%, so you can always find it.", 40, 100, store.tabOpacity, "%") {
                store.tabOpacity = it
                preview?.refresh()
            },
            choiceRow(R.drawable.ic_dock, "Slide away", "Tucks the tab mostly off screen when idle. It slides back for new chat.", listOf(Pair("Never", 0), Pair("3 s", 3), Pair("5 s", 5), Pair("10 s", 10), Pair("20 s", 20)), store.tabHide) { store.tabHide = it }
        )

        sectionLabel(col, "Chat pop-outs", "New messages that slide out beside the tab.")
        group(col,
            choiceRow(R.drawable.ic_clock, "Time on screen", "How long each message stays. Auto uses what SavahBot asks for.", listOf(Pair("Auto", 0), Pair("2 s", 2), Pair("4 s", 4), Pair("6 s", 6), Pair("8 s", 8), Pair("12 s", 12)), store.popSeconds) {
                store.popSeconds = it
                preview?.focus(1)
            },
            choiceRow(R.drawable.ic_chat, "Messages at once", "One keeps the card small and calm. More shows the latest few together.", listOf(Pair("1", 1), Pair("2", 2), Pair("3", 3)), store.popLines) {
                store.popLines = it
                preview?.focus(1)
            },
            sliderRow(R.drawable.ic_expand, "Size", "", 70, 170, store.popScale, "%") {
                store.popScale = it
                preview?.focus(1)
            },
            sliderRow(R.drawable.ic_opacity, "Opacity", "", 30, 100, store.popOpacity, "%") {
                store.popOpacity = it
                preview?.focus(1)
            }
        )

        sectionLabel(col, "Moving things")
        group(col,
            toggleRow(R.drawable.ic_move, "Hold to move", "Press and hold the tab, or the panel's top row, then drag.", store.holdToMove) { store.holdToMove = it }
        )

        sectionLabel(col, "Saved looks", "Save a layout and theme, for example one for landscape games.")
        val rows = ArrayList<View>()
        val saved = Presets.list(store)
        if (saved.isEmpty()) {
            rows.add(ui.text("Nothing saved yet. Set things up above, name it below, and save.", 13f, Ui.MUTED).also { it.setPadding(0, ui.dp(16), 0, ui.dp(16)) })
        }
        for ((i, preset) in saved.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, ui.dp(10), 0, ui.dp(10))
            val inUse = i == store.presetIndex
            row.addView(ui.text(preset.name, 15f, Ui.INK, true), LinearLayout.LayoutParams(0, wrap, 1f))
            if (inUse) row.addView(ui.chip("In use", Ui.GREEN), LinearLayout.LayoutParams(wrap, wrap).also { it.rightMargin = ui.dp(8) })
            row.addView(ui.pill("Use") {
                Presets.apply(store, i)
                toast("Using \"" + preset.name + "\".")
                render()
            })
            row.addView(iconButton(R.drawable.ic_trash, "Delete " + preset.name) {
                Presets.delete(store, i)
                render()
            }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).also { it.leftMargin = ui.dp(6) })
            rows.add(row)
        }
        val save = LinearLayout(this)
        save.orientation = LinearLayout.VERTICAL
        save.setPadding(0, ui.dp(14), 0, ui.dp(10))
        save.addView(ui.field("Name this look", presetName) { presetName = it })
        save.addView(ui.button("Save the current look", R.drawable.ic_bookmark, Ui.SECONDARY) {
            toast(if (Presets.save(store, presetName)) "Saved." else "You can keep up to ${Presets.MAX} looks. Delete one first.")
            presetName = ""
            render()
        })
        rows.add(save)
        group(col, *rows.toTypedArray(), inset = 0)

        sectionLabel(col, "Start over")
        val resetLook = ui.head(R.drawable.ic_palette, "Reset look", "Resets the look. Your saved looks are kept.", ui.pill("Reset") {
            store.themeId = 0
            store.customHue = 210
            store.customLight = 18
            store.panelOpacity = 92
            store.tabOpacity = 90
            store.tabHide = 0
            store.textScale = 100
            store.popScale = 100
            store.popLines = 1
            store.popOpacity = 95
            store.glass = true
            toast("Look reset.")
            render()
        })
        resetLook.setPadding(0, ui.dp(14), 0, ui.dp(14))
        val reset = ui.head(R.drawable.ic_move, "Reset size and position", "Puts the panel and the tab back where they start.", ui.pill("Reset") {
            store.panelWdp = 270
            store.panelHdp = 300
            store.expanded = false
            store.panelFx = 500
            store.panelPlaced = false
            store.panelFy = 300
            store.tabEdge = Edge.RIGHT.ordinal
            store.tabAlong = 400
            toast("Reset. The panel is back in the middle.")
        })
        reset.setPadding(0, ui.dp(14), 0, ui.dp(14))
        group(col, resetLook, reset)
    }

    private fun chatTab(col: LinearLayout) {
        pageTitle(col, "Chat", "What you can do with chat from the panel.")
        sectionLabel(col, "Talk to your chat", "Needs permission in SavahBot: Manage, SavahLive, then this phone.")
        group(col,
            toggleRow(R.drawable.ic_send, "Show the message box", "Type to your chat from the panel.", store.sendEnabled) { store.sendEnabled = it }
        )

        sectionLabel(col, "Tapping chat lines")
        group(col,
            toggleRow(R.drawable.ic_pin, "Pin a message", "Tap a line to pin it at the top of the panel. Tap the pin to let it go.", store.pinEnabled) { store.pinEnabled = it },
            toggleRow(R.drawable.ic_eye_off, "Hide or time out people", "Hold a line to hide it, or time its author out for 5 minutes.", store.modEnabled) { store.modEnabled = it }
        )

        sectionLabel(col, "Celebrations", "The panel and tab celebrate when viewers or likes pass a round number.")
        group(col,
            choiceRow(R.drawable.ic_star, "Celebrate every", "", listOf(Pair("Off", 0), Pair("50", 50), Pair("100", 100), Pair("250", 250), Pair("500", 500), Pair("1000", 1000)), store.milestoneStep) { store.milestoneStep = it }
        )
    }

    private fun settingsTab(col: LinearLayout) {
        pageTitle(col, "Settings", "Fewer taps on stream day.")
        sectionLabel(col, "App theme", "Only changes this app. The overlay keeps the look you chose in Look.")
        themePicker(col)

        sectionLabel(col, "Automatic")
        group(col,
            toggleRow(R.drawable.ic_bell, "Controls in notifications", "On keeps the notification so you can start the overlay without opening the app. Off shows it only while the overlay is open.", store.controlsAlways) {
                store.controlsAlways = it
                Controls.refreshIdle(this, true)
            },
            toggleRow(R.drawable.ic_play, "Start when the app opens", "Opening SavahLive starts the overlay for you.", store.autoOpen) { store.autoOpen = it },
            choiceRow(R.drawable.ic_clock, "Stop after the stream ends", "Stops this long after SavahBot says your stream is over. Carries on if you go live again.", listOf(Pair("Never", 0), Pair("5 min", 5), Pair("15 min", 15), Pair("30 min", 30), Pair("1 hour", 60)), store.autoStopMin) { store.autoStopMin = it },
            toggleRow(R.drawable.ic_chart, "Keep a stream summary", "Peak viewers, likes, messages and top chatters, shown on Home after you stop.", store.summaryOn) { store.summaryOn = it }
        )

        sectionLabel(col, "Connection", "When something is wrong, say so plainly.")
        group(col,
            toggleRow(R.drawable.ic_wifi, "Show the connection status", "Offline or retrying appears on the panel and replaces the numbers on the tab.", store.statusBanner) { store.statusBanner = it },
            toggleRow(R.drawable.ic_download, "Tell me about new versions", "Shows a card on Home when SavahBot has a newer SavahLive.", store.updateCheck) { store.updateCheck = it }
        )

        sectionLabel(col, "This phone")
        val host = Uri.parse(store.server).host ?: store.server
        val expiry = if (store.expires == 0L) "Stays paired until you remove it in SavahBot."
        else "Pairing ends " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(store.expires)) + "."
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(0, ui.dp(14), 0, ui.dp(6))
        info.addView(ui.head(R.drawable.ic_phone, store.deviceName.ifEmpty { "This phone" }, expiry, null, Ui.GREEN, ui.tint(Ui.GREEN, 16)))
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(ui.dp(52), ui.dp(8), 0, 0)
        chips.addView(ui.statusChip(host, Ui.GREEN))
        info.addView(chips)
        info.addView(ui.button("Unpair this phone", R.drawable.ic_close, Ui.QUIET) { confirmUnpair() })
        group(col, info)
    }

    /** Five swatches: each is a tiny picture of the theme. Tapping one swipes the whole app over to it. */
    private fun themePicker(col: LinearLayout) {
        val items = AppThemes.all
        val current = store.appTheme
        val g = ui.card(14)
        for (rowStart in intArrayOf(0, 3)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until 3) {
                val idx = rowStart + k
                if (idx >= items.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                val id = items[idx].first
                val pal = AppThemes.palette(id, this)
                val on = id == current
                val swatch = FrameLayout(this)
                val bgD = GradientDrawable()
                bgD.setColor(pal.bg)
                bgD.setCornerRadius(ui.dp(14).toFloat())
                bgD.setStroke(ui.dp(if (on) 2 else 1), if (on) Ui.RED else Ui.EDGE)
                swatch.background = bgD
                // a miniature card and a red dot, drawn with that theme's own colours
                val mini = View(this)
                val miniD = GradientDrawable()
                miniD.setColor(pal.card)
                miniD.setCornerRadius(ui.dp(7).toFloat())
                miniD.setStroke(ui.dp(1), pal.line)
                mini.background = miniD
                if (pal.soft) mini.elevation = ui.dp(3).toFloat()
                swatch.addView(mini, FrameLayout.LayoutParams(ui.dp(54), ui.dp(26), Gravity.CENTER_HORIZONTAL or Gravity.TOP).also { it.topMargin = ui.dp(10) })
                val dot = View(this)
                dot.background = ui.round(Ui.RED, 4)
                swatch.addView(dot, FrameLayout.LayoutParams(ui.dp(8), ui.dp(8), Gravity.TOP or Gravity.START).also { it.leftMargin = ui.dp(22); it.topMargin = ui.dp(19) })
                val lines = View(this)
                lines.background = ui.round(pal.muted, 2)
                swatch.addView(lines, FrameLayout.LayoutParams(ui.dp(24), ui.dp(4), Gravity.TOP or Gravity.START).also { it.leftMargin = ui.dp(34); it.topMargin = ui.dp(21) })
                if (id == AppThemes.SYSTEM) {
                    // "Auto" shows both halves: the left half dark, the right half light
                    val half = View(this)
                    half.background = ui.round(AppThemes.light.bg, 14)
                    swatch.addView(half, 0, FrameLayout.LayoutParams(ui.dp(44), ui.dp(64), Gravity.END))
                }
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.addView(swatch, LinearLayout.LayoutParams(matchWidth, ui.dp(64)))
                cell.addView(ui.text(items[idx].second, 13f, if (on) Ui.INK else Ui.MUTED, on), LinearLayout.LayoutParams(wrap, wrap).also { it.topMargin = ui.dp(6) })
                cell.isClickable = true
                cell.contentDescription = items[idx].second + " theme"
                cell.setOnClickListener { switchTheme(id) }
                row.addView(cell, LinearLayout.LayoutParams(0, wrap, 1f).also { it.leftMargin = ui.dp(5); it.rightMargin = ui.dp(5); it.bottomMargin = ui.dp(8) })
            }
            g.addView(row)
        }
        col.addView(g)
    }

    // ---- rows: sliders, switches and choices that apply to the open overlay at once --------------------------------------------------------------
    private fun toggleRow(icon: Int, title: String, body: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = ui.head(icon, title, body, ui.switch(on, title, onChange), compact = true)
        row.setPadding(0, ui.dp(10), 0, ui.dp(10))
        return row
    }

    private fun sliderRow(icon: Int, title: String, body: String, min: Int, max: Int, value: Int, suffix: String, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, ui.dp(10), 0, ui.dp(4))
        val valueTv = ui.text("$value$suffix", 13f, Ui.SOFT, true)
        valueTv.gravity = Gravity.CENTER
        valueTv.minWidth = ui.dp(52)
        valueTv.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
        valueTv.background = ui.round(Ui.RAISED, 10)
        box.addView(ui.head(icon, title, body, valueTv, compact = true))
        val slider = LineSlider(this)
        slider.min = min
        slider.max = max
        slider.value = value
        slider.trackColor = Ui.TRACK
        slider.fillColor = Ui.RED
        slider.thumbColor = Ui.INK
        slider.contentDescription = title
        slider.onChange = { v ->
            valueTv.text = "$v$suffix"
            onChange(v)
        }
        box.addView(slider, LinearLayout.LayoutParams(matchWidth, ui.dp(30)).also { it.topMargin = ui.dp(2) })
        return box
    }

    /** One tidy row showing the current choice; tapping it slides a sheet up from the bottom with all the options. */
    private fun choiceRow(icon: Int, title: String, body: String, options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit): LinearLayout {
        var now = current
        val valueTv = ui.text(options.firstOrNull { it.second == now }?.first ?: "", 14f, Ui.SOFT, true)
        val chevron = ui.icon(R.drawable.ic_chevron_right, Ui.MUTED, 18)
        val trail = LinearLayout(this)
        trail.orientation = LinearLayout.HORIZONTAL
        trail.gravity = Gravity.CENTER_VERTICAL
        trail.addView(valueTv)
        trail.addView(chevron, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).also { it.leftMargin = ui.dp(4) })
        val box = ui.head(icon, title, body, trail, compact = true)
        box.setPadding(0, ui.dp(10), 0, ui.dp(10))
        box.isClickable = true
        box.getChildAt(1)?.setOnClickListener { box.performClick() }        // the words open the sheet too (the sheet shows the full text)
        box.setOnClickListener {
            showSheet(title, body, options, now) { v ->
                now = v
                valueTv.text = options.firstOrNull { it.second == v }?.first ?: ""
                onPick(v)
            }
        }
        return box
    }

    /** A bottom sheet: dimmed screen, a rounded panel that slides up, one tidy row per option with a tick on the current one. */
    private fun showSheet(title: String, body: String, options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(24))
        val r = ui.dp(26).toFloat()
        val bgD = GradientDrawable()
        bgD.setColor(Ui.CARD)
        bgD.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        bgD.setStroke(ui.dp(1), Ui.LINE)
        sheet.background = bgD
        val handle = View(this)
        handle.background = ui.round(Ui.TRACK, 2)
        sheet.addView(handle, LinearLayout.LayoutParams(ui.dp(40), ui.dp(4)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = ui.dp(14) })
        sheet.addView(ui.text(title, 18f, Ui.INK, true))
        if (body.isNotEmpty()) sheet.addView(ui.text(body, 13f, Ui.MUTED), LinearLayout.LayoutParams(wrap, wrap).also { it.topMargin = ui.dp(2); it.bottomMargin = ui.dp(8) })
        fun close(after: (() -> Unit)? = null) {
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(180).withEndAction {
                d.dismiss()
                after?.invoke()
            }.start()
        }
        for ((label, value) in options) {
            val on = value == current
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(ui.dp(14), 0, ui.dp(14), 0)
            row.background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), if (on) ui.round(ui.tint(Ui.RED, 16), 14) else ui.round(0x00000000, 14), null)
            row.addView(ui.text(label, 16f, if (on) Ui.INK else Ui.SOFT, on), LinearLayout.LayoutParams(0, wrap, 1f))
            if (on) row.addView(ui.icon(R.drawable.ic_check, Ui.RED, 20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
            row.isClickable = true
            row.setOnClickListener { close { onPick(value) } }
            sheet.addView(row, LinearLayout.LayoutParams(matchWidth, ui.dp(50)).also { it.topMargin = ui.dp(2) })
        }
        d.setContentView(sheet)
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.55f)
            w.decorView.setPadding(0, 0, 0, 0)
        }
        d.setCanceledOnTouchOutside(true)
        sheet.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheet.viewTreeObserver.removeOnPreDrawListener(this)
                sheet.translationY = sheet.height.toFloat()
                sheet.animate().translationY(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
                return true
            }
        })
        d.show()
    }

    private fun iconButton(res: Int, description: String, onClick: () -> Unit): FrameLayout {
        val b = FrameLayout(this)
        b.background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), ui.plate(Ui.RAISED, 12, Ui.LINE, 1), null)
        b.addView(ui.icon(res, Ui.MUTED, 18), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
        b.isClickable = true
        b.contentDescription = description
        b.setOnClickListener { onClick() }
        return b
    }

    /** Six themes and Custom. The panel, the tab and the pop-outs all take their colours from the one you pick. */
    private fun themeRow(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, ui.dp(14), 0, ui.dp(10))
        val nameTv = ui.text("", 13f, Ui.MUTED)
        box.addView(ui.head(R.drawable.ic_palette, "Theme", "", nameTv))
        val swatches = LinearLayout(this)
        swatches.orientation = LinearLayout.HORIZONTAL
        swatches.gravity = Gravity.CENTER_VERTICAL
        swatches.setPadding(0, ui.dp(12), 0, ui.dp(2))
        val custom = LinearLayout(this)
        custom.orientation = LinearLayout.VERTICAL
        val hue = LineSlider(this)
        hue.min = 0
        hue.max = 359
        hue.value = store.customHue
        hue.gradient = IntArray(7) { i -> Themes.hsl(i * 60 % 360, 0.85f, 0.55f) }
        hue.thumbColor = Ui.INK
        hue.contentDescription = "Colour"
        hue.onChange = { v ->
            store.customHue = v
            preview?.refresh()
        }
        val light = LineSlider(this)
        light.min = 8
        light.max = 94
        light.value = store.customLight
        light.gradient = intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())
        light.thumbColor = Ui.INK
        light.contentDescription = "Light or dark"
        light.onChange = { v ->
            store.customLight = v
            preview?.refresh()
        }
        custom.addView(hue, LinearLayout.LayoutParams(matchWidth, ui.dp(34)))
        custom.addView(light, LinearLayout.LayoutParams(matchWidth, ui.dp(34)))
        fun draw() {
            swatches.removeAllViews()
            val ids = ArrayList<Int>()
            for (t in Themes.presets) ids.add(t.id)
            ids.add(Themes.CUSTOM)
            val current = if (ids.contains(store.themeId)) store.themeId else 0
            for (id in ids) {
                val on = id == current
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                if (id == Themes.CUSTOM) {
                    g.setGradientType(GradientDrawable.SWEEP_GRADIENT)
                    g.setColors(IntArray(7) { i -> Themes.hsl(i * 60 % 360, 0.85f, 0.55f) })
                } else {
                    g.setColor(Themes.byId(id, 0, 0).bg)
                }
                g.setStroke(ui.dp(if (on) 2 else 1), if (on) Ui.INK else Ui.EDGE)
                val dot = FrameLayout(this)
                dot.background = g
                if (on) {
                    val mark = if (id == Themes.CUSTOM) 0xFFFFFFFF.toInt() else Themes.byId(id, 0, 0).text
                    dot.addView(ui.icon(R.drawable.ic_check, mark, 16), FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
                }
                dot.contentDescription = if (id == Themes.CUSTOM) "Custom colour" else Themes.byId(id, 0, 0).name
                val cell = FrameLayout(this)
                cell.addView(dot, FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER))
                cell.setOnClickListener {
                    store.themeId = id
                    draw()
                    preview?.refresh()
                }
                swatches.addView(cell, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
            }
            nameTv.text = if (current == Themes.CUSTOM) "Custom" else Themes.byId(current, 0, 0).name
            custom.visibility = if (current == Themes.CUSTOM) View.VISIBLE else View.GONE
        }
        draw()
        box.addView(swatches)
        box.addView(custom)
        return box
    }

    // ---- the live preview on the Look tab ---------------------------------------------------------------------------------------------------
    /**
     * A stand-in for the real overlay, drawn from the same theme, opacities, text size, glass, tab sizes and pop-out rules, so every control on the Look tab
     * shows its effect straight away. It stays pinned at the top while the settings scroll under it.
     * Two views: the Panel (with the tab), and the Pop-out (the tab on any of the four edges with the chat card beside it, which Play runs through messages
     * of different lengths so the card's resizing can be judged). "Tune" adds the edge and the backdrop (night game, daylight, busy picture).
     */
    private inner class Preview {
        val root = LinearLayout(this@MainActivity)
        private val bar = LinearLayout(this@MainActivity)
        private val stage = FrameLayout(this@MainActivity)
        private val panel = LinearLayout(this@MainActivity)
        private val dotIv = ui.icon(R.drawable.ic_broadcast, Ui.MUTED, 19)
        private val eyeIv = ui.icon(R.drawable.ic_user, Ui.INK, 17)
        private val heartIv = ui.icon(R.drawable.ic_heart_o, Ui.RED, 17)
        private val expandIv = ui.icon(R.drawable.ic_expand, Ui.INK, 14)
        private val dockIv = ui.icon(Icons.tuck(store.tuckTo), Ui.INK, 14)
        private val viewersTv = TextView(this@MainActivity)
        private val likesTv = TextView(this@MainActivity)
        private val divider = View(this@MainActivity)
        private val chatRows = ArrayList<TextView>()
        private val chips = ArrayList<FrameLayout>()
        private val blobs = ArrayList<View>()
        private val lines = listOf(
            Line(1L, "a", "Nia", "that boss is tough", "member"),
            Line(2L, "b", "SavahBot", "Kofi rolled a 4", "bot"),
            Line(3L, "c", "Baraka", "can you show the settings?", "mod")
        )

        // the tab, drawn from TabMetrics: upright on the left and right edges, flat on the top and bottom ones
        private val tabStrip = LinearLayout(this@MainActivity)
        private val tabEye = ui.icon(R.drawable.ic_user, Ui.INK, TabMetrics.EYE_DP)
        private val tabHeart = ui.icon(R.drawable.ic_heart_o, Ui.RED, TabMetrics.HEART_DP)
        private val tabViewers = TextView(this@MainActivity)
        private val tabLikes = TextView(this@MainActivity)
        private val flatTab = LinearLayout(this@MainActivity)
        private val flatEye = ui.icon(R.drawable.ic_user, Ui.INK, TabMetrics.EYE_DP)
        private val flatHeart = ui.icon(R.drawable.ic_heart_o, Ui.RED, TabMetrics.HEART_DP)
        private val flatViewers = TextView(this@MainActivity)
        private val flatLikes = TextView(this@MainActivity)

        // the chat pop-out card: name small on top, message under it (same rules as the real card)
        private val card = LinearLayout(this@MainActivity)
        private val cardRows = List(3) { PRow() }
        private val samples = listOf(
            Line(11L, "", "Zuri", "I missed the start, what happened?", "member"),
            Line(12L, "", "Amara", "gg", "mod"),
            Line(13L, "", "Otieno", "who else is watching this late? this stream is great", ""),
            Line(14L, "", "SavahBot", "Kofi rolled a 4", "bot")
        )
        private var sampleAt = 0
        private val playRuns = ArrayList<Runnable>()

        private inner class PRow {
            val view = LinearLayout(this@MainActivity)
            val name = TextView(this@MainActivity)
            val body = TextView(this@MainActivity)

            init {
                view.orientation = LinearLayout.VERTICAL
                name.setTypeface(Typeface.DEFAULT_BOLD)
                name.setSingleLine(true)
                name.ellipsize = android.text.TextUtils.TruncateAt.END
                name.includeFontPadding = false
                body.ellipsize = android.text.TextUtils.TruncateAt.END
                body.includeFontPadding = false
                body.setLineSpacing(0f, 1.08f)
                view.addView(name)
                view.addView(body, LinearLayout.LayoutParams(wrap, wrap).also { it.topMargin = ui.dp(2) })
            }
        }

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundColor(Ui.BG)
            root.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
            bar.orientation = LinearLayout.VERTICAL
            root.addView(bar, LinearLayout.LayoutParams(matchWidth, wrap))

            stage.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, ui.dp(22).toFloat())
                }
            }
            stage.clipToOutline = true
            root.addView(stage, LinearLayout.LayoutParams(matchWidth, ui.dp(if (pvTune) 176 else 190)).also { it.topMargin = ui.dp(8) })
            blob(130, 0x33FFFFFF, Gravity.TOP or Gravity.END, 0, -30, -30, 0)
            blob(90, 0x22FFFFFF, Gravity.BOTTOM or Gravity.START, -20, 0, 0, -30)
            stage.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) = stopPlay()
            })

            // panel: header (light, viewers, likes, expand, tuck), a hairline, then a few chat lines
            panel.orientation = LinearLayout.VERTICAL
            val header = LinearLayout(this@MainActivity)
            header.orientation = LinearLayout.HORIZONTAL
            header.gravity = Gravity.CENTER_VERTICAL
            header.setPadding(ui.dp(12), 0, ui.dp(10), 0)
            for (t in listOf(viewersTv, likesTv)) {
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                t.setTypeface(Typeface.DEFAULT_BOLD)
                t.setSingleLine(true)
            }
            viewersTv.text = "76"
            likesTv.text = "303"
            header.addView(dotIv)
            header.addView(eyeIv, LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)).also { it.leftMargin = ui.dp(6) })
            header.addView(viewersTv, LinearLayout.LayoutParams(wrap, wrap).also { it.leftMargin = ui.dp(5) })
            header.addView(heartIv, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).also { it.leftMargin = ui.dp(12) })
            header.addView(likesTv, LinearLayout.LayoutParams(wrap, wrap).also { it.leftMargin = ui.dp(5) })
            header.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            header.addView(chip(expandIv), LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).also { it.rightMargin = ui.dp(6) })
            header.addView(chip(dockIv), LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
            panel.addView(header, LinearLayout.LayoutParams(matchWidth, ui.dp(44)))
            panel.addView(divider, LinearLayout.LayoutParams(matchWidth, 1))
            val chat = LinearLayout(this@MainActivity)
            chat.orientation = LinearLayout.VERTICAL
            chat.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(8))
            for (l in lines) {
                val tv = TextView(this@MainActivity)
                tv.setPadding(0, ui.dp(3), 0, ui.dp(3))
                chatRows.add(tv)
                chat.addView(tv)
            }
            panel.addView(chat, LinearLayout.LayoutParams(matchWidth, wrap))
            stage.addView(panel, FrameLayout.LayoutParams(ui.dp(212), wrap, Gravity.START or Gravity.CENTER_VERTICAL).also { it.leftMargin = ui.dp(14) })

            // upright tab
            tabStrip.orientation = LinearLayout.VERTICAL
            tabStrip.gravity = Gravity.CENTER
            tabStrip.setPadding(ui.dp(4), ui.dp(15), ui.dp(4), ui.dp(15))
            tabStrip.minimumWidth = ui.dp(TabMetrics.THICKNESS_DP)
            for (t in listOf(tabViewers, tabLikes, flatViewers, flatLikes)) {
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, TabMetrics.TEXT_SP)
                t.setTypeface(Typeface.DEFAULT_BOLD)
                t.setSingleLine(true)
                t.includeFontPadding = false
                t.gravity = Gravity.CENTER
            }
            tabViewers.text = "76"
            tabLikes.text = "303"
            flatViewers.text = "76"
            flatLikes.text = "303"
            tabStrip.addView(tabEye, LinearLayout.LayoutParams(ui.dp(TabMetrics.EYE_DP), ui.dp(TabMetrics.EYE_DP)))
            tabStrip.addView(tabViewers, LinearLayout.LayoutParams(wrap, wrap).also { it.topMargin = ui.dp(3) })
            tabStrip.addView(tabHeart, LinearLayout.LayoutParams(ui.dp(TabMetrics.HEART_DP), ui.dp(TabMetrics.HEART_DP)).also { it.topMargin = ui.dp(13) })
            tabStrip.addView(tabLikes, LinearLayout.LayoutParams(wrap, wrap).also { it.topMargin = ui.dp(3) })
            stage.addView(tabStrip, FrameLayout.LayoutParams(wrap, wrap, Gravity.END or Gravity.CENTER_VERTICAL).also { it.rightMargin = ui.dp(8) })

            // flat tab
            flatTab.orientation = LinearLayout.HORIZONTAL
            flatTab.gravity = Gravity.CENTER_VERTICAL
            flatTab.setPadding(ui.dp(15), ui.dp(4), ui.dp(15), ui.dp(4))
            flatTab.minimumHeight = ui.dp(TabMetrics.THICKNESS_DP)
            flatTab.addView(flatEye, LinearLayout.LayoutParams(ui.dp(TabMetrics.EYE_DP), ui.dp(TabMetrics.EYE_DP)))
            flatTab.addView(flatViewers, LinearLayout.LayoutParams(wrap, wrap).also { it.leftMargin = ui.dp(4) })
            flatTab.addView(flatHeart, LinearLayout.LayoutParams(ui.dp(TabMetrics.HEART_DP), ui.dp(TabMetrics.HEART_DP)).also { it.leftMargin = ui.dp(13) })
            flatTab.addView(flatLikes, LinearLayout.LayoutParams(wrap, wrap).also { it.leftMargin = ui.dp(4) })
            stage.addView(flatTab, FrameLayout.LayoutParams(wrap, wrap))

            // pop-out card
            card.orientation = LinearLayout.VERTICAL
            for ((i, r) in cardRows.withIndex()) card.addView(r.view, LinearLayout.LayoutParams(wrap, wrap).also { if (i > 0) it.topMargin = ui.dp(6) })
            stage.addView(card, FrameLayout.LayoutParams(wrap, wrap))

            buildBar()
            refresh()
        }

        // ---- the controls above the picture
        private fun buildBar() {
            bar.removeAllViews()
            val top = LinearLayout(this@MainActivity)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(ui.segmented(listOf(Pair("Panel", 0), Pair("Pop-out", 1)), pvView, { pvView = it; buildBar(); refresh() }, 7, 12.5f), LinearLayout.LayoutParams(0, wrap, 1f))
            val play = ui.pill("Play") { play() }
            top.addView(play, LinearLayout.LayoutParams(wrap, wrap).also { it.leftMargin = ui.dp(8) })
            val tune = ui.pill(if (pvTune) "Less" else "Tune") {
                pvTune = !pvTune
                stage.layoutParams = (stage.layoutParams as LinearLayout.LayoutParams).also { it.height = ui.dp(if (pvTune) 176 else 190) }
                buildBar()
                refresh()
            }
            top.addView(tune, LinearLayout.LayoutParams(wrap, wrap).also { it.leftMargin = ui.dp(6) })
            bar.addView(top)
            if (pvTune) {
                if (pvView == 1) {
                    val edges = listOf(Pair("Left", Edge.LEFT.ordinal), Pair("Right", Edge.RIGHT.ordinal), Pair("Top", Edge.TOP.ordinal), Pair("Bottom", Edge.BOTTOM.ordinal))
                    bar.addView(ui.segmented(edges, pvEdge, { pvEdge = it; refresh() }, 7, 12.5f), LinearLayout.LayoutParams(matchWidth, wrap).also { it.topMargin = ui.dp(6) })
                }
                bar.addView(ui.segmented(listOf(Pair("Night game", 0), Pair("Daylight", 1), Pair("Busy", 2)), pvBack, { pvBack = it; refresh() }, 7, 12.5f), LinearLayout.LayoutParams(matchWidth, wrap).also { it.topMargin = ui.dp(6) })
            }
        }

        /** Switches the picture to the Panel or Pop-out view (used when the person changes a setting that belongs to one of them). */
        fun focus(view: Int) {
            if (pvView != view) {
                pvView = view
                buildBar()
            }
            refresh()
        }

        private fun blob(sizeDp: Int, color: Int, gravity: Int, l: Int, t: Int, r: Int, b: Int) {
            val v = View(this@MainActivity)
            v.background = GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                it.setColor(color)
            }
            blobs.add(v)
            stage.addView(v, FrameLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp), gravity).also {
                it.leftMargin = ui.dp(l)
                it.topMargin = ui.dp(t)
                it.rightMargin = ui.dp(r)
                it.bottomMargin = ui.dp(b)
            })
        }

        private fun chip(icon: ImageView): FrameLayout {
            val f = FrameLayout(this@MainActivity)
            f.addView(icon, FrameLayout.LayoutParams(icon.layoutParams.width, icon.layoutParams.height, Gravity.CENTER))
            chips.add(f)
            return f
        }

        // ---- pop-out card: runs the real card's rules (width cap, lines per edge, glass, opacity, messages at once)
        private fun bindCard(animated: Boolean) {
            if (animated && card.visibility == View.VISIBLE) {
                val t = android.transition.TransitionSet()
                t.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER)
                t.addTransition(android.transition.ChangeBounds())
                t.addTransition(android.transition.Fade())
                t.setDuration(240)
                t.setInterpolator(android.view.animation.DecelerateInterpolator())
                android.transition.TransitionManager.beginDelayedTransition(stage, t)
            }
            val t = Themes.byId(store.themeId, store.customHue, store.customLight)
            val k = store.popScale / 100f
            val vertical = pvEdge == Edge.LEFT.ordinal || pvEdge == Edge.RIGHT.ordinal
            val padL = ui.dp((9 * k).toInt())
            val padR = ui.dp((11 * k).toInt())
            val maxW = minOf(ui.dp(((if (vertical) 150 else 200) * k).toInt()), (stage.width * 0.7f).toInt().let { if (it <= 0) Int.MAX_VALUE else it })
            val textMax = maxOf(ui.dp(50), maxW - padL - padR)
            card.setPadding(padL, ui.dp((6 * k).toInt()), padR, ui.dp((6 * k).toInt()))
            card.background = if (store.glass) Glass.surface(t, store.popOpacity, ui.dp((12 * k).toInt()).toFloat(), ui.dp(1).toFloat()) else GradientDrawable().also {
                it.setColor(Themes.withAlpha(t.surface, store.popOpacity))
                it.setCornerRadius(ui.dp((12 * k).toInt()).toFloat())
                it.setStroke(ui.dp(1), Themes.withAlpha(t.text, 30))
            }
            val n = store.popLines
            val first = maxOf(0, sampleAt - n + 1)
            for ((i, r) in cardRows.withIndex()) {
                val idx = first + i
                if (i < n && idx <= sampleAt) {
                    val s = samples[idx % samples.size]
                    r.view.visibility = View.VISIBLE
                    r.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * k)
                    r.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * k)
                    r.name.maxWidth = textMax
                    r.body.maxWidth = textMax
                    r.body.maxLines = if (vertical) 2 else 1
                    r.name.setTextColor(ChatStyle.levelColor(t, s.level))
                    r.body.setTextColor(t.text)
                    r.name.text = ChatStyle.nameWithBadge(this@MainActivity, t, s, r.name.textSize)
                    r.body.text = s.text
                    (r.view.layoutParams as LinearLayout.LayoutParams).topMargin = if (i > 0) ui.dp((6 * k).toInt()) else 0
                } else {
                    r.view.visibility = View.GONE
                }
            }
        }

        private fun stopPlay() {
            for (r in playRuns) stage.removeCallbacks(r)
            playRuns.clear()
        }

        /** The card slides out of the tab, then three more messages of different lengths arrive one by one: watch the card ease to each new size. */
        fun play() {
            if (pvView != 1) focus(1)
            stopPlay()
            sampleAt = 0
            bindCard(false)
            val d = ui.dp(28).toFloat()
            card.alpha = 0f
            card.translationX = when (pvEdge) { Edge.RIGHT.ordinal -> d; Edge.LEFT.ordinal -> -d; else -> 0f }
            card.translationY = when (pvEdge) { Edge.TOP.ordinal -> -d; Edge.BOTTOM.ordinal -> d; else -> 0f }
            card.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
            for (i in 1..3) {
                val r = Runnable {
                    sampleAt = i
                    bindCard(true)
                }
                playRuns.add(r)
                stage.postDelayed(r, 1700L * i)
            }
        }

        // ---- picture: the backdrop and where things sit
        private fun paintBackdrop() {
            when (pvBack) {
                1 -> stage.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFBBD7F0.toInt(), 0xFFEFE3C6.toInt(), 0xFFF7D7B5.toInt()))
                2 -> stage.background = BusyDrawable(ui.dp(14).toFloat())
                else -> stage.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6C8CC0.toInt(), 0xFF34456B.toInt(), 0xFF171D2E.toInt()))
            }
            for (b in blobs) b.visibility = if (pvBack == 2) View.GONE else View.VISIBLE
        }

        private fun place(v: View, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) {
            v.layoutParams = FrameLayout.LayoutParams(wrap, wrap, gravity).also {
                it.leftMargin = ui.dp(l)
                it.topMargin = ui.dp(t)
                it.rightMargin = ui.dp(r)
                it.bottomMargin = ui.dp(b)
            }
        }

        private fun layoutScene() {
            if (pvView == 0) {
                panel.visibility = View.VISIBLE
                tabStrip.visibility = View.VISIBLE
                flatTab.visibility = View.GONE
                card.visibility = View.GONE
                return
            }
            panel.visibility = View.GONE
            val gap = 6
            val e = pvEdge
            val vertical = e == Edge.LEFT.ordinal || e == Edge.RIGHT.ordinal
            tabStrip.visibility = if (vertical) View.VISIBLE else View.GONE
            flatTab.visibility = if (vertical) View.GONE else View.VISIBLE
            card.visibility = View.VISIBLE
            val thick = TabMetrics.THICKNESS_DP
            when (e) {
                Edge.RIGHT.ordinal -> {
                    place(tabStrip, Gravity.END or Gravity.CENTER_VERTICAL, r = 8)
                    place(card, Gravity.END or Gravity.CENTER_VERTICAL, r = 8 + thick + gap)
                }
                Edge.LEFT.ordinal -> {
                    place(tabStrip, Gravity.START or Gravity.CENTER_VERTICAL, l = 8)
                    place(card, Gravity.START or Gravity.CENTER_VERTICAL, l = 8 + thick + gap)
                }
                Edge.TOP.ordinal -> {
                    place(flatTab, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 8)
                    place(card, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 8 + thick + gap)
                }
                else -> {
                    place(flatTab, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 8)
                    place(card, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 8 + thick + gap)
                }
            }
        }

        /** Repaints everything from the saved choices. Cheap enough to run on every slider tick. */
        fun refresh() {
            val t = Themes.byId(store.themeId, store.customHue, store.customLight)
            paintBackdrop()
            layoutScene()
            if (pvView == 1) {
                if (playRuns.isEmpty()) sampleAt = 0
                bindCard(false)
            }
            val top = Themes.mix(t.bg, t.text, 0.06f)
            panel.background = if (store.glass) Glass.surface(t, store.panelOpacity, ui.dp(20).toFloat(), ui.dp(1).toFloat()) else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Themes.withAlpha(top, store.panelOpacity), Themes.withAlpha(t.bg, store.panelOpacity))).also {
                it.setCornerRadius(ui.dp(20).toFloat())
                it.setStroke(ui.dp(1), Themes.withAlpha(t.text, 26))
            }
            dotIv.setColorFilter(t.accent)
            eyeIv.setColorFilter(t.text)
            heartIv.setColorFilter(t.heart)
            expandIv.setColorFilter(t.text)
            dockIv.setImageResource(Icons.tuck(store.tuckTo))
            dockIv.setColorFilter(t.text)
            viewersTv.setTextColor(t.text)
            likesTv.setTextColor(t.text)
            divider.setBackgroundColor(Themes.withAlpha(t.text, 16))
            for (c in chips) {
                val disc = GradientDrawable()
                disc.shape = GradientDrawable.OVAL
                disc.setColor(Themes.withAlpha(t.text, 12))
                c.background = disc
            }
            val sp = 13f * store.textScale / 100f
            for ((i, tv) in chatRows.withIndex()) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                tv.setTextColor(t.text)
                tv.text = ChatStyle.render(this@MainActivity, t, lines[i], tv.textSize)
            }

            val tabTop = Themes.mix(t.bg, t.text, 0.07f)
            val pill = ui.dp(TabMetrics.THICKNESS_DP / 2).toFloat()
            for (s in listOf(tabStrip, flatTab)) {
                s.background = if (store.glass) Glass.surface(t, 96, pill, ui.dp(1).toFloat()) else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Themes.withAlpha(tabTop, 96), Themes.withAlpha(t.bg, 96))).also {
                    it.setCornerRadius(pill)
                    it.setStroke(ui.dp(1), Themes.withAlpha(t.text, 34))
                }
                s.alpha = store.tabOpacity / 100f
            }
            for (e in listOf(tabEye, flatEye)) e.setColorFilter(t.text)
            for (h in listOf(tabHeart, flatHeart)) h.setColorFilter(t.heart)
            for (v in listOf(tabViewers, tabLikes, flatViewers, flatLikes)) v.setTextColor(t.text)
        }
    }

    /** A deliberately busy picture (stripes and dots in strong colours) to judge whether text stays readable over a messy game screen. */
    private class BusyDrawable(private val step: Float) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val cols = intArrayOf(0xFFE5242B.toInt(), 0xFFF5B301.toInt(), 0xFF2ECC71.toInt(), 0xFF2D7DF6.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt())
        override fun draw(c: android.graphics.Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            var i = 0
            var x = -h
            while (x < w) {
                p.color = cols[i % cols.size]
                val path = android.graphics.Path()
                path.moveTo(x, h)
                path.lineTo(x + step, h)
                path.lineTo(x + step + h, 0f)
                path.lineTo(x + h, 0f)
                path.close()
                c.drawPath(path, p)
                x += step
                i++
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    // ---- cards on Home --------------------------------------------------------------------------------------------------------------------
    /** SavahBot tells the app the newest SavahLive version, so a higher number there means a newer app exists. */
    private fun updateCard(col: LinearLayout) {
        if (!store.updateCheck) return
        val info = try {
            org.json.JSONObject(store.updateJson.ifEmpty { "{}" })
        } catch (e: Exception) {
            return
        }
        val version = info.optString("version", "")
        if (version.isEmpty() || !Versions.isNewer(version, BuildConfig.VERSION_NAME)) return
        val url = info.optString("url", "")
        val card = ui.card(18)
        card.addView(ui.head(R.drawable.ic_download, "SavahLive $version is available", "You have ${BuildConfig.VERSION_NAME}.", null, Ui.GREEN, ui.tint(Ui.GREEN, 16)))
        if (url.startsWith("https://") || url.startsWith("http://")) {
            card.addView(ui.button("Download", R.drawable.ic_download, Ui.PRIMARY) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    message = "Could not open the link."
                    render()
                }
            })
        } else {
            card.addView(ui.space(8))
            card.addView(ui.text("Ask whoever runs SavahBot for the new file.", 13f, Ui.MUTED))
        }
        col.addView(card)
    }

    private fun summaryCard(col: LinearLayout) {
        if (!store.summaryOn || store.summaryJson.isEmpty()) return
        val o = try {
            org.json.JSONObject(store.summaryJson)
        } catch (e: Exception) {
            return
        }
        val secs = o.optLong("seconds", 0)
        val duration = if (secs >= 3600) "${secs / 3600} h ${(secs % 3600) / 60} min" else "${maxOf(1L, secs / 60)} min"
        val card = ui.card(18)
        card.addView(ui.head(R.drawable.ic_chart, "Last stream", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(o.optLong("ended", 0))) + "  ·  " + duration))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, ui.dp(16), 0, 0)
        fun stat(label: String, value: String) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.addView(ui.text(value, 24f, Ui.INK, true))
            box.addView(ui.text(label, 12f, Ui.MUTED))
            row.addView(box, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        stat("Peak viewers", Fmt.count(o.optLong("peak", 0)))
        stat("Likes", Fmt.count(o.optLong("likes", 0)))
        stat("Messages", Fmt.count(o.optLong("messages", 0)))
        card.addView(row)
        val top = o.optJSONArray("top")
        if (top != null && top.length() > 0) {
            val names = ArrayList<String>()
            for (i in 0 until top.length()) {
                val t = top.optJSONArray(i) ?: continue
                names.add(t.optString(0) + " (" + t.optInt(1) + ")")
            }
            card.addView(ui.space(12))
            card.addView(ui.text("Top chatters: " + names.joinToString(", "), 13f, Ui.MUTED))
        }
        card.addView(ui.button("Clear", null, Ui.QUIET) {
            store.summaryJson = ""
            render()
        })
        col.addView(card)
    }

    // ---- actions ----------------------------------------------------------------------------------------------------------------------------
    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) (clip.getItemAt(0).coerceToText(this)?.toString() ?: "") else ""
        if (text.isBlank()) {
            message = "Nothing to paste yet. Copy the pairing link or code first."
            messageIsError = true
            render()
            return
        }
        typedCode = text.trim()
        onPairClicked()
    }

    private fun onPairClicked() {
        val input = Pairing.parse(typedCode, typedServer)
        if (input == null) {
            message = "That does not look like a pairing link or code. A code has 8 letters and numbers, like K7Q4-9M2X."
            messageIsError = true
            render()
            return
        }
        pair(input)
    }

    private fun pair(input: PairInput) {
        if (pairing) return
        val server = Pairing.normalizeServer(input.server ?: typedServer, BuildConfig.DEBUG)
        if (server == null) {
            message = "Enter the SavahBot address (https://…) from the pairing link."
            messageIsError = true
            showServer = true
            render()
            return
        }
        pairing = true
        message = "Pairing…"
        messageIsError = false
        render()
        Thread({
            var ok: Net.PairResult? = null
            var err = ""
            try {
                ok = Net.pair(server, input.code)
            } catch (e: Net.PairError) {
                err = e.message ?: "Pairing failed."
            } catch (e: Exception) {
                err = "Pairing failed. Please try again."
            }
            val done = ok
            runOnUiThread {
                pairing = false
                if (done != null) {
                    store.savePairing(server, done.key, done.name, done.expires)
                    message = ""
                    typedCode = ""
                } else {
                    message = err
                    messageIsError = true
                }
                render()
            }
        }, "savahlive-pair").start()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            render()
            return
        }
        message = ""
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFICATIONS)    // asked once; the overlay starts either way
        }
        OverlayService.start(this)
        main.postDelayed({ render() }, 700)
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle("Unpair this phone?")
            .setMessage("The overlay stops and this phone forgets SavahBot. You can pair again with a new code any time.")
            .setPositiveButton("Unpair") { _, _ ->
                OverlayService.stop(this)
                store.clearPairing("")
                message = ""
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
