package dev.steamshelf.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("dev.steamshelf.server")
    val config = ServerConfig.fromEnvironment()
    config.dataDir.mkdirs()
    val container = ServerContainer(config)
    val sessionKey = runBlocking { container.serverSettings.sessionKey() }

    val server = embeddedServer(Netty, port = config.port, host = config.host) {
        steamShelf(container, sessionKey)
    }
    // `docker stop` sends SIGTERM: finish requests, then write the cache before the JVM exits.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutting down")
            server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
            container.close()
        },
    )
    container.startAutoSync()
    log.info("SteamShelf {} listening on {}:{}, data in {}", BuildInfo.version, config.host, config.port, config.dataDir.absolutePath)
    server.start(wait = true)
}
