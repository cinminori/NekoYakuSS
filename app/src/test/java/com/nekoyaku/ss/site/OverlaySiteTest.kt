package com.nekoyaku.ss.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySiteTest {
    @Test
    fun overlayConfigsAreValidJson() {
        SiteRegistry.all().forEach { site ->
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(site.overlayConfigJson) as kotlinx.serialization.json.JsonObject
            assertTrue(site.id, obj.containsKey("paras"))
        }
        val kp = kotlinx.serialization.json.Json.parseToJsonElement(KakaopageSite.overlayConfigJson) as kotlinx.serialization.json.JsonObject
        assertTrue(kp.containsKey("scrollFix"))
    }

    @Test
    fun kakaopageViewerUrl() {
        val url = "https://page.kakao.com/content/58743922/viewer/65191034"
        assertEquals(KakaopageSite, SiteRegistry.forUrl(url))
        assertTrue(KakaopageSite.isChapterUrl(url))
        assertTrue(KakaopageSite.isChapterUrl("$url/"))
        assertEquals("58743922", KakaopageSite.bookKeyOf(url))
        assertEquals("65191034", KakaopageSite.chapterKeyOf(url))
        assertFalse(KakaopageSite.isChapterUrl("https://page.kakao.com/content/58743922"))
    }

    @Test
    fun ridiViewUrlNeedsResolver() {
        val url = "https://ridibooks.com/books/1234567/view"
        assertEquals(RidiSite, SiteRegistry.forUrl(url))
        assertTrue(RidiSite.isChapterUrl(url))
        assertNull(RidiSite.bookKeyOf(url))
        assertEquals("1234567", RidiSite.chapterKeyOf(url))
        assertFalse(RidiSite.isChapterUrl("https://ridibooks.com/books/1234567"))
    }

    @Test
    fun ridiSeriesKeyIsSmallestId() {
        val html = """<a href="/books/900/view">이어보기</a><a href="/books/120/view">1화</a>
            <a href="/books/130/view">2화</a><a href="/books/900/view">14화</a>"""
        val (key, ids) = RidiSite.seriesKeyFromDetail(html)!!
        assertEquals("120", key)
        assertEquals(setOf("120", "130", "900"), ids)
        assertNull(RidiSite.seriesKeyFromDetail("<html></html>"))
        // 新版作品頁只有本話自己的連結：不能當系列
        assertNull(RidiSite.seriesKeyFromDetail("<a href=\"/books/120116693/view\">보기</a>"))
    }

    @Test
    fun ridiSeriesIdFromEmbeddedData() {
        val html = "{\"recommend\":[{\"bookId\":\"999\",\"series\":{\"id\":\"111\"}}]," +
            "\"book\":{\"bookId\":\"120116693\",\"title\":\"x\",\"series\":{\"id\":\"555\",\"title\":\"y\"}}}"
        assertEquals("555", RidiSite.seriesIdFromDetail(html, "120116693"))
        assertEquals("777", RidiSite.seriesIdFromDetail("{\"seriesId\":777}", "1"))
        assertNull(RidiSite.seriesIdFromDetail("{\"title\":\"x\"}", "1"))
        assertEquals("s555", RidiSite.seriesKey("555"))
    }

    @Test
    fun ridiSeriesIdFromBookApi() {
        assertEquals("3214", RidiSite.seriesIdFromBookApi("{\"id\":\"120116693\",\"series\":{\"id\":\"3214\",\"volume\":3}}"))
        assertNull(RidiSite.seriesIdFromBookApi("{\"id\":\"120116693\"}"))
    }

    @Test
    fun bomtoonAndMrblue() {
        assertTrue(BomtoonSite.isChapterUrl("https://www.bomtoon.com/viewer/EC-230/1"))
        assertEquals("EC-230", BomtoonSite.bookKeyOf("https://www.bomtoon.com/viewer/EC-230/1"))
        assertTrue(MrBlueSite.isChapterUrl("https://viewer.mrblue.com/ebook/E000168615/1"))
        assertFalse(MrBlueSite.isChapterUrl("https://www.mrblue.com/ebook/E000168615/1"))
        assertEquals("E000168615", MrBlueSite.bookKeyOf("https://viewer.mrblue.com/ebook/E000168615/3"))
        assertEquals("3", MrBlueSite.chapterKeyOf("https://viewer.mrblue.com/ebook/E000168615/3"))
    }

    @Test
    fun rejectsNonHttpsAndLookalikeHosts() {
        assertNull(SiteRegistry.forUrl("http://page.kakao.com/content/1/viewer/2"))
        assertNull(SiteRegistry.forUrl("https://page.kakao.com.evil.example/content/1/viewer/2"))
        assertFalse(KakaopageSite.isChapterUrl("https://evil.example/content/1/viewer/2"))
    }

    @Test
    fun titlesStripEpisodeSuffix() {
        assertEquals("테스트 작품" to "테스트 작품 3화", KakaopageSite.titlesOf("", "테스트 작품 3화"))
        assertEquals("작품" to "작품 제12화. 부제", RidiSite.titlesOf("작품 제12화. 부제 - 리디", ""))
        assertEquals("윈터필드" to "윈터필드 [단행본] / 2권", MrBlueSite.titlesOf("윈터필드 [단행본] / 2권 | 미스터블루", ""))
    }
}
