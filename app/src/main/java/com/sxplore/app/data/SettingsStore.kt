package com.sxplore.app.data

import android.content.Context
import kotlinx.serialization.json.Json

/** Non-sensitive app preferences (plain SharedPreferences). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("sxplore_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Migrate pre-provider settings (single whisper/translation endpoint)
        // into the provider scheme once.
        if (!prefs.contains("asr_provider")) {
            val oldKey = prefs.getString("whisper_api_key", "") ?: ""
            val oldBase = prefs.getString("whisper_base_url", "") ?: ""
            if (oldKey.isNotBlank()) {
                prefs.edit()
                    .putString("asr_provider", "custom")
                    .putString("asr_base_custom", oldBase)
                    .putString("asr_key_custom", oldKey)
                    .putString("asr_model_custom", prefs.getString("whisper_model", "") ?: "")
                    .apply()
            } else {
                prefs.edit().putString("asr_provider", "local").apply()
            }
        }
        if (!prefs.contains("trans_provider")) {
            val oldKey = (prefs.getString("translation_api_key", "") ?: "")
                .ifBlank { prefs.getString("whisper_api_key", "") ?: "" }
            val oldBase = (prefs.getString("translation_base_url", "") ?: "")
                .ifBlank { prefs.getString("whisper_base_url", "") ?: "" }
            if (oldKey.isNotBlank()) {
                prefs.edit()
                    .putString("trans_provider", "custom")
                    .putString("trans_base_custom", oldBase)
                    .putString("trans_key_custom", oldKey)
                    .putString("trans_model_custom", prefs.getString("translation_model", "") ?: "")
                    .apply()
            } else {
                prefs.edit().putString("trans_provider", "openai").apply()
            }
        }
    }

    // ---------- General ----------

    var showLiveSpeed: Boolean
        get() = prefs.getBoolean(KEY_LIVE_SPEED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LIVE_SPEED, value).apply()
        }

    var rememberTabs: Boolean
        get() = prefs.getBoolean("remember_tabs", true)
        set(value) {
            prefs.edit().putBoolean("remember_tabs", value).apply()
        }

    // ---------- Video / ASR ----------

    var aiSubtitlesEnabled: Boolean
        get() = prefs.getBoolean("ai_subs_enabled", false)
        set(value) {
            prefs.edit().putBoolean("ai_subs_enabled", value).apply()
        }

    /** Recognition language hint; blank = auto-detect. */
    var whisperLanguage: String
        get() = prefs.getString("whisper_language", "") ?: ""
        set(value) {
            prefs.edit().putString("whisper_language", value).apply()
        }

    /** ASR provider id: local / openai / siliconflow / groq / custom. */
    var asrProvider: String
        get() = prefs.getString("asr_provider", "local") ?: "local"
        set(value) {
            prefs.edit().putString("asr_provider", value).apply()
        }

    /** Selected local model: tiny / base / small. */
    var whisperLocalModel: String
        get() = prefs.getString("whisper_local_model", "base") ?: "base"
        set(value) {
            prefs.edit().putString("whisper_local_model", value).apply()
        }

    /** Model download mirror; hf-mirror.com is reachable from China. */
    var modelDownloadSource: String
        get() = prefs.getString("model_download_source", "https://hf-mirror.com") ?: "https://hf-mirror.com"
        set(value) {
            prefs.edit().putString("model_download_source", value).apply()
        }

    // ---------- Video / Translation ----------

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

    /** Translation provider id: openai / siliconflow / deepseek / custom. */
    var transProvider: String
        get() = prefs.getString("trans_provider", "openai") ?: "openai"
        set(value) {
            prefs.edit().putString("trans_provider", value).apply()
        }

    // ---------- Per-provider fields (scope = "asr" | "trans") ----------

    fun providerBase(scope: String, provider: String): String =
        prefs.getString("${scope}_base_$provider", "") ?: ""

    fun setProviderBase(scope: String, provider: String, value: String) {
        prefs.edit().putString("${scope}_base_$provider", value).apply()
    }

    fun providerKey(scope: String, provider: String): String =
        prefs.getString("${scope}_key_$provider", "") ?: ""

    fun setProviderKey(scope: String, provider: String, value: String) {
        prefs.edit().putString("${scope}_key_$provider", value).apply()
    }

    fun providerModel(scope: String, provider: String): String =
        prefs.getString("${scope}_model_$provider", "") ?: ""

    fun setProviderModel(scope: String, provider: String, value: String) {
        prefs.edit().putString("${scope}_model_$provider", value).apply()
    }

    /** Blank base URL falls back to the provider's default. */
    fun effectiveBase(scope: String, provider: String): String =
        providerBase(scope, provider).trim().ifBlank { Providers.byId(provider)?.defaultBase ?: "" }

    /** Blank model falls back to the provider's default. */
    fun effectiveModel(scope: String, provider: String): String =
        providerModel(scope, provider).trim().ifBlank { Providers.defaultModel(scope, provider) }

    // ---------- Developer ----------

    var debugMode: Boolean
        get() = prefs.getBoolean("debug_mode", false)
        set(value) {
            prefs.edit().putBoolean("debug_mode", value).apply()
        }

    /** Clear diagnostic logs when the app exits. */
    var cleanLogsOnClose: Boolean
        get() = prefs.getBoolean("clean_logs_on_close", false)
        set(value) {
            prefs.edit().putBoolean("clean_logs_on_close", value).apply()
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
