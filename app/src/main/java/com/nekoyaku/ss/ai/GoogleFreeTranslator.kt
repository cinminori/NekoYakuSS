package com.nekoyaku.ss.ai

import com.nekoyaku.ss.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/**
 * 免費 Google 翻譯（非官方 gtx 端點，免金鑰）。**只給內建瀏覽器「譯頁」用**——逛站看個大概零成本；
 * 正文閱讀／圖片翻譯仍走 LLM。非官方端點 Google 隨時可能關閉或限流，壞掉就切回 LLM profile。
 * 同款端點在 LensLay／漫畫翻譯擴充等專案長年實用。
 */
object GoogleFreeTranslator {
    // 併發上限：對免費端點客氣點，避免觸發限流（429 就是玩太兇）
    private val slots = Semaphore(3)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
    )

    /** 單條翻譯（自動偵測來源語 → 台灣繁中）。失敗拋例外，由呼叫端決定略過或重試。 */
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        slots.withPermit {
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=zh-TW&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
            // 不走 Http.get：它每請求記 2 行 log，一次譯頁 ~180 條×2＝灌爆 400 行除錯紀錄
            // （0.4.28 實錄：DeepL 的失敗訊息全被洗掉、無從診斷）。這裡直接打、不記逐條 log。
            val req = okhttp3.Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val body = Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("gtx HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            // 回應格式：[[["譯句1","原句1",...],["譯句2",...]], null, "ko", ...] → 串接各句譯文
            val segs = JSONArray(body).getJSONArray(0)
            buildString {
                for (i in 0 until segs.length()) {
                    val piece = segs.optJSONArray(i)?.optString(0)
                    if (!piece.isNullOrEmpty()) append(piece)
                }
            }.trim().ifEmpty { error("gtx 空回應") }
        }
    }
}
