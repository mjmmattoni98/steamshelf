package dev.steamshelf.data

import dev.steamshelf.data.steam.SteamApi
import dev.steamshelf.data.steam.SteamApiException
import dev.steamshelf.model.OwnedGame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SteamApiTest {
    private val server = MockWebServer()
    private lateinit var api: SteamApi

    @Before
    fun setUp() {
        server.start()
        api = SteamApi(OkHttpClient(), testJson, server.baseUrl)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `parses a recorded owned games response`() = runTest {
        server.enqueue(jsonResponse(fixture("steam/owned_games.json")))

        val games = api.getOwnedGames("KEY", "76561197960287930", includeFreeGames = false)

        assertEquals(
            listOf(
                OwnedGame(367520, "Hollow Knight", playtimeForever = 2710, lastPlayed = 1718000000, playtimeLinux = 2590, playtimeDeck = 1800),
                OwnedGame(620, "Portal 2", playtimeForever = 610, playtime2Weeks = 95, lastPlayed = 1731000000),
                // Steam sometimes leaves out the name of delisted apps.
                OwnedGame(1245620, "App 1245620"),
            ),
            games,
        )
        val request = server.takeRequest()
        assertEquals("/IPlayerService/GetOwnedGames/v1/", request.url.encodedPath)
        assertEquals("KEY", request.url.queryParameter("key"))
        assertEquals("1", request.url.queryParameter("include_appinfo"))
        assertEquals("0", request.url.queryParameter("include_played_free_games"))
    }

    @Test
    fun `a response without games means the profile is private`() {
        server.enqueue(jsonResponse(fixture("steam/owned_games_private.json")))

        val error = assertThrows(SteamApiException::class.java) {
            runBlocking { api.getOwnedGames("KEY", "76561197960287930", includeFreeGames = true) }
        }
        assertTrue(error.message!!.contains("Game details"))
    }

    @Test
    fun `a rejected key is reported as such`() {
        server.enqueue(MockResponse(code = 403))

        val error = assertThrows(SteamApiException::class.java) {
            runBlocking { api.getOwnedGames("KEY", "76561197960287930", includeFreeGames = true) }
        }
        assertTrue(error.message!!.contains("API key"))
    }

    @Test
    fun `parses recorded store items, including an app the store doesn't know`() = runTest {
        server.enqueue(jsonResponse(fixture("steam/get_items.json")))

        val items = api.getStoreItems(listOf(367520, 999999999), "DE", now = 42, language = "german")

        assertEquals(listOf(367520, 999999999), items.map { it.appId })
        val hollowKnight = items[0]
        assertTrue(hollowKnight.found)
        assertEquals(42L, hollowKnight.fetchedAt)
        assertTrue(hollowKnight.linux && hollowKnight.windows)
        assertEquals(3, hollowKnight.deckCompat)
        assertTrue(hollowKnight.tagIds.isNotEmpty())
        assertTrue(2 in hollowKnight.playerCategoryIds)
        assertNotNull(hollowKnight.releaseDate)
        assertNotNull(hollowKnight.reviewPercent)
        assertTrue(hollowKnight.headerImage!!.startsWith("https://shared.akamai.steamstatic.com/store_item_assets/"))
        assertEquals("german", hollowKnight.language)

        val unknown = items[1]
        assertFalse(unknown.found)
        assertEquals(999999999, unknown.appId)

        val input = server.takeRequest().url.queryParameter("input_json")!!
        assertTrue(input.contains("\"language\":\"german\""))
        assertTrue(input.contains("\"country_code\":\"DE\""))
    }

    @Test
    fun `tag names are requested in the given language`() = runTest {
        server.enqueue(jsonResponse("""{"response":{"version_hash":"1","tags":[{"tagid":19,"name":"Action"},{"tagid":492,"name":"Indie"}]}}"""))

        assertEquals(mapOf(19 to "Action", 492 to "Indie"), api.getTagNames("french"))
        assertEquals("french", server.takeRequest().url.queryParameter("language"))
    }
}
