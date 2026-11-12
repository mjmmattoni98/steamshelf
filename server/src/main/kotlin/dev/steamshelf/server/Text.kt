package dev.steamshelf.server

import dev.steamshelf.data.hltb.HltbRateLimitedException
import dev.steamshelf.data.net.HttpStatusException
import dev.steamshelf.data.steam.SteamApiException
import dev.steamshelf.data.steam.SteamApiException.Reason
import dev.steamshelf.data.steam.SteamLanguage
import dev.steamshelf.model.DeckCompat
import dev.steamshelf.model.DeckFilter
import dev.steamshelf.model.LengthFilter
import dev.steamshelf.model.PlayStateFilter
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.model.PlayerModeFilter
import dev.steamshelf.model.ProtonFilter
import dev.steamshelf.model.ProtonTier
import dev.steamshelf.model.SortField
import dev.steamshelf.model.SyncPhase
import dev.steamshelf.model.SyncProblem
import dev.steamshelf.model.SyncStatus
import java.io.IOException
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// The web app's text, in English like the app's res/values/strings.xml (the model stays free of UI text).

val SyncPhase.label: String
    get() = when (this) {
        SyncPhase.LIBRARY -> "Loading your library"
        SyncPhase.STORE -> "Fetching store details"
        SyncPhase.PROTONDB -> "Fetching ProtonDB ratings"
        SyncPhase.HLTB -> "Fetching HowLongToBeat times"
    }

val PlayStatus.label: String
    get() = when (this) {
        PlayStatus.FINISHED -> "Finished"
        PlayStatus.DROPPED -> "Dropped"
        PlayStatus.HIDDEN -> "Hidden"
    }

val DeckCompat.label: String
    get() = when (this) {
        DeckCompat.UNKNOWN -> "Unknown"
        DeckCompat.UNSUPPORTED -> "Unsupported"
        DeckCompat.PLAYABLE -> "Playable"
        DeckCompat.VERIFIED -> "Verified"
    }

val ProtonTier.label: String
    get() = when (this) {
        ProtonTier.PLATINUM -> "Platinum"
        ProtonTier.GOLD -> "Gold"
        ProtonTier.SILVER -> "Silver"
        ProtonTier.BRONZE -> "Bronze"
        ProtonTier.BORKED -> "Borked"
        ProtonTier.PENDING -> "Pending"
    }

val PlayStateFilter.label: String
    get() = when (this) {
        PlayStateFilter.UNPLAYED -> "Never played"
        PlayStateFilter.TRIED -> "Played < 2 h"
        PlayStateFilter.IN_PROGRESS -> "Started, not beaten"
        PlayStateFilter.BEATEN -> "Past main story"
        PlayStateFilter.RECENT -> "Played last 2 weeks"
        PlayStateFilter.DROPPED -> "Dropped"
    }

val ProtonFilter.label: String
    get() = when (this) {
        ProtonFilter.NATIVE -> "Native Linux"
        ProtonFilter.PLATINUM -> "Platinum"
        ProtonFilter.GOLD -> "Gold"
        ProtonFilter.SILVER -> "Silver"
        ProtonFilter.BRONZE -> "Bronze"
        ProtonFilter.BORKED -> "Borked"
        ProtonFilter.NO_REPORTS -> "No reports"
    }

val DeckFilter.label: String get() = compat.label

val LengthFilter.label: String
    get() = when (this) {
        LengthFilter.SHORT -> "Under 5 h"
        LengthFilter.MEDIUM -> "5–15 h"
        LengthFilter.LONG -> "15–40 h"
        LengthFilter.VERY_LONG -> "40 h +"
        LengthFilter.UNKNOWN -> "No HLTB data"
    }

val PlayerModeFilter.label: String
    get() = when (this) {
        PlayerModeFilter.SINGLE -> "Single-player"
        PlayerModeFilter.COOP -> "Co-op"
        PlayerModeFilter.MULTIPLAYER -> "Multiplayer"
        PlayerModeFilter.SPLIT_SCREEN -> "Local / split screen"
    }

