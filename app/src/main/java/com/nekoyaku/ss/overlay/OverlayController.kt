package com.nekoyaku.ss.overlay

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.nekoyaku.ss.NekoYakuApp
import com.nekoyaku.ss.ai.GlossaryTerm
import com.nekoyaku.ss.ai.NETWORK_ERROR_HINT
import com.nekoyaku.ss.ai.TranslationService
import com.nekoyaku.ss.ai.friendlyApiError
import com.nekoyaku.ss.ai.UsageTally
import com.nekoyaku.ss.data.db.GlossaryEntity
import com.nekoyaku.ss.data.db.GlossaryRepository
import com.nekoyaku.ss.net.Http
import com.nekoyaku.ss.site.OverlaySite
import com.nekoyaku.ss.site.RidiSite
import com.nekoyaku.ss.site.SiteRegistry
import com.nekoyaku.ss.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume

/** 疊翻狀態（瀏覽器上方的狀態列顯示用）。 */
data class OverlayStatus(
    /** 目前這頁是不是可疊翻的章節頁。 */
    val active: Boolean = false,
    val translating: Int = 0,
    val translated: Int = 0,
    val fromCache: Int = 0,
    val extractingGlossary: Boolean = false,
    /** 長章節分段抽名詞時的進度（第幾段／共幾段）；只有一段時 total＝1。 */
    val extractStep: Int = 0,
    val extractTotal: Int = 0,
    /** 一般提示（不是錯誤），例如自動加進名詞表的詞數；翻譯開始時不會被清掉，換頁或按「知道了」才清。 */
    val notice: String? = null,
    val message: String? = null,
    /** 這一話所屬的書名（中文譯名優先）；網頁標題常常不是書名（kakaopage 是「추천 | 카카오페이지」）。 */
    val title: String = "",
)

/** 長按譯文開啟的編輯對象：這一話第 [occurrence] 次出現的 [text]。 */
data class TrEdit(
    val sid: String,
    val url: String,
    val text: String,
    val occurrence: Int,
    val translation: String,
    val context: List<String>,
    /** 這一處已經單獨改過（不是用共用譯文）。 */
    val overridden: Boolean,
)

/**
 * 一個站內瀏覽器（WebView）的疊翻控制器。
 *
 * 流程：注入 assets/overlay.js → 腳本找到正文段落、捲到畫面附近時呼叫 [Bridge.request]
 * → 先查段落快取（hash→譯文），命中的立刻套回網頁 → 沒命中的分批交給 [com.nekoyaku.ss.ai.TranslationService]
 * → 每翻完一批就存快取、套回網頁。
 *
 * 原文只在記憶體裡經過（送翻譯、算 hash），**不寫進資料庫**。
 */
