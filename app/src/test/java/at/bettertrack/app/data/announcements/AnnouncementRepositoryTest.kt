package at.bettertrack.app.data.announcements

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** The announcement endpoints on the wire (contract §4), against a MockWebServer. */
class AnnouncementRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: AnnouncementRepository
    private val sleeps = mutableListOf<Long>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val sha = "c".repeat(64)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = AnnouncementRepository(api, json, sleep = { sleeps += it })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun status(code: Int, body: String = "") = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(code: Int, errorCode: String) =
        status(code, """{"error":{"code":"$errorCode","message":"server words"}}""")

    @Test
    fun `the list passes the app locale and decodes the old shape`() = runBlocking {
        server.enqueue(status(200, """{"announcements":[{"id":"a1","severity":"info","title":"T","body":"B","publishedAt":null}]}"""))
        val r = repo.list("de") as BtResult.Ok
        assertEquals(listOf("a1"), r.value.map { it.id })
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/notifications/announcements?locale=de", req.path)
    }

    @Test
    fun `one item is fetched by id with its locale`() = runBlocking {
        server.enqueue(status(200, """{"id":"a1","kind":"ack_required","title":"T","body":"B","contentLocale":"en","contentSha256":"$sha"}"""))
        val r = repo.get("a1", "en") as BtResult.Ok
        assertEquals(sha, r.value.contentSha256)
        assertEquals("/api/v1/notifications/announcements/a1?locale=en", server.takeRequest().path)
    }

    @Test
    fun `acknowledge sends the strict body and returns what is still owed`() = runBlocking {
        server.enqueue(
            status(
                200,
                """{"acknowledgement":{"announcementId":"a1","acknowledgedAt":"2026-10-30T10:00:00Z","via":"first_party_oauth","locale":"en","contentSha256":"$sha"},
                    "pendingAcknowledgements":[{"id":"a2","severity":"info","title":"Next","startsAt":null,"endsAt":null}]}""",
            ),
        )
        val out = repo.acknowledge("a1", "en", sha) as AckOutcome.Acknowledged
        assertEquals(listOf("a2"), (out.pending as PendingAcks.Known).items.map { it.id })
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/notifications/announcements/a1/acknowledge", req.path)
        assertEquals("""{"confirm":true,"locale":"en","contentSha256":"$sha"}""", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `a timed-out acknowledge is retried with the identical body (first-write-wins)`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(status(503, "{}"))
        server.enqueue(
            status(200, """{"acknowledgement":{"announcementId":"a1","acknowledgedAt":"2026-10-30T09:00:00Z","via":"first_party_oauth","locale":"en","contentSha256":"$sha"},"pendingAcknowledgements":[]}"""),
        )
        val out = repo.acknowledge("a1", "en", sha) as AckOutcome.Acknowledged
        assertEquals(PendingAcks.Known(emptyList()), out.pending)
        assertEquals(3, server.requestCount)
        val bodies = (0 until 3).map { server.takeRequest().body.readUtf8() }
        assertEquals(1, bodies.toSet().size)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun `retries are bounded`() = runBlocking {
        repeat(3) { server.enqueue(status(502, "{}")) }
        val out = repo.acknowledge("a1", "en", sha)
        assertTrue(out is AckOutcome.Failed)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `each 409 and 400 maps to its branch`() = runBlocking {
        server.enqueue(error(409, "ANNOUNCEMENT_CONTENT_CHANGED"))
        assertEquals(AckOutcome.ContentChanged, repo.acknowledge("a1", "en", sha))
        server.enqueue(error(409, "ANNOUNCEMENT_NOT_PENDING"))
        assertEquals(AckOutcome.NoLongerPending("ANNOUNCEMENT_NOT_PENDING"), repo.acknowledge("a1", "en", sha))
        server.enqueue(error(400, "ANNOUNCEMENT_LOCALE_UNAVAILABLE"))
        assertEquals(AckOutcome.LocaleUnavailable, repo.acknowledge("a1", "de", sha))
        server.enqueue(error(404, "ANNOUNCEMENT_NOT_FOUND"))
        assertEquals(AckOutcome.NoLongerPending("ANNOUNCEMENT_NOT_FOUND"), repo.acknowledge("a1", "en", sha))
        server.enqueue(error(409, "ANNOUNCEMENT_NOT_ACKNOWLEDGEABLE"))
        assertEquals(AckOutcome.NoLongerPending("ANNOUNCEMENT_NOT_ACKNOWLEDGEABLE"), repo.acknowledge("a1", "en", sha))
        server.enqueue(error(403, "API_KEY_FORBIDDEN"))
        assertTrue(repo.acknowledge("a1", "en", sha) is AckOutcome.Failed)
        // None of these is retried: each is a verdict.
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `dismiss maps the contract's refusals`() = runBlocking {
        server.enqueue(status(200, """{"ok":true}"""))
        assertEquals(ReceiptOutcome.Recorded, repo.dismiss("n1"))
        server.enqueue(error(409, "ANNOUNCEMENT_NOT_DISMISSIBLE"))
        assertEquals(ReceiptOutcome.NotDismissible, repo.dismiss("ack1"))
        server.enqueue(error(404, "ANNOUNCEMENT_NOT_FOUND"))
        assertEquals(ReceiptOutcome.Gone, repo.dismiss("gone"))
        server.enqueue(error(403, "INSUFFICIENT_SCOPE"))
        assertEquals(ReceiptOutcome.ScopeMissing, repo.dismiss("n2"))
        server.enqueue(status(500, "{}"))
        assertTrue(repo.dismiss("n3") is ReceiptOutcome.Failed)
        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/api/v1/notifications/announcements/n1/dismiss", first.path)
        assertEquals(0L, first.bodySize)
    }

    @Test
    fun `seen is a body-less POST`() = runBlocking {
        server.enqueue(status(200, """{"ok":true}"""))
        assertEquals(ReceiptOutcome.Recorded, repo.seen("n1"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/notifications/announcements/n1/seen", req.path)
        server.enqueue(error(403, "INSUFFICIENT_SCOPE"))
        assertEquals(ReceiptOutcome.ScopeMissing, repo.seen("n1"))
    }
}
