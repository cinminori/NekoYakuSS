package com.nekoyaku.ss.site

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSecurityTest {
    @Test
    fun acceptsOnlyExactOfficialHttpsOrigin() {
        assertNotNull(UrlSecurity.parseOfficialUrl("https://www.bomtoon.com/viewer/EC-230/1", "www.bomtoon.com"))
        assertNotNull(UrlSecurity.parseOfficialUrl("https://www.bomtoon.com:443/viewer/EC-230/1", "www.bomtoon.com"))
        assertNull(UrlSecurity.parseOfficialUrl("http://www.bomtoon.com/viewer/EC-230/1", "www.bomtoon.com"))
        assertNull(UrlSecurity.parseOfficialUrl("https://evilbomtoon.com/viewer/EC-230/1", "www.bomtoon.com"))
        assertNull(UrlSecurity.parseOfficialUrl("https://www.bomtoon.com.example.org/viewer/EC-230/1", "www.bomtoon.com"))
        assertNull(UrlSecurity.parseOfficialUrl("https://www.bomtoon.com:8443/viewer/EC-230/1", "www.bomtoon.com"))
    }

    @Test
    fun redirectsMustRemainOnSameOrigin() {
        val origin = "https://www.bomtoon.com/viewer/EC-230/1".toHttpUrl()
        assertTrue(UrlSecurity.isSameOrigin(origin, "https://www.bomtoon.com/viewer/EC-230/2".toHttpUrl()))
        assertFalse(UrlSecurity.isSameOrigin(origin, "https://bomtoon.com/viewer/EC-230/2".toHttpUrl()))
        assertFalse(UrlSecurity.isSameOrigin(origin, "https://www.bomtoon.com:8443/viewer/EC-230/2".toHttpUrl()))
        assertFalse(UrlSecurity.isSameOrigin(origin, "http://www.bomtoon.com/viewer/EC-230/2".toHttpUrl()))
    }
}
