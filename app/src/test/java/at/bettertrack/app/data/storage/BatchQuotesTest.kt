package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.SearchOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests for `GET /assets/quotes` — the batch read that replaced the
 * watchlist's one-request-per-row fan-out.
 *
 * Four things about this endpoint are easy to get wrong and expensive to get
 * wrong quietly, so each has a test that fails loudly:
 *
 *  1. **`ids` is one comma-separated parameter.** Retrofit would happily encode a
 *     list as repeated `ids=` params, which this server rejects.
 *  2. **The query is `.strict()`.** Nothing may be appended to it.
 *  3. **The response has no euro conversion.** A EUR-denominated quote is its own
 *     euro price; a dollar quote must come back with `eurPrice = null` rather
 *     than the dollar figure wearing a euro sign.
 *  4. **`failed[]` is a per-row outcome**, and an id the server mentions nowhere
 *     is just as unanswered as one it names.
 */
class BatchQuotesTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ApiMarketDataSource

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
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        source = ApiMarketDataSource(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `ids travel as one comma-separated parameter and nothing else is appended`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"quotes":[],"failed":[]}"""))

        source.quotes(listOf("aaa", "bbb", "ccc"))

        val path = server.takeRequest().path!!
        assertTrue("path was $path", path.startsWith("/assets/quotes?"))
        val query = path.substringAfter('?')
        // One parameter, one name, three values inside it.
        assertEquals("query was $query", "ids=aaa%2Cbbb%2Cccc", query)
    }

    @Test
    fun `a euro quote keeps its price and a dollar quote reports no euro price`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"quotes":[
                  {"assetId":"eur-1","quote":{"price":92.5,"currency":"EUR","dayChangePct":1.25},"stale":false},
                  {"assetId":"usd-1","quote":{"price":210.0,"currency":"USD","dayChangePct":-0.5},"stale":true}
                ],"failed":[]}
                """.trimIndent(),
            ),
        )

        val r = source.quotes(listOf("eur-1", "usd-1")) as BtResult.Ok
        val eur = r.value.quotes.getValue("eur-1")
        val usd = r.value.quotes.getValue("usd-1")

        // Identity read, not a conversion: the quote is already in euros.
        assertEquals(92.5, eur.eurPrice!!, 0.0001)
        assertEquals(1.25, eur.dayChangePct!!, 0.0001)

        // The batch cannot convert, so it must not pretend to have.
        assertNull("a USD quote has no euro price in this response", usd.eurPrice)
        assertEquals(210.0, usd.nativePrice!!, 0.0001)
        assertEquals("USD", usd.quoteCurrency)
        // The day percent is currency-independent and survives.
        assertEquals(-0.5, usd.dayChangePct!!, 0.0001)
        assertTrue("stale is carried per row", usd.stale)
    }

    @Test
    fun `lowercase eur still counts as euros`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"quotes":[{"assetId":"a","quote":{"price":10.0,"currency":"eur"}}],"failed":[]}""",
            ),
        )

        val r = source.quotes(listOf("a")) as BtResult.Ok
        assertEquals(10.0, r.value.quotes.getValue("a").eurPrice!!, 0.0001)
    }

    @Test
    fun `failed ids and unanswered ids both land in failed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"quotes":[{"assetId":"ok","quote":{"price":1.0,"currency":"EUR"}}],
                 "failed":["named"]}
                """.trimIndent(),
            ),
        )

        // "ghost" is asked for but appears in neither list.
        val r = source.quotes(listOf("ok", "named", "ghost")) as BtResult.Ok

        assertEquals(setOf("ok"), r.value.quotes.keys)
        assertEquals(setOf("named", "ghost"), r.value.failed)
    }

    @Test
    fun `more than a hundred ids are split into chunks the server accepts`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"quotes":[],"failed":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"quotes":[],"failed":[]}"""))

        source.quotes((1..150).map { "id$it" })

        assertEquals("two calls for 150 ids", 2, server.requestCount)
        val first = server.takeRequest().path!!.substringAfter("ids=")
        val second = server.takeRequest().path!!.substringAfter("ids=")
        assertEquals(100, first.split("%2C").size)
        assertEquals(50, second.split("%2C").size)
    }

    @Test
    fun `duplicates are collapsed before the call is made`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"quotes":[],"failed":[]}"""))

        source.quotes(listOf("a", "a", "b", "", "b"))

        assertEquals("ids=a%2Cb", server.takeRequest().path!!.substringAfter('?'))
    }

    @Test
    fun `an empty request never touches the network`() = runBlocking {
        val r = source.quotes(emptyList()) as BtResult.Ok
        assertTrue(r.value.quotes.isEmpty())
        assertTrue(r.value.failed.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a transport failure is an error the caller can fall back from`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"SERVER_ERROR"}}"""))

        val r = source.quotes(listOf("a"))
        assertTrue("was $r", r is BtResult.Err)
    }

    @Test
    fun `a source without a batch endpoint falls back to per-asset reads`() = runBlocking {
        // The interface default is what a Drive-autonomous source inherits: it
        // must produce the same shape, including the euro price the per-asset
        // path CAN supply for a non-EUR quote.
        val fake = object : MarketDataSource {
            var calls = 0
            override suspend fun search(query: String): BtResult<SearchOutcome> = error("unused")
            override suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> = error("unused")
            override suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> = error("unused")
            override suspend fun assetHistory(
                assetId: String,
                range: AssetRange,
            ): BtResult<AssetPriceSeries> = error("unused")

            override suspend fun quote(assetId: String): BtResult<AssetSnapshot> {
                calls++
                if (assetId == "bad") return BtResult.Err(at.bettertrack.app.data.api.BtApiError(0, "OFFLINE"))
                return BtResult.Ok(
                    AssetSnapshot(
                        asset = MarketAsset(assetId, "SYM", "Name", null, "stock", "USD", false),
                        nativePrice = 210.0,
                        quoteCurrency = "USD",
                        dayChangePct = 2.0,
                        prevClose = null,
                        // The per-asset endpoint converts, and the fallback must
                        // keep that figure rather than re-deriving it.
                        eurPrice = 193.0,
                        asOf = null,
                        stale = false,
                    ),
                )
            }
        }

        val r = fake.quotes(listOf("good", "bad")) as BtResult.Ok

        assertEquals(2, fake.calls)
        assertEquals(193.0, r.value.quotes.getValue("good").eurPrice!!, 0.0001)
        assertEquals(setOf("bad"), r.value.failed)
    }
}
