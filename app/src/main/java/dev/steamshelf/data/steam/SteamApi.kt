package dev.steamshelf.data.steam

import dev.steamshelf.data.net.HttpStatusException
import dev.steamshelf.data.net.fetchString
import dev.steamshelf.model.OwnedGame
import dev.steamshelf.model.StoreInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class SteamApiException(message: String) : Exception(message)

data class SteamProfile(
    val steamId: String,
    val personaName: String,
    val avatarUrl: String?,
    val profileUrl: String?,
)

/** Steam Web API + public store endpoints. */
class SteamApi(private val client: OkHttpClient, private val json: Json) {

    suspend fun getOwnedGames(apiKey: String, steamId: String, includeFreeGames: Boolean): List<OwnedGame> {
        val url = api("IPlayerService/GetOwnedGames/v1/")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("steamid", steamId)
            .addQueryParameter("include_appinfo", "1")
            .addQueryParameter("include_played_free_games", if (includeFreeGames) "1" else "0")
            .addQueryParameter("skip_unvetted_apps", "0")
            .addQueryParameter("format", "json")
            .build()
        val body = get(url)
        val response = json.decodeFromString<OwnedGamesEnvelope>(body).response
        val games = response.games ?: throw SteamApiException(
            "Steam returned no games. Make sure the API key belongs to this account, " +
                "or set \"Game details\" to Public in your Steam privacy settings.",
        )
        return games.map {
            OwnedGame(
                appId = it.appid,
                name = it.name ?: "App ${it.appid}",
                playtimeForever = it.playtimeForever,
                playtime2Weeks = it.playtime2Weeks,
                lastPlayed = it.lastPlayed,
                playtimeLinux = it.playtimeLinux,
                playtimeDeck = it.playtimeDeck,
            )
        }
    }

    suspend fun getPlayerSummary(apiKey: String, steamId: String): SteamProfile {
        val url = api("ISteamUser/GetPlayerSummaries/v2/")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("steamids", steamId)
            .build()
        val player = json.decodeFromString<PlayersEnvelope>(get(url)).response.players.firstOrNull()
            ?: throw SteamApiException("No Steam profile found for $steamId.")
        return SteamProfile(player.steamid, player.personaname, player.avatarfull, player.profileurl)
    }

    suspend fun resolveVanity(apiKey: String, vanity: String): String {
        val url = api("ISteamUser/ResolveVanityURL/v1/")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("vanityurl", vanity)
            .build()
        val response = json.decodeFromString<VanityEnvelope>(get(url)).response
        return response.steamid?.takeIf { response.success == 1 }
            ?: throw SteamApiException("Couldn't find a Steam profile called \"$vanity\".")
    }

    /** Batched store metadata. Apps the store doesn't know (delisted, region-locked) come back with found=false. */
    suspend fun getStoreItems(appIds: List<Int>, countryCode: String, now: Long): List<StoreInfo> {
        val input = buildJsonObject {
            putJsonArray("ids") { appIds.forEach { id -> addJsonObject { put("appid", id) } } }
            putJsonObject("context") {
                put("language", "english")
                put("country_code", countryCode)
            }
            putJsonObject("data_request") {
                put("include_basic_info", true)
                put("include_tag_count", 20)
                put("include_platforms", true)
                put("include_release", true)
                put("include_reviews", true)
                put("include_assets", true)
            }
        }
        val url = api("IStoreBrowseService/GetItems/v1/")
            .addQueryParameter("input_json", input.toString())
            .build()
        val items = json.decodeFromString<StoreItemsEnvelope>(get(url)).response.storeItems
        val byId = items.associateBy { it.appid ?: it.id }
        return appIds.map { id ->
            val item = byId[id]
            if (item == null || item.success != 1) StoreInfo(appId = id, fetchedAt = now, found = false)
            else item.toStoreInfo(id, now)
        }
    }

    suspend fun getTagNames(): Map<Int, String> {
        val url = api("IStoreService/GetTagList/v1/").addQueryParameter("language", "english").build()
        return json.decodeFromString<TagListEnvelope>(get(url)).response.tags.associate { it.tagid to it.name }
    }

    private suspend fun get(url: HttpUrl): String = try {
        client.fetchString(Request.Builder().url(url).build())
    } catch (e: HttpStatusException) {
        throw when (e.code) {
            401, 403 -> SteamApiException("Steam rejected the API key (HTTP ${e.code}). Check that it's correct.")
            429 -> SteamApiException("Steam is rate limiting requests. Try again in a few minutes.")
            else -> SteamApiException("Steam API error (HTTP ${e.code}).")
        }
    }

    private fun api(path: String): HttpUrl.Builder = "https://api.steampowered.com/$path".toHttpUrl().newBuilder()

