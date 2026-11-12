package dev.steamshelf.server

import dev.steamshelf.model.PlayStatus
import dev.steamshelf.server.fake.FakeBackend
import dev.steamshelf.server.fake.FakeOpenId
import dev.steamshelf.server.fake.SampleLibrary
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The web app end to end: Ktor's test engine in front, a fake Steam/ProtonDB/HowLongToBeat behind. */
class ServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val backend = FakeBackend(SampleLibrary.small)
    private val steam = MockWebServer()
    private lateinit var container: ServerContainer

    @Before
    fun setUp() {
        steam.dispatcher = backend
        steam.start()
        container = ServerContainer(ServerConfig(dataDir = tmp.root, allowHttp = true), httpClient = FakeBackend.routingClient(steam))
        // HLTB lookups are paced for politeness; the tests that need HLTB search it directly.
        runBlocking { container.settings.setAutoHltb(false) }
    }

    @After
    fun tearDown() {
        container.close()
        steam.close()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { steamShelf(container, ByteArray(32) { it.toByte() }) }
        block()
    }

    private fun ApplicationTestBuilder.browser(): HttpClient = createClient {
        install(HttpCookies)
        followRedirects = false
    }

    private suspend fun HttpClient.signIn(steamId: String = SampleLibrary.STEAM_ID): HttpResponse {
        val callback = FakeOpenId.callbackUrl("http://localhost/auth/steam/callback", steamId)
        return get(callback.removePrefix("http://localhost"))
    }

    /** Signs in as the owner, enters the API key and waits for the first sync to finish. */
    private suspend fun HttpClient.setUpOwner() {
        assertEquals("/setup", signIn().headers[HttpHeaders.Location])
        val setup = submitForm(
            "/setup",
            parameters {
                append("api_key", SampleLibrary.API_KEY)
                append("language", "english")
                append("country", "US")
            },
        )
        assertEquals(HttpStatusCode.Found, setup.status)
        assertEquals("/", setup.headers[HttpHeaders.Location])
        withTimeout(10_000) {
            while (container.repository.sync.value.running || container.repository.items.first().size < 3) delay(20)
        }
    }

    @Test
    fun `signed-out visitors are sent to the sign-in page`() = app {
        val client = browser()
        val home = client.get("/")
        assertEquals(HttpStatusCode.Found, home.status)
        assertEquals("/login", home.headers[HttpHeaders.Location])
        assertTrue(client.get("/login").bodyAsText().contains("Sign in through Steam"))
        assertEquals(HttpStatusCode.Found, client.get("/settings").status)
    }

    @Test
    fun `sign-in goes to Steam and comes back to this server`() = app {
        val redirect = browser().get("/auth/steam")
        val location = redirect.headers[HttpHeaders.Location].orEmpty()
        assertTrue(location, location.startsWith("https://steamcommunity.com/openid/login?"))
        assertTrue(location, location.contains("openid.return_to=" + "http://localhost/auth/steam/callback".encodeURLParameter()))
    }

    @Test
    fun `signing in and entering the API key shows the library`() = app {
        val client = browser()
        client.setUpOwner()

        val library = client.get("/").bodyAsText()
        listOf("Hollow Knight", "Portal 2", "Hades").forEach { assertTrue(it, library.contains(it)) }
        assertTrue(library.contains("3 games"))
        assertEquals(1, backend.openIdChecks.get())
    }

    @Test
    fun `a wrong API key keeps the setup page with a message`() = app {
        val client = browser()
        client.signIn()
        val response = client.submitForm("/setup", parameters { append("api_key", "not-a-key") })
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("32 letters and numbers"))
        assertEquals(null, container.settings.account.first())
    }

    @Test
    fun `once set up, other Steam accounts can't sign in`() = app {
        browser().setUpOwner()

        val stranger = browser()
        val response = stranger.signIn(SampleLibrary.OTHER_STEAM_ID)
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("belongs to another Steam account"))
        assertEquals("/login", stranger.get("/").headers[HttpHeaders.Location])
    }

    @Test
    fun `a sign-in response can't be replayed, and Steam has to confirm it`() = app {
        val callback = FakeOpenId.callbackUrl("http://localhost/auth/steam/callback", SampleLibrary.STEAM_ID).removePrefix("http://localhost")
        assertEquals("/setup", browser().get(callback).headers[HttpHeaders.Location])
        val replay = browser().get(callback)
        assertTrue(replay.headers[HttpHeaders.Location].orEmpty().startsWith("/login?message="))

        backend.openIdValid = false
        val forged = browser().signIn()
        assertTrue(forged.headers[HttpHeaders.Location].orEmpty().startsWith("/login?message="))
    }

    @Test
    fun `filters and search in the URL narrow the list`() = app {
        val client = browser()
        client.setUpOwner()

        val unplayed = client.get("/?play=unplayed").bodyAsText()
        assertTrue(unplayed.contains("Hades"))
        assertFalse(unplayed.contains("Hollow Knight"))
        assertTrue(unplayed.contains("1 of 3 games"))

        val search = client.get("/?q=portal").bodyAsText()
        assertTrue(search.contains("Portal 2"))
        assertFalse(search.contains("Hades"))
    }

    @Test
    fun `a quick chip pushes the canonical URL and redraws the filters`() = app {
        val client = browser()
        client.setUpOwner()

        val response = client.get("/?toggle=unplayed&sort=name&sorted=name&desc=0") { header("HX-Request", "true") }
        assertEquals("/?play=unplayed", response.headers["HX-Push-Url"])
        val body = response.bodyAsText()
        assertTrue(body.contains("""id="filters-body" hx-swap-oob="true""""))
        // The view is remembered for a bare "/".
        assertTrue(client.get("/").bodyAsText().contains("1 of 3 games"))
    }

    @Test
    fun `cross-site POSTs are refused`() = app {
        val client = browser()
        client.setUpOwner()
        assertEquals(HttpStatusCode.Forbidden, client.post("/sync") { header(HttpHeaders.Origin, "https://evil.example") }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/sync") { header(HttpHeaders.Origin, "http://localhost") }.status)
    }

    @Test
    fun `the game page sets a status and fixes the HowLongToBeat match`() = app {
        val client = browser()
        client.setUpOwner()

        assertTrue(client.get("/games/367520").bodyAsText().contains("Your status"))
        assertEquals(HttpStatusCode.OK, client.submitForm("/games/367520/status", parameters { append("status", "finished") }).status)
        assertEquals(PlayStatus.FINISHED, container.repository.items.first().first { it.appId == 367520 }.status)

        val results = client.get("/games/367520/hltb/search?q=Hollow").bodyAsText()
        assertTrue(results, results.contains("Hollow Knight"))
        val hltbId = Regex("""<button[^>]*name="id"[^>]*value="(\d+)"""").find(results)!!.groupValues[1]
        val pick = client.submitForm("/games/367520/hltb/pick", parameters { append("id", hltbId) })
        assertEquals("""{"dialog-close":true}""", pick.headers["HX-Trigger"])
        val hltb = container.repository.items.first().first { it.appId == 367520 }.hltb!!
        assertTrue(hltb.manual)
        assertEquals(27.5 * 3600, hltb.mainSeconds.toDouble(), 1.0)

        assertEquals(HttpStatusCode.NotFound, client.get("/games/1").status)
    }

    @Test
    fun `behind tailscale serve the session cookie is HTTPS-only and sign-in works`() {
        container.close()
        container = ServerContainer(ServerConfig(dataDir = tmp.newFolder(), allowHttp = false), httpClient = FakeBackend.routingClient(steam))
        app {
            val proxied: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                header(HttpHeaders.Host, "raspberrypi.tail1234.ts.net")
                header("X-Forwarded-Host", "raspberrypi.tail1234.ts.net")
                header("X-Forwarded-Proto", "https")
            }
            val client = browser()
            // Plain HTTP is refused before going to Steam.
            assertEquals("/login?error=insecure", client.get("/auth/steam").headers[HttpHeaders.Location])

            val returnTo = "https://raspberrypi.tail1234.ts.net/auth/steam/callback"
            assertTrue(client.get("/auth/steam", proxied).headers[HttpHeaders.Location].orEmpty().contains(returnTo.encodeURLParameter()))
            val callback = FakeOpenId.callbackUrl(returnTo, SampleLibrary.STEAM_ID).removePrefix("https://raspberrypi.tail1234.ts.net")
            val signedIn = client.get(callback, proxied)
            assertEquals("/setup", signedIn.headers[HttpHeaders.Location])
            val cookie = signedIn.headers[HttpHeaders.SetCookie].orEmpty()
            assertTrue(cookie, cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"))
        }
    }

    @Test
    fun `signing out forgets the account and the browser's session`() = app {
        val client = browser()
        client.setUpOwner()
        val response = client.post("/settings/sign-out")
        assertEquals("/login", response.headers["HX-Redirect"])
        assertEquals(null, container.settings.account.first())
        assertTrue(container.repository.items.first().isEmpty())
        assertEquals("/login", client.get("/").headers[HttpHeaders.Location])
    }
}
