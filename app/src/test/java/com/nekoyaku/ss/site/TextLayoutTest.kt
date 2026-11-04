package com.nekoyaku.ss.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutTest {
    @Test
    fun gaugeLinesKeepTheirSpaces() {
        // 怪談 20화 的拉桿儀表（API 原文 &nbsp; 已 unescape 成空白）
        assertEquals("      ▲", TextSanitizer.preserveLayout("      ▲"))
        assertEquals("MIN   MAX", TextSanitizer.preserveLayout("MIN   MAX"))
        assertEquals(" ∥-----∥", TextSanitizer.preserveLayout(" ∥-----∥ "))
    }

    @Test
    fun normalParagraphsAreUntouched() {
        assertNull(TextSanitizer.preserveLayout("  노리는 것은… 레버."))
        assertNull(TextSanitizer.preserveLayout("……"))
        assertNull(TextSanitizer.preserveLayout("   "))
        assertFalse(TextSanitizer.isLayoutOnly("“꽉 잡으십시오.”"))
        assertTrue(TextSanitizer.isLayoutOnly("  ▲"))
    }
}
