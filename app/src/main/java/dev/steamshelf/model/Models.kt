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
)

enum class DeckCompat(val code: Int, val label: String) {
    UNKNOWN(0, "Unknown"),
    UNSUPPORTED(1, "Unsupported"),
    PLAYABLE(2, "Playable"),
    VERIFIED(3, "Verified");

    companion object {
        fun of(code: Int?): DeckCompat = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

@Serializable
enum class ProtonTier(val label: String, val score: Int) {
    PLATINUM("Platinum", 5),
    GOLD("Gold", 4),
    SILVER("Silver", 3),
    BRONZE("Bronze", 2),
    BORKED("Borked", 1),
    PENDING("Pending", 0);

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
    /** 0..1 name similarity of the automatic match; 1 for manual picks. */
    val similarity: Double = 0.0,
    /** True when the user picked (or rejected) the match by hand; never overwritten automatically. */
    val manual: Boolean = false,
) {
    val found: Boolean get() = hltbId != null
}

data class Account(
    val steamId: String,
    val apiKey: String,
    val personaName: String,
    val avatarUrl: String?,
    val profileUrl: String?,
)

enum class SyncPhase(val label: String) {
    LIBRARY("Loading your library"),
    STORE("Fetching store details"),
    PROTONDB("Fetching ProtonDB ratings"),
    HLTB("Fetching HowLongToBeat times"),
}

data class SyncStatus(
    val running: Boolean = false,
    val phase: SyncPhase? = null,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    /** Bumped on every new error so the UI can show it once. */
    val errorId: Long = 0,
)
