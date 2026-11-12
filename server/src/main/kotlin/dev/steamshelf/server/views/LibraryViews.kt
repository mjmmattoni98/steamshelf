package dev.steamshelf.server.views

import dev.steamshelf.model.DeckFilter
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.LengthFilter
import dev.steamshelf.model.PlayStateFilter
import dev.steamshelf.model.PlayerModeFilter
import dev.steamshelf.model.ProtonFilter
import dev.steamshelf.model.SortField
import dev.steamshelf.model.SyncStatus
import dev.steamshelf.model.ViewMode
import dev.steamshelf.server.Format
import dev.steamshelf.server.label
import dev.steamshelf.server.library.LibraryPage
import dev.steamshelf.server.library.LibraryParams
import dev.steamshelf.server.library.LibraryToggle
import dev.steamshelf.server.library.key
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.ul

/** How an htmx response should update the page around #results (see [libraryPage]). */
data class LibrarySwap(
    /** The request came from htmx, so extra parts are marked for out-of-band swaps. */
    val htmx: Boolean = false,
    /** A chip or button changed the filters, so the filter panel has to be redrawn too. */
    val filtersChanged: Boolean = false,
)

/**
 * The library is one GET form: any change (a checkbox, the search box, a chip, which are submit buttons
 * named "toggle") asks the server for the page again, and htmx swaps #results plus whichever parts are
 * marked hx-swap-oob. The server answers with the canonical URL in HX-Push-Url.
 */
fun HTML.libraryPage(page: LibraryPage, chrome: PageChrome, sync: SyncStatus, swap: LibrarySwap, now: Long) {
    page("Library", chrome, "page-library") {
        form(action = "/", method = FormMethod.get, classes = "library") {
            id = "library"
            attributes["hx-get"] = "/"
            attributes["hx-target"] = "#results"
            attributes["hx-select"] = "#results"
            attributes["hx-swap"] = "outerHTML"
            attributes["hx-push-url"] = "true"
            attributes["hx-trigger"] = "change[target.name && target.id !== 'q'], input changed delay:300ms from:#q, submit"
            attributes["hx-sync"] = "this:replace"
            attributes["hx-disinherit"] = "*"
            // app.js puts this in the address bar when the page was opened as a bare "/".
            attributes["data-url"] = LibraryParams.url(page.view)
            toolbar(page, swap)
            div("library-layout") {
                filtersPanel(page, swap)
                results(page, sync, now)
            }
        }
        div("sheet-backdrop") { attributes["data-filters-close"] = "" }
    }
}

private fun kotlinx.html.HTMLTag.oobAttribute(swap: LibrarySwap, condition: Boolean = true) {
    if (swap.htmx && condition) attributes["hx-swap-oob"] = "true"
}

private fun FlowContent.toolbar(page: LibraryPage, swap: LibrarySwap) {
    val view = page.view
    div("toolbar") {
        label("search") {
            icon("search")
            input(type = InputType.search, name = LibraryParams.QUERY) {
                id = "q"
                value = view.query
                placeholder = "Search your library"
                attributes["aria-label"] = "Search your library"
                attributes["autocomplete"] = "off"
                attributes["enterkeyhint"] = "search"
                attributes["aria-keyshortcuts"] = "/"
                // Never swapped: the form listens to this element's input events. "Clear" empties it from app.js.
            }
        }
        button(type = ButtonType.button, classes = "btn filters-toggle") {
            attributes["data-filters-open"] = ""
            attributes["aria-controls"] = "filters"
            attributes["aria-expanded"] = "false"
            icon("filter")
            span { +"Filters" }
            span("badge") {
                id = "filter-badge"
                oobAttribute(swap)
                val count = view.filter.activeCount
                if (count == 0) attributes["hidden"] = "hidden"
                +count.toString()
            }
        }
        label("select") {
            span("sr-only") { +"Sort by" }
            select {
                name = LibraryParams.SORT
                id = "sort"
                SortField.entries.forEach { field ->
                    option {
                        value = field.key
                        selected = field == view.prefs.sort.field
                        +field.label
                    }
                }
            }
        }
        div("segmented") {
            attributes["role"] = "radiogroup"
            attributes["aria-label"] = "Layout"
            listOf(ViewMode.LIST to "list", ViewMode.GRID to "grid").forEach { (mode, iconName) ->
                label {
                    attributes["title"] = if (mode == ViewMode.LIST) "List" else "Grid"
                    input(type = InputType.radio, name = LibraryParams.VIEW) {
                        value = mode.key
                        checked = view.prefs.viewMode == mode
                    }
                    icon(iconName)
                    span("sr-only") { +(if (mode == ViewMode.LIST) "List" else "Grid") }
                }
            }
        }
    }
}

