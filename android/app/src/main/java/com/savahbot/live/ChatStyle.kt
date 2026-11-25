package com.savahbot.live

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan

/** How one chat line looks, shared by the panel and the tab's pop-out card so they always agree. */
object ChatStyle {
    fun levelColor(theme: Theme, level: String): Int {
        val light = Themes.luminance(theme.bg) > 0.5
        return when (level) {
            "owner" -> if (light) 0xFFB7791F.toInt() else 0xFFFFC857.toInt()
            "mod" -> if (light) 0xFF2563EB.toInt() else 0xFF6EA8FF.toInt()
            "member" -> if (light) 0xFF15803D.toInt() else 0xFF4ADE80.toInt()
            "bot" -> if (light) 0xFF7E22CE.toInt() else 0xFFC084FC.toInt()
            else -> theme.muted
        }
    }

    /** The little tag icon in front of a name: a robot for the bot, a shield for a moderator, a crown for the owner. Nothing for everyone else. */
    private fun badgeRes(level: String): Int = when (level) {
        "bot" -> R.drawable.ic_badge_bot
        "mod" -> R.drawable.ic_badge_mod
        "owner" -> R.drawable.ic_badge_owner
        else -> 0
    }

    private fun badge(ctx: Context, theme: Theme, level: String, textPx: Float): ImageSpan? {
        val res = badgeRes(level)
        if (res == 0) return null
        val d: Drawable = ctx.getDrawable(res)?.mutate() ?: return null
        d.setTint(levelColor(theme, level))
        val s = (textPx * 1.12f).toInt().coerceAtLeast(8)
        d.setBounds(0, 0, s, s)
        return ImageSpan(d, if (Build.VERSION.SDK_INT >= 29) ImageSpan.ALIGN_CENTER else ImageSpan.ALIGN_BASELINE)
    }

    /** Just a name with its tag in front, for the pop-out card where the name has its own line. */
    fun nameWithBadge(ctx: Context, theme: Theme, line: Line, textPx: Float): CharSequence {
        val b = badge(ctx, theme, line.level, textPx) ?: return line.name
        val sb = SpannableStringBuilder()
        sb.append("\uFFFC")
        sb.setSpan(b, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" ").append(line.name)
        return sb
    }

    /** The plain form (no tag icon), kept so older code that still calls render(theme, line) keeps building. */
    fun render(theme: Theme, line: Line): CharSequence {
        val sb = SpannableStringBuilder()
        if (line.level == "note") {
            sb.append(line.text)
            sb.setSpan(ForegroundColorSpan(theme.muted), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        sb.append(line.name).append("  ")
        sb.setSpan(ForegroundColorSpan(levelColor(theme, line.level)), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(line.text)
        return sb
    }

    fun render(ctx: Context, theme: Theme, line: Line, textPx: Float): CharSequence {
        val sb = SpannableStringBuilder()
        if (line.level == "note") {
            sb.append(line.text)
            sb.setSpan(ForegroundColorSpan(theme.muted), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        val b = badge(ctx, theme, line.level, textPx)
        if (b != null) {
            sb.append("\uFFFC")
            sb.setSpan(b, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
        }
        val start = sb.length
        sb.append(line.name).append("  ")
        sb.setSpan(ForegroundColorSpan(levelColor(theme, line.level)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(line.text)
        return sb
    }
}
