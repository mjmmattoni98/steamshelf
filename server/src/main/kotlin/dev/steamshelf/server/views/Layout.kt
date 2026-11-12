package dev.steamshelf.server.views

import dev.steamshelf.model.Account
import dev.steamshelf.model.SyncStatus
import dev.steamshelf.server.label
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.html.BODY
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.h2
import kotlinx.html.img
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.appendHTML
import kotlinx.html.title
import kotlinx.html.unsafe

/** Shared by every page: who is signed in, the sync status for the top bar, and the asset version for cache busting. */
data class PageChrome(
    val account: Account? = null,
    val sync: SyncStatus? = null,
    val assetVersion: String,
)

/** A whole page; [title] goes before " · SteamShelf" in the browser tab. */
fun HTML.page(title: String?, chrome: PageChrome, bodyClass: String, content: MAIN.() -> Unit) {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1, viewport-fit=cover")
        meta(name = "color-scheme", content = "dark light")
        // The browser bar follows the page's colours (the phone's address bar, the installed app's title bar).
        meta(name = "theme-color", content = "#10151c") { attributes["media"] = "(prefers-color-scheme: dark)" }
        meta(name = "theme-color", content = "#f6f8fb") { attributes["media"] = "(prefers-color-scheme: light)" }
        meta(name = "htmx-config", content = """{"historyCacheSize":0,"scrollIntoViewOnBoost":false,"defaultFocusScroll":false}""")
        title { +(title?.let { "$it · SteamShelf" } ?: "SteamShelf") }
        link(rel = "manifest", href = "/manifest.webmanifest")
        link(rel = "icon", href = "/static/icon.svg", type = "image/svg+xml")
        link(rel = "apple-touch-icon", href = "/static/icon-192.png")
        link(rel = "stylesheet", href = "/static/app.css?v=${chrome.assetVersion}")
        script(src = "/static/htmx.min.js?v=${chrome.assetVersion}") { defer = true }
        script(src = "/static/app.js?v=${chrome.assetVersion}") { defer = true }
    }
    body(classes = bodyClass) {
        unsafe { +ICON_SPRITE }
        a(href = "#main", classes = "skip-link") { +"Skip to content" }
        topBar(chrome)
        main {
            id = "main"
            content()
        }
        div("toasts") {
            id = "toasts"
            attributes["role"] = "status"
            attributes["aria-live"] = "polite"
        }
    }
}

private fun BODY.topBar(chrome: PageChrome) {
    header("topbar") {
        a(href = "/", classes = "brand") {
            img(src = "/static/icon.svg", alt = "") { width = "28"; height = "28" }
            span { +"SteamShelf" }
        }
        chrome.account?.let { account ->
            val sync = chrome.sync ?: SyncStatus()
            syncBar(sync)
            nav("top-actions") {
                attributes["aria-label"] = "Account"
                button(type = ButtonType.button, classes = "icon-btn") {
                    id = "sync-now"
                    attributes["hx-post"] = "/sync"
                    attributes["hx-swap"] = "none"
                    attributes["title"] = "Sync now"
                    attributes["aria-label"] = "Sync now"
                    if (sync.running) attributes["disabled"] = "disabled"
                    icon("refresh")
                }
                a(href = "/settings", classes = "avatar-link") {
                    attributes["title"] = "Settings"
                    val avatar = account.avatarUrl
                    if (avatar != null) {
                        img(src = avatar, alt = "Settings", classes = "avatar") { width = "32"; height = "32" }
                    } else {
                        icon("settings")
                        span("sr-only") { +"Settings" }
                    }
                }
            }
        }
    }
}

/** Filled in by app.js from /events; rendered here so the first paint is already right. */
private fun FlowContent.syncBar(sync: SyncStatus) {
    div("sync") {
        id = "sync"
        attributes["data-error-id"] = sync.errorId.toString()
        attributes["data-running"] = sync.running.toString()
        attributes["data-phase"] = sync.phase?.name.orEmpty()
        if (!sync.running) attributes["hidden"] = "hidden"
        div("sync-text") {
            span("spinner") { attributes["aria-hidden"] = "true" }
            span("sync-label") { +sync.label() }
        }
        div(if (sync.total > 0) "bar" else "bar indeterminate") {
            span { attributes["style"] = "width:${if (sync.total > 0) sync.done * 100 / sync.total else 0}%" }
        }
        button(type = ButtonType.button, classes = "icon-btn small") {
            attributes["hx-post"] = "/sync/stop"
            attributes["hx-swap"] = "none"
            attributes["title"] = "Stop syncing"
            attributes["aria-label"] = "Stop syncing"
            icon("stop")
        }
    }
}

