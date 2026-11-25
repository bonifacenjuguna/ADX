package com.savahbot.live

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Controls the floating UI and what it shows. There is exactly ONE system window for the whole life of the overlay. It holds both forms:
 *  - the [PanelView]: a free-form window (any size, drag the header to move, drag any edge or corner to resize);
 *  - the [TabView]: a small tab on a screen edge (tap to open, drag to move it to another edge).
 * Switching between them only changes which content is visible and the window's size and place. No window is ever added or removed in between, and no
 * view is ever added or removed while a finger is on it, so tucking and opening cannot crash or lose the overlay.
 * Every entry point is wrapped in [guard]: if something unexpected happens the overlay logs it and carries on instead of taking the app down.
 * It keeps the one chat model, theme and settings. It draws what SavahBot sent; it decides nothing.
 */
class Overlay(
    private val ctx: Context,
    private val store: Store,
    private val onStop: () -> Unit,
    private val onLockChanged: (Boolean) -> Unit,
    private val onModeChanged: () -> Unit = {}
) : PanelView.Host, TabView.Host {
    private enum class Mode { PANEL, TAB }

    private companion object {
        const val TAG = "SavahLive"
        const val MIN_W = 190
        const val MIN_H = 140
        const val MAX_W = 700
        const val MAX_H = 900
        const val EXPANDED_W = 420
        const val EXPANDED_H = 620
        const val SLIVER = 16
        const val POP_QUEUE_MAX = 25
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Int): Int = dpOf(ctx, v)
    /**
     * The tab on the bottom edge floats this far above it. The phone's swipe-up (home, recents) starts in a strip along the bottom of the screen, and a touch that
     * starts there belongs to the phone, not to us, so a tab sitting in that strip could never be held and dragged. Above the strip, it can.
     */
    private fun bottomGutter(): Int {
        var g = dp(34)
        if (Build.VERSION.SDK_INT >= 29) {
            val ins = root.rootWindowInsets?.systemGestureInsets
            if (ins != null) g = maxOf(g, ins.bottom)
        }
        return g + dp(6)
    }

    private fun screenW(): Int = ctx.resources.displayMetrics.widthPixels
    private fun screenH(): Int = ctx.resources.displayMetrics.heightPixels

    /** Runs [block]; if it throws, logs and carries on. A bug in the overlay must never close SavahLive or the game under it. */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "overlay recovered from an error", e)
            try {
                recover()
            } catch (e2: Exception) {
                Log.w(TAG, "overlay could not recover", e2)
            }
        }
    }

    // ---- model ---------------------------------------------------------------------------------------------------------------------------
    private val lines = ArrayList<Line>()
    private val shown = HashSet<Long>()
    private var prefs = Prefs(40, 5, 14, 3, false)
    private var link = Link.CONNECTING
    private var detail = ""
    private var state = State("live", false, "")
    private var stats: Stats? = null
    private var lastData = 0L
    private var notice = ""
    private var locked = false
    private var sending = false
    private var pinned: Line? = null          // the line pinned at the top of the panel (kept while the panel is tucked)
    private var modBusy = false

    // ---- the one window ------------------------------------------------------------------------------------------------------------------
    private val root = FrameLayout(ctx)
    private val panel = PanelView(ctx, store, this)
    private val tab = TabView(ctx, this)
    private val params = WindowManager.LayoutParams(
        dp(MIN_W),
        dp(MIN_H),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )
    private var added = false
    private var mode = Mode.PANEL
    private var keyboardOn = false
    private var moving = false

    // panel gestures
    private var moveX = 0
    private var moveY = 0
    private var rsMode = 0
    private var rsMoved = false
    private var rsX = 0
    private var rsY = 0
    private var rsW = 0
    private var rsH = 0

    // tab state
    private var edge = Edge.RIGHT
    private var along = 0.4f
    private var hiddenF = 0f            // 0 = fully showing, 1 = slid mostly off screen (only when the app's "slide away" setting is on)
    private var tabDragging = false
    private var dragX = 0
    private var dragY = 0
    private var anim: ValueAnimator? = null
    private var slide: ValueAnimator? = null

    private val hideRun = Runnable { guard { if (mode == Mode.TAB && !tabDragging && !tab.cardShowing) animateHidden(1f) } }
    private val pumpRun = Runnable { guard { pumpPop() } }
    private val popQueue = java.util.ArrayDeque<Line>()      // chat waiting to pop out, in order: a flood is shown one message at a time, never all at once
    private var popTimerOn = false
    private var wasLandscape = false
    private val staleCheck = Runnable { guard { redrawHeader() } }

    init {
        root.addView(panel.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(tab.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        params.gravity = Gravity.TOP or Gravity.LEFT
        // a tap outside the window while the keyboard is up puts the keyboard away
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && keyboardOn) {
                keyboard(false)
                true
            } else {
                false
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------
    fun show(): Boolean {
        wasLandscape = screenW() > screenH()
        edge = Edge.values().getOrElse(store.tabEdge) { Edge.RIGHT }
        along = store.tabAlong / 1000f
        mode = if (store.panelOpen) Mode.PANEL else Mode.TAB
        prefsOrLookChanged()
        redrawHeader()
        panel.setLines(lines)
        panel.setPin(pinned)
        panel.setExpanded(store.expanded)
        applyMode()
        return try {
            wm.addView(root, params)
            added = true
            store.watch { main.post { guard { prefsOrLookChanged() } } }
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(160).start()
            scheduleHide()
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not show the overlay", e)
            false           // no overlay permission, or the system refused: the service stops
        }
    }

    fun hide() {
        store.unwatch()
        main.removeCallbacksAndMessages(null)
        slide?.cancel()
        slide = null
        anim?.cancel()
        anim = null
        if (added) {
            try {
                panel.hideKeyboard()
                wm.removeView(root)
            } catch (e: Exception) {
                // already gone
            }
        }
        added = false
    }

    fun onScreenChanged() = guard {
        if (!added) return@guard
        val land = screenW() > screenH()
        if (land != wasLandscape) {
            // each orientation has its own remembered tab edge and place, and panel size and place (see Store)
            wasLandscape = land
            if (!tabDragging) {
                edge = Edge.values().getOrElse(store.tabEdge) { Edge.RIGHT }
                along = store.tabAlong / 1000f
            }
        }
        if (mode == Mode.PANEL) layoutPanel() else placeTab()
    }

    /** The last resort after an unexpected error: show the small tab, which is the simplest state, and carry on. */
    private fun recover() {
        tabDragging = false
        moving = false
        keyboardOn = false
        params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        rsMode = 0
        anim?.cancel()
        anim = null
        slide?.cancel()
        slide = null
        hiddenF = 0f
        mode = Mode.TAB
        store.panelOpen = false
        applyMode()
    }

    // ---- showing the right form ------------------------------------------------------------------------------------------------------------
    private fun applyMode() {
        if (mode == Mode.PANEL) {
            tab.view.visibility = View.GONE
            panel.view.visibility = View.VISIBLE
            layoutPanel()
        } else {
            panel.view.visibility = View.GONE
            tab.view.visibility = View.VISIBLE
            placeTab()
        }
        applyBlur()
        push()
        onModeChanged()
    }

    /** A real blur of what is behind the panel (Android 12+ with window blur on). Only while the full panel shows: the tab's window is bigger than the tab. */
    private fun applyBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        if (mode == Mode.PANEL && store.glass && Glass.blurOn(ctx)) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = dp(22)
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            params.blurBehindRadius = 0
        }
    }

    /** True while the full panel is showing, false while it is tucked into the tab. The notification's Hide/Show button follows it. */
    val panelOpen: Boolean get() = mode == Mode.PANEL

    private fun push() {
        if (!added) return
        try {
            wm.updateViewLayout(root, params)
        } catch (e: Exception) {
            // the window is being torn down
        }
    }

    // ---- the panel -----------------------------------------------------------------------------------------------------------------------
    /** The panel's size in pixels: the big size when expanded, otherwise the one you dragged it to, always fitting the screen. */
    private fun panelSize(): IntArray {
        val sw = screenW()
        val sh = screenH()
        val margin = dp(8)
        if (store.expanded) {
            val e = Geo.expandedSize(sw, sh, margin, dp(EXPANDED_W), dp(EXPANDED_H))
            return intArrayOf(Geo.clampI(e[0], dp(MIN_W), sw), Geo.clampI(e[1], dp(MIN_H), sh))
        }
        return intArrayOf(
            Geo.clampI(dp(store.panelWdp), dp(MIN_W), maxOf(dp(MIN_W), sw)),
            Geo.clampI(dp(store.panelHdp), dp(MIN_H), maxOf(dp(MIN_H), sh))
        )
    }

    private fun layoutPanel() {
        val sw = screenW()
        val sh = screenH()
        val size = panelSize()
        params.width = size[0]
        params.height = size[1]
        params.x = Geo.clampI((store.panelFx / 1000f * (sw - size[0])).toInt(), 0, sw - size[0])
        params.y = Geo.clampI((store.panelFy / 1000f * (sh - size[1])).toInt(), 0, sh - size[1])
        push()
    }

    private fun savePanelPosition() {
        val roomX = screenW() - params.width
        val roomY = screenH() - params.height
        store.panelFx = if (roomX > 0) (params.x * 1000L / roomX).toInt().coerceIn(0, 1000) else 500
        store.panelFy = if (roomY > 0) (params.y * 1000L / roomY).toInt().coerceIn(0, 1000) else 300
        store.panelPlaced = true          // from now on it reopens exactly here
    }

    override fun moveStart() = guard {
        moving = true
        moveX = params.x
        moveY = params.y
    }

    override fun moveBy(dx: Int, dy: Int) = guard {
        if (mode != Mode.PANEL) return@guard
        params.x = Geo.clampI(moveX + dx, 0, screenW() - params.width)
        params.y = Geo.clampI(moveY + dy, 0, screenH() - params.height)
        push()
    }

    override fun moveEnd() = guard {
        moving = false
        panel.outline(false)
        savePanelPosition()
    }

    override fun resizeStart(mode: Int) = guard {
        keyboard(false)
        rsMode = mode
        rsMoved = false
        rsX = params.x
        rsY = params.y
        rsW = params.width
        rsH = params.height
        panel.outline(true)
    }

    override fun resizeBy(dx: Int, dy: Int) = guard {
        if (rsMode == 0 || mode != Mode.PANEL) return@guard
        val sw = screenW()
        val sh = screenH()
        val r = Geo.resize(rsMode, rsX, rsY, rsW, rsH, dx, dy, dp(MIN_W), dp(MIN_H), minOf(dp(MAX_W), sw), minOf(dp(MAX_H), sh), sw, sh)
        params.x = r[0]
        params.y = r[1]
        params.width = r[2]
        params.height = r[3]
        rsMoved = true
        push()
    }

    override fun resizeEnd() = guard {
        panel.outline(false)
        if (rsMode == 0) return@guard
        rsMode = 0
        if (!rsMoved) return@guard          // a tap on an edge changes nothing
        // The size you dragged it to becomes the normal size; the expand button then goes between this and the big size.
        val d = ctx.resources.displayMetrics.density
        store.panelWdp = Math.round(params.width / d)
        store.panelHdp = Math.round(params.height / d)
        store.expanded = false
        panel.setExpanded(false)
        savePanelPosition()
    }

    /** The expand button: the big window, then back to the normal one. */
    override fun toggleExpand() = guard {
        keyboard(false)
        savePanelPosition()
        store.expanded = !store.expanded
        panel.setExpanded(store.expanded)
        layoutPanel()
    }

    // ---- keyboard and sending -------------------------------------------------------------------------------------------------------------
    /** An overlay cannot type by default. While the message box is in use the window becomes focusable, and goes back to pass-through afterwards. */
    override fun keyboard(on: Boolean) = guard {
        if (!added || on == keyboardOn) return@guard
        if (on && mode != Mode.PANEL) return@guard
        keyboardOn = on
        val focus = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val outside = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (on) {
            params.flags = (params.flags and focus.inv()) or outside
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        } else {
            params.flags = (params.flags or focus) and outside.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        applyLockFlag()
        push()
        if (on) main.postDelayed({ guard { if (keyboardOn) panel.focusInput() } }, 80) else panel.hideKeyboard()
    }

    override fun send(text: String) {
        if (sending) return
        sending = true
        val server = store.server
        Thread({
            var error = "Not paired."
            try {
                val key = store.key()
                if (key != null && server.isNotEmpty()) error = Net.send(server, key, text) ?: ""
            } catch (e: Exception) {
                error = "Could not send."
            }
            val result = error
            main.post {
                sending = false
                guard { panel.sendResult(result) }
            }
        }, "savahlive-send").start()
    }

    override fun pin(line: Line?) {
        main.post {
            guard {
                pinned = if (line == null || pinned?.id == line.id) null else line     // tapping the pinned line again lets it go
                panel.setPin(pinned)
            }
        }
    }

    override fun moderate(line: Line, action: String) {
        if (modBusy) return
        modBusy = true
        val server = store.server
        Thread({
            var error = "Not paired."
            try {
                val key = store.key()
                if (key != null && server.isNotEmpty()) error = Net.moderate(server, key, line.id, action) ?: ""
            } catch (e: Exception) {
                error = "Could not do that."
            }
            val result = error
            main.post {
                modBusy = false
                guard {
                    if (result.isEmpty()) {
                        panel.showNote(if (action == "delete") "Message hidden." else "${line.name} timed out for 5 minutes.", false)
                        if (action == "delete") purge(emptyList(), listOf(line.id))
                    } else {
                        panel.showNote(result, true)
                    }
                }
            }
        }, "savahlive-mod").start()
    }

    /** A milestone from the service (viewers or likes passed a round number): the open form pulses and says so. */
    fun celebrate(text: String) = guard {
        if (!added) return@guard
        if (mode == Mode.PANEL) {
            panel.celebrate(text)
        } else if (!tabDragging) {
            peek(listOf(Line(0, "", "", text, "note")))
            tab.pulse()
        }
    }

    // ---- lock ----------------------------------------------------------------------------------------------------------------------------
    private fun applyLockFlag() {
        val t = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = if (locked) params.flags or t else params.flags and t.inv()
    }

    /** Locking makes touches pass through the overlay (to play under it). It is switched back from the notification or the app. */
    fun setLocked(on: Boolean) = guard {
        locked = on
        if (on) keyboard(false)
        applyLockFlag()
        push()
    }

    // ---- tucking into a tab and opening again --------------------------------------------------------------------------------------------
    /** From the notification: panel to tab, or tab to panel. Runs on the next main-loop turn, like every other mode change. */
    fun togglePanel() {
        main.post { guard { if (mode == Mode.PANEL) tuckNow() else openPanelNow() } }
    }

    override fun tuck() {
        // not inside the button's own click handling: the next turn of the main loop
        main.post { guard { tuckNow() } }
    }

    private fun tuckNow() {
        if (!added || mode != Mode.PANEL) return
        keyboard(false)
        savePanelPosition()
        val sw = screenW()
        val sh = screenH()
        val cx = params.x + params.width / 2f
        val cy = params.y + params.height / 2f
        val wanted = when (store.tuckTo) {
            5 -> edge                                                   // the default: the side the tab was last on
            in 0..3 -> Edge.values()[store.tuckTo]                      // a side chosen in Look > Tab > Tuck to
            else -> Geo.nearestEdge(cx, cy, sw, sh)
        }
        if (wanted == edge) {
            // the tab is already on that side: it goes back exactly where it was last dragged, whatever the panel did in between
            tab.setEdge(edge)
        } else {
            edge = wanted
            tab.setEdge(wanted)
            val size = tab.measure(sw, sh)
            along = Geo.alongFraction(wanted, (cx - size.boxW / 2f).toInt(), (cy - size.boxH / 2f).toInt(), size.boxW, size.boxH, sw, sh)
            saveTab()
        }
        hiddenF = 0f
        mode = Mode.TAB
        store.panelOpen = false
        applyMode()
        scheduleHide()
    }

    private fun openPanelNow() {
        if (!added || mode != Mode.TAB) return
        main.removeCallbacks(hideRun)
        stopPop()
        val old = anim
        anim = null
        old?.cancel()
        slide?.cancel()
        slide = null
        tabDragging = false
        tab.clearCard()
        hiddenF = 0f
        mode = Mode.PANEL
        store.panelOpen = true
        panel.setLines(lines)
        panel.setExpanded(store.expanded)
        if (store.openNearTab && !store.panelPositionKnown) anchorPanelToTab()      // only the very first time: after that it always reopens where it was
        applyMode()
        growPanel()
    }

    /**
     * Opens the panel beside the tab's own edge and at the tab's own height (or width, for a tab on the top or bottom): a tab near the top opens a panel near
     * the top, a tab at the bottom opens one at the bottom, a tab on the left one on the left. The panel never jumps to the middle of the screen.
     * This only happens until the panel has been moved, resized or tucked once; after that it always reopens exactly where it was left.
    
     */
    private fun anchorPanelToTab() {
        val sw = screenW()
        val sh = screenH()
        val size = panelSize()
        val roomX = maxOf(0, sw - size[0])
        val roomY = maxOf(0, sh - size[1])
        val m = dp(8)
        val x: Int
        val y: Int
        when (edge) {
            Edge.RIGHT -> { x = roomX - m; y = (along * roomY).toInt() }
            Edge.LEFT -> { x = m; y = (along * roomY).toInt() }
            Edge.TOP -> { x = (along * roomX).toInt(); y = dp(24) }
            Edge.BOTTOM -> { x = (along * roomX).toInt(); y = roomY - bottomGutter() - m }
        }
        val cx = Geo.clampI(x, 0, roomX)
        val cy = Geo.clampI(y, 0, roomY)
        store.panelFx = if (roomX > 0) (cx * 1000L / roomX).toInt() else 500
        store.panelFy = if (roomY > 0) (cy * 1000L / roomY).toInt() else 300
    }

    /** The panel grows out of the side it was tucked into: a short scale and fade from that edge, so it reads as the tab opening. */
    private fun growPanel() {
        val v = panel.view
        val w = params.width.toFloat()
        val h = params.height.toFloat()
        when (edge) {
            Edge.RIGHT -> { v.pivotX = w; v.pivotY = h * along }
            Edge.LEFT -> { v.pivotX = 0f; v.pivotY = h * along }
            Edge.TOP -> { v.pivotX = w * along; v.pivotY = 0f }
            Edge.BOTTOM -> { v.pivotX = w * along; v.pivotY = h }
        }
        v.animate().cancel()
        v.alpha = 0f
        v.scaleX = 0.86f
        v.scaleY = 0.86f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun saveTab() {
        store.tabEdge = edge.ordinal
        store.tabAlong = (along * 1000).toInt().coerceIn(0, 1000)
    }

    /** Puts the tab flush against its edge, anchored there so a pop-out card growing beside it never moves it. */
    private fun placeTab() {
        if (mode != Mode.TAB) return
        val sw = screenW()
        val sh = screenH()
        tab.setEdge(edge)
        val s = tab.measure(sw, sh)
        val vertical = edge == Edge.LEFT || edge == Edge.RIGHT
        val extent = if (vertical) s.boxW else s.boxH
        // slides away by a quarter of its thickness at most (about its own padding), so the icons and numbers always stay fully readable
        val hidden = maxOf(0, (extent * 0.25f * hiddenF).toInt())
        val alongBox = Geo.alongPx(edge, along, s.boxW, s.boxH, sw, sh)
        // the strip's own screen position: flush to its edge and along it. A pop-out card is placed around it and never moves it.
        val f = Geo.flush(edge, alongBox, s.boxW, s.boxH, sw, sh, hidden)
        val win = tab.place(sw, sh, f.first, if (edge == Edge.BOTTOM) f.second - bottomGutter() else f.second)
        params.x = win[0]
        params.y = win[1]
        params.width = win[2]
        params.height = win[3]
        tab.view.alpha = tabAlpha()
        push()
    }

    /** The tab is never invisible: its opacity (set in the app, never below 40%) is lowered by at most a quarter while it is slid away. */
    private fun tabAlpha(): Float = (store.tabOpacity / 100f) * (1f - 0.25f * hiddenF)

    private fun animateHidden(to: Float) {
        slide?.cancel()
        slide = null
        if (Math.abs(hiddenF - to) < 0.001f) return
        val a = ValueAnimator.ofFloat(hiddenF, to)
        a.duration = if (to > hiddenF) 260 else 160
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            guard {
                hiddenF = it.animatedValue as Float
                placeTab()
            }
        }
        slide = a
        a.start()
    }

    /** Slides away only if you chose that in the app (Tab > Slide away). The default is to stay put where you can see it. */
    private fun scheduleHide() {
        main.removeCallbacks(hideRun)
        val s = store.tabHide
        if (s > 0 && added && mode == Mode.TAB && !tabDragging) main.postDelayed(hideRun, s * 1000L)
    }

    // ---- TabView.Host: what a finger does to the tab ----------------------------------------------------------------------------------------
    override fun touched() = guard {
        main.removeCallbacks(hideRun)
        animateHidden(0f)
    }

    override fun released() = guard { scheduleHide() }

    override fun tapped() {
        main.post { guard { openPanelNow() } }
    }

    override fun dragStart() = guard {
        // cancel any slide in progress first, so nothing from before can finish in the middle of this drag
        val old = anim
        anim = null
        old?.cancel()
        slide?.cancel()
        slide = null
        main.removeCallbacks(hideRun)
        stopPop()
        tabDragging = true
        hiddenF = 0f
        tab.clearCard()
        placeTab()
        dragX = params.x
        dragY = params.y
    }

    override fun dragBy(dx: Int, dy: Int) = guard {
        if (!tabDragging) return@guard
        params.x = Geo.clampI(dragX + dx, 0, screenW() - params.width)
        params.y = Geo.clampI(dragY + dy, 0, screenH() - params.height)
        push()
    }

    /** Let go: snap to the nearest edge. The change of shape (upright or flat) happens after the finger is up, not during the touch. */
    override fun dragEnd() {
        main.post { guard { settleTab() } }
    }

    private fun settleTab() {
        if (!tabDragging) return
        val sw = screenW()
        val sh = screenH()
        val bw = maxOf(1, params.width)
        val bh = maxOf(1, params.height)
        val e = Geo.nearestEdge(params.x + bw / 2f, params.y + bh / 2f, sw, sh)
        along = Geo.alongFraction(e, params.x, params.y, bw, bh, sw, sh)
        edge = e
        tab.setEdge(e)
        val s = tab.measure(sw, sh)
        val flushed = Geo.flush(e, Geo.alongPx(e, along, s.boxW, s.boxH, sw, sh), s.winW, s.winH, sw, sh, 0)
        val target = if (e == Edge.BOTTOM) Pair(flushed.first, flushed.second - bottomGutter()) else flushed
        params.width = s.winW
        params.height = s.winH
        val fromX = params.x
        val fromY = params.y
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 180
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            guard {
                val t = it.animatedValue as Float
                params.x = (fromX + (target.first - fromX) * t).toInt()
                params.y = (fromY + (target.second - fromY) * t).toInt()
                push()
            }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (anim !== a) return          // a newer drag took over
                anim = null
                guard {
                    tabDragging = false
                    saveTab()
                    placeTab()
                    scheduleHide()
                }
            }
        })
        anim = a
        a.start()
    }

    /**
     * New chat while the tab is showing. It goes into a queue and is shown one message at a time, each for the pop-out time set in the app, so a flood of
     * messages is a calm sequence and not a blur. When many are waiting, each one is shown a little shorter (never under 1.2 s), and if the queue grows
     * past 25 the oldest waiting ones are folded into a "+N more messages" note. Everything is still in the panel.
     */
    private fun peek(fresh: List<Line>) {
        if (!added || mode != Mode.TAB || tabDragging) return
        for (l in fresh) popQueue.addLast(l)
        if (popQueue.size > POP_QUEUE_MAX) {
            var dropped = 0
            while (popQueue.size > POP_QUEUE_MAX - 1) {
                val x = popQueue.pollFirst() ?: break
                dropped++
            }
            popQueue.addFirst(Line(0, "", "", "+$dropped more messages", "note"))
        }
        if (!popTimerOn) pumpPop()
    }

    /** How long the message on screen stays: the app's pop-out time (or SavahBot's if set to Auto), shorter while many are waiting. */
    private fun dwellMs(): Long {
        val base = (if (store.popSeconds > 0) store.popSeconds else prefs.peekSeconds) * 1000L
        return maxOf(1200L, base / (1 + popQueue.size / 3))
    }

    private fun pumpPop() {
        popTimerOn = false
        if (!added || mode != Mode.TAB || tabDragging) {
            popQueue.clear()
            return
        }
        val next = popQueue.pollFirst()
        if (next == null) {
            endPeek()
            return
        }
        val wasShowing = tab.cardShowing
        tab.pop(listOf(next))
        main.removeCallbacks(hideRun)
        slide?.cancel()
        slide = null
        hiddenF = 0f
        placeTab()
        if (!wasShowing) tab.animateIn() else tab.bump()
        popTimerOn = true
        main.removeCallbacks(pumpRun)
        main.postDelayed(pumpRun, dwellMs())
    }

    /** Forgets the queue and the timer (the panel opened, or the tab is being dragged). */
    private fun stopPop() {
        main.removeCallbacks(pumpRun)
        popTimerOn = false
        popQueue.clear()
    }

    private fun endPeek() {
        main.removeCallbacks(pumpRun)
        popTimerOn = false
        // the card tucks back into the tab first; only then does the window shrink, so the animation is never cut
        tab.animateOut { guard { tab.clearCard(); placeTab(); scheduleHide() } }
    }

    // ---- look and settings ---------------------------------------------------------------------------------------------------------------
    /** Runs when anything changes, here or on the app's settings screen (the open overlay follows live). Cheap when nothing it draws has changed. */
    private fun prefsOrLookChanged() {
        val theme = Themes.byId(store.themeId, store.customHue, store.customLight)
        panel.setTuckEdge(store.tuckTo)
        panel.setLook(theme, store.panelOpacity, prefs.fontSize.toFloat(), store.textScale, prefs.chatLines)
        tab.setLook(theme, prefs.fontSize.toFloat(), store.popScale, store.popOpacity)
        applyBlur()
        push()
        panel.setComposer(prefs.canSend && store.sendEnabled)
        panel.setInteractions(store.pinEnabled, store.modEnabled && prefs.canMod)
        if (!store.pinEnabled && pinned != null) {
            pinned = null
            panel.setPin(null)
        }
        redrawHeader()
        if (!added) return
        if (mode == Mode.PANEL) {
            // sizes or positions may have been reset from the app's settings screen
            if (rsMode == 0 && !moving) layoutPanel()
        } else if (!tabDragging) {
            edge = Edge.values().getOrElse(store.tabEdge) { Edge.RIGHT }
            along = store.tabAlong / 1000f
            placeTab()
            scheduleHide()
        }
    }

    // ---- what SavahBot sends -------------------------------------------------------------------------------------------------------------
    fun setLink(l: Link, d: String) = guard {
        link = l
        detail = d
        main.removeCallbacks(staleCheck)
        if (l == Link.ONLINE) lastData = System.currentTimeMillis() else main.postDelayed(staleCheck, 31_000)
        redrawHeader()
    }

    fun setState(s: State) = guard {
        state = s
        redrawHeader()
    }

    fun setStats(s: Stats) = guard {
        stats = s
        lastData = System.currentTimeMillis()
        redrawHeader()
    }

    fun setPrefs(p: Prefs) = guard {
        prefs = p
        prefsOrLookChanged()
        trim()
        if (mode == Mode.PANEL) panel.setLines(lines)
    }

    fun showNotice(text: String) = guard {
        notice = text
        redrawHeader()
    }

    private fun trim() {
        while (lines.size > prefs.chatLines) {
            val old = lines.removeAt(0)
            shown.remove(old.id)
        }
    }

    fun addChat(newLines: List<Line>, skipped: Int) = guard {
        val fresh = ArrayList<Line>()
        if (skipped > 0) fresh.add(Line(0, "", "", "+$skipped more messages", "note"))
        for (l in newLines) {
            if (l.id == 0L || shown.add(l.id)) fresh.add(l)
        }
        if (fresh.isEmpty()) return@guard
        lines.addAll(fresh)
        trim()
        if (mode == Mode.PANEL) panel.addLines(fresh) else peek(fresh)
    }

    fun purge(uids: List<String>, ids: List<Long>) = guard {
        val p = pinned
        if (p != null && ((p.id > 0 && ids.contains(p.id)) || (p.uid.isNotEmpty() && uids.contains(p.uid)))) {
            pinned = null
            panel.setPin(null)
        }
        val iter = lines.iterator()
        var changed = false
        while (iter.hasNext()) {
            val l = iter.next()
            if ((l.id > 0 && ids.contains(l.id)) || (l.uid.isNotEmpty() && uids.contains(l.uid))) {
                iter.remove()
                shown.remove(l.id)
                changed = true
            }
        }
        if (changed && mode == Mode.PANEL) panel.setLines(lines)
    }

    /** The numbers and the status line, worked out once and given to both forms. Nothing is shown as live when it is not. */
    private fun redrawHeader() {
        val fresh = link == Link.ONLINE || System.currentTimeMillis() - lastData < 30_000
        val s = stats
        val canShow = fresh && s != null && s.enabled && state.live
        val viewers = if (canShow && s != null) Fmt.count(s.viewers) else Fmt.count(null)
        val likes = if (canShow && s != null && !s.likesHidden) Fmt.count(s.likes) else Fmt.count(null)
        val demo = link == Link.ONLINE && state.mode == "demo"
        val dot = when {
            demo -> 3                                   // demo: the broadcast icon turns blue, so no label is needed
            link == Link.ONLINE && state.live -> 0
            link == Link.CONNECTING || link == Link.RECONNECTING || link == Link.WAITING -> 1
            else -> 2
        }
        var text = ""
        var alert = false
        when {
            notice.isNotEmpty() -> {
                text = notice
                alert = true
            }
            link == Link.CONNECTING -> text = "Connecting…"
            link == Link.RECONNECTING -> {
                text = "Reconnecting…"
                alert = true
            }
            link == Link.WAITING -> {
                text = detail
                alert = true
            }
            store.statusBanner && link != Link.ONLINE && !fresh -> {
                text = "Offline \u00B7 retrying"
                alert = true
            }
            demo -> text = ""                              // demo is shown by the blue icon, never by words (not even the demo stream's title)
            link == Link.ONLINE && !state.live -> text = "Waiting for your stream"
            link == Link.ONLINE && state.title.isNotEmpty() -> text = state.title
        }
        panel.setHeader(viewers, likes, dot, text, alert)
        val banner = if (!store.statusBanner || link == Link.ONLINE) "" else when {
            !fresh -> "Offline"
            link == Link.RECONNECTING -> "Retrying"
            link == Link.CONNECTING -> "Connecting"
            link == Link.WAITING -> "Waiting"
            link == Link.UNPAIRED -> "Unpaired"
            else -> ""
        }
        val bannerChanged = tab.setBanner(banner)
        if ((tab.setHeader(viewers, likes, dot) || bannerChanged) && added && mode == Mode.TAB && !tabDragging) placeTab()
    }
}
