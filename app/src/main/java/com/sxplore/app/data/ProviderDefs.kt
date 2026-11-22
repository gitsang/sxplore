package com.sxplore.app.data

/** OpenAI-compatible provider presets. Blank user fields fall back to these defaults. */
data class ProviderDef(
    val id: String,
    val label: String,
    val defaultBase: String,
    val defaultAsrModel: String = "",
    val defaultTransModel: String = "",
)

object Providers {
    val LOCAL = ProviderDef("local", "Local (on-device Whisper)", "")
    val OPENAI = ProviderDef("openai", "OpenAI", "https://api.openai.com/v1", "whisper-1", "gpt-4o-mini")
    val SILICONFLOW = ProviderDef(
        "siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1",
        "FunAudioLLM/SenseVoiceSmall", "Qwen/Qwen2.5-7B-Instruct",
    )
    val GROQ = ProviderDef(
        "groq", "Groq", "https://api.groq.com/openai/v1",
        "whisper-large-v3", "llama-3.3-70b-versatile",
    )
    val DEEPSEEK = ProviderDef("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "", "deepseek-chat")
    val CUSTOM = ProviderDef("custom", "Custom (OpenAI-compatible)", "")

    val asr = listOf(LOCAL, OPENAI, SILICONFLOW, GROQ, CUSTOM)
    val translation = listOf(OPENAI, SILICONFLOW, DEEPSEEK, CUSTOM)

    fun byId(id: String): ProviderDef? = (asr + translation).firstOrNull { it.id == id }

    fun defaultModel(scope: String, id: String): String {
        val p = byId(id) ?: return ""
        return if (scope == "asr") p.defaultAsrModel else p.defaultTransModel
    }
}
