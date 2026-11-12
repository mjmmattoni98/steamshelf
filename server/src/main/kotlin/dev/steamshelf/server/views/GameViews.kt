package dev.steamshelf.server.views

import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.hltb.HltbGame
import dev.steamshelf.data.hltb.HltbMatcher
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.model.FULL_CONTROLLER_CATEGORY
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.PARTIAL_CONTROLLER_CATEGORY
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.server.Format
import dev.steamshelf.server.PLAYER_CATEGORY_NAMES
import dev.steamshelf.server.label
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.HTMLTag
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.header
import kotlinx.html.hr
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.ul
import kotlinx.html.visit
import kotlin.math.roundToInt

/** The game page. While details are still being fetched, #game reloads itself every couple of seconds. */
fun HTML.gamePage(item: GameItem, tagNames: Map<Int, String>, loading: Boolean, chrome: PageChrome, now: Long) {
    page(item.name, chrome, "page-game") {
        gameContent(item, tagNames, loading, now)
        hltbDialog(item)
    }
}

fun FlowContent.gameContent(item: GameItem, tagNames: Map<Int, String>, loading: Boolean, now: Long) {
    div("game") {
        id = "game"
        if (loading) {
            attributes["hx-get"] = "/games/${item.appId}"
            attributes["hx-trigger"] = "load delay:1500ms"
            attributes["hx-select"] = "#game"
            attributes["hx-swap"] = "outerHTML"
        }
        a(href = "/", classes = "back-link") {
            attributes["data-back"] = ""
            icon("arrow-left")
            span { +"Library" }
        }
        gameHeader(item)
        div("game-grid") {
            div("game-column") {
                playtimeCard(item, now)
                hltbCard(item, loading)
            }
            div("game-column") {
                protonCard(item, loading)
                storeCard(item, tagNames)
                div("link-row") {
                    externalLink("https://store.steampowered.com/app/${item.appId}", "Steam store", "btn")
                    externalLink("https://steamdb.info/app/${item.appId}/", "SteamDB", "btn")
                }
            }
        }
    }
}

private fun FlowContent.gameHeader(item: GameItem) {
    header("game-header") {
        steamImage(item.headerUrls, "header-art", alt = item.name, lazy = false)
        div("game-title") {
            h1 { +item.name }
            val byline = listOfNotNull(
                item.store?.developers?.firstOrNull(),
                item.store?.releaseDate?.takeIf { it > 0 }?.let { Format.date(it) },
            ).joinToString(" · ")
            if (byline.isNotEmpty()) p("byline") { +byline }
            div("pills") {
                protonPill(item.protonTier, linuxNative = false) { "ProtonDB $it" }
                if (item.linuxNative) protonPill(null, linuxNative = true) { "Linux $it" }
                deckPill(item.deck)
                val store = item.store
                val reviewLabel = store?.reviewLabel
                if (store != null && reviewLabel != null && (store.reviewCount ?: 0) > 0) {
                    pill("$reviewLabel · ${store.reviewPercent ?: 0}%", "neutral")
                }
            }
        }
    }
}

private fun FlowContent.card(title: String, classes: String = "", content: FlowContent.() -> Unit) {
    section("card $classes".trim()) {
        h2 { +title }
        content()
    }
}

private fun FlowContent.tile(value: String, label: String, caption: String? = null) {
    div("tile") {
        strong { +value }
        span { +label }
        if (caption != null) small { +caption }
    }
}

