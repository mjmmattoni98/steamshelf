package dev.steamshelf.server.routes

import dev.steamshelf.model.Account
import dev.steamshelf.server.BuildInfo
import dev.steamshelf.server.PendingOwner
import dev.steamshelf.server.STORE_LANGUAGES
import dev.steamshelf.server.ServerContainer
import dev.steamshelf.server.WebSession
import dev.steamshelf.server.label
import dev.steamshelf.server.message
import dev.steamshelf.server.owner
import dev.steamshelf.server.storeDefaultsFor
import dev.steamshelf.server.userMessage
import dev.steamshelf.server.views.PageChrome
import dev.steamshelf.server.views.SettingsModel
import dev.steamshelf.server.views.respondHtmlPage
import dev.steamshelf.server.views.settingsPage
import dev.steamshelf.server.views.setupPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

private val API_KEY = Regex("^[A-Fa-f0-9]{32}$")
private val COUNTRY = Regex("^[A-Za-z]{2}$")
private const val API_KEY_FORMAT = "A Steam Web API key is 32 letters and numbers (0–9, A–F)."

/** First run: after signing in through Steam, the API key (checked against Steam before it's saved). */
fun Route.setupRoutes(container: ServerContainer, chrome: suspend ApplicationCall.() -> PageChrome) {
    get("/setup") {
        val pending = checkNotNull(call.principal<PendingOwner>())
        val (language, country) = storeDefaultsFor(call.request.header(HttpHeaders.AcceptLanguage))
        val chromeValue = call.chrome()
        call.respondHtmlPage { setupPage(chromeValue, pending.steamId, "", language, country, null) }
    }

    post("/setup") {
        val pending = checkNotNull(call.principal<PendingOwner>())
        val form = call.receiveParameters()
        val key = form["api_key"]?.trim().orEmpty()
        val language = form["language"]?.takeIf { code -> STORE_LANGUAGES.any { it.first == code } } ?: "english"
        val country = form["country"]?.trim()?.takeIf(COUNTRY::matches)?.uppercase() ?: "US"
        val error = if (!API_KEY.matches(key)) {
            API_KEY_FORMAT
        } else {
            try {
                val profile = container.steam.getPlayerSummary(key, pending.steamId)
                // Confirms the key can actually read this library before we commit to it.
                container.steam.getOwnedGames(key, pending.steamId, includeFreeGames = false)
                container.serverSettings.setStore(language, country)
                container.settings.saveAccount(Account(pending.steamId, key, profile.personaName, profile.avatarUrl, profile.profileUrl))
                container.repository.sync()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage()
            }
        }
        if (error == null) return@post call.respondRedirect("/")
        val chromeValue = call.chrome()
        call.respondHtmlPage(HttpStatusCode.UnprocessableEntity) {
            setupPage(chromeValue, pending.steamId, key, language, country, error)
        }
    }
}

fun Route.settingsRoutes(container: ServerContainer, chrome: suspend ApplicationCall.() -> PageChrome) {
    val settings = container.settings
    val repository = container.repository

    suspend fun RoutingContext.respondSettings(toast: String? = null, keyError: String? = null) {
        if (toast != null) call.response.header("HX-Trigger", hxTrigger(toast = toast))
        val model = SettingsModel(
            account = settings.account.first() ?: call.owner().account,
            syncPrefs = settings.syncPrefs.first(),
            serverPrefs = container.serverSettings.prefs.value,
            sync = repository.sync.value,
            version = BuildInfo.version,
            now = container.clock(),
            keyError = keyError,
        )
        val chromeValue = call.chrome()
        call.respondHtmlPage(if (keyError != null) HttpStatusCode.UnprocessableEntity else HttpStatusCode.OK) {
            settingsPage(model, chromeValue)
        }
    }

    get("/settings") { respondSettings() }

    post("/settings/sources") {
        val form = call.receiveParameters()
        // Missing means the switch is off (unchecked boxes aren't submitted). Changes start the fetches they need.
        settings.setAutoProtonDb(form["protondb"] != null)
        settings.setAutoHltb(form["hltb"] != null)
        settings.setIncludeFreeGames(form["free_games"] != null)
        respondSettings()
    }

    post("/settings/store") {
        val form = call.receiveParameters()
        val language = form["language"]?.takeIf { code -> STORE_LANGUAGES.any { it.first == code } }
        val country = form["country"]?.trim()?.takeIf(COUNTRY::matches)?.uppercase()
        if (language == null || country == null) return@post respondSettings(toast = "Pick a language and a two-letter country code.")
        val before = container.serverSettings.prefs.value
        container.serverSettings.setStore(language, country)
        container.serverSettings.prefs.first { it.storeLanguage == language && it.storeCountry == country }
        // A sync refetches store text and tag names that are in another language. A new country only applies as
        // store entries are refreshed, since refetching everything would also redo every HowLongToBeat lookup.
        val toast = when {
            before.storeLanguage != language -> {
                repository.sync()
                "Saved. Store details are being fetched in the new language."
            }
            before.storeCountry != country -> "Saved. The country applies as store details are refreshed."
            else -> "Saved."
        }
        respondSettings(toast = toast)
    }

    post("/settings/api-key") {
        val key = call.receiveParameters()["api_key"]?.trim().orEmpty()
        val current = settings.account.first() ?: return@post call.respond(HttpStatusCode.Conflict)
        if (!API_KEY.matches(key)) return@post respondSettings(keyError = API_KEY_FORMAT)
        val error = try {
            container.steam.getPlayerSummary(key, current.steamId)
            settings.saveAccount(current.copy(apiKey = key))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.userMessage()
        }
        if (error != null) respondSettings(keyError = error) else respondSettings(toast = "API key saved.")
    }

    post("/settings/sign-out") {
        repository.clearAll()
        settings.clearAccount()
        container.serverSettings.invalidateSessions(container.clock())
        call.sessions.clear<WebSession>()
        call.response.header("HX-Redirect", "/login")
        call.respond(HttpStatusCode.NoContent)
    }
}

fun Route.syncRoutes(container: ServerContainer) {
    val repository = container.repository

    post("/sync") {
        repository.sync()
        call.respond(HttpStatusCode.NoContent)
    }

    post("/sync/stop") {
        repository.cancelSync()
        call.respond(HttpStatusCode.NoContent)
    }

    post("/sync/refresh") {
        repository.sync(force = true)
        call.response.header("HX-Trigger", hxTrigger(toast = "Refreshing your library and all ratings in the background."))
        call.respond(HttpStatusCode.NoContent)
    }

    post("/sync/clear-cache") {
        repository.clearEnrichment()
        call.response.header("HX-Trigger", hxTrigger(toast = "Cleared. Ratings and times are being fetched again."))
        call.respond(HttpStatusCode.NoContent)
    }

    /** The sync status as it changes, for the top bar (app.js). */
    sse("/events") {
        heartbeat { period = 20.seconds }
        repository.sync.collect { status ->
            val data = buildJsonObject {
                put("running", status.running)
                put("phase", status.phase?.name)
                put("label", status.label())
                put("done", status.done)
                put("total", status.total)
                put("errorId", status.errorId)
                put("error", status.error?.message())
            }
            send(ServerSentEvent(data = data.toString(), event = "sync"))
        }
    }
}
