package dev.steamshelf.model

import kotlinx.serialization.Serializable

/** A game in the user's Steam library, as returned by IPlayerService/GetOwnedGames. Times are minutes. */
@Serializable
data class OwnedGame(
    val appId: Int,
    val name: String,
    val playtimeForever: Int = 0,
    val playtime2Weeks: Int = 0,
    /** Epoch seconds, 0 when never played. */
    val lastPlayed: Long = 0,
    val playtimeLinux: Int = 0,
    val playtimeDeck: Int = 0,
)

/** Store metadata from IStoreBrowseService/GetItems. */
@Serializable
data class StoreInfo(
    val appId: Int,
    val fetchedAt: Long,
    val found: Boolean = true,
    val shortDescription: String? = null,
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    /** Epoch seconds. */
    val releaseDate: Long? = null,
    val windows: Boolean = false,
    val mac: Boolean = false,
    val linux: Boolean = false,
    /** 0 unknown, 1 unsupported, 2 playable, 3 verified. */
    val deckCompat: Int = 0,
    val tagIds: List<Int> = emptyList(),
    val playerCategoryIds: List<Int> = emptyList(),
    val controllerCategoryIds: List<Int> = emptyList(),
    val reviewPercent: Int? = null,
    val reviewCount: Int? = null,
    val reviewLabel: String? = null,
    val headerImage: String? = null,
    val capsuleImage: String? = null,
    val heroImage: String? = null,
    /** Steam language code the text fields were fetched in; null for caches written before this field existed (English). */
    val language: String? = null,
)

enum class DeckCompat(val code: Int) {
    UNKNOWN(0),
    UNSUPPORTED(1),
    PLAYABLE(2),
    VERIFIED(3);

    companion object {
        fun of(code: Int?): DeckCompat = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

@Serializable
enum class ProtonTier(val score: Int) {
    PLATINUM(5),
    GOLD(4),
    SILVER(3),
    BRONZE(2),
    BORKED(1),
    PENDING(0);

    companion object {
        fun parse(raw: String?): ProtonTier? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/** ProtonDB summary. [tier] is null when ProtonDB has no reports for the game. */
@Serializable
data class ProtonInfo(
    val appId: Int,
    val fetchedAt: Long,
    val tier: ProtonTier? = null,
    val trendingTier: ProtonTier? = null,
    val bestReportedTier: ProtonTier? = null,
    val confidence: String? = null,
    val score: Double? = null,
    val total: Int = 0,
)

/** HowLongToBeat match for a Steam game. [hltbId] is null when no match was found. Times are seconds. */
@Serializable
data class HltbInfo(
    val appId: Int,
    val fetchedAt: Long,
    val hltbId: Int? = null,
    val name: String? = null,
    val mainSeconds: Long = 0,
    val plusSeconds: Long = 0,
    val completionistSeconds: Long = 0,
    val allStylesSeconds: Long = 0,
    val mainCount: Int = 0,
    val plusCount: Int = 0,
    val completionistCount: Int = 0,
    val reviewScore: Int = 0,
    val releaseYear: Int = 0,
    /** 0..1 name similarity of the automatic match; 1 for picks from the search dialog. */
    val similarity: Double = 0.0,
    /** True when the user picked, confirmed or rejected the match by hand; never overwritten automatically. */
    val manual: Boolean = false,
) {
    val found: Boolean get() = hltbId != null

    /** An automatic match whose name is only a fuzzy fit, so the user should check it. */
    val needsReview: Boolean get() = found && !manual && similarity < CONFIDENT_SIMILARITY

    companion object {
        /** Automatic matches below this similarity are shown as "possible match". */
        const val CONFIDENT_SIMILARITY = 0.85
    }
}

/**
 * The user's own verdict on a game, overriding the status derived from playtime. No entry means automatic.
 * Kept through "Clear cache" and "Refresh everything", like hand-picked HLTB matches.
 */
@Serializable
enum class PlayStatus {
    /** Counts as beaten whatever the playtime. */
    FINISHED,

    /** Given up on: not in progress and not part of the backlog. */
    DROPPED,

    /** Not a game the user wants to see (soundtracks, tools, …). */
    HIDDEN,
}

data class Account(
    val steamId: String,
    val apiKey: String,
    val personaName: String,
    val avatarUrl: String?,
    val profileUrl: String?,
)

enum class SyncPhase { LIBRARY, STORE, PROTONDB, HLTB }

/** What went wrong in a sync; the UI turns it into a message. */
sealed interface SyncProblem {
    /** Steam couldn't be reached or refused the request, so the cached library was used. */
    data class LibraryNotRefreshed(val cause: Throwable) : SyncProblem

    data object StoreIncomplete : SyncProblem

    data class ProtonIncomplete(val failedGames: Int) : SyncProblem

    /** HLTB failed too many times in a row; the phase stopped early. */
    data object HltbUnavailable : SyncProblem

    data class Failed(val cause: Throwable) : SyncProblem
}

data class SyncStatus(
    val running: Boolean = false,
    /** Null while a requested sync hasn't started yet. */
    val phase: SyncPhase? = null,
    val done: Int = 0,
    val total: Int = 0,
    val error: SyncProblem? = null,
    /** Bumped on every new error so the UI can show it once. */
    val errorId: Long = 0,
)