val SortField.label: String
    get() = when (this) {
        SortField.NAME -> "Name"
        SortField.PLAYTIME -> "Playtime"
        SortField.LAST_PLAYED -> "Last played"
        SortField.TIME_TO_BEAT -> "Time to beat"
        SortField.TIME_LEFT -> "Time left to beat"
        SortField.PROTONDB -> "ProtonDB rating"
        SortField.REVIEWS -> "Steam reviews"
        SortField.RELEASE -> "Release date"
    }

/** Steam's player-mode category ids, as named on the game page. */
val PLAYER_CATEGORY_NAMES = mapOf(
    2 to "Single-player",
    1 to "Multiplayer",
    9 to "Co-op",
    38 to "Online co-op",
    48 to "LAN co-op",
    39 to "Split-screen co-op",
    24 to "Shared/split screen",
    49 to "PvP",
    36 to "Online PvP",
    47 to "LAN PvP",
    37 to "Split-screen PvP",
    20 to "MMO",
    27 to "Cross-platform multiplayer",
)

/** Steam's store languages with their own names, for the settings page. */
val STORE_LANGUAGES: List<Pair<String, String>> = listOf(
    "english" to "English",
    "arabic" to "العربية",
    "bulgarian" to "Български",
    "schinese" to "简体中文",
    "tchinese" to "繁體中文",
    "czech" to "Čeština",
    "danish" to "Dansk",
    "dutch" to "Nederlands",
    "finnish" to "Suomi",
    "french" to "Français",
    "german" to "Deutsch",
    "greek" to "Ελληνικά",
    "hungarian" to "Magyar",
    "indonesian" to "Bahasa Indonesia",
    "italian" to "Italiano",
    "japanese" to "日本語",
    "koreana" to "한국어",
    "malay" to "Bahasa Melayu",
    "norwegian" to "Norsk",
    "polish" to "Polski",
    "portuguese" to "Português",
    "brazilian" to "Português (Brasil)",
    "romanian" to "Română",
    "russian" to "Русский",
    "spanish" to "Español (España)",
    "latam" to "Español (Latinoamérica)",
    "swedish" to "Svenska",
    "thai" to "ไทย",
    "turkish" to "Türkçe",
    "ukrainian" to "Українська",
    "vietnamese" to "Tiếng Việt",
)

/** Store language and country for a browser's Accept-Language header, used as the defaults at setup. */
fun storeDefaultsFor(acceptLanguage: String?): Pair<String, String> {
    val tag = acceptLanguage?.split(',')?.firstOrNull()?.substringBefore(';')?.trim().orEmpty()
    val locale = if (tag.isEmpty() || tag == "*") Locale.US else Locale.forLanguageTag(tag)
    val language = SteamLanguage.forLocale(locale)
    val country = locale.country.uppercase(Locale.ROOT).takeIf { it.length == 2 && it.all(Char::isLetter) } ?: "US"
    return language to country
}

object Format {
    private val integer: NumberFormat get() = NumberFormat.getIntegerInstance(Locale.US)
    private val date = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun number(value: Int): String = integer.format(value)

    fun hours(hours: Double): String = when {
        hours <= 0.0 -> "0 h"
        hours < 1.0 -> "${(hours * 60).roundToInt().coerceAtLeast(1)} min"
        hours < 10.0 -> String.format(Locale.US, "%.1f h", hours)
        else -> "${integer.format(hours.roundToInt())} h"
    }

    fun minutes(minutes: Int): String = if (minutes == 0) "Never played" else hours(minutes / 60.0)

    fun seconds(seconds: Long): String? = seconds.takeIf { it > 0 }?.let { hours(it / 3600.0) }

