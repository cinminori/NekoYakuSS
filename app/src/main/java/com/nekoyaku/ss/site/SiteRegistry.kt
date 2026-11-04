package com.nekoyaku.ss.site

/** 依 URL 挑出對應的站別設定。 */
object SiteRegistry {
    private val sites: List<OverlaySite> = listOf(
        KakaopageSite,
        RidiSite,
        BomtoonSite,
        MrBlueSite,
    )

    fun forUrl(url: String): OverlaySite? = sites.firstOrNull { it.matches(url) }

    fun byId(id: String): OverlaySite? = sites.firstOrNull { it.id == id }

    fun all(): List<OverlaySite> = sites
}