private fun FlowContent.playtimeCard(item: GameItem, now: Long) {
    val game = item.game
    card("Your playtime") {
        div("tiles") {
            tile(Format.hours(item.playtimeHours), "Total")
            tile(Format.hours(game.playtime2Weeks / 60.0), "Last 2 weeks")
            tile(Format.shortRelativeDate(game.lastPlayed, now), "Last played")
        }
        if (game.playtimeDeck > 0 || game.playtimeLinux > 0) {
            div("tiles") {
                tile(Format.hours(game.playtimeDeck / 60.0), "On Steam Deck")
                tile(Format.hours(game.playtimeLinux / 60.0), "On Linux")
            }
        }
        val progress = item.progress
        val beat = item.beatHours
        if (progress != null && beat != null) {
            progressBar(progress, "progress large", label = "Share of a typical main-story run played")
            val remaining = item.remainingHours ?: 0.0
            p("hint") {
                +when {
                    item.status == PlayStatus.FINISHED -> "Marked as finished."
                    item.dropped -> "Marked as dropped, so it isn't counted in your backlog."
                    remaining <= 0.0 -> "You've played longer than a typical main-story run (${Format.hours(beat)})."
                    else -> "${(progress * 100).roundToInt()}% of a typical ${Format.hours(beat)} run · about ${Format.hours(remaining)} to go"
                }
            }
        }
        hr()
        h3 { +"Your status" }
        form(classes = "segmented status") {
            attributes["hx-post"] = "/games/${item.appId}/status"
            gameTarget()
            listOf(null, PlayStatus.FINISHED, PlayStatus.DROPPED, PlayStatus.HIDDEN).forEach { option ->
                button(type = ButtonType.submit, name = "status", classes = "seg") {
                    value = option?.name?.lowercase() ?: "auto"
                    attributes["aria-pressed"] = (item.status == option).toString()
                    +(option?.label ?: "Automatic")
                }
            }
        }
        p("hint") {
            +"Automatic counts a game as beaten once you've played a typical main-story run. Finished and Dropped override that and take the game off your backlog; Hidden removes it from the library, stats and random picks."
        }
    }
}

/** htmx: replace the whole #game block with the one in the response. */
private fun HTMLTag.gameTarget() {
    attributes["hx-target"] = "#game"
    attributes["hx-select"] = "#game"
    attributes["hx-swap"] = "outerHTML"
}

private fun FlowContent.hltbCard(item: GameItem, loading: Boolean) {
    val info = item.hltb
    card("HowLongToBeat") {
        when {
            info == null && loading -> loadingRow("Searching HowLongToBeat…")
            info == null -> p { +"Not fetched yet. Sync the library, or search manually." }
            info.hltbId == null -> p { +(if (info.manual) "Marked as not on HowLongToBeat." else "No match found on HowLongToBeat.") }
            else -> {
                div("tiles") {
                    tile(Format.seconds(info.mainSeconds) ?: "–", "Main story", polled(info.mainCount))
                    tile(Format.seconds(info.plusSeconds) ?: "–", "Main + extras", polled(info.plusCount))
                    tile(Format.seconds(info.completionistSeconds) ?: "–", "Completionist", polled(info.completionistCount))
                }
                val name = info.name.orEmpty().let { if (info.releaseYear > 0) "$it (${info.releaseYear})" else it }
                p(if (info.needsReview) "match-line warning-text" else "match-line") {
                    if (info.needsReview) icon("warning")
                    span {
                        +when {
                            info.manual -> "Matched by you: $name"
                            info.needsReview -> "Possible match: $name"
                            else -> "Matched: $name"
                        }
                    }
                }
            }
        }
        div("actions") {
            if (info?.needsReview == true) {
                button(type = ButtonType.button, classes = "btn") {
                    attributes["hx-post"] = "/games/${item.appId}/hltb/confirm"
                    gameTarget()
                    icon("check")
                    span { +"It's right" }
                }
            }
            button(type = ButtonType.button, classes = "btn") {
                attributes["data-dialog-open"] = "hltb-dialog"
                icon("edit")
                span { +(if (info?.hltbId != null) "Wrong game?" else "Search HLTB") }
            }
            info?.hltbId?.let { externalLink(HltbApi.pageUrl(it), "Open on HLTB", "btn ghost") }
        }
    }
}

