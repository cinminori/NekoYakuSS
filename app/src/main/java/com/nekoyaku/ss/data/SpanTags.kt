package com.nekoyaku.ss.data

/**
 * 行內樣式標籤 `<mN>…</mN>` 的掃描／拆除（0.5.91）。純函式、可測。
 *
 * 原文的標籤是解析時包的，一定合法；譯文的標籤是模型照規則 15 包回來的，可能少一組、
 * 編號改掉、只剩半邊——那種一律當作沒有標籤（整段拆掉、顯示純文字），不猜位置。
 */
object SpanTags {
    private val TAG_RE = Regex("""</?m(\d{1,2})>""")

    fun open(n: Int) = "<m$n>"
    fun close(n: Int) = "</m$n>"

    fun hasTags(s: String): Boolean = s.contains("<m") || s.contains("</m")

    /** 拆掉所有標籤（匯出、送名詞抽取、查字等不需要樣式的地方用）。 */
    fun strip(s: String): String = if (hasTags(s)) s.replace(TAG_RE, "") else s

    /** [n] 號標籤包住的範圍，座標是**拆掉標籤後**的文字。 */
    data class Styled(val n: Int, val start: Int, val end: Int)

    /**
     * @param tagRanges 每個標籤字串在 [s] 中的位置（呈現層照這個把標籤剪掉）。
     * @param styled 合法時＝各號標籤包住的範圍；不合法＝null（呼叫端只拆標籤、不上樣式）。
     */
    class Scan(val tagRanges: List<IntRange>, val styled: List<Styled>?)

    /**
     * 合法＝1..[expected] 每號恰好一組、先開後關、巢狀正確，且沒有其他編號。
     * [expected] 為 0 時任何標籤都不合法（舊快取的段落沒有標籤樣式可對）。
     */
    fun scan(s: String, expected: Int): Scan {
        if (!hasTags(s)) return Scan(emptyList(), if (expected == 0) emptyList() else null)
        val matches = TAG_RE.findAll(s).toList()
        val ranges = matches.map { it.range }
        var ok = true
        val stack = ArrayDeque<Pair<Int, Int>>() // 號碼, 開始位置（拆標籤後座標）
        val seen = mutableSetOf<Int>()
        val styled = mutableListOf<Styled>()
        var removed = 0
        for (m in matches) {
            val n = m.groupValues[1].toInt()
            val at = m.range.first - removed
            removed += m.value.length
            if (!ok) continue
            if (n !in 1..expected) { ok = false; continue }
            if (m.value.startsWith("</")) {
                val top = stack.removeLastOrNull()
                if (top == null || top.first != n) { ok = false; continue }
                styled.add(Styled(n, top.second, at))
            } else {
                if (!seen.add(n)) { ok = false; continue }
                stack.addLast(n to at)
            }
        }
        if (stack.isNotEmpty() || seen.size != expected) ok = false
        return Scan(ranges, if (ok) styled.sortedBy { it.start } else null)
    }
}
