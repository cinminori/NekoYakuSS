package com.nekoyaku.ss.data.db

import com.nekoyaku.ss.site.TextSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 段落譯文快取＋書架的存取封裝。 */
class CacheRepository(private val dao: NekoDao) {

    // ── 段落譯文快取 ──

    /** 譯文快取 key。hash 用 `String.hashCode()`。 */
    fun keyFor(siteId: String, bookKey: String, chapterKey: String, original: String): String =
        prefix(siteId, bookKey, chapterKey) + original.hashCode()

    private fun prefix(siteId: String, bookKey: String, chapterKey: String) = "$siteId|$bookKey|$chapterKey|"

    /**
     * 查快取：回傳「有譯文的段落原文 → 譯文」。沒有要翻的字的空白排版段（例如用空白排位置的儀表），
     * 譯文＝原文，不送翻譯。
     */
    suspend fun lookup(siteId: String, bookKey: String, chapterKey: String, originals: Collection<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (originals.isEmpty()) return@withContext emptyMap()
            val byKey = originals.associateBy { keyFor(siteId, bookKey, chapterKey, it) }
            val found = byKey.keys.chunked(SQL_CHUNK).flatMap { dao.getTranslations(it) }
            buildMap {
                found.forEach { e -> byKey[e.key]?.let { put(it, e.text) } }
                originals.forEach { o -> if (o !in this && TextSanitizer.isLayoutOnly(o)) put(o, o) }
            }
        }

    suspend fun save(siteId: String, bookKey: String, chapterKey: String, pairs: Map<String, String>) =
        withContext(Dispatchers.IO) {
            if (pairs.isEmpty()) return@withContext
            dao.putTranslations(pairs.map { (o, t) -> TranslationEntity(keyFor(siteId, bookKey, chapterKey, o), t) })
        }

    /**
     * 插圖文字的快取 key：同一張圖（網址或圖檔內容）＝同一筆。放在同一張 translation 表，
     * 「重新翻譯本話」清整話時一併清掉；`img@` 開頭不會跟段落 hash（整數）撞到。
     */
    fun imageKey(siteId: String, bookKey: String, chapterKey: String, imageId: String): String =
        prefix(siteId, bookKey, chapterKey) + "img@" + imageId.hashCode() + "." + imageId.length

    /**
     * 單處覆寫的快取 key：這一話第 [occurrence] 次出現的 [original]（長按譯文手動改或重翻）。
     * 放在同一張 translation 表，「重新翻譯本話」、譯文取代、書籍合併都會一併處理；`ov@` 不會跟段落 hash 撞到。
     */
    fun overrideKey(siteId: String, bookKey: String, chapterKey: String, original: String, occurrence: Int): String =
        overridePrefix(siteId, bookKey, chapterKey) + original.hashCode() + "#" + occurrence

    private fun overridePrefix(siteId: String, bookKey: String, chapterKey: String) =
        prefix(siteId, bookKey, chapterKey) + "ov@"

    /** 查 [originals] 這些原文的單處覆寫：回傳（原文, 第幾次出現, 譯文）。 */
    suspend fun overrides(siteId: String, bookKey: String, chapterKey: String, originals: Collection<String>): List<Triple<String, Int, String>> =
        withContext(Dispatchers.IO) {
            val head = overridePrefix(siteId, bookKey, chapterKey)
            val rows = dao.getTranslationsByPrefix(head)
            if (rows.isEmpty()) return@withContext emptyList()
            val byHash = originals.associateBy { it.hashCode() }
            rows.mapNotNull { e ->
                val (hash, occ) = e.key.removePrefix(head).split('#').takeIf { it.size == 2 } ?: return@mapNotNull null
                val original = hash.toIntOrNull()?.let(byHash::get) ?: return@mapNotNull null
                Triple(original, occ.toIntOrNull() ?: return@mapNotNull null, e.text)
            }
        }

    suspend fun countOverrides(siteId: String, bookKey: String, chapterKey: String): Int =
        withContext(Dispatchers.IO) { dao.getTranslationsByPrefix(overridePrefix(siteId, bookKey, chapterKey)).size }

    suspend fun deleteRaw(key: String) = withContext(Dispatchers.IO) { dao.deleteTranslation(key) }

    suspend fun getRaw(key: String): String? =
        withContext(Dispatchers.IO) { dao.getTranslations(listOf(key)).firstOrNull()?.text }

    suspend fun putRaw(key: String, text: String) =
        withContext(Dispatchers.IO) { dao.putTranslations(listOf(TranslationEntity(key, text))) }

    /** 清掉這一話的所有譯文（「重新翻譯本話」用）。 */
    suspend fun clearChapter(siteId: String, bookKey: String, chapterKey: String) =
        withContext(Dispatchers.IO) { dao.deleteTranslationsByPrefix(prefix(siteId, bookKey, chapterKey)) }

    /** 清掉整本書的譯文（書從書架刪除時可選）。 */
    suspend fun clearBookTranslations(siteId: String, bookKey: String) =
        withContext(Dispatchers.IO) { dao.deleteTranslationsByPrefix("$siteId|$bookKey|") }

