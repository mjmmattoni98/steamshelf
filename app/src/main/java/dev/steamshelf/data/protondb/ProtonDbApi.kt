package dev.steamshelf.data.protondb

import dev.steamshelf.data.net.HttpStatusException
import dev.steamshelf.data.net.fetch
import dev.steamshelf.model.ProtonInfo
import dev.steamshelf.model.ProtonTier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** ProtonDB's public per-game summary endpoint (the one their own site and browser extensions use). */
class ProtonDbApi(private val client: OkHttpClient, private val json: Json) {

    suspend fun summary(appId: Int, now: Long): ProtonInfo {
        val request = Request.Builder()
            .url("https://www.protondb.com/api/v1/reports/summaries/$appId.json")
            .build()
        val result = client.fetch(request)
        // 404 means nobody has reported on this game yet.
        if (result.code == 404) return ProtonInfo(appId = appId, fetchedAt = now)
        if (!result.isSuccessful) throw HttpStatusException(result.code, result.body.take(200))

        val dto = json.decodeFromString<SummaryDto>(result.body)
        return ProtonInfo(
            appId = appId,
            fetchedAt = now,
            tier = ProtonTier.parse(dto.tier),
            trendingTier = ProtonTier.parse(dto.trendingTier),
            bestReportedTier = ProtonTier.parse(dto.bestReportedTier),
            confidence = dto.confidence,
            score = dto.score,
            total = dto.total,
        )
    }

    companion object {
        fun pageUrl(appId: Int) = "https://www.protondb.com/app/$appId"
    }
}

@Serializable
private data class SummaryDto(
    val tier: String? = null,
    val trendingTier: String? = null,
    val bestReportedTier: String? = null,
    val confidence: String? = null,
    val score: Double? = null,
    val total: Int = 0,
)
