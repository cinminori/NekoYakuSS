package com.nekoyaku.ss.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTallyTest {
    @Test
    fun add_accumulatesAndCounts() {
        val t = UsageTally()
        t.add(1000, 200)
        t.add(2500, 300)
        assertEquals(3500L, t.promptTokens)
        assertEquals(500L, t.completionTokens)
        assertEquals(4000L, t.totalTokens)
        assertEquals(2, t.reportedRequests)
    }

    /** 供應商沒回 usage（兩邊都 0）不計請求數——顯示端靠 reportedRequests==0 判「未回報」。 */
    @Test
    fun add_ignoresEmptyUsage() {
        val t = UsageTally()
        t.add(0, 0)
        assertEquals(0, t.reportedRequests)
        assertEquals(0L, t.totalTokens)
        // 單邊為 0 仍是有效回報（如純 prompt 計費）
        t.add(100, 0)
        assertEquals(1, t.reportedRequests)
    }

    /** 異常負值（供應商 bug）不讓總帳倒扣。 */
    @Test
    fun add_clampsNegative() {
        val t = UsageTally()
        t.add(-50, 300)
        assertEquals(0L, t.promptTokens)
        assertEquals(300L, t.completionTokens)
    }

    @Test
    fun format_scalesReadably() {
        assertEquals("999", UsageTally.format(999))
        assertEquals("1.2K", UsageTally.format(1_234))
        assertEquals("48.2K", UsageTally.format(48_200))
        assertEquals("1.23M", UsageTally.format(1_234_567))
        assertEquals("0", UsageTally.format(0))
    }
}
