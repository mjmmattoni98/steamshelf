package dev.steamshelf.server

import java.io.File

/** Server settings from environment variables (see docker-compose.yml and the README). */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Settings, the library cache and the HTTP cache. */
    val dataDir: File = File("data"),
    /**
     * The address the browser uses, e.g. https://raspberrypi.your-tailnet.ts.net. Null works it out from each
     * request, which is right behind `tailscale serve` (it passes the original host and X-Forwarded-Proto).
     */
    val publicUrl: String? = null,
    /**
     * Allows signing in over plain HTTP, for local development. Session cookies are then sent without the
     * Secure flag; leave it off when SteamShelf is reached through HTTPS.
     */
    val allowHttp: Boolean = false,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            host = env["STEAMSHELF_HOST"]?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
            port = env["STEAMSHELF_PORT"]?.toIntOrNull() ?: 8080,
            dataDir = File(env["STEAMSHELF_DATA_DIR"]?.takeIf { it.isNotBlank() } ?: "data"),
            publicUrl = env["STEAMSHELF_PUBLIC_URL"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
            allowHttp = env["STEAMSHELF_ALLOW_HTTP"]?.trim()?.lowercase() in setOf("1", "true", "yes"),
        )
    }
}
