package dev.steamshelf.data

import dev.steamshelf.data.steam.SteamApiException
import dev.steamshelf.data.steam.SteamApiException.Reason
import dev.steamshelf.data.steam.SteamOpenId
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** check_authentication against a fake steamcommunity.com (requests to Steam are rerouted to a local server). */
class SteamOpenIdTest {
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private val returnTo = "https://shelf.example.ts.net/auth/steam/callback"
    private val steamId = "76561197960287930"

    @Before
    fun setUp() {
        server.start()
        val local = server.url("/")
        client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.newBuilder().scheme("http").host(local.host).port(local.port).build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a valid assertion is checked with Steam and yields the SteamID`() = runTest {
        server.enqueue(MockResponse(body = "ns:http://specs.openid.net/auth/2.0\nis_valid:true\n"))

        assertEquals(steamId, SteamOpenId.verify(client, callback(), returnTo))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val form = request.body!!.utf8()
        assertTrue(form, form.contains("openid.mode=check_authentication"))
    }

    @Test
    fun `Steam saying the assertion is invalid fails the sign-in`() = runTest {
        server.enqueue(MockResponse(body = "ns:http://specs.openid.net/auth/2.0\nis_valid:false\n"))
        assertFails(Reason.SIGN_IN_NOT_VERIFIED) { SteamOpenId.verify(client, callback(), returnTo) }
    }

    @Test
    fun `an assertion whose signature doesn't cover the SteamID is refused without asking Steam`() = runTest {
        val unsigned = callback(signed = "signed,op_endpoint,identity,return_to,response_nonce,assoc_handle")
        assertFails(Reason.SIGN_IN_UNEXPECTED) { SteamOpenId.verify(client, unsigned, returnTo) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an assertion for another site or with mismatched ids is refused`() = runTest {
        assertFails(Reason.SIGN_IN_UNEXPECTED) {
            SteamOpenId.verify(client, callback(returnTo = "https://evil.example/cb"), returnTo)
        }
        assertFails(Reason.SIGN_IN_UNEXPECTED) {
            SteamOpenId.verify(client, callback(identity = "https://steamcommunity.com/openid/id/76561197960287931"), returnTo)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a cancelled sign-in says so`() = runTest {
        val cancelled = returnTo.toHttpUrl().newBuilder().addQueryParameter("openid.mode", "cancel").build().toString()
        assertFails(Reason.SIGN_IN_CANCELLED) { SteamOpenId.verify(client, cancelled, returnTo) }
    }

    @Test
    fun `the login url sends the browser back to the given callback`() {
        val url = SteamOpenId.loginUrl(returnTo, "https://shelf.example.ts.net/").toHttpUrl()
        assertEquals(returnTo, url.queryParameter("openid.return_to"))
        assertEquals("https://shelf.example.ts.net/", url.queryParameter("openid.realm"))
    }

    private fun callback(
        returnTo: String = this.returnTo,
        signed: String = "signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle",
        identity: String = "https://steamcommunity.com/openid/id/$steamId",
    ): String = this.returnTo.toHttpUrl().newBuilder()
        .addQueryParameter("openid.ns", "http://specs.openid.net/auth/2.0")
        .addQueryParameter("openid.mode", "id_res")
        .addQueryParameter("openid.op_endpoint", SteamOpenId.ENDPOINT)
        .addQueryParameter("openid.claimed_id", "https://steamcommunity.com/openid/id/$steamId")
        .addQueryParameter("openid.identity", identity)
        .addQueryParameter("openid.return_to", returnTo)
        .addQueryParameter("openid.response_nonce", "2026-11-12T10:00:00Zabc123")
        .addQueryParameter("openid.assoc_handle", "1234567890")
        .addQueryParameter("openid.signed", signed)
        .addQueryParameter("openid.sig", "c2lnbmF0dXJl")
        .build()
        .toString()

    private suspend fun assertFails(reason: Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: SteamApiException) {
            assertEquals(reason, e.reason)
        }
    }
}
