package com.nekoyaku.ss.ai

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 一輪翻譯的 token 消耗累計（0.5.55）。
 *
 * OpenAI 相容回應本來就附帶 `usage` 欄位（prompt_tokens/completion_tokens/total_tokens）——
 * 讀它**零成本**：零額外請求、零額外 token，而且是計費端的真實數字（thinking 模型的推理
 * token 也如實計入，比任何本地 tokenizer 估算都準）。
 *
 * thread-safe：整章批次是 async 併發、多批同時 [add]，計數器全用 Atomic。
 * 供應商沒回 usage 的請求不會 add（[reportedRequests] 為 0 ＝「供應商未回報」，
 * 顯示端據此略過，不顯示誤導性的 0）。
 */
class UsageTally {
    private val prompt = AtomicLong(0)
    private val completion = AtomicLong(0)
    private val count = AtomicInteger(0)
    private val cached = AtomicLong(0)

    fun add(promptTokens: Long, completionTokens: Long, cachedTokens: Long = 0) {
        if (promptTokens <= 0 && completionTokens <= 0) return
        prompt.addAndGet(promptTokens.coerceAtLeast(0))
        completion.addAndGet(completionTokens.coerceAtLeast(0))
        cached.addAndGet(cachedTokens.coerceIn(0, promptTokens.coerceAtLeast(0)))
        count.incrementAndGet()
    }

    /** prompt 中命中供應商前綴快取的 token 數（0.5.57）；供應商不回報＝0。 */
    val cachedTokens: Long get() = cached.get()

    /** 快取命中率百分比（整數）；prompt 為 0 時 0。 */
    val cacheHitPercent: Int get() = prompt.get().let { p -> if (p <= 0) 0 else (cached.get() * 100 / p).toInt() }

    val promptTokens: Long get() = prompt.get()
    val completionTokens: Long get() = completion.get()
    val totalTokens: Long get() = prompt.get() + completion.get()

    /** 有回報 usage 的請求數；0＝供應商未回報（如部分自架 proxy）。 */
    val reportedRequests: Int get() = count.get()

    companion object {
        /** token 數的顯示格式：1234→「1.2K」、48_200→「48.2K」、1_234_567→「1.23M」；<1000 原樣。 */
        fun format(n: Long): String = when {
            n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
            n >= 1_000 -> "%.1fK".format(n / 1_000.0)
            else -> n.toString()
        }
    }
}
