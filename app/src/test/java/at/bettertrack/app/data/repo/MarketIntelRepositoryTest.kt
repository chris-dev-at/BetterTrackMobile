package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The market-intel gating contract, proved against a real MockWebServer so the
 * assertions land on the ACTUAL paths and bytes Retrofit produces.
 *
 * What is under test is not "does JSON parse" (that is
 * `MarketIntelWireTest`) but the three-way distinction the whole feature exists
 * for, and the request economy that comes with it:
 *
 *  - the server-wide flag off ⇒ four [IntelSection.Off] and **no** follow-up
 *    requests at all;
 *  - a capability the asset's provider lacks ⇒ that block off, **without**
 *    spending a round-trip discovering it;
 *  - a 200 whose body says `available:false` ⇒ that block off, even though the
 *    probe promised the capability;
 *  - one section's read failing ⇒ that block off while the other three still
 *    render.
 *
 * A path-keyed [Dispatcher] is used rather than `enqueue`, because the four
 * per-asset reads run in PARALLEL — with a FIFO queue the response a given
 * endpoint receives would depend on thread scheduling.
 */
class MarketIntelRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi
    private lateinit var repo: MarketIntelRepository
    private lateinit var dispatcher: PathDispatcher

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        dispatcher = PathDispatcher()
        server.dispatcher = dispatcher
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = MarketIntelRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── The flag ────────────────────────────────────────────────────────────

    @Test
    fun `the feature flag off turns every block off and costs exactly one request`() = runBlocking {
        dispatcher.stub(INTEL, ok("""{"enabled":false,"capabilities":$ALL_CAPS}"""))

        val intel = (repo.assetIntel(ASSET) as BtResult.Ok).value

        assertTrue(intel.allOff)
        assertTrue(intel.dividends is IntelSection.Off)
        assertTrue(intel.earnings is IntelSection.Off)
        assertTrue(intel.news is IntelSection.Off)
        assertTrue(intel.splits is IntelSection.Off)
        // The probe and nothing else — four requests for an answer already known
        // would be four provider round-trips wasted on every custom asset.
        assertEquals(listOf(INTEL), dispatcher.hits())
    }

    // ── Capabilities ────────────────────────────────────────────────────────

    @Test
    fun `a capability the provider lacks is turned off WITHOUT being requested`() = runBlocking {
        dispatcher.stub(
            INTEL,
            ok(
                """{"enabled":true,"capabilities":
                   {"dividends":true,"earnings":false,"news":false,"splits":false}}""",
            ),
        )
        dispatcher.stub(DIVIDENDS, ok("""{"available":true,"history":[],"upcoming":[]}"""))

        val intel = (repo.assetIntel(ASSET) as BtResult.Ok).value

        assertTrue(intel.dividends is IntelSection.Data)
        assertTrue(intel.earnings is IntelSection.Off)
        assertTrue(intel.news is IntelSection.Off)
        assertTrue(intel.splits is IntelSection.Off)
        assertFalse(intel.allOff)
        assertEquals(setOf(INTEL, DIVIDENDS), dispatcher.hits().toSet())
        assertEquals(2, dispatcher.hits().size)
    }

    @Test
    fun `an available section with an EMPTY list is Data, never Off`() = runBlocking {
        // "This asset pays no dividends" is an answer. Collapsing it to Off would
        // hide the block and leave the user unable to tell the two apart.
        dispatcher.stub(INTEL, ok("""{"enabled":true,"capabilities":$ALL_CAPS}"""))
        dispatcher.stub(DIVIDENDS, ok("""{"available":true,"history":[],"upcoming":[]}"""))
        dispatcher.stub(EARNINGS, ok("""{"available":true,"next":null,"recent":[]}"""))
        dispatcher.stub(NEWS, ok("""{"available":true,"headlines":[]}"""))
        dispatcher.stub(SPLITS, ok("""{"available":true,"history":[],"upcoming":[]}"""))

        val intel = (repo.assetIntel(ASSET) as BtResult.Ok).value

        val dividends = intel.dividends as IntelSection.Data
        assertTrue(dividends.value.history.isEmpty())
        assertTrue(intel.earnings is IntelSection.Data)
        assertTrue(intel.news is IntelSection.Data)
        assertTrue(intel.splits is IntelSection.Data)
        assertFalse(intel.allOff)
    }

    @Test
    fun `an available-false BODY turns the block off even though the probe promised it`() =
        runBlocking {
            // The probe is not a guarantee: the provider can still throw on the
            // individual call, and the server answers 200 + available:false.
            dispatcher.stub(INTEL, ok("""{"enabled":true,"capabilities":$ALL_CAPS}"""))
            dispatcher.stub(DIVIDENDS, ok("""{"available":true,"history":[],"upcoming":[]}"""))
            dispatcher.stub(EARNINGS, ok("""{"available":true,"next":null,"recent":[]}"""))
            dispatcher.stub(NEWS, ok("""{"available":false,"headlines":[]}"""))
            dispatcher.stub(SPLITS, ok("""{"available":true,"history":[],"upcoming":[]}"""))

            val intel = (repo.assetIntel(ASSET) as BtResult.Ok).value

            assertTrue(intel.news is IntelSection.Off)
            assertTrue(intel.dividends is IntelSection.Data)
            assertTrue(intel.earnings is IntelSection.Data)
            assertTrue(intel.splits is IntelSection.Data)
        }

    // ── Per-section failure ─────────────────────────────────────────────────

    @Test
    fun `one section's read failing degrades THAT block only`() = runBlocking {
        dispatcher.stub(INTEL, ok("""{"enabled":true,"capabilities":$ALL_CAPS}"""))
        dispatcher.stub(DIVIDENDS, ok("""{"available":true,"history":[],"upcoming":[]}"""))
        dispatcher.stub(EARNINGS, ok("""{"available":true,"next":null,"recent":[]}"""))
        dispatcher.stub(NEWS, ok("""{"available":true,"headlines":[]}"""))
        dispatcher.stub(
            SPLITS,
            MockResponse().setResponseCode(500)
                .setBody("""{"error":{"code":"UPSTREAM","message":"boom"}}"""),
        )

        val intel = (repo.assetIntel(ASSET) as BtResult.Ok).value

        assertTrue(intel.splits is IntelSection.Off)
        assertTrue(intel.dividends is IntelSection.Data)
        assertTrue(intel.earnings is IntelSection.Data)
        assertTrue(intel.news is IntelSection.Data)
        // All five went out: nothing is skipped because a sibling failed.
        assertEquals(5, dispatcher.hits().size)
    }

    @Test
    fun `a failed PROBE fails the whole section instead of claiming four absences`() = runBlocking {
        dispatcher.stub(
            INTEL,
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"Session expired."}}"""),
        )

        val r = repo.assetIntel(ASSET)

        assertTrue("was $r", r is BtResult.Err)
        assertEquals(401, (r as BtResult.Err).error.httpStatus)
        // Nothing can be honestly claimed, so nothing is attempted.
        assertEquals(listOf(INTEL), dispatcher.hits())
    }

    @Test
    fun `an offline probe is a NETWORK error, not an asset that pays nothing`() = runBlocking {
        server.shutdown()

        val r = repo.assetIntel(ASSET)

        val err = (r as BtResult.Err).error
        assertTrue(err.isNetwork)
        assertEquals(0, err.httpStatus)
    }

    // ── Portfolio-wide roll-ups ─────────────────────────────────────────────

    @Test
    fun `the four roll-ups hit their documented paths`() = runBlocking {
        dispatcher.stub(EARNINGS_CAL, ok("""{"available":true,"entries":[]}"""))
        dispatcher.stub(DIVIDEND_CAL, ok("""{"available":true,"entries":[]}"""))
        dispatcher.stub(PROJECTION, ok("""{"available":true,"currency":"EUR","holdings":[]}"""))
        dispatcher.stub(DIGEST, ok("""{"available":true,"groups":[]}"""))

        assertTrue(repo.earningsCalendar() is BtResult.Ok)
        assertTrue(repo.dividendCalendar() is BtResult.Ok)
        assertTrue(repo.dividendProjection() is BtResult.Ok)
        assertTrue(repo.newsDigest() is BtResult.Ok)

        assertEquals(
            listOf(EARNINGS_CAL, DIVIDEND_CAL, PROJECTION, DIGEST),
            dispatcher.hits(),
        )
    }

    @Test
    fun `a roll-up saying available false is a SUCCESSFUL read the screen must hide`() =
        runBlocking {
            // The roll-ups keep their BtResult so the screen can tell this
            // (absent block) from an error (retry) from an empty list (empty state).
            dispatcher.stub(DIVIDEND_CAL, ok("""{"available":false,"entries":[]}"""))

            val r = repo.dividendCalendar()

            assertTrue("was $r", r is BtResult.Ok)
            assertFalse((r as BtResult.Ok).value.available)
        }

    @Test
    fun `a paranoid-mode 403 stays an ERROR and is never flattened into an empty list`() =
        runBlocking {
            dispatcher.stub(
                PROJECTION,
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"code":"PARANOID_MODE",
                       "message":"Paranoid mode is on for this account."}}""",
                ),
            )

            val r = repo.dividendProjection()

            assertTrue("was $r", r is BtResult.Err)
            assertEquals(403, (r as BtResult.Err).error.httpStatus)
        }

    // ── Paranoid mode (S2c-2 integration) ───────────────────────────────────

    /**
     * A paranoid account's server genuinely cannot see its holdings, so the
     * three holdings-derived roll-ups answer `403 PARANOID_MODE`. That is a
     * permanent, deliberate "we can't tell you" — the exact meaning of
     * `available:false` on this surface — so it must reach the UI as an absent
     * block, never as an error the user is invited to retry forever.
     */
    @Test
    fun `a paranoid-mode refusal reads as unavailable, not as a retryable error`() = runBlocking {
        val refusal = MockResponse().setResponseCode(403).setBody(
            """{"error":{"code":"PARANOID_MODE","message":"Portfolio data is not accessible."}}""",
        )
        dispatcher.stub(DIVIDEND_CAL, refusal)
        dispatcher.stub(PROJECTION, refusal)
        dispatcher.stub(DIGEST, refusal)

        val calendar = repo.dividendCalendar()
        val projection = repo.dividendProjection()
        val digest = repo.newsDigest()

        assertTrue(calendar is BtResult.Ok)
        assertFalse((calendar as BtResult.Ok).value.available)
        assertTrue(projection is BtResult.Ok)
        assertFalse((projection as BtResult.Ok).value.available)
        assertTrue(digest is BtResult.Ok)
        assertFalse((digest as BtResult.Ok).value.available)
    }

    /** Any OTHER failure stays an error — only the paranoid code is translated. */
    @Test
    fun `an ordinary failure on a roll-up is still an error`() = runBlocking {
        dispatcher.stub(
            PROJECTION,
            MockResponse().setResponseCode(500).setBody(
                """{"error":{"code":"INTERNAL","message":"boom"}}""",
            ),
        )

        assertTrue(repo.dividendProjection() is BtResult.Err)
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun ok(body: String) = MockResponse().setBody(body)

    /** Answers by PATH so the parallel per-asset reads are deterministic. */
    private class PathDispatcher : Dispatcher() {
        private val stubs = mutableMapOf<String, MockResponse>()
        private val seen = CopyOnWriteArrayList<String>()

        fun stub(path: String, response: MockResponse) {
            stubs[path] = response
        }

        fun hits(): List<String> = seen.toList()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            seen += path
            return stubs[path] ?: MockResponse().setResponseCode(404).setBody(
                """{"error":{"code":"NO_STUB","message":"no stub for $path"}}""",
            )
        }
    }

    private companion object {
        const val ASSET = "a1"
        const val INTEL = "/assets/a1/intel"
        const val DIVIDENDS = "/assets/a1/intel/dividends"
        const val EARNINGS = "/assets/a1/intel/earnings"
        const val NEWS = "/assets/a1/intel/news"
        const val SPLITS = "/assets/a1/intel/splits"
        const val EARNINGS_CAL = "/assets/intel/earnings-calendar"
        const val DIVIDEND_CAL = "/assets/portfolio/dividend-calendar"
        const val PROJECTION = "/assets/portfolio/dividend-projection"
        const val DIGEST = "/assets/portfolio/news-digest"
        const val ALL_CAPS =
            """{"dividends":true,"earnings":true,"news":true,"splits":true}"""
    }
}
