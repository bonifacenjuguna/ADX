package com.savahbot.live

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Everything the app remembers: where SavahBot is, this phone's device key, and a few overlay choices.
 * The device key is encrypted with an AES key that lives in the Android Keystore and cannot be taken out of the phone.
 * It is never a SavahBot sign-in: it only opens the overlay feed, and SavahBot can cancel it at any time.
 */
class Store(context: Context) {
    private val dm = context.applicationContext.resources
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("savahlive", Context.MODE_PRIVATE)

    val server: String get() = prefs.getString("server", "") ?: ""
    val deviceName: String get() = prefs.getString("name", "") ?: ""
    val expires: Long get() = prefs.getLong("expires", 0L)          // epoch milliseconds, 0 = until removed

    /** What to tell the person next time the app opens (for example that SavahBot unpaired this phone). */
    var notice: String
        get() = prefs.getString("notice", "") ?: ""
        set(v) { prefs.edit().putString("notice", v).apply() }

    // ---- the floating panel and its edge tab: local choices, kept on this phone ----------------------------------------------------------
    private fun int(key: String, def: Int, lo: Int, hi: Int): Int = prefs.getInt(key, def).coerceIn(lo, hi)
    private fun put(key: String, v: Int) { prefs.edit().putInt(key, v).apply() }

    /**
     * Per-orientation memory: the panel's size and place and the tab's edge and place are kept separately for portrait and landscape.
     * Portrait keeps the old key names (so nothing is lost on update); landscape uses "L_" + the name and, until it has its own value, starts from portrait's.
     */
    private fun landscape(): Boolean = dm.displayMetrics.widthPixels > dm.displayMetrics.heightPixels
    private fun oint(key: String, def: Int, lo: Int, hi: Int): Int {
        val k = if (landscape()) "L_$key" else key
        return (if (prefs.contains(k)) prefs.getInt(k, def) else prefs.getInt(key, def)).coerceIn(lo, hi)
    }
    private fun oput(key: String, v: Int) { prefs.edit().putInt(if (landscape()) "L_$key" else key, v).apply() }

    var themeId: Int
        get() = prefs.getInt("themeId", 0)
        set(v) = put("themeId", v)
    var customHue: Int                       // 0..359, used by the Custom theme
        get() = int("customHue", 210, 0, 359)
        set(v) = put("customHue", v)
    var customLight: Int                     // 8..94
        get() = int("customLight", 18, 8, 94)
        set(v) = put("customLight", v)
    var panelOpacity: Int                    // percent of the panel background, 20..100 (text and icons stay fully visible)
        get() = int("panelOpacity", 92, 20, 100)
        set(v) = put("panelOpacity", v)
    var tabOpacity: Int                      // percent opacity of the edge tab, 40..100. Never lower, so the tab can always be found.
        get() = int("tabOpacity", 90, 40, 100)
        set(v) = put("tabOpacity", v)
    var tabHide: Int                         // seconds before the tab slides mostly off screen, 0 = never (the default)
        get() = int("tabHide", 0, 0, 30)
        set(v) = put("tabHide", v)
    var textScale: Int                       // percent, 80..140, panel chat text on top of the size SavahBot asks for
        get() = int("textScale", 100, 80, 140)
        set(v) = put("textScale", v)
    var popScale: Int                        // percent, 70..170, size of the chat pop-outs beside the tab (separate from the panel)
        get() = int("popScale", 100, 70, 170)
        set(v) = put("popScale", v)
    var popLines: Int                        // 1..3, how many chat messages the pop-out card shows at once (default 1)
        get() = int("popLines", 1, 1, 3)
        set(v) = put("popLines", v)
    var popOpacity: Int                      // percent, 30..100, how solid the chat pop-outs are (separate from the panel)
        get() = int("popOpacity", 95, 30, 100)
        set(v) = put("popOpacity", v)
    var holdToMove: Boolean                  // the tab and the panel's header must be held until they lift before they can be dragged (default on)
        get() = prefs.getBoolean("holdToMove", true)
        set(v) { prefs.edit().putBoolean("holdToMove", v).apply() }
    var sendEnabled: Boolean                 // show the message box in the panel (only if SavahBot allows this phone to send)
        get() = prefs.getBoolean("sendEnabled", true)
        set(v) { prefs.edit().putBoolean("sendEnabled", v).apply() }

