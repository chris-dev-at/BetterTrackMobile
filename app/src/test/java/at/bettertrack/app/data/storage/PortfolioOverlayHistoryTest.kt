package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.SearchOutcome
import at.bettertrack.app.ui.insights.BtInsightMoveRange
import at.bettertrack.app.ui.insights.insightMovePercentIn
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests for the per-asset overlay —
 * `GET /portfolios/{id}/history?range=…&overlay=true`, the batch that replaced
 * the movers card's one-request-per-position fan-out.
 *
 * Contract as measured on the deployed `https://api.bettertrack.at/openapi.json`
 * (2026-08-20) and cross-read against the platform's
 * `packages/contracts/src/portfolio.ts`:
 *
 * ```
 * PortfolioHistoryResponse.assets[]:
 *   assetId  uuid      required
 *   symbol   string    required
 *   name     string    required
 *   currency ^[A-Z]{3} required   ← the closes' NATIVE currency, never converted
 *   points[] { date: ^\d{4}-\d{2}-\d{2}$, close: number }   required
 * range query enum: 1D | 1W | 1M | 6M | 1Y | 5Y | MAX   ← 1W IS served
 * overlay query enum: "true" | "false"                  ← a string, not a bool
 * ```
 *
 * The five things that are easy to get wrong quietly, each with a test that
 * fails loudly:
 *
 *  1. **One request, whatever the position count.** The whole point.
 *  2. **`overlay` travels as the literal string `true`**, and the plain history
 *     call must keep sending no `overlay` at all — the ETag store is keyed by URL.
 *  3. **A failed batch is an error, not eleven retries.** No hidden fan-out.
 *  4. **A 200 without `assets` is a capability gap**, not an empty portfolio.
 *  5. **Both extraction paths must reduce the same closes to the same percent** —
 *     the number on the card may not depend on which shape delivered it.
 */
class PortfolioOverlayHistoryTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ApiMarketDataSource

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
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        source = ApiMarketDataSource(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── 1 · one call ────────────────────────────────────────────────────────

    @Test
    fun `eleven positions cost one request`() = runBlocking {
        server.enqueue(ok(overlayBody(3)))

        val ids = (1..11).map { "asset-$it" }
        val r = source.assetHistories(portfolio, ids, HistoryRange.M1)

        assertTrue("was $r", r is BtResult.Ok)
        assertEquals("one call for eleven assets", 1, server.requestCount)
    }

    @Test
    fun `the overlay flag is the literal string the server enumerates`() = runBlocking {
        server.enqueue(ok(overlayBody(2)))

        source.assetHistories(portfolio, listOf("a"), HistoryRange.W1)

        val path = server.takeRequest().path!!
        assertTrue("path was $path", path.startsWith("/portfolios/$portfolio/history?"))
        val query = path.substringAfter('?').split('&').toSet()
        assertEquals(setOf("range=1W", "overlay=true"), query)
    }

    @Test
    fun `the plain history call still sends no overlay parameter`() = runBlocking {
        // The §6.1 graph's request must stay byte-identical: ConditionalGetInterceptor
        // keys its ETag store by full URL, so a new parameter would silently
        // invalidate every cached history body.
        server.enqueue(
            ok("""{"range":"1M","interval":"144m","baseCurrency":"EUR","points":[],"performance":[]}"""),
        )
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)

        api.portfolioHistory(portfolio, "1M")

        assertEquals("/portfolios/$portfolio/history?range=1M", server.takeRequest().path)
    }

    @Test
    fun `every span this card fetches is one the portfolio endpoint serves`() {
        // The deployed openapi.json enum, re-read 2026-08-20. 1W is in it — the
        // stale note claiming portfolio history is 1M|6M|1Y|MAX only is wrong,
        // and if it were right the week span would need a different source.
        val served = setOf("1D", "1W", "1M", "6M", "1Y", "5Y", "MAX")
        BtInsightMoveRange.entries.mapNotNull { it.historyRange }.forEach { range ->
            assertTrue("${range.wire} is not served by portfolio history", range.wire in served)
        }
    }

    // ── 2 · what the response means ─────────────────────────────────────────

    @Test
    fun `each row becomes one ascending series keyed by asset id`() = runBlocking {
        server.enqueue(
            ok(
                """
                {"range":"1M","interval":"1d","baseCurrency":"EUR","points":[],"performance":[],
                 "assets":[
                   {"assetId":"aaa","symbol":"KO","name":"Coca-Cola","currency":"USD",
                    "points":[{"date":"2026-08-03","close":70.0},{"date":"2026-08-01","close":60.0}]},
                   {"assetId":"bbb","symbol":"SIE.DE","name":"Siemens","currency":"EUR",
                    "points":[{"date":"2026-08-01","close":200.0}]}
                 ]}
                """.trimIndent(),
            ),
        )

        val r = source.assetHistories(portfolio, listOf("aaa", "bbb"), HistoryRange.M1) as BtResult.Ok

        assertEquals(setOf("aaa", "bbb"), r.value.keys)
        val ko = r.value.getValue("aaa")
        assertEquals("the server's echoed window", AssetRange.M1, ko.range)
        // Sorted ascending by time even though the wire order was not.
        assertEquals(listOf(60.0, 70.0), ko.points.map { it.close })
        assertTrue(ko.points[0].timeMs < ko.points[1].timeMs)
    }

    @Test
    fun `a day label round-trips to the same day the device reads back`() {
        // The window clamp compares CALENDAR days, so the key has to resolve to
        // the day the server wrote — in every zone, not just the ones east of
        // Greenwich where midnight-UTC happens to survive.
        listOf("Europe/Vienna", "America/Los_Angeles", "Pacific/Kiritimati").forEach { id ->
            val zone = ZoneId.of(id)
            val ms = ApiMarketDataSource.overlayDayMillis("2026-08-13", zone)
            assertNotNull("no key for $id", ms)
            assertEquals(
                "day drifted in $id",
                LocalDate.of(2026, 8, 13),
                java.time.Instant.ofEpochMilli(ms!!).atZone(zone).toLocalDate(),
            )
        }
    }

    @Test
    fun `rows the caller never named are kept, because the response is per portfolio`() = runBlocking {
        server.enqueue(ok(overlayBody(2)))

        // Only "asset-1" was cold; the server answers with the whole portfolio.
        val r = source.assetHistories(portfolio, listOf("asset-1"), HistoryRange.Y1) as BtResult.Ok

        assertEquals(setOf("asset-1", "asset-2"), r.value.keys)
    }

    @Test
    fun `an empty id list never touches the network`() = runBlocking {
        val r = source.assetHistories(portfolio, listOf("", " "), HistoryRange.M1) as BtResult.Ok

        assertTrue(r.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    // ── 3 · failure honesty ─────────────────────────────────────────────────

    @Test
    fun `a failed batch is an error and is never retried per asset`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"SERVER_ERROR"}}"""))

        val r = source.assetHistories(portfolio, (1..11).map { "asset-$it" }, HistoryRange.M1)

        assertTrue("was $r", r is BtResult.Err)
        assertEquals("a broken batch must not become eleven requests", 1, server.requestCount)
    }

    @Test
    fun `a 200 without assets is a capability gap, not an empty portfolio`() = runBlocking {
        server.enqueue(ok("""{"range":"1M","interval":"1d","baseCurrency":"EUR","points":[],"performance":[]}"""))

        val r = source.assetHistories(portfolio, listOf("a"), HistoryRange.M1)

        assertTrue("was $r", r is BtResult.Err)
        assertEquals(ApiMarketDataSource.CODE_NO_OVERLAY, (r as BtResult.Err).error.code)
    }

    @Test
    fun `an empty assets array is an answer, not an error`() = runBlocking {
        // A portfolio whose holdings the price service covers for nothing at all.
        server.enqueue(
            ok("""{"range":"1M","interval":"1d","baseCurrency":"EUR","points":[],"performance":[],"assets":[]}"""),
        )

        val r = source.assetHistories(portfolio, listOf("a"), HistoryRange.M1) as BtResult.Ok

        assertTrue(r.value.isEmpty())
    }

    // ── 4 · the fan-out fallback the interface keeps ────────────────────────

    @Test
    fun `a source without the batch answers per asset and says so`() = runBlocking {
        val fake = FanOutSource()

        assertFalse("a fan-out source must not claim to batch", fake.batchesAssetHistories)
        val r = fake.assetHistories(portfolio, listOf("aaa", "bad", "bbb"), HistoryRange.Y1) as BtResult.Ok

        assertEquals("one call per asset", 3, fake.calls)
        assertEquals("the same window in the asset vocabulary", listOf(AssetRange.Y1), fake.ranges.distinct())
        // The failed id is ABSENT, not present-and-empty: unknown, never flat.
        assertEquals(setOf("aaa", "bbb"), r.value.keys)
    }

    @Test
    fun `the api source declares its batch`() {
        assertTrue(source.batchesAssetHistories)
    }

    // ── 5 · the number may not depend on the shape ──────────────────────────

    @Test
    fun `both extraction paths reduce the same closes to the same percent`() = runBlocking {
        // One set of closes, delivered twice: once as the per-asset endpoint's
        // `points[{time, close}]`, once as an overlay row's `points[{date, close}]`.
        // The card's percentage must not be able to tell which arrived.
        val closes = listOf(
            "2026-07-20" to 100.0,
            "2026-07-27" to 108.5,
            "2026-08-05" to 96.25,
            "2026-08-20" to 134.14,
        )

        server.enqueue(
            ok(
                """
                {"range":"1M","interval":"1d","currency":"USD","stale":false,"asOf":"2026-08-20T12:00:00.000Z",
                 "points":[${closes.joinToString(",") { (d, c) -> """{"time":"${d}T12:00:00.000Z","close":$c}""" }}]}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            ok(
                """
                {"range":"1M","interval":"1d","baseCurrency":"EUR","points":[],"performance":[],
                 "assets":[{"assetId":"aaa","symbol":"ONDS","name":"Ondas","currency":"USD",
                   "points":[${closes.joinToString(",") { (d, c) -> """{"date":"$d","close":$c}""" }}]}]}
                """.trimIndent(),
            ),
        )

        val perAsset = (source.assetHistory("aaa", AssetRange.M1) as BtResult.Ok).value
        val batched = (source.assetHistories(portfolio, listOf("aaa"), HistoryRange.M1) as BtResult.Ok)
            .value.getValue("aaa")

        val asOf = LocalDate.of(2026, 8, 20).toEpochDay()
        val fromFanOut = insightMovePercentIn(perAsset.points, BtInsightMoveRange.MONTH, asOf)
        val fromBatch = insightMovePercentIn(batched.points, BtInsightMoveRange.MONTH, asOf)

        assertNotNull(fromFanOut)
        assertEquals(34.14, fromFanOut!!, 0.0001)
        assertEquals("the shape must not move the number", fromFanOut, fromBatch!!, 1e-9)
    }

    @Test
    fun `a one-point overlay row yields no move rather than a zero`() = runBlocking {
        server.enqueue(
            ok(
                """
                {"range":"1W","interval":"1d","baseCurrency":"EUR","points":[],"performance":[],
                 "assets":[{"assetId":"aaa","symbol":"X","name":"X","currency":"EUR",
                   "points":[{"date":"2026-08-20","close":10.0}]}]}
                """.trimIndent(),
            ),
        )

        val series = (source.assetHistories(portfolio, listOf("aaa"), HistoryRange.W1) as BtResult.Ok)
            .value.getValue("aaa")

        assertEquals(
            null,
            insightMovePercentIn(series.points, BtInsightMoveRange.WEEK, LocalDate.of(2026, 8, 20).toEpochDay()),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun overlayBody(assets: Int): String {
        val rows = (1..assets).joinToString(",") { i ->
            """
            {"assetId":"asset-$i","symbol":"S$i","name":"Name $i","currency":"EUR",
             "points":[{"date":"2026-08-01","close":${100 + i}.0},{"date":"2026-08-20","close":${110 + i}.0}]}
            """.trimIndent()
        }
        return """{"range":"1M","interval":"1d","baseCurrency":"EUR","points":[],"performance":[],"assets":[$rows]}"""
    }

    /** A source with no batch of its own — it inherits the interface default. */
    private class FanOutSource : MarketDataSource {
        var calls = 0
        val ranges = mutableListOf<AssetRange>()

        override suspend fun search(query: String): BtResult<SearchOutcome> = error("unused")
        override suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> = error("unused")
        override suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> = error("unused")
        override suspend fun quote(assetId: String): BtResult<AssetSnapshot> = error("unused")

        override suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries> {
            calls++
            ranges += range
            if (assetId == "bad") return BtResult.Err(BtApiError(httpStatus = 0, code = "OFFLINE"))
            return BtResult.Ok(
                AssetPriceSeries(range, listOf(PricePoint(1_000L, 10.0), PricePoint(2_000L, 11.0))),
            )
        }
    }
}
