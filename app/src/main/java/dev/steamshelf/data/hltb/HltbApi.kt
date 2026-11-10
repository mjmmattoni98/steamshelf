package dev.steamshelf.data.hltb

import dev.steamshelf.data.net.HttpStatusException
import dev.steamshelf.data.net.fetch
import dev.steamshelf.data.net.fetchString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class HltbRateLimitedException : IOException("HowLongToBeat is rate limiting searches")

/** A search hit. Times are in seconds. */
@Serializable
data class HltbGame(
    @SerialName("game_id") val id: Int,
    @SerialName("game_name") val name: String = "",
    @SerialName("game_alias") val alias: String? = null,
    @SerialName("game_type") val type: String? = null,
    @SerialName("game_image") val image: String? = null,
    @SerialName("comp_main") val main: Long = 0,
    @SerialName("comp_plus") val plus: Long = 0,
    @SerialName("comp_100") val completionist: Long = 0,
    @SerialName("comp_all") val allStyles: Long = 0,
    @SerialName("comp_main_count") val mainCount: Int = 0,
    @SerialName("comp_plus_count") val plusCount: Int = 0,
    @SerialName("comp_100_count") val completionistCount: Int = 0,
    @SerialName("review_score") val reviewScore: Int = 0,
    @SerialName("release_world") val releaseYear: Int = 0,
    @SerialName("profile_platform") val platforms: String? = null,
) {
    val imageUrl: String? get() = image?.takeIf { it.isNotBlank() }?.let { "${HltbApi.BASE}/games/$it" }
}

@Serializable
private data class SearchResponse(val data: List<HltbGame> = emptyList())

/**
 * HowLongToBeat has no public API, so this talks to the same endpoints as its website:
 * `GET {path}/init?t=…` hands out a short-lived token (bound to IP + User-Agent), then
 * `POST {path}` with `x-auth-token` runs the search. HLTB renames these endpoints every few months,
 * so on a 404 we re-discover the path from the site's own JavaScript bundles.
 */
