package com.nekoyaku.ss.ai

/** AI provider 快捷範本：選一下就填好 Base URL，省得手打。模型用「抓模型」自動帶。 */
data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val sampleModel: String,
)

object ProviderPresets {
    val ALL = listOf(
        // DeepSeek／OpenRouter 排前面：預付儲值制（花完就停、不會爆卡），分享給非技術朋友時的推薦路線
        ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-pro"),
        ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
        ProviderPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.5-flash"),
        ProviderPreset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        ProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        // Claude 官方 API：自動走原生 /v1/messages（含 prompt caching，見 AnthropicBridge）
        ProviderPreset("Claude 官方 API", "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
    )
}
