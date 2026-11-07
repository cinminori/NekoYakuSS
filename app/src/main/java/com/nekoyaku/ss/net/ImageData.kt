package com.nekoyaku.ss.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.CookieManager

/**
 * 下載圖片→縮到長邊 [maxSide]→JPEG 80 data URL（送 vision 模型用）。阻塞呼叫，請在 IO 執行緒跑。
 * 帶 WebView 的 cookie（站內瀏覽器看得到的圖，這裡也拿得到）與 [referer]。
 */
fun fetchImageAsDataUrl(src: String, referer: String, maxSide: Int = 1280): String {
    if (src.startsWith("data:image")) return src
    require(src.startsWith("https://")) { "只支援 HTTPS 圖片" }
    val bytes = download(src, referer)
    // 先只讀尺寸，用 inSampleSize 解成接近 maxSide 的大小：長條圖整張解碼動輒幾十 MB 會 OOM
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("圖片解碼失敗")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("圖片解碼失敗")
    val longest = maxOf(bmp.width, bmp.height).toFloat()
    if (longest > maxSide) {
        val s = maxSide / longest
        bmp = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * s).toInt().coerceAtLeast(1),
            (bmp.height * s).toInt().coerceAtLeast(1),
            true,
        )
    }
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

private val noRedirectClient by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

/** 自己跟轉址：每一站只帶那個網址自己的 cookie（OkHttp 自動轉址會把 Cookie 標頭原樣帶到別的網域）。 */
private fun download(src: String, referer: String): ByteArray {
    var url = src
    repeat(5) {
        val req = okhttp3.Request.Builder()
            .url(url)
            .apply {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                if (referer.startsWith("http")) header("Referer", referer)
            }
            .build()
        noRedirectClient.newCall(req).execute().use { resp ->
            if (resp.isRedirect) {
                val next = resp.header("Location")?.let { resp.request.url.resolve(it) } ?: error("圖片下載失敗（轉址沒有目標）")
                require(next.isHttps) { "只支援 HTTPS 圖片" }
                url = next.toString()
                return@repeat
            }
            if (!resp.isSuccessful) error("圖片下載失敗 HTTP ${resp.code}")
            return resp.body?.bytes() ?: error("圖片下載失敗（空回應）")
        }
    }
    error("圖片下載失敗（轉址太多次）")
}