private fun polled(count: Int): String? = count.takeIf { it > 0 }?.let { "${Format.count(it)} polled" }

private fun FlowContent.protonCard(item: GameItem, loading: Boolean) {
    val info = item.proton
    val tier = info?.tier
    card("ProtonDB") {
        when {
            info == null && loading -> loadingRow("Loading ProtonDB rating…")
            info == null -> p { +"Not fetched yet." }
            tier == null -> p { +"No ProtonDB reports for this game yet." }
            else -> {
                div("proton-summary") {
                    pill(tier.label.uppercase(), "tier-${tier.name.lowercase()} large")
                    span {
                        +(info.confidence?.let { "${Format.count(info.total)} reports · $it confidence" } ?: "${Format.count(info.total)} reports")
                    }
                }
                val extra = listOfNotNull(
                    info.trendingTier?.let { "Trending: ${it.label}" },
                    info.bestReportedTier?.let { "Best reported: ${it.label}" },
                ).joinToString(" · ")
                if (extra.isNotEmpty()) p("hint") { +extra }
            }
        }
        if (item.linuxNative) p("native-note") { +"This game has a native Linux version." }
        div("actions") { externalLink(ProtonDbApi.pageUrl(item.appId), "Open on ProtonDB", "btn ghost") }
    }
}

private fun FlowContent.storeCard(item: GameItem, tagNames: Map<Int, String>) {
    val store = item.store ?: return
    if (!store.found) return
    card("Steam") {
        val platforms = listOfNotNull(
            "Windows".takeIf { store.windows },
            "macOS".takeIf { store.mac },
            "Linux".takeIf { store.linux },
        ).joinToString(", ")
        val players = store.playerCategoryIds.mapNotNull { PLAYER_CATEGORY_NAMES[it] }.joinToString(", ")
        val controller = when {
            FULL_CONTROLLER_CATEGORY in store.controllerCategoryIds -> "Full support"
            PARTIAL_CONTROLLER_CATEGORY in store.controllerCategoryIds -> "Partial support"
            else -> null
        }
        dl("info-list") {
            infoLine("Steam Deck", item.deck.label)
            if (platforms.isNotEmpty()) infoLine("Platforms", platforms)
            if (players.isNotEmpty()) infoLine("Players", players)
            controller?.let { infoLine("Controller", it) }
            val reviewCount = store.reviewCount
            if (reviewCount != null && reviewCount > 0) {
                infoLine("Reviews", "${store.reviewLabel.orEmpty()} (${store.reviewPercent ?: 0}% of ${Format.count(reviewCount)})".trim())
            }
            if (store.publishers.isNotEmpty()) infoLine("Publisher", store.publishers.joinToString(", "))
        }
        val tags = store.tagIds.mapNotNull { tagNames[it] }
        if (tags.isNotEmpty()) {
            div("pills tags") { tags.forEach { pill(it, "muted") } }
        }
        store.shortDescription?.takeIf { it.isNotBlank() }?.let { p("description") { +it } }
    }
}

private fun kotlinx.html.DL.infoLine(label: String, value: String) {
    dt { +label }
    dd { +value }
}

private fun FlowContent.loadingRow(text: String) {
    p("loading-row") {
        span("spinner") { attributes["aria-hidden"] = "true" }
        span { +text }
    }
}

private fun FlowContent.externalLink(href: String, text: String, classes: String) {
    a(href = href, classes = classes) {
        attributes["target"] = "_blank"
        attributes["rel"] = "noopener noreferrer"
        span { +text }
        icon("external")
    }
}