class OverlayController(
    private val app: NekoYakuApp,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    /** 目前頂層頁面的網址（WebViewClient 回呼更新；JS 介面在背景執行緒讀）。 */
    @Volatile
    var topUrl: String = ""
        private set

    /** 注入腳本回報的頁面識別碼；只接受這些 sid 的請求（擋掉頁面裡的第三方 iframe）。 */
    private val knownSids = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private val _status = MutableStateFlow(OverlayStatus())
    val status: StateFlow<OverlayStatus> = _status.asStateFlow()

    private val _trEdit = MutableStateFlow<TrEdit?>(null)
    val trEdit = _trEdit.asStateFlow()

    /** 同一段正在翻的（cacheKey → 等著套用的 sid），避免同段重複送。 */
    private val inFlight = HashMap<String, MutableSet<String>>()
    private val inFlightLock = Any()

    /** 每一話正在翻的工作（gateKey → Job，同受 [inFlightLock] 保護）：重新翻譯本話時要先取消，否則舊譯文會寫回來。 */
    private val chapterJobs = HashMap<String, MutableSet<Job>>()

    /** 每一話的名詞預抽（同一話只跑一次；翻譯要等它好）。 */
    private val glossaryGate = HashMap<String, Deferred<Unit>>()
    private val gateLock = Mutex()

    /** 本次瀏覽已處理過書名翻譯的書。 */
    private val titleTried = HashSet<String>()

    private val script: String by lazy {
        app.assets.open("overlay.js").bufferedReader().use { it.readText() }
    }

    fun attach(wv: WebView) {
        webView = wv
        wv.addJavascriptInterface(Bridge(), BRIDGE_NAME)
    }

    fun detach() {
        webView = null
    }

    // ── WebViewClient 回呼 ──

    private var lastUrl = ""

    /**
     * 頂層網址變了（[doInject]＝false 用在 onPageStarted：新頁的 document 還沒好，注入了也是白做）。
     */
    fun onUrlChanged(url: String?, doInject: Boolean = true) {
        if (url.isNullOrBlank()) return
        topUrl = url
        val site = SiteRegistry.forUrl(url)
        val active = site != null && site.isChapterUrl(url) && site.support != com.nekoyaku.ss.site.SupportLevel.UNSUPPORTED
        if (url != lastUrl) {
            lastUrl = url
            _status.value = OverlayStatus(active = active)
        }
        // 章節頁要疊翻；其他頁（首頁、作品頁）開了「網站介面翻譯」也要注入
        if (doInject && site != null && (active || app.overlaySettings.uiTranslate)) inject()
    }

    /** 注入（可重複呼叫）：第一次載入腳本，之後只更新設定、重新掃描。 */
    fun inject() {
        val wv = webView ?: return
        val url = topUrl
        val site = SiteRegistry.forUrl(url) ?: return
        val js = "window.__nyCfg=${configJson(site)};\n$script\n;window.__ny?window.__ny.sid():''"
        main.post {
            wv.evaluateJavascript(js) { r ->
                val sid = r?.trim('"').orEmpty()
                if (sid.isNotBlank() && sid != "null") {
                    knownSids.add(sid)
                    while (knownSids.size > 8) knownSids.remove(knownSids.first())
                }
            }
        }
    }

    private fun configJson(site: OverlaySite): String {
        val s = app.overlaySettings
        val base = json.parseToJsonElement(site.overlayConfigJson).jsonObject
            .let { if (s.fixPagedMode) it else JsonObject(it - "scrollFix") }
        val merged = JsonObject(
            base + mapOf(
                "mode" to JsonPrimitive(s.mode.value.js),
                "auto" to JsonPrimitive(s.autoTranslate),
                "scale" to JsonPrimitive(s.scale),
                "eink" to JsonPrimitive(s.useEink),
                "ahead" to JsonPrimitive(s.aheadScreens),
                "pathRe" to JsonPrimitive(site.chapterPathRegex),
                "images" to JsonPrimitive(site.translatesImages && s.translateImages),
                "ui" to JsonPrimitive(s.uiTranslate),
                "font" to JsonPrimitive(s.trFont.js),
            ),
        )
        return safeJs(merged.toString())
    }

    // ── App → 網頁 ──

    fun setMode(m: OverlayMode) = js("window.__ny&&__ny.setMode('${m.js}')")
    fun setAuto(on: Boolean) = js("window.__ny&&__ny.setAuto($on)")
    fun setStyle(scale: Float, eink: Boolean) = js("window.__ny&&__ny.setStyle({scale:$scale,eink:$eink})")
    fun translateAll() = js("window.__ny&&__ny.translateAll()")

    /** 本話還沒翻、也沒在翻的段落數。 */
    suspend fun untranslatedCount(): Int =
        evalJs("window.__ny?(function(s){return s.total-s.done-s.pending})(JSON.parse(__ny.stats())):0")?.toIntOrNull() ?: 0

    /** 網站介面翻譯開關（關掉會把網頁上的字還原）。 */
    fun setUi(on: Boolean) {
        if (on) inject()
        js("window.__ny&&__ny.setUi($on)")
    }

    /** 音量鍵翻頁。 */
    fun page(dir: Int) = js("window.__ny?__ny.page($dir):window.scrollBy(0,$dir*window.innerHeight*0.9)")

    private fun js(code: String) {
        val wv = webView ?: return
        main.post { wv.evaluateJavascript(code, null) }
    }

    private suspend fun evalJs(code: String): String? {
        val wv = webView ?: return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(EVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    wv.evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(r) }
                }
            }
        }
    }

    private fun applyToPage(sids: Collection<String>, pairs: Map<String, String>) {
        if (pairs.isEmpty() || sids.isEmpty()) return
        val payload = safeJs(json.encodeToString(pairs.map { (t, tr) -> ApplyItem(t, tr) }))
        sids.forEach { sid -> js("window.__ny&&__ny.apply('${jsStr(sid)}',$payload)") }
    }

    private fun failOnPage(sid: String, ids: List<Int>, msg: String) {
        if (ids.isEmpty()) return
        val auto = isTransient(msg)
        val shown = if (auto) "網路不穩" else friendlyError(msg).take(60)
        js("window.__ny&&__ny.fail('${jsStr(sid)}',${ids.joinToString(",", "[", "]")},'${jsStr(shown)}',$auto)")
    }

    /** 讀者看得懂的錯誤（同設定頁的白話說明）；沒有 HTTP 碼的斷網類錯誤也換成白話。 */
    private fun friendlyError(raw: String?): String {
        val msg = raw?.takeIf { it.isNotBlank() } ?: return "翻譯失敗"
        return if (isTransient(msg) && !msg.contains("HTTP ")) NETWORK_ERROR_HINT else friendlyApiError(msg)
    }

    /** 暫時性錯誤（斷網、DNS、逾時、限流、伺服器忙）：網頁端 20 秒後自動重送。 */
    private fun isTransient(msg: String): Boolean {
        val m = msg.lowercase()
        if (TranslationService.isOutOfCredit(m)) return false
        return listOf(
            "unable to resolve host", "timeout", "timed out", "failed to connect", "connection reset",
            "connection refused", "network", "http 429", "http 500", "http 502", "http 503", "http 504",
            "rate limit", "overloaded", "socket",
        ).any { it in m }
    }

    // ── 書／話的對應 ──

    private data class Chapter(val site: OverlaySite, val bookKey: String, val chapterKey: String) {
        val bookId get() = GlossaryRepository.bookId(site.id, bookKey)
        val gateKey get() = "${site.id}|$bookKey|$chapterKey"
    }

    private suspend fun chapterOf(url: String): Chapter? {
        val site = SiteRegistry.forUrl(url) ?: return null
        if (!site.isChapterUrl(url)) return null
        val chapterKey = site.chapterKeyOf(url) ?: return null
        val bookKey = site.bookKeyOf(url) ?: resolveBookKey(site, chapterKey)
        return Chapter(site, bookKey, chapterKey)
    }

    private val resolveLock = Mutex()

    /** ridi：抓作品頁找出系列首話當書的 key；抓不到就先用這一話自己的 id（不寫入對照表，下次再試）。 */
    private suspend fun resolveBookKey(site: OverlaySite, chapterKey: String): String = resolveLock.withLock {
        val cached = app.cacheRepository.chapterBook(site.id, chapterKey)
        // 對照表裡「本話自成一本」是 0.1.6 以前系列判斷失敗留下的，要重查
        if (cached != null && cached != chapterKey) return@withLock cached
        if (site !== RidiSite) return@withLock chapterKey
        val found = findRidiSeries(chapterKey)
        if (found == null) {
            DebugLog.i("overlay", "ridi 找不到系列資訊（$chapterKey），暫以本話自成一本，下次再查")
            return@withLock chapterKey
        }
        app.cacheRepository.saveChapterBooks(site.id, found.first, found.second + chapterKey)
        if (cached == chapterKey && found.first != chapterKey) {
            app.cacheRepository.migrateBook(site.id, chapterKey, found.first)
            DebugLog.i("overlay", "ridi：把先前自成一本的 $chapterKey 併入系列 ${found.first}（名詞表、譯文快取一起搬）")
        }
        found.first
    }

    /**
     * ridi 每一話是獨立 bookId，系列要另外查。依序：
     * ① book-api 的 `series.id`；② 作品頁內嵌資料的系列編號（取離本話 id 最近的）；
     * ③ 作品頁上的各話連結（舊版作品頁才有）。都沒有＝null。
     */
    private suspend fun findRidiSeries(chapterKey: String): Pair<String, Set<String>>? = withContext(Dispatchers.IO) {
        fun get(url: String): String? = runCatching {
            val cookie = CookieManager.getInstance().getCookie(url)
            Http.get(url, if (cookie.isNullOrBlank()) emptyMap() else mapOf("Cookie" to cookie))
        }.getOrElse {
            if (it is CancellationException) throw it
            DebugLog.w("overlay", "ridi 查詢失敗 ${url.substringBefore('?')}：${it.message?.take(80)}")
            null
        }
        get("https://book-api.ridibooks.com/books/$chapterKey")?.let { RidiSite.seriesIdFromBookApi(it) }?.let { id ->
            DebugLog.i("overlay", "ridi 系列（book-api）：$chapterKey → $id")
            return@withContext RidiSite.seriesKey(id) to setOf(chapterKey)
        }
        val html = get("https://ridibooks.com/books/$chapterKey") ?: return@withContext null
        RidiSite.seriesIdFromDetail(html, chapterKey)?.let { id ->
            DebugLog.i("overlay", "ridi 系列（作品頁資料）：$chapterKey → $id")
            return@withContext RidiSite.seriesKey(id) to setOf(chapterKey)
        }
        RidiSite.seriesKeyFromDetail(html)?.let { (first, ids) ->
            DebugLog.i("overlay", "ridi 系列（作品頁目次）：$chapterKey → $first（${ids.size} 話）")
            return@withContext first to ids
        }
        DebugLog.w("overlay", "ridi 作品頁 ${html.length}B 找不到系列；series 相關鍵：${RidiSite.seriesKeyNames(html).ifBlank { "無" }}")
        null
    }

    // ── 網頁 → App ──

    @Serializable
    private data class PageInfo(val sid: String = "", val url: String = "", val title: String = "", val og: String = "", val count: Int = 0)

    @Serializable
    private data class TrMenu(val sid: String = "", val url: String = "", val t: String = "", val occ: Int = 0, val tr: String = "", val ctx: List<String> = emptyList())

    @Serializable
    private data class OvItem(val t: String, val occ: Int, val tr: String? = null)

    @Serializable
    private data class ReqItem(val i: Int, val t: String)

    @Serializable
    private data class Req(val sid: String = "", val url: String = "", val items: List<ReqItem> = emptyList(), val ctx: List<String> = emptyList())

    @Serializable
    private data class ApplyItem(val t: String, val tr: String)

    inner class Bridge {
        /** 腳本第一次在這一話找到正文時呼叫：記進書架。 */
        @JavascriptInterface
        fun page(payload: String) {
            val info = runCatching { json.decodeFromString<PageInfo>(payload) }.getOrNull() ?: return
            if (!accept(info.sid, info.url)) return
            scope.launch { onPage(info) }
        }

        /** 長按譯文。 */
        @JavascriptInterface
        fun trMenu(payload: String) {
            val m = runCatching { json.decodeFromString<TrMenu>(payload) }.getOrNull() ?: return
            if (!accept(m.sid, m.url)) return
            scope.launch {
                val ch = chapterOf(m.url) ?: return@launch
                val key = app.cacheRepository.overrideKey(ch.site.id, ch.bookKey, ch.chapterKey, m.t, m.occ)
                _trEdit.value = TrEdit(m.sid, m.url, m.t, m.occ, m.tr, m.ctx, app.cacheRepository.getRaw(key) != null)
            }
        }

        /** 腳本要翻的段落（已去重、照網頁順序）。 */
        @JavascriptInterface
        fun request(payload: String) {
            val req = runCatching { json.decodeFromString<Req>(payload) }.getOrNull() ?: return
            if (req.items.isEmpty() || req.items.size > MAX_ITEMS_PER_REQUEST) return
            scope.launch {
                // 剛注入時 sid 可能還沒回到 App——等一下再判斷
                if (req.sid !in knownSids) delay(800)
                if (!accept(req.sid, req.url)) return@launch
                handle(req)
            }
        }

        @JavascriptInterface
        fun note(payload: String) {
            val n = runCatching { json.decodeFromString<Note>(payload) }.getOrNull() ?: return
            if (!accept(n.sid, n.url)) return
            onNote(n)
        }

        /** 網站介面翻譯（任何支援站的頁面都可以，不限章節頁）。 */
        @JavascriptInterface
        fun ui(payload: String) {
            val req = runCatching { json.decodeFromString<UiReq>(payload) }.getOrNull() ?: return
            if (req.items.isEmpty() || req.items.size > MAX_ITEMS_PER_REQUEST) return
            scope.launch {
                if (req.sid !in knownSids) delay(800)
                if (!acceptUi(req.sid, req.url) || !app.overlaySettings.uiTranslate) return@launch
                runCatching { handleUi(req) }.onFailure {
                    if (it is CancellationException) throw it
                    DebugLog.w("uiTrans", "介面翻譯失敗：${it.message?.take(120)}")
                }
            }
        }

        /** 腳本要翻的一張正文插圖（[ImgReq.data]＝網頁裡轉好的 JPEG data URL；空＝App 自己下載 [ImgReq.src]）。 */
        @JavascriptInterface
        fun image(payload: String) {
            val req = runCatching { json.decodeFromString<ImgReq>(payload) }.getOrNull() ?: return
            if (req.data.length > MAX_IMAGE_DATA) return
            scope.launch {
                if (req.sid !in knownSids) delay(800)
                if (!accept(req.sid, req.url)) return@launch
                handleImage(req)
            }
        }
    }

    @Serializable
    private data class Note(val sid: String = "", val url: String = "", val kind: String = "", val detail: String = "")

    @Serializable
    private data class UiReq(val sid: String = "", val url: String = "", val items: List<ReqItem> = emptyList())

    private var pagedNoticeShown = false

    /**
     * 自訂譯文字型：腳本在 document 宣告 @font-face，網址用**網站同源**的假路徑（避開網站的 CSP 限制），
     * 這裡攔下來回傳字型檔。只在支援站、而且使用者真的匯入了字型時才回應。
     */
    fun interceptFont(url: android.net.Uri): android.webkit.WebResourceResponse? {
        if (url.path != FONT_PATH) return null
        if (SiteRegistry.forUrl(url.toString()) == null) return null
        val f = app.overlaySettings.customFontFile
        if (!f.exists()) return null
        return android.webkit.WebResourceResponse("font/ttf", null, java.io.FileInputStream(f)).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400")
        }
    }

    /** 腳本回報的狀況（目前只有「偵測到翻頁模式」）。 */
    private fun onNote(n: Note) {
        when (n.kind) {
            "forceScroll" -> {
                DebugLog.i("paged", "翻頁模式→改為捲動模式並重新載入（${n.detail}）")
                pagedNoticeShown = true
                _status.update {
                    it.copy(message = "翻頁模式下譯文會讓頁數錯亂，已切換為捲動模式（音量鍵可一次捲一個畫面；可在設定關閉此功能）")
                }
                return
            }
            "forceScrollFailed" -> {
                DebugLog.w("paged", "改為捲動模式沒有生效（${n.detail}）")
                _status.update {
                    it.copy(message = "無法自動切換捲動模式：請在網站閱讀設定的「열람 방식」選「스크롤」")
                }
                return
            }
        }
        if (n.kind == "paged") {
            // 只有關掉「翻頁模式自動改成捲動」或自動切換沒生效時才會走到這裡
            DebugLog.i("overlay", "偵測到翻頁模式")
            if (pagedNoticeShown) return
            pagedNoticeShown = true
            _status.update {
                it.copy(message = "偵測到翻頁模式：譯文會讓網站算錯總頁數，翻到一半可能跳到最後一頁；建議在網站設定改用捲動（스크롤）模式。")
            }
        }
    }

    /** 網站介面翻譯：短字串、直接換字。整個 App 共用一份記憶體快取（選單字串跨頁重複很多）。 */
    private suspend fun handleUi(req: UiReq) {
        val cache = app.uiTextCache
        val texts = req.items.map { it.t }.distinct().filter { it.length <= 300 }
        val hits = synchronized(cache) { texts.mapNotNull { t -> cache[t]?.let { t to it } }.toMap() }
        if (hits.isNotEmpty()) uiApply(req.sid, hits)
        val todo = texts.filter { it !in hits }
        if (todo.isEmpty()) return
        val s = app.overlaySettings
        val failed = mutableListOf<String>()
        var googleError: String? = null
        if (s.uiEngine == UiEngine.GOOGLE_FREE) {
            todo.chunked(10).forEach { group ->
                val out = coroutineScope {
                    group.map { t ->
                        async {
                            t to runCatching { com.nekoyaku.ss.ai.GoogleFreeTranslator.translate(t) }.getOrElse {
                                if (it is CancellationException) throw it
                                if (googleError == null) googleError = it.message?.take(80)
                                null
                            }
                        }
                    }.awaitAll()
                }
                val ok = out.mapNotNull { (t, tr) -> tr?.let { t to app.translationService.toTraditional(it) } }.toMap()
                failed += out.filter { it.second == null }.map { it.first }
                synchronized(cache) { cache.putAll(ok) }
                uiApply(req.sid, ok)
            }
            // 被限流多半是短時間打太多：等一下、一條一條重試一次
            if (failed.isNotEmpty()) {
                delay(1500)
                val retry = failed.toList()
                failed.clear()
                retry.forEach { t ->
                    val tr = runCatching { com.nekoyaku.ss.ai.GoogleFreeTranslator.translate(t) }.getOrElse {
                        if (it is CancellationException) throw it
                        null
                    }
                    if (tr == null) {
                        failed += t
                    } else {
                        val ok = mapOf(t to app.translationService.toTraditional(tr))
                        synchronized(cache) { cache.putAll(ok) }
                        uiApply(req.sid, ok)
                    }
                    delay(250)
                }
            }
            // 還是失敗：有設定 AI 服務就用它補翻這幾條（介面字串很短，花費極少）
            val profile = app.aiProfileStore.selected
            if (failed.isNotEmpty() && profile != null) {
                val rest = failed.toList()
                val delivered = HashSet<Int>()
                runCatching {
                    app.translationService.translate(rest, profile, emptyList(), UI_PAGE_NOTE) { idx, translated ->
                        val ok = idx.indices.associate { k -> rest[idx[k]] to translated[k] }
                        synchronized(delivered) { delivered.addAll(idx) }
                        synchronized(cache) { cache.putAll(ok) }
                        uiApply(req.sid, ok)
                    }
                }.onFailure { if (it is CancellationException) throw it }
                DebugLog.i("uiTrans", "Google 免費翻譯失敗（${googleError ?: "原因不明"}），改用 AI 補翻 ${delivered.size}/${rest.size} 條")
                failed.clear()
                failed += rest.filterIndexed { i, _ -> i !in delivered }
            }
            if (failed.isNotEmpty()) {
                DebugLog.w("uiTrans", "Google 免費翻譯失敗 ${failed.size}/${todo.size} 條")
                if (failed.size == todo.size) {
                    _status.update { it.copy(message = "Google 免費翻譯沒有回應（可能被限流）；可到設定改用 AI 翻譯服務") }
                }
            }
        } else {
            val profile = app.aiProfileStore.selected ?: run {
                _status.update { it.copy(message = "網站介面翻譯：尚未設定 AI 翻譯服務") }
                return
            }
            val delivered = HashSet<Int>()
            app.translationService.translate(todo, profile, emptyList(), UI_PAGE_NOTE) { idx, translated ->
                val ok = idx.indices.associate { k -> todo[idx[k]] to translated[k] }
                synchronized(delivered) { delivered.addAll(idx) }
                synchronized(cache) { cache.putAll(ok) }
                uiApply(req.sid, ok)
            }
            failed += todo.filterIndexed { i, _ -> i !in delivered }
        }
        if (failed.isNotEmpty()) {
            js("window.__ny&&__ny.uiFail('${jsStr(req.sid)}',${safeJs(json.encodeToString(failed))})")
        }
    }

    private fun uiApply(sid: String, pairs: Map<String, String>) {
        if (pairs.isEmpty()) return
        val payload = safeJs(json.encodeToString(pairs.map { (t, tr) -> ApplyItem(t, tr) }))
        js("window.__ny&&__ny.uiApply('${jsStr(sid)}',$payload)")
    }

    @Serializable
    private data class ImgReq(val sid: String = "", val url: String = "", val i: Int = 0, val src: String = "", val data: String = "")

    /** 一次只讀一張圖：看圖翻譯又慢又貴，捲過一整排插圖時別同時全部送出去。 */
    private val imageGate = Mutex()

    private suspend fun handleImage(req: ImgReq) {
        val ch = chapterOf(req.url) ?: return
        val cache = app.cacheRepository
        val sid = req.sid
        fun applyImg(text: String) =
            js("window.__ny&&__ny.applyImg('${jsStr(sid)}',${req.i},${json.encodeToString(req.src)},${safeJs(json.encodeToString(text))})")
        fun failImg(msg: String) = js("window.__ny&&__ny.failImg('${jsStr(sid)}',${req.i},'${jsStr(msg.take(40))}')")

        // 一般網址用網址當 key；blob:（每次載入都不同）改用圖檔內容
        val imageId = if (req.src.startsWith("https://")) req.src.substringBefore('#') else req.data
        if (imageId.isBlank()) { failImg("讀不到圖片"); return }
        val key = cache.imageKey(ch.site.id, ch.bookKey, ch.chapterKey, imageId)
        cache.getRaw(key)?.let { applyImg(it); return }

        val profile = app.aiProfileStore.selected
        if (profile == null) { failImg("尚未設定 AI 服務"); return }
        _status.update { it.copy(translating = it.translating + 1, message = null) }
        try {
            val text = imageGate.withLock {
                cache.getRaw(key) ?: run {
                    val dataUrl = req.data.ifBlank {
                        withContext(Dispatchers.IO) { com.nekoyaku.ss.net.fetchImageAsDataUrl(req.src, req.url) }
                    }
                    val book = cache.getBook(ch.site.id, ch.bookKey)
                    val parsed = app.translationService.parseIllustrationJson(
                        app.translationService.translateIllustration(dataUrl, profile, book?.promptNote),
                    )
                    illustrationDisplay(parsed).also { cache.putRaw(key, it) }
                }
            }
            applyImg(text)
            _status.update { it.copy(translated = it.translated + 1) }
            DebugLog.i("overlay", "插圖文字：${if (text.isBlank()) "圖中沒有文字" else "${text.length} 字"}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DebugLog.w("overlay", "插圖翻譯失敗：${e.message?.take(160)}")
            failImg(e.message?.take(40) ?: "翻譯失敗")
            _status.update { it.copy(message = "插圖翻譯失敗：${e.message?.take(100)}（模型需支援圖片輸入）") }
        } finally {
            _status.update { it.copy(translating = (it.translating - 1).coerceAtLeast(0)) }
        }
    }

    /** 插圖譯文的顯示文字；空字串＝圖中沒有文字（網頁上不畫）。 */
    private fun illustrationDisplay(t: com.nekoyaku.ss.ai.TranslationService.IllustrationText): String {
        if (t.none) return ""
        return buildList {
            if (t.repeated.isNotBlank()) add(t.repeated)
            if (t.hidden.isNotBlank()) add("（強調字：${t.hidden}）")
            if (t.ending.isNotBlank()) add(t.ending)
        }.joinToString("\n")
    }

    /** 介面翻譯只要求頂層頁面在支援站上（不限章節頁）。 */
    private fun acceptUi(sid: String, url: String): Boolean {
        if (sid.isBlank() || sid !in knownSids) return false
        val site = SiteRegistry.forUrl(topUrl) ?: return false
        return site.matches(url)
    }

    /** 只接受：本 App 注入的腳本（sid 對得上）、且頂層頁面是支援站的章節頁。 */
    private fun accept(sid: String, url: String): Boolean {
        if (sid.isBlank() || sid !in knownSids) return false
        val top = topUrl
        val site = SiteRegistry.forUrl(top) ?: return false
        return site.isChapterUrl(top) && site.matches(url)
    }

    private suspend fun onPage(info: PageInfo) {
        val ch = runCatching { chapterOf(info.url) }.getOrNull() ?: return
        applyCached(ch, info.sid)
        // 站方 API 給的書名優先（kakaopage 網頁標題是「추천 | 카카오페이지」，不是書名）
        val official = ch.site.fetchTitles(info.url, CookieManager.getInstance().getCookie(info.url))
        val (bookTitle, chapterTitle) = official ?: ch.site.titlesOf(info.title, info.og)
        val book = app.cacheRepository.recordVisit(
            siteId = ch.site.id,
            bookKey = ch.bookKey,
            bookTitle = bookTitle,
            url = info.url,
            chapterKey = ch.chapterKey,
            chapterTitle = chapterTitle,
            authoritative = official != null,
        )
        DebugLog.i("overlay", "開啟 ${ch.site.id} 書=${ch.bookKey} 話=${ch.chapterKey} 段落=${info.count}")
        if (info.url == topUrl) _status.update { it.copy(title = book.aliasZh.ifBlank { book.bookTitle }) }
        // 書名自動翻譯：只填空白，不覆蓋使用者手填的
        val profile = app.aiProfileStore.selected
        if (book.aliasZh.isBlank() && book.bookTitle.isNotBlank() && profile != null &&
            app.overlaySettings.autoTitle && titleTried.add(ch.bookId)
        ) {
            val zh = app.translationService.translateTitle(book.bookTitle, profile, book.promptNote)
            if (!zh.isNullOrBlank()) {
                app.cacheRepository.updateBook(ch.site.id, ch.bookKey) { if (it.aliasZh.isBlank()) it.copy(aliasZh = zh) else it }
                if (info.url == topUrl) _status.update { it.copy(title = zh) }
            }
        }
    }

    /** 翻過的段落開頁就套上：只讀本機快取、不花 API 費用，所以不看「自動」開關。 */
    private suspend fun applyCached(ch: Chapter, sid: String) {
        val texts = collectPage()
        applyOverrides(ch, sid, texts)
        val hits = app.cacheRepository.lookup(ch.site.id, ch.bookKey, ch.chapterKey, texts)
        if (hits.isEmpty()) return
        applyToPage(listOf(sid), hits)
        _status.update { it.copy(fromCache = it.fromCache + hits.size) }
    }

    private suspend fun applyOverrides(ch: Chapter, sid: String, texts: Collection<String>) {
        val items = app.cacheRepository.overrides(ch.site.id, ch.bookKey, ch.chapterKey, texts)
            .map { (t, occ, tr) -> OvItem(t, occ, tr) }
        if (items.isNotEmpty()) sendOverrides(sid, items)
    }

    private fun sendOverrides(sid: String, items: List<OvItem>) =
        js("window.__ny&&__ny.applyOv('${jsStr(sid)}',${safeJs(json.encodeToString(items))})")

    /** 單段重翻：回傳新譯文給對話框，使用者按儲存才寫入。 */
    suspend fun retranslateOne(e: TrEdit): String {
        val profile = app.aiProfileStore.selected ?: error("尚未設定 AI 翻譯服務")
        val ch = chapterOf(e.url) ?: error("這一頁不是可疊翻的章節頁")
        val glossary = app.glossaryRepository.confirmedList(ch.bookId)
            .map { GlossaryTerm(it.term, it.translation, it.type, it.note) }
        val book = app.cacheRepository.getBook(ch.site.id, ch.bookKey)
        return app.translationService.translateOne(e.text, profile, glossary, book?.promptNote, e.context)
    }

    suspend fun saveOverride(e: TrEdit, translation: String) {
        val ch = chapterOf(e.url) ?: return
        app.cacheRepository.putRaw(app.cacheRepository.overrideKey(ch.site.id, ch.bookKey, ch.chapterKey, e.text, e.occurrence), translation)
        sendOverrides(e.sid, listOf(OvItem(e.text, e.occurrence, translation)))
    }

    suspend fun clearOverride(e: TrEdit) {
        val ch = chapterOf(e.url) ?: return
        app.cacheRepository.deleteRaw(app.cacheRepository.overrideKey(ch.site.id, ch.bookKey, ch.chapterKey, e.text, e.occurrence))
        sendOverrides(e.sid, listOf(OvItem(e.text, e.occurrence)))
    }

    fun dismissTrEdit() { _trEdit.value = null }

    /** 這一話有幾處單獨改過的譯文。 */
    suspend fun overrideCount(): Int {
        val ch = chapterOf(topUrl) ?: return 0
        return app.cacheRepository.countOverrides(ch.site.id, ch.bookKey, ch.chapterKey)
    }

    private suspend fun handle(req: Req) {
        val ch = chapterOf(req.url) ?: return
        val cache = app.cacheRepository
        val texts = req.items.map { it.t }.distinct()
        applyOverrides(ch, req.sid, texts)
        val hits = cache.lookup(ch.site.id, ch.bookKey, ch.chapterKey, texts)
        if (hits.isNotEmpty()) {
            applyToPage(listOf(req.sid), hits)
            _status.update { it.copy(fromCache = it.fromCache + hits.size) }
        }
        val misses = req.items.filter { it.t !in hits }
        if (misses.isEmpty()) return

        val profile = app.aiProfileStore.selected
        if (profile == null) {
            failOnPage(req.sid, misses.map { it.i }, "尚未設定 AI 服務")
            _status.update { it.copy(message = "尚未設定 AI 翻譯服務：請到「設定 → AI 翻譯服務」新增") }
            return
        }

        // 已經在翻的同段不再送，翻好時一併套到這個 sid
        val todo = synchronized(inFlightLock) {
            misses.filter { m ->
                val key = cache.keyFor(ch.site.id, ch.bookKey, ch.chapterKey, m.t)
                val waiting = inFlight[key]
                if (waiting != null) { waiting.add(req.sid); false } else { inFlight[key] = mutableSetOf(req.sid); true }
            }
        }
        if (todo.isEmpty()) return

        val job = currentCoroutineContext().job
        synchronized(inFlightLock) { chapterJobs.getOrPut(ch.gateKey) { mutableSetOf() }.add(job) }
        _status.update { it.copy(translating = it.translating + todo.size, message = null) }
        try {
            glossaryGateFor(ch, req.sid).await()
            val glossary = app.glossaryRepository.confirmedList(ch.bookId)
                .map { GlossaryTerm(it.term, it.translation, it.type, it.note) }
            val book = cache.getBook(ch.site.id, ch.bookKey)
            val paragraphs = todo.map { it.t }
            val delivered = HashSet<Int>()
            val tally = UsageTally()
            val err = app.translationService.translate(
                paragraphs = paragraphs,
                profile = profile,
                glossary = glossary,
                bookNote = book?.promptNote,
                tally = tally,
                leadingContext = req.ctx,
            ) { indices, translated ->
                val pairs = indices.indices.associate { k -> paragraphs[indices[k]] to translated[k] }
                synchronized(delivered) { delivered.addAll(indices) }
                scope.launch {
                    // 這一話已按「重新翻譯」：舊譯文不寫回
                    if (job.isCancelled) return@launch
                    cache.save(ch.site.id, ch.bookKey, ch.chapterKey, pairs)
                    applyToPage(finishWaiters(ch, pairs.keys), pairs)
                    _status.update { it.copy(translating = (it.translating - pairs.size).coerceAtLeast(0), translated = it.translated + pairs.size) }
                }
            }
            val failed = todo.filterIndexed { idx, _ -> idx !in delivered }
            if (failed.isNotEmpty()) {
                reportFailed(ch, failed, err ?: "翻譯失敗")
                _status.update { it.copy(message = "部分段落翻譯失敗：${friendlyError(err)}") }
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                releaseWaiters(ch, todo)
                throw e
            }
            DebugLog.e("overlay", "翻譯失敗：${e.message?.take(160)}", e)
            reportFailed(ch, todo, e.message ?: "翻譯失敗")
            _status.update { it.copy(message = "翻譯失敗：${friendlyError(e.message)}") }
        } finally {
            synchronized(inFlightLock) { chapterJobs[ch.gateKey]?.remove(job) }
        }
    }

    private fun finishWaiters(ch: Chapter, originals: Collection<String>): Set<String> = synchronized(inFlightLock) {
        buildSet {
            originals.forEach { t ->
                inFlight.remove(app.cacheRepository.keyFor(ch.site.id, ch.bookKey, ch.chapterKey, t))?.let { addAll(it) }
            }
        }
    }

    private fun releaseWaiters(ch: Chapter, items: List<ReqItem>) {
        finishWaiters(ch, items.map { it.t })
    }

    private fun reportFailed(ch: Chapter, items: List<ReqItem>, msg: String) {
        val sids = finishWaiters(ch, items.map { it.t })
        sids.forEach { sid -> failOnPage(sid, items.map { it.i }, msg) }
        _status.update { it.copy(translating = (it.translating - items.size).coerceAtLeast(0)) }
    }

    /**
     * 名詞預抽：每一話第一次要翻譯時，如果整話幾乎都還沒有譯文（＝第一次讀這話），
     * 先把整話正文送去抽專有名詞、自動加進名詞表，再開始翻——同一話各批的人名才會一致。
     */
    private suspend fun glossaryGateFor(ch: Chapter, sid: String): Deferred<Unit> = gateLock.withLock {
        glossaryGate[ch.gateKey]?.let { return@withLock it }
        val gate: Deferred<Unit> = if (!app.overlaySettings.autoGlossary) {
            CompletableDeferred(Unit)
        } else {
            scope.async {
                runCatching { extractGlossary(ch, force = false) }.onFailure {
                    if (it is CancellationException) throw it
                    DebugLog.w("overlay", "名詞預抽失敗（不影響翻譯）：${it.message?.take(120)}")
                }
                Unit
            }
        }
        glossaryGate[ch.gateKey] = gate
        gate
    }

    /** 收集這一話網頁上的全部段落原文（只在記憶體裡用，不存檔）。 */
    private suspend fun collectPage(): List<String> {
        val raw = evalJs("window.__ny?__ny.collect():'[]'") ?: return emptyList()
        return runCatching {
            val inner = json.decodeFromString<String>(raw)
            json.decodeFromString<List<String>>(inner)
        }.getOrDefault(emptyList())
    }

    /** 抽名詞；回傳新增幾個詞（null＝沒做）。 */
    private suspend fun extractGlossary(ch: Chapter, force: Boolean): Int? {
        val profile = app.aiProfileStore.selected ?: return null
        val all = collectPage()
        if (all.isEmpty()) return null
        if (!force) {
            val cached = app.cacheRepository.lookup(ch.site.id, ch.bookKey, ch.chapterKey, all)
            // 已經翻過不少段＝之前讀過這話，不再重抽
            if (cached.size * 5 > all.size) return null
        }
        _status.update { it.copy(extractingGlossary = true) }
        try {
            val book = app.cacheRepository.getBook(ch.site.id, ch.bookKey)
            val prior = app.glossaryRepository.confirmedMap(ch.bookId)
            val cands = app.translationService.extractGlossary(
                all, profile, prior, book?.promptNote, stableKnown = prior,
            ) { k, n -> _status.update { it.copy(extractStep = k, extractTotal = n) } }
            if (cands.isEmpty()) return 0
            val now = System.currentTimeMillis()
            app.glossaryRepository.autoAdoptAsConfirmed(
                ch.bookId,
                cands.map { GlossaryEntity(ch.bookId, it.term, it.zh, "", true, it.type, now) },
            )
            DebugLog.i("overlay", "名詞抽取：既有 ${prior.size} 詞，新增 ${cands.size} 詞")
            // AI 抽的詞直接當正式譯名用：要讓讀者知道，譯名怪怪的時才知道去名詞表改
            _status.update { it.copy(notice = "已自動把 ${cands.size} 個新名詞加進名詞表；譯名不對可以在名詞表修改。") }
            return cands.size
        } finally {
            _status.update { it.copy(extractingGlossary = false, extractStep = 0, extractTotal = 0) }
        }
    }

    /** 選單「從本話抽名詞」。 */
    suspend fun extractGlossaryNow(): String {
        val ch = chapterOf(topUrl) ?: return "這一頁不是可疊翻的章節頁"
        if (app.aiProfileStore.selected == null) return "尚未設定 AI 翻譯服務"
        return when (val n = runCatching { extractGlossary(ch, force = true) }.getOrElse {
            if (it is CancellationException) throw it
            return "名詞抽取失敗：${it.message?.take(80)}"
        }) {
            null -> "這一頁還沒找到正文"
            0 -> "沒有找到新的專有名詞"
            else -> "已加入 $n 個名詞到名詞表"
        }
    }

    /** 選單「重新翻譯本話」：清掉這一話的譯文快取，網頁上的譯文拿掉重來。 */
    suspend fun retranslateChapter(): String {
        val ch = chapterOf(topUrl) ?: return "這一頁不是可疊翻的章節頁"
        // 先停掉還在翻的批次：不然它們回來會把舊譯文寫回剛清空的快取，新請求也會被 inFlight 擋成「等舊的」
        val running = synchronized(inFlightLock) { chapterJobs.remove(ch.gateKey)?.toList().orEmpty() }
        running.forEach { it.cancel() }
        running.joinAll()
        _status.update { it.copy(translating = 0) }
        app.cacheRepository.clearChapter(ch.site.id, ch.bookKey, ch.chapterKey)
        js("window.__ny&&__ny.reset()")
        return "已清除本話譯文，重新翻譯中"
    }

    /** 目前這一話所屬的書（名詞表、書籍設定用）。 */
    suspend fun currentBookId(): String? = chapterOf(topUrl)?.bookId

    /** 「知道了」：先收掉錯誤訊息，沒有錯誤時收掉一般提示。 */
    fun clearMessage() = _status.update { if (it.message != null) it.copy(message = null) else it.copy(notice = null) }

    companion object {
        const val BRIDGE_NAME = "NekoYaku"
        const val FONT_PATH = "/__nekoyaku_font__/tr"

        /** 介面翻譯走 AI 時附的提示（經 bookNote 注入 system prompt）：要簡潔、不加戲。 */
        private const val UI_PAGE_NOTE =
            "以下段落是韓文網站的「介面文字」（選單、按鈕、標籤、作品與章節標題、價格說明），" +
                "逐條翻成簡潔自然的台灣繁體中文；數字、英文、符號原樣保留，不要添加任何解釋。"
        private const val EVAL_TIMEOUT_MS = 8_000L

        /** 單次請求的段落上限（腳本每 350ms 送一批，正常不會超過；超過＝異常請求，直接丟掉）。 */
        private const val MAX_ITEMS_PER_REQUEST = 400

        /** 網頁轉好的插圖 data URL 上限（1280px JPEG 正常幾百 KB）。 */
        private const val MAX_IMAGE_DATA = 4_000_000

        /** JSON 塞進 JS 原始碼前的保險：舊版 WebView 不認 JSON 裡的 U+2028／U+2029。 */
        fun safeJs(jsonText: String): String = jsonText.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

        /** 單引號字串內容跳脫。 */
        fun jsStr(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", " ").replace("\r", " ").replace("\u2028", " ").replace("\u2029", " ")
    }
}