private fun FlowContent.filtersPanel(page: LibraryPage, swap: LibrarySwap) {
    val filter = page.view.filter
    aside("filters") {
        id = "filters"
        attributes["aria-label"] = "Filters"
        div("filters-head") {
            h2 { +"Filters" }
            button(type = ButtonType.button, classes = "icon-btn") {
                attributes["data-filters-close"] = ""
                attributes["aria-label"] = "Close filters"
                icon("x")
            }
        }
        div("filters-body") {
            id = "filters-body"
            oobAttribute(swap, swap.filtersChanged)
            filterGroup("Play status") {
                chips(LibraryParams.PLAY, PlayStateFilter.entries, filter.playStates) { it.label }
                switchRow("Show hidden games", LibraryParams.HIDDEN, filter.showHidden)
            }
            filterGroup("ProtonDB & Linux", "How well it runs on Linux / Steam Deck via Proton") {
                chips(LibraryParams.PROTON, ProtonFilter.entries, filter.proton) { it.label }
            }
            filterGroup("Steam Deck") {
                chips(LibraryParams.DECK, DeckFilter.entries, filter.deck) { it.label }
            }
            filterGroup("Time to beat", "HowLongToBeat main story") {
                chips(LibraryParams.LENGTH, LengthFilter.entries, filter.length) { it.label }
                switchRow("Match needs review", LibraryParams.HLTB_REVIEW, filter.hltbNeedsReview)
            }
            filterGroup("Players") {
                chips(LibraryParams.PLAYERS, PlayerModeFilter.entries, filter.playerModes) { it.label }
                switchRow("Full controller support", LibraryParams.CONTROLLER, filter.fullControllerSupport)
            }
            filterGroup("Steam reviews") {
                div("chip-list") {
                    (listOf(0) + LibraryParams.REVIEW_THRESHOLDS).forEach { threshold ->
                        label("chip") {
                            input(type = InputType.radio, name = LibraryParams.REVIEW) {
                                value = threshold.toString()
                                checked = filter.minReviewPercent == threshold
                            }
                            span {
                                +when (threshold) {
                                    0 -> "Any"
                                    70 -> "70%+ positive"
                                    else -> "$threshold%+"
                                }
                            }
                        }
                    }
                }
            }
            tagsGroup(page)
        }
        div("filters-foot") {
            button(type = ButtonType.submit, name = LibraryParams.TOGGLE, classes = "btn ghost") {
                value = LibraryToggle.CLEAR.key
                +"Reset all"
            }
            button(type = ButtonType.button, classes = "btn primary") {
                attributes["data-filters-close"] = ""
                span {
                    id = "sheet-count"
                    oobAttribute(swap)
                    val n = page.items.size
                    +"Show ${Format.number(n)} ${Format.plural(n, "game", "games")}"
                }
            }
        }
    }
}

private fun FlowContent.filterGroup(title: String, hint: String? = null, content: FlowContent.() -> Unit) {
    fieldSet("filter-group") {
        legend { +title }
        if (hint != null) p("hint") { +hint }
        content()
    }
}

private fun <E : Enum<E>> FlowContent.chips(name: String, options: List<E>, selected: Set<E>, label: (E) -> String) {
    div("chip-list") {
        options.forEach { option ->
            label("chip") {
                input(type = InputType.checkBox, name = name) {
                    value = option.key
                    checked = option in selected
                }
                span { +label(option) }
            }
        }
    }
}

private fun FlowContent.switchRow(text: String, name: String, on: Boolean) {
    label("switch-row") {
        span { +text }
        input(type = InputType.checkBox, name = name, classes = "switch") {
            value = "1"
            checked = on
            attributes["role"] = "switch"
        }
    }
}

private const val TAG_PREVIEW = 24

