package dev.steamshelf.server.views

import dev.steamshelf.model.DeckCompat
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.model.ProtonTier
import dev.steamshelf.server.Format
import dev.steamshelf.server.label
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.img
import kotlinx.html.span

/**
 * Steam artwork: newer games use hashed asset paths, so [urls] lists candidates and app.js moves on to the
 * next one when an image is missing (like the app's SteamImage).
 */
fun FlowContent.steamImage(urls: List<String>, classes: String, alt: String = "", lazy: Boolean = true) {
    val first = urls.firstOrNull()
    if (first == null) {
        div("$classes art-missing") { attributes["aria-hidden"] = "true" }
        return
    }
    img(src = first, alt = alt, classes = classes) {
        if (lazy) attributes["loading"] = "lazy"
        attributes["decoding"] = "async"
        attributes["referrerpolicy"] = "no-referrer"
        if (urls.size > 1) attributes["data-fallback"] = urls.drop(1).joinToString(" ")
    }
}

fun FlowContent.pill(text: String, variant: String, iconName: String? = null, title: String? = null) {
    span("pill $variant") {
        if (title != null) {
            attributes["title"] = title
            attributes["aria-label"] = "$text, $title"
        }
        iconName?.let { icon(it) }
        span { +text }
    }
}

/** ProtonDB tier or native Linux; [format] wraps the label, e.g. "ProtonDB %s". */
fun FlowContent.protonPill(tier: ProtonTier?, linuxNative: Boolean, format: ((String) -> String)? = null) {
    fun label(text: String) = format?.invoke(text) ?: text
    when {
        linuxNative -> pill(label("Native"), "native", "terminal")
        tier != null -> pill(label(tier.label), "tier-${tier.name.lowercase()}")
    }
}

fun FlowContent.deckPill(compat: DeckCompat, withPrefix: Boolean = true) {
    if (compat == DeckCompat.UNKNOWN) return
    val icon = when (compat) {
        DeckCompat.VERIFIED -> "check-circle"
        DeckCompat.UNSUPPORTED -> "block"
        else -> "info"
    }
    pill(if (withPrefix) "Deck ${compat.label}" else compat.label, "deck-${compat.name.lowercase()}", icon, title = if (withPrefix) null else "Steam Deck")
}

/** HowLongToBeat time; [uncertain] marks an automatic match that is only a fuzzy fit. */
fun FlowContent.hltbPill(hours: Double?, uncertain: Boolean) {
    if (hours == null) return
    if (uncertain) {
        pill(Format.hours(hours), "warning", "warning", title = "Possible HowLongToBeat match; check it on the game page")
    } else {
        pill(Format.hours(hours), "neutral", "clock", title = "HowLongToBeat main story")
    }
}

/** The user's own status; nothing for automatic. */
fun FlowContent.statusPill(status: PlayStatus?) {
    when (status) {
        null -> Unit
        PlayStatus.FINISHED -> pill(status.label, "finished", "flag")
        PlayStatus.DROPPED -> pill(status.label, "muted", "block")
        PlayStatus.HIDDEN -> pill(status.label, "muted", "eye-off")
    }
}

/** A thin bar for how much of a typical main-story run has been played. */
fun FlowContent.progressBar(fraction: Float, classes: String = "progress", label: String? = null) {
    div(classes) {
        attributes["role"] = "progressbar"
        attributes["aria-valuemin"] = "0"
        attributes["aria-valuemax"] = "100"
        attributes["aria-valuenow"] = (fraction * 100).toInt().toString()
        if (label != null) attributes["aria-label"] = label
        span { attributes["style"] = "width:${"%.1f".format(java.util.Locale.ROOT, fraction * 100)}%" }
    }
}
