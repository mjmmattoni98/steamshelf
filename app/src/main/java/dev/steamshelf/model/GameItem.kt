package dev.steamshelf.model

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.max

private const val STEAM_ASSETS = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps"

/** A library game joined with everything we know about it. Derived values are computed once. */
data class GameItem(
    val game: OwnedGame,
    val store: StoreInfo?,
    val proton: ProtonInfo?,
    val hltb: HltbInfo?,
    /** The user's own status; null means automatic (derived from playtime). */
    val status: PlayStatus? = null,
) {
    val appId: Int get() = game.appId
    val name: String get() = game.name
    val searchKey: String = TextNormalizer.normalize(game.name)

    val playtimeHours: Double = game.playtimeForever / 60.0

    /** Hours to beat: HLTB main story, falling back to main+extras or all-styles when main has no data. */
    val beatHours: Double? = hltb?.takeIf { it.found }?.let { h ->
        listOf(h.mainSeconds, h.plusSeconds, h.allStylesSeconds).firstOrNull { it > 0 }?.div(3600.0)
    }

    val hidden: Boolean get() = status == PlayStatus.HIDDEN
    val dropped: Boolean get() = status == PlayStatus.DROPPED

    /** Marked finished by the user, or (with no verdict of theirs) played at least a typical main-story run. */
    val beaten: Boolean = when (status) {
        PlayStatus.FINISHED -> true
        PlayStatus.DROPPED -> false
        else -> beatHours != null && playtimeHours >= beatHours
    }

    /** Hours left on the backlog: none once finished, and not counted at all for dropped games. */
    val remainingHours: Double? = when (status) {
        PlayStatus.FINISHED -> 0.0
        PlayStatus.DROPPED -> null
        else -> beatHours?.let { max(0.0, it - playtimeHours) }
    }

    /** Share of a typical main-story run already played, capped at 1; full once marked finished. */
    val progress: Float? = if (status == PlayStatus.FINISHED) 1f
    else beatHours?.takeIf { it > 0 }?.let { (playtimeHours / it).coerceIn(0.0, 1.0).toFloat() }

    /** The HLTB match is automatic and only a fuzzy fit. */
    val hltbNeedsReview: Boolean get() = hltb?.needsReview == true

    val deck: DeckCompat = DeckCompat.of(store?.deckCompat)
    val linuxNative: Boolean = store?.linux == true
    val protonTier: ProtonTier? = proton?.tier

    val releaseYear: Int? = store?.releaseDate?.takeIf { it > 0 }?.let {
        Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).year
    }

    val headerUrls: List<String> = listOfNotNull(
        store?.headerImage,
        "$STEAM_ASSETS/${game.appId}/header.jpg",
    ).distinct()

    val capsuleUrls: List<String> = (listOfNotNull(
        store?.capsuleImage,
        "$STEAM_ASSETS/${game.appId}/library_600x900.jpg",
    ) + headerUrls).distinct()

    val heroUrls: List<String> = (listOfNotNull(
        store?.heroImage,
        "$STEAM_ASSETS/${game.appId}/library_hero.jpg",
    ) + headerUrls).distinct()
}

object TextNormalizer {
    private val trademarks = Regex("[™®©]")
    private val nonAlphaNum = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")

    /** Lowercase, drop trademark symbols and punctuation, collapse whitespace. */
    fun normalize(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace(trademarks, "")
        .replace("&", " and ")
        .replace(nonAlphaNum, " ")
        .replace(spaces, " ")
        .trim()
}
