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

    companion object {
        private const val KEY_LIVE_SPEED = "show_live_speed"
    }
}
