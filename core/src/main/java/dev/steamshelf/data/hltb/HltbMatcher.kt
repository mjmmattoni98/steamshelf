package dev.steamshelf.data.hltb

import dev.steamshelf.model.HltbInfo
import dev.steamshelf.model.TextNormalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Matches Steam library names to HowLongToBeat search results. Pure, so it's unit tested. */
object HltbMatcher {

    /** Matches below this similarity are treated as "not on HLTB". */
    const val MIN_SIMILARITY = 0.55

    private const val SUBTITLE_MATCH = 0.75

    /** Matches below this are shown as "fuzzy" so the user can double-check them. */
    const val CONFIDENT_SIMILARITY = HltbInfo.CONFIDENT_SIMILARITY

    private val trademarks = Regex("[™®©]")
    private val editionSuffix = Regex(
        "(?i)[\\s:–—-]*\\b(game of the year|goty|definitive|complete|enhanced|deluxe|digital deluxe|gold|" +
            "ultimate|anniversary|director'?s cut|special|collector'?s|legendary|premium|standard|" +
            "remastered|royal|super deluxe)( edition)?\\s*$",
    )
    private val goty = Regex("(?i)\\b(game of the year edition|goty edition|goty)\\b")
    private val spaces = Regex("\\s+")

    /** The query to send to HLTB: trademark symbols and trailing edition names removed. */
    fun searchQuery(steamName: String): String {
        var name = steamName.replace(trademarks, "").replace(goty, " ")
        repeat(2) { name = name.replace(editionSuffix, "") }
        name = name.replace(spaces, " ").trim().trimEnd(':', '-', '–', '—').trim()
        return name.ifEmpty { steamName.replace(trademarks, "").trim() }
    }

    /** Looser queries to try when the main one finds nothing. */
    fun fallbackQueries(steamName: String): List<String> {
        val primary = searchQuery(steamName)
        val words = TextNormalizer.normalize(primary)
        // "Grand Theft Auto V Legacy" -> "Grand Theft Auto V": store names often carry a trailing qualifier.
        val withoutLastWord = primary.substringBeforeLast(' ', "").trimEnd(':', '-', '–', '—', ' ')
            .takeIf { it.contains(' ') }.orEmpty()
        val beforeSubtitle = primary.split(":", "–", "—", " - ").first().trim()
        return listOf(words, withoutLastWord, beforeSubtitle)
            .filter { it.length >= 3 && !it.equals(primary, ignoreCase = true) }
            .distinct()
    }

    /**
     * Picks the best candidate, returning it with its name similarity (0..1). Release year, being a full
     * game rather than DLC, and having times only break ties; they never push a weak name match over the bar.
     */
    fun bestMatch(steamName: String, releaseYear: Int?, candidates: List<HltbGame>): Pair<HltbGame, Double>? {
        val query = searchQuery(steamName)
        val full = TextNormalizer.normalize(steamName)
        val stripped = TextNormalizer.normalize(query)
        val base = TextNormalizer.normalize(query.split(":", "–", "—", " - ").first())
        return candidates
            .map { candidate ->
                val names = listOfNotNull(candidate.name, candidate.alias?.takeIf { it.isNotBlank() })
                    .map { TextNormalizer.normalize(it) }
                var similarity = names.maxOf { max(nameScore(full, it), nameScore(stripped, it)) }
                // "Disco Elysium - The Final Cut" when HLTB only lists "Disco Elysium".
                if (base.length >= 3 && base != stripped && base in names) similarity = max(similarity, SUBTITLE_MATCH)
                var rank = similarity
                if (releaseYear != null && candidate.releaseYear > 0) {
                    when (abs(candidate.releaseYear - releaseYear)) {
                        0 -> rank += 0.05
                        1 -> rank += 0.02
                    }
                }
                if (candidate.type == "game") rank += 0.01
                if (candidate.main > 0 || candidate.plus > 0) rank += 0.01
                Triple(candidate, similarity, rank)
            }
            .filter { it.second >= MIN_SIMILARITY }
            .maxByOrNull { it.third }
            ?.let { (candidate, similarity, _) -> candidate to min(similarity, 1.0) }
    }

    /** Blend of character edit distance and word overlap; either alone is easy to fool. */
    fun nameScore(a: String, b: String): Double {
        if (a == b) return 1.0
        val wordsA = a.split(' ').toSet()
        val wordsB = b.split(' ').toSet()
        val jaccard = (wordsA intersect wordsB).size.toDouble() / (wordsA union wordsB).size
        return 0.5 * similarity(a, b) + 0.5 * jaccard
    }

    /** Levenshtein similarity in 0..1 on already-normalized strings. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
