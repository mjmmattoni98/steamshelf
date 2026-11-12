package dev.steamshelf.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.steamshelf.data.hltb.HltbApi
import dev.steamshelf.data.protondb.ProtonDbApi
import dev.steamshelf.data.steam.SteamApi
import dev.steamshelf.model.Account
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.HltbInfo
import dev.steamshelf.model.OwnedGame
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.model.ProtonInfo
import dev.steamshelf.model.StoreInfo
import dev.steamshelf.model.SyncPhase
import dev.steamshelf.model.SyncProblem
import dev.steamshelf.model.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The repository against fake Steam, ProtonDB and HowLongToBeat servers. Syncs are run with [runSync]
 * directly (a recording launcher stands in for WorkManager); file writes and delays use virtual time.
 */
class LibraryRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val backend = FakeBackend()
    private val http = OkHttpClient()
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsStore
    private lateinit var cacheDir: File
    private val launcher = RecordingLauncher()
    private val now = 1_800_000_000_000L
    private var language = "english"

    @Before
    fun setUp() {
        server.dispatcher = backend
        server.start()
        cacheDir = tmp.root.resolve("library-cache")
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) { tmp.root.resolve("settings.preferences_pb") },
            testJson,
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        server.close()
    }

    // region expiry rules

    @Test
    fun `refetches only what is missing or expired`() = runTest {
        seedThreeGames()
        val repository = newRepository()

        repository.runSync(force = false)

        assertEquals(setOf(2, 3), backend.storeRequests.toSet())
        assertEquals(setOf(2, 3), backend.protonRequests.toSet())
        // 1 is fresh, 3 was picked by hand: only the expired "not on HLTB" entry is looked up again.
        assertEquals(listOf("Game Two"), backend.hltbSearches)
        assertEquals(SyncStatus(), repository.sync.value)
        val items = repository.items.first().associateBy { it.appId }
        assertEquals(now, items.getValue(2).store!!.fetchedAt)
        assertEquals(36_000L, items.getValue(2).hltb!!.mainSeconds)
    }

    @Test
    fun `a forced refresh refetches everything but keeps hand-picked matches`() = runTest {
        seedThreeGames()
        val repository = newRepository()

        repository.runSync(force = true)

        assertEquals(setOf(1, 2, 3), backend.storeRequests.toSet())
        assertEquals(setOf(1, 2, 3), backend.protonRequests.toSet())
        assertEquals(setOf("Game One", "Game Two"), backend.hltbSearches.toSet())
        assertEquals(MANUAL_MATCH, repository.items.first().first { it.appId == 3 }.hltb)
    }

    @Test
    fun `a forced refresh that is stopped and run again carries on where it stopped`() = runTest {
        backend.games = (1..30).map { it to "Game $it" }
        signIn()
        var time = now
        val repository = newRepository(clock = { time })
        repository.runSync(force = false)
        backend.clearRequests()

        // "Refresh everything" an hour later, stopped partway through the HLTB phase.
        time += 3_600_000
        val requestedAt = time
        time += 1_000
        val job = launch { repository.runSync(refreshBefore = requestedAt) }
        repository.sync.first { it.phase == SyncPhase.HLTB && it.done >= 5 }
        repository.cancelSync()
        job.join()
        assertEquals(30, backend.storeRequests.size)
        assertEquals(30, backend.protonRequests.size)
        backend.clearRequests()

        // The same request again, as when WorkManager reruns the worker after the process died.
        time += 1_000
        repository.runSync(refreshBefore = requestedAt)

        assertTrue(backend.storeRequests.isEmpty())
        assertTrue(backend.protonRequests.isEmpty())
        assertTrue("searched ${backend.hltbSearches.size} games again", backend.hltbSearches.size <= 25)
        assertTrue(repository.items.first().all { it.hltb!!.fetchedAt > requestedAt })
    }

    @Test
    fun `clearing the cache keeps hand-picked matches and statuses, then syncs again`() = runTest {
        seedThreeGames()
        val repository = newRepository()
        repository.awaitLoaded()
        repository.setPlayStatus(1, PlayStatus.FINISHED)

        repository.clearEnrichment()

        val cleared = repository.items.first().associateBy { it.appId }
        assertTrue(cleared.values.all { it.store == null && it.proton == null })
        assertEquals(listOf(null, null, MANUAL_MATCH), listOf(1, 2, 3).map { cleared.getValue(it).hltb })
        assertEquals(PlayStatus.FINISHED, cleared.getValue(1).status)
        assertEquals(listOf(false), launcher.starts)
        assertTrue(repository.sync.value.running)

        repository.runSync(force = false)

        val synced = repository.items.first().associateBy { it.appId }
        assertEquals(MANUAL_MATCH, synced.getValue(3).hltb)
        assertTrue(synced.getValue(1).hltb!!.found && synced.getValue(2).hltb!!.found)
        assertEquals(PlayStatus.FINISHED, synced.getValue(1).status)
        assertFalse(repository.sync.value.running)
    }

    @Test
    fun `store data is fetched again when the device language changes`() = runTest {
        seedThreeGames()
        language = "german"
        val repository = newRepository()

        repository.runSync(force = false)

        // Game 1's store entry is fresh but English (written before the language field existed).
        assertEquals(setOf(1, 2, 3), backend.storeRequests.toSet())
        assertEquals(listOf("german"), backend.tagLanguages)
        assertTrue(repository.items.first().all { it.store!!.language == "german" })
        assertEquals("Aktion", repository.tagNames.value[19])
    }

    // endregion

    // region failures and cancelling

    @Test
    fun `a cancelled sync leaves a clean status and can be started again`() = runTest {
        backend.games = (1..30).map { it to "Game $it" }
        signIn()
        val repository = newRepository()

        val job = launch { repository.runSync(force = false) }
        repository.sync.first { it.phase == SyncPhase.HLTB }
        repository.cancelSync()
        job.join()

        assertEquals(SyncStatus(), repository.sync.value)
        assertTrue(backend.hltbSearches.size < 30)
        repository.sync()
        assertEquals(listOf(false), launcher.starts)
        assertTrue(repository.sync.value.running)
    }

    @Test
    fun `clearing the cache during a sync stops it and asks for a new one`() = runTest {
        backend.games = (1..30).map { it to "Game $it" }
        signIn()
        val repository = newRepository()

        val job = launch { repository.runSync(force = false) }
        repository.sync.first { it.phase == SyncPhase.HLTB }
        repository.clearEnrichment()

        assertTrue(backend.hltbSearches.size < 30)
        assertTrue(repository.items.first().all { it.store == null && it.proton == null && it.hltb == null })
        assertEquals(listOf(false), launcher.starts)
        // The request stays pending after the stopped sync has wound down completely.
        job.join()
        assertEquals(SyncStatus(running = true), repository.sync.value)
    }

    @Test
    fun `the HLTB phase gives up after 8 failures in a row and says so`() = runTest {
        backend.games = (1..20).map { it to "Game $it" }
        backend.hltbFails = true
        signIn()
        val repository = newRepository()

        repository.runSync(force = false)

        val status = repository.sync.value
        assertFalse(status.running)
        assertEquals(SyncProblem.HltbUnavailable, status.error)
        // Two lookups run at once, so one more may already be on its way when the eighth failure lands.
        assertTrue("searched ${backend.hltbSearches.size} times", backend.hltbSearches.size in 8..9)
    }

    @Test
    fun `when Steam is unreachable the cached library is used and the failure is reported`() = runTest {
        seedGames(listOf(OwnedGame(1, "Game One"), OwnedGame(2, "Game Two")))
        backend.steamDown = true
        signIn()
        val repository = newRepository()

        repository.runSync(force = false)

        val status = repository.sync.value
        assertTrue(status.error is SyncProblem.LibraryNotRefreshed)
        assertEquals(1L, status.errorId)
        assertEquals(listOf(1, 2), repository.items.first().map(GameItem::appId))
        // Ratings still refreshed for the cached games.
        assertEquals(setOf(1, 2), backend.protonRequests.toSet())
    }

    @Test
    fun `without a cached library a Steam failure fails the sync`() = runTest {
        backend.steamDown = true
        signIn()
        val repository = newRepository()

        repository.runSync(force = false)

        assertTrue(repository.sync.value.error is SyncProblem.Failed)
        assertTrue(backend.protonRequests.isEmpty())
    }

    // endregion

    // region settings changes

    @Test
    fun `turning a source on starts a sync`() = runTest {
        signIn()
        settings.setAutoHltb(false)
        val repository = newRepository()
        awaitPrefs(repository) { !it.autoHltb }

        settings.setAutoHltb(true)

        awaitReal { launcher.starts.isNotEmpty() }
        assertEquals(listOf(false), launcher.starts)
    }

    @Test
    fun `a source turned on during a sync is fetched in an extra pass`() = runTest {
        backend.games = listOf(1 to "Game One", 2 to "Game Two")
        signIn()
        settings.setAutoHltb(false)
        val repository = newRepository()
        val gate = CountDownLatch(1)
        backend.protonGate = gate

        val job = launch { repository.runSync(force = false) }
        repository.sync.first { it.phase == SyncPhase.PROTONDB }
        settings.setAutoHltb(true)
        awaitPrefs(repository) { it.autoHltb }
        gate.countDown()
        job.join()

        assertEquals(setOf("Game One", "Game Two"), backend.hltbSearches.toSet())
        assertTrue(launcher.starts.isEmpty())
        assertEquals(SyncStatus(), repository.sync.value)
    }

    @Test
    fun `a source turned off during its phase stops it`() = runTest {
        backend.games = (1..40).map { it to "Game $it" }
        signIn()
        val repository = newRepository()

        val job = launch { repository.runSync(force = false) }
        repository.sync.first { it.phase == SyncPhase.HLTB && it.done >= 2 }
        settings.setAutoHltb(false)
        job.join()

        assertTrue("searched ${backend.hltbSearches.size} times", backend.hltbSearches.size < 40)
        assertEquals(SyncStatus(), repository.sync.value)
    }

    // endregion

    // region auto-sync (checked each time the app comes to the foreground)

    @Test
    fun `auto-sync needs an account`() = runTest {
        assertFalse(newRepository().shouldAutoSync())
    }

    @Test
    fun `auto-sync runs when there is no library yet`() = runTest {
        signIn()
        settings.setLastLibrarySync(now)
        assertTrue(newRepository().shouldAutoSync())
    }

    @Test
    fun `auto-sync waits six hours after the last library sync`() = runTest {
        seedThreeGames()
        cacheDir.resolve("tags.json").writeText("""{"19":"Action"}""")
        settings.setLastLibrarySync(now - 60_000)
        assertFalse(newRepository().shouldAutoSync())

        settings.setLastLibrarySync(now - LibraryRepository.AUTO_SYNC_INTERVAL_MS - 1)
        assertTrue(newRepository().shouldAutoSync())
    }

    @Test
    fun `auto-sync runs when the device language changed`() = runTest {
        seedThreeGames()
        cacheDir.resolve("tags.json").writeText("""{"19":"Action"}""")
        settings.setLastLibrarySync(now - 60_000)
        language = "german"
        assertTrue(newRepository().shouldAutoSync())
    }

    // endregion

    // region saving

    @Test
    fun `a steady stream of updates doesn't starve saving`() = runTest {
        val repository = newRepository()
        repository.awaitLoaded()
        val file = cacheDir.resolve("statuses.json")

        // An update every 500 ms for 10 s, faster than the old 1.5 s debounce could ever settle.
        for (appId in 1..20) {
            repository.setPlayStatus(appId, PlayStatus.HIDDEN)
            advanceTimeBy(500)
        }

        assertTrue("saved ${readStatuses(file).size}", readStatuses(file).size >= 13)
        advanceTimeBy(LibraryRepository.PERSIST_INTERVAL_MS + 1)
        assertEquals((1..20).toSet(), readStatuses(file).keys)
    }

    @Test
    fun `flush writes pending changes immediately`() = runTest {
        val repository = newRepository()
        repository.awaitLoaded()
        repository.setPlayStatus(7, PlayStatus.DROPPED)
        assertFalse(cacheDir.resolve("statuses.json").exists())

        repository.flush()

        assertEquals(mapOf(7 to PlayStatus.DROPPED), readStatuses(cacheDir.resolve("statuses.json")))
    }

    @Test
    fun `nothing is written after sign-out`() = runTest {
        seedThreeGames()
        val repository = newRepository()
        repository.awaitLoaded()
        // Pending changes that the periodic writer hasn't saved yet.
        repository.setPlayStatus(1, PlayStatus.FINISHED)
        repository.setNoHltbMatch(2)

        repository.clearAll()
        advanceTimeBy(10 * LibraryRepository.PERSIST_INTERVAL_MS)
        repository.flush()

        assertTrue(cacheDir.listFiles().isNullOrEmpty())
        assertTrue(repository.items.first().isEmpty())
    }

    @Test
    fun `files written by the previous version still load`() = runTest {
        // The JSON as the previous version wrote it: no language, no statuses file, no tag language.
        cacheDir.mkdirs()
        cacheDir.resolve("games.json").writeText("""[{"appId":10,"name":"Old","playtimeForever":60}]""")
        cacheDir.resolve("store.json").writeText("""[{"appId":10,"fetchedAt":$now,"found":true,"linux":true}]""")
        cacheDir.resolve("hltb.json").writeText(
            """[{"appId":10,"fetchedAt":$now,"hltbId":5,"name":"Old","mainSeconds":7200,"similarity":0.7,"manual":false}]""",
        )
        cacheDir.resolve("tags.json").writeText("""{"19":"Action"}""")
        val repository = newRepository()
        repository.awaitLoaded()

        val item = repository.items.first().single()
        assertEquals(null, item.store!!.language)
        assertTrue(item.hltbNeedsReview)
        assertEquals(null, item.status)
        assertEquals("Action", repository.tagNames.value[19])
    }

    // endregion

    private fun TestScope.newRepository(clock: () -> Long = { now }): LibraryRepository {
        val base = server.baseUrl
        return LibraryRepository(
            steam = SteamApi(http, testJson, base),
            protonDb = ProtonDbApi(http, testJson, base),
            hltb = HltbApi(http, testJson, baseUrl = base),
            cache = CacheStore(cacheDir, testJson, StandardTestDispatcher(testScheduler)),
            settings = settings,
            scope = backgroundScope,
            clock = clock,
            launcher = launcher,
            storeLanguage = { language },
        )
    }

    private suspend fun signIn() = settings.saveAccount(Account("76561197960287930", "KEY", "me", null, null))

    /** Waits, in real time, for something that happens on another thread. */
    private suspend fun awaitReal(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private suspend fun awaitPrefs(repository: LibraryRepository, condition: (SyncPrefs) -> Boolean) =
        awaitReal { repository.observedSyncPrefs.value?.let(condition) == true }

    /**
     * Game 1: everything fresh. Game 2: everything expired. Game 3: nothing cached except a match the user
     * picked by hand long ago.
     */
    private suspend fun seedThreeGames() {
        backend.games = listOf(1 to "Game One", 2 to "Game Two", 3 to "Game Three")
        signIn()
        seedGames(backend.games.map { (id, name) -> OwnedGame(id, name) })
        val day = 24 * 60 * 60 * 1000L
        write("store.json", ListSerializer(StoreInfo.serializer()), listOf(
            StoreInfo(1, fetchedAt = now - day),
            StoreInfo(2, fetchedAt = now - LibraryRepository.STORE_TTL_MS - day),
        ))
        write("protondb.json", ListSerializer(ProtonInfo.serializer()), listOf(
            ProtonInfo(1, fetchedAt = now - day),
            ProtonInfo(2, fetchedAt = now - LibraryRepository.PROTON_TTL_MS - day),
        ))
        write("hltb.json", ListSerializer(HltbInfo.serializer()), listOf(
            HltbInfo(1, fetchedAt = now - day, hltbId = 100, name = "Game One", mainSeconds = 3600, similarity = 1.0),
            HltbInfo(2, fetchedAt = now - LibraryRepository.HLTB_MISS_TTL_MS - day),
            MANUAL_MATCH,
        ))
    }

    private fun seedGames(games: List<OwnedGame>) = write("games.json", ListSerializer(OwnedGame.serializer()), games)

    private fun <T> write(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        cacheDir.mkdirs()
        cacheDir.resolve(name).writeText(testJson.encodeToString(serializer, value))
    }

    private fun readStatuses(file: File): Map<Int, PlayStatus> =
        if (!file.exists()) emptyMap()
        else testJson.decodeFromString(MapSerializer(Int.serializer(), PlayStatus.serializer()), file.readText())

    private class RecordingLauncher : SyncLauncher {
        val starts = CopyOnWriteArrayList<Boolean>()
        override fun start(force: Boolean) {
            starts += force
        }

        override fun stop() = Unit
    }

    /** Steam, ProtonDB and HLTB on one server; their paths don't overlap. */
    private class FakeBackend : Dispatcher() {
        @Volatile var games: List<Pair<Int, String>> = emptyList()
        @Volatile var steamDown = false
        @Volatile var hltbFails = false
        @Volatile var protonGate: CountDownLatch? = null
        val storeRequests = CopyOnWriteArrayList<Int>()
        val protonRequests = CopyOnWriteArrayList<Int>()
        val hltbSearches = CopyOnWriteArrayList<String>()
        val tagLanguages = CopyOnWriteArrayList<String>()

        fun clearRequests() {
            storeRequests.clear()
            protonRequests.clear()
            hltbSearches.clear()
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            val path = url.encodedPath
            return when {
                path == "/IPlayerService/GetOwnedGames/v1/" ->
                    if (steamDown) MockResponse(code = 503)
                    else jsonResponse(
                        games.joinToString(",", """{"response":{"game_count":${games.size},"games":[""", "]}}") { (id, name) ->
                            """{"appid":$id,"name":"$name","playtime_forever":0}"""
                        },
                    )
                path == "/IStoreBrowseService/GetItems/v1/" -> {
                    val input = testJson.parseToJsonElement(url.queryParameter("input_json")!!).jsonObject
                    val ids = input.getValue("ids").jsonArray.map { it.jsonObject.getValue("appid").jsonPrimitive.int }
                    storeRequests += ids
                    jsonResponse(
                        ids.joinToString(",", """{"response":{"store_items":[""", "]}}") { id ->
                            """{"item_type":0,"id":$id,"appid":$id,"success":1,"tagids":[19],"platforms":{"steamos_linux":true}}"""
                        },
                    )
                }
                path == "/IStoreService/GetTagList/v1/" -> {
                    val language = url.queryParameter("language")!!
                    tagLanguages += language
                    val action = if (language == "german") "Aktion" else "Action"
                    jsonResponse("""{"response":{"tags":[{"tagid":19,"name":"$action"}]}}""")
                }
                path.startsWith("/api/v1/reports/summaries/") -> {
                    protonRequests += path.substringAfterLast('/').removeSuffix(".json").toInt()
                    protonGate?.await(5, TimeUnit.SECONDS)
                    jsonResponse("""{"tier":"gold","total":12,"confidence":"good"}""")
                }
                path == "/api/search/site/init" -> jsonResponse("""{"token":"t"}""")
                path == "/api/search/site" -> {
                    val body = testJson.parseToJsonElement(request.body!!.utf8()).jsonObject
                    val query = body.getValue("searchTerms").jsonArray.joinToString(" ") { it.jsonPrimitive.content }
                    hltbSearches += query
                    if (hltbFails) MockResponse(code = 500)
                    else jsonResponse("""{"data":[{"game_id":${query.hashCode() and 0xffff},"game_name":"$query","comp_main":36000}]}""")
                }
                else -> MockResponse(code = 404)
            }
        }
    }

    private companion object {
        val MANUAL_MATCH = HltbInfo(3, fetchedAt = 1_000L, hltbId = 333, name = "Picked by hand", mainSeconds = 7200, similarity = 1.0, manual = true)
    }
}
