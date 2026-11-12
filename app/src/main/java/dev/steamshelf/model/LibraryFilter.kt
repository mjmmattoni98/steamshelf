package dev.steamshelf.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlayStateFilter {
    UNPLAYED,
    TRIED,
    IN_PROGRESS,
    BEATEN,
    RECENT,
    DROPPED;

    fun matches(item: GameItem): Boolean {
        val minutes = item.game.playtimeForever
        return when (this) {
            // A game the user has finished or given up on isn't backlog, even with no playtime on Steam.
            UNPLAYED -> minutes == 0 && item.status != PlayStatus.FINISHED && !item.dropped
            TRIED -> minutes in 1 until 120
            IN_PROGRESS -> minutes > 0 && !item.beaten && !item.dropped
            BEATEN -> item.beaten
            RECENT -> item.game.playtime2Weeks > 0
            DROPPED -> item.dropped
        }
    }
}

@Serializable
enum class ProtonFilter {
    NATIVE,
    PLATINUM,
    GOLD,
    SILVER,
    BRONZE,
    BORKED,
    NO_REPORTS;

    fun matches(item: GameItem): Boolean = when (this) {
        NATIVE -> item.linuxNative
        PLATINUM -> item.protonTier == ProtonTier.PLATINUM
        GOLD -> item.protonTier == ProtonTier.GOLD
        SILVER -> item.protonTier == ProtonTier.SILVER
        BRONZE -> item.protonTier == ProtonTier.BRONZE
        BORKED -> item.protonTier == ProtonTier.BORKED
        NO_REPORTS -> item.protonTier == null || item.protonTier == ProtonTier.PENDING
    }
}

@Serializable
enum class DeckFilter(val compat: DeckCompat) {
    VERIFIED(DeckCompat.VERIFIED),
    PLAYABLE(DeckCompat.PLAYABLE),
    UNSUPPORTED(DeckCompat.UNSUPPORTED),
    UNKNOWN(DeckCompat.UNKNOWN);

    fun matches(item: GameItem): Boolean = item.deck == compat
}

@Serializable
enum class LengthFilter(val minHours: Double, val maxHours: Double) {
    SHORT(0.0, 5.0),
    MEDIUM(5.0, 15.0),
    LONG(15.0, 40.0),
    VERY_LONG(40.0, Double.MAX_VALUE),
    UNKNOWN(-1.0, -1.0);

    fun matches(item: GameItem): Boolean {
        val hours = item.beatHours
        return if (this == UNKNOWN) hours == null else hours != null && hours >= minHours && hours < maxHours
    }
}

/** Steam "supported player" category ids. */
@Serializable
enum class PlayerModeFilter(val categoryIds: Set<Int>) {
    SINGLE(setOf(2)),
    COOP(setOf(9, 38, 39, 48)),
    MULTIPLAYER(setOf(1, 20, 27, 36, 47, 49)),
    SPLIT_SCREEN(setOf(24, 37, 39));

    fun matches(item: GameItem): Boolean =
        item.store?.playerCategoryIds?.any { it in categoryIds } == true
}

const val FULL_CONTROLLER_CATEGORY = 28
const val PARTIAL_CONTROLLER_CATEGORY = 18

/** Within a group, options are OR-ed; groups are AND-ed. Tags must all match. */
@Serializable
data class LibraryFilter(
    val playStates: Set<PlayStateFilter> = emptySet(),
    val proton: Set<ProtonFilter> = emptySet(),
    val deck: Set<DeckFilter> = emptySet(),
    val length: Set<LengthFilter> = emptySet(),
    val playerModes: Set<PlayerModeFilter> = emptySet(),
    val minReviewPercent: Int = 0,
    val tags: Set<Int> = emptySet(),
    val fullControllerSupport: Boolean = false,
    /** Only automatic HLTB matches that are a fuzzy fit, so the user can check them. */
    val hltbNeedsReview: Boolean = false,
    /** Include games the user marked as hidden. */
    val showHidden: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(playStates, proton, deck, length, playerModes, tags).count { it.isNotEmpty() } +
            listOf(minReviewPercent > 0, fullControllerSupport, hltbNeedsReview, showHidden).count { it }

    fun matches(item: GameItem): Boolean {
        if (item.hidden && !showHidden) return false
        if (hltbNeedsReview && !item.hltbNeedsReview) return false
        if (playStates.isNotEmpty() && playStates.none { it.matches(item) }) return false
        if (proton.isNotEmpty() && proton.none { it.matches(item) }) return false
        if (deck.isNotEmpty() && deck.none { it.matches(item) }) return false
        if (length.isNotEmpty() && length.none { it.matches(item) }) return false
        if (playerModes.isNotEmpty() && playerModes.none { it.matches(item) }) return false
        if (minReviewPercent > 0 && (item.store?.reviewPercent ?: -1) < minReviewPercent) return false
        if (tags.isNotEmpty() && !(item.store?.tagIds.orEmpty()).containsAll(tags)) return false
        if (fullControllerSupport && item.store?.controllerCategoryIds?.contains(FULL_CONTROLLER_CATEGORY) != true) {
            return false
        }
        return true
    }
}

