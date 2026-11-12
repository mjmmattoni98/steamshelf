package dev.steamshelf

import android.content.Context
import dev.steamshelf.data.CacheStore
import dev.steamshelf.data.LibraryRepository
import dev.steamshelf.data.SettingsStore
import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.data.steam.SteamApi
import dev.steamshelf.sync.WorkManagerSyncLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency graph; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024))
        .build()

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context, json)
    val steamApi = SteamApi(httpClient, json)
    private val protonDbApi = ProtonDbApi(httpClient, json)
    private val hltbApi = HltbApi(httpClient, json)

    val repository = LibraryRepository(
        steam = steamApi,
        protonDb = protonDbApi,
        hltb = hltbApi,
        cache = CacheStore(File(context.filesDir, "library-cache"), json),
        settings = settings,
        scope = appScope,
        launcher = WorkManagerSyncLauncher(context.applicationContext),
    )
}
