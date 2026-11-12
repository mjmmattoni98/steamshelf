package dev.steamshelf.server.fake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One game in the fake world: what Steam, ProtonDB and HowLongToBeat say about it. */
data class FakeGame(
    val appId: Int,
    val name: String,
    val playtimeMinutes: Int = 0,
    val playtime2WeeksMinutes: Int = 0,
    val lastPlayed: Long = 0,
    val tagIds: List<Int> = emptyList(),
    /** 0 unknown, 1 unsupported, 2 playable, 3 verified. */
    val deck: Int = 0,
    val linux: Boolean = false,
    val protonTier: String? = null,
    val hltbMainHours: Double? = null,
    /** HLTB only knows it under another name, so the automatic match is uncertain. */
    val hltbName: String? = null,
    val reviewPercent: Int = 90,
    val playerCategories: List<Int> = listOf(2),
    val controllerCategories: List<Int> = emptyList(),
    val releaseYear: Int = 2018,
    val developer: String = "Some Studio",
)

/**
 * Steam (Web API, store and OpenID), ProtonDB and HowLongToBeat in one MockWebServer dispatcher. Point the
 * server's HTTP client at it with [routingClient]; every request goes here whatever its host.
 */
class FakeBackend(
    @Volatile var games: List<FakeGame>,
    val tagNames: Map<Int, String> = SampleLibrary.TAG_NAMES,
) : Dispatcher() {
    @Volatile var openIdValid = true
    val openIdChecks = AtomicInteger()
    val hltbSearches = CopyOnWriteArrayList<String>()
    private val json = Json

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        return when (val path = url.encodedPath) {
            "/openid/login" -> {
                openIdChecks.incrementAndGet()
                MockResponse(body = "ns:http://specs.openid.net/auth/2.0\nis_valid:${openIdValid}\n")
            }
            "/ISteamUser/GetPlayerSummaries/v2/" -> {
                val id = url.queryParameter("steamids").orEmpty()
                ok(
                    buildJsonObject {
                        putJsonObject("response") {
                            putJsonArray("players") {
                                addJsonObject {
                                    put("steamid", id)
                                    put("personaname", "Shelf Tester")
                                    put("profileurl", "https://steamcommunity.com/profiles/$id/")
                                }
                            }
                        }
                    },
                )
            }
            "/IPlayerService/GetOwnedGames/v1/" -> ok(
                buildJsonObject {
                    putJsonObject("response") {
                        put("game_count", games.size)
                        putJsonArray("games") {
                            games.forEach { g ->
                                addJsonObject {
                                    put("appid", g.appId)
                                    put("name", g.name)
                                    put("playtime_forever", g.playtimeMinutes)
                                    put("playtime_2weeks", g.playtime2WeeksMinutes)
                                    put("rtime_last_played", g.lastPlayed)
                                    put("playtime_linux_forever", if (g.linux) g.playtimeMinutes / 2 else 0)
                                    put("playtime_deck_forever", if (g.deck >= 2) g.playtimeMinutes / 3 else 0)
                                }
                            }
                        }
                    }
                },
            )
            "/IStoreService/GetTagList/v1/" -> ok(
                buildJsonObject {
                    putJsonObject("response") {
                        putJsonArray("tags") {
                            tagNames.forEach { (id, name) ->
                                addJsonObject {
                                    put("tagid", id)
                                    put("name", name)
                                }
                            }
                        }
                    }
                },
            )
            "/IStoreBrowseService/GetItems/v1/" -> {
                val input = json.parseToJsonElement(url.queryParameter("input_json")!!).jsonObject
                val ids = input.getValue("ids").jsonArray.map { it.jsonObject.getValue("appid").jsonPrimitive.int }
                val byId = games.associateBy { it.appId }
                ok(
                    buildJsonObject {
                        putJsonObject("response") {
                            putJsonArray("store_items") { ids.mapNotNull(byId::get).forEach { add(storeItem(it)) } }
                        }
                    },
                )
            }
            "/api/search/site/init" -> ok(buildJsonObject { put("token", "fake-token") })
            "/api/search/site" -> hltbSearch(request)
            else -> when {
                path.startsWith("/api/v1/reports/summaries/") -> {
                    val id = path.substringAfterLast('/').removeSuffix(".json").toIntOrNull()
                    val tier = games.firstOrNull { it.appId == id }?.protonTier
                    if (tier == null) MockResponse(code = 404)
                    else ok(
                        buildJsonObject {
                            put("tier", tier)
                            put("trendingTier", tier)
                            put("bestReportedTier", "platinum")
                            put("confidence", "good")
                            put("score", 0.8)
                            put("total", 40 + (id ?: 0) % 300)
                        },
                    )
                }
                else -> MockResponse(code = 404)
            }
        }
    }

    private fun storeItem(g: FakeGame): JsonObject = buildJsonObject {
        put("item_type", 0)
        put("id", g.appId)
        put("appid", g.appId)
        put("success", 1)
        putJsonArray("tagids") { g.tagIds.forEach { add(it) } }
        putJsonObject("categories") {
            putJsonArray("supported_player_categoryids") { g.playerCategories.forEach { add(it) } }
            putJsonArray("controller_categoryids") { g.controllerCategories.forEach { add(it) } }
        }
        putJsonObject("reviews") {
            putJsonObject("summary_filtered") {
                put("review_count", 1_000 + g.appId % 90_000)
                put("percent_positive", g.reviewPercent)
                put("review_score_label", if (g.reviewPercent >= 80) "Very Positive" else "Mostly Positive")
            }
        }
        putJsonObject("basic_info") {
            put("short_description", "${g.name} is one of the games in SteamShelf's sample library.")
            putJsonArray("developers") { addJsonObject { put("name", g.developer) } }
            putJsonArray("publishers") { addJsonObject { put("name", g.developer) } }
        }
        putJsonObject("release") {
            put("steam_release_date", Instant.parse("${g.releaseYear}-06-01T00:00:00Z").epochSecond)
        }
        putJsonObject("platforms") {
            put("windows", true)
            put("mac", g.appId % 3 == 0)
            put("steamos_linux", g.linux)
            put("steam_deck_compat_category", g.deck)
        }
    }

    private fun hltbSearch(request: RecordedRequest): MockResponse {
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        val query = body.getValue("searchTerms").jsonArray.joinToString(" ") { it.jsonPrimitive.content }
        hltbSearches += query
        val needle = normalize(query)
        val hits = games.filter { g ->
            g.hltbMainHours != null && needle.isNotEmpty() &&
                (normalize(g.name).contains(needle) || normalize(g.hltbName ?: g.name).contains(needle))
        }
        return ok(
            buildJsonObject {
                putJsonArray("data") {
                    hits.forEach { g ->
                        val main = (g.hltbMainHours!! * 3600).toLong()
                        addJsonObject {
                            put("game_id", 100_000 + g.appId % 100_000)
                            put("game_name", g.hltbName ?: g.name)
                            put("game_type", "game")
                            put("comp_main", main)
                            put("comp_plus", (main * 1.6).toLong())
                            put("comp_100", (main * 2.8).toLong())
                            put("comp_all", (main * 1.4).toLong())
                            put("comp_main_count", 120 + g.appId % 2_000)
                            put("comp_plus_count", 80 + g.appId % 900)
                            put("comp_100_count", 20 + g.appId % 300)
                            put("release_world", g.releaseYear)
                            put("profile_platform", "PC")
                        }
                    }
                }
            },
        )
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun ok(body: JsonObject) = MockResponse(200, headersOf("Content-Type", "application/json"), body.toString())

    companion object {
        /** A client that sends every request to [server], whatever host it was meant for. */
        fun routingClient(server: MockWebServer): OkHttpClient {
            val target = server.url("/")
            return OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val url = original.url.newBuilder().scheme("http").host(target.host).port(target.port).build()
                    chain.proceed(original.newBuilder().url(url).build())
                }
                .build()
        }
    }
}

/** Builds the URL Steam would send the browser back to after signing in as [steamId]. */
object FakeOpenId {
    fun callbackUrl(returnTo: String, steamId: String, nonce: String = freshNonce()): String =
        returnTo.toHttpUrl().newBuilder()
            .addQueryParameter("openid.ns", "http://specs.openid.net/auth/2.0")
            .addQueryParameter("openid.mode", "id_res")
            .addQueryParameter("openid.op_endpoint", "https://steamcommunity.com/openid/login")
            .addQueryParameter("openid.claimed_id", "https://steamcommunity.com/openid/id/$steamId")
            .addQueryParameter("openid.identity", "https://steamcommunity.com/openid/id/$steamId")
            .addQueryParameter("openid.return_to", returnTo)
            .addQueryParameter("openid.response_nonce", nonce)
            .addQueryParameter("openid.assoc_handle", "1234567890")
            .addQueryParameter("openid.signed", "signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle")
            .addQueryParameter("openid.sig", "c2lnbmF0dXJl")
            .build()
            .toString()

    fun freshNonce(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() + (1000..9999).random()
}
