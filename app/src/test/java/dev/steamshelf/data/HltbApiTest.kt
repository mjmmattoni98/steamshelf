package dev.steamshelf.data

import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.hltb.HltbRateLimitedException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HltbApiTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
    }

    private fun api(searchPath: String = HltbApi.DEFAULT_SEARCH_PATH) =
        HltbApi(OkHttpClient(), testJson, initialSearchPath = searchPath, baseUrl = server.baseUrl)

    private fun paths() = requests.map { "${it.method} ${it.url.encodedPath}" }

    @Test
    fun `a 401 fetches a new token and retries`() = refreshesTokenAfter(401)

    @Test
    fun `a 403 fetches a new token and retries`() = refreshesTokenAfter(403)

    private fun refreshesTokenAfter(code: Int) = runTest {
        val tokens = AtomicInteger()
        route { request ->
            when (request.url.encodedPath) {
                "/api/search/site/init" -> jsonResponse("""{"token":"token-${tokens.incrementAndGet()}"}""")
                "/api/search/site" ->
                    if (request.headers["x-auth-token"] == "token-1") MockResponse(code = code) else jsonResponse(CELESTE)
                else -> MockResponse(code = 404)
            }
        }

        val results = api().search("Celeste")

        assertEquals(listOf("Celeste"), results.map { it.name })
        assertEquals(
            listOf("GET /api/search/site/init", "POST /api/search/site", "GET /api/search/site/init", "POST /api/search/site"),
            paths(),
        )
        assertEquals("token-2", requests.last().headers["x-auth-token"])
    }

    @Test
    fun `a 404 on the token endpoint rediscovers the search path from the site's scripts`() = runTest {
        route(renamedSite(oldPathAnswers = { MockResponse(code = 404) }))

        val results = api(searchPath = "/api/old").search("Celeste")

        assertEquals(listOf("Celeste"), results.map { it.name })
        assertEquals(
            listOf(
                "GET /api/old/init",
                "GET /",
                "GET /_next/static/chunks/framework-1.js",
                "GET /_next/static/chunks/pages/_app-2.js",
                "GET /api/finder/init",
                "POST /api/finder",
            ),
            paths(),
        )
    }

    @Test
    fun `a 404 on search rediscovers the search path`() = runTest {
        route(
            renamedSite(oldPathAnswers = { request ->
                if (request.url.encodedPath.endsWith("/init")) jsonResponse("""{"token":"old"}""") else MockResponse(code = 404)
            }),
        )

        val results = api(searchPath = "/api/old").search("Celeste")

        assertEquals(listOf("Celeste"), results.map { it.name })
        assertEquals("POST /api/finder", paths().last())
    }

    @Test
    fun `a 429 becomes HltbRateLimitedException`() {
        route { request ->
            if (request.url.encodedPath.endsWith("/init")) jsonResponse("""{"token":"t"}""") else MockResponse(code = 429)
        }

        assertThrows(HltbRateLimitedException::class.java) { runBlocking { api().search("Celeste") } }
    }

    @Test
    fun `the honeypot key from the token response is sent back`() = runTest {
        route { request ->
            if (request.url.encodedPath.endsWith("/init")) jsonResponse("""{"token":"t","hpKey":"ign_x","hpVal":"42"}""")
            else jsonResponse(CELESTE)
        }

        api().search("Celeste")

        val search = requests.last()
        assertEquals("ign_x", search.headers["x-hp-key"])
        assertEquals("42", search.headers["x-hp-val"])
        assertEquals(true, search.body!!.utf8().contains("\"ign_x\":\"42\""))
    }

    /** A site whose search moved from /api/old to /api/finder; [oldPathAnswers] serves the old path. */
    private fun renamedSite(oldPathAnswers: (RecordedRequest) -> MockResponse): (RecordedRequest) -> MockResponse = { request ->
        val path = request.url.encodedPath
        when {
            path.startsWith("/api/old") -> oldPathAnswers(request)
            path == "/" -> MockResponse(
                body = """<html><head>
                    <script src="/_next/static/chunks/framework-1.js" defer=""></script>
                    <script src="/_next/static/chunks/pages/_app-2.js" defer=""></script>
                    </head></html>""",
            )
            path == "/_next/static/chunks/framework-1.js" -> MockResponse(body = "console.log('nothing here')")
            path == "/_next/static/chunks/pages/_app-2.js" ->
                MockResponse(body = """let r=await fetch("/api/finder/init?t="+Date.now(),{headers:h});""")
            path == "/api/finder/init" -> jsonResponse("""{"token":"new"}""")
            path == "/api/finder" -> jsonResponse(CELESTE)
            else -> MockResponse(code = 404)
        }
    }

    private companion object {
        const val CELESTE = """{"color":"blue","count":1,"data":[{"game_id":42818,"game_name":"Celeste","game_type":"game","comp_main":30600,"comp_plus":52000,"comp_100":136000,"release_world":2018}]}"""
    }
}
