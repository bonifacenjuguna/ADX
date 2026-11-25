package com.savahbot.live

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The small kit the app's screens are built from, so every screen looks like it belongs to the same app. No layout XML, no libraries.
 *
 * The look: near-black canvas, softly lifted cards with a hairline edge, ONE accent (red) used only for the main action and for "on",
 * outline icons (res/drawable/ic_*.xml) on quiet rounded tiles, medium-weight labels and calm grey help text.
 */
class Ui(private val ctx: Context) {
    companion object {
        // The palette is live: MainActivity calls use() when the app theme changes, then redraws the screen.
        @JvmField var BG = 0xFF0B0D12.toInt()
        @JvmField var CARD = 0xFF12161F.toInt()
        @JvmField var RAISED = 0xFF1B2130.toInt()
        @JvmField var LINE = 0x1AFFFFFF
        @JvmField var INK = 0xFFF3F5F9.toInt()
        @JvmField var SOFT = 0xFFC3C9D8.toInt()      // icons and secondary text that should read as "present" but not shout
        @JvmField var MUTED = 0xFF8D94A8.toInt()
        @JvmField var GREEN = 0xFF34D399.toInt()
        @JvmField var AMBER = 0xFFF5B301.toInt()
        @JvmField var RIPPLE = 0x33FFFFFF
        @JvmField var EDGE = 0x33FFFFFF
        @JvmField var TRACK = 0x33FFFFFF
        @JvmField var KNOB_OFF = 0xFFA7AEC0.toInt()
        @JvmField var LIGHT = false
        @JvmField var SOFT_UI = false
        private var hi = 0
        private var shade = 0
        private var drop = 0
        const val RED = 0xFFE5242B.toInt()
        const val ON_ACCENT = 0xFFFFFFFF.toInt()      // text on the red button, whatever the theme

        fun use(p: AppPalette) {
            BG = p.bg; CARD = p.card; RAISED = p.raised; LINE = p.line
            INK = p.ink; SOFT = p.softInk; MUTED = p.muted
            GREEN = p.green; AMBER = p.amber
            RIPPLE = p.ripple; EDGE = p.edge; TRACK = p.track; KNOB_OFF = p.knobOff
            LIGHT = p.light; SOFT_UI = p.soft
            hi = AppThemes.blend(p.bg, 0xFFFFFFFF.toInt(), 0.7f)
            shade = AppThemes.blend(p.bg, 0xFF8A95AB.toInt(), 0.24f)
            drop = p.drop
        }
        const val PRIMARY = 0
        const val SECONDARY = 1
        const val QUIET = 2
    }

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(v: Int): Int = dpOf(ctx, v)