    /** "1.2K" style counts. */
    fun count(count: Int): String = when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> compact(count / 1_000.0, "K")
        else -> compact(count / 1_000_000.0, "M")
    }

    private fun compact(value: Double, suffix: String): String =
        (if (value < 10) String.format(Locale.US, "%.1f", value).removeSuffix(".0") else value.toInt().toString()) + suffix

    /** Short relative date that fits a stat tile: "Today", "3 d ago", "5 wk ago", "4 mo ago", "2 y ago". */
    fun shortRelativeDate(epochSeconds: Long, nowMillis: Long): String {
        if (epochSeconds <= 0) return "Never"
        val days = ((nowMillis / 1000 - epochSeconds) / 86_400).coerceAtLeast(0)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 14 -> "$days d ago"
            days < 60 -> "${days / 7} wk ago"
            days < 365 -> "${days / 30} mo ago"
            else -> "${days / 365} y ago"
        }
    }

    /** "just now", "12 min ago", "3 h ago", then the short relative date. */
    fun relativeTime(epochMillis: Long, nowMillis: Long): String {
        val minutes = (nowMillis - epochMillis) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> shortRelativeDate(epochMillis / 1000, nowMillis)
        }
    }

    fun date(epochSeconds: Long): String = date.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC))

    fun plural(count: Int, one: String, other: String): String = if (count == 1) one else other
}

/** The sync bar's text: "Fetching HowLongToBeat times · 120/800". */
fun SyncStatus.label(): String {
    val phase = phase?.label ?: "Syncing"
    return if (total > 0) "$phase · $done/$total" else phase
}

fun SyncProblem.message(): String = when (this) {
    is SyncProblem.LibraryNotRefreshed -> "Couldn't refresh your library from Steam, so the saved one is shown. ${cause.userMessage()}"
    SyncProblem.StoreIncomplete -> "Couldn't load Steam store details for some games."
    is SyncProblem.ProtonIncomplete ->
        if (failedGames == 1) "Couldn't load the ProtonDB rating for 1 game."
        else "Couldn't load ProtonDB ratings for $failedGames games."
    SyncProblem.HltbUnavailable -> "HowLongToBeat isn't responding right now; times will be fetched on the next sync."
    is SyncProblem.Failed -> "Sync failed. ${cause.userMessage()}"
}

/** A failure explained to the user, like the app's userMessage(). */
fun Throwable.userMessage(): String = when (this) {
    is SteamApiException -> when (reason) {
        Reason.NO_GAMES ->
            "Steam returned no games. Make sure the API key belongs to this account, or set \"Game details\" to Public in your Steam privacy settings."
        Reason.KEY_REJECTED -> "Steam rejected the API key (HTTP ${detail.orEmpty()}). Check that it's correct."
        Reason.RATE_LIMITED -> "Steam is rate limiting requests. Try again in a few minutes."
        Reason.HTTP_ERROR -> "Steam API error (HTTP ${detail.orEmpty()})."
        Reason.PROFILE_NOT_FOUND -> "No Steam profile found for ${detail.orEmpty()}."
        Reason.VANITY_NOT_FOUND -> "Couldn't find a Steam profile called \"${detail.orEmpty()}\"."
        Reason.SIGN_IN_INVALID -> "Invalid sign-in response."
        Reason.SIGN_IN_CANCELLED -> "Sign-in was cancelled."
        Reason.SIGN_IN_UNEXPECTED -> "Unexpected sign-in response."
        Reason.SIGN_IN_NO_STEAM_ID -> "Steam didn't return a SteamID."
        Reason.SIGN_IN_NOT_VERIFIED -> "Steam couldn't verify the sign-in. Please try again."
    }
    is HltbRateLimitedException -> "HowLongToBeat says too many searches. Wait a minute and try again."
    is HttpStatusException -> "The server answered with an error (HTTP $code)."
    is IOException -> "Couldn't connect. Check the Raspberry Pi's internet connection."
    else -> "Something went wrong."
}
