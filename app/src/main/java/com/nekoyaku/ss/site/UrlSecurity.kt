package com.nekoyaku.ss.site

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** URL 信任邊界：站別 adapter 只能接收 HTTPS 且 host 完全相符的網址。 */
object UrlSecurity {
    fun parseOfficialUrl(url: String, expectedHost: String): HttpUrl? =
        url.toHttpUrlOrNull()?.takeIf {
            it.isHttps &&
                it.port == HTTPS_PORT &&
                it.host.equals(expectedHost, ignoreCase = true)
        }

    fun isSameOrigin(origin: HttpUrl, target: HttpUrl): Boolean =
        target.scheme == origin.scheme &&
            target.port == origin.port &&
            target.host.equals(origin.host, ignoreCase = true)

    private const val HTTPS_PORT = 443
}
