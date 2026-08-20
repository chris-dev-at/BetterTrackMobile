package at.bettertrack.app.data.repo

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.FeedbackStatus
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
 * `GET /feedback/mine` — the wire shape, the two server invariants, the
 * unknown-value tolerance, and the error passthrough.
 *
 * Driven through a real MockWebServer so the Retrofit route, the DTO decode and
 * the domain mapping are exercised exactly the way the phone exercises them. The
 * bodies below are the contract's own shape, copied from production's deployed
 * `openapi.json` on 2026-08-20 (`MyFeedbackResponse`) rather than from a
 * description of it — which is the difference between "we built to the spec" and
 * "we built to what we remembered of the spec".
 *
 * The one place the app deliberately does NOT mirror the wire is `updatedAt`: the
 * caller-facing row does not declare one (the ADMIN row does), so none is
 * modelled, and one arriving anyway must be ignored rather than crash the list.
 * That is pinned below too.
 */
class FeedbackMineTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: FeedbackRepository

    /** Mirror AppGraph.json exactly. */
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
        repo = DefaultFeedbackRepository(api = api, json = json)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ok(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fail(status: Int, code: String, message: String = "nope") = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":"$code","message":"$message"}}""")

    private fun mine(): BtResult<List<FeedbackSubmission>> = runBlocking { repo.mine() }

    private fun loaded(): List<FeedbackSubmission> =
        (mine() as BtResult.Ok).value

    // ── The exact shape ──────────────────────────────────────────────────────

    @Test
    fun `the contract's ten fields decode into the read model`() {
        server.enqueue(
            ok(
                """
                {"submissions":[{
                  "id":"7b1f0c3e-0000-4000-8000-000000000001",
                  "category":"bug",
                  "subject":"Chart is blank",
                  "message":"The 1M chart renders nothing on my phone.",
                  "status":"working_on_it",
                  "lastStatusChangeAt":"2026-08-19T09:30:00.000Z",
                  "declinedReason":null,
                  "shippedVersion":null,
                  "unreadReplyCount":0,
                  "createdAt":"2026-08-17T12:00:00.000Z"
                }]}
                """.trimIndent(),
            ),
        )
        val item = loaded().single()
        assertEquals("7b1f0c3e-0000-4000-8000-000000000001", item.id)
        assertEquals(FeedbackCategory.Bug, item.category)
        assertEquals("bug", item.categoryWire)
        assertEquals("Chart is blank", item.subject)
        assertEquals("The 1M chart renders nothing on my phone.", item.message)
        assertEquals(FeedbackStatus.WorkingOnIt, item.status)
        assertEquals("working_on_it", item.statusWire)
        assertNull(item.declinedReason)
        assertNull(item.shippedVersion)
        assertEquals(0, item.unreadReplyCount)
        // 2026-08-17T12:00:00Z and 2026-08-19T09:30:00Z, as epoch millis.
        assertEquals(1_786_968_000_000L, item.createdAtMs)
        assertEquals(1_787_131_800_000L, item.lastStatusChangeAtMs)
    }

    @Test
    fun `the route is GET feedback slash mine and is asked exactly once`() {
        server.enqueue(ok("""{"submissions":[]}"""))
        mine()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/feedback/mine", request.path)
        // No retry loop, by design: one call in, one call out. A repository that
        // quietly retried would multiply a rate-limited feedback endpoint's load
        // and hide a real failure behind a slower one.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an undeclared field on the row is ignored rather than fatal`() {
        // `updatedAt` is on the ADMIN row, not this one — the caller-facing schema
        // does not declare it. If the platform ever adds it, the list must keep
        // working, and the app must still not report a field it was not promised.
        server.enqueue(
            ok(
                """
                {"submissions":[{
                  "id":"a","category":"other","subject":null,"message":"hi",
                  "status":"new","lastStatusChangeAt":"2026-08-20T06:00:00.000Z",
                  "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                  "createdAt":"2026-08-20T06:00:00.000Z",
                  "updatedAt":"2026-08-20T06:00:00.000Z"
                }]}
                """.trimIndent(),
            ),
        )
        assertEquals("hi", loaded().single().message)
    }

    @Test
    fun `a submission sent without a subject keeps a null subject`() {
        // `subject` is nullable by contract, and the row falls back to the message.
        // A blank string is normalised to null too — an empty subject is not a
        // subject, and rendering it would leave a title-shaped hole in the row.
        server.enqueue(
            ok(
                """
                {"submissions":[
                  {"id":"a","category":"other","subject":null,"message":"one",
                   "status":"new","lastStatusChangeAt":"2026-08-20T06:00:00.000Z",
                   "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                   "createdAt":"2026-08-20T06:00:00.000Z"},
                  {"id":"b","category":"other","subject":"   ","message":"two",
                   "status":"new","lastStatusChangeAt":"2026-08-20T05:00:00.000Z",
                   "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                   "createdAt":"2026-08-20T05:00:00.000Z"}
                ]}
                """.trimIndent(),
            ),
        )
        assertTrue(loaded().all { it.subject == null })
    }

    // ── The two server invariants ────────────────────────────────────────────

    @Test
    fun `declined always carries its reason and shipped always carries its version`() {
        server.enqueue(
            ok(
                """
                {"submissions":[
                  {"id":"a","category":"feature","subject":"Dark widgets","message":"m",
                   "status":"declined","lastStatusChangeAt":"2026-08-19T09:00:00.000Z",
                   "declinedReason":"The launcher decides the widget theme, not the app.",
                   "shippedVersion":null,"unreadReplyCount":0,
                   "createdAt":"2026-08-10T09:00:00.000Z"},
                  {"id":"b","category":"feature","subject":"Cash filters","message":"m",
                   "status":"shipped","lastStatusChangeAt":"2026-08-18T09:00:00.000Z",
                   "declinedReason":null,"shippedVersion":"0.131","unreadReplyCount":0,
                   "createdAt":"2026-08-09T09:00:00.000Z"}
                ]}
                """.trimIndent(),
            ),
        )
        val (declined, shipped) = loaded()
        assertEquals(FeedbackStatus.Declined, declined.status)
        assertEquals("The launcher decides the widget theme, not the app.", declined.declinedReason)
        assertNull(declined.shippedVersion)

        assertEquals(FeedbackStatus.Shipped, shipped.status)
        // VERBATIM. No "v" prepended, no reformatting: how a release is spelled is
        // the platform's to decide, and a client that normalises it will
        // eventually print a version that does not exist.
        assertEquals("0.131", shipped.shippedVersion)
        assertNull(shipped.declinedReason)
    }

    @Test
    fun `a reason or a version arriving on the wrong status is dropped`() {
        // The contract says both are null on every other status, so this body is a
        // server bug. The conservative read is to drop them: showing a decline
        // reason under "In Arbeit" would be the client amplifying the bug into a
        // sentence the user believes.
        server.enqueue(
            ok(
                """
                {"submissions":[{
                  "id":"a","category":"bug","subject":null,"message":"m",
                  "status":"working_on_it","lastStatusChangeAt":"2026-08-19T09:00:00.000Z",
                  "declinedReason":"stale","shippedVersion":"0.99","unreadReplyCount":0,
                  "createdAt":"2026-08-19T09:00:00.000Z"
                }]}
                """.trimIndent(),
            ),
        )
        val item = loaded().single()
        assertNull(item.declinedReason)
        assertNull(item.shippedVersion)
    }

    // ── Tolerance ────────────────────────────────────────────────────────────

    @Test
    fun `a status this build does not know decodes to null and keeps the wire word`() {
        // THE forward-compatibility case. A status the platform adds next month
        // must neither vanish (the row would claim nothing happened) nor throw
        // (the whole list would fail); it resolves to `null` and the raw wire
        // string survives so the chip can print it.
        server.enqueue(
            ok(
                """
                {"submissions":[{
                  "id":"a","category":"bug","subject":"x","message":"m",
                  "status":"needs_more_info","lastStatusChangeAt":"2026-08-19T09:00:00.000Z",
                  "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                  "createdAt":"2026-08-19T09:00:00.000Z"
                }]}
                """.trimIndent(),
            ),
        )
        val item = loaded().single()
        assertNull(item.status)
        assertEquals("needs_more_info", item.statusWire)
    }

    @Test
    fun `a category this build does not know behaves the same way`() {
        server.enqueue(
            ok(
                """
                {"submissions":[{
                  "id":"a","category":"question","subject":"x","message":"m",
                  "status":"new","lastStatusChangeAt":"2026-08-19T09:00:00.000Z",
                  "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                  "createdAt":"2026-08-19T09:00:00.000Z"
                }]}
                """.trimIndent(),
            ),
        )
        val item = loaded().single()
        assertNull(item.category)
        assertEquals("question", item.categoryWire)
    }

    @Test
    fun `every wire status the contract names resolves`() {
        val wire = listOf(
            "new", "triaged", "working_on_it", "saved_as_future_idea", "declined", "shipped",
        )
        assertEquals(wire, FeedbackStatus.entries.map { it.wire })
        wire.forEach { assertEquals(it, FeedbackStatus.fromWire(it)?.wire) }
        assertNull(FeedbackStatus.fromWire(null))
        assertNull(FeedbackStatus.fromWire(""))
        // Case matters: the wire values are ASCII constants, not a fuzzy match.
        assertNull(FeedbackStatus.fromWire("NEW"))
    }

    @Test
    fun `an empty list is an answer, not a failure`() {
        server.enqueue(ok("""{"submissions":[]}"""))
        assertEquals(emptyList<FeedbackSubmission>(), loaded())
    }

    @Test
    fun `an absent submissions key degrades to an empty list`() {
        server.enqueue(ok("""{}"""))
        assertEquals(emptyList<FeedbackSubmission>(), loaded())
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    @Test
    fun `the list comes back newest first by createdAt`() {
        // The route documents no ordering, so the app imposes one rather than
        // rendering whatever a query planner produced.
        server.enqueue(
            ok(
                """
                {"submissions":[
                  {"id":"middle","category":"bug","subject":null,"message":"m",
                   "status":"new","lastStatusChangeAt":"2026-08-15T00:00:00.000Z",
                   "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                   "createdAt":"2026-08-15T00:00:00.000Z"},
                  {"id":"oldest","category":"bug","subject":null,"message":"m",
                   "status":"new","lastStatusChangeAt":"2026-08-01T00:00:00.000Z",
                   "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                   "createdAt":"2026-08-01T00:00:00.000Z"},
                  {"id":"newest","category":"bug","subject":null,"message":"m",
                   "status":"new","lastStatusChangeAt":"2026-08-20T00:00:00.000Z",
                   "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                   "createdAt":"2026-08-20T00:00:00.000Z"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("newest", "middle", "oldest"), loaded().map { it.id })
    }

    @Test
    fun `a row with an unparseable stamp sorts last instead of disappearing`() {
        server.enqueue(
            ok(
                """
                {"submissions":[
                  {"id":"broken","category":"bug","subject":null,"message":"m",
                   "status":"new","lastStatusChangeAt":"yesterday",
                   "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                   "createdAt":"not-a-date"},
                  {"id":"good","category":"bug","subject":null,"message":"m",
                   "status":"new","lastStatusChangeAt":"2026-08-20T00:00:00.000Z",
                   "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                   "createdAt":"2026-08-20T00:00:00.000Z"}
                ]}
                """.trimIndent(),
            ),
        )
        val items = loaded()
        assertEquals(listOf("good", "broken"), items.map { it.id })
        // The row survives with no place on the timeline — a submission the user
        // actually wrote is not something to hide over a bad timestamp.
        assertNull(items.last().createdAtMs)
        assertNull(items.last().lastStatusChangeAtMs)
    }

    @Test
    fun `an explicit offset stamp parses too`() {
        // `format: date-time` permits an offset, not only `…Z`.
        server.enqueue(
            ok(
                """
                {"submissions":[{
                  "id":"a","category":"bug","subject":null,"message":"m",
                  "status":"new","lastStatusChangeAt":"2026-08-20T08:00:00+02:00",
                  "declinedReason":null,"shippedVersion":null,"unreadReplyCount":0,
                  "createdAt":"2026-08-20T08:00:00+02:00"
                }]}
                """.trimIndent(),
            ),
        )
        // 08:00+02:00 == 06:00Z.
        assertEquals(1_787_205_600_000L, loaded().single().createdAtMs)
    }

    // ── unreadReplyCount is RESERVED ─────────────────────────────────────────

    @Test
    fun `unreadReplyCount is carried through untouched for the day threads ship`() {
        server.enqueue(
            ok(
                """
                {"submissions":[{
                  "id":"a","category":"bug","subject":null,"message":"m",
                  "status":"new","lastStatusChangeAt":"2026-08-20T00:00:00.000Z",
                  "declinedReason":null,"shippedVersion":null,"unreadReplyCount":3,
                  "createdAt":"2026-08-20T00:00:00.000Z"
                }]}
                """.trimIndent(),
            ),
        )
        // The server sends 0 today (no reply thread exists), so this is what the
        // model must do when that changes — not what it currently sees.
        assertEquals(3, loaded().single().unreadReplyCount)
    }

    // ── Errors come back verbatim ────────────────────────────────────────────

    @Test
    fun `403 INSUFFICIENT_SCOPE is surfaced as itself with the catalogued copy`() {
        // The pre-widening outcome, kept forever: a token minted before the
        // platform's grant-widening (or before any future scope split) lacks
        // `feedback:read` and gets exactly this. It must arrive as an ordinary
        // BtApiError whose code the app-wide catalogue already owns copy for —
        // the remedy in that copy (sign out and back in) is literally sufficient.
        server.enqueue(fail(403, BtApiError.Codes.INSUFFICIENT_SCOPE, "missing scope feedback:read"))
        val error = (mine() as BtResult.Err).error
        assertEquals(403, error.httpStatus)
        assertEquals(BtApiError.Codes.INSUFFICIENT_SCOPE, error.code)
        assertTrue(error.isInsufficientScope)
        assertEquals(R.string.bt_err_insufficient_scope, error.asMessage().res)
        // No dim English second line: the app owns the sentence for this code.
        assertNull(error.asMessage().diagnostic)
    }

    @Test
    fun `every other failure is passed through unsoftened`() {
        // Deliberately NOT softened to an empty list. An empty list is a real and
        // common answer here ("you have not written anything yet"), so a failure
        // that returned one would be indistinguishable from the truth.
        listOf(401 to "UNAUTHORIZED", 429 to "RATE_LIMITED", 500 to "INTERNAL_ERROR")
            .forEach { (status, code) ->
                server.enqueue(fail(status, code))
                val error = (mine() as BtResult.Err).error
                assertEquals(status, error.httpStatus)
                assertEquals(code, error.code)
            }
    }

    @Test
    fun `a transport failure is a network error, never a throw`() {
        server.shutdown()
        val error = (mine() as BtResult.Err).error
        assertEquals(0, error.httpStatus)
        assertTrue(error.isNetwork)
    }
}