    fun round(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.setCornerRadius(dp(radiusDp).toFloat())
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    /**
     * A surface. Normally a flat rounded rectangle; in the Soft theme it is shaped by light instead: a raised plate is lighter at the top left
     * and darker at the bottom right, a pressed-in (inset) one is the other way round.
     */
    fun plate(fill: Int, radiusDp: Int, strokeColor: Int = LINE, strokeDp: Int = 1, inset: Boolean = false): android.graphics.drawable.Drawable {
        if (!SOFT_UI) return round(fill, radiusDp, strokeColor, strokeDp)
        return NeuDrawable(dp(radiusDp).toFloat(), dp(1).toFloat(), inset, hi, shade, drop)
    }

    /** Soft theme only: the shadow is painted by [NeuDrawable] itself, so all that is needed here is room for it to show. */
    fun lift(v: View, dpv: Int) {
        if (!SOFT_UI) return
        (v as? android.view.ViewGroup)?.clipChildren = false
    }

    /** [color] with its transparency set to [percent]. */
    fun tint(color: Int, percent: Int): Int = Themes.withAlpha(color, percent)

    fun text(s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.setTypeface(if (sizeSp >= 20f) Typeface.DEFAULT_BOLD else medium)
        t.setLineSpacing(0f, 1.14f)
        return t
    }

    fun icon(res: Int, color: Int, sizeDp: Int): ImageView = iconView(ctx, res, color, sizeDp)

    fun space(h: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return v
    }

    fun hairline(insetStartDp: Int = 0): View {
        val v = View(ctx)
        v.setBackgroundColor(LINE)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also { it.leftMargin = dp(insetStartDp) }
        return v
    }

    fun card(padding: Int = 20): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        c.background = plate(CARD, 22, LINE, 1)
        lift(c, 7)
        c.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(12) }
        return c
    }

    /** "SavahLive" with the Live in red. */
    fun wordmark(sizeSp: Float): TextView {
        val sb = SpannableStringBuilder("SavahLive")
        sb.setSpan(ForegroundColorSpan(RED), 5, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val t = text(sb, sizeSp, INK, true)
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.letterSpacing = -0.01f
        return t
    }

    fun button(label: String, iconRes: Int?, kind: Int, onClick: () -> Unit): LinearLayout {
        val b = LinearLayout(ctx)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER
        b.setPadding(dp(18), 0, dp(18), 0)
        val fill: android.graphics.drawable.Drawable = when (kind) {
            PRIMARY -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFEF3A40.toInt(), 0xFFD91F26.toInt())).also { it.setCornerRadius(dp(16).toFloat()) }
            SECONDARY -> plate(RAISED, 16, LINE, 1)
            else -> round(0x00000000, 16)
        }
        b.background = RippleDrawable(ColorStateList.valueOf(if (kind == PRIMARY) 0x33FFFFFF else RIPPLE), fill, null)
        if (kind != QUIET) lift(b, 5)
        val ink = when (kind) { PRIMARY -> ON_ACCENT; QUIET -> SOFT; else -> INK }
        if (iconRes != null) {
            b.addView(icon(iconRes, ink, 20), LinearLayout.LayoutParams(dp(20), dp(20)).also { it.rightMargin = dp(10) })
        }
        b.addView(text(label, 16f, ink, true))
        b.isClickable = true
        b.isFocusable = true
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (kind == QUIET) 46 else 52)).also { it.topMargin = dp(12) }
        return b
    }

    /** A small button that sits at the end of a row ("Allow", "Use", "Delete"). */
    fun pill(label: String, color: Int = INK, onClick: () -> Unit): TextView {
        val t = text(label, 13f, color, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        t.background = RippleDrawable(ColorStateList.valueOf(RIPPLE), plate(RAISED, 12, LINE, 1), null)
        lift(t, 3)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /** The red small button for a row's main action ("Start"). */
    fun pillRed(label: String, onClick: () -> Unit): TextView {
        val t = text(label, 14f, ON_ACCENT, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), dp(10), dp(18), dp(10))
        val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFEF3A40.toInt(), 0xFFD91F26.toInt()))
        fill.setCornerRadius(dp(14).toFloat())
        t.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, null)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /** A square-ish action tile for Home. Tinted when [active]. */
    fun quickTile(res: Int, label: String, sub: String, active: Boolean, accent: Int, onClick: () -> Unit): LinearLayout {
        val t = LinearLayout(ctx)
        t.orientation = LinearLayout.VERTICAL
        t.setPadding(dp(14), dp(13), dp(14), dp(12))
        val face = if (active) round(tint(accent, 18), 18, tint(accent, 45), 1) else plate(CARD, 18, LINE, 1)
        t.background = RippleDrawable(ColorStateList.valueOf(RIPPLE), face, null)
        lift(t, 5)
        t.addView(icon(res, if (active) accent else SOFT, 22), LinearLayout.LayoutParams(dp(22), dp(22)))
        t.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        t.addView(text(label, 15f, INK, true))
        val s = text(sub, 12f, MUTED)
        s.maxLines = 1
        s.ellipsize = android.text.TextUtils.TruncateAt.END
        t.addView(s)
        t.isClickable = true
        t.isFocusable = true
        t.contentDescription = "$label, $sub"
        t.setOnClickListener { onClick() }
        return t
    }

    fun setEnabled(b: View, on: Boolean) {
        b.isEnabled = on
        b.alpha = if (on) 1f else 0.5f
    }

    fun field(hint: String, value: String, onChange: (String) -> Unit): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.setTextColor(INK)
        e.setHintTextColor(MUTED)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        e.setSingleLine(true)
        e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_VARIATION_URI
        e.setPadding(dp(16), dp(14), dp(16), dp(14))
        e.background = plate(RAISED, 16, LINE, 1, true)
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        return e
    }

    // ---- icons on tiles -------------------------------------------------------------------------------------------------------------------
    /** A rounded square holding one icon: the leading mark of a row. */
    fun tile(res: Int, color: Int = SOFT, sizeDp: Int = 38, fill: Int = RAISED): FrameLayout {
        val t = FrameLayout(ctx)
        t.background = if (fill == RAISED) plate(fill, if (sizeDp >= 48) 16 else 12, LINE, 0, true) else round(fill, if (sizeDp >= 48) 16 else 12)
        val iconSize = sizeDp * 5 / 11
        t.addView(icon(res, color, iconSize), FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER))
        t.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return t
    }

    /** A tile tinted with [color] (for status: green when fine, red for the main thing). */
    fun colorTile(res: Int, color: Int, sizeDp: Int = 38): FrameLayout = tile(res, color, sizeDp, tint(color, 16))

    fun numberBadge(n: Int): LinearLayout {
        val b = LinearLayout(ctx)
        b.gravity = Gravity.CENTER
        b.background = plate(RAISED, 13, LINE, 1, true)
        b.addView(text(n.toString(), 13f, INK, true))
        b.layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        return b
    }

    /** icon tile + title + small text (+ an optional view at the end) on one row. */
    fun head(res: Int?, title: String, body: String, trailing: View? = null, iconColor: Int = SOFT, tileFill: Int = RAISED, compact: Boolean = false): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        if (res != null) {
            row.addView(tile(res, iconColor, if (compact) 34 else 38, tileFill))
        }
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(title, 15f, INK, true))
        if (body.isNotEmpty()) {
            val b = text(body, if (compact) 12.5f else 13f, MUTED)
            if (compact) {
                // one line; tap the words to read the rest
                b.maxLines = 1
                b.ellipsize = android.text.TextUtils.TruncateAt.END
                col.setOnClickListener {
                    var top: View = col
                    while (top.parent is View && top.parent !is android.widget.ScrollView) top = top.parent as View
                    (top as? android.view.ViewGroup)?.let { g -> android.transition.TransitionManager.beginDelayedTransition(g) }
                    b.maxLines = if (b.maxLines == 1) Int.MAX_VALUE else 1
                }
            }
            col.addView(b)
        }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = if (res != null) dp(if (compact) 12 else 14) else 0 })
        if (trailing != null) {
            val lp = (trailing.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(12)
            row.addView(trailing, lp)
        }
        return row
    }

    fun infoRow(res: Int, color: Int, title: String, body: String): LinearLayout {
        val row = head(res, title, body, null, color, tint(color, 16))
        row.setPadding(0, dp(8), 0, dp(8))
        return row
    }

    fun numberedStep(n: Int, body: String): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))
        row.addView(numberBadge(n))
        row.addView(text(body, 14f, SOFT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = dp(12) })
        return row
    }

    /** Three segments: where you are in Pair > Allow > Go. */
    fun stepBar(current: Int): LinearLayout {
        val names = listOf("Pair", "Allow", "Go")
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, name) in names.withIndex()) {
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            val bar = View(ctx)
            bar.background = round(if (i <= current) RED else TRACK, 3)
            col.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))
            col.addView(text(name, 12f, if (i <= current) INK else MUTED, i == current), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = if (i < names.size - 1) dp(8) else 0 })
        }
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(18) }
        return row
    }

    fun chip(label: String, color: Int): TextView {
        val t = text(label, 12f, color, true)
        t.setPadding(dp(10), dp(4), dp(10), dp(4))
        t.background = round(tint(color, 15), 10)
        return t
    }

    /** A status chip with a small light in front ("On air", "Ready"). */
    fun statusChip(label: String, color: Int): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(11), dp(6), dp(12), dp(6))
        c.background = round(tint(color, 15), 14)
        val light = View(ctx)
        light.background = round(color, 4)
        c.addView(light, LinearLayout.LayoutParams(dp(8), dp(8)).also { it.rightMargin = dp(7) })
        c.addView(text(label, 12f, color, true))
        return c
    }

    // ---- controls -------------------------------------------------------------------------------------------------------------------------
    /** An on/off switch: a track with a knob that slides. */
    fun switch(on: Boolean, description: String, onChange: (Boolean) -> Unit): FrameLayout {
        val w = dp(50)
        val k = dp(24)
        val pad = dp(3)
        val track = FrameLayout(ctx)
        val knob = View(ctx)
        track.addView(knob, FrameLayout.LayoutParams(k, k, Gravity.CENTER_VERTICAL or Gravity.START).also { it.leftMargin = pad })
        var state = on
        fun paint(animate: Boolean) {
            track.background = if (state) round(RED, 15) else if (SOFT_UI) plate(RAISED, 15, LINE, 1, true) else round(RAISED, 15, EDGE, 1)
            knob.background = round(if (state) 0xFFFFFFFF.toInt() else KNOB_OFF, 12)
            if (SOFT_UI) lift(knob, 2)
            val tx = if (state) (w - k - 2 * pad).toFloat() else 0f
            if (animate) knob.animate().translationX(tx).setDuration(140).start() else knob.translationX = tx
        }
        paint(false)
        track.isClickable = true
        track.contentDescription = description
        track.setOnClickListener {
            state = !state
            paint(true)
            onChange(state)
        }
        track.layoutParams = LinearLayout.LayoutParams(w, dp(30))
        return track
    }

    /** A row of choices in one rounded control; the chosen one is filled red. */
    fun segmented(options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit, pad: Int = 9, sizeSp: Float = 13f): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(3), dp(3), dp(3), dp(3))
        row.background = plate(RAISED, 14, LINE, 1, true)
        val cells = ArrayList<TextView>()
        fun paint(selected: Int) {
            for ((i, c) in cells.withIndex()) {
                val on = options[i].second == selected
                c.background = if (on) round(RED, 11) else null
                c.setTextColor(if (on) ON_ACCENT else MUTED)
            }
        }
        for (o in options) {
            val c = text(o.first, sizeSp, MUTED, true)
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(pad), 0, dp(pad))
            c.isClickable = true
            c.setOnClickListener {
                onPick(o.second)
                paint(o.second)
            }
            cells.add(c)
            row.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(current)
        return row
    }
}


