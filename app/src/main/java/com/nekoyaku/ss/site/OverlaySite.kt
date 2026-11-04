package com.nekoyaku.ss.site

import com.nekoyaku.ss.net.Http
import com.nekoyaku.ss.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl

/** 這一站的疊翻支援程度（書架與首頁的站別按鈕會標示）。 */
enum class SupportLevel(val label: String) {
    /** 已依現有站點知識完整實作（kakaopage、ridi）。 */
    SUPPORTED("支援"),

    /** 有實作，但正文結構多變、尚未完整驗證（bomtoon、mrblue）。 */
    EXPERIMENTAL("實驗性"),

    /** 尚未支援：只能瀏覽、不會疊翻。 */
    UNSUPPORTED("尚未支援"),
}

/**
 * 站別設定：**只描述「網頁上哪裡是正文」與「網址怎麼對應到書／話」**。
 *
 * NekoYakuSS 不再抽文、不存原文：使用者在站內瀏覽器照常閱讀，注入腳本（assets/overlay.js）
 * 依 [overlayConfigJson] 找出正文段落，把譯文插在每段原文下方。
 */
interface OverlaySite {
    val id: String
    val displayName: String

    /** 站首頁（首頁「前往網站」與登入用）。 */
    val homeUrl: String

    val support: SupportLevel

    /** 支援狀況補充說明（顯示在首頁站別按鈕下方）。 */
    val supportNote: String

    /** 本站接受的 host（完全相符、只收 HTTPS）。 */
    val hosts: List<String>

    fun matches(url: String): Boolean = parse(url) != null

    /** 章節頁的路徑規則（Kotlin 判斷與注入腳本共用；SPA 站內換頁時腳本自己再比一次）。 */
    val chapterPathRegex: String

    /** 網址是不是「閱讀某一話」的頁面——只有這種頁面才會啟動疊翻。 */
    fun isChapterUrl(url: String): Boolean

    /** 從網址取出書的 key（ridi 網址上沒有，要靠 [ChapterBookResolver]，這裡回 null）。 */
    fun bookKeyOf(url: String): String?

    /** 從網址取出話的 key。 */
    fun chapterKeyOf(url: String): String?

    /**
     * 給注入腳本的設定（JSON 物件字串）：
     * - `roots`：正文容器的 CSS selector 清單（依序嘗試；空＝整個 document）
     * - `shadow`：要不要往 open shadow root 裡找（kakaopage）
     * - `iframes`：要不要往同源 iframe 裡找（bomtoon 電子書型）
     * - `paras`：段落候選 selector；只取「裡面沒有別的候選段落」的最內層那個
     * - `minParas`：一個容器至少要有幾段才算正文（避免把選單、留言當正文）
     */
    val overlayConfigJson: String

    /**
     * 正文插圖要不要送去看圖翻譯（每張一次請求）。kakaopage 的作者常把特效文字、聊天畫面做成圖片，
     * 不翻就整段劇情消失；其他站的插圖多半是真插畫，送去只是白花錢。
     */
    val translatesImages: Boolean get() = false

    /**
     * 向站方取正式的（書名, 話名）。網頁標題不一定是書名（kakaopage viewer 的標題是「추천 | 카카오페이지」）。
     * null＝不支援或失敗，退回 [titlesOf]。
     */
    suspend fun fetchTitles(url: String, cookie: String?): Pair<String, String>? = null

    /** 從網頁標題推書名／話名。 */
    fun titlesOf(docTitle: String, ogTitle: String): Pair<String, String> {
        val raw = ogTitle.ifBlank { docTitle }.trim()
        val chapter = raw.substringBefore(" | ").substringBefore(" - ").trim()
        val book = chapter.replace(EP_SUFFIX, "").trim().ifBlank { chapter }
        return book to chapter
    }

    fun parse(url: String): HttpUrl? = hosts.firstNotNullOfOrNull { UrlSecurity.parseOfficialUrl(url, it) }

    companion object {
        /** 「… 12화」「… 제12화. 副標」之類的話數尾巴。 */
        val EP_SUFFIX = Regex("""\s*(제\s*)?\d+\s*(화|권|회).*$""")
    }
}

/** kakaopage：正文 render 在某個匿名元素的 **open shadow root** 裡（class 名是雜湊、改版就變，不可寫死）。 */
object KakaopageSite : OverlaySite {
    override val id = "kakaopage"
    override val displayName = "카카오페이지（kakaopage）"
    override val homeUrl = "https://page.kakao.com/"
    override val support = SupportLevel.SUPPORTED
    override val supportNote = "正文在 shadow DOM 內，腳本會自動穿透。"
    override val hosts = listOf("page.kakao.com")

    override val chapterPathRegex = """^/content/(\d+)/viewer/(\d+)/?$"""
    private val viewerRe = Regex(chapterPathRegex)

