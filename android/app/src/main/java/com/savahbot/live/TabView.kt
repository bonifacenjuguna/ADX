package com.savahbot.live

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * One set of sizes for the tab on every edge. The strip that sits on the left or right edge is the top/bottom strip stood upright, so both are
 * exactly [THICKNESS_DP] thick (the side one used to be about 50% thicker). The app's Look preview draws the tab from the same numbers.
 */
object TabMetrics {
    const val THICKNESS_DP = 32
    const val TEXT_SP = 11f
    const val EYE_DP = 14
    const val HEART_DP = 13
}

/**
 * The panel's other form: a small rounded tab on a screen edge showing the live light, viewers and likes.
 * It is only content (no window of its own, so showing and hiding it can never fail): [Overlay] owns the one window and sizes it to [measure].
 * Two strips are built once (one upright for the left and right edges, one flat for the top and bottom) and only their visibility changes,
 * so nothing is ever added to or removed from the view tree while a finger is on it.
 * When chat arrives it can show a small card beside the tab; the card has its own size and opacity, separate from the panel's.
 */
class TabView(private val ctx: Context, private val host: Host) {
    interface Host {
        fun touched()
        fun dragStart()
        fun dragBy(dx: Int, dy: Int)
        fun dragEnd()
        fun tapped()
        fun released()
    }

    class Size(val winW: Int, val winH: Int, val boxW: Int, val boxH: Int)

    private companion object {
        const val AMBER = 0xFFF5B301.toInt()
    }

    private fun dp(v: Int): Int = dpOf(ctx, v)

    private var theme: Theme = Themes.presets[0]
    private var fontSp = 14f
    private var popScale = 100
    private var popOpacity = 95
    private var edge = Edge.RIGHT
    private var dotKind = 2
    private var viewersText = ""
    private var likesText = ""
    private var lifted = false             // held long enough to be picked up: glows, and follows the finger

    val view = FrameLayout(ctx)
    private val upright = Strip(true)
    private val flat = Strip(false)
    private val card = LinearLayout(ctx)
    private val row1 = Row()
    private val row2 = Row()
    private val row3 = Row()
    private val recent = ArrayList<Line>()          // the last few messages, so "show 2 or 3 at once" keeps the earlier ones
    private var styledVertical: Boolean? = null     // which shape the card was last styled for (beside the tab, or above/below it)

    val cardShowing: Boolean get() = card.visibility == View.VISIBLE

    init {
        card.orientation = LinearLayout.VERTICAL
        card.visibility = View.GONE
        card.addView(row1.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(row2.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(row3.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        view.addView(upright.box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        view.addView(flat.box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        view.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        view.setOnTouchListener(TabTouch())
        // on the left and right edges the phone's back swipe starts at the screen edge: ask it to leave the tab alone (Android 10+; the bottom edge is handled by floating the tab, see Overlay)
        view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (Build.VERSION.SDK_INT >= 29) v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                setLifted(false)       // never stay lifted if the window goes away mid-touch
            }
        })
        view.contentDescription = "SavahLive. Tap to open."
        paint()
        setEdge(Edge.RIGHT)
    }

    // ---- the strips ----------------------------------------------------------------------------------------------------------------------
    private inner class Strip(val vertical: Boolean) {
        val box = LinearLayout(ctx)
        val eye = iconView(ctx, R.drawable.ic_user, theme.text, TabMetrics.EYE_DP)
        val heart = iconView(ctx, R.drawable.ic_heart_o, theme.heart, TabMetrics.HEART_DP)
        val viewers = TextView(ctx)
        val likes = TextView(ctx)
        val note = iconView(ctx, R.drawable.ic_wifi_off, AMBER, 20)      // shown instead of the numbers when the connection is down: an icon, not a word

        init {
            for (t in listOf(viewers, likes)) {
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, TabMetrics.TEXT_SP)
                t.setTypeface(Typeface.DEFAULT_BOLD)
                t.setSingleLine(true)
                t.includeFontPadding = false     // exact text height, so the strip is exactly as thick as TabMetrics says
                t.gravity = Gravity.CENTER
            }
            note.visibility = View.GONE
            if (vertical) {
                // the same strip stood upright: its width is the thickness, its height is the content
                box.orientation = LinearLayout.VERTICAL
                box.gravity = Gravity.CENTER
                box.setPadding(dp(4), dp(15), dp(4), dp(15))
                box.minimumWidth = dp(TabMetrics.THICKNESS_DP)
                put(eye, 0, 0)
                put(viewers, 3, 0)
                put(heart, 13, 0)
                put(likes, 3, 0)
                put(note, 0, 0)
            } else {
                box.orientation = LinearLayout.HORIZONTAL
                box.gravity = Gravity.CENTER
                box.setPadding(dp(12), 0, dp(12), 0)
                box.minimumHeight = dp(TabMetrics.THICKNESS_DP)
                put(eye, 0, 0)
                put(viewers, 0, 4)
                put(heart, 0, 12)
                put(likes, 0, 4)
                put(note, 0, 0)
            }
        }

        private fun put(v: View, top: Int, left: Int) {
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(top)
            lp.leftMargin = dp(left)
            box.addView(v, lp)
        }

        fun paint() {
            val top = Themes.mix(theme.bg, theme.text, 0.07f)
            val a = if (lifted) 100 else 96
            if (Store(ctx).glass) {
                box.background = Glass.surface(theme, a, dp(TabMetrics.THICKNESS_DP / 2).toFloat(), dp(1).toFloat(), if (lifted) theme.accent else null)
            } else {
            val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Themes.withAlpha(if (lifted) Themes.mix(top, theme.text, 0.1f) else top, a), Themes.withAlpha(theme.bg, a)))
            g.setCornerRadius(dp(TabMetrics.THICKNESS_DP / 2).toFloat())     // a true pill
            if (lifted) g.setStroke(dp(2), theme.accent) else g.setStroke(dp(1), Themes.withAlpha(theme.text, 34))
            box.background = g
            }
            viewers.setTextColor(theme.text)
            likes.setTextColor(theme.text)
            eye.setColorFilter(theme.text)
            heart.setColorFilter(theme.heart)
        }

