package com.nekoyaku.ss.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnthropicBridgeTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun onlyOfficialHostIsNative() {
        assertTrue(AnthropicBridge.isNative("https://api.anthropic.com/v1"))
        assertTrue(AnthropicBridge.isNative("https://API.anthropic.com/v1/"))
        assertFalse(AnthropicBridge.isNative("http://192.0.2.10:8080/v1"))
        assertFalse(AnthropicBridge.isNative("https://api.anthropic.com.evil.example/v1"))
        assertFalse(AnthropicBridge.isNative("https://openrouter.ai/api/v1"))
    }

    @Test
    fun translationRequestMapsSystemCacheAndDropsUnsupportedParams() {
        val body = obj(
            AnthropicBridge.toMessagesBody(
                """{"model":"claude-opus-5-5","messages":[{"role":"system","content":"規則"},""" +
                    """{"role":"user","content":"{\"paragraphs\":[]}"}],"temperature":0.3,"max_tokens":5400,""" +
                    """"thinking":{"type":"disabled"},"stream":false}""",
            ),
        )
        assertEquals(5400, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val sys = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("規則", sys["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", sys["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val msgs = body["messages"]!!.jsonArray
        assertEquals(1, msgs.size)
        assertEquals("user", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        // Opus 5.5：temperature／thinking:disabled 都會 400 → 不送；關思考改 effort low
        assertNull(body["temperature"])
        assertNull(body["thinking"])
        assertNull(body["stream"])
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun olderModelsKeepTemperatureAndHaikuGetsNoEffort() {
        val body = obj(
            AnthropicBridge.toMessagesBody(
                """{"model":"claude-haiku-4-5","messages":[{"role":"user","content":"hi"}],""" +
                    """"temperature":0.1,"max_completion_tokens":4096,"thinking":{"type":"disabled"}}""",
            ),
        )
        assertEquals("0.1", body["temperature"]!!.jsonPrimitive.content)
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(body["output_config"])
        assertNull(body["system"])
    }

    @Test
    fun modelCapabilityTables() {
        assertTrue(AnthropicBridge.acceptsTemperature("claude-sonnet-4-6"))
        assertTrue(AnthropicBridge.acceptsTemperature("claude-opus-4-6"))
        assertFalse(AnthropicBridge.acceptsTemperature("claude-opus-4-7"))
        assertFalse(AnthropicBridge.acceptsTemperature("claude-sonnet-5-5"))
        assertFalse(AnthropicBridge.acceptsTemperature("claude-fable-5-1"))
        assertTrue(AnthropicBridge.acceptsEffort("claude-opus-4-7"))
        assertTrue(AnthropicBridge.acceptsEffort("claude-opus-4-5"))
        assertTrue(AnthropicBridge.acceptsEffort("claude-sonnet-4-6"))
        assertFalse(AnthropicBridge.acceptsEffort("claude-sonnet-4-5"))
        assertFalse(AnthropicBridge.acceptsEffort("claude-opus-4-1"))
        assertFalse(AnthropicBridge.acceptsEffort("claude-haiku-4-5"))
    }

    @Test
    fun visionDataUrlBecomesBase64Image() {
        val body = obj(
            AnthropicBridge.toMessagesBody(
                """{"model":"claude-sonnet-5-5","messages":[{"role":"user","content":[""" +
                    """{"type":"text","text":"讀圖"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,QUJD"}}]}]}""",
            ),
        )
        val blocks = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        val src = blocks[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", src["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", src["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", src["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun responseMapsTextFinishReasonAndCacheUsage() {
        val out = obj(
            AnthropicBridge.toOpenAiResponse(
                """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"[{\"id\":0}]"}],""" +
                    """"stop_reason":"end_turn","usage":{"input_tokens":100,"output_tokens":50,""" +
                    """"cache_read_input_tokens":3000,"cache_creation_input_tokens":0}}""",
            ),
        )
        val choice = out["choices"]!!.jsonArray[0].jsonObject
        assertEquals("[{\"id\":0}]", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        val usage = out["usage"]!!.jsonObject
        assertEquals(3100L, usage["prompt_tokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(50L, usage["completion_tokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(3000L, usage["prompt_tokens_details"]!!.jsonObject["cached_tokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun maxTokensMapsToLengthSoEmptyContentRetryKicksIn() {
        val out = obj(
            AnthropicBridge.toOpenAiResponse(
                """{"content":[{"type":"thinking","thinking":""}],"stop_reason":"max_tokens","usage":{"input_tokens":1,"output_tokens":4096}}""",
            ),
        )
        val choice = out["choices"]!!.jsonArray[0].jsonObject
        assertEquals("length", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals("", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusalThrowsReadableError() {
        try {
            AnthropicBridge.toOpenAiResponse(
                """{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"}}""",
            )
            fail("應拋錯")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("refusal：cyber"))
        }
    }
}
