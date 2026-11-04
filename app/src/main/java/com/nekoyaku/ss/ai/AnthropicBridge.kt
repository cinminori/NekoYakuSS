package com.nekoyaku.ss.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Claude 官方 API 原生通道（0.5.72）。
 *
 * Anthropic 的 OpenAI 相容端點（`/v1/chat/completions`）**不支援 prompt caching**，而且
 * 把本 App 固定送的 temperature／`thinking:disabled` 照轉過去——Opus 5.5／Sonnet 5.5 兩者都 400。
 * 所以 Base URL 指向 `api.anthropic.com` 時改走原生 `/v1/messages`：
 *
 * - 請求端：把既有 OpenAI 形狀的 body（ChatRequest／VisionChatRequest／DictChatRequest
 *   序列化結果）**在送出前轉換**——呼叫端、prompt、快取 key 全部不動，舊路徑零影響。
 * - 回應端：把 Messages 回應轉回 OpenAI 形狀（choices／usage），既有解析、空內容重試、
 *   usage 入帳照舊運作。
 *
 * 快取：system 最後一塊掛 `cache_control`。system＝翻譯規則＋整本名詞表（0.5.56 起放 system 尾端），
 * 同一本書不變 → 同書連續批次讀快取（約 0.1 倍輸入價）。前綴低於模型門檻（Haiku 4.5 是 4096 token）
 * 不會快取，也不報錯——看閱讀頁的「快取命中」數字就知道。
 */
internal object AnthropicBridge {
    const val API_VERSION = "2023-06-01"
    private const val DEFAULT_MAX_TOKENS = 4096

    private val json = Json { ignoreUnknownKeys = true }

    /** Base URL 指向 Claude 官方 API（只認官方 host；自架 proxy 照走 OpenAI 相容）。 */
    fun isNative(baseUrl: String): Boolean =
        Regex("""^https?://api\.anthropic\.com(/|$)""", RegexOption.IGNORE_CASE).containsMatchIn(baseUrl.trim())

