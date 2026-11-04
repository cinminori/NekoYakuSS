package com.nekoyaku.ss.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NekoDao {
    // ── 段落譯文快取 ──

    @Query("SELECT * FROM translation WHERE `key` IN (:keys)")
    suspend fun getTranslations(keys: List<String>): List<TranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTranslations(items: List<TranslationEntity>)

    @Query("SELECT * FROM translation WHERE `key` LIKE :prefix || '%'")
    suspend fun getTranslationsByPrefix(prefix: String): List<TranslationEntity>

    @Query("DELETE FROM translation WHERE `key` = :key")
    suspend fun deleteTranslation(key: String)

    @Query("DELETE FROM translation WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteTranslationsByPrefix(prefix: String)

    @Query("SELECT COUNT(*) FROM translation")
    suspend fun countTranslations(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(text)), 0) FROM translation")
    suspend fun translationBytes(): Long

    @Query("DELETE FROM translation")
    suspend fun deleteAllTranslations()

    /** 譯文取代：先數本書有幾段含舊譯名。 */
    @Query("SELECT COUNT(*) FROM translation WHERE `key` LIKE :prefix || '%' AND text LIKE '%' || :needle || '%'")
    suspend fun countTranslationsContaining(prefix: String, needle: String): Int

    @Query("UPDATE translation SET text = REPLACE(text, :from, :to) WHERE `key` LIKE :prefix || '%' AND text LIKE '%' || :from || '%'")
    suspend fun replaceInTranslations(prefix: String, from: String, to: String): Int

    // ── 書架 ──

    @Query("SELECT * FROM book ORDER BY updatedAt DESC")
    fun allBooksFlow(): Flow<List<BookEntity>>

    @Query("SELECT * FROM book WHERE siteId = :siteId AND bookKey = :bookKey")
    fun getBookFlow(siteId: String, bookKey: String): Flow<BookEntity?>

    @Query("SELECT * FROM book WHERE siteId = :siteId AND bookKey = :bookKey")
    suspend fun getBook(siteId: String, bookKey: String): BookEntity?

    @Upsert
    suspend fun putBook(book: BookEntity)

    @Query("DELETE FROM book WHERE siteId = :siteId AND bookKey = :bookKey")
    suspend fun deleteBook(siteId: String, bookKey: String)

    // ── 名詞表 ──

    @Query("SELECT * FROM glossary WHERE bookId = :bookId ORDER BY confirmed DESC, updatedAt DESC")
    fun getGlossaryFlow(bookId: String): Flow<List<GlossaryEntity>>

    @Query("SELECT * FROM glossary WHERE bookId = :bookId AND confirmed = 1")
    suspend fun getConfirmedGlossary(bookId: String): List<GlossaryEntity>

    /** AI 候選：IGNORE＝已存在的詞（含使用者確認過的）不覆蓋。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGlossaryCandidates(items: List<GlossaryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlossary(entry: GlossaryEntity)

    @Query("UPDATE glossary SET confirmed = 1, updatedAt = :now WHERE bookId = :bookId AND term = :term")
    suspend fun confirmGlossary(bookId: String, term: String, now: Long)

    @Query("DELETE FROM glossary WHERE bookId = :bookId AND term = :term")
    suspend fun deleteGlossary(bookId: String, term: String)

    @Query("SELECT * FROM glossary WHERE bookId = :bookId")
    suspend fun getGlossaryOnce(bookId: String): List<GlossaryEntity>

    @Query("DELETE FROM glossary WHERE bookId = :bookId")
    suspend fun deleteGlossaryByBook(bookId: String)

    @Query("DELETE FROM glossary WHERE bookId = :bookId AND confirmed = 1")
    suspend fun deleteConfirmedGlossary(bookId: String)

    /** 匯入「取代」：刪掉本書已確認名詞、換成檔案裡的（同一個交易，失敗不會清空）。 */
    @Transaction
    suspend fun replaceConfirmedGlossary(bookId: String, items: List<GlossaryEntity>) {
        deleteConfirmedGlossary(bookId)
        items.forEach { upsertGlossary(it) }
    }

    // ── 搬書（ridi 系列判斷修正後，把「單話自成一本」的資料併到系列底下） ──

    @Query("UPDATE OR IGNORE translation SET `key` = :newPrefix || substr(`key`, length(:oldPrefix) + 1) WHERE `key` LIKE :oldPrefix || '%'")
    suspend fun moveTranslations(oldPrefix: String, newPrefix: String): Int

    @Query("UPDATE OR IGNORE glossary SET bookId = :newId WHERE bookId = :oldId")
    suspend fun moveGlossary(oldId: String, newId: String): Int

    // ── 章節 → 書（ridi） ──

    @Query("SELECT bookKey FROM chapter_book WHERE siteId = :siteId AND chapterKey = :chapterKey")
    suspend fun getChapterBook(siteId: String, chapterKey: String): String?

    @Upsert
    suspend fun putChapterBooks(items: List<ChapterBookEntity>)
}