    private fun StoreItemDto.toStoreInfo(appId: Int, now: Long): StoreInfo {
        fun asset(file: String?): String? {
            val format = assets?.assetUrlFormat ?: return null
            if (file.isNullOrBlank()) return null
            return "$ASSET_BASE${format.replace("\${FILENAME}", file)}"
        }
        val summary = reviews?.summaryFiltered
        return StoreInfo(
            appId = appId,
            fetchedAt = now,
            found = true,
            shortDescription = basicInfo?.shortDescription,
            developers = basicInfo?.developers.orEmpty().map { it.name },
            publishers = basicInfo?.publishers.orEmpty().map { it.name },
            releaseDate = release?.steamReleaseDate ?: release?.originalReleaseDate,
            windows = platforms?.windows == true,
            mac = platforms?.mac == true,
            linux = platforms?.steamosLinux == true,
            deckCompat = platforms?.steamDeckCompatCategory ?: 0,
            tagIds = tagids,
            playerCategoryIds = categories?.supportedPlayerCategoryIds.orEmpty(),
            controllerCategoryIds = categories?.controllerCategoryIds.orEmpty(),
            reviewPercent = summary?.percentPositive?.takeIf { (summary.reviewCount ?: 0) > 0 },
            reviewCount = summary?.reviewCount,
            reviewLabel = summary?.reviewScoreLabel,
            headerImage = asset(assets?.header),
            capsuleImage = asset(assets?.libraryCapsule ?: assets?.libraryCapsule2x),
            heroImage = asset(assets?.libraryHero),
        )
    }

    private companion object {
        const val ASSET_BASE = "https://shared.akamai.steamstatic.com/store_item_assets/"
    }
}

@Serializable
private data class OwnedGamesEnvelope(val response: OwnedGamesResponse = OwnedGamesResponse())

@Serializable
private data class OwnedGamesResponse(val games: List<OwnedGameDto>? = null)

@Serializable
private data class OwnedGameDto(
    val appid: Int,
    val name: String? = null,
    @SerialName("playtime_forever") val playtimeForever: Int = 0,
    @SerialName("playtime_2weeks") val playtime2Weeks: Int = 0,
    @SerialName("playtime_linux_forever") val playtimeLinux: Int = 0,
    @SerialName("playtime_deck_forever") val playtimeDeck: Int = 0,
    @SerialName("rtime_last_played") val lastPlayed: Long = 0,
)

@Serializable
private data class PlayersEnvelope(val response: PlayersResponse = PlayersResponse())

@Serializable
private data class PlayersResponse(val players: List<PlayerDto> = emptyList())

@Serializable
private data class PlayerDto(
    val steamid: String,
    val personaname: String = "",
    val avatarfull: String? = null,
    val profileurl: String? = null,
)

@Serializable
private data class VanityEnvelope(val response: VanityResponse = VanityResponse())

@Serializable
private data class VanityResponse(val steamid: String? = null, val success: Int = 0)

@Serializable
private data class TagListEnvelope(val response: TagListResponse = TagListResponse())

@Serializable
private data class TagListResponse(val tags: List<TagDto> = emptyList())

@Serializable
private data class TagDto(val tagid: Int, val name: String)

@Serializable
private data class StoreItemsEnvelope(val response: StoreItemsResponse = StoreItemsResponse())

@Serializable
private data class StoreItemsResponse(@SerialName("store_items") val storeItems: List<StoreItemDto> = emptyList())

@Serializable
private data class StoreItemDto(
    val id: Int? = null,
    val appid: Int? = null,
    val success: Int = 0,
    val tagids: List<Int> = emptyList(),
    val categories: CategoriesDto? = null,
    val reviews: ReviewsDto? = null,
    @SerialName("basic_info") val basicInfo: BasicInfoDto? = null,
    val release: ReleaseDto? = null,
    val platforms: PlatformsDto? = null,
    val assets: AssetsDto? = null,
)

@Serializable
private data class CategoriesDto(
    @SerialName("supported_player_categoryids") val supportedPlayerCategoryIds: List<Int> = emptyList(),
    @SerialName("controller_categoryids") val controllerCategoryIds: List<Int> = emptyList(),
)

@Serializable
private data class ReviewsDto(@SerialName("summary_filtered") val summaryFiltered: ReviewSummaryDto? = null)

@Serializable
private data class ReviewSummaryDto(
    @SerialName("review_count") val reviewCount: Int? = null,
    @SerialName("percent_positive") val percentPositive: Int? = null,
    @SerialName("review_score_label") val reviewScoreLabel: String? = null,
)

@Serializable
private data class BasicInfoDto(
    @SerialName("short_description") val shortDescription: String? = null,
    val publishers: List<NameDto> = emptyList(),
    val developers: List<NameDto> = emptyList(),
)

@Serializable
private data class NameDto(val name: String)

@Serializable
private data class ReleaseDto(
    @SerialName("steam_release_date") val steamReleaseDate: Long? = null,
    @SerialName("original_release_date") val originalReleaseDate: Long? = null,
)

@Serializable
private data class PlatformsDto(
    val windows: Boolean = false,
    val mac: Boolean = false,
    @SerialName("steamos_linux") val steamosLinux: Boolean = false,
    @SerialName("steam_deck_compat_category") val steamDeckCompatCategory: Int = 0,
)

@Serializable
private data class AssetsDto(
    @SerialName("asset_url_format") val assetUrlFormat: String? = null,
    val header: String? = null,
    @SerialName("library_capsule") val libraryCapsule: String? = null,
    @SerialName("library_capsule_2x") val libraryCapsule2x: String? = null,
    @SerialName("library_hero") val libraryHero: String? = null,
)
