package dev.steamshelf.server

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.steamshelf.data.CacheStore
import dev.steamshelf.data.LibraryRepository
import dev.steamshelf.data.SettingsStore
import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.hltb.HltbGame
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.data.steam.SteamApi
import dev.steamshelf.model.GameItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The server's dependency graph, the counterpart of the app's AppContainer. */
class ServerContainer(
    val config: ServerConfig,
    /** Tests pass a client that sends every request to a fake backend. */
    httpClient: OkHttpClient? = null,
    val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(config.dataDir, "http-cache"), 20L * 1024 * 1024))
        .build()

    val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = ioScope) { File(config.dataDir, "settings.preferences_pb") },
        json,
    )

    val serverSettings: ServerSettings = runBlocking {
        ServerSettings.open(
            PreferenceDataStoreFactory.create(scope = ioScope) { File(config.dataDir, "server.preferences_pb") },
            scope,
        )
    }

    val steam = SteamApi(http, json)

    val repository = LibraryRepository(
        steam = steam,
        protonDb = ProtonDbApi(http, json),
        hltb = HltbApi(http, json),
        cache = CacheStore(File(config.dataDir, "library-cache"), json),
        settings = settings,
        scope = scope,
        clock = clock,
        storeLanguage = { serverSettings.prefs.value.storeLanguage },
        storeCountry = { serverSettings.prefs.value.storeCountry },
    )

    val details = GameDetails(repository, scope, clock)

    /**
     * Refreshes the library on the same schedule as the app (more than six hours since the last sync, or the
     * store language changed), checking every few minutes, whether or not a browser is open.
     */
    fun startAutoSync(checkEveryMillis: Long = AUTO_SYNC_CHECK_MS): Job = scope.launch {
        while (isActive) {
            try {
                if (repository.shouldAutoSync()) repository.sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Auto-sync check failed", e)
            }
            delay(checkEveryMillis)
        }
    }

    /** Writes pending cache changes and stops background work. */
    override fun close() {
        runBlocking { repository.flush() }
        scope.cancel()
        ioScope.cancel()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ServerContainer::class.java)
        const val AUTO_SYNC_CHECK_MS = 10 * 60 * 1000L
    }
}

/**
 * What the game page needs beyond the library: fetching missing details in the background (the page polls
 * while that runs) and remembering HowLongToBeat search results, so a pick only has to send the HLTB id.
 */
class GameDetails(
    private val repository: LibraryRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private class Fetch(val job: Job, val startedAt: Long)

    private val fetches = ConcurrentHashMap<Int, Fetch>()
    private val searchResults = ConcurrentHashMap<Int, HltbGame>()

    /**
     * Starts fetching whatever [item] is missing (store data, ProtonDB, HLTB) unless that was tried in the
     * last minute. True while a fetch for it is running.
     */
    fun loading(item: GameItem): Boolean {
        val missing = item.store == null || item.proton == null || item.hltb == null
        val fetch = if (!missing) {
            fetches[item.appId]
        } else {
            fetches.compute(item.appId) { _, current ->
                if (current != null && (current.job.isActive || clock() - current.startedAt < RETRY_AFTER_MS)) current
                else Fetch(scope.launch { repository.ensureDetails(item.appId) }, clock())
            }
        }
        return fetch?.job?.isActive == true
    }

    suspend fun search(query: String): List<HltbGame> {
        val results = repository.searchHltb(query)
        if (searchResults.size > MAX_REMEMBERED) searchResults.clear()
        results.forEach { searchResults[it.id] = it }
        return results
    }

    fun searchResult(hltbId: Int): HltbGame? = searchResults[hltbId]

    suspend fun item(appId: Int): GameItem? {
        repository.awaitLoaded()
        return repository.items.first().firstOrNull { it.appId == appId }
    }

    private companion object {
        const val RETRY_AFTER_MS = 60_000L
        const val MAX_REMEMBERED = 500
    }
}
