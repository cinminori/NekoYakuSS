package com.nekoyaku.ss.ai

/**
 * 翻譯服務範本：選一下就填好 Base URL 與預設模型。
 * [keyUrl]＝申請 API key 的頁面；[disableThinking]＝預設關思考（見 [AiProfile.disableThinking]）。
 */
data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val sampleModel: String,
    val keyUrl: String,
    val note: String,
    val disableThinking: Boolean = false,
)

object ProviderPresets {
    val ALL = listOf(
        // DeepSeek／OpenRouter 排前面：預付儲值制（花完就停），分享給非技術朋友時的推薦路線
        ProviderPreset(
            "DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-pro",
            "https://platform.deepseek.com/api_keys",
            "預付儲值，用完就停。",
            disableThinking = true,
        ),
        ProviderPreset(
            "OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat",
            "https://openrouter.ai/settings/keys",
            "一組 key 可以用很多家的模型，預付儲值。",
        ),
        ProviderPreset(
            "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash",
            "https://aistudio.google.com/apikey",
            "Google 的服務，有免費額度（免費方案的資料條款可能不同）。",
        ),
        ProviderPreset(
            "OpenAI", "https://api.openai.com/v1", "gpt-6-luna",
            "https://platform.openai.com/api-keys",
            "API 與 ChatGPT 訂閱分開計費，有訂閱也要另外儲值。",
        ),
        // Claude 官方 API：自動走原生 /v1/messages（含 prompt caching，見 AnthropicBridge）
        ProviderPreset(
            "Claude 官方 API", "https://api.anthropic.com/v1", "claude-sonnet-5-5",
            "https://platform.claude.com/settings/keys",
            "API 與 Claude 訂閱分開計費，有訂閱也要另外儲值。",
        ),
    )

    fun matching(baseUrl: String): ProviderPreset? =
        ALL.firstOrNull { it.baseUrl.trimEnd('/').equals(baseUrl.trim().trimEnd('/'), ignoreCase = true) }
}
