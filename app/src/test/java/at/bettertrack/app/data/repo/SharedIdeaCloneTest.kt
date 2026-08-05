package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A friend's shared idea: the `/social/shared` pointer, and the clone that is
 * the only way to resolve it.
 *
 * ## Why this test exists
 *
 * `GET /social/shared` has returned FOUR arrays since ideas became a share kind,
 * and the app's DTO named three. Because the app's `Json` sets
 * `ignoreUnknownKeys = true` — correctly, so a platform addition never crashes a
 * screen — the fourth array decoded to nothing at all, with no exception, no log
 * line and no failing test. A friend who shared an idea simply did not appear.
 * That is invisible without a decode test, which is what the first case here is.
 *
 * ## The shapes asserted
 *
 * Every body below is the LIVE dev-backend response, captured 2026-08-05 by
 * sharing a real idea between two real accounts, not a shape inferred from the
 * contract:
 *
 *  - the summary is a read-only POINTER — `{ideaId, name, owner, hasThesis,
 *    activityAlertsEnabled}` — and carries no thesis text and no state;
 *  - `POST /ideas/{id}/clone` answers **201** (not 200) with `{"idea": {...}}`,
 *    a NEW id and the SAME name as the original;
 *  - a viewer the audience no longer admits gets **404 `NOT_FOUND`**, never 403.
 *    The server deliberately does not distinguish "revoked" from "never existed"
 *    — leaking that difference would leak the idea's existence — so the app must
 *    read 404 here as "not shared with you any more" and must not invent a
 *    distinction the wire does not make.
 */
class SharedIdeaCloneTest {

    private lateinit var server: MockWebServer
    private lateinit var social: DefaultSocialRepository
    private lateinit var ideas: IdeasRepository

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
        social = DefaultSocialRepository(api, json, webOrigin = "https://bettertrack.at")
        ideas = IdeasRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── The pointer ──────────────────────────────────────────────────────────

    @Test
    fun `a friend's shared idea decodes as a pointer with its owner`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"portfolios":[],"conglomerates":[],"watchlists":[],
                 "ideas":[
                   {"ideaId":"019fd1d8-c6f5-7208-bd57-1669c34e77de",
                    "name":"Probe shared idea",
                    "owner":{"id":"019fd1d8-7f95-7029-9b25-ad0cdea36fb8",
                             "username":"btcloneprobe","profileIcon":null},
                    "hasThesis":true,"activityAlertsEnabled":false},
                   {"ideaId":"i-2","name":"No rationale",
                    "owner":{"id":"u-2","username":"mira"},
                    "hasThesis":false,"activityAlertsEnabled":true}
                 ]}
                """.trimIndent(),
            ),
        )

        val shared = (social.sharedWithMe() as BtResult.Ok).value

        assertEquals(2, shared.ideas.size)
        val first = shared.ideas[0]
        assertEquals("019fd1d8-c6f5-7208-bd57-1669c34e77de", first.ideaId)
        assertEquals("Probe shared idea", first.name)
        assertEquals("btcloneprobe", first.ownerName)
        assertEquals("019fd1d8-7f95-7029-9b25-ad0cdea36fb8", first.ownerId)
        // The one signal about the rationale. The TEXT is deliberately not here.
        assertTrue(first.hasThesis)
        assertFalse(first.activityAlertsEnabled)

        assertFalse(shared.ideas[1].hasThesis)
        assertTrue(shared.ideas[1].activityAlertsEnabled)

        // And the fourth kind counts like the other three.
        assertEquals(2, shared.count)
        assertFalse(shared.isEmpty)
    }

    /** A body from before ideas were shareable must still decode: the key is defaulted. */
    @Test
    fun `a response without the ideas key still decodes`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"portfolios":[],"conglomerates":[],
                    "watchlists":[{"watchlistId":"w-1","name":"General",
                                   "owner":{"id":"u-1","username":"alice"},
                                   "itemCount":3}]}""",
            ),
        )

        val shared = (social.sharedWithMe() as BtResult.Ok).value

        assertEquals(1, shared.watchlists.size)
        assertTrue(shared.ideas.isEmpty())
    }

    // ── The clone ────────────────────────────────────────────────────────────

    @Test
    fun `cloning returns my own copy on 201`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {"idea":{"id":"019fd1d9-78e1-7cbb-b0d2-4c9a4963ee80",
                 "name":"Probe shared idea",
                 "thesis":"Wire-shape probe.",
                 "state":{"mode":"clip","range":"1Y",
                   "source":{"kind":"adhoc","positions":[{"weight":1,"assetId":"a-1"}]},
                   "benchmark":null,"rebalance":"none"},
                 "createdAt":"2026-08-05T12:15:18.229Z",
                 "updatedAt":"2026-08-05T12:15:18.229Z"}}
                """.trimIndent(),
            ),
        )

        val clone = (ideas.clone("019fd1d8-c6f5-7208-bd57-1669c34e77de") as BtResult.Ok).value

        // 201 must be treated as success — this is the one idea route that is
        // not a 200, and a naive `code == 200` check would have failed it.
        assertEquals("019fd1d9-78e1-7cbb-b0d2-4c9a4963ee80", clone.id)
        // A NEW id, and the name is carried verbatim — the server adds no
        // "(copy)" suffix, so the copy and any original sit under one name.
        assertEquals("Probe shared idea", clone.name)
        // The whole point: the state the pointer never carried is now readable.
        assertEquals("Wire-shape probe.", clone.thesis)
        assertEquals("1Y", clone.state.range)
        assertTrue(clone.state.source is IdeaSource.Adhoc)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/ideas/019fd1d8-c6f5-7208-bd57-1669c34e77de/clone"))
    }

    @Test
    fun `a revoked share answers 404, not 403`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"NOT_FOUND","message":"Idea not found."}}"""),
        )

        val r = ideas.clone("019fd1d8-c6f5-7208-bd57-1669c34e77de") as BtResult.Err

        // The UI branches on the STATUS, not on the code: the platform sends the
        // generic `NOT_FOUND` for a revoked share on purpose (a distinct code
        // would confirm the idea exists), so the status is the only signal there
        // is — and it must not be read as "403 forbidden" or as a plain bug.
        assertEquals(404, r.error.httpStatus)
        assertFalse(r.error.isForbidden)
        assertFalse(r.error.isNetwork)
    }
}
