package com.nekoyaku.ss.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AI provider 設定（Profile Pool）：多組 baseUrl/apiKey/model，可指向 OpenAI 相容端點
 * （DeepSeek／OpenRouter／Gemini／Groq／OpenAI…）或 Claude 官方 API（自動走原生通道，見 [AnthropicBridge]）。
 * API key 只存在本機 SharedPreferences（allowBackup=false），不會上傳到任何地方。
 */
@Serializable
data class AiProfile(
    val id: String,
    val name: String,
    val baseUrl: String,   // 需含版本路徑，例：https://api.groq.com/openai/v1
    val apiKey: String,
    val model: String,
    /**
     * 關閉思考模式（0.5.17；0.5.54 起依供應商分流）：DeepSeek 式請求加頂層
     * `"thinking":{"type":"disabled"}`；**Groq 端點改送 `"reasoning_effort":"none"`**
     * （Groq 不認 thinking 物件；qwen3 系預設 thinking，`<think>` 會吃光 max_tokens——
     * 2026-07-19 評比實錄）。
     * DeepSeek V4 **pro 與 flash 都預設開啟**思考——換 flash 並不會變快（實案：單批推理
     * 26953 字、整章 4~8 分鐘、還常把 max_tokens 吃光回空內容）。翻譯有名詞表與規則約束，
     * 不需要 chain-of-thought，關掉即大幅提速。
     * 預設 false＝完全不送這些欄位，其他供應商行為不變（舊 profile 反序列化也吃這個預設值）。
     */
    val disableThinking: Boolean = false,
) {
    /** Groq 端點：`thinking` 物件不認得、關思考要走 OpenAI 式 `reasoning_effort`（見 [disableThinking]）。 */
    val isGroqEndpoint: Boolean get() = baseUrl.contains("groq.com", ignoreCase = true)
}

/** 用 SharedPreferences 持久化 profile 清單與目前選擇；以 StateFlow 對 Compose 反應式暴露。 */
class AiProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<AiProfile>> = _profiles.asStateFlow()

    private val _selectedId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    val selected: AiProfile?
        get() = _profiles.value.firstOrNull { it.id == _selectedId.value }
            ?: _profiles.value.firstOrNull()

    private fun load(): List<AiProfile> =
        prefs.getString(KEY_LIST, null)?.let {
            runCatching { json.decodeFromString<List<AiProfile>>(it) }.getOrNull()
        } ?: emptyList()

    private fun persist() {
        prefs.edit().putString(KEY_LIST, json.encodeToString(_profiles.value)).apply()
    }

    fun upsert(profile: AiProfile) {
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        _profiles.value = list
        persist()
        if (_selectedId.value == null) select(profile.id)
    }

    fun remove(id: String) {
        _profiles.value = _profiles.value.filterNot { it.id == id }
        persist()
        if (_selectedId.value == id) select(_profiles.value.firstOrNull()?.id)
    }

    fun select(id: String?) {
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_SELECTED = "selected"
    }
}
