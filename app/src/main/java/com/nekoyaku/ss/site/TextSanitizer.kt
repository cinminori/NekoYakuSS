package com.nekoyaku.ss.site

/**
 * 文字淨化。重點是過濾 **zero-width 浮水印**：ridi 會在內文植入每個讀者不同的隱形指紋
 * （zero-width 字元），不濾掉會 ①污染翻譯輸入 ②等於把帳號指紋送給第三方 LLM。
 */
object TextSanitizer {

    // zero-width / 方向控制 / BOM / soft hyphen 等隱形字元
    private val INVISIBLE = Regex(
        "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u206F\\uFEFF\\u00AD\\u061C]"
    )

    /** 移除隱形字元並正規化空白。 */
    fun clean(text: String): String =
        text.replace(INVISIBLE, "")
            .replace(" ", " ")
            .trim()

    // 需要翻譯的文字：韓文音節／자모、日文假名、漢字
    private val SOURCE_LETTERS = Regex("[\\uAC00-\\uD7A3\\u3131-\\u318E\\u3040-\\u30FF\\u4E00-\\u9FFF]")
    // 用空白排位置的痕跡：開頭有空白，或中間連續兩格以上
    private val LAYOUT_SPACES = Regex("^[ \\u00A0\\u3000]|[ \\u00A0\\u3000]{2,}")

    /**
     * 空白排版段落（0.5.81）：沒有要翻的字、靠空白排位置——怪談 20화 的拉桿儀表
     * 「MIN␣␣…␣␣MAX／∥-----∥／␣␣␣▲」，▲ 前面幾格空白就是拉桿拉到哪。
     * 這種段落譯文＝原文（不送翻譯），呈現時保留空白。
     */
    fun isLayoutOnly(text: String): Boolean =
        text.isNotBlank() && !SOURCE_LETTERS.containsMatchIn(text) && LAYOUT_SPACES.containsMatchIn(text)

    /**
     * 空白排版段落保留開頭與連續空白（全轉 U+00A0，不被裁掉也不被斷行吃掉）；不是這種段落回 null，
     * 呼叫端照舊 [clean]＋trim。只動「沒有要翻的字」的段落——一般段落的文字（＝譯文快取 hash）不變。
     */
    fun preserveLayout(raw: String): String? {
        val t = raw.replace(INVISIBLE, "").trimEnd()
        if (!isLayoutOnly(t)) return null
        return t.replace(' ', '\u00A0')
    }

    /** 清理後若仍為空（例如整段都是浮水印）回傳 true。 */
    fun isBlank(text: String): Boolean = clean(text).isEmpty()

    // <rt>（振假名注音）與 <rp>（注音括號 fallback）整個節點。粗暴剝標籤前必須先移除——
    // 否則 <ruby>漢字<rt>かんじ</rt></ruby> 剝完變「漢字かんじ」，注音混進正文毒到翻譯。
    private val RUBY_ANNOTATION = Regex(
        """<r[tp]\b[^>]*>.*?</r[tp]>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 移除 ruby 注音節點（<rt>/<rp> 含內容），保留 ruby base 文字。日文 EPUB 抽文用。 */
    fun stripRuby(html: String): String = html.replace(RUBY_ANNOTATION, "")
}
