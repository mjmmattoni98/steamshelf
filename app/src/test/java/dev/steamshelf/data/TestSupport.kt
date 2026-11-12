package dev.steamshelf.data

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.headersOf

/** Same settings as AppContainer's Json. */
val testJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

fun fixture(path: String): String =
    checkNotNull(Thread.currentThread().contextClassLoader?.getResource(path)) { "missing fixture $path" }.readText()

/** The server's origin without a trailing slash, as the API classes expect their base URL. */
val MockWebServer.baseUrl: String get() = url("/").toString().trimEnd('/')

fun jsonResponse(body: String, code: Int = 200) =
    MockResponse(code, headersOf("Content-Type", "application/json"), body)