    /** 原生通道的請求標頭（Messages 與 Models 共用）。 */
    fun headers(apiKey: String): Map<String, String> =
        mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION)

    fun messagesUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/messages"

    /**
     * temperature 只送給還收它的舊款模型；Opus 4.7 起、Sonnet 5 起、Fable／Mythos 送了就 400
     * （Sonnet 5.5 是「非預設值」400）。未知新款預設不送＝安全側。
     */
    internal fun acceptsTemperature(model: String): Boolean =
        Regex("""claude-(3|haiku-4|sonnet-4|opus-4-[0-6](\D|$))""").containsMatchIn(model)

    /** effort 參數：Haiku 4.5、Sonnet 4.5 與更舊的模型不收（送了 400）。 */
    internal fun acceptsEffort(model: String): Boolean {
        val m = model.trim()
        return !(m.contains("haiku") || m.startsWith("claude-3") ||
            Regex("""^claude-sonnet-4(-5)?(-\d{8})?$""").matches(m) ||
            Regex("""^claude-opus-4(-[01])?(-\d{8})?$""").matches(m))
    }

    /** OpenAI 形狀 body（已序列化）→ Messages API body。[cacheTtl]＝null 用預設 5 分鐘。 */
    fun toMessagesBody(openAiBody: String, cacheTtl: String? = null): String {
        val src = json.parseToJsonElement(openAiBody).jsonObject
        val model = src["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val maxTokens = src["max_tokens"]?.jsonPrimitive?.intOrNull
            ?: src["max_completion_tokens"]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_MAX_TOKENS
        val systemTexts = mutableListOf<String>()
        val turns = mutableListOf<Pair<String, JsonArray>>()
        src["messages"]?.jsonArray.orEmpty().forEach { el ->
            val m = el.jsonObject
            val role = m["role"]?.jsonPrimitive?.contentOrNull ?: "user"
            val content = m["content"] ?: return@forEach
            if (role == "system" || role == "developer") {
                textOf(content)?.let(systemTexts::add)
            } else {
                turns += (if (role == "assistant") "assistant" else "user") to contentBlocks(content)
            }
        }
        // 「關閉思考模式」對應：effort low（Opus 5.5 不能關思考，只能壓低；Haiku 4.5 本來就沒思考）
        val disableThinking = src["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "disabled" ||
            src["reasoning_effort"]?.jsonPrimitive?.contentOrNull == "none"
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (systemTexts.isNotEmpty()) {
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text")
                        put("text", systemTexts.joinToString("\n"))
                        putJsonObject("cache_control") {
                            put("type", "ephemeral")
                            // 批次（0.5.73）用 1 小時：批內並行處理，5 分鐘快取常常等不到
                            cacheTtl?.let { put("ttl", it) }
                        }
                    }
                }
            }
            putJsonArray("messages") {
                turns.forEach { (role, blocks) ->
                    addJsonObject {
                        put("role", role)
                        put("content", blocks)
                    }
                }
            }
            if (acceptsTemperature(model)) src["temperature"]?.let { put("temperature", it) }
            if (disableThinking && acceptsEffort(model)) putJsonObject("output_config") { put("effort", "low") }
        }
        return body.toString()
    }

    /**
     * Messages 回應 → OpenAI 形狀（choices[0].message.content／finish_reason／usage）。
     * 只取 text 區塊（thinking 區塊不給呼叫端）。拒答（stop_reason=refusal）直接拋錯，
     * 不當空內容去加預算重試。
     */
    fun toOpenAiResponse(messagesResponse: String): String {
        val src = json.parseToJsonElement(messagesResponse).jsonObject
        val text = src["content"]?.jsonArray.orEmpty()
            .mapNotNull { b ->
                val o = b.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull == "text") o["text"]?.jsonPrimitive?.contentOrNull else null
            }
            .joinToString("")
        val stop = src["stop_reason"]?.jsonPrimitive?.contentOrNull
        if (stop == "refusal" && text.isBlank()) {
            val category = src["stop_details"]?.let { it as? JsonObject }?.get("category")?.jsonPrimitive?.contentOrNull
            error("Claude 拒絕回應這段內容（refusal${category?.let { "：$it" } ?: ""}）——可改用其他模型翻這段")
        }
        val usage = src["usage"]?.jsonObject
        fun u(key: String) = usage?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
        val cacheRead = u("cache_read_input_tokens")
        val prompt = u("input_tokens") + cacheRead + u("cache_creation_input_tokens")
        val completion = u("output_tokens")
        return buildJsonObject {
            putJsonArray("choices") {
                addJsonObject {
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", text)
                    }
                    put("finish_reason", finishReason(stop))
                }
            }
            if (usage != null) {
                putJsonObject("usage") {
                    put("prompt_tokens", prompt)
                    put("completion_tokens", completion)
                    put("total_tokens", prompt + completion)
                    putJsonObject("prompt_tokens_details") { put("cached_tokens", cacheRead) }
                }
            }
        }.toString()
    }

    private fun finishReason(stop: String?): String? = when (stop) {
        null -> null
        "end_turn", "stop_sequence" -> "stop"
        "max_tokens" -> "length"
        else -> stop
    }

    /** system 訊息的 content 可能是字串或 parts 陣列 → 純文字。 */
    private fun textOf(content: JsonElement): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n").ifEmpty { null }
        else -> null
    }

    /** user/assistant content（字串或 OpenAI parts）→ Messages 區塊陣列；data URL 圖片轉 base64 image。 */
    private fun contentBlocks(content: JsonElement): JsonArray = buildJsonArray {
        when (content) {
            is JsonPrimitive -> addJsonObject {
                put("type", "text")
                put("text", content.contentOrNull.orEmpty())
            }
            is JsonArray -> content.forEach { part ->
                val p = part.jsonObject
                when (p["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> addJsonObject {
                        put("type", "text")
                        put("text", p["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                    "image_url" -> imageBlock(p["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull)
                        ?.let { add(it) }
                }
            }
            else -> {}
        }
    }

    private fun imageBlock(url: String?): JsonObject? {
        url ?: return null
        val m = Regex("""^data:([^;,]+);base64,(.*)$""", RegexOption.DOT_MATCHES_ALL).find(url)
        return buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                if (m != null) {
                    put("type", "base64")
                    put("media_type", m.groupValues[1])
                    put("data", m.groupValues[2])
                } else {
                    put("type", "url")
                    put("url", url)
                }
            }
        }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
