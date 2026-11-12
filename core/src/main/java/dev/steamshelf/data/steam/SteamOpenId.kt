package dev.steamshelf.data.steam

import dev.steamshelf.data.net.fetchString
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * "Sign in through Steam" (OpenID 2.0). Steam's login page sends the browser back to a return URL with a
 * signed assertion, which we verify directly with Steam before trusting the SteamID it carries.
 *
 * The app runs the login page in a WebView and returns to [RETURN_TO], which never resolves (.invalid is a
 * reserved TLD), so the WebView intercepts it. The web server passes its own callback URL instead.
 */
object SteamOpenId {
    const val RETURN_TO = "https://steamshelf.invalid/openid/callback"
    private const val REALM = "https://steamshelf.invalid/"
    const val ENDPOINT = "https://steamcommunity.com/openid/login"
    private const val NS = "http://specs.openid.net/auth/2.0"
    private const val IDENTIFIER_SELECT = "http://specs.openid.net/auth/2.0/identifier_select"
    private val claimedIdPattern = Regex("^https://steamcommunity\\.com/openid/id/(\\d{17})$")

    /**
     * Fields Steam's signature has to cover. check_authentication only checks the fields listed in
     * openid.signed, so an assertion that leaves one of these out could carry a forged SteamID.
     */
    private val requiredSignedFields = setOf("op_endpoint", "claimed_id", "identity", "return_to", "response_nonce")

    /** The app's login page, returning to [RETURN_TO]. */
    val loginUrl: String = loginUrl(RETURN_TO, REALM)

    /** Steam's login page, sending the browser back to [returnTo]. [realm] is the site Steam names on that page. */
    fun loginUrl(returnTo: String, realm: String): String = ENDPOINT.toHttpUrl().newBuilder()
        .addQueryParameter("openid.ns", NS)
        .addQueryParameter("openid.mode", "checkid_setup")
        .addQueryParameter("openid.return_to", returnTo)
        .addQueryParameter("openid.realm", realm)
        .addQueryParameter("openid.identity", IDENTIFIER_SELECT)
        .addQueryParameter("openid.claimed_id", IDENTIFIER_SELECT)
        .build()
        .toString()

    fun isCallback(url: String?, returnTo: String = RETURN_TO): Boolean = url != null && url.startsWith(returnTo)

    /** Verifies the callback with Steam (check_authentication) and returns the SteamID64. */
    suspend fun verify(client: OkHttpClient, callbackUrl: String, returnTo: String = RETURN_TO): String {
        val url = callbackUrl.toHttpUrlOrNull() ?: throw SteamApiException(SteamApiException.Reason.SIGN_IN_INVALID, "Invalid sign-in response.")
        val params = url.queryParameterNames
            .filter { it.startsWith("openid.") }
            .associateWith { url.queryParameter(it).orEmpty() }

        if (params["openid.mode"] == "cancel") throw SteamApiException(SteamApiException.Reason.SIGN_IN_CANCELLED, "Sign-in was cancelled.")
        val signed = params["openid.signed"].orEmpty().split(',').toSet()
        if (params["openid.op_endpoint"] != ENDPOINT ||
            params["openid.return_to"]?.startsWith(returnTo) != true ||
            !signed.containsAll(requiredSignedFields) ||
            params["openid.identity"] != params["openid.claimed_id"]
        ) {
            throw SteamApiException(SteamApiException.Reason.SIGN_IN_UNEXPECTED, "Unexpected sign-in response.")
        }
        val steamId = claimedIdPattern.find(params["openid.claimed_id"].orEmpty())?.groupValues?.get(1)
            ?: throw SteamApiException(SteamApiException.Reason.SIGN_IN_NO_STEAM_ID, "Steam didn't return a SteamID.")

        val form = FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, if (key == "openid.mode") "check_authentication" else value) }
        }.build()
        val body = client.fetchString(Request.Builder().url(ENDPOINT).post(form).build())
        if (!body.lines().any { it.trim() == "is_valid:true" }) {
            throw SteamApiException(SteamApiException.Reason.SIGN_IN_NOT_VERIFIED, "Steam couldn't verify the sign-in. Please try again.")
        }
        return steamId
    }
}

/** What the user typed into the "SteamID or profile URL" field. */
sealed interface SteamIdInput {
    data class Id(val steamId: String) : SteamIdInput
    data class Vanity(val name: String) : SteamIdInput

    companion object {
        private val steamId64 = Regex("^7656119\\d{10}$")
        private val profilesUrl = Regex("steamcommunity\\.com/profiles/(\\d{17})", RegexOption.IGNORE_CASE)
        private val vanityUrl = Regex("steamcommunity\\.com/id/([^/?#\\s]+)", RegexOption.IGNORE_CASE)
        private val vanityName = Regex("^[A-Za-z0-9_-]{2,32}$")

        fun parse(raw: String): SteamIdInput? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            if (steamId64.matches(text)) return Id(text)
            profilesUrl.find(text)?.let { return Id(it.groupValues[1]) }
            vanityUrl.find(text)?.let { return Vanity(it.groupValues[1]) }
            return if (vanityName.matches(text)) Vanity(text) else null
        }
    }
}