    private fun flag(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    private fun putFlag(key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply() }
    private fun text(key: String): String = prefs.getString(key, "") ?: ""
    private fun putText(key: String, v: String) { prefs.edit().putString(key, v).apply() }

    // ---- extras, each with its own switch on the app's home screen ----------------------------------------------------------------------------
    var pinEnabled: Boolean                  // tap a chat line to pin it at the top of the panel
        get() = flag("pinEnabled", true)
        set(v) = putFlag("pinEnabled", v)
    var milestoneStep: Int                   // flash the tab and panel each time viewers or likes pass a multiple of this, 0 = off
        get() = int("milestoneStep", 100, 0, 1000)
        set(v) = put("milestoneStep", v)
    var modEnabled: Boolean                  // long-press a chat line to hide it or time its author out (only if SavahBot allows this phone)
        get() = flag("modEnabled", true)
        set(v) = putFlag("modEnabled", v)
    var statusBanner: Boolean                // say plainly when the connection is down, in the panel and on the tab
        get() = flag("statusBanner", true)
        set(v) = putFlag("statusBanner", v)
    var idleHidden: Boolean                  // the notification's Off button was pressed: keep it away until the overlay has been used again
        get() = flag("idleHidden", false)
        set(v) = putFlag("idleHidden", v)
    var controlsAlways: Boolean              // keep the notification (with a Start button) even when the overlay is off
        get() = flag("controlsAlways", true)
        set(v) = putFlag("controlsAlways", v)
    var glass: Boolean                       // frosted-glass look for the panel, tab and chat pop-outs
        get() = flag("glass", true)
        set(v) = putFlag("glass", v)
    var appTheme: Int                        // the APP's look (not the overlay's): 0 follow the phone, 1 dark (default), 2 AMOLED, 3 light, 4 soft
        get() = int("appTheme", 1, 0, 4)
        set(v) = put("appTheme", v)
    var tuckTo: Int                          // where the tab appears when the panel is tucked: 5 back where the tab was last (default), 0 left, 1 right, 2 top, 3 bottom, 4 the nearest edge
        get() = int("tuckMode", 5, 0, 5)          // a new key: an older saved "Right" from before this existed must not override the default
        set(v) = put("tuckMode", v)
    var openNearTab: Boolean                 // the panel opens beside the tab's edge (default) instead of where it was last left
        get() = flag("openNearTab", true)
        set(v) = putFlag("openNearTab", v)
    var popSeconds: Int                      // how long each pop-out message stays, 0 = what SavahBot asks for
        get() = int("popSeconds", 4, 0, 30)
        set(v) = put("popSeconds", v)
    var autoOpen: Boolean                    // start the overlay by itself when the app opens
        get() = flag("autoOpen", false)
        set(v) = putFlag("autoOpen", v)
    var autoStopMin: Int                     // stop the overlay this many minutes after the stream ends, 0 = never
        get() = int("autoStopMin", 0, 0, 180)
        set(v) = put("autoStopMin", v)
    var summaryOn: Boolean                   // keep a short summary of each stream and show it on the home screen
        get() = flag("summaryOn", true)
        set(v) = putFlag("summaryOn", v)
    var updateCheck: Boolean                 // tell me when SavahBot has a newer version of this app
        get() = flag("updateCheck", true)
        set(v) = putFlag("updateCheck", v)
    var presetsJson: String                  // saved layouts and looks, see Presets
        get() = text("presets")
        set(v) = putText("presets", v)
    var presetIndex: Int
        get() = int("presetIndex", -1, -1, 50)
        set(v) = put("presetIndex", v)
    var summaryJson: String                  // the last finished stream, see StreamSummary
        get() = text("summary")
        set(v) = putText("summary", v)
    var updateJson: String                   // {"version","url"} of the newest version SavahBot told us about
        get() = text("update")
        set(v) = putText("update", v)

    /** The normal panel size in dp, set by dragging its edges; and whether it is currently expanded to the big size. */
    var panelWdp: Int
        get() = oint("panelWdp", 270, 180, 700)
        set(v) = oput("panelWdp", v)
    var panelHdp: Int
        get() = oint("panelHdp", 300, 140, 900)
        set(v) = oput("panelHdp", v)
    var expanded: Boolean
        get() = prefs.getBoolean("expanded", false)
        set(v) { prefs.edit().putBoolean("expanded", v).apply() }

    /** Position of the panel as fractions of the free room on screen (per mille), so it keeps its place when the phone rotates. */
    var panelFx: Int
        get() = oint("panelFx", 500, 0, 1000)
        set(v) = oput("panelFx", v)
    /** True once the panel has any remembered place (portrait or landscape). */
    val panelPositionKnown: Boolean
        get() = prefs.contains("panelFx") || prefs.contains("L_panelFx")
    var panelPlaced: Boolean                 // the person has put the panel somewhere (or it was left there): reopen it exactly there, per orientation
        get() = oint("panelPlaced", 0, 0, 1) == 1
        set(v) = oput("panelPlaced", if (v) 1 else 0)
    var panelFy: Int
        get() = oint("panelFy", 300, 0, 1000)
        set(v) = oput("panelFy", v)

    var tabEdge: Int                         // Edge.ordinal: LEFT, RIGHT, TOP, BOTTOM
        get() = oint("tabEdge", Edge.RIGHT.ordinal, 0, 3)
        set(v) = oput("tabEdge", v)
    var tabAlong: Int                        // position along that edge, per mille
        get() = oint("tabAlong", 400, 0, 1000)
        set(v) = oput("tabAlong", v)

    /** Which window was showing when the overlay last ran: the panel (true) or the tucked-away tab (false). */
    var panelOpen: Boolean
        get() = prefs.getBoolean("panelOpen", true)
        set(v) { prefs.edit().putBoolean("panelOpen", v).apply() }

    private var watcher: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Calls [onChange] (on the main thread) whenever any choice above changes, wherever it was changed, so the open overlay follows the app's settings screen live. */
    fun watch(onChange: () -> Unit) {
        unwatch()
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        watcher = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    fun unwatch() {
        val l = watcher ?: return
        prefs.unregisterOnSharedPreferenceChangeListener(l)
        watcher = null
    }

    val isPaired: Boolean get() = server.isNotEmpty() && key() != null

    /** The device key, or null when there is none (or the Keystore could not open it, in which case the phone simply pairs again). */
    fun key(): String? {
        val sealed = prefs.getString("key", null) ?: return null
        return Vault.unseal(sealed)
    }

    /** Written in one go, and synchronously, so a pairing is never half saved. */
    fun savePairing(server: String, key: String, name: String, expires: Long) {
        prefs.edit()
            .putString("server", server)
            .putString("key", Vault.seal(key))
            .putString("name", name)
            .putLong("expires", expires)
            .putString("notice", "")
            .commit()
    }

    /** A renewed key from SavahBot. Synchronous on purpose: losing it would mean pairing again. */
    fun saveKey(key: String) {
        prefs.edit().putString("key", Vault.seal(key)).commit()
    }

    fun clearPairing(notice: String) {
        prefs.edit().remove("server").remove("key").remove("name").remove("expires").putString("notice", notice).commit()
    }

    private object Vault {
        private const val ALIAS = "savahlive_device_key"

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val existing = ks.getKey(ALIAS, null)
            if (existing is SecretKey) return existing
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }

        /** "v1:" + base64(iv + ciphertext). If the Keystore is unusable on some phone, the key is kept in the app's private storage as it is ("p:"). */
        fun seal(plain: String): String {
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                "v1:" + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
            } catch (e: Exception) {
                "p:$plain"
            }
        }

        fun unseal(sealed: String): String? {
            if (sealed.startsWith("p:")) return sealed.substring(2)
            if (!sealed.startsWith("v1:")) return null
            return try {
                val raw = Base64.decode(sealed.substring(3), Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
    }
}