    override fun isChapterUrl(url: String): Boolean = parse(url)?.encodedPath?.let { viewerRe.matches(it) } == true
    override fun bookKeyOf(url: String): String? = parse(url)?.encodedPath?.let { viewerRe.find(it)?.groupValues?.get(1) }
    override fun chapterKeyOf(url: String): String? = parse(url)?.encodedPath?.let { viewerRe.find(it)?.groupValues?.get(2) }

    // 正文 shadow root 裡是一大串 <p>；作者用 .box* 框住的系統訊息、聊天卡片有時是直接放字的 div
    // （只取最內層含韓文的元素，包住整章的外層 div 不會被當成一段）
    override val overlayConfigJson =
        """{"roots":[],"shadow":true,"iframes":false,"paras":"p,div,li,td,th,h1,h2,h3,h4,blockquote","minParas":5,""" +
            // 翻頁模式的總頁數不隨譯文更新（0.1.5 實機確認）→ 改 viewer 存在 localStorage 的閱讀方式為捲動
            """"scrollFix":{"key":"text_viewer_viewer_setting_read_type","value":"scroll"}}"""

    override val translatesImages = true

    private val json = Json { ignoreUnknownKeys = true }

    /** viewer API（必須帶 Referer: page.kakao.com，否則 403）：`series_item.title`＝書名、`item.title`＝話名。 */
    override suspend fun fetchTitles(url: String, cookie: String?): Pair<String, String>? {
        val m = parse(url)?.encodedPath?.let { viewerRe.find(it) } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val headers = buildMap {
                    put("Referer", "https://page.kakao.com/")
                    if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                }
                val meta = json.parseToJsonElement(
                    Http.get(
                        "https://bff-page.kakao.com/api/gateway/api/v1/viewer/data" +
                            "?series_id=${m.groupValues[1]}&product_id=${m.groupValues[2]}",
                        headers,
                    ),
                ).jsonObject
                val book = (meta["series_item"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
                val chapter = (meta["item"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (book.isBlank()) null else book to chapter
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                DebugLog.w("kakaopage", "viewer API 取書名失敗：${it.message?.take(100)}")
                null
            }
        }
    }
}

/** ridi：Vite SPA，正文在 `#viewer_contents`；除了 `<p>`，聊天泡泡／系統窗等排版是 div、論壇板面是 table。 */
object RidiSite : OverlaySite {
    override val id = "ridi"
    override val displayName = "리디（ridi）"
    override val homeUrl = "https://ridibooks.com/"
    override val support = SupportLevel.SUPPORTED
    override val supportNote = "每話第一次開啟會查一次作品頁，確認屬於哪個系列。"
    override val hosts = listOf("ridibooks.com")

    override val chapterPathRegex = """^/books/(\d+)/view/?$"""
    private val viewRe = Regex(chapterPathRegex)

    override fun isChapterUrl(url: String): Boolean = parse(url)?.encodedPath?.let { viewRe.matches(it) } == true

    /** ridi 每話都是獨立 bookId，網址上看不出系列 → 交給 [ChapterBookResolver]。 */
    override fun bookKeyOf(url: String): String? = null
    override fun chapterKeyOf(url: String): String? = parse(url)?.encodedPath?.let { viewRe.find(it)?.groupValues?.get(1) }

    override val overlayConfigJson =
        """{"roots":["#viewer_contents"],"shadow":false,"iframes":false,"paras":"p,div,li,td,th,h1,h2,h3,h4,h5,h6,blockquote","minParas":3}"""

    private val viewLinkRe = Regex("""/books/(\d+)/view""")

    /**
     * 作品頁（`/books/{任一話 id}`，SSR）上所有 `/books/{id}/view` 連結＝整個系列的各話。
     * 取**數字最小**的當系列 key（ridi 話數與 bookId 同向遞增；頁首「이어보기」那種精選連結指到哪都不影響）。
     */
    fun seriesKeyFromDetail(html: String): Pair<String, Set<String>>? {
        val ids = viewLinkRe.findAll(html).map { it.groupValues[1] }.toSet()
        // 只找到一話＝頁面上根本沒有目次（0.1.6 實機：新版作品頁 586KB 只有本話自己的連結），不能拿來當系列
        if (ids.size < 2) return null
        val first = ids.minByOrNull { it.toLongOrNull() ?: Long.MAX_VALUE } ?: return null
        return first to ids
    }

    /** 系列 key 加前綴，跟「單話 bookId」分開，避免撞號。 */
    fun seriesKey(seriesId: String): String = "s$seriesId"

    private val seriesObjRe = Regex(""""series"\s*:\s*\{[^{}]{0,300}?"id"\s*:\s*"?(\d+)""")
    private val seriesIdRe = Regex(""""series_?[iI]d"\s*:\s*"?(\d+)""")
    private val seriesNameRe = Regex(""""([A-Za-z_]*[sS]eries[A-Za-z_]*)"\s*:""")

    /** book-api（`book-api.ridibooks.com/books/{id}`）回應裡的 `series.id`。 */
    fun seriesIdFromBookApi(jsonText: String): String? =
        seriesObjRe.find(jsonText)?.groupValues?.get(1) ?: seriesIdRe.find(jsonText)?.groupValues?.get(1)

    /** 作品頁內嵌資料（Next.js 等）裡的系列編號。頁面上還有推薦書，所以取**離本話 id 最近**的那一筆。 */
    fun seriesIdFromDetail(html: String, chapterKey: String): String? {
        val cands = (seriesObjRe.findAll(html) + seriesIdRe.findAll(html)).map { it.range.first to it.groupValues[1] }.toList()
        if (cands.isEmpty()) return null
        val anchor = "\"" + chapterKey + "\""
        val anchors = generateSequence(html.indexOf(anchor).takeIf { it >= 0 }) { prev ->
            html.indexOf(anchor, prev + 1).takeIf { it >= 0 }
        }.take(200).toList()
        if (anchors.isEmpty()) return cands.minByOrNull { it.first }?.second
        return cands.minByOrNull { (pos, _) -> anchors.minOf { kotlin.math.abs(it - pos) } }?.second
    }

    /** 診斷用：頁面上跟 series 有關的 JSON 鍵名與出現次數（不含內容）。 */
    fun seriesKeyNames(html: String): String =
        seriesNameRe.findAll(html).map { it.groupValues[1] }
            .groupingBy { it }.eachCount().entries.take(12).joinToString { "${it.key}×${it.value}" }
}

/** bomtoon：小說 viewer 正文是 `p[id^="text_"]`；電子書型把正文放在同源 iframe。 */
object BomtoonSite : OverlaySite {
    override val id = "bomtoon"
    override val displayName = "봄툰（bomtoon）"
    override val homeUrl = "https://www.bomtoon.com/"
    override val support = SupportLevel.EXPERIMENTAL
    override val supportNote = "一般連載型可用；電子書型（iframe）版面多變，未完整驗證。"
    override val hosts = listOf("www.bomtoon.com")

    override val chapterPathRegex = """^/viewer/([^/]+)/([^/]+)/?$"""
    private val viewerRe = Regex(chapterPathRegex)

    override fun isChapterUrl(url: String): Boolean = parse(url)?.encodedPath?.let { viewerRe.matches(it) } == true
    override fun bookKeyOf(url: String): String? = parse(url)?.encodedPath?.let { viewerRe.find(it)?.groupValues?.get(1) }
    override fun chapterKeyOf(url: String): String? = parse(url)?.encodedPath?.let { viewerRe.find(it)?.groupValues?.get(2) }

    override val overlayConfigJson =
        """{"roots":[],"shadow":false,"iframes":true,"paras":"p,li,td,th,h1,h2,h3,h4,blockquote","minParas":5,"skip":"header,nav,footer,button"}"""
}

/** mrblue：正文由 WASM 解密後注入 `#renderArea`（light DOM）；連載版是 `p.body*`，單行本是 spine div 裡的 `<p>`。 */
object MrBlueSite : OverlaySite {
    override val id = "mrblue"
    override val displayName = "미스터블루（mrblue）"
    override val homeUrl = "https://www.mrblue.com/"
    override val support = SupportLevel.EXPERIMENTAL
    override val supportNote = "連載版可用；單行本若為分頁排版，插入譯文後頁數會變多。"
    override val hosts = listOf("viewer.mrblue.com", "www.mrblue.com", "m.mrblue.com", "mrblue.com")

    override val chapterPathRegex = """^/ebook/([^/]+)/(\d+)/?$"""
    private val ebookRe = Regex(chapterPathRegex)

    override fun isChapterUrl(url: String): Boolean {
        val u = parse(url) ?: return false
        return u.host.equals("viewer.mrblue.com", ignoreCase = true) && ebookRe.matches(u.encodedPath)
    }
    override fun bookKeyOf(url: String): String? = parse(url)?.encodedPath?.let { ebookRe.find(it)?.groupValues?.get(1) }
    override fun chapterKeyOf(url: String): String? = parse(url)?.encodedPath?.let { ebookRe.find(it)?.groupValues?.get(2) }

    override val overlayConfigJson =
        """{"roots":["#renderArea"],"shadow":false,"iframes":false,"paras":"p,li,td,th,h1,h2,h3,h4,h5,h6,blockquote","minParas":3,"skip":"#not-support-msg"}"""

    /** mrblue viewer 沒有 og:title，document.title 長「書名 / N권 | 미스터블루」。 */
    override fun titlesOf(docTitle: String, ogTitle: String): Pair<String, String> {
        val raw = docTitle.substringBefore(" | ").trim()
        val book = raw.substringBefore(" / ").replace("[단행본]", "").trim()
        return book.ifBlank { raw } to raw
    }
}
