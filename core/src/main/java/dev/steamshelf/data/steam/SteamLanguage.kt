package dev.steamshelf.data.steam

import java.util.Locale

/**
 * Maps a device locale to Steam's own language names (the "API language code" column of
 * partner.steamgames.com/doc/store/localization/languages). Steam falls back to English for text a
 * game hasn't translated, and so do we for languages Steam doesn't support.
 */
object SteamLanguage {
    const val ENGLISH = "english"

    private val byLanguage = mapOf(
        "ar" to "arabic",
        "bg" to "bulgarian",
        "cs" to "czech",
        "da" to "danish",
        "nl" to "dutch",
        "en" to ENGLISH,
        "fi" to "finnish",
        "fr" to "french",
        "de" to "german",
        "el" to "greek",
        "hu" to "hungarian",
        "id" to "indonesian",
        "in" to "indonesian", // Java's legacy code for Indonesian
        "it" to "italian",
        "ja" to "japanese",
        "ko" to "koreana",
        "ms" to "malay",
        "no" to "norwegian",
        "nb" to "norwegian",
        "nn" to "norwegian",
        "pl" to "polish",
        "ro" to "romanian",
        "ru" to "russian",
        "sv" to "swedish",
        "th" to "thai",
        "tr" to "turkish",
        "uk" to "ukrainian",
        "vi" to "vietnamese",
    )

    private val traditionalChineseRegions = setOf("TW", "HK", "MO")

    fun forLocale(locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        val region = locale.country.uppercase(Locale.ROOT)
        return when (language) {
            "zh" -> if (locale.script == "Hant" || region in traditionalChineseRegions) "tchinese" else "schinese"
            "pt" -> if (region == "BR") "brazilian" else "portuguese"
            // Steam's "spanish" is Spain; every other Spanish-speaking region reads Latin American Spanish.
            "es" -> if (region == "ES" || region.isEmpty()) "spanish" else "latam"
            else -> byLanguage[language] ?: ENGLISH
        }
    }
}
