package dev.steamshelf.data

import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.hltb.HltbGame
import dev.steamshelf.data.hltb.HltbMatcher
import dev.steamshelf.data.hltb.HltbRateLimitedException
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.data.steam.SteamApi
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.HltbInfo
import dev.steamshelf.model.OwnedGame
import dev.steamshelf.model.ProtonInfo
import dev.steamshelf.model.StoreInfo
import dev.steamshelf.model.SyncPhase
import dev.steamshelf.model.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single source of truth for the library: owned games from Steam plus store metadata, ProtonDB ratings
 * and HowLongToBeat times. Everything is cached on disk and refreshed incrementally in the background.
 */
class LibraryRepository(
    private val steam: SteamApi,
    private val protonDb: ProtonDbApi,
    private val hltb: HltbApi,
    private val cache: CacheStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val games = MutableStateFlow<List<OwnedGame>>(emptyList())
    private val store = MutableStateFlow<Map<Int, StoreInfo>>(emptyMap())
    private val proton = MutableStateFlow<Map<Int, ProtonInfo>>(emptyMap())
    private val hltbTimes = MutableStateFlow<Map<Int, HltbInfo>>(emptyMap())
    private val _tagNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val _sync = MutableStateFlow(SyncStatus())
    private val _loaded = MutableStateFlow(false)

    val tagNames: StateFlow<Map<Int, String>> = _tagNames.asStateFlow()
    val sync: StateFlow<SyncStatus> = _sync.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val items: Flow<List<GameItem>> = combine(games, store, proton, hltbTimes) { owned, s, p, h ->
        owned.map { GameItem(it, s[it.appId], p[it.appId], h[it.appId]) }
    }

    private var syncJob: Job? = null

    init {
        scope.launch {
            loadFromDisk()
            _loaded.value = true
            persist(games, GAMES_FILE, ListSerializer(OwnedGame.serializer())) { it }
            persist(store, STORE_FILE, ListSerializer(StoreInfo.serializer())) { it.values.toList() }
            persist(proton, PROTON_FILE, ListSerializer(ProtonInfo.serializer())) { it.values.toList() }
            persist(hltbTimes, HLTB_FILE, ListSerializer(HltbInfo.serializer())) { it.values.toList() }
            persist(_tagNames, TAGS_FILE, MapSerializer(Int.serializer(), String.serializer())) { it }
        }
    }

    suspend fun awaitLoaded() {
        _loaded.first { it }
    }

    suspend fun shouldAutoSync(): Boolean {
        awaitLoaded()
        val last = settings.syncPrefs.first().lastLibrarySync
        return games.value.isEmpty() || clock() - last > AUTO_SYNC_INTERVAL_MS
    }

    /** Reloads the library from Steam, then fills in anything missing or stale. No-op if already running. */
    fun sync(force: Boolean = false) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            awaitLoaded()
            val account = settings.account.first() ?: return@launch
            val prefs = settings.syncPrefs.first()
            try {
                setPhase(SyncPhase.LIBRARY, 0, 0)
                val warnings = mutableListOf<String>()
                val owned = try {
                    steam.getOwnedGames(account.apiKey, account.steamId, prefs.includeFreeGames).also { fresh ->
                        games.value = fresh.sortedBy { it.name.lowercase(Locale.ROOT) }
                        settings.setLastLibrarySync(clock())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Steam down or offline: keep going with the cached library so ratings still refresh.
                    if (games.value.isEmpty()) throw e
                    warnings += e.message ?: "Couldn't reach Steam."
                    games.value
                }

                attempt { if (force || _tagNames.value.isEmpty()) _tagNames.value = steam.getTagNames() }
                syncStore(owned, force)?.let(warnings::add)
                if (prefs.autoProtonDb) syncProton(owned, force)?.let(warnings::add)
                if (prefs.autoHltb) syncHltb(owned, force)?.let(warnings::add)
                finish(warnings.firstOrNull())
            } catch (e: CancellationException) {
                finish(null)
                throw e
            } catch (e: Exception) {
                finish(e.message ?: "Sync failed")
            }
        }
    }

    fun cancelSync() {
        syncJob?.cancel()
    }

    /** Fetches whatever is missing for one game right away (used by the detail screen). */
    suspend fun ensureDetails(appId: Int) = coroutineScope {
        val game = games.value.firstOrNull { it.appId == appId } ?: return@coroutineScope
        val now = clock()
        launch {
            if (store.value[appId] == null) {
                attempt { steam.getStoreItems(listOf(appId), country(), now) }
                    ?.let { infos -> store.update { it + infos.associateBy(StoreInfo::appId) } }
            }
        }
        launch {
            if (proton.value[appId] == null) {
                attempt { protonDb.summary(appId, now) }?.let { info -> proton.update { it + (appId to info) } }
            }
        }
        launch {
            if (hltbTimes.value[appId] == null) {
                attempt { lookupHltb(game) }?.let { info -> hltbTimes.update { it + (appId to info) } }
            }
        }
    }

    suspend fun searchHltb(query: String): List<HltbGame> = hltb.search(query, size = 20)

    fun setHltbMatch(appId: Int, match: HltbGame) {
        hltbTimes.update { it + (appId to match.toInfo(appId, clock(), similarity = 1.0, manual = true)) }
    }

    fun setNoHltbMatch(appId: Int) {
        hltbTimes.update { it + (appId to HltbInfo(appId = appId, fetchedAt = clock(), manual = true)) }
    }

    /** Throws away the cached match (manual or not) and runs automatic matching again. */
    suspend fun rematchHltb(appId: Int) {
        val game = games.value.firstOrNull { it.appId == appId } ?: return
        val info = lookupHltb(game)
        hltbTimes.update { it + (appId to info) }
    }

    /** Clears ratings, times and store data (keeping hand-picked HLTB matches) and re-syncs. */
    suspend fun clearEnrichment() {
        syncJob?.cancelAndJoin()
        store.value = emptyMap()
        proton.value = emptyMap()
        hltbTimes.update { current -> current.filterValues { it.manual } }
        sync()
    }

    /** Sign-out: stop syncing and forget everything about the account. */
    suspend fun clearAll() {
        syncJob?.cancelAndJoin()
        games.value = emptyList()
        store.value = emptyMap()
        proton.value = emptyMap()
        hltbTimes.value = emptyMap()
        cache.clear()
        _sync.value = SyncStatus()
    }

    // region sync phases

    private suspend fun syncStore(owned: List<OwnedGame>, force: Boolean): String? {
        val now = clock()
        val todo = owned.map { it.appId }.filter { id ->
            val info = store.value[id]
            force || info == null || now - info.fetchedAt > if (info.found) STORE_TTL_MS else STORE_MISS_TTL_MS
        }
        if (todo.isEmpty()) return null
        var done = 0
        var failedBatches = 0
        setPhase(SyncPhase.STORE, 0, todo.size)
        for (batch in todo.chunked(STORE_BATCH_SIZE)) {
            val infos = attempt { steam.getStoreItems(batch, country(), now) }
            if (infos == null) failedBatches++ else store.update { it + infos.associateBy(StoreInfo::appId) }
            done += batch.size
            setPhase(SyncPhase.STORE, done, todo.size)
        }
        return if (failedBatches > 0) "Couldn't load Steam store details for some games." else null
    }

    private suspend fun syncProton(owned: List<OwnedGame>, force: Boolean): String? {
        val now = clock()
        val todo = owned.map { it.appId }.filter { id ->
            val info = proton.value[id]
            force || info == null || now - info.fetchedAt > PROTON_TTL_MS
        }
        if (todo.isEmpty()) return null
        val failures = AtomicInteger()
        runBatched(SyncPhase.PROTONDB, todo, concurrency = 6, target = proton) { id ->
            attempt { protonDb.summary(id, now) } ?: run { failures.incrementAndGet(); null }
        }
        return failures.get().takeIf { it > 0 }?.let { "Couldn't load ProtonDB ratings for $it games." }
    }

    private suspend fun syncHltb(owned: List<OwnedGame>, force: Boolean): String? {
        val now = clock()
        val todo = owned
            .filter { game ->
                val info = hltbTimes.value[game.appId]
                when {
                    info == null -> true
                    info.manual -> false
                    force -> true
                    else -> now - info.fetchedAt > if (info.found) HLTB_TTL_MS else HLTB_MISS_TTL_MS
                }
            }
            // Recently played and never-played games first: that's what backlog planning needs.
            .sortedWith(compareByDescending<OwnedGame> { it.playtime2Weeks > 0 }.thenByDescending { it.playtimeForever == 0 })
        if (todo.isEmpty()) return null
        val byId = todo.associateBy { it.appId }
        val consecutiveFailures = AtomicInteger()
        return try {
            runBatched(SyncPhase.HLTB, todo.map { it.appId }, concurrency = 2, target = hltbTimes) { id ->
                val info = lookupHltbWithRetry(byId.getValue(id))
                if (info == null) {
                    if (consecutiveFailures.incrementAndGet() >= HLTB_MAX_CONSECUTIVE_FAILURES) throw HltbUnavailable()
                } else {
                    consecutiveFailures.set(0)
                }
                delay(HLTB_REQUEST_SPACING_MS)
                info
            }
            null
        } catch (e: HltbUnavailable) {
            "HowLongToBeat isn't responding right now; times will be fetched on the next sync."
        }
    }

    private class HltbUnavailable : Exception()

    private suspend fun lookupHltbWithRetry(game: OwnedGame): HltbInfo? {
        repeat(3) { attemptNo ->
            try {
                return lookupHltb(game)
            } catch (e: HltbRateLimitedException) {
                delay(HLTB_RATE_LIMIT_BACKOFF_MS * (attemptNo + 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    private suspend fun lookupHltb(game: OwnedGame): HltbInfo {
        val now = clock()
        val year = store.value[game.appId]?.releaseDate?.takeIf { it > 0 }
            ?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).year }
        var best = HltbMatcher.bestMatch(game.name, year, hltb.search(HltbMatcher.searchQuery(game.name)))
        if (best == null) {
            for (query in HltbMatcher.fallbackQueries(game.name)) {
                best = HltbMatcher.bestMatch(game.name, year, hltb.search(query))
                if (best != null) break
            }
        }
        return best?.let { (match, score) -> match.toInfo(game.appId, now, score, manual = false) }
            ?: HltbInfo(appId = game.appId, fetchedAt = now)
    }

    /** Runs [fetch] over [ids] with bounded concurrency, publishing results in batches to keep the UI smooth. */
    private suspend fun <T : Any> runBatched(
        phase: SyncPhase,
        ids: List<Int>,
        concurrency: Int,
        target: MutableStateFlow<Map<Int, T>>,
        fetch: suspend (Int) -> T?,
    ) = coroutineScope {
        val pending = ConcurrentHashMap<Int, T>()
        val done = AtomicInteger()
        fun flush() {
            if (pending.isNotEmpty()) {
                val snapshot = HashMap(pending)
                snapshot.keys.forEach { pending.remove(it) }
                target.update { it + snapshot }
            }
            setPhase(phase, done.get(), ids.size)
        }
        setPhase(phase, 0, ids.size)
        val flusher = launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
        val permits = Semaphore(concurrency)
        try {
            ids.map { id ->
                launch {
                    permits.withPermit {
                        fetch(id)?.let { pending[id] = it }
                        done.incrementAndGet()
                    }
                }
            }.joinAll()
        } finally {
            flusher.cancel()
            flush()
        }
    }

    // endregion

    private fun setPhase(phase: SyncPhase, done: Int, total: Int) =
        _sync.update { it.copy(running = true, phase = phase, done = done, total = total) }

    private fun finish(message: String?) = _sync.update {
        if (message == null) SyncStatus(errorId = it.errorId) else SyncStatus(error = message, errorId = it.errorId + 1)
    }

    private suspend fun loadFromDisk() {
        cache.read(GAMES_FILE, ListSerializer(OwnedGame.serializer()))?.let { games.value = it }
        cache.read(STORE_FILE, ListSerializer(StoreInfo.serializer()))?.let { list -> store.value = list.associateBy { it.appId } }
        cache.read(PROTON_FILE, ListSerializer(ProtonInfo.serializer()))?.let { list -> proton.value = list.associateBy { it.appId } }
        cache.read(HLTB_FILE, ListSerializer(HltbInfo.serializer()))?.let { list -> hltbTimes.value = list.associateBy { it.appId } }
        cache.read(TAGS_FILE, MapSerializer(Int.serializer(), String.serializer()))?.let { _tagNames.value = it }
    }

    @OptIn(FlowPreview::class)
    private fun <S, T> persist(flow: StateFlow<S>, name: String, serializer: KSerializer<T>, transform: (S) -> T) {
        scope.launch {
            flow.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { value ->
                attempt { cache.write(name, serializer, transform(value)) }
            }
        }
    }

    private fun country(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

    private fun HltbGame.toInfo(appId: Int, now: Long, similarity: Double, manual: Boolean) = HltbInfo(
        appId = appId,
        fetchedAt = now,
        hltbId = id,
        name = name,
        mainSeconds = main,
        plusSeconds = plus,
        completionistSeconds = completionist,
        allStylesSeconds = allStyles,
        mainCount = mainCount,
        plusCount = plusCount,
        completionistCount = completionistCount,
        reviewScore = reviewScore,
        releaseYear = releaseYear,
        similarity = similarity,
        manual = manual,
    )

    /** Runs [block], turning failures into null but letting cancellation through. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val GAMES_FILE = "games.json"
        const val STORE_FILE = "store.json"
        const val PROTON_FILE = "protondb.json"
        const val HLTB_FILE = "hltb.json"
        const val TAGS_FILE = "tags.json"

        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val AUTO_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val STORE_TTL_MS = 14 * DAY_MS
        const val STORE_MISS_TTL_MS = 7 * DAY_MS
        const val PROTON_TTL_MS = 7 * DAY_MS
        const val HLTB_TTL_MS = 30 * DAY_MS
        const val HLTB_MISS_TTL_MS = 14 * DAY_MS

        const val STORE_BATCH_SIZE = 50
        const val FLUSH_INTERVAL_MS = 750L
        const val PERSIST_DEBOUNCE_MS = 1500L
        const val HLTB_REQUEST_SPACING_MS = 350L
        const val HLTB_RATE_LIMIT_BACKOFF_MS = 30_000L
        const val HLTB_MAX_CONSECUTIVE_FAILURES = 8
    }
}