/** "Match on HowLongToBeat": opening it runs the same automatic searches as the app's dialog. */
private fun FlowContent.hltbDialog(item: GameItem) {
    dialogElement("dialog") {
        id = "hltb-dialog"
        attributes["aria-labelledby"] = "hltb-title"
        div("dialog-head") {
            div {
                h2 {
                    id = "hltb-title"
                    +"Match on HowLongToBeat"
                }
                p { +item.name }
            }
            button(type = ButtonType.button, classes = "icon-btn") {
                attributes["data-dialog-close"] = ""
                attributes["aria-label"] = "Close"
                icon("x")
            }
        }
        form(classes = "dialog-search") {
            attributes["hx-get"] = "/games/${item.appId}/hltb/search"
            attributes["hx-target"] = "#hltb-results"
            attributes["hx-swap"] = "innerHTML"
            attributes["hx-trigger"] = "submit, input changed delay:450ms from:#hltb-q"
            attributes["hx-sync"] = "this:replace"
            attributes["hx-indicator"] = "#hltb-dialog"
            label("search") {
                icon("search")
                input(type = InputType.search, name = "q") {
                    id = "hltb-q"
                    value = HltbMatcher.searchQuery(item.name)
                    attributes["aria-label"] = "Search HowLongToBeat"
                    attributes["autocomplete"] = "off"
                    attributes["enterkeyhint"] = "search"
                }
            }
        }
        div("match-results") {
            id = "hltb-results"
            attributes["hx-get"] = "/games/${item.appId}/hltb/search?auto=1"
            attributes["hx-trigger"] = "dialog-open"
            attributes["hx-swap"] = "innerHTML"
            attributes["hx-indicator"] = "#hltb-dialog"
            attributes["aria-live"] = "polite"
        }
        div("dialog-actions") {
            button(type = ButtonType.button, classes = "btn") {
                attributes["hx-post"] = "/games/${item.appId}/hltb/none"
                gameTarget()
                +"Not on HLTB"
            }
            button(type = ButtonType.button, classes = "btn") {
                attributes["hx-post"] = "/games/${item.appId}/hltb/auto"
                gameTarget()
                +"Auto-match"
            }
        }
    }
}

/** Results for the match dialog; [shownQuery] is the search that produced them when it differs from the typed one. */
fun hltbResultsFragment(appId: Int, results: List<HltbGame>, error: String?, shownQuery: String?): String = renderFragment {
    when {
        error != null -> p("error-text") { +error }
        results.isEmpty() -> p("muted-text") { +"No results. Try a shorter or different name." }
        else -> div {
            if (shownQuery != null) p("hint") { +"Results for “$shownQuery”" }
            ul("match-list") {
                results.forEach { result ->
                    li {
                        button(type = ButtonType.button, name = "id", classes = "match") {
                            value = result.id.toString()
                            attributes["hx-post"] = "/games/$appId/hltb/pick"
                            attributes["hx-target"] = "#game"
                            attributes["hx-select"] = "#game"
                            attributes["hx-swap"] = "outerHTML"
                            steamImage(listOfNotNull(result.imageUrl), "match-art")
                            span("match-info") {
                                strong { +result.name }
                                val meta = listOfNotNull(
                                    result.releaseYear.takeIf { it > 0 }?.toString(),
                                    result.type?.takeIf { it.isNotBlank() && it != "game" }?.uppercase(),
                                    result.platforms?.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (meta.isNotEmpty()) span("meta") { +meta }
                                val times = listOfNotNull(
                                    Format.seconds(result.main)?.let { "Main $it" },
                                    Format.seconds(result.plus)?.let { "+Extras $it" },
                                    Format.seconds(result.completionist)?.let { "100% $it" },
                                ).joinToString(" · ")
                                span("times") { +times.ifEmpty { "No times yet" } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** kotlinx.html has no <dialog> builder, so this declares one. */
private class DIALOG(consumer: kotlinx.html.TagConsumer<*>, classes: String?) :
    kotlinx.html.HTMLTag("dialog", consumer, kotlinx.html.attributesMapOf("class", classes), null, false, false),
    kotlinx.html.HtmlBlockTag

private fun FlowContent.dialogElement(classes: String, block: DIALOG.() -> Unit) {
    DIALOG(consumer, classes).visit(block)
}
