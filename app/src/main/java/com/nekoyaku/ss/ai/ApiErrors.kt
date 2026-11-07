package com.nekoyaku.ss.ai

private val HTTP_CODE = Regex("HTTP (\\d{3})")

/** 把 HTTP 錯誤翻成白話；看不懂的狀況保留原訊息方便回報。設定頁與閱讀頁共用。 */
fun friendlyApiError(e: Throwable): String {
    val message = e.message.orEmpty()
    if (e is java.io.IOException && HTTP_CODE.find(message) == null) return NETWORK_ERROR_HINT
    return friendlyApiError(message)
}

fun friendlyApiError(message: String): String {
    val code = HTTP_CODE.find(message)?.groupValues?.get(1)?.toInt() ?: return message.take(200)
    val hint = when {
        code == 401 || code == 403 -> "API Key 不正確或沒有權限，請重新複製一次。"
        code == 402 || TranslationService.isOutOfCredit(message) -> "帳戶餘額不足，請到服務網站儲值。"
        code == 400 -> "服務拒絕了這次請求，請確認 API Key 與模型是否正確。"
        code == 404 -> "找不到這個模型或網址，請重新選擇模型。"
        code == 429 -> "超過用量限制或額度已用完，請稍後再試，或到服務網站查看額度。"
        code >= 500 -> "服務暫時有問題，請稍後再試。"
        else -> "連線失敗。"
    }
    return "$hint（HTTP $code）"
}

const val NETWORK_ERROR_HINT = "連不上服務，請檢查網路或網址。"
