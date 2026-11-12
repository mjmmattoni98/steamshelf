package dev.steamshelf.data

import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.hltb.HltbMatcher
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.data.steam.SteamApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Hits the real services, so it's skipped unless LIVE_API_TESTS=1. Run it when HowLongToBeat or Steam
 * changes something:  LIVE_API_TESTS=1 ./gradlew testDebugUnitTest --tests '*LiveApiTest*'
 */
class LiveApiTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val http = OkHttpClient()

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("LIVE_API_TESTS") == "1")

    @Test
    fun `hltb search and matching`() = runBlocking {
        val hltb = HltbApi(http, json)
        val cases = mapOf(
            "Hollow Knight" to "Hollow Knight",
            "DARK SOULS™ III" to "Dark Souls III",
            "The Witcher® 3: Wild Hunt" to "The Witcher 3: Wild Hunt",
            "Hades" to "Hades",
            "Portal 2" to "Portal 2",
            "Disco Elysium - The Final Cut" to "Disco Elysium",
            "Grand Theft Auto V Legacy" to "Grand Theft Auto V",
        )
        for ((steamName, expected) in cases) {
            var best = HltbMatcher.bestMatch(steamName, null, hltb.search(HltbMatcher.searchQuery(steamName)))
            for (query in HltbMatcher.fallbackQueries(steamName)) {
                if (best != null) break
                best = HltbMatcher.bestMatch(steamName, null, hltb.search(query))
            }
            println("$steamName -> ${best?.first?.name} (${best?.second}) main=${best?.first?.main}")
            assertEquals(steamName, expected, best?.first?.name)
            assertTrue(best!!.first.main > 0)
        }
    }

    @Test
    fun `hltb rediscovers a renamed search endpoint`() = runBlocking {
        val hltb = HltbApi(http, json, initialSearchPath = "/api/this-path-does-not-exist")
        val results = hltb.search("Celeste")
        println("rediscovered search returned ${results.size} results: ${results.take(3).map { it.name }}")
        assertTrue(results.any { it.name == "Celeste" })
    }

    @Test
    fun `protondb summaries`() = runBlocking {
        val proton = ProtonDbApi(http, json)
        val hk = proton.summary(367520, 0)
        println("Hollow Knight ProtonDB: $hk")
        assertNotNull(hk.tier)
        assertNull(proton.summary(1, 0).tier)
    }

    @Test
    fun `steam store batch and tags`() = runBlocking {
        val steam = SteamApi(http, json)
        val items = steam.getStoreItems(listOf(367520, 1245620, 999999999), "US", 0)
        items.forEach { println(it) }
        val hk = items.first { it.appId == 367520 }
        assertTrue(hk.found && hk.linux && hk.deckCompat == 3 && hk.tagIds.isNotEmpty())
        assertNotNull(hk.headerImage)
        assertFalse(items.first { it.appId == 999999999 }.found)
        assertTrue(steam.getTagNames().size > 100)
    }
}
