package com.nekoyaku.ss.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.2.6：OpenAI／Gemini 對選配欄位的 400（2026-11-07 實測錯誤訊息）要能拿掉重試。 */
class AdaptiveParamsTest {
    private val body = """{"model":"gpt-6-luna","messages":[],"temperature":0.2,"reasoning_effort":"none"}"""

    @Test
    fun lunaTemperatureRejectionIsDetected() {
        val err = "翻譯 API 失敗 HTTP 400：{\"error\":{\"message\":\"Unsupported value: 'temperature' does not support 0.2 with this model. Only the default (1) value is supported.\"}}"
        assertEquals("temperature", rejectedOptionalParam(err, body))
    }

    @Test
    fun unrecognizedReasoningEffortIsDetected() {
        val err = "翻譯 API 失敗 HTTP 400：{\"error\":{\"message\":\"Unrecognized request argument supplied: reasoning_effort\"}}"
        assertEquals("reasoning_effort", rejectedOptionalParam(err, body))
    }

    @Test
    fun paramNotSentIsNotBlamed() {
        val err = "翻譯 API 失敗 HTTP 400：Unknown parameter: 'thinking'."
        assertNull(rejectedOptionalParam(err, body))
    }

    @Test
    fun non400IsIgnored() {
        assertNull(rejectedOptionalParam("翻譯 API 失敗 HTTP 429：temperature", body))
    }

    @Test
    fun withoutParamsRemovesKeys() {
        assertEquals(
            """{"model":"gpt-6-luna","messages":[],"reasoning_effort":"none"}""",
            withoutParams(body, setOf("temperature")),
        )
    }

    @Test
    fun reasoningEffortOnlyForKnownEndpoints() {
        fun p(url: String) = AiProfile("x", "x", url, "k", "m", disableThinking = true)
        assertEquals(true, p("https://api.openai.com/v1").usesReasoningEffort)
        assertEquals(true, p("https://generativelanguage.googleapis.com/v1beta/openai").usesReasoningEffort)
        assertEquals(true, p("https://api.groq.com/openai/v1").usesReasoningEffort)
        assertEquals(false, p("https://api.deepseek.com/v1").usesReasoningEffort)
    }
}