private fun FlowContent.tagsGroup(page: LibraryPage) {
    val selected = page.view.filter.tags
    filterGroup("Tags", "Games must have every selected tag") {
        if (page.tags.isEmpty()) {
            p("hint") { +"Tags show up once store details have loaded." }
            return@filterGroup
        }
        label("search small") {
            icon("search")
            input(type = InputType.search) {
                id = "tag-search"
                placeholder = "Search tags"
                attributes["aria-label"] = "Search tags"
                attributes["autocomplete"] = "off"
            }
        }
        val ordered = page.tags.filter { it.id in selected } + page.tags.filterNot { it.id in selected }
        div("chip-list tag-list collapsed") {
            id = "tag-list"
            ordered.forEachIndexed { index, tag ->
                label(if (index >= TAG_PREVIEW && tag.id !in selected) "chip extra" else "chip") {
                    attributes["data-name"] = tag.name.lowercase()
                    input(type = InputType.checkBox, name = LibraryParams.TAG) {
                        value = tag.id.toString()
                        checked = tag.id in selected
                    }
                    span { +"${tag.name} · ${Format.number(tag.count)}" }
                }
            }
        }
        if (ordered.size > TAG_PREVIEW) {
            button(type = ButtonType.button, classes = "link-btn") {
                attributes["data-tags-more"] = ""
                attributes["data-more"] = "Show all ${ordered.size} tags"
                attributes["data-less"] = "Show fewer tags"
                attributes["aria-controls"] = "tag-list"
                attributes["aria-expanded"] = "false"
                +"Show all ${ordered.size} tags"
            }
        }
    }
}

private fun FlowContent.results(page: LibraryPage, sync: SyncStatus, now: Long) {
    val view = page.view
    val filter = view.filter
    section("results") {
        id = "results"
        attributes["aria-label"] = "Games"
        input(type = InputType.hidden, name = LibraryParams.PREVIOUS_SORT) { value = view.prefs.sort.field.key }
        input(type = InputType.hidden, name = LibraryParams.DESCENDING) { value = if (view.prefs.sort.descending) "1" else "0" }

        div("chips") {
            attributes["role"] = "group"
            attributes["aria-label"] = "Quick filters"
            if (filter.activeCount > 0) {
                toggleButton(LibraryToggle.CLEAR, "chip action", pressed = null) {
                    icon("x")
                    span { +"Clear" }
                }
            }
            // Only offered when there's something to check (or the filter is on, so it can be turned off).
            if (page.reviewCount > 0 || filter.hltbNeedsReview) {
                toggleButton(LibraryToggle.HLTB_REVIEW, "chip warning", pressed = filter.hltbNeedsReview) {
                    icon("warning")
                    span { +(if (page.reviewCount > 0) "Check HLTB matches · ${page.reviewCount}" else "Check HLTB matches") }
                }
            }
            LibraryToggle.QUICK.forEach { toggle ->
                toggleButton(toggle, "chip", pressed = toggle.isOn(filter)) { span { +toggle.label.orEmpty() } }
            }
        }

        when {
            page.totalCount == 0 && sync.running -> emptyState(
                "Loading your library…",
                "This takes a few seconds. Ratings and times follow in the background.",
            ) { div("spinner large") { attributes["aria-hidden"] = "true" } }
            page.totalCount == 0 -> emptyState("No games yet", "Load your Steam library to get started.") {
                button(type = ButtonType.button, classes = "btn primary") {
                    attributes["hx-post"] = "/sync"
                    attributes["hx-swap"] = "none"
                    +"Load library"
                }
            }
            page.items.isEmpty() -> emptyState("No games match", "Try removing a filter or changing the search.") {
                toggleButton(LibraryToggle.CLEAR, "btn", pressed = null) { +"Clear filters" }
            }
            else -> {
                div("summary") {
                    p("stats") { +statsLine(page) }
                    div("summary-actions") {
                        val descending = view.prefs.sort.descending
                        toggleButton(LibraryToggle.DIRECTION, "icon-btn", pressed = null) {
                            attributes["title"] = if (descending) "Descending (click for ascending)" else "Ascending (click for descending)"
                            attributes["aria-label"] = "Sort direction: ${if (descending) "descending" else "ascending"}"
                            icon(if (descending) "arrow-down" else "arrow-up")
                        }
                        a(href = LibraryParams.url(view, "/random"), classes = "btn") {
                            attributes["title"] = "Pick a random game from this list"
                            icon("dice")
                            span { +"Random pick" }
                        }
                    }
                }
                ul(if (view.prefs.viewMode == ViewMode.GRID) "games grid" else "games list") {
                    gameItems(page, offset = 0, now = now)
                }
            }
        }
    }
}

