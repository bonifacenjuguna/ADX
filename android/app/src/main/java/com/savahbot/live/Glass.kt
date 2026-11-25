package com.savahbot.live

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The frosted-glass surface used by the panel, the tab and the chat pop-outs when "Glass look" is on.
 * Layers, bottom to top: a see-through tinted fill, a soft sheen from the top, a small specular glow near the top-left corner, and a thin
 * light rim that is brightest at the top edge and fades toward the bottom, like the edge of a glass pane catching light.
 * It is only a drawable (no blur of what is behind it: Android cannot blur behind a single view), so it costs almost nothing to draw.
 */
class GlassDrawable(
    private val radius: Float,
    private val tint: Int,           // colour of the glass, without alpha
    private val fillPct: Int,        // how solid the tint is, 0..100
    private val edge: Int,           // theme text colour, for the faint outer hairline
    private val rimPx: Float,
    private val accent: Int? = null,  // when set (tab lifted), the rim is this colour and thicker
    private val light: Boolean = false // a light theme: frosted white glass, with a stronger sheen, glow and edge than the dark one
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val inner = RectF()

    init {
        fill.style = Paint.Style.FILL
        fill.color = Themes.withAlpha(tint, fillPct)
        sheen.style = Paint.Style.FILL
        glow.style = Paint.Style.FILL
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = if (accent != null) rimPx * 2f else rimPx
        hair.style = Paint.Style.STROKE
        hair.strokeWidth = 1f
        hair.color = Themes.withAlpha(edge, if (light) 22 else 16)
    }

    private fun white(pct: Int): Int = Themes.withAlpha(0xFFFFFF, pct)

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        box.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val half = rim.strokeWidth / 2f
        inner.set(box.left + half, box.top + half, box.right - half, box.bottom - half)
        sheen.shader = LinearGradient(0f, box.top, 0f, box.bottom, if (light) intArrayOf(white(46), white(16), white(6), white(18)) else intArrayOf(white(9), white(2), white(0), white(3)), floatArrayOf(0f, 0.3f, 0.72f, 1f), Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(box.left + w * 0.22f, box.top, maxOf(w, h) * 0.8f, white(if (light) 60 else 10), white(0), Shader.TileMode.CLAMP)
        rim.shader = if (accent != null) null else LinearGradient(0f, box.top, 0f, box.bottom, if (light) intArrayOf(white(100), white(55), white(40), white(85)) else intArrayOf(white(55), white(18), white(12), white(30)), floatArrayOf(0f, 0.25f, 0.7f, 1f), Shader.TileMode.CLAMP)
        if (accent != null) rim.color = accent
    }

    override fun draw(c: Canvas) {
        if (box.isEmpty) return
        c.drawRoundRect(box, radius, radius, fill)
        c.drawRoundRect(box, radius, radius, sheen)
        c.drawRoundRect(box, radius, radius, glow)
        c.drawRoundRect(inner, radius, radius, rim)
        if (accent == null) {
            val o = RectF(box.left + 0.5f, box.top + 0.5f, box.right - 0.5f, box.bottom - 0.5f)
            c.drawRoundRect(o, radius, radius, hair)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object Glass {
    private fun isLight(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) > 150f
    }

    /**
     * Dark themes keep almost the full opacity you set, so text stays clear over a busy picture; the glass comes from a faint sheen and a light edge.
     * Light themes are frosted white glass: a bit more see-through, a bright sheen from the top, a glow in the corner and a white edge.
     */
    fun pct(userPct: Int, light: Boolean = false, blur: Boolean = false): Int =
        (userPct * (if (blur) (if (light) 50 else 56) else if (light) 80 else 95) / 100).coerceIn(14, 100)

    /** True when the phone (Android 12 or newer, with window blur enabled) can blur what is behind a window. The panel then gets a real frosted blur. */
    fun blurOn(ctx: android.content.Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return false
        return try {
            (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).isCrossWindowBlurEnabled
        } catch (e: Exception) {
            false
        }
    }

    fun surface(t: Theme, userPct: Int, radiusPx: Float, rimPx: Float, accent: Int? = null, blur: Boolean = false): Drawable {
        val light = isLight(t.bg)
        return GlassDrawable(radiusPx, if (light) Themes.mix(t.bg, 0xFFFFFFFF.toInt(), 0.6f) else t.bg, pct(userPct, light, blur), t.text, rimPx, accent, light)
    }
}
