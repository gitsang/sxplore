package com.sxplore.app.data

import android.content.Context
import kotlinx.serialization.json.Json

/** Non-sensitive app preferences (plain SharedPreferences). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("sxplore_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var showLiveSpeed: Boolean
        get() = prefs.getBoolean(KEY_LIVE_SPEED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LIVE_SPEED, value).apply()
        }

    /** Remember open tabs and paths across app restarts. */
    var rememberTabs: Boolean
        get() = prefs.getBoolean("remember_tabs", true)
        set(value) {
            prefs.edit().putBoolean("remember_tabs", value).apply()
        }

    // ---------- AI live captions (Whisper via OpenAI-compatible API) ----------

    var aiSubtitlesEnabled: Boolean
        get() = prefs.getBoolean("ai_subs_enabled", false)
        set(value) {
            prefs.edit().putBoolean("ai_subs_enabled", value).apply()
        }

    var whisperBaseUrl: String
        get() = prefs.getString("whisper_base_url", "https://api.openai.com") ?: ""
        set(value) {
            prefs.edit().putString("whisper_base_url", value).apply()
        }

    var whisperApiKey: String
        get() = prefs.getString("whisper_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("whisper_api_key", value).apply()
        }

    var whisperModel: String
        get() = prefs.getString("whisper_model", "whisper-1") ?: "whisper-1"
        set(value) {
            prefs.edit().putString("whisper_model", value).apply()
        }

    /** Recognition language hint; blank = auto-detect. */
    var whisperLanguage: String
        get() = prefs.getString("whisper_language", "") ?: ""
        set(value) {
            prefs.edit().putString("whisper_language", value).apply()
        }

    // ---------- Translation (OpenAI-compatible chat API) ----------

    var translationEnabled: Boolean
        get() = prefs.getBoolean("translation_enabled", true)
        set(value) {
            prefs.edit().putBoolean("translation_enabled", value).apply()
        }

    var translationTargetLang: String
        get() = prefs.getString("translation_target_lang", "Chinese") ?: "Chinese"
        set(value) {
            prefs.edit().putString("translation_target_lang", value).apply()
        }

    /** Blank = reuse the Whisper base URL / key. */
    var translationBaseUrl: String
        get() = prefs.getString("translation_base_url", "") ?: ""
        set(value) {
            prefs.edit().putString("translation_base_url", value).apply()
        }

    var translationApiKey: String
        get() = prefs.getString("translation_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("translation_api_key", value).apply()
        }

    var translationModel: String
        get() = prefs.getString("translation_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) {
            prefs.edit().putString("translation_model", value).apply()
        }

    // ---------- Tab persistence ----------

    fun saveTabs(state: SavedTabs) {
        prefs.edit().putString("saved_tabs", json.encodeToString(SavedTabs.serializer(), state)).apply()
    }

    fun loadTabs(): SavedTabs? = runCatching {
        val raw = prefs.getString("saved_tabs", null) ?: return null
        json.decodeFromString(SavedTabs.serializer(), raw)
    }.getOrNull()

    /** Last-used open method per media kind (video/audio/image/other). true = built-in. */
    fun openInternally(kind: String): Boolean = prefs.getBoolean("open_mode_$kind", true)

    fun setOpenMode(kind: String, internal: Boolean) {
        prefs.edit().putBoolean("open_mode_$kind", internal).apply()
    }

    companion object {
        private const val KEY_LIVE_SPEED = "show_live_speed"
    }
}
