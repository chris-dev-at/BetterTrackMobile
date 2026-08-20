package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * `DELETE /feedback/{id}` — the route, the empty-body success, and the two things
 * this call must NOT claim (platform #1400, live on production 2026-08-20).
 *
 * The contract below was read off the deployed `openapi.json` on 2026-08-20, not
 * off the widening's prose: the route's own summary is *"Hide one caller-owned
 * submission while retaining an admin-visible tombstone"*, the path parameter is a
 * `uuid`, the success is **204 No Content**, and the declared failures are
 * `400 VALIDATION_ERROR`, `401` and the generic envelope — **no 404**. That absence
 * is the idempotency, stated in schema: deleting an id that is already gone is a
 * 204 like any other.
 *
 * Which is why the repository returns [Unit] and not a "was it deleted" boolean.
 * A boolean would have to be invented from the status code, and the screen would
 * then report a fact nobody checked. The screen re-reads `/feedback/mine` instead
 * and lets the list decide — the same discipline `settings/remembered-devices`
 * forced on the trusted-devices screen, for exactly the same reason.
 */
class FeedbackDeleteTest {

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

    private fun noContent() = MockResponse().setResponseCode(204)

    private fun fail(status: Int, code: String, message: String = "nope") = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":"$code","message":"$message"}}""")

    private val id = "7b1f0c3e-0000-4000-8000-000000000001"

    private fun delete(target: String = id): BtResult<Unit> = runBlocking { repo.delete(target) }

    // ── The route ────────────────────────────────────────────────────────────

    @Test
    fun `the route is DELETE feedback slash id and is asked exactly once`() {
        server.enqueue(noContent())
        delete()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/feedback/$id", request.path)
        // No retry loop: a delete that silently repeated itself would be harmless
        // here (the route is idempotent) and dishonest everywhere else — the
        // repository does what it was asked, once.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the id is sent as a path segment, not a query parameter`() {
        server.enqueue(noContent())
        delete()
        val path = server.takeRequest().path.orEmpty()
        assertTrue("id must be in the path: $path", path.endsWith("/$id"))
        assertTrue("no query string belongs on this route: $path", !path.contains("?"))
    }

    @Test
    fun `no request body is sent`() {
        server.enqueue(noContent())
        delete()
        assertEquals(0L, server.takeRequest().bodySize)
    }

    // ── 204 is a success, not an empty-response error ────────────────────────

    @Test
    fun `a 204 with no body is Ok`() {
        // THE regression this route invites: `apiCall` insists on a non-null body
        // and would map every successful delete to `APP_EMPTY_RESPONSE`. The
        // repository uses `unitApiCall` precisely so a bodyless 204 is a success.
        server.enqueue(noContent())
        assertTrue(delete() is BtResult.Ok)
    }

    @Test
    fun `deleting the same id twice succeeds twice`() {
        // The contract declares no 404, i.e. the route is idempotent. The app must
        // not treat a repeat as an error — and must not treat either 204 as proof
        // the row is gone, which is the caller's job via a re-read.
        server.enqueue(noContent())
        server.enqueue(noContent())
        assertTrue(delete() is BtResult.Ok)
        assertTrue(delete() is BtResult.Ok)
        assertEquals(2, server.requestCount)
    }

    // ── Failures arrive verbatim ─────────────────────────────────────────────

    @Test
    fun `every declared failure is passed through unsoftened`() {
        listOf(
            400 to BtApiError.Codes.VALIDATION_ERROR,
            401 to "UNAUTHENTICATED",
            403 to BtApiError.Codes.INSUFFICIENT_SCOPE,
            500 to "INTERNAL",
        ).forEach { (status, code) ->
            server.enqueue(fail(status, code))
            val error = (delete() as BtResult.Err).error
            assertEquals(status, error.httpStatus)
            assertEquals(code, error.code)
        }
    }

    @Test
    fun `a 404 is surfaced rather than swallowed into a fake success`() {
        // The contract does not declare one, so if a 404 ever arrives it is news:
        // reporting it as a success would tell the user their submission is gone on
        // the word of a status the route was never supposed to send. The screen
        // shows the catalogued sentence and the list — which is the truth — is
        // whatever the next read says.
        server.enqueue(fail(404, "NOT_FOUND"))
        val error = (delete() as BtResult.Err).error
        assertEquals(404, error.httpStatus)
        assertEquals("NOT_FOUND", error.code)
    }

    @Test
    fun `a transport failure is a network error, never a throw`() {
        server.shutdown()
        val error = (delete() as BtResult.Err).error
        assertEquals(0, error.httpStatus)
        assertTrue(error.isNetwork)
    }
}
