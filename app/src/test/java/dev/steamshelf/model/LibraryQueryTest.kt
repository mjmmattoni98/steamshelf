package dev.steamshelf.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {

    private val now = 1_700_000_000_000L

    private val portal = item(400, "Portal", minutes = 300, deck = 3, tier = ProtonTier.PLATINUM, mainHours = 3.0, tags = listOf(1, 2))
    private val witcher = item(292030, "The Witcher® 3: Wild Hunt", minutes = 0, deck = 3, tier = ProtonTier.GOLD, mainHours = 51.0, tags = listOf(2))
    private val borked = item(10, "Borked Game", minutes = 60, deck = 1, tier = ProtonTier.BORKED, mainHours = null)
    private val native = item(20, "Native Game", minutes = 10_000, deck = 2, tier = null, mainHours = 20.0, linux = true)
    private val all = listOf(portal, witcher, borked, native)

    @Test
    fun `search ignores case and trademark symbols`() {
        assertEquals(listOf(witcher), LibraryQuery.apply(all, LibraryFilter(), "witcher 3", SortOption()))
    }

    @Test
    fun `options in one group are OR-ed, groups are AND-ed`() {
        val filter = LibraryFilter(
            proton = setOf(ProtonFilter.PLATINUM, ProtonFilter.NATIVE),
            playStates = setOf(PlayStateFilter.BEATEN),
        )
        // Portal: 5 h played vs 3 h main -> beaten; Native: 166 h vs 20 h -> beaten. Witcher is Gold.
        assertEquals(listOf("Native Game", "Portal"), names(LibraryQuery.apply(all, filter, "", SortOption())))
    }

    @Test
    fun `length buckets use HLTB time and can select missing data`() {
        assertEquals(listOf("Portal"), names(LibraryQuery.apply(all, LibraryFilter(length = setOf(LengthFilter.SHORT)), "", SortOption())))
        assertEquals(listOf("Borked Game"), names(LibraryQuery.apply(all, LibraryFilter(length = setOf(LengthFilter.UNKNOWN)), "", SortOption())))
    }

    @Test
    fun `tags must all match`() {
        assertEquals(listOf("Portal"), names(LibraryQuery.apply(all, LibraryFilter(tags = setOf(1, 2)), "", SortOption())))
    }

    @Test
    fun `games without a sort value go last in both directions`() {
        val asc = names(LibraryQuery.apply(all, LibraryFilter(), "", SortOption(SortField.TIME_TO_BEAT, descending = false)))
        val desc = names(LibraryQuery.apply(all, LibraryFilter(), "", SortOption(SortField.TIME_TO_BEAT, descending = true)))
        assertEquals(listOf("Portal", "Native Game", "The Witcher® 3: Wild Hunt", "Borked Game"), asc)
        assertEquals(listOf("The Witcher® 3: Wild Hunt", "Native Game", "Portal", "Borked Game"), desc)
    }

    @Test
    fun `protondb sort ranks native linux first`() {
        val sorted = names(LibraryQuery.apply(all, LibraryFilter(), "", SortOption(SortField.PROTONDB, descending = true)))
        assertEquals(listOf("Native Game", "Portal", "The Witcher® 3: Wild Hunt", "Borked Game"), sorted)
    }

    @Test
    fun `stats sum remaining time only for games with HLTB data`() {
        val stats = LibraryStats.of(all)
        // Portal 0 left (played 5 of 3), Witcher 51, Native 0 (played way more), Borked unknown.
        assertEquals(51.0, stats.remainingHours, 0.001)
        assertEquals(3, stats.withHltb)
        assertEquals(1, stats.unplayed)
    }

    private fun names(items: List<GameItem>) = items.map { it.name }

    private fun item(
        appId: Int,
        name: String,
        minutes: Int,
        deck: Int,
        tier: ProtonTier?,
        mainHours: Double?,
        tags: List<Int> = emptyList(),
        linux: Boolean = false,
    ) = GameItem(
        game = OwnedGame(appId, name, playtimeForever = minutes),
        store = StoreInfo(appId, now, deckCompat = deck, tagIds = tags, linux = linux),
        proton = ProtonInfo(appId, now, tier = tier),
        hltb = mainHours?.let { HltbInfo(appId, now, hltbId = appId, mainSeconds = (it * 3600).toLong()) },
    )
}
