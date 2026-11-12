package dev.steamshelf.server.routes

import dev.steamshelf.data.hltb.HltbGame
import dev.steamshelf.data.hltb.HltbMatcher
import dev.steamshelf.data.hltb.HltbRateLimitedException
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.server.ServerContainer
import dev.steamshelf.server.userMessage
import dev.steamshelf.server.views.PageChrome
import dev.steamshelf.server.views.errorPage
import dev.steamshelf.server.views.gamePage
import dev.steamshelf.server.views.hltbResultsFragment
import dev.steamshelf.server.views.respondHtmlFragment
import dev.steamshelf.server.views.respondHtmlPage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException

fun Route.gameRoutes(container: ServerContainer, chrome: suspend ApplicationCall.() -> PageChrome) {
    val repository = container.repository
    val details = container.details

    /** Renders the game page; htmx requests select #game from it. */
    suspend fun RoutingContext.respondGame(item: GameItem, closeDialog: Boolean = false, toast: String? = null) {
        val fresh = details.item(item.appId) ?: item
        if (closeDialog || toast != null) call.response.header("HX-Trigger", hxTrigger(closeDialog, toast))
        val chromeValue = call.chrome()
        call.respondHtmlPage {
            gamePage(fresh, repository.tagNames.value, details.loading(fresh), chromeValue, container.clock())
        }
    }

    route("/games/{appId}") {
        /** The game, or a 404 page (and null) when it isn't in the library. */
        suspend fun RoutingContext.game(): GameItem? {
            val item = call.parameters["appId"]?.toIntOrNull()?.let { details.item(it) }
            if (item == null) {
                val chromeValue = call.chrome()
                call.respondHtmlPage(HttpStatusCode.NotFound) {
                    errorPage(chromeValue, "Game not found", "That game isn't in your library (any more).")
                }
            }
            return item
        }

        get {
            val item = game() ?: return@get
            respondGame(item)
        }

        post("/status") {
            val item = game() ?: return@post
            val status = when (call.receiveParameters()["status"]) {
                "finished" -> PlayStatus.FINISHED
                "dropped" -> PlayStatus.DROPPED
                "hidden" -> PlayStatus.HIDDEN
                else -> null
            }
            repository.setPlayStatus(item.appId, status)
            respondGame(item)
        }

        post("/hltb/confirm") {
            val item = game() ?: return@post
            repository.confirmHltbMatch(item.appId)
            respondGame(item)
        }

        post("/hltb/none") {
            val item = game() ?: return@post
            repository.setNoHltbMatch(item.appId)
            respondGame(item, closeDialog = true)
        }

        post("/hltb/auto") {
            val item = game() ?: return@post
            val failure = try {
                repository.rematchHltb(item.appId)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage()
            }
            respondGame(item, closeDialog = failure == null, toast = failure)
        }

        post("/hltb/pick") {
            val item = game() ?: return@post
            val match = call.receiveParameters()["id"]?.toIntOrNull()?.let(details::searchResult)
            if (match == null) {
                respondGame(item, toast = "That search result expired. Search again and pick it once more.")
                return@post
            }
            repository.setHltbMatch(item.appId, match)
            respondGame(item, closeDialog = true)
        }

        /** Typed searches use q; the dialog opening uses auto=1, which tries the app's queries until one finds something. */
        get("/hltb/search") {
            val item = game() ?: return@get
            val typed = call.request.queryParameters["q"]?.trim().orEmpty()
            val queries = if (call.request.queryParameters["auto"] == "1") {
                listOf(HltbMatcher.searchQuery(item.name)) + HltbMatcher.fallbackQueries(item.name)
            } else {
                listOf(typed)
            }.filter { it.isNotBlank() }.distinct()

            var results = emptyList<HltbGame>()
            var used: String? = null
            var error: String? = null
            try {
                for (query in queries) {
                    results = details.search(query)
                    used = query
                    if (results.isNotEmpty()) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HltbRateLimitedException) {
                error = e.userMessage()
            } catch (e: Exception) {
                error = "Search failed: ${e.userMessage()}"
            }
            // Say which search found these when the automatic one had to fall back to another name.
            val shown = used?.takeIf { it != queries.firstOrNull() }
            call.respondHtmlFragment(hltbResultsFragment(item.appId, results, error, shown))
        }
    }
}

/** An HX-Trigger header value. Header values must be ASCII, so everything else is escaped as JSON \u sequences. */
fun hxTrigger(closeDialog: Boolean = false, toast: String? = null): String {
    val parts = buildList {
        if (closeDialog) add("\"dialog-close\":true")
        if (toast != null) add("\"toast\":\"${jsonEscape(toast)}\"")
    }
    return parts.joinToString(",", "{", "}")
}

private fun jsonEscape(text: String): String = buildString {
    text.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' || c.code > 126 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
