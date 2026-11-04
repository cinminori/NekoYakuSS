package com.nekoyaku.ss.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 名詞表存取。bookId = "siteId|bookKey"，每本書一份。
 * **名詞表共用**：book.glossaryShareId 有值時，本書所有名詞操作改指向該 bookId
 * （同作品連載版/ebook 版共用一份）——解析集中在此 repo，呼叫端無感。
 */
class GlossaryRepository(private val dao: NekoDao) {

    /** 共用解析：有指向就用目標 bookId（一跳，不追鏈）。 */
    private suspend fun resolve(bookId: String): String {
        val i = bookId.indexOf('|')
        if (i <= 0) return bookId
        val share = dao.getBook(bookId.substring(0, i), bookId.substring(i + 1))
            ?.glossaryShareId?.takeIf { it.isNotBlank() }
        return share ?: bookId
    }

    /** UI 設定共用時用：把使用者挑的目標解析成最終目標（目標自己也在共用→指到它的源頭）。 */
    suspend fun resolveTarget(bookId: String): String = withContext(Dispatchers.IO) { resolve(bookId) }

    /** 此書全部名詞（候選＋已確認），表變動自動推新；共用設定變更也會即時切換來源。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun forBookFlow(bookId: String): Flow<List<GlossaryEntity>> {
        val i = bookId.indexOf('|')
        if (i <= 0) return dao.getGlossaryFlow(bookId)
        return dao.getBookFlow(bookId.substring(0, i), bookId.substring(i + 1))
            .flatMapLatest { meta ->
                dao.getGlossaryFlow(meta?.glossaryShareId?.takeIf { it.isNotBlank() } ?: bookId)
            }
    }

    /** 翻譯注入用：已確認名詞的 韓→中 對照表（名詞抽取防重抽用）。 */
    suspend fun confirmedMap(bookId: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            dao.getConfirmedGlossary(resolve(bookId)).associate { it.term to it.translation }
        }

    /** 翻譯注入用：已確認名詞完整清單（含 type/note，分級注入——稱呼靈活、其他固定）。 */
    suspend fun confirmedList(bookId: String): List<GlossaryEntity> =
        withContext(Dispatchers.IO) { dao.getConfirmedGlossary(resolve(bookId)) }

    suspend fun upsert(entry: GlossaryEntity) =
        withContext(Dispatchers.IO) { dao.upsertGlossary(entry.copy(bookId = resolve(entry.bookId))) }

    /** 加入 AI 候選（已存在的詞不覆蓋，避免蓋掉使用者已確認的）。 */
    suspend fun addCandidates(items: List<GlossaryEntity>) =
        withContext(Dispatchers.IO) {
            dao.insertGlossaryCandidates(items.map { it.copy(bookId = resolve(it.bookId)) })
        }

    /**
     * 「自動採用」：把 AI 抽出的詞直接視為已確認、塞進名詞表。
     * - 已存在的詞（confirmed 或 candidate）：只升級成 confirmed，**不動** zh 譯名（尊重使用者改過的字 / 既有 candidate）
     * - 不存在的詞：插入並標 confirmed
     *
     * 回傳實際「新增」的詞數量（用於 log / 進度顯示）。
     */
    suspend fun autoAdoptAsConfirmed(bookId: String, items: List<GlossaryEntity>): Int =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext 0
            val id = resolve(bookId)
            // IGNORE → 既有者不覆蓋；新詞先以 confirmed=true 插入（我們傳進來時就標 true）
            dao.insertGlossaryCandidates(items.map { it.copy(bookId = id) })
            // 然後把每個詞的 confirmed flag 拉成 1（針對既有 candidate 升級用；新插入的本來就 1，no-op）
            val now = System.currentTimeMillis()
            items.forEach { dao.confirmGlossary(id, it.term, now) }
            items.size
        }

    suspend fun confirm(bookId: String, term: String, now: Long) =
        withContext(Dispatchers.IO) { dao.confirmGlossary(resolve(bookId), term, now) }

    suspend fun delete(bookId: String, term: String) =
        withContext(Dispatchers.IO) { dao.deleteGlossary(resolve(bookId), term) }

    /**
     * 名詞表共用前置：把本書既有名詞併入 target（IGNORE＝target 同名詞優先），原表清空。
     * 呼叫端接著 saveGlossaryShare 設定指向。回傳搬移筆數。
     */
    suspend fun mergeInto(bookId: String, targetBookId: String): Int = withContext(Dispatchers.IO) {
        if (bookId == targetBookId) return@withContext 0
        val own = dao.getGlossaryOnce(bookId)
        if (own.isNotEmpty()) {
            dao.insertGlossaryCandidates(own.map { it.copy(bookId = targetBookId) })
            dao.deleteGlossaryByBook(bookId)
        }
        own.size
    }

    /** 匯出已確認名詞為 JSON（備份／搬到別本書共用）。無已確認詞回空表 JSON。 */
    suspend fun exportJson(bookId: String): String = withContext(Dispatchers.IO) {
        val terms = dao.getConfirmedGlossary(resolve(bookId)).map {
            GlossaryTermJson(it.term, it.translation, it.type, it.note)
        }
        GLOSSARY_JSON.encodeToString(
            GlossaryExport(bookId = bookId, exportedAt = System.currentTimeMillis(), terms = terms),
        )
    }

    /**
     * 匯入 JSON（同名詞以匯入者為準覆蓋、一律標已確認）；回實際匯入筆數。格式錯會拋例外。
     * [replace]＝true（0.5.78）：先刪本書所有已確認名詞，只留檔案裡的——匯出→整理→匯回＝批量刪改。
     * 檔案解析出 0 筆時拒絕取代（防選錯檔把整本名詞表清空）。
     */
    suspend fun importJson(bookId: String, json: String, replace: Boolean = false): Int = withContext(Dispatchers.IO) {
        val parsed = GLOSSARY_JSON.decodeFromString<GlossaryExport>(json)
        val id = resolve(bookId)
        val now = System.currentTimeMillis()
        val items = parsed.terms.mapNotNull { t ->
            val term = t.term.trim()
            val zh = t.zh.trim()
            if (term.isEmpty() || zh.isEmpty()) null else GlossaryEntity(id, term, zh, t.note, true, t.type, now)
        }
        if (replace) {
            require(items.isNotEmpty()) { "檔案裡沒有名詞，不取代" }
            dao.replaceConfirmedGlossary(id, items)
        } else {
            items.forEach { dao.upsertGlossary(it) }
        }
        items.size
    }

    companion object {
        fun bookId(siteId: String, bookKey: String): String = "$siteId|$bookKey"
        private val GLOSSARY_JSON = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }
}

/** 名詞表匯出格式（JSON 檔）。對外用 zh 當中譯欄名，比內部 translation 直覺。 */
@Serializable
data class GlossaryExport(
    val app: String = "NekoYakuSS",
    val version: Int = 1,
    val bookId: String = "",
    val exportedAt: Long = 0,
    val terms: List<GlossaryTermJson> = emptyList(),
)

@Serializable
data class GlossaryTermJson(
    val term: String,
    val zh: String,
    val type: String = "",
    val note: String = "",
)