class HltbApi(
    baseClient: OkHttpClient,
    private val json: Json,
    initialSearchPath: String = DEFAULT_SEARCH_PATH,
) {

    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$BASE/")
                    .header("Origin", BASE)
                    .build(),
            )
        }
        .build()

    private val mutex = Mutex()
    @Volatile private var searchPath = initialSearchPath
    @Volatile private var session: Session? = null
    @Volatile private var lastDiscoveryFailure = 0L

    private data class Session(val token: String, val hpKey: String?, val hpVal: String?, val createdAt: Long)

    suspend fun search(query: String, size: Int = 10): List<HltbGame> {
        // A lone "-" or "&" is taken literally by HLTB and kills the search, so keep only real words.
        val terms = query.trim().split(Regex("\\s+")).filter { term -> term.any { it.isLetterOrDigit() } }
        if (terms.isEmpty()) return emptyList()

        var refreshedToken = false
        var rediscovered = false
        while (true) {
            val path = searchPath
            val current = try {
                session(forceRefresh = false)
            } catch (e: HttpStatusException) {
                if (e.code == 404 && !rediscovered) {
                    rediscovered = true
                    rediscoverPath(path)
                    continue
                }
                throw e
            }

            val result = client.fetch(
                Request.Builder()
                    .url("$BASE$path")
                    .header("x-auth-token", current.token)
                    .apply {
                        if (current.hpKey != null && current.hpVal != null) {
                            header("x-hp-key", current.hpKey)
                            header("x-hp-val", current.hpVal)
                        }
                    }
                    .post(searchBody(terms, size, current).toString().toRequestBody(JSON_TYPE))
                    .build(),
            )
            when {
                result.isSuccessful -> return json.decodeFromString<SearchResponse>(result.body).data
                result.code == 429 -> throw HltbRateLimitedException()
                (result.code == 401 || result.code == 403) && !refreshedToken -> {
                    refreshedToken = true
                    invalidate(current)
                }
                result.code == 404 && !rediscovered -> {
                    rediscovered = true
                    rediscoverPath(path)
                }
                else -> throw HttpStatusException(result.code, result.body.take(300))
            }
        }
    }

    private suspend fun session(forceRefresh: Boolean): Session = mutex.withLock {
        val existing = session
        if (!forceRefresh && existing != null && System.currentTimeMillis() - existing.createdAt < TOKEN_TTL_MS) {
            return existing
        }
        val body = client.fetchString(
            Request.Builder().url("$BASE$searchPath/init?t=${System.currentTimeMillis()}").build(),
        )
        val obj = json.parseToJsonElement(body).jsonObject
        val token = obj.string("token") ?: throw IOException("HowLongToBeat didn't return a search token")
        Session(token, obj.string("hpKey"), obj.string("hpVal"), System.currentTimeMillis()).also { session = it }
    }

    private suspend fun invalidate(stale: Session) = mutex.withLock {
        if (session === stale) session = null
    }

    /** Scans the site's script bundles for `fetch(`/api/<something>/init?t=` to learn the current search path. */
    private suspend fun rediscoverPath(failedPath: String) = mutex.withLock {
        if (searchPath != failedPath) return@withLock // another caller already updated it
        if (System.currentTimeMillis() - lastDiscoveryFailure < DISCOVERY_COOLDOWN_MS) {
            throw IOException("HowLongToBeat's search endpoint is unavailable")
        }
        lastDiscoveryFailure = System.currentTimeMillis()
        val html = client.fetchString(Request.Builder().url("$BASE/").build())
        val scripts = SCRIPT_SRC.findAll(html).map { it.groupValues[1] }.distinct().toList()
        for (src in scripts) {
            val js = runCatching {
                client.fetchString(Request.Builder().url(if (src.startsWith("http")) src else "$BASE$src").build())
            }.getOrNull() ?: continue
            val found = INIT_CALL.find(js)?.groupValues?.get(1)
            if (found != null) {
                searchPath = found
                session = null
                lastDiscoveryFailure = 0
                return@withLock
            }
        }
        throw IOException("Couldn't find HowLongToBeat's search endpoint; the site may have changed.")
    }

    private fun searchBody(terms: List<String>, size: Int, session: Session): JsonObject = buildJsonObject {
        put("searchType", "games")
        putJsonArray("searchTerms") { terms.forEach { add(it) } }
        put("searchPage", 1)
        put("size", size)
        putJsonObject("searchOptions") {
            putJsonObject("games") {
                put("userId", 0)
                put("platform", "")
                put("sortCategory", "popular")
                put("rangeCategory", "main")
                putJsonObject("rangeTime") {
                    put("min", JsonNull)
                    put("max", JsonNull)
                }
                putJsonObject("gameplay") {
                    put("perspective", "")
                    put("flow", "")
                    put("genre", "")
                }
                put("year", "")
                put("modifier", "")
            }
            putJsonObject("users") { put("sortCategory", "postcount") }
            putJsonObject("lists") { put("sortCategory", "follows") }
            put("filter", "")
            put("sort", 0)
            put("randomizer", 0)
        }
        put("useCache", true)
        if (session.hpKey != null && session.hpVal != null) put(session.hpKey, session.hpVal)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    companion object {
        const val BASE = "https://howlongtobeat.com"
        const val DEFAULT_SEARCH_PATH = "/api/search/site"
        private const val TOKEN_TTL_MS = 10 * 60 * 1000L
        private const val DISCOVERY_COOLDOWN_MS = 5 * 60 * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36"
        private val JSON_TYPE = "application/json".toMediaType()
        private val SCRIPT_SRC = Regex("""src="([^"]*/_next/static/[^"]+\.js)"""")
        private val INIT_CALL = Regex("""fetch\(\s*[`"'](/api/[A-Za-z0-9_/-]+?)/init\?t=""")

        fun pageUrl(id: Int) = "$BASE/game/$id"
    }
}