    suspend fun cacheStats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        dao.countTranslations() to dao.translationBytes()
    }

    suspend fun clearAllTranslations() = withContext(Dispatchers.IO) { dao.deleteAllTranslations() }

    /** 譯文取代：本書有幾段譯文含 [needle]。 */
    suspend fun countTranslationsContaining(siteId: String, bookKey: String, needle: String): Int =
        withContext(Dispatchers.IO) { dao.countTranslationsContaining("$siteId|$bookKey|", needle) }

    /** 譯文取代：直接改快取，不重翻、不花 API。回傳改了幾段。 */
    suspend fun replaceInTranslations(siteId: String, bookKey: String, from: String, to: String): Int =
        withContext(Dispatchers.IO) { dao.replaceInTranslations("$siteId|$bookKey|", from, to) }

    // ── 書架 ──

    fun recordsFlow(): Flow<List<BookEntity>> = dao.allBooksFlow()

    fun bookFlow(siteId: String, bookKey: String): Flow<BookEntity?> = dao.getBookFlow(siteId, bookKey)

    suspend fun getBook(siteId: String, bookKey: String): BookEntity? =
        withContext(Dispatchers.IO) { dao.getBook(siteId, bookKey) }

    /** 書的中文譯名表（"siteId|bookKey" → 譯名），名詞表共用挑書時顯示用。 */
    fun bookAliasesFlow(): Flow<Map<String, String>> = dao.allBooksFlow().map { list ->
        list.filter { it.aliasZh.isNotBlank() }.associate { "${it.siteId}|${it.bookKey}" to it.aliasZh }
    }

    fun bookShareFlow(siteId: String, bookKey: String): Flow<String?> =
        dao.getBookFlow(siteId, bookKey).map { it?.glossaryShareId?.takeIf { s -> s.isNotBlank() } }

    suspend fun saveGlossaryShare(siteId: String, bookKey: String, target: String) =
        updateBook(siteId, bookKey) { it.copy(glossaryShareId = target) }

    /**
     * 打開某一話時記一筆閱讀紀錄：書不存在就建立；書名只在原本空白時才填（使用者改過的不蓋掉）。
     */
    suspend fun recordVisit(
        siteId: String,
        bookKey: String,
        bookTitle: String,
        url: String,
        chapterKey: String,
        chapterTitle: String,
        /** 書名來自站方 API（可信）：蓋掉之前從網頁標題猜錯的書名。 */
        authoritative: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): BookEntity = withContext(Dispatchers.IO) {
        val old = dao.getBook(siteId, bookKey) ?: BookEntity(siteId, bookKey)
        val titleFixed = authoritative && bookTitle.isNotBlank() && old.bookTitle.isNotBlank() && old.bookTitle != bookTitle
        val updated = old.copy(
            bookTitle = if (authoritative && bookTitle.isNotBlank()) bookTitle else old.bookTitle.ifBlank { bookTitle },
            // 舊書名是猜錯的，對著它自動翻出來的中文書名也一定是錯的，清掉讓它重翻
            aliasZh = if (titleFixed) "" else old.aliasZh,
            lastUrl = url,
            lastChapterKey = chapterKey,
            lastChapterTitle = chapterTitle.ifBlank { old.lastChapterTitle.takeIf { old.lastChapterKey == chapterKey }.orEmpty() },
            updatedAt = now,
        )
        dao.putBook(updated)
        updated
    }

    suspend fun updateBook(siteId: String, bookKey: String, change: (BookEntity) -> BookEntity) =
        withContext(Dispatchers.IO) {
            val old = dao.getBook(siteId, bookKey) ?: BookEntity(siteId, bookKey)
            dao.putBook(change(old))
        }

    suspend fun deleteBook(siteId: String, bookKey: String, alsoTranslations: Boolean) = withContext(Dispatchers.IO) {
        dao.deleteBook(siteId, bookKey)
        if (alsoTranslations) dao.deleteTranslationsByPrefix("$siteId|$bookKey|")
    }

    /**
     * 把一本書的資料（譯文快取、名詞表、書名與提示詞、閱讀紀錄）併到另一個 key 底下，舊的刪掉。
     * 目標已有的不覆蓋（以目標為準）。
     */
    suspend fun migrateBook(siteId: String, oldKey: String, newKey: String) = withContext(Dispatchers.IO) {
        if (oldKey == newKey) return@withContext
        dao.moveTranslations("$siteId|$oldKey|", "$siteId|$newKey|")
        dao.deleteTranslationsByPrefix("$siteId|$oldKey|")
        dao.moveGlossary("$siteId|$oldKey", "$siteId|$newKey")
        dao.deleteGlossaryByBook("$siteId|$oldKey")
        val old = dao.getBook(siteId, oldKey) ?: return@withContext
        val target = dao.getBook(siteId, newKey) ?: old.copy(bookKey = newKey)
        dao.putBook(
            target.copy(
                bookTitle = target.bookTitle.ifBlank { old.bookTitle },
                aliasZh = target.aliasZh.ifBlank { old.aliasZh },
                promptNote = target.promptNote.ifBlank { old.promptNote },
            ),
        )
        dao.deleteBook(siteId, oldKey)
    }

    // ── 章節 → 書（ridi） ──

    suspend fun chapterBook(siteId: String, chapterKey: String): String? =
        withContext(Dispatchers.IO) { dao.getChapterBook(siteId, chapterKey) }

    suspend fun saveChapterBooks(siteId: String, bookKey: String, chapterKeys: Collection<String>) =
        withContext(Dispatchers.IO) {
            dao.putChapterBooks(chapterKeys.map { ChapterBookEntity(siteId, it, bookKey) })
        }

    private companion object {
        /** SQLite 變數上限保守值：一次 IN 查詢最多帶這麼多個 key。 */
        const val SQL_CHUNK = 500
    }
}