/** An icon from [ICON_SPRITE]; decorative, so screen readers skip it. */
fun FlowContent.icon(name: String, classes: String = "icon") {
    consumer.onTagContentUnsafe { +"""<svg class="$classes" aria-hidden="true" focusable="false"><use href="#i-$name"/></svg>""" }
}

/** Renders a full page as a string, without pretty-printing (whitespace between inline elements matters in places). */
fun renderPage(block: HTML.() -> Unit): String = buildString {
    append("<!DOCTYPE html>\n")
    appendHTML(prettyPrint = false).html(block = block)
}

/** Renders loose elements (htmx fragments). */
fun renderFragment(block: TagConsumer<StringBuilder>.() -> Unit): String {
    val out = StringBuilder()
    out.appendHTML(prettyPrint = false).block()
    return out.toString()
}

suspend fun ApplicationCall.respondHtmlPage(status: HttpStatusCode = HttpStatusCode.OK, block: HTML.() -> Unit) {
    // Pages change with every sync: always revalidate, but stay eligible for the back/forward cache.
    response.header(HttpHeaders.CacheControl, "no-cache")
    response.header(HttpHeaders.Vary, "HX-Request")
    respondText(renderPage(block), ContentType.Text.Html.withParameter("charset", "utf-8"), status)
}

suspend fun ApplicationCall.respondHtmlFragment(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
}

fun FlowContent.emptyState(title: String, message: String, action: (FlowContent.() -> Unit)? = null) {
    div("empty-state") {
        h2 { +title }
        p { +message }
        action?.invoke(this)
    }
}

/** Feather-style icons (MIT), stroked with currentColor so they follow the text colour. */
private val ICON_SPRITE = """
<svg xmlns="http://www.w3.org/2000/svg" style="display:none">
<symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><line x1="20" y1="20" x2="16.2" y2="16.2"/></symbol>
<symbol id="i-filter" viewBox="0 0 24 24"><line x1="4" y1="6" x2="20" y2="6"/><line x1="7" y1="12" x2="17" y2="12"/><line x1="10" y1="18" x2="14" y2="18"/></symbol>
<symbol id="i-x" viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></symbol>
<symbol id="i-check" viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"/></symbol>
<symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/></symbol>
<symbol id="i-warning" viewBox="0 0 24 24"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></symbol>
<symbol id="i-refresh" viewBox="0 0 24 24"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></symbol>
<symbol id="i-grid" viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/></symbol>
<symbol id="i-list" viewBox="0 0 24 24"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></symbol>
<symbol id="i-arrow-down" viewBox="0 0 24 24"><line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/></symbol>
<symbol id="i-arrow-up" viewBox="0 0 24 24"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></symbol>
<symbol id="i-arrow-left" viewBox="0 0 24 24"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></symbol>
<symbol id="i-external" viewBox="0 0 24 24"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></symbol>
<symbol id="i-flag" viewBox="0 0 24 24"><path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/><line x1="4" y1="22" x2="4" y2="15"/></symbol>
<symbol id="i-block" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><line x1="5.6" y1="5.6" x2="18.4" y2="18.4"/></symbol>
<symbol id="i-eye-off" viewBox="0 0 24 24"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></symbol>
<symbol id="i-terminal" viewBox="0 0 24 24"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></symbol>
<symbol id="i-info" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></symbol>
<symbol id="i-check-circle" viewBox="0 0 24 24"><path d="M21 11.08V12a9 9 0 1 1-5.34-8.22"/><polyline points="21 4 12 13.01 9 10.01"/></symbol>
<symbol id="i-dice" viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="4"/><circle cx="8" cy="8" r="1.3" fill="currentColor"/><circle cx="16" cy="8" r="1.3" fill="currentColor"/><circle cx="12" cy="12" r="1.3" fill="currentColor"/><circle cx="8" cy="16" r="1.3" fill="currentColor"/><circle cx="16" cy="16" r="1.3" fill="currentColor"/></symbol>
<symbol id="i-edit" viewBox="0 0 24 24"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></symbol>
<symbol id="i-stop" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/></symbol>
<symbol id="i-settings" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></symbol>
</svg>
""".trimIndent()
