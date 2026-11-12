package dev.steamshelf.server

import dev.steamshelf.server.routes.gameRoutes
import dev.steamshelf.server.routes.libraryRoutes
import dev.steamshelf.server.routes.settingsRoutes
import dev.steamshelf.server.routes.setupRoutes
import dev.steamshelf.server.routes.syncRoutes
import dev.steamshelf.server.views.PageChrome
import dev.steamshelf.server.views.errorPage
import dev.steamshelf.server.views.respondHtmlPage
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.flow.first
import org.slf4j.event.Level
import java.util.Properties
import java.util.zip.CRC32

object BuildInfo {
    val version: String by lazy {
        val props = Properties()
        BuildInfo::class.java.classLoader.getResourceAsStream("steamshelf-build.properties")?.use(props::load)
        props.getProperty("version") ?: "dev"
    }

    /** Changes whenever the CSS or JS does, so browsers can cache them for a year (`?v=` in their URLs). */
    val assetVersion: String by lazy {
        val crc = CRC32()
        listOf("static/app.css", "static/app.js", "static/htmx.min.js").forEach { path ->
            BuildInfo::class.java.classLoader.getResourceAsStream(path)?.use { crc.update(it.readBytes()) }
        }
        java.lang.Long.toHexString(crc.value)
    }
}

/** The web app. [sessionKey] signs the session cookies. */
fun Application.steamShelf(container: ServerContainer, sessionKey: ByteArray) {
    install(XForwardedHeaders)
    install(AutoHeadResponse)
    install(Compression) { gzip() }
    install(SSE)
    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.path().startsWith("/static") && call.request.path() != "/health" }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Request failed: ${call.request.path()}", cause)
            if (call.request.header("HX-Request") == "true") {
                call.respondText("Something went wrong.", status = HttpStatusCode.InternalServerError)
            } else {
                val chrome = call.pageChrome(container)
                call.respondHtmlPage(HttpStatusCode.InternalServerError) {
                    errorPage(chrome, "Something went wrong", "The server couldn't show this page. Check its logs, then try again.")
                }
            }
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            if (call.request.header("HX-Request") == "true") return@status call.respond(HttpStatusCode.NotFound)
            val chrome = call.pageChrome(container)
            call.respondHtmlPage(HttpStatusCode.NotFound) { errorPage(chrome, "Page not found", "There's nothing at this address.") }
        }
    }
    installAuth(container, sessionKey)

    val chrome: suspend ApplicationCall.() -> PageChrome = { pageChrome(container) }

    routing {
        staticResources("/static", "static") {
            cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 365 * 24 * 3600), Immutable) }
        }
        get("/manifest.webmanifest") {
            val manifest = BuildInfo::class.java.classLoader.getResource("manifest.webmanifest")!!.readText()
            call.respondText(manifest, ContentType("application", "manifest+json"))
        }
        get("/favicon.ico") { call.respondRedirect("/static/icon.svg", permanent = true) }
        get("/health") { call.respondText("ok") }

        authRoutes(container) { PageChrome(assetVersion = BuildInfo.assetVersion) }

        authenticate(SETUP_AUTH) { setupRoutes(container, chrome) }

        authenticate(OWNER_AUTH) {
            libraryRoutes(container, chrome)
            gameRoutes(container, chrome)
            settingsRoutes(container, chrome)
            syncRoutes(container)
        }
    }
}

/** The top bar shows the account and sync status only to the signed-in owner. */
private suspend fun ApplicationCall.pageChrome(container: ServerContainer): PageChrome {
    val owner = principal<Owner>()
    return PageChrome(
        account = owner?.let { container.settings.account.first() ?: it.account },
        sync = if (owner != null) container.repository.sync.value else null,
        assetVersion = BuildInfo.assetVersion,
    )
}

private object Immutable : CacheControl(null) {
    override fun toString(): String = "immutable"
}
