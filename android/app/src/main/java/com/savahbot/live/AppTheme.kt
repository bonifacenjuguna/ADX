package com.savahbot.live

import android.content.Context
import android.content.res.Configuration

/**
 * The colours of one look of the APP (not the overlay: the overlay keeps its own themes, see Themes.kt).
 *
 * The order of [AppThemes.all] matters: choosing a theme that comes later slides in from the right, an earlier one from the left.
 */
class AppPalette(
    val key: String,
    val label: String,
    val light: Boolean,
    val soft: Boolean,                 // neumorphism: surfaces share the canvas colour and are shaped by light and shadow instead
    val bg: Int,
    val card: Int,
    val raised: Int,
    val line: Int,
    val ink: Int,
    val softInk: Int,
    val muted: Int,
    val ripple: Int,
    val edge: Int,                     // a stronger hairline (off switch, swatch outlines)
    val track: Int,                    // empty part of bars and sliders
    val knobOff: Int,
    val green: Int,
    val amber: Int,
    val drop: Int = 0                  // neumorphism only: the colour of the dark shadow
)

object AppThemes {
    const val SYSTEM = 0
    const val DARK = 1
    const val AMOLED = 2
    const val LIGHT = 3
    const val SOFT = 4

    val dark = AppPalette(
        "dark", "Dark", false, false,
        bg = 0xFF0B0D12.toInt(), card = 0xFF12161F.toInt(), raised = 0xFF1B2130.toInt(), line = 0x1AFFFFFF,
        ink = 0xFFF3F5F9.toInt(), softInk = 0xFFC3C9D8.toInt(), muted = 0xFF8D94A8.toInt(),
        ripple = 0x33FFFFFF, edge = 0x33FFFFFF, track = 0x33FFFFFF, knobOff = 0xFFA7AEC0.toInt(),
        green = 0xFF34D399.toInt(), amber = 0xFFF5B301.toInt()
    )
    val amoled = AppPalette(
        "amoled", "AMOLED", false, false,
        bg = 0xFF000000.toInt(), card = 0xFF0A0B0E.toInt(), raised = 0xFF16181D.toInt(), line = 0x22FFFFFF,
        ink = 0xFFF3F5F9.toInt(), softInk = 0xFFC3C9D8.toInt(), muted = 0xFF8A8F9E.toInt(),
        ripple = 0x33FFFFFF, edge = 0x40FFFFFF, track = 0x33FFFFFF, knobOff = 0xFFA7AEC0.toInt(),
        green = 0xFF34D399.toInt(), amber = 0xFFF5B301.toInt()
    )
    val light = AppPalette(
        "light", "Light", true, false,
        bg = 0xFFF3F5F9.toInt(), card = 0xFFFFFFFF.toInt(), raised = 0xFFEEF1F6.toInt(), line = 0x1A0B1220,
        ink = 0xFF12151C.toInt(), softInk = 0xFF3F4657.toInt(), muted = 0xFF667085.toInt(),
        ripple = 0x240B1220, edge = 0x330B1220, track = 0x240B1220, knobOff = 0xFF9AA3B5.toInt(),
        green = 0xFF0E9F6E.toInt(), amber = 0xFFB87900.toInt()
    )
    val softUi = AppPalette(
        "soft", "Soft", true, true,
        bg = 0xFFE3E8F0.toInt(), card = 0xFFE3E8F0.toInt(), raised = 0xFFE3E8F0.toInt(), line = 0x228A95AB,
        ink = 0xFF1E2430.toInt(), softInk = 0xFF4A5368.toInt(), muted = 0xFF6B748A.toInt(),
        ripple = 0x1F0B1220, edge = 0x338A95AB, track = 0x338A95AB, knobOff = 0xFFFFFFFF.toInt(),
        green = 0xFF0E9F6E.toInt(), amber = 0xFFB87900.toInt(), drop = 0xFF7E8AA3.toInt()
    )

    /** The order here is the order on screen, and decides which way the screen slides when you switch. */
    val all: List<Pair<Int, String>> = listOf(
        Pair(SYSTEM, "Auto"), Pair(DARK, "Dark"), Pair(AMOLED, "AMOLED"), Pair(LIGHT, "Light"), Pair(SOFT, "Soft")
    )

    fun palette(choice: Int, ctx: Context): AppPalette = when (choice) {
        DARK -> dark
        AMOLED -> amoled
        LIGHT -> light
        SOFT -> softUi
        else -> {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (night) dark else light
        }
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        val r = (((a shr 16) and 255) * u + ((b shr 16) and 255) * t).toInt()
        val g = (((a shr 8) and 255) * u + ((b shr 8) and 255) * t).toInt()
        val bl = ((a and 255) * u + (b and 255) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
