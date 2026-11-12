package dev.steamshelf.server.library

import dev.steamshelf.model.DeckFilter
import dev.steamshelf.model.LengthFilter
import dev.steamshelf.model.LibraryFilter
import dev.steamshelf.model.LibraryPrefs
import dev.steamshelf.model.PlayStateFilter
import dev.steamshelf.model.PlayerModeFilter
import dev.steamshelf.model.ProtonFilter
import dev.steamshelf.model.SortField
import dev.steamshelf.model.SortOption
import dev.steamshelf.model.ViewMode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode

/** What the library page shows: the saved view preferences plus the search text. */
data class LibraryView(val prefs: LibraryPrefs = LibraryPrefs(), val query: String = "") {
    val filter: LibraryFilter get() = prefs.filter
}

/**
 * The library view as URL parameters, so a filtered view can be bookmarked and Back works. Enum values are
 * written in lower case (`play=in_progress`); options of a group repeat the parameter (`proton=gold&proton=platinum`).
 */
object LibraryParams {
    const val QUERY = "q"
    const val PLAY = "play"
    const val PROTON = "proton"
    const val DECK = "deck"
    const val LENGTH = "length"
    const val PLAYERS = "players"
    const val REVIEW = "review"
    const val TAG = "tag"
    const val CONTROLLER = "controller"
    const val HLTB_REVIEW = "hltb_review"
    const val HIDDEN = "hidden"
    const val SORT = "sort"
    const val DESCENDING = "desc"
    const val VIEW = "view"

    /** The sort field the page was showing, so picking another one can start in its natural direction. */
    const val PREVIOUS_SORT = "sorted"

    /** A quick chip or button that changes the view (see [LibraryToggle]); never part of a canonical URL. */
    const val TOGGLE = "toggle"

    val REVIEW_THRESHOLDS = listOf(70, 80, 90)

    private val stateKeys = setOf(
        QUERY, PLAY, PROTON, DECK, LENGTH, PLAYERS, REVIEW, TAG, CONTROLLER, HLTB_REVIEW, HIDDEN, SORT, DESCENDING, VIEW,
    )

    /** Whether the URL describes a view at all; a bare "/" shows the saved one. */
    fun hasState(params: Parameters): Boolean = params.names().any { it in stateKeys }

    fun parse(params: Parameters): LibraryView {
        val filter = LibraryFilter(
            playStates = params.enums(PLAY, PlayStateFilter.entries),
            proton = params.enums(PROTON, ProtonFilter.entries),
            deck = params.enums(DECK, DeckFilter.entries),
            length = params.enums(LENGTH, LengthFilter.entries),
            playerModes = params.enums(PLAYERS, PlayerModeFilter.entries),
            minReviewPercent = params[REVIEW]?.toIntOrNull()?.takeIf { it in REVIEW_THRESHOLDS } ?: 0,
            tags = params.getAll(TAG).orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
            fullControllerSupport = params.flag(CONTROLLER),
            hltbNeedsReview = params.flag(HLTB_REVIEW),
            showHidden = params.flag(HIDDEN),
        )
        val field = params[SORT]?.let { enumOf(it, SortField.entries) } ?: SortField.NAME
        val previous = params[PREVIOUS_SORT]?.let { enumOf(it, SortField.entries) }
        val descending = when {
            previous != null && previous != field -> field.defaultDescending
            else -> params[DESCENDING]?.let { it == "1" } ?: field.defaultDescending
        }
        val view = if (params[VIEW].equals("grid", ignoreCase = true)) ViewMode.GRID else ViewMode.LIST
        return LibraryView(
            prefs = LibraryPrefs(filter = filter, sort = SortOption(field, descending), viewMode = view),
            query = params[QUERY].orEmpty().trim().take(200),
        )
    }

    /** The canonical query string (without "?"), leaving out defaults; empty for the default view. */
    fun encode(view: LibraryView, withQuery: Boolean = true): String {
        val f = view.filter
        val pairs = buildList {
            if (withQuery && view.query.isNotBlank()) add(QUERY to view.query)
            f.playStates.sorted().forEach { add(PLAY to it.key) }
            f.proton.sorted().forEach { add(PROTON to it.key) }
            f.deck.sorted().forEach { add(DECK to it.key) }
            f.length.sorted().forEach { add(LENGTH to it.key) }
            f.playerModes.sorted().forEach { add(PLAYERS to it.key) }
            if (f.minReviewPercent > 0) add(REVIEW to f.minReviewPercent.toString())
            f.tags.sorted().forEach { add(TAG to it.toString()) }
            if (f.fullControllerSupport) add(CONTROLLER to "1")
            if (f.hltbNeedsReview) add(HLTB_REVIEW to "1")
            if (f.showHidden) add(HIDDEN to "1")
            val sort = view.prefs.sort
            if (sort != SortOption()) {
                add(SORT to sort.field.key)
                add(DESCENDING to if (sort.descending) "1" else "0")
            }
            if (view.prefs.viewMode == ViewMode.GRID) add(VIEW to "grid")
        }
        return pairs.formUrlEncode()
    }

