package dev.steamshelf.server

import dev.steamshelf.model.DeckFilter
import dev.steamshelf.model.LengthFilter
import dev.steamshelf.model.LibraryFilter
import dev.steamshelf.model.LibraryPrefs
import dev.steamshelf.model.PlayStateFilter
import dev.steamshelf.model.ProtonFilter
import dev.steamshelf.model.SortField
import dev.steamshelf.model.SortOption
import dev.steamshelf.model.ViewMode
import dev.steamshelf.server.library.LibraryParams
import dev.steamshelf.server.library.LibraryToggle
import dev.steamshelf.server.library.LibraryView
import io.ktor.http.parseQueryString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryParamsTest {
    private fun parse(query: String) = LibraryParams.parse(parseQueryString(query))

    @Test
    fun `the default view has no parameters, and links spell it out`() {
        assertEquals("", LibraryParams.encode(LibraryView()))
        assertEquals("/?view=list", LibraryParams.url(LibraryView()))
        assertEquals(LibraryView(), parse("view=list"))
        assertEquals(LibraryView(), parse(""))
        assertFalse(LibraryParams.hasState(parseQueryString("")))
        assertTrue(LibraryParams.hasState(parseQueryString("sort=name")))
    }

    @Test
    fun `every part of the view survives the URL`() {
        val view = LibraryView(
            prefs = LibraryPrefs(
                filter = LibraryFilter(
                    playStates = setOf(PlayStateFilter.IN_PROGRESS, PlayStateFilter.UNPLAYED),
                    proton = setOf(ProtonFilter.GOLD),
                    deck = setOf(DeckFilter.VERIFIED),
                    length = setOf(LengthFilter.SHORT),
                    minReviewPercent = 80,
                    tags = setOf(19, 492),
                    fullControllerSupport = true,
                    hltbNeedsReview = true,
                    showHidden = true,
                ),
                sort = SortOption(SortField.TIME_LEFT, descending = true),
                viewMode = ViewMode.GRID,
            ),
            query = "dark souls",
        )
        val encoded = LibraryParams.encode(view)
        assertEquals(
            "q=dark+souls&play=unplayed&play=in_progress&proton=gold&deck=verified&length=short&review=80&tag=19&tag=492" +
                "&controller=1&hltb_review=1&hidden=1&sort=time_left&desc=1&view=grid",
            encoded,
        )
        assertEquals(view, parse(encoded))
    }

    @Test
    fun `unknown values are ignored rather than failing`() {
        val view = parse("play=nonsense&review=55&sort=bogus&tag=x&view=table")
        assertEquals(LibraryView(), view)
    }

    @Test
    fun `picking another sort field starts in its natural direction`() {
        // The page sent desc=0 for "name"; switching to playtime should sort most-played first.
        assertEquals(SortOption(SortField.PLAYTIME, descending = true), parse("sort=playtime&sorted=name&desc=0").prefs.sort)
        // Same field: the direction sticks.
        assertEquals(SortOption(SortField.PLAYTIME, descending = false), parse("sort=playtime&sorted=playtime&desc=0").prefs.sort)
    }

    @Test
    fun `quick chips toggle like the app's`() {
        val linux = LibraryToggle.LINUX.apply(LibraryView())
        assertEquals(setOf(ProtonFilter.NATIVE, ProtonFilter.PLATINUM, ProtonFilter.GOLD), linux.filter.proton)
        assertTrue(LibraryToggle.LINUX.isOn(linux.filter))
        assertEquals(emptySet<ProtonFilter>(), LibraryToggle.LINUX.apply(linux).filter.proton)

        val under15 = LibraryToggle.UNDER_15H.apply(LibraryView())
        assertEquals(setOf(LengthFilter.SHORT, LengthFilter.MEDIUM), under15.filter.length)

        val flipped = LibraryToggle.DIRECTION.apply(LibraryView())
        assertTrue(flipped.prefs.sort.descending)

        val busy = LibraryView(LibraryPrefs(filter = LibraryFilter(proton = setOf(ProtonFilter.GOLD)), viewMode = ViewMode.GRID), query = "x")
        val cleared = LibraryToggle.CLEAR.apply(busy)
        assertEquals(LibraryFilter(), cleared.filter)
        assertEquals("", cleared.query)
        assertEquals(ViewMode.GRID, cleared.prefs.viewMode)
    }
}
