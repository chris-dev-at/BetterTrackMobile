package at.bettertrack.app.data.api.dto

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.db.PortfolioHistoryEntity
import at.bettertrack.app.data.repo.parsePortfolioHistory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * `moneyWeightedPct` on `GET /portfolios/{id}/history` (#1669, board 2026-09-15)
 * is TRI-STATE and the rendering ruling depends on it: a number renders, an
 * explicit `null` renders a dash ("no capital"), an ABSENT key renders nothing
 * ("not computed" — an older server, or a row cached before v15).
 *
 * A plain `Double? = null` would collapse the last two, so each state is pinned
 * here through the REAL decode path (Retrofit + the app's `Json` config), then
 * through the Room cache column and back out of [parsePortfolioHistory] — the
 * tri-state has to survive a cold start, not just one response.
 */
class MoneyWeightedPctWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi

    /** The app's own configuration (`AppGraph.json`). */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val portfolio = "019f2362-edf9-7b3e-bda1-e625375af831"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun body(moneyWeighted: String?): String {
        val field = moneyWeighted?.let { ""","moneyWeightedPct":$it""" }.orEmpty()
        return """{"range":"MAX","interval":"1d","baseCurrency":"EUR",""" +
            """"points":[{"date":"2026-01-01","valueEur":100.0},{"date":"2026-09-01","valueEur":5000.0}],""" +
            """"performance":[{"date":"2026-01-01","pct":0.0},{"date":"2026-09-01","pct":-32.73}]$field}"""
    }

    private fun fetch(moneyWeighted: String?): PortfolioHistoryResponse = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(moneyWeighted)))
        api.portfolioHistory(portfolio, "MAX").body()!!
    }

    // ── The wire, three states ──────────────────────────────────────────────

    @Test
    fun `a number decodes to the server's value verbatim`() {
        assertEquals(MoneyWeightedPct.Value(9.5), fetch("9.5").moneyWeightedPct)
        assertEquals(MoneyWeightedPct.Value(-12.3456), fetch("-12.3456").moneyWeightedPct)
        assertEquals(MoneyWeightedPct.Value(0.0), fetch("0").moneyWeightedPct)
    }

    @Test
    fun `an explicit null decodes to no-capital, not to absent`() {
        assertEquals(MoneyWeightedPct.NoCapital, fetch("null").moneyWeightedPct)
    }

    @Test
    fun `an absent key decodes to not-computed, not to no-capital`() {
        assertEquals(MoneyWeightedPct.NotComputed, fetch(null).moneyWeightedPct)
    }

    @Test
    fun `the rest of the response is untouched by the new field`() {
        val r = fetch("9.5")
        assertEquals(-32.73, r.performance.last().pct, 0.0)
        assertEquals(2, r.points.size)
    }

    @Test
    fun `a value this build cannot read degrades to not-computed instead of failing the chart`() {
        // One malformed optional figure must not cost the whole history decode.
        assertEquals(MoneyWeightedPct.NotComputed, fetch("\"9.5\"").moneyWeightedPct)
        assertEquals(MoneyWeightedPct.NotComputed, fetch("{}").moneyWeightedPct)
        assertEquals(2, fetch("[]").points.size)
    }

    // ── The Room column, three states ───────────────────────────────────────

    @Test
    fun `the cache column spells each state distinctly`() {
        assertNull(MoneyWeightedPct.NotComputed.toCacheJson())
        assertEquals("null", MoneyWeightedPct.NoCapital.toCacheJson())
        assertEquals("9.5", MoneyWeightedPct.Value(9.5).toCacheJson())
    }

    @Test
    fun `every state round-trips through the cache column`() {
        listOf(
            MoneyWeightedPct.NotComputed,
            MoneyWeightedPct.NoCapital,
            MoneyWeightedPct.Value(9.5),
            MoneyWeightedPct.Value(-0.01),
            MoneyWeightedPct.Value(1234.5678),
        ).forEach { state ->
            assertEquals(state, moneyWeightedFromCacheJson(state.toCacheJson()))
        }
    }

    @Test
    fun `an unreadable cache cell reads as not-computed`() {
        assertEquals(MoneyWeightedPct.NotComputed, moneyWeightedFromCacheJson("{oops"))
        assertEquals(MoneyWeightedPct.NotComputed, moneyWeightedFromCacheJson("\"9.5\""))
    }

    @Test
    fun `wire to cache to parsed history keeps all three states`() {
        mapOf(
            "9.5" to MoneyWeightedPct.Value(9.5),
            "null" to MoneyWeightedPct.NoCapital,
            null to MoneyWeightedPct.NotComputed,
        ).forEach { (wire, expected) ->
            val response = fetch(wire)
            val entity = PortfolioHistoryEntity(
                portfolioId = portfolio,
                range = response.range,
                baseCurrency = response.baseCurrency,
                pointsJson = "[]",
                performanceJson = """[{"date":"2026-09-01","pct":-32.73}]""",
                syncedAtMs = 1L,
                moneyWeightedPctJson = response.moneyWeightedPct.toCacheJson(),
            )
            val parsed = parsePortfolioHistory(entity, json)!!
            assertEquals("wire `$wire`", expected, parsed.moneyWeighted)
        }
    }

    @Test
    fun `a row cached before v15 has no column value and reads as not-computed`() {
        val legacy = PortfolioHistoryEntity(
            portfolioId = portfolio,
            range = "MAX",
            baseCurrency = "EUR",
            pointsJson = "[]",
            performanceJson = "[]",
            syncedAtMs = 1L,
        )
        assertEquals(MoneyWeightedPct.NotComputed, parsePortfolioHistory(legacy, json)!!.moneyWeighted)
    }

    // ── Encoding never invents a state ──────────────────────────────────────

    @Test
    fun `not-computed is never encoded, no-capital encodes as an explicit null`() {
        val absent = json.encodeToString(
            PortfolioHistoryResponse.serializer(),
            PortfolioHistoryResponse(range = "MAX", baseCurrency = "EUR"),
        )
        assertFalse("absent must stay absent: $absent", "moneyWeightedPct" in absent)

        val noCapital = json.encodeToString(
            PortfolioHistoryResponse.serializer(),
            PortfolioHistoryResponse(range = "MAX", baseCurrency = "EUR", moneyWeightedPct = MoneyWeightedPct.NoCapital),
        )
        assertTrue(noCapital, "\"moneyWeightedPct\":null" in noCapital)
        assertEquals(
            MoneyWeightedPct.NoCapital,
            json.decodeFromString(PortfolioHistoryResponse.serializer(), noCapital).moneyWeightedPct,
        )
    }
}