private fun FlowContent.toggleButton(
    toggle: LibraryToggle,
    classes: String,
    pressed: Boolean?,
    content: kotlinx.html.BUTTON.() -> Unit,
) {
    button(type = ButtonType.submit, name = LibraryParams.TOGGLE, classes = classes) {
        value = toggle.key
        if (pressed != null) attributes["aria-pressed"] = pressed.toString()
        content()
    }
}

private fun statsLine(page: LibraryPage): String {
    val n = page.items.size
    val count = if (n == page.shownTotal) {
        "${Format.number(n)} ${Format.plural(n, "game", "games")}"
    } else {
        "${Format.number(n)} of ${Format.number(page.shownTotal)} ${Format.plural(page.shownTotal, "game", "games")}"
    }
    val played = "$count · ${Format.hours(page.stats.playedHours)} played"
    return if (page.stats.withHltb > 0) "$played · ≈ ${Format.hours(page.stats.remainingHours)} left to beat" else played
}

/**
 * One page of cards from [offset], plus a placeholder that loads the next page when it scrolls into view
 * (GET /library/items answers with `<ul class="page">` and the placeholder swaps itself for its items).
 */
fun UL.gameItems(page: LibraryPage, offset: Int, now: Long) {
    val view = page.view
    val chunk = page.items.drop(offset).take(LibraryPage.PAGE_SIZE)
    chunk.forEach { item ->
        li {
            if (view.prefs.viewMode == ViewMode.GRID) gameCell(item) else gameRow(item, view.prefs.sort.field, now)
        }
    }
    val next = offset + chunk.size
    if (next < page.items.size) {
        li("more") {
            val params = LibraryParams.encode(view).let { if (it.isEmpty()) "" else "$it&" }
            attributes["hx-get"] = "/library/items?${params}offset=$next"
            attributes["hx-trigger"] = "revealed"
            attributes["hx-select"] = ".page > li"
            attributes["hx-swap"] = "outerHTML"
            span("spinner") { attributes["aria-hidden"] = "true" }
            span("sr-only") { +"Loading more games" }
        }
    }
}

private fun FlowContent.gameRow(item: GameItem, sortField: SortField, now: Long) {
    a(href = "/games/${item.appId}", classes = "game-row") {
        steamImage(item.headerUrls, "art")
        div("info") {
            h3("name") { +item.name }
            p("sub") { +subtitle(item, sortField, now) }
            div("pills") {
                statusPill(item.status)
                protonPill(item.protonTier, item.linuxNative)
                deckPill(item.deck, withPrefix = false)
                hltbPill(item.beatHours, uncertain = item.hltbNeedsReview)
            }
            item.progress?.takeIf { item.game.playtimeForever > 0 }?.let {
                progressBar(it, label = "Share of a typical main-story run played")
            }
        }
    }
}

private fun FlowContent.gameCell(item: GameItem) {
    a(href = "/games/${item.appId}", classes = "game-cell") {
        div("cover") {
            steamImage(item.capsuleUrls, "art", alt = item.name)
            div("pills") {
                statusPill(item.status)
                protonPill(item.protonTier, item.linuxNative)
                hltbPill(item.beatHours, uncertain = item.hltbNeedsReview)
                deckPill(item.deck, withPrefix = false)
            }
        }
        span("name") { +item.name }
    }
}

/** What the second line shows depends on the sort, so the number you're sorting by is visible. */
private fun subtitle(item: GameItem, sortField: SortField, now: Long): String {
    val played = Format.minutes(item.game.playtimeForever)
    return when (sortField) {
        SortField.LAST_PLAYED ->
            if (item.game.lastPlayed > 0) "Last played ${Format.shortRelativeDate(item.game.lastPlayed, now).lowercase()}" else played
        SortField.TIME_LEFT -> item.remainingHours?.let { "$played · ${Format.hours(it)} left" } ?: played
        SortField.REVIEWS -> item.store?.reviewPercent?.let { "$played · $it% positive" } ?: played
        SortField.RELEASE -> item.releaseYear?.let { "$played · $it" } ?: played
        else ->
            if (item.game.playtime2Weeks > 0) "$played · ${Format.hours(item.game.playtime2Weeks / 60.0)} last 2 weeks"
            else played
    }
}

/** The fragment for GET /library/items. */
fun libraryItemsFragment(page: LibraryPage, offset: Int, now: Long): String = renderFragment {
    ul("page") { gameItems(page, offset, now) }
}
