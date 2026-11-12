package dev.steamshelf.server

import dev.steamshelf.data.steam.SteamApiException
import dev.steamshelf.data.steam.SteamOpenId
import dev.steamshelf.model.Account
import dev.steamshelf.server.views.PageChrome
import dev.steamshelf.server.views.loginPage
import dev.steamshelf.server.views.notOwnerPage
import dev.steamshelf.server.views.respondHtmlPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.principal
import io.ktor.server.auth.session
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sessions.SessionSerializer
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The signed session cookie: who signed in through Steam, and when. */
data class WebSession(val steamId: String, val issuedAt: Long)

/** Signed in as the account this server belongs to. */
data class Owner(val account: Account)

/** Signed in through Steam on a server that isn't set up yet; may enter the API key. */
data class PendingOwner(val steamId: String)

const val OWNER_AUTH = "owner"
const val SETUP_AUTH = "setup"
private const val SESSION_COOKIE = "steamshelf_session"
private const val SESSION_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

private object WebSessionSerializer : SessionSerializer<WebSession> {
    override fun serialize(session: WebSession): String = "${session.steamId}.${session.issuedAt}"

    // A cookie in an unexpected shape is treated like no session (validation rejects the empty id).
    override fun deserialize(text: String): WebSession {
        val id = text.substringBefore('.')
        val issuedAt = text.substringAfter('.', "").toLongOrNull()
        return if (issuedAt == null) WebSession("", 0) else WebSession(id, issuedAt)
    }
}

fun Application.installAuth(container: ServerContainer, sessionKey: ByteArray) {
    val config = container.config
    install(Sessions) {
        cookie<WebSession>(SESSION_COOKIE) {
            serializer = WebSessionSerializer
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = !config.allowHttp
            cookie.maxAgeInSeconds = SESSION_MAX_AGE_MS / 1000
            // Lax still sends the cookie when Steam redirects back to the callback, but not with cross-site POSTs.
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(sessionKey))
        }
    }
    install(Authentication) {
        session<WebSession>(OWNER_AUTH) {
            validate { session ->
                if (!container.sessionIsCurrent(session)) return@validate null
                container.settings.account.first()?.takeIf { it.steamId == session.steamId }?.let(::Owner)
            }
            challenge { redirectToLogin(call) }
        }
        session<WebSession>(SETUP_AUTH) {
            validate { session ->
                if (!container.sessionIsCurrent(session)) return@validate null
                if (container.settings.account.first() != null) null else PendingOwner(session.steamId)
            }
            challenge { redirectToLogin(call) }
        }
    }
    installSameOriginCheck(config)
}

private fun ServerContainer.sessionIsCurrent(session: WebSession): Boolean {
    val now = clock()
    return session.steamId.isNotEmpty() &&
        session.issuedAt >= serverSettings.prefs.value.sessionsValidAfter &&
        now - session.issuedAt < SESSION_MAX_AGE_MS
}

private suspend fun redirectToLogin(call: ApplicationCall) {
    when {
        // htmx would swap the login page into part of the page; ask it to navigate instead.
        call.request.header("HX-Request") == "true" -> {
            call.response.header("HX-Redirect", "/login")
            call.respond(HttpStatusCode.Unauthorized)
        }
        call.request.uri.startsWith("/events") -> call.respond(HttpStatusCode.Unauthorized)
        else -> call.respondRedirect("/login")
    }
}

/**
 * Refuses state-changing requests from other sites (on top of the SameSite cookie): browsers send Origin with
 * every POST, and it has to be this server.
 */
private fun Application.installSameOriginCheck(config: ServerConfig) {
    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return@intercept
        val origin = call.request.header(HttpHeaders.Origin)
        val fetchSite = call.request.header("Sec-Fetch-Site")
        val allowed = setOfNotNull(call.baseUrl(null), config.publicUrl)
        val crossSite = (origin != null && origin !in allowed) || fetchSite == "cross-site"
        if (crossSite) {
            call.respondText("Cross-site request refused.", status = HttpStatusCode.Forbidden)
            finish()
        }
    }
}

