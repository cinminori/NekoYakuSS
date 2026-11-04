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
    val req = okhttp3.Request.Builder()
        .url(src)
        .apply {
            CookieManager.getInstance().getCookie(src)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
            if (referer.startsWith("http")) header("Referer", referer)
        }
        .build()
    Http.client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("圖片下載失敗 HTTP ${resp.code}")
        val bytes = resp.body?.bytes() ?: error("圖片下載失敗（空回應）")
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("圖片解碼失敗")
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
}
