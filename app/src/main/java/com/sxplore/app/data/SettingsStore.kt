package com.sxplore.app.data

import android.content.Context

/** Non-sensitive app preferences (plain SharedPreferences). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("sxplore_settings", Context.MODE_PRIVATE)

    var showLiveSpeed: Boolean
        get() = prefs.getBoolean(KEY_LIVE_SPEED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LIVE_SPEED, value).apply()
        }

    /** Last-used open method per media kind (video/audio/image/other). true = built-in. */
    fun openInternally(kind: String): Boolean = prefs.getBoolean("open_mode_$kind", true)

    fun setOpenMode(kind: String, internal: Boolean) {
        prefs.edit().putBoolean("open_mode_$kind", internal).apply()
    }

    companion object {
        private const val KEY_LIVE_SPEED = "show_live_speed"
    }
}