/**
 * Where the browser reaches this server, e.g. https://raspberrypi.tailnet.ts.net. [config]'s public URL wins;
 * otherwise it comes from the request, which behind `tailscale serve` carries the original host and
 * X-Forwarded-Proto: https (the XForwardedHeaders plugin applies them).
 */
fun ApplicationCall.baseUrl(config: ServerConfig?): String {
    config?.publicUrl?.let { return it }
    val origin = request.origin
    val defaultPort = if (origin.scheme == "https") 443 else 80
    val port = origin.serverPort
    return "${origin.scheme}://${origin.serverHost}${if (port == defaultPort || port <= 0) "" else ":$port"}"
}

/**
 * Steam's assertions carry a timestamped nonce; each one is accepted once and only while fresh, so a callback
 * URL that leaked (browser history, logs) can't be replayed to sign in.
 */
class NonceGuard(private val clock: () -> Long) {
    private val seen = ConcurrentHashMap<String, Long>()

    fun accept(nonce: String?): Boolean {
        if (nonce.isNullOrBlank() || nonce.length < 20) return false
        val issued = runCatching { Instant.parse(nonce.take(20)).toEpochMilli() }.getOrNull() ?: return false
        val now = clock()
        if (now - issued > MAX_AGE_MS || issued - now > MAX_AGE_MS) return false
        seen.entries.removeIf { now - it.value > 2 * MAX_AGE_MS }
        return seen.putIfAbsent(nonce, now) == null
    }

    private companion object {
        const val MAX_AGE_MS = 5 * 60 * 1000L
    }
}

private val log = LoggerFactory.getLogger("dev.steamshelf.server.Auth")

fun Route.authRoutes(container: ServerContainer, chrome: () -> PageChrome) {
    val nonces = NonceGuard(container.clock)

    get("/login") {
        val session = call.sessions.get<WebSession>()
        val account = container.settings.account.first()
        if (session != null && account != null && session.steamId == account.steamId && container.sessionIsCurrent(session)) {
            return@get call.respondRedirect("/")
        }
        val error = when (call.request.queryParameters["error"]) {
            "insecure" -> "Open SteamShelf through its HTTPS address (https://…ts.net) to sign in, or set STEAMSHELF_ALLOW_HTTP=true for plain HTTP."
            else -> call.request.queryParameters["message"]?.take(300)
        }
        call.respondHtmlPage { loginPage(chrome(), error) }
    }

    get("/auth/steam") {
        val base = call.baseUrl(container.config)
        if (!container.config.allowHttp && !base.startsWith("https://")) return@get call.respondRedirect("/login?error=insecure")
        call.respondRedirect(SteamOpenId.loginUrl(returnTo = "$base/auth/steam/callback", realm = "$base/"))
    }

    get("/auth/steam/callback") {
        val base = call.baseUrl(container.config)
        val returnTo = "$base/auth/steam/callback"
        val params = call.request.queryParameters
        val steamId = try {
            if (params["openid.mode"] != "cancel" && !nonces.accept(params["openid.response_nonce"])) {
                throw SteamApiException(SteamApiException.Reason.SIGN_IN_UNEXPECTED, "Stale or replayed sign-in response.")
            }
            SteamOpenId.verify(container.http, base + call.request.uri, returnTo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("Steam sign-in failed: {}", e.message)
            return@get call.respondRedirect("/login?message=" + java.net.URLEncoder.encode(e.userMessage(), Charsets.UTF_8))
        }

        val account = container.settings.account.first()
        if (account != null && account.steamId != steamId) {
            log.warn("Refused sign-in by {}: this server belongs to another account", steamId)
            call.sessions.clear<WebSession>()
            return@get call.respondHtmlPage(HttpStatusCode.Forbidden) { notOwnerPage(chrome()) }
        }
        call.sessions.set(WebSession(steamId, container.clock()))
        call.respondRedirect(if (account == null) "/setup" else "/")
    }
}

fun ApplicationCall.owner(): Owner = checkNotNull(principal<Owner>()) { "Route needs authenticate(OWNER_AUTH)" }
