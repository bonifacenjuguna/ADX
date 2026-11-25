package com.savahbot.live

/** Which icon goes where, for the Android side (Geometry.kt stays plain Kotlin with no R, so it can be unit-tested on a normal JVM). */
object Icons {
    /** The tuck button's icon: an arrow into the edge the panel will go to (see Store.tuckTo). Nearest shows four arrows out to all four edges. */
    fun tuck(tuckTo: Int): Int = when (tuckTo) {
        0 -> R.drawable.ic_tuck_left
        1 -> R.drawable.ic_tuck_right
        2 -> R.drawable.ic_tuck_top
        3 -> R.drawable.ic_tuck_bottom
        5 -> R.drawable.ic_tuck
        else -> R.drawable.ic_tuck_near
    }

    /** The connection icon for a status word from the overlay: a crossed wifi when offline, plain wifi while it is trying, a link when unpaired. */
    fun link(word: String): Int = when {
        word.startsWith("Offline") -> R.drawable.ic_wifi_off
        word.startsWith("Unpaired") -> R.drawable.ic_link
        else -> R.drawable.ic_wifi
    }
}