/**
 * A neumorphic surface drawn by hand (the system's own elevation shadow is far too faint to read as "raised").
 * Raised: a soft dark shadow falls to the bottom right and a soft light one to the top left, outside the bounds, so the parents
 * must not clip their children. Inset: the same two shadows fall inside, as if the surface were pressed in.
 */
class NeuDrawable(
    private val radius: Float,
    private val px: Float,                 // one dp in pixels
    private val inset: Boolean,
    private val hi: Int,
    private val shade: Int,
    private val dark: Int
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private val path = android.graphics.Path()
    private var alphaScale = 255

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val steps = 9
        val reach = px * 9f               // how far the shadow spreads
        val off = px * 5f                 // how far it is pushed away from the light
        val k = alphaScale / 255f
        if (!inset) {
            paint.style = android.graphics.Paint.Style.FILL
            for (i in steps downTo 1) {
                val grow = reach * i / steps
                paint.color = withAlpha(dark, (15 * k).toInt())
                rect.set(b.left + off - grow + px * 2, b.top + off - grow + px * 2, b.right + off + grow - px * 2, b.bottom + off + grow - px * 2)
                canvas.drawRoundRect(rect, radius + grow, radius + grow, paint)
                paint.color = withAlpha(0xFFFFFFFF.toInt(), (46 * k).toInt())
                rect.set(b.left - off - grow + px * 2, b.top - off - grow + px * 2, b.right - off + grow - px * 2, b.bottom - off + grow - px * 2)
                canvas.drawRoundRect(rect, radius + grow, radius + grow, paint)
            }
        }
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        paint.shader = android.graphics.LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            if (inset) shade else hi, if (inset) hi else shade, android.graphics.Shader.TileMode.CLAMP
        )
        paint.style = android.graphics.Paint.Style.FILL
        paint.alpha = alphaScale
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        if (inset) {
            // inner shadows: clip to the shape and draw its outline pushed in from the top left (dark) and bottom right (light)
            path.reset()
            path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            paint.style = android.graphics.Paint.Style.STROKE
            for (i in 1..6) {
                paint.strokeWidth = px * 2f * i
                paint.color = withAlpha(dark, (20 * k).toInt())
                canvas.save(); canvas.translate(px * 3f, px * 3f)
                canvas.drawRoundRect(rect, radius, radius, paint)
                canvas.restore()
                paint.color = withAlpha(0xFFFFFFFF.toInt(), (50 * k).toInt())
                canvas.save(); canvas.translate(-px * 3f, -px * 3f)
                canvas.drawRoundRect(rect, radius, radius, paint)
                canvas.restore()
            }
            canvas.restore()
        } else {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = px
            paint.color = withAlpha(0xFFFFFFFF.toInt(), (170 * k).toInt())
            rect.inset(px / 2f, px / 2f)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun withAlpha(c: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)

    override fun setAlpha(alpha: Int) { alphaScale = alpha; invalidateSelf() }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
