package dev.steamshelf.server.routes

import dev.steamshelf.server.ServerContainer
import dev.steamshelf.server.library.LibraryPage
import dev.steamshelf.server.library.LibraryParams
import dev.steamshelf.server.library.LibraryToggle
import dev.steamshelf.server.library.LibraryView
import dev.steamshelf.server.views.LibrarySwap
import dev.steamshelf.server.views.PageChrome
import dev.steamshelf.server.views.libraryItemsFragment
import dev.steamshelf.server.views.libraryPage
import dev.steamshelf.server.views.respondHtmlFragment
import dev.steamshelf.server.views.respondHtmlPage
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.first

fun Route.libraryRoutes(container: ServerContainer, chrome: suspend ApplicationCall.() -> PageChrome) {
    val repository = container.repository

    get("/") {
        val params = call.request.queryParameters
        val saved = container.settings.libraryPrefs.first()
        var view = if (LibraryParams.hasState(params)) LibraryParams.parse(params) else LibraryView(saved)
        val toggle = LibraryToggle.of(params[LibraryParams.TOGGLE])
        if (toggle != null) view = toggle.apply(view)
        val htmx = call.request.header("HX-Request") == "true"
        // htmx re-requests the page when you go Back; that's not choosing a view.
        val restoring = call.request.header("HX-History-Restore-Request") == "true"
        // Like the app, the last view is remembered: a bare "/" opens it again.
        if (view.prefs != saved && !restoring) container.settings.saveLibraryPrefs(view.prefs)

        val page = LibraryPage.build(repository, view)
        // The form's URL carries "toggle" and hidden fields; show the canonical one instead. A background
        // refresh (app.js) and a Back restore mustn't add a history entry at all.
        if (htmx && !restoring && call.request.header("X-SteamShelf-Refresh") == null) {
            call.response.header("HX-Push-Url", LibraryParams.url(view))
        }
        if (htmx && toggle == LibraryToggle.CLEAR) call.response.header("HX-Trigger", """{"clear-search":true}""")
        val swap = LibrarySwap(htmx = htmx, filtersChanged = toggle != null && toggle != LibraryToggle.DIRECTION)
        val chromeValue = call.chrome()
        call.respondHtmlPage { libraryPage(page, chromeValue, repository.sync.value, swap, container.clock()) }
    }

    get("/library/items") {
        val params = call.request.queryParameters
        val offset = params["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = LibraryPage.build(repository, LibraryParams.parse(params))
        call.respondHtmlFragment(libraryItemsFragment(page, offset, container.clock()))
    }

    get("/random") {
        val params = call.request.queryParameters
        val view = if (LibraryParams.hasState(params)) LibraryParams.parse(params) else LibraryView(container.settings.libraryPrefs.first())
        val pick = LibraryPage.build(repository, view).items.randomOrNull()
        call.respondRedirect(if (pick != null) "/games/${pick.appId}" else LibraryParams.url(view))
    }
}
