package dev.steamshelf.data

import dev.steamshelf.data.hltb.HltbGame
import dev.steamshelf.data.hltb.HltbMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbMatcherTest {

    @Test
    fun `search query drops trademarks and edition suffixes`() {
        assertEquals("DARK SOULS III", HltbMatcher.searchQuery("DARK SOULS™ III"))
        assertEquals("The Witcher 3: Wild Hunt", HltbMatcher.searchQuery("The Witcher 3: Wild Hunt - Game of the Year Edition"))
        assertEquals("Divinity: Original Sin", HltbMatcher.searchQuery("Divinity: Original Sin - Enhanced Edition"))
        assertEquals("Tomb Raider", HltbMatcher.searchQuery("Tomb Raider GOTY Edition"))
        assertEquals("Sid Meier's Civilization VI", HltbMatcher.searchQuery("Sid Meier's Civilization® VI"))
        assertEquals("Hollow Knight", HltbMatcher.searchQuery("Hollow Knight"))
    }

    @Test
    fun `search query never becomes empty`() {
        assertEquals("Gold", HltbMatcher.searchQuery("Gold"))
    }

    @Test
    fun `fallback queries loosen the name step by step`() {
        assertEquals(
            listOf("warhammer 40 000 space marine 2", "Warhammer 40,000: Space Marine", "Warhammer 40,000"),
            HltbMatcher.fallbackQueries("Warhammer 40,000: Space Marine 2"),
        )
        // Same words as the main query (just lowercased) aren't searched twice.
        assertEquals(listOf("Grand Theft Auto V"), HltbMatcher.fallbackQueries("Grand Theft Auto V Legacy"))
        assertEquals(emptyList<String>(), HltbMatcher.fallbackQueries("Hades"))
    }

    @Test
    fun `prefers the base game over its DLC`() {
        val candidates = listOf(
            hltb(1, "Hollow Knight - The Grimm Troupe", type = "dlc", year = 2017),
            hltb(2, "Hollow Knight", type = "game", year = 2017),
            hltb(3, "Hollow Knight: Silksong", type = "game", year = 2025),
        )
        val best = HltbMatcher.bestMatch("Hollow Knight", 2017, candidates)
        assertEquals(2, best?.first?.id)
        assertTrue(best!!.second >= HltbMatcher.CONFIDENT_SIMILARITY)
    }

    @Test
    fun `release year separates a remake from the original`() {
        val candidates = listOf(
            hltb(10, "Resident Evil 4", year = 2005),
            hltb(11, "Resident Evil 4", year = 2023),
        )
        assertEquals(11, HltbMatcher.bestMatch("Resident Evil 4", 2023, candidates)?.first?.id)
        assertEquals(10, HltbMatcher.bestMatch("Resident Evil 4", 2005, candidates)?.first?.id)
    }

    @Test
    fun `edition names still match the base entry`() {
        val candidates = listOf(hltb(5, "The Witcher 3: Wild Hunt", year = 2015))
        val best = HltbMatcher.bestMatch("The Witcher 3: Wild Hunt - Game of the Year Edition", 2016, candidates)
        assertEquals(5, best?.first?.id)
    }

    @Test
    fun `a renamed re-release still finds the base game, flagged as fuzzy`() {
        val disco = HltbMatcher.bestMatch("Disco Elysium - The Final Cut", 2021, listOf(hltb(9, "Disco Elysium", year = 2019)))
        assertEquals(9, disco?.first?.id)
        assertTrue(disco!!.second < HltbMatcher.CONFIDENT_SIMILARITY)

        val gta = HltbMatcher.bestMatch("Grand Theft Auto V Legacy", 2015, listOf(hltb(12, "Grand Theft Auto V", year = 2013)))
        assertEquals(12, gta?.first?.id)
    }

    @Test
    fun `unrelated results are rejected`() {
        val candidates = listOf(hltb(7, "Wallpaper Wars"), hltb(8, "The Engine Room"))
        assertNull(HltbMatcher.bestMatch("Wallpaper Engine", null, candidates))
    }

    private fun hltb(id: Int, name: String, type: String = "game", year: Int = 0) =
        HltbGame(id = id, name = name, type = type, releaseYear = year, main = 3600)
}
