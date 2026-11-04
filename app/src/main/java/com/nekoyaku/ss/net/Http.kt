package com.nekoyaku.ss.net

import com.nekoyaku.ss.site.UrlSecurity
import com.nekoyaku.ss.util.DebugLog
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 共用 OkHttp client。bomtoon SSR 抽文走這裡（帶登入 cookie）。 */
object Http {
    // 用行動版 Chrome UA：部分站點會依 UA 吐不同版型，貼近實機抓到的環境
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Mobile Safari/537.36"

    // LLM 用（TranslationService chat completions）：claude proxy 慢，一批最久給到 4 分鐘
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    // 站點抓取用（get()：章節/詳情頁/抓模型）：逾時收緊——訊號差時一個請求掛 4 分鐘
    // ＝已快取的章「看起來打不開」（v0.5.3 出門離線實案）。站點請求 30 秒等不到就該放手。
    private val siteClient: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val noRedirectClient: OkHttpClient = siteClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** GET 回傳 body 字串；非 2xx 拋例外。 */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val t0 = System.currentTimeMillis()
        val hasCookie = headers.keys.any { it.equals("Cookie", ignoreCase = true) }
        DebugLog.d("http", "GET $url cookie=$hasCookie")

        return try {
            if (hasCookie) getWithProtectedCookie(url, headers, t0) else getPlain(url, headers, t0)
        } catch (e: Exception) {
            val dt = System.currentTimeMillis() - t0
            DebugLog.e("http", "GET 失敗 ${dt}ms url=$url", e)
            throw e
        }
    }

    private fun getPlain(url: String, headers: Map<String, String>, startedAt: Long): String {
        val parsed = url.toHttpUrlOrNull() ?: error("網址格式錯誤：$url")
        siteClient.newCall(buildGet(parsed, headers)).execute().use { resp ->
            return readBodyOrThrow(
                resp.code,
                resp.isSuccessful,
                resp.body?.string().orEmpty(),
                resp.request.url,
                startedAt,
            )
        }
    }

    /** 手動 Cookie 只能留在原始 HTTPS host；不可讓 OkHttp 在跨站 redirect 時沿用。 */
    private fun getWithProtectedCookie(
        url: String,
        headers: Map<String, String>,
        startedAt: Long,
    ): String {
        val origin = url.toHttpUrlOrNull() ?: error("網址格式錯誤：$url")
        require(origin.isHttps) { "帶登入 Cookie 的請求只允許 HTTPS" }
        var current: HttpUrl = origin
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            var next: HttpUrl? = null
            noRedirectClient.newCall(buildGet(current, headers)).execute().use { resp ->
                if (resp.code in REDIRECT_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) error("重新導向次數過多：$url")
                    val location = resp.header("Location") ?: error("重新導向缺少 Location：$url")
                    val target = current.resolve(location) ?: error("無效重新導向：$location")
                    if (!UrlSecurity.isSameOrigin(origin, target)) {
                        DebugLog.e("http", "拒絕攜帶 Cookie 跨站 redirect ${origin.host} → ${target.host}")
                        error("登入請求被重新導向到非官方網域：${target.host}")
                    }
                    next = target
                } else {
                    return readBodyOrThrow(
                        resp.code,
                        resp.isSuccessful,
                        resp.body?.string().orEmpty(),
                        current,
                        startedAt,
                    )
                }
            }
            current = next ?: error("重新導向處理失敗：$url")
        }
        error("重新導向次數過多：$url")
    }

    private fun buildGet(url: HttpUrl, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun readBodyOrThrow(
        code: Int,
        successful: Boolean,
        body: String,
        url: HttpUrl,
        startedAt: Long,
    ): String {
        val dt = System.currentTimeMillis() - startedAt
        if (!successful) {
            DebugLog.e("http", "GET $code ${dt}ms body=${body.length}B url=$url")
            error("HTTP $code：$url")
        }
        DebugLog.d("http", "GET $code ${dt}ms body=${body.length}B")
        return body
    }

    private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    private const val MAX_REDIRECTS = 5
}
