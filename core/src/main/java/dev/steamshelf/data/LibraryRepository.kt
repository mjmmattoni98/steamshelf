package dev.steamshelf.data

import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.hltb.HltbGame
import dev.steamshelf.data.hltb.HltbMatcher
import dev.steamshelf.data.hltb.HltbRateLimitedException
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.data.steam.SteamApi
import dev.steamshelf.data.steam.SteamLanguage
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.HltbInfo
import dev.steamshelf.model.OwnedGame
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.model.ProtonInfo
import dev.steamshelf.model.StoreInfo
import dev.steamshelf.model.SyncPhase
import dev.steamshelf.model.SyncProblem
import dev.steamshelf.model.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
 * Starts and stops the library sync. The app runs it through WorkManager so it outlives the UI and the
 * process; either way the work ends up in [LibraryRepository.runSync].
 */
interface SyncLauncher {
    fun start(force: Boolean)
    fun stop()
}

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
    launcher: SyncLauncher? = null,
    private val storeLanguage: () -> String = { SteamLanguage.forLocale(Locale.getDefault()) },
    /** Two-letter country the store data is requested for (availability differs by region). */
    private val storeCountry: () -> String = { Locale.getDefault().country.takeIf { it.length == 2 } ?: "US" },
) {
    private val games = MutableStateFlow<List<OwnedGame>>(emptyList())
    private val store = MutableStateFlow<Map<Int, StoreInfo>>(emptyMap())
    private val proton = MutableStateFlow<Map<Int, ProtonInfo>>(emptyMap())
    private val hltbTimes = MutableStateFlow<Map<Int, HltbInfo>>(emptyMap())
    private val statuses = MutableStateFlow<Map<Int, PlayStatus>>(emptyMap())
    private val _tagNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val tagLanguage = MutableStateFlow<String?>(null)
    private val _sync = MutableStateFlow(SyncStatus())
    private val _loaded = MutableStateFlow(false)

    val tagNames: StateFlow<Map<Int, String>> = _tagNames.asStateFlow()
    val sync: StateFlow<SyncStatus> = _sync.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val items: Flow<List<GameItem>> = combine(games, store, proton, hltbTimes, statuses) { owned, s, p, h, st ->
        owned.map { GameItem(it, s[it.appId], p[it.appId], h[it.appId], st[it.appId]) }
    }

    private val launcher: SyncLauncher = launcher ?: InProcessLauncher()

    // Guards [syncing] and [followUp] together, so a change can't slip in while a sync is deciding to end.
    private val syncLock = Any()
    private var syncing = false
    private var followUp = false
    @Volatile private var activeSync: Job? = null

    /** Completes once the current run has reset the status, which is later than [activeSync] ending. */
    @Volatile private var currentRun: Job? = null

    /** The sync options as they are now; a running sync checks them to stop a source the user turned off. */
    private val livePrefs = MutableStateFlow<SyncPrefs?>(null)

    /** The sync options the repository has seen so far; lets tests wait for a settings change to arrive. */
    internal val observedSyncPrefs: StateFlow<SyncPrefs?> = livePrefs.asStateFlow()

    private val persisted = listOf(
        Persisted(games, GAMES_FILE, ListSerializer(OwnedGame.serializer())) { it },
        Persisted(store, STORE_FILE, ListSerializer(StoreInfo.serializer())) { it.values.toList() },
        Persisted(proton, PROTON_FILE, ListSerializer(ProtonInfo.serializer())) { it.values.toList() },
        Persisted(hltbTimes, HLTB_FILE, ListSerializer(HltbInfo.serializer())) { it.values.toList() },
        Persisted(statuses, STATUS_FILE, MapSerializer(Int.serializer(), PlayStatus.serializer())) { it },
        Persisted(_tagNames, TAGS_FILE, MapSerializer(Int.serializer(), String.serializer())) { it },
        Persisted(tagLanguage, TAG_LANGUAGE_FILE, String.serializer()) { it ?: SteamLanguage.ENGLISH },
    )
    private val persistLock = Mutex()

    init {
        scope.launch {
            loadFromDisk()
            persisted.forEach { it.markWritten() }
            _loaded.value = true
            launch { writeChangesPeriodically() }
            watchSyncPrefs()
        }
    }

    suspend fun awaitLoaded() {
        _loaded.first { it }
    }

    /** Checked whenever the app comes to the foreground. */
    suspend fun shouldAutoSync(): Boolean {
        awaitLoaded()
        if (settings.account.first() == null) return false
        val last = settings.syncPrefs.first().lastLibrarySync
        val languageChanged = _tagNames.value.isNotEmpty() && (tagLanguage.value ?: SteamLanguage.ENGLISH) != storeLanguage()
        return games.value.isEmpty() || clock() - last > AUTO_SYNC_INTERVAL_MS || languageChanged
    }

    /** Reloads the library from Steam, then fills in anything missing or stale. No-op if already running. */
    fun sync(force: Boolean = false) {
        // Shown as running straight away, so the UI doesn't flicker while the work is being scheduled.
        while (true) {
            val current = _sync.value
            if (current.running) return
            if (_sync.compareAndSet(current, current.copy(running = true, phase = null, done = 0, total = 0))) break
        }
        launcher.start(force)
    }

    fun cancelSync() {
        val wasSyncing = synchronized(syncLock) {
            followUp = false
            syncing
        }
        launcher.stop()
        activeSync?.cancel()
        // Nothing had started yet: clear the "scheduled" state ourselves.
        if (!wasSyncing) finish(null)
    }

    /**
     * Stops the sync and waits until its status is reset. Waiting only for the passes to end isn't enough:
     * a caller that goes on to sync() could still find the old run marked as running and be ignored.
     */
    private suspend fun stopSync() {
        cancelSync()
        currentRun?.join()
    }

    /** As [runSync] below; a forced sync refreshes everything cached so far. */
    suspend fun runSync(force: Boolean) = runSync(refreshBefore = if (force) clock() else null)

    /**
     * Runs a sync in the caller's coroutine (the WorkManager worker, in the app) and returns when it ends.
     * Returns at once if another sync is already running. Cancelling the caller stops the sync cleanly.
     *
     * With [refreshBefore], everything fetched up to that time is fetched again whatever its age ("Refresh
     * everything"). It is a time rather than a flag so that a refresh which is stopped and run again (after
     * the process died, say) carries on where it stopped instead of starting over.
     */
    suspend fun runSync(refreshBefore: Long?) {
        val run = synchronized(syncLock) {
            if (syncing) return
            syncing = true
            followUp = false
            Job().also { currentRun = it }
        }
        var outcome: SyncProblem? = null
        var completed = false
        try {
            coroutineScope {
                val job = launch {
                    outcome = syncPasses(refreshBefore)
                    completed = true
                }
                activeSync = job
                job.join()
            }
        } finally {
            activeSync = null
            val again = synchronized(syncLock) {
                syncing = false
                followUp.also { followUp = false }
            }
            // Published only now, so a sync() that sees "not running" can always start a new one.
            finish(outcome)
            run.complete()
            withContext(NonCancellable) { flush() }
            // A setting changed right as the last pass ended.
            if (again && completed) sync()
        }
    }

    /** Runs passes until no setting changed during the last one; returns the first problem, if any. */
    private suspend fun syncPasses(initialRefreshBefore: Long?): SyncProblem? {
        var refreshBefore = initialRefreshBefore
        val problems = mutableListOf<SyncProblem>()
        return try {
            awaitLoaded()
            while (runPass(refreshBefore, problems)) {
                refreshBefore = null
                // A setting changed during this pass: go round again (only missing data is fetched).
                val again = synchronized(syncLock) { followUp.also { followUp = false } }
                if (!again) break
            }
            problems.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncProblem.Failed(e)
        }
    }

    /** One sync pass; false if there's no account to sync. */
    private suspend fun runPass(refreshBefore: Long?, problems: MutableList<SyncProblem>): Boolean {
        val account = settings.account.first() ?: return false
        val prefs = settings.syncPrefs.first()
        setPhase(SyncPhase.LIBRARY, 0, 0)
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
            problems += SyncProblem.LibraryNotRefreshed(e)
            games.value
        }

        val language = storeLanguage()
        if (refreshBefore != null || _tagNames.value.isEmpty() || (tagLanguage.value ?: SteamLanguage.ENGLISH) != language) {
            attempt { steam.getTagNames(language) }?.let {
                _tagNames.value = it
                tagLanguage.value = language
            }
        }
        syncStore(owned, refreshBefore, language)?.let(problems::add)
        if (prefs.autoProtonDb) syncProton(owned, refreshBefore)?.let(problems::add)
        if (prefs.autoHltb) syncHltb(owned, refreshBefore)?.let(problems::add)
        return true
    }

    /** Fetches whatever is missing for one game right away (used by the detail screen). */
    suspend fun ensureDetails(appId: Int) = coroutineScope {
        val game = games.value.firstOrNull { it.appId == appId } ?: return@coroutineScope
        val now = clock()
        launch {
            if (store.value[appId] == null) {
                attempt { steam.getStoreItems(listOf(appId), country(), now, storeLanguage()) }
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

    /** Accepts the current automatic match as correct, so it's no longer flagged and never replaced. */
    fun confirmHltbMatch(appId: Int) {
        hltbTimes.update { current ->
            val info = current[appId]?.takeIf { it.found } ?: return@update current
            current + (appId to info.copy(manual = true))
        }
    }

    /** Throws away the cached match (manual or not) and runs automatic matching again. */
    suspend fun rematchHltb(appId: Int) {
        val game = games.value.firstOrNull { it.appId == appId } ?: return
        val info = lookupHltb(game)
        hltbTimes.update { it + (appId to info) }
    }

    /** Sets the user's status for a game; null goes back to automatic. */
    fun setPlayStatus(appId: Int, status: PlayStatus?) {
        statuses.update { if (status == null) it - appId else it + (appId to status) }
    }

    /** Clears ratings, times and store data (keeping the user's own matches and statuses) and re-syncs. */
    suspend fun clearEnrichment() {
        stopSync()
        store.value = emptyMap()
        proton.value = emptyMap()
        hltbTimes.update { current -> current.filterValues { it.manual } }
        sync()
    }

    /** Sign-out: stop syncing and forget everything about the account, including hand-made matches and statuses. */
    suspend fun clearAll() {
        stopSync()
        persistLock.withLock {
            games.value = emptyList()
            store.value = emptyMap()
            proton.value = emptyMap()
            hltbTimes.value = emptyMap()
            statuses.value = emptyMap()
            _tagNames.value = emptyMap()
            tagLanguage.value = null
            // Marking the empty state as written keeps the periodic writer from recreating the files.
            persisted.forEach { it.markWritten() }
            cache.clear()
        }
        _sync.value = SyncStatus()
    }

    /** Writes every cache file whose data changed since it was last written. Called when the app goes to the background. */
    suspend fun flush() {
        // Before the cache is loaded the flows are still empty, and writing them would wipe the files.
        if (!_loaded.value) return
        persistLock.withLock { persisted.forEach { it.writeIfChanged(cache) } }
    }

    // region sync phases

    private suspend fun syncStore(owned: List<OwnedGame>, refreshBefore: Long?, language: String): SyncProblem? {
        val now = clock()
        val todo = owned.map { it.appId }.filter { id ->
            val info = store.value[id]
            info == null || refreshDue(info.fetchedAt, refreshBefore) ||
                now - info.fetchedAt > (if (info.found) STORE_TTL_MS else STORE_MISS_TTL_MS) ||
                // Fetched in another language: the device language changed since.
                (info.found && (info.language ?: SteamLanguage.ENGLISH) != language)
        }
        if (todo.isEmpty()) return null
        var done = 0
        var failedBatches = 0
        setPhase(SyncPhase.STORE, 0, todo.size)
        for (batch in todo.chunked(STORE_BATCH_SIZE)) {
            val infos = attempt { steam.getStoreItems(batch, country(), now, language) }
            if (infos == null) failedBatches++ else store.update { it + infos.associateBy(StoreInfo::appId) }
            done += batch.size
            setPhase(SyncPhase.STORE, done, todo.size)
        }
        return if (failedBatches > 0) SyncProblem.StoreIncomplete else null
    }

    private suspend fun syncProton(owned: List<OwnedGame>, refreshBefore: Long?): SyncProblem? {
        val now = clock()
        val todo = owned.map { it.appId }.filter { id ->
            val info = proton.value[id]
            info == null || refreshDue(info.fetchedAt, refreshBefore) || now - info.fetchedAt > PROTON_TTL_MS
        }
        if (todo.isEmpty()) return null
        val failures = AtomicInteger()
        try {
            runBatched(SyncPhase.PROTONDB, todo, concurrency = 6, target = proton) { id ->
                if (livePrefs.value?.autoProtonDb == false) throw SourceTurnedOff()
                attempt { protonDb.summary(id, now) } ?: run { failures.incrementAndGet(); null }
            }
        } catch (_: SourceTurnedOff) {
            return null
        }
        return failures.get().takeIf { it > 0 }?.let { SyncProblem.ProtonIncomplete(it) }
    }

    private suspend fun syncHltb(owned: List<OwnedGame>, refreshBefore: Long?): SyncProblem? {
        val now = clock()
        val todo = owned
            .filter { game ->
                val info = hltbTimes.value[game.appId]
                when {
                    info == null -> true
                    info.manual -> false
                    refreshDue(info.fetchedAt, refreshBefore) -> true
                    else -> now - info.fetchedAt > if (info.found) HLTB_TTL_MS else HLTB_MISS_TTL_MS
                }
            }
            // Recently played and never-played games first: that's what backlog planning needs.
            .sortedWith(compareByDescending<OwnedGame> { it.playtime2Weeks > 0 }.thenByDescending { it.playtimeForever == 0 })
        if (todo.isEmpty()) return null
        val byId = todo.associateBy { it.appId }
        val consecutiveFailures = AtomicInteger()
        return try {
            // A match the user picked while this phase was running wins over the automatic one.
            val keepManual = { old: HltbInfo? -> old?.manual != true }
            runBatched(SyncPhase.HLTB, todo.map { it.appId }, concurrency = 2, target = hltbTimes, replace = keepManual) { id ->
                if (livePrefs.value?.autoHltb == false) throw SourceTurnedOff()
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
        } catch (_: HltbUnavailable) {
            SyncProblem.HltbUnavailable
        } catch (_: SourceTurnedOff) {
            null
        }
    }

    /** Whether a forced refresh asked for at [refreshBefore] still has to refetch data from [fetchedAt]. */
    private fun refreshDue(fetchedAt: Long, refreshBefore: Long?): Boolean =
        refreshBefore != null && fetchedAt <= refreshBefore

    private class HltbUnavailable : Exception()

    /** The user switched a source off in settings while its phase was running. */
    private class SourceTurnedOff : Exception()

    private suspend fun lookupHltbWithRetry(game: OwnedGame): HltbInfo? {
        repeat(3) { attemptNo ->
            try {
                return lookupHltb(game)
            } catch (_: HltbRateLimitedException) {
                delay(HLTB_RATE_LIMIT_BACKOFF_MS * (attemptNo + 1))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
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
        replace: (old: T?) -> Boolean = { true },
        fetch: suspend (Int) -> T?,
    ) = coroutineScope {
        val pending = ConcurrentHashMap<Int, T>()
        val done = AtomicInteger()
        fun flushPending() {
            if (pending.isNotEmpty()) {
                val snapshot = HashMap(pending)
                snapshot.keys.forEach { pending.remove(it) }
                target.update { current -> current + snapshot.filterKeys { replace(current[it]) } }
            }
            setPhase(phase, done.get(), ids.size)
        }
        setPhase(phase, 0, ids.size)
        val flusher = launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushPending()
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
            flushPending()
        }
    }

    // endregion

    /**
     * Keeps [livePrefs] current, and fetches what a changed setting needs: a source that was turned on, or
     * the library again when "Include free games" changes. A running sync picks the change up in an
     * extra pass when it finishes, since it read its options when it started.
     */
    private suspend fun watchSyncPrefs() {
        var previous: SyncPrefs? = null
        settings.syncPrefs.collect { prefs ->
            livePrefs.value = prefs
            val old = previous
            previous = prefs
            val needsSync = old != null && (
                (prefs.autoProtonDb && !old.autoProtonDb) ||
                    (prefs.autoHltb && !old.autoHltb) ||
                    prefs.includeFreeGames != old.includeFreeGames
                )
            if (needsSync && settings.account.first() != null) {
                val running = synchronized(syncLock) {
                    if (syncing) followUp = true
                    syncing
                }
                if (!running) sync()
            }
        }
    }

    private fun setPhase(phase: SyncPhase, done: Int, total: Int) =
        _sync.update { it.copy(running = true, phase = phase, done = done, total = total) }

    private fun finish(problem: SyncProblem?) = _sync.update {
        if (problem == null) SyncStatus(errorId = it.errorId) else SyncStatus(error = problem, errorId = it.errorId + 1)
    }

    private suspend fun loadFromDisk() {
        cache.read(GAMES_FILE, ListSerializer(OwnedGame.serializer()))?.let { games.value = it }
        cache.read(STORE_FILE, ListSerializer(StoreInfo.serializer()))?.let { list -> store.value = list.associateBy { it.appId } }
        cache.read(PROTON_FILE, ListSerializer(ProtonInfo.serializer()))?.let { list -> proton.value = list.associateBy { it.appId } }
        cache.read(HLTB_FILE, ListSerializer(HltbInfo.serializer()))?.let { list -> hltbTimes.value = list.associateBy { it.appId } }
        cache.read(STATUS_FILE, MapSerializer(Int.serializer(), PlayStatus.serializer()))?.let { statuses.value = it }
        cache.read(TAGS_FILE, MapSerializer(Int.serializer(), String.serializer()))?.let { _tagNames.value = it }
        cache.read(TAG_LANGUAGE_FILE, String.serializer())?.let { tagLanguage.value = it }
    }

    /**
     * Writes changed data at most every [PERSIST_INTERVAL_MS]. Unlike a debounce, a steady stream of
     * updates (a long sync publishes every [FLUSH_INTERVAL_MS]) can't postpone the write indefinitely, and
     * the last change is always written once the interval is over.
     */
    private suspend fun writeChangesPeriodically() {
        merge(*persisted.map { it.flow }.toTypedArray()).conflate().collect {
            flush()
            delay(PERSIST_INTERVAL_MS)
        }
    }

    /** One cache file and the flow it mirrors. Only touched under [persistLock]. */
    private class Persisted<S, T>(
        val flow: StateFlow<S>,
        val name: String,
        val serializer: KSerializer<T>,
        val transform: (S) -> T,
    ) {
        // Flow values are immutable, so identity tells whether anything changed since the last write.
        private var written: Any? = null

        fun markWritten() {
            written = flow.value
        }

        suspend fun writeIfChanged(cache: CacheStore) {
            val value = flow.value
            if (value === written) return
            try {
                cache.write(name, serializer, transform(value))
                written = value
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Disk full or similar: try again on the next write.
            }
        }
    }

    private fun country(): String = storeCountry()

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
    } catch (_: Exception) {
        null
    }

    /** Runs syncs in [scope]; used when no launcher is given (tests). */
    private inner class InProcessLauncher : SyncLauncher {
        override fun start(force: Boolean) {
            scope.launch { runSync(force) }
        }

        override fun stop() {
            activeSync?.cancel()
        }
    }

    companion object {
        private const val GAMES_FILE = "games.json"
        private const val STORE_FILE = "store.json"
        private const val PROTON_FILE = "protondb.json"
        private const val HLTB_FILE = "hltb.json"
        private const val STATUS_FILE = "statuses.json"
        private const val TAGS_FILE = "tags.json"
        private const val TAG_LANGUAGE_FILE = "tags-language.json"

        private const val DAY_MS = 24 * 60 * 60 * 1000L
        const val AUTO_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val STORE_TTL_MS = 14 * DAY_MS
        const val STORE_MISS_TTL_MS = 7 * DAY_MS
        const val PROTON_TTL_MS = 7 * DAY_MS
        const val HLTB_TTL_MS = 30 * DAY_MS
        const val HLTB_MISS_TTL_MS = 14 * DAY_MS

        private const val STORE_BATCH_SIZE = 50
        const val FLUSH_INTERVAL_MS = 750L
        const val PERSIST_INTERVAL_MS = 3_000L
        private const val HLTB_REQUEST_SPACING_MS = 350L
        private const val HLTB_RATE_LIMIT_BACKOFF_MS = 30_000L
        const val HLTB_MAX_CONSECUTIVE_FAILURES = 8
    }
}
