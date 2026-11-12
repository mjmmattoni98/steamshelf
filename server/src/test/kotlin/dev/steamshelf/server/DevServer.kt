package dev.steamshelf.server

import dev.steamshelf.server.fake.FakeBackend
import dev.steamshelf.server.fake.FakeOpenId
import dev.steamshelf.server.fake.SampleLibrary
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import java.io.File

/**
 * `./gradlew :server:runDev`: the web app on http://localhost:8080 against a fake Steam, ProtonDB and
 * HowLongToBeat with a sample library. Open /dev/sign-in to sign in without a Steam account (only this
 * launcher has that route), then enter any 32-character hex key, e.g. 0123456789ABCDEF0123456789ABCDEF.
 */
fun main(args: Array<String>) {
    val dataDir = File(args.getOrNull(0) ?: "build/dev-data")
    val fake = MockWebServer()
    fake.dispatcher = FakeBackend(SampleLibrary.games)
    fake.start()

    val config = ServerConfig(
        host = "127.0.0.1",
        port = System.getenv("STEAMSHELF_PORT")?.toIntOrNull() ?: 8080,
        dataDir = dataDir,
        allowHttp = true,
    )
    val container = ServerContainer(config, httpClient = FakeBackend.routingClient(fake))
    val sessionKey = runBlocking { container.serverSettings.sessionKey() }
    container.startAutoSync()
    println("SteamShelf dev server: http://localhost:${config.port}/dev/sign-in (data in ${dataDir.absolutePath})")
    embeddedServer(Netty, port = config.port, host = config.host) {
        steamShelf(container, sessionKey)
        routing {
            get("/dev/sign-in") {
                val base = call.baseUrl(config)
                val steamId = call.request.queryParameters["as"] ?: SampleLibrary.STEAM_ID
                call.respondRedirect(FakeOpenId.callbackUrl("$base/auth/steam/callback", steamId))
            }
        }
    }.start(wait = true)
}