        fun showNote(text: String) {
            val on = text.isNotEmpty()
            if (on) note.setImageResource(Icons.link(text))
            note.contentDescription = text
            note.visibility = if (on) View.VISIBLE else View.GONE
            val numbers = if (on) View.GONE else View.VISIBLE
            eye.visibility = numbers
            viewers.visibility = numbers
            heart.visibility = numbers
            likes.visibility = numbers
        }

        fun set(v: String, l: String) {
            viewers.text = v
            likes.text = l
        }
    }

    private val isVertical: Boolean get() = edge == Edge.LEFT || edge == Edge.RIGHT
    private val active: Strip get() = if (isVertical) upright else flat

    // ---- look and numbers -----------------------------------------------------------------------------------------------------------------
    fun setLook(t: Theme, baseFontSp: Float, popScalePct: Int, popOpacityPct: Int) {
        theme = t
        fontSp = baseFontSp
        popScale = popScalePct
        popOpacity = popOpacityPct
        paint()
    }

    /** Returns true when what is shown changed, so the controller knows the tab may need a new size. */
    fun setHeader(viewers: String, likes: String, dot: Int): Boolean {
        val changed = viewers != viewersText || likes != likesText || dot != dotKind
        viewersText = viewers
        likesText = likes
        dotKind = dot
        upright.set(viewers, likes)
        flat.set(viewers, likes)
        return changed
    }

    private var bannerText = ""

    /** Plain words for a bad connection, in place of the numbers. Returns true when the tab's size may have changed. */
    fun setBanner(text: String): Boolean {
        if (text == bannerText) return false
        bannerText = text
        upright.showNote(text)
        flat.showNote(text)
        return true
    }

    /** A milestone: the tab shrinks a little and springs back (it never grows past its own window, which would clip). Only its own scale changes. */
    fun pulse() {
        val b = active.box
        b.animate().cancel()
        b.pivotX = b.width / 2f
        b.pivotY = b.height / 2f
        b.scaleX = 0.78f
        b.scaleY = 0.78f
        b.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun paint() {
        upright.paint()
        flat.paint()
        paintCard()
    }

    /**
     * The pop-out card: by default ONE message, the sender's name small on top and the message under it (more at once is a setting in the app).
     * Beside the tab (left and right edges) a message may take two lines; above or below the tab it keeps to one line, so the card stays low.
     * Everything scales with "Chat pop-outs > Size" in the app.
     */
    private fun cardMaxPx(k: Float): Int = min((dp(if (isVertical) 150 else 200) * k).toInt(), (ctx.resources.displayMetrics.widthPixels * 0.7f).toInt())

    private fun paintCard() {
        styledVertical = isVertical
        val k = popScale / 100f
        if (Store(ctx).glass) {
            card.background = Glass.surface(theme, popOpacity, dp((12 * k).toInt()).toFloat(), dp(1).toFloat())
        } else {
            val g = GradientDrawable()
            g.setColor(Themes.withAlpha(theme.surface, popOpacity))
            g.setCornerRadius(dp((12 * k).toInt()).toFloat())
            g.setStroke(dp(1), Themes.withAlpha(theme.text, 30))
            card.background = g
        }
        val padL = dp((9 * k).toInt())
        val padR = dp((11 * k).toInt())
        card.setPadding(padL, dp((6 * k).toInt()), padR, dp((6 * k).toInt()))
        val textMax = maxOf(dp(50), cardMaxPx(k) - padL - padR)
        for (r in listOf(row2, row3)) (r.view.layoutParams as LinearLayout.LayoutParams).topMargin = dp((6 * k).toInt())
        for (r in listOf(row1, row2, row3)) r.restyle(k, textMax)
    }

    /** One message in the card: the name small on top, the message under it. */
    private inner class Row {
        val view = LinearLayout(ctx)
        val name = TextView(ctx)
        val body = TextView(ctx)
        var line: Line? = null

        init {
            view.orientation = LinearLayout.VERTICAL
            view.visibility = View.GONE
            name.setTypeface(Typeface.DEFAULT_BOLD)
            name.setSingleLine(true)
            name.ellipsize = TextUtils.TruncateAt.END
            name.includeFontPadding = false
            body.ellipsize = TextUtils.TruncateAt.END
            body.includeFontPadding = false
            body.setLineSpacing(0f, 1.08f)
            view.addView(name)
            view.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) })
        }

        fun restyle(k: Float, textMax: Int) {
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * k)
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, min(fontSp, 11.5f) * k)
            body.maxLines = if (isVertical) 2 else 1
            name.maxWidth = textMax
            body.maxWidth = textMax
            line?.let { bind(it) }
        }

        /** The most height this row can take, so the window can be sized once and never change with the message. */
        fun maxHeight(): Int {
            val nf = name.paint.fontMetrics
            val bf = body.paint.fontMetrics
            val nameH = ceil(nf.descent - nf.ascent)
            val lineH = ceil((bf.descent - bf.ascent) * 1.08f)
            return (nameH + lineH * body.maxLines).toInt() + dp(2) + dp(3)
        }

        fun bind(l: Line) {
            line = l
            body.setTextColor(theme.text)
            if (l.level == "note") {
                name.visibility = View.GONE
                body.text = ChatStyle.render(ctx, theme, l, body.textSize)
            } else {
                name.setTextColor(ChatStyle.levelColor(theme, l.level))
                name.text = ChatStyle.nameWithBadge(ctx, theme, l, name.textSize)
                name.visibility = View.VISIBLE
                body.text = l.text
            }
            view.visibility = View.VISIBLE
        }

        fun clear() {
            line = null
            view.visibility = View.GONE
        }
    }

    /** How big the card can ever be with the current settings: the window is sized to this once, so a longer or shorter message never moves or resizes anything. */
    private fun cardReserve(): Pair<Int, Int> {
        val k = popScale / 100f
        val n = Store(ctx).popLines
        val h = dp((6 * k).toInt()) * 2 + n * row1.maxHeight() + (n - 1) * dp((6 * k).toInt()) + dp(4)
        return Pair(cardMaxPx(k), h)
    }

    // ---- arranging for an edge -----------------------------------------------------------------------------------------------------------
    /** Puts the strip and the card in the right order and corner for [e]. Only changes visibility and layout rules, never the view tree. */
    fun setEdge(e: Edge) {
        edge = e
        upright.box.visibility = if (isVertical) View.VISIBLE else View.GONE
        flat.box.visibility = if (isVertical) View.GONE else View.VISIBLE
        if (styledVertical != isVertical) paintCard()
        arrange()
    }

    /** Layout is done in [place], from screen positions, so the strip and the card never depend on each other's size. */
    private fun arrange() {}

    /**
     * Positions the strip at the screen spot ([bx], [by]) and returns the window that holds it as [x, y, w, h].
     * While a card is out the window is the strip plus the card's FIXED reserve (see [cardReserve]), so it is the same size for every message: the strip never
     * moves, nothing flickers, and only the card inside changes size (smoothly, see [pop]). The card hangs off the strip's inner side by gravity, not by its own size.
     */
    fun place(sw: Int, sh: Int, bx: Int, by: Int): IntArray {
        val s = active.box
        s.measure(View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST))
        val bw = s.measuredWidth
        val bh = s.measuredHeight
        val gap = dp(6)
        var ww = bw
        var wh = bh
        var wx = bx
        var wy = by
        val cl = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        if (cardShowing) {
            val r = cardReserve()
            val cw = r.first
            val ch = r.second
            when (edge) {
                Edge.RIGHT, Edge.LEFT -> {
                    ww = bw + gap + cw
                    wh = maxOf(bh, ch)
                    wx = if (edge == Edge.RIGHT) bx + bw - ww else bx
                    wy = Geo.clampI(by + bh / 2 - wh / 2, 0, sh - wh)
                    cl.gravity = (if (edge == Edge.RIGHT) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
                    if (edge == Edge.RIGHT) cl.rightMargin = bw + gap else cl.leftMargin = bw + gap
                }
                else -> {
                    ww = maxOf(bw, cw)
                    wh = bh + gap + ch
                    wx = Geo.clampI(bx + bw / 2 - ww / 2, 0, sw - ww)
                    wy = if (edge == Edge.BOTTOM) by + bh - wh else by
                    cl.gravity = (if (edge == Edge.BOTTOM) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
                    if (edge == Edge.BOTTOM) cl.bottomMargin = bh + gap else cl.topMargin = bh + gap
                }
            }
            card.layoutParams = cl
        }
        // The strip is pinned to its screen edge by GRAVITY (and centred across it), not by a margin worked out from the window's old position. So in
        // every frame, whether the window has already grown for the card or not, the strip is in the same place: nothing can jump when the first card of
        // a while appears. Only when the window had to be squeezed against a screen corner does the strip need a margin instead.
        val sl = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        if (isVertical) {
            val centred = wy == by + bh / 2 - wh / 2
            sl.gravity = (if (edge == Edge.RIGHT) Gravity.RIGHT else Gravity.LEFT) or (if (centred) Gravity.CENTER_VERTICAL else Gravity.TOP)
            if (!centred) sl.topMargin = by - wy
        } else {
            val centred = wx == bx + bw / 2 - ww / 2
            sl.gravity = (if (edge == Edge.BOTTOM) Gravity.BOTTOM else Gravity.TOP) or (if (centred) Gravity.CENTER_HORIZONTAL else Gravity.LEFT)
            if (!centred) sl.leftMargin = bx - wx
        }
        s.layoutParams = sl
        return intArrayOf(wx, wy, ww, wh)
    }

    /** The size the window needs now: the strip, plus the card's fixed reserve if a card is out. */
    fun measure(sw: Int, sh: Int): Size {
        arrange()
        val s = active.box
        s.measure(View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST))
        val bw = s.measuredWidth
        val bh = s.measuredHeight
        var w = bw
        var h = bh
        if (cardShowing) {
            val r = cardReserve()
            if (isVertical) {
                w = bw + dp(6) + r.first
                h = maxOf(bh, r.second)
            } else {
                w = maxOf(bw, r.first)
                h = bh + dp(6) + r.second
            }
        }
        return Size(w, h, bw, bh)
    }

    // ---- the pop-out card ------------------------------------------------------------------------------------------------------------------
    /**
     * Shows the newest message (or the newest 2 or 3, if the app says so). When a card is already out, the new message does not replace the old one with a jump:
     * the card eases to its new size and the text fades in. The window never changes size here, so nothing else on screen moves.
     */
    fun pop(lines: List<Line>) {
        if (lines.isEmpty()) return
        if (cardShowing) {
            val t = TransitionSet()
            t.setOrdering(TransitionSet.ORDERING_TOGETHER)
            t.addTransition(ChangeBounds())
            t.addTransition(Fade())
            t.setDuration(240)
            t.setInterpolator(DecelerateInterpolator())
            TransitionManager.beginDelayedTransition(view, t)
        }
        recent.addAll(lines)
        while (recent.size > 3) recent.removeAt(0)
        val n = Store(ctx).popLines
        val shown = if (recent.size > n) recent.subList(recent.size - n, recent.size) else recent
        val rows = listOf(row1, row2, row3)
        for (i in 0 until 3) {
            if (i < shown.size) rows[i].bind(shown[i]) else rows[i].clear()
        }
        card.visibility = View.VISIBLE
        arrange()
    }

    private var cardToken = 0

    /** Springs the card out of the tab: it grows from the strip's side, slides outward and fades in, with a small overshoot. Call after the window was sized for it. */
    fun animateIn() {
        if (!cardShowing) return
        cardToken++
        val token = cardToken
        card.animate().cancel()
        card.alpha = 0f                  // hidden until it has its real place, so the first card of a while never shows up in the wrong spot
        val start = Runnable {
            if (token != cardToken || !cardShowing) return@Runnable
            val w = card.width.toFloat()
            val h = card.height.toFloat()
            val d = dp(28).toFloat()
            var tx = 0f
            var ty = 0f
            when (edge) {
                Edge.RIGHT -> { card.pivotX = w; card.pivotY = h / 2f; tx = d }
                Edge.LEFT -> { card.pivotX = 0f; card.pivotY = h / 2f; tx = -d }
                Edge.TOP -> { card.pivotX = w / 2f; card.pivotY = 0f; ty = -d }
                Edge.BOTTOM -> { card.pivotX = w / 2f; card.pivotY = h; ty = d }
            }
            card.scaleX = 0.5f
            card.scaleY = 0.5f
            card.translationX = tx
            card.translationY = ty
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(320).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
        // after the window has been resized and the card laid out in it (never from the card's old, empty size)
        val vto = card.viewTreeObserver
        vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (card.width == 0 || card.height == 0 || card.isLayoutRequested) return true
                card.viewTreeObserver.removeOnPreDrawListener(this)
                start.run()
                return true
            }
        })
    }

    /** Tucks the card back into the tab, then runs [done] (which removes it from the window's size). Cancelled cleanly if anything else touches the card first. */
    fun animateOut(done: () -> Unit) {
        if (!cardShowing) { done(); return }
        val token = ++cardToken
        val d = dp(16).toFloat()
        val tx = when (edge) { Edge.RIGHT -> d; Edge.LEFT -> -d; else -> 0f }
        val ty = when (edge) { Edge.TOP -> -d; Edge.BOTTOM -> d; else -> 0f }
        card.animate().cancel()
        card.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).translationX(tx).translationY(ty)
            .setDuration(180).setInterpolator(AccelerateInterpolator())
            .withEndAction { if (token == cardToken) done() }.start()
    }

    /** A new message while the card is already out: no pulse and no jump (the card eases in [pop]); just make sure it is fully showing. */
    fun bump() {
        if (!cardShowing) return
        cardToken++
        card.animate().cancel()
        card.alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
        card.translationX = 0f
        card.translationY = 0f
    }

    private fun resetCardLook() {
        card.animate().cancel()
        card.alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
        card.translationX = 0f
        card.translationY = 0f
    }

    fun clearCard() {
        cardToken++
        resetCardLook()
        card.visibility = View.GONE
        row1.clear()
        row2.clear()
        row3.clear()
        recent.clear()
        arrange()
    }

    /** Lifted = held long enough to be picked up. Glows in the accent colour, shows at full strength, and the icons pop. Nothing grows, because the window is exactly the tab's size. */
    fun setLifted(on: Boolean) {
        if (lifted == on) return
        lifted = on
        paint()
        if (on) view.alpha = 1f
        for (v in listOf(upright.eye, upright.heart, flat.eye, flat.heart)) {
            v.animate().scaleX(if (on) 1.25f else 1f).scaleY(if (on) 1.25f else 1f).setDuration(160).setInterpolator(OvershootInterpolator()).start()
        }
    }

    // ---- touch: a tap opens the panel; hold to lift it, then drag to move it ---------------------------------------------------------------
    // A quick slide does nothing to the tab, so the phone's own gestures (swipe up for home, back from the edge) get the swipe without the tab moving.
    private inner class TabTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var lift = false          // picked up: the finger now moves the tab
        private var swiped = false       // the finger slid before the tab lifted, so this touch is a swipe, not a tap
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val holdRun = Runnable { pickUp(true) }

        private fun holdMs(): Long = if (edge == Edge.BOTTOM) 450L else 350L     // longer at the bottom, where the swipe-up gesture lives

        private fun pickUp(haptic: Boolean) {
            if (lift) return
            lift = true
            downX = lastX                      // drag from where the finger is now, so the tab does not jump
            downY = lastY
            host.dragStart()
            setLifted(true)
            if (haptic) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        private fun finish(cancelled: Boolean) {
            view.removeCallbacks(holdRun)
            if (lift) {
                lift = false
                setLifted(false)
                host.dragEnd()
            } else if (swiped || cancelled) {
                host.released()
            } else {
                host.tapped()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    lastX = downX
                    lastY = downY
                    lift = false
                    swiped = false
                    host.touched()
                    if (Store(ctx).holdToMove) view.postDelayed(holdRun, holdMs())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    val dx = lastX - downX
                    val dy = lastY - downY
                    if (lift) {
                        host.dragBy(dx.toInt(), dy.toInt())
                    } else if (abs(dx) > slop || abs(dy) > slop) {
                        view.removeCallbacks(holdRun)
                        if (Store(ctx).holdToMove) swiped = true else pickUp(false)       // with "Hold to move" off, a slide moves it at once
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    finish(false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    finish(true)
                    return true
                }
            }
            return false
        }
    }
}
