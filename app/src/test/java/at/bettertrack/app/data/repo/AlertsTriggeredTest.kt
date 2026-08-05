package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.storage.StorageMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The triggered-alerts count that feeds the Workbench tab dot and Home's
 * "Needs you" row (R-arc R1).
 *
 * Two things are under test, and only the first is arithmetic:
 *
 *  1. **What counts as triggered.** Exactly `status == "triggered"` — not fired
 *     history, not disabled alerts that fired last week. The number answers "is
 *     something waiting for me?".
 *  2. **That a Drive-only install never asks.** This is the one that would go
 *     unnoticed in production: `ALERTS_NOTIFICATIONS` is ABSENT in Drive mode
 *     (§4.5), there is no server to ask, and a shell that primed the count on
 *     every launch regardless would spend a guaranteed-failing request each
 *     time. The gate lives in the repository rather than at the two call sites
 *     precisely so it can be proved here, once, by counting requests.
 */
class AlertsTriggeredTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: AlertsRepository

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = AlertsRepository(api, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun alert(id: String, status: String) = """
        {"id":"$id","kind":"price_above","threshold":100.0,"refPrice":null,
         "repeat":false,"status":"$status","lastTriggeredAt":null,
         "asset":{"id":"a-$id","symbol":"SYM","name":"Name","currency":"EUR","type":"stock"}}
    """.trimIndent()

    private fun enqueueAlerts(vararg statuses: String) {
        val items = statuses.mapIndexed { i, s -> alert("al-$i", s) }.joinToString(",")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[$items]}"""),
        )
    }

    // ── The counting rule ───────────────────────────────────────────────────

    @Test
    fun `only alerts whose status is triggered are counted`() = runBlocking {
        enqueueAlerts("triggered", "active", "triggered", "disabled", "active")
        repo.refreshTriggered(StorageMode.SERVER)
        assertEquals(2, repo.triggered.value)
    }

    @Test
    fun `a quiet account counts zero rather than staying unset`() = runBlocking {
        enqueueAlerts("active", "active", "disabled")
        repo.refreshTriggered(StorageMode.SERVER)
        assertEquals(0, repo.triggered.value)
    }

    @Test
    fun `the count starts at zero so the dot is dark before the first fetch`() {
        assertEquals(0, repo.triggered.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the pure counting rule ignores everything but the status`() {
        // Stated directly on the free function, because three surfaces call it
        // and only one of them goes through the repository.
        val statuses = listOf(
            AlertStatus.Triggered,
            AlertStatus.Active,
            AlertStatus.Disabled,
            AlertStatus.Triggered,
        )
        val alerts = statuses.mapIndexed { i, s ->
            PriceAlert(
                id = "$i",
                kind = AlertKind.PriceAbove,
                threshold = 1.0,
                refPrice = 0.0,
                repeat = false,
                status = s,
                // A disabled alert that DID fire once still does not count: it is
                // history, not something waiting for a decision.
                lastTriggeredAt = "2026-08-01T10:00:00Z",
                asset = AlertAsset("a", "SYM", "Name", "EUR", "stock"),
            )
        }
        assertEquals(2, countTriggered(alerts))
        assertEquals(0, countTriggered(emptyList()))
    }

    // ── The mode gate ───────────────────────────────────────────────────────

    @Test
    fun `drive-only never asks the server for alerts`() = runBlocking {
        repo.refreshTriggered(StorageMode.DRIVE)
        assertEquals("a Drive install has no alert engine to ask", 0, server.requestCount)
        assertEquals(0, repo.triggered.value)
    }

    @Test
    fun `server, both and unset all fetch`() = runBlocking {
        for (mode in listOf(StorageMode.SERVER, StorageMode.BOTH, StorageMode.UNSET)) {
            enqueueAlerts("triggered")
            repo.refreshTriggered(mode)
            assertEquals(mode.name, 1, repo.triggered.value)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `switching to drive-only clears a count left over from a server session`() = runBlocking {
        enqueueAlerts("triggered", "triggered")
        repo.refreshTriggered(StorageMode.SERVER)
        assertEquals(2, repo.triggered.value)

        // A stale "2 alerts" dot on an install that no longer has alerts at all
        // would be unexplainable from inside the app.
        repo.refreshTriggered(StorageMode.DRIVE)
        assertEquals(0, repo.triggered.value)
        assertEquals(1, server.requestCount)
    }

    // ── Failure ─────────────────────────────────────────────────────────────

    @Test
    fun `a failed refresh keeps the last known count instead of zeroing it`() = runBlocking {
        enqueueAlerts("triggered", "triggered", "triggered")
        repo.refreshTriggered(StorageMode.SERVER)
        assertEquals(3, repo.triggered.value)

        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        repo.refreshTriggered(StorageMode.SERVER)
        // "We could not reach the server" is not "your alerts stopped firing".
        assertEquals(3, repo.triggered.value)
    }
}