    /**
     * "/?…", for links and the address bar. The default view is spelled out as "?view=list", because a bare
     * "/" means "the saved view": Back to a cleared view must not bring the last filters back.
     */
    fun url(view: LibraryView, path: String = "/"): String = "$path?" + encode(view).ifEmpty { "$VIEW=list" }

    private fun <E : Enum<E>> Parameters.enums(name: String, entries: List<E>): Set<E> =
        getAll(name).orEmpty().mapNotNull { enumOf(it, entries) }.toSet()

    private fun Parameters.flag(name: String): Boolean = this[name].let { it == "1" || it == "on" || it == "true" }

    private fun <E : Enum<E>> enumOf(value: String, entries: List<E>): E? =
        entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
}

/** An enum value as it appears in URLs and form fields. */
val Enum<*>.key: String get() = name.lowercase()

/** Buttons that change the library view in one step: the quick chips, the sort direction and "clear". */
enum class LibraryToggle(val label: String?) {
    UNPLAYED("Never played"),
    IN_PROGRESS("In progress"),
    LINUX("Runs great on Linux"),
    DECK_VERIFIED("Deck Verified"),
    UNDER_15H("Under 15 h"),
    RECENT("Played recently"),
    HLTB_REVIEW(null),
    DIRECTION(null),
    CLEAR(null),
    ;

    fun isOn(filter: LibraryFilter): Boolean = when (this) {
        UNPLAYED -> PlayStateFilter.UNPLAYED in filter.playStates
        IN_PROGRESS -> PlayStateFilter.IN_PROGRESS in filter.playStates
        LINUX -> filter.proton.containsAll(LINUX_READY)
        DECK_VERIFIED -> DeckFilter.VERIFIED in filter.deck
        UNDER_15H -> filter.length == UNDER_15H_LENGTHS
        RECENT -> PlayStateFilter.RECENT in filter.playStates
        HLTB_REVIEW -> filter.hltbNeedsReview
        DIRECTION, CLEAR -> false
    }

    /** Same behaviour as the app's quick chips. CLEAR also empties the search box. */
    fun apply(view: LibraryView): LibraryView {
        val f = view.filter
        fun withFilter(filter: LibraryFilter) = view.copy(prefs = view.prefs.copy(filter = filter))
        return when (this) {
            UNPLAYED -> withFilter(f.copy(playStates = f.playStates.toggle(PlayStateFilter.UNPLAYED)))
            IN_PROGRESS -> withFilter(f.copy(playStates = f.playStates.toggle(PlayStateFilter.IN_PROGRESS)))
            LINUX -> withFilter(f.copy(proton = if (f.proton.containsAll(LINUX_READY)) f.proton - LINUX_READY else f.proton + LINUX_READY))
            DECK_VERIFIED -> withFilter(f.copy(deck = f.deck.toggle(DeckFilter.VERIFIED)))
            UNDER_15H -> withFilter(f.copy(length = if (f.length == UNDER_15H_LENGTHS) emptySet() else UNDER_15H_LENGTHS))
            RECENT -> withFilter(f.copy(playStates = f.playStates.toggle(PlayStateFilter.RECENT)))
            HLTB_REVIEW -> withFilter(f.copy(hltbNeedsReview = !f.hltbNeedsReview))
            DIRECTION -> view.copy(prefs = view.prefs.copy(sort = view.prefs.sort.copy(descending = !view.prefs.sort.descending)))
            CLEAR -> LibraryView(prefs = view.prefs.copy(filter = LibraryFilter()), query = "")
        }
    }

    companion object {
        val QUICK = listOf(UNPLAYED, IN_PROGRESS, LINUX, DECK_VERIFIED, UNDER_15H, RECENT)
        private val LINUX_READY = setOf(ProtonFilter.NATIVE, ProtonFilter.PLATINUM, ProtonFilter.GOLD)
        private val UNDER_15H_LENGTHS = setOf(LengthFilter.SHORT, LengthFilter.MEDIUM)

        fun of(value: String?): LibraryToggle? = entries.firstOrNull { it.key == value?.trim()?.lowercase() }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
