package dev.steamshelf.data

import dev.steamshelf.data.steam.SteamIdInput
import dev.steamshelf.data.steam.SteamOpenId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamIdInputTest {

    @Test
    fun `parses ids, profile urls and vanity names`() {
        assertEquals(SteamIdInput.Id("76561197960287930"), SteamIdInput.parse(" 76561197960287930 "))
        assertEquals(
            SteamIdInput.Id("76561197960287930"),
            SteamIdInput.parse("https://steamcommunity.com/profiles/76561197960287930/"),
        )
        assertEquals(SteamIdInput.Vanity("gabelogannewell"), SteamIdInput.parse("https://steamcommunity.com/id/gabelogannewell/"))
        assertEquals(SteamIdInput.Vanity("gabelogannewell"), SteamIdInput.parse("steamcommunity.com/id/gabelogannewell"))
        assertEquals(SteamIdInput.Vanity("some_name"), SteamIdInput.parse("some_name"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(SteamIdInput.parse(""))
        assertNull(SteamIdInput.parse("hello world"))
    }

    @Test
    fun `openid login url points back at the intercepted callback`() {
        val url = SteamOpenId.loginUrl
        assertTrue(url.startsWith("https://steamcommunity.com/openid/login?"))
        assertTrue(url.contains("openid.mode=checkid_setup"))
        assertTrue(SteamOpenId.isCallback("https://steamshelf.invalid/openid/callback?openid.mode=id_res"))
        assertFalse(SteamOpenId.isCallback("https://steamcommunity.com/login/home/"))
    }
}
