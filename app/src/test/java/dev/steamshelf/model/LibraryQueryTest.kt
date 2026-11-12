package dev.steamshelf.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // region HLTB matches that need review

    @Test
    fun `review filter shows only automatic fuzzy matches`() {
        val fuzzy = item(1, "Fuzzy", minutes = 0, deck = 0, tier = null, mainHours = 10.0, similarity = 0.7)
        val confident = item(2, "Confident", minutes = 0, deck = 0, tier = null, mainHours = 10.0, similarity = 0.95)
        val confirmed = item(3, "Confirmed", minutes = 0, deck = 0, tier = null, mainHours = 10.0, similarity = 0.7, manual = true)
        val notFound = GameItem(OwnedGame(4, "Missing"), null, null, HltbInfo(4, now, similarity = 0.0))
        val filter = LibraryFilter(hltbNeedsReview = true)

        assertEquals(listOf("Fuzzy"), names(LibraryQuery.apply(listOf(fuzzy, confident, confirmed, notFound), filter, "", SortOption())))
        assertEquals(1, filter.activeCount)
    }

    @Test
    fun `confirming a match clears the review flag`() {
        val info = HltbInfo(1, now, hltbId = 9, mainSeconds = 3600, similarity = 0.6)
        assertTrue(info.needsReview)
        assertFalse(info.copy(manual = true).needsReview)
    }

    // endregion

    // region the user's own status

    @Test
    fun `finished counts as beaten whatever the playtime and leaves nothing to beat`() {
        val quick = item(1, "Finished fast", minutes = 60, deck = 0, tier = null, mainHours = 20.0, status = PlayStatus.FINISHED)

        assertTrue(PlayStateFilter.BEATEN.matches(quick))
        assertFalse(PlayStateFilter.IN_PROGRESS.matches(quick))
        assertEquals(0.0, quick.remainingHours!!, 0.0)
        assertEquals(1f, quick.progress)
        assertEquals(0.0, LibraryStats.of(listOf(quick)).remainingHours, 0.0)
    }

    @Test
    fun `finished without any playtime isn't "never played"`() {
        val elsewhere = item(1, "Played elsewhere", minutes = 0, deck = 0, tier = null, mainHours = 5.0, status = PlayStatus.FINISHED)
        assertFalse(PlayStateFilter.UNPLAYED.matches(elsewhere))
        assertTrue(PlayStateFilter.BEATEN.matches(elsewhere))
    }

    @Test
    fun `dropped is not in progress, not backlog, and has its own filter`() {
        val dropped = item(1, "Dropped", minutes = 300, deck = 0, tier = null, mainHours = 40.0, status = PlayStatus.DROPPED)
        val started = item(2, "Started", minutes = 300, deck = 0, tier = null, mainHours = 40.0)
        val all = listOf(dropped, started)

        assertEquals(listOf("Started"), names(LibraryQuery.apply(all, LibraryFilter(playStates = setOf(PlayStateFilter.IN_PROGRESS)), "", SortOption())))
        assertEquals(listOf("Dropped"), names(LibraryQuery.apply(all, LibraryFilter(playStates = setOf(PlayStateFilter.DROPPED)), "", SortOption())))
        assertFalse(PlayStateFilter.BEATEN.matches(dropped))
        // Only the started game's 35 h count towards "left to beat"; the dropped one sorts last by time left.
        assertEquals(35.0, LibraryStats.of(all).remainingHours, 0.001)
        assertEquals(
            listOf("Started", "Dropped"),
            names(LibraryQuery.apply(all, LibraryFilter(), "", SortOption(SortField.TIME_LEFT))),
        )
    }

    @Test
    fun `hidden games are left out of the list and stats unless shown`() {
        val hidden = item(1, "Soundtrack", minutes = 600, deck = 0, tier = null, mainHours = 2.0, status = PlayStatus.HIDDEN)
        val all = listOf(hidden, portal)

        val visible = LibraryQuery.apply(all, LibraryFilter(), "", SortOption())
        assertEquals(listOf("Portal"), names(visible))
        assertEquals(1, LibraryStats.of(visible).count)
        assertEquals(5.0, LibraryStats.of(visible).playedHours, 0.001)

        val shown = LibraryQuery.apply(all, LibraryFilter(showHidden = true), "", SortOption())
        assertEquals(listOf("Portal", "Soundtrack"), names(shown))
        // Shown hidden games behave as automatic: 10 h played against a 2 h main story is beaten.
        assertTrue(PlayStateFilter.BEATEN.matches(hidden))
    }

    @Test
    fun `automatic status still derives beaten from playtime`() {
        assertTrue(PlayStateFilter.BEATEN.matches(portal))
        assertTrue(PlayStateFilter.IN_PROGRESS.matches(item(5, "Long", minutes = 60, deck = 0, tier = null, mainHours = 10.0)))
    }

    // endregion

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
        similarity: Double = 1.0,
        manual: Boolean = false,
        status: PlayStatus? = null,
    ) = GameItem(
        game = OwnedGame(appId, name, playtimeForever = minutes),
        store = StoreInfo(appId, now, deckCompat = deck, tagIds = tags, linux = linux),
        proton = ProtonInfo(appId, now, tier = tier),
        hltb = mainHours?.let {
            HltbInfo(appId, now, hltbId = appId, mainSeconds = (it * 3600).toLong(), similarity = similarity, manual = manual)
        },
        status = status,
    )
}