@Serializable
enum class SortField(val defaultDescending: Boolean) {
    NAME(false),
    PLAYTIME(true),
    LAST_PLAYED(true),
    TIME_TO_BEAT(false),
    TIME_LEFT(false),
    PROTONDB(true),
    REVIEWS(true),
    RELEASE(true),
}

@Serializable
data class SortOption(val field: SortField = SortField.NAME, val descending: Boolean = false)

@Serializable
enum class ViewMode { LIST, GRID }

@Serializable
data class LibraryPrefs(
    val filter: LibraryFilter = LibraryFilter(),
    val sort: SortOption = SortOption(),
    val viewMode: ViewMode = ViewMode.LIST,
)

object LibraryQuery {

    fun apply(items: List<GameItem>, filter: LibraryFilter, query: String, sort: SortOption): List<GameItem> {
        val needle = TextNormalizer.normalize(query)
        return items
            .filter { (needle.isEmpty() || it.searchKey.contains(needle)) && filter.matches(it) }
            .sortedWith(comparator(sort))
    }

    /** Games without a value for the sort key always go last, whatever the direction. */
    fun comparator(sort: SortOption): Comparator<GameItem> {
        val byName = Comparator<GameItem> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
        val primary: Comparator<GameItem> = when (sort.field) {
            SortField.NAME -> if (sort.descending) byName.reversed() else byName
            SortField.PLAYTIME -> keyed(sort.descending) { it.game.playtimeForever }
            SortField.LAST_PLAYED -> keyed(sort.descending) { it.game.lastPlayed.takeIf { t -> t > 0 } }
            SortField.TIME_TO_BEAT -> keyed(sort.descending) { it.beatHours }
            SortField.TIME_LEFT -> keyed(sort.descending) { it.remainingHours }
            SortField.PROTONDB -> keyed(sort.descending) { protonScore(it) }
            SortField.REVIEWS -> keyed(sort.descending) { it.store?.reviewPercent }
            SortField.RELEASE -> keyed(sort.descending) { it.store?.releaseDate?.takeIf { d -> d > 0 } }
        }
        return primary.then(byName)
    }

    /** Native Linux ranks above Platinum; no rating sorts last. */
    fun protonScore(item: GameItem): Int? = when {
        item.linuxNative -> 6
        else -> item.protonTier?.score?.takeIf { it > 0 }
    }

    private fun <T : Comparable<T>> keyed(descending: Boolean, selector: (GameItem) -> T?): Comparator<GameItem> =
        Comparator { a, b ->
            val ka = selector(a)
            val kb = selector(b)
            when {
                ka == null && kb == null -> 0
                ka == null -> 1
                kb == null -> -1
                descending -> kb.compareTo(ka)
                else -> ka.compareTo(kb)
            }
        }
}

data class LibraryStats(
    val count: Int = 0,
    val playedHours: Double = 0.0,
    val remainingHours: Double = 0.0,
    val withHltb: Int = 0,
    val unplayed: Int = 0,
) {
    companion object {
        fun of(items: List<GameItem>) = LibraryStats(
            count = items.size,
            playedHours = items.sumOf { it.playtimeHours },
            remainingHours = items.sumOf { it.remainingHours ?: 0.0 },
            withHltb = items.count { it.beatHours != null },
            unplayed = items.count { it.game.playtimeForever == 0 },
        )
    }
}
