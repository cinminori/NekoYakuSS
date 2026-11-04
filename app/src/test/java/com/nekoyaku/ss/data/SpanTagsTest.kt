package com.nekoyaku.ss.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpanTagsTest {

    @Test
    fun `合法標籤回傳拆標籤後的範圍`() {
        val s = "他做出了<m1>人類不會做的行為</m1>，<m2>絕對</m2>不行"
        val scan = SpanTags.scan(s, 2)
        assertEquals(4, scan.tagRanges.size)
        val plain = SpanTags.strip(s)
        assertEquals("他做出了人類不會做的行為，絕對不行", plain)
        val st = scan.styled!!
        assertEquals("人類不會做的行為", plain.substring(st[0].start, st[0].end))
        assertEquals("絕對", plain.substring(st[1].start, st[1].end))
    }

    @Test
    fun `模型把順序對調也算合法`() {
        // 中韓語序不同，譯文裡 m2 跑到 m1 前面是正常的
        val scan = SpanTags.scan("<m2>B</m2> 和 <m1>A</m1>", 2)
        assertEquals(listOf(2, 1), scan.styled!!.map { it.n })
    }

    @Test
    fun `數量不符、改號、半邊、交錯都不合法`() {
        assertNull(SpanTags.scan("<m1>A</m1>", 2).styled) // 少一組
        assertNull(SpanTags.scan("<m1>A</m1><m3>B</m3>", 2).styled) // 改號
        assertNull(SpanTags.scan("<m1>A<m2>B</m2>", 2).styled) // 只剩半邊
        assertNull(SpanTags.scan("<m1>A<m2>B</m1>C</m2>", 2).styled) // 交錯
        assertNull(SpanTags.scan("<m1>A</m1><m1>B</m1>", 1).styled) // 重複
        assertNull(SpanTags.scan("純文字", 1).styled) // 標籤整組被吃掉
        assertNull(SpanTags.scan("<m1>A</m1>", 0).styled) // 舊段落沒有樣式可對
    }

    @Test
    fun `沒有標籤也不期待標籤時合法`() {
        assertEquals(emptyList<SpanTags.Styled>(), SpanTags.scan("一般段落", 0).styled)
    }
}
