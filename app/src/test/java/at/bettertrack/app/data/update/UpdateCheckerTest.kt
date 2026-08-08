package at.bettertrack.app.data.update

import at.bettertrack.app.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The update notifier as the user meets it: app-opens, not function calls.
 *
 * `UpdateCheckLogicTest` pins the rules; this pins the ORCHESTRATION that calls
 * them, because that is where v0.107's "the popup never showed up again" lived.
 * Every rule was correct and every one of them was being asked at most once per
 * process — Android keeps that process alive for days.
 *
 * The spec these tests hold the checker to (owner, 2026-08-08): **if a newer
 * release exists, opening the app shows the popup — every time, cold or warm —
 * unless the user snoozed it (24h) or ignored that exact version.**
 */
class UpdateCheckerTest {

    /** The build in the owner's report; 108 is the release it failed to offer. */
    private val current = 107
    private val t0 = 1_800_000_000_000L
    private val minute = 60L * 1000
    private val hour = 60 * minute

    private var clock = t0
    private lateinit var server: MockWebServer
    private lateinit var store: FakeUpdateStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        store = FakeUpdateStore()
        clock = t0
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        // Play builds compile self-update off; the checker is a no-op there and
        // every assertion below would be about a feature that does not exist.
        // Assumed LAST so the fixtures above exist for @After — a skip raised
        // before them turns into "could not be skipped due to other failures".
        assumeTrue(BuildConfig.SELF_UPDATE_ENABLED)
    }

    @After
    fun tearDown() {
        scope.coroutineContext.job.cancel()
        server.shutdown()
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private fun checker(): UpdateChecker = UpdateChecker(
        prefs = store,
        currentVersionCode = current,
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        json = Json { ignoreUnknownKeys = true },
        nowMs = { clock },
        scope = scope,
        versionJsonUrl = server.url("/version.json").toString(),
    )

    /**
     * Wait for every check the checker launched. The scope is injected, so its
     * children ARE the in-flight fetches — no sleeps, no polling, and "nothing
     * was launched" costs nothing.
     */
    private fun settle() = runBlocking {
        scope.coroutineContext.job.children.toList().forEach { it.join() }
    }

    private fun serveManifest(code: Int, name: String, apk: String? = null) {
        val apkField = apk?.let { ""","apk":"$it"""" } ?: ""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"versionCode":$code,"versionName":"$name"$apkField}"""),
        )
    }

    /** GitHub answered, but not with a manifest — the silent-skip path. */
    private fun serveFailure() = server.enqueue(MockResponse().setResponseCode(500))

    private fun seedCachedRelease(code: Int = 108, name: String = "0.108", apk: String? = "bt-0.108.apk") {
        store.cachedLatestCode = code
        store.cachedLatestName = name
        store.cachedLatestApk = apk
        store.lastCheckMs = t0 - hour
    }

    // ── The bug ─────────────────────────────────────────────────────────────

    @Test
    fun `reopening the app shows the popup again, from cache, with no successful fetch`() {
        // A previous process already learned 0.108 exists. This process starts
        // cold and the network is useless — the offer is owed anyway.
        seedCachedRelease()
        val checker = checker()
        serveFailure()
        checker.onForeground()
        settle()
        assertEquals(108, checker.pendingDialog.value?.versionCode)
        assertEquals("bt-0.108.apk", checker.pendingDialog.value?.apkName)

        // The user taps "Go to GitHub"; the prompt goes down.
        checker.dismissDialog()
        assertNull(checker.pendingDialog.value)

        // …and reopens the app later. THIS is what v0.107 never did: same
        // process, no new information, no fetch that works — popup returns.
        clock = t0 + 20 * minute
        serveFailure()
        checker.onForeground()
        settle()
        assertEquals(108, checker.pendingDialog.value?.versionCode)
        // Nothing was snoozed along the way: About must not claim a paused reminder.
        assertEquals(0L, store.remindAfterMs)
    }

    @Test
    fun `a cold start with no cache offers what the fetch finds`() {
        val checker = checker()
        serveManifest(108, "0.108", apk = "bt-0.108.apk")
        checker.onForeground()
        settle()

        assertEquals(108, checker.pendingDialog.value?.versionCode)
        assertEquals("0.108", checker.available.value?.versionName)
        // …and remembers it, so the next app-open needs no network at all.
        assertEquals(108, store.cachedLatestCode)
        assertEquals("0.108", store.cachedLatestName)
        assertEquals("bt-0.108.apk", store.cachedLatestApk)
    }

    // ── Politeness: the debounce ────────────────────────────────────────────

    @Test
    fun `foreground checks are debounced to one every fifteen minutes`() {
        val checker = checker()
        serveManifest(108, "0.108")
        checker.onForeground() // cold — always checks
        settle()
        assertEquals(1, server.requestCount)

        // App-switching bursts must not become request bursts.
        clock = t0 + 14 * minute
        checker.onForeground()
        settle()
        assertEquals(1, server.requestCount)

        clock = t0 + 15 * minute
        serveManifest(108, "0.108")
        checker.onForeground()
        settle()
        assertEquals(2, server.requestCount)
        // The prompt was never the thing being rate-limited: it stood the whole time.
        assertEquals(108, checker.pendingDialog.value?.versionCode)
    }

    @Test
    fun `a failed check does not license a retry on every single foreground`() {
        // lastCheckMs only advances on success, so without an in-memory attempt
        // stamp an offline user would fire a doomed request per app-switch.
        val checker = checker()
        serveFailure()
        checker.onForeground()
        settle()
        assertEquals(1, server.requestCount)

        clock = t0 + 2 * minute
        checker.onForeground()
        settle()
        assertEquals(1, server.requestCount)
    }

    // ── Snooze / ignore: unchanged semantics ────────────────────────────────

    @Test
    fun `remind me later stays quiet for 24h and then returns in the SAME process`() {
        val checker = checker()
        serveManifest(108, "0.108")
        checker.onForeground()
        settle()
        assertNotNull(checker.pendingDialog.value)

        checker.remindLater()
        assertNull(checker.pendingDialog.value)
        assertEquals(t0 + UpdateChecker.REMIND_SNOOZE_MS, store.remindAfterMs)
        assertEquals(t0 + UpdateChecker.REMIND_SNOOZE_MS, checker.snoozedUntilMs())

        // Inside the snooze: reopening (and re-checking) says nothing.
        clock = t0 + hour
        serveManifest(108, "0.108")
        checker.onForeground()
        settle()
        assertNull(checker.pendingDialog.value)

        // Past it: the offer comes back WITHOUT the process having died. The
        // old process-lifetime flag outlived the snooze by however long Android
        // kept the app resident — days, in the owner's case.
        clock = t0 + UpdateChecker.REMIND_SNOOZE_MS + 1
        serveFailure()
        checker.onForeground()
        settle()
        assertEquals(108, checker.pendingDialog.value?.versionCode)
        assertNull(checker.snoozedUntilMs())
    }

    @Test
    fun `ignore is forever for that exact version and only that version`() {
        val checker = checker()
        serveManifest(108, "0.108")
        checker.onForeground()
        settle()
        checker.ignorePending()
        assertEquals(108, store.ignoredVersionCode)
        assertNull(checker.pendingDialog.value)

        // Reopen, cache present, fetch dead: still nothing. Forever means forever.
        clock = t0 + hour
        serveFailure()
        checker.onForeground()
        settle()
        assertNull(checker.pendingDialog.value)
        // The badge is not silenced by an ignore — it is how the user gets back.
        assertEquals(108, checker.available.value?.versionCode)

        // A newer build is a new question.
        clock = t0 + 2 * hour
        serveManifest(109, "0.109")
        checker.onForeground()
        settle()
        assertEquals(109, checker.pendingDialog.value?.versionCode)
    }

    @Test
    fun `going to GitHub buys a quiet window, not a snooze`() {
        val checker = checker()
        serveManifest(108, "0.108")
        checker.onForeground()
        settle()
        checker.dismissDialog()

        // Coming straight back from the browser must not re-prompt…
        clock = t0 + minute
        checker.onForeground()
        settle()
        assertNull(checker.pendingDialog.value)
        // …and it is not recorded as a choice: no persisted deadline, no
        // "reminder paused until…" line in About.
        assertEquals(0L, store.remindAfterMs)
        assertNull(checker.snoozedUntilMs())

        // Once the trip is over, the offer is owed again.
        clock = t0 + UpdateChecker.HANDOFF_QUIET_MS + 1
        serveFailure()
        checker.onForeground()
        settle()
        assertEquals(108, checker.pendingDialog.value?.versionCode)
    }

    // ── The opt-out and the manual button ───────────────────────────────────

    @Test
    fun `automatic checks off means no request and no cached offer`() {
        seedCachedRelease()
        store.setAutoCheckEnabled(false)
        val checker = checker()
        checker.onForeground()
        settle()

        assertEquals(0, server.requestCount)
        assertNull(checker.pendingDialog.value)
        // The badge still stands: the opt-out silences the app, not the fact.
        assertEquals(108, checker.available.value?.versionCode)
    }

    @Test
    fun `a manual check re-offers an ignored version without clearing the ignore`() {
        seedCachedRelease()
        store.ignoredVersionCode = 108
        val checker = checker()
        serveFailure()
        checker.onForeground()
        settle()
        assertNull(checker.pendingDialog.value)

        serveManifest(108, "0.108")
        checker.checkNow()
        settle()
        assertEquals(108, checker.pendingDialog.value?.versionCode)
        assertEquals(108, store.ignoredVersionCode)
        // A newer build answers with the dialog, so the inline row stands down.
        assertTrue(checker.manualCheck.value is ManualUpdateCheck.Idle)
    }

    @Test
    fun `an automatic failure is silent and a manual one is reported`() {
        val checker = checker()
        serveFailure()
        checker.onForeground()
        settle()
        assertTrue(checker.manualCheck.value is ManualUpdateCheck.Idle)
        assertNull(checker.pendingDialog.value)

        serveFailure()
        checker.checkNow()
        settle()
        assertTrue(checker.manualCheck.value is ManualUpdateCheck.Failed)
    }

    // ── Cache hygiene (the cache now drives an OFFER, not just a badge) ──────

    @Test
    fun `an up-to-date answer clears the cache so nothing stale is re-offered`() {
        seedCachedRelease()
        val checker = checker()
        assertEquals(108, checker.available.value?.versionCode) // seeded for offline

        // The release was yanked (or we just installed it): GitHub says 107.
        serveManifest(107, "0.107")
        checker.onForeground()
        settle()
        assertNull(checker.available.value)
        assertNull(checker.pendingDialog.value)
        assertEquals(0, store.cachedLatestCode)
        assertNull(store.cachedLatestName)
        assertNull(store.cachedLatestApk)

        // Next app-open, offline: nothing to offer, and nothing invented.
        clock = t0 + hour
        serveFailure()
        checker.onForeground()
        settle()
        assertNull(checker.pendingDialog.value)
    }

    @Test
    fun `turning automatic checks back on answers from cache before the network does`() {
        seedCachedRelease()
        store.setAutoCheckEnabled(false)
        val checker = checker()
        checker.onForeground()
        settle()
        assertNull(checker.pendingDialog.value)

        serveFailure()
        checker.setAutoCheckEnabled(true)
        settle()
        assertEquals(108, checker.pendingDialog.value?.versionCode)
    }
}

/**
 * In-memory [UpdateStore]. Plain vars: the production one is SharedPreferences
 * and the checker is the only thing that reads or writes it, so a map would add
 * indirection without adding fidelity.
 */
private class FakeUpdateStore : UpdateStore {
    override var remindAfterMs: Long = 0L
    override var lastCheckMs: Long = 0L
    override var ignoredVersionCode: Int = 0
    override var cachedLatestCode: Int = 0
    override var cachedLatestName: String? = null
    override var cachedLatestApk: String? = null

    private val _autoCheckEnabled = MutableStateFlow(true)
    override val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()
    override fun autoCheckEnabledNow(): Boolean = _autoCheckEnabled.value
    override fun setAutoCheckEnabled(enabled: Boolean) { _autoCheckEnabled.value = enabled }
}
