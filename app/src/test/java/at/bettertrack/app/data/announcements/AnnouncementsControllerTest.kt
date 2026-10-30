package at.bettertrack.app.data.announcements

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.MeResponse
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.auth.SessionUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pipeline end to end: `/auth/me` tri-state → list → gate text → the three
 * surfaces, and every acknowledge branch, against a MockWebServer that routes
 * by path. Window and ordering rules are pinned on the pure derivations below.
 */
class AnnouncementsControllerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private lateinit var server: MockWebServer
    private lateinit var repo: AnnouncementRepository
    private lateinit var scope: CoroutineScope
    private val requests = CopyOnWriteArrayList<String>()

    // ── what the fake server answers ──
    @Volatile private var listBody = """{"announcements":[]}"""
    private val items = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val ackAnswers = ConcurrentLinkedQueue<MockResponse>()
    @Volatile private var dismissAnswer = ok()

    // ── what /auth/me answers ──
    @Volatile private var pendingField = ""
    private val meCalls = AtomicInteger(0)

    private val auth = MutableStateFlow<AuthState>(AuthState.LoggedIn(SessionUser.unknown().copy(id = "u1")))
    private val store = InMemoryLocalDismissStore()

    private fun ok() = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"ok":true}""")

    private fun body(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun err(code: Int, c: String) = body(code, """{"error":{"code":"$c","message":"m"}}""")

    private val sha1 = "1".repeat(64)
    private val sha2 = "2".repeat(64)

    private fun ackItem(id: String, sha: String, locale: String = "en", title: String = "Terms $id") =
        """{"id":"$id","kind":"ack_required","severity":"warning","title":"$title","body":"Body $id","contentLocale":"$locale","availableLocales":["en","de"],"contentSha256":"$sha","dismissible":false,"status":"active"}"""

    private fun notice(id: String, endsAt: String? = null) =
        """{"id":"$id","kind":"notice","severity":"info","title":"N $id","body":"b","endsAt":${endsAt?.let { "\"$it\"" } ?: "null"}}"""

    private fun once(id: String) = """{"id":"$id","kind":"once","severity":"info","title":"O $id","body":"b"}"""

    private fun pending(vararg ids: String) =
        ""","pendingAcknowledgements":[${ids.joinToString(",") { """{"id":"$it","severity":"warning","title":"P $it","startsAt":null,"endsAt":null}""" }}]"""

    private fun ackOk(vararg stillOwed: String) = body(
        200,
        """{"acknowledgement":{"announcementId":"x","acknowledgedAt":"2026-10-30T10:00:00Z","via":"first_party_oauth","locale":"en","contentSha256":"$sha1"},
            "pendingAcknowledgements":[${stillOwed.joinToString(",") { """{"id":"$it","severity":"info","title":"P $it","startsAt":null,"endsAt":null}""" }}]}""",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.removePrefix("/api/v1/")
                requests += "${request.method} $path"
                val bare = path.substringBefore('?')
                return when {
                    request.method == "GET" && bare == "notifications/announcements" -> body(200, listBody)
                    request.method == "GET" && bare.startsWith("notifications/announcements/") -> {
                        val id = bare.removePrefix("notifications/announcements/")
                        items[id]?.let { body(200, it) } ?: err(404, "ANNOUNCEMENT_NOT_FOUND")
                    }
                    bare.endsWith("/seen") -> ok()
                    bare.endsWith("/dismiss") -> dismissAnswer
                    bare.endsWith("/acknowledge") -> ackAnswers.poll() ?: err(500, "INTERNAL")
                    else -> err(404, "NOT_FOUND")
                }
            }
        }
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = AnnouncementRepository(api, json, sleep = {})
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun me(): BtResult<MeResponse> {
        meCalls.incrementAndGet()
        return BtResult.Ok(
            json.decodeFromString(
                MeResponse.serializer(),
                """{"id":"u1","email":"e","username":"n","role":"user","status":"active"$pendingField}""",
            ),
        )
    }

    private fun controller(locale: String = "en", minInterval: Long = 0L) = AnnouncementsController(
        enabled = true,
        repo = repo,
        authState = auth,
        fetchMe = { me() },
        localDismiss = store,
        locale = { locale },
        scope = scope,
        minRefreshIntervalMs = minInterval,
    ).also { it.start() }

    private fun await(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    private fun settle() = Thread.sleep(150)

    // ── The tri-state ─────────────────────────────────────────────────────────

    @Test
    fun `an older server (pendingAcknowledgements absent) gets no modal`() {
        listBody = """{"announcements":[${ackItem("a1", sha1)},${notice("n1")}]}"""
        val c = controller()
        await("list") { c.state.value.items.size == 2 }
        settle()
        assertEquals(PendingAcks.Unknown, c.state.value.pending)
        assertNull(c.state.value.gate())
        assertEquals("n1", c.state.value.banner()?.id)
    }

    @Test
    fun `an empty pendingAcknowledgements is nothing owed`() {
        pendingField = """,${"\""}pendingAcknowledgements${"\""}:[]"""
        listBody = """{"announcements":[${ackItem("a1", sha1)}]}"""
        val c = controller()
        await("list") { c.state.value.items.isNotEmpty() }
        assertEquals(PendingAcks.Known(emptyList()), c.state.value.pending)
        assertNull(c.state.value.gate())
    }

    @Test
    fun `pending obligations show oldest first, one at a time, text from the list`() {
        pendingField = pending("a1", "a2")
        listBody = """{"announcements":[${ackItem("a1", sha1)},${ackItem("a2", sha2)}]}"""
        val c = controller()
        await("gate text") { c.state.value.gate()?.content != null }
        val g = c.state.value.gate()!!
        assertEquals("a1", g.id)
        assertEquals(2, g.total)
        assertTrue(g.canAcknowledge)
        // The list already carried the text — no per-id fetch.
        assertTrue(requests.none { it.startsWith("GET notifications/announcements/") })
    }

    @Test
    fun `the gate's text is fetched by id, in the app locale, when the list lacks it`() {
        pendingField = pending("a9")
        items["a9"] = ackItem("a9", sha1, locale = "de")
        val c = controller(locale = "de")
        await("gate text") { c.state.value.gate()?.content != null }
        assertTrue(requests.contains("GET notifications/announcements/a9?locale=de"))
        assertEquals("de", c.state.value.gate()!!.content!!.contentLocale)
    }

    // ── Acknowledge branches ────────────────────────────────────────────────────

    @Test
    fun `acknowledge happy path echoes the item and moves to the next`() {
        pendingField = pending("a1", "a2")
        listBody = """{"announcements":[${ackItem("a1", sha1)},${ackItem("a2", sha2)}]}"""
        ackAnswers += ackOk("a2")
        val c = controller()
        await("gate") { c.state.value.gate()?.content != null }
        c.acknowledge()
        await("next") { c.state.value.gate()?.id == "a2" }
        val req = server.takeRequestMatching("acknowledge")
        assertEquals("""{"confirm":true,"locale":"en","contentSha256":"$sha1"}""", req)
        assertEquals(1, c.state.value.gate()!!.total)
        assertFalse(c.state.value.ackBusy)
    }

    @Test
    fun `the last acknowledgement lifts the gate`() {
        pendingField = pending("a1")
        listBody = """{"announcements":[${ackItem("a1", sha1)}]}"""
        ackAnswers += ackOk()
        val c = controller()
        await("gate") { c.state.value.gate()?.content != null }
        c.acknowledge()
        await("lifted") { c.state.value.gate() == null }
        assertEquals(PendingAcks.Known(emptyList()), c.state.value.pending)
    }

    @Test
    fun `content changed - the item is re-read and asked again`() {
        pendingField = pending("a1")
        listBody = """{"announcements":[${ackItem("a1", sha1)}]}"""
        items["a1"] = ackItem("a1", sha2, title = "Terms v2")
        ackAnswers += err(409, "ANNOUNCEMENT_CONTENT_CHANGED")
        val c = controller()
        await("gate") { c.state.value.gate()?.content != null }
        c.acknowledge()
        await("re-read") { c.state.value.gate()?.content?.contentSha256 == sha2 }
        val g = c.state.value.gate()!!
        assertEquals(AnnouncementCodes.CONTENT_CHANGED, g.notice)
        assertEquals("Terms v2", g.content!!.title)
        assertTrue(requests.contains("GET notifications/announcements/a1?locale=en"))
    }

    @Test
    fun `not pending - auth me is re-read and the gate moves on`() {
        pendingField = pending("a1")
        listBody = """{"announcements":[${ackItem("a1", sha1)}]}"""
        ackAnswers += err(409, "ANNOUNCEMENT_NOT_PENDING")
        val c = controller()
        await("gate") { c.state.value.gate()?.content != null }
        val before = meCalls.get()
        pendingField = pending() // the server no longer lists it
        c.acknowledge()
        await("moved on") { c.state.value.gate() == null }
        await("me re-read") { meCalls.get() > before }
    }

    @Test
    fun `locale unavailable - falls back to an available locale and asks again`() {
        pendingField = pending("a1")
        listBody = """{"announcements":[${ackItem("a1", sha1, locale = "de")}]}"""
        items["a1"] = ackItem("a1", sha2, locale = "en")
        ackAnswers += err(400, "ANNOUNCEMENT_LOCALE_UNAVAILABLE")
        val c = controller(locale = "de")
        await("gate") { c.state.value.gate()?.content != null }
        c.acknowledge()
        await("fallback text") { c.state.value.gate()?.content?.contentLocale == "en" }
        assertTrue(requests.contains("GET notifications/announcements/a1?locale=en"))
        assertEquals(AnnouncementCodes.LOCALE_UNAVAILABLE, c.state.value.gate()!!.notice)
    }

    @Test
    fun `the language switch re-fetches and acknowledges in the shown locale`() {
        pendingField = pending("a1")
        listBody = """{"announcements":[${ackItem("a1", sha1, locale = "en")}]}"""
        items["a1"] = ackItem("a1", sha2, locale = "de")
        ackAnswers += ackOk()
        val c = controller()
        await("gate") { c.state.value.gate()?.content != null }
        c.switchGateLocale("de")
        await("de text") { c.state.value.gate()?.content?.contentLocale == "de" }
        c.acknowledge()
        await("lifted") { c.state.value.gate() == null }
        assertEquals("""{"confirm":true,"locale":"de","contentSha256":"$sha2"}""", server.takeRequestMatching("acknowledge"))
    }

    // ── 403 ACKNOWLEDGEMENT_REQUIRED ─────────────────────────────────────────────

    @Test
    fun `a 403 refusal anywhere raises the gate from its details`() {
        items["a5"] = ackItem("a5", sha1)
        val c = controller()
        await("first refresh") { requests.any { it.startsWith("GET notifications/announcements?") } }
        assertNull(c.state.value.gate())
        c.onGateRefusal(listOf(PendingAcknowledgement("a5", title = "From details")))
        await("gate") { c.state.value.gate()?.content != null }
        assertEquals("a5", c.state.value.gate()!!.id)
    }

    @Test
    fun `the interceptor reports the refusal and leaves the body to the caller`() {
        val seen = CopyOnWriteArrayList<List<PendingAcknowledgement>?>()
        val refusal = """{"error":{"code":"ACKNOWLEDGEMENT_REQUIRED","message":"m","details":{"pendingAcknowledgements":[{"id":"p1","severity":"info","title":"T","startsAt":null,"endsAt":null}]}}}"""
        val local = MockWebServer()
        local.enqueue(body(403, refusal))
        local.enqueue(err(403, "INSUFFICIENT_SCOPE"))
        local.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(AcknowledgementGateInterceptor(json) { seen += it }).build()
            val first = client.newCall(Request.Builder().url(local.url("/portfolios")).build()).execute()
            assertEquals(403, first.code)
            assertEquals(refusal, first.body!!.string()) // peeked, not consumed
            client.newCall(Request.Builder().url(local.url("/portfolios")).build()).execute().close()
            assertEquals(1, seen.size)
            assertEquals(listOf("p1"), seen.single()!!.map { it.id })
        } finally {
            local.shutdown()
        }
    }

    // ── notice / once ───────────────────────────────────────────────────────────

    @Test
    fun `closing a notice posts the dismissal and hides it`() {
        listBody = """{"announcements":[${once("o1")},${notice("n1")}]}"""
        val c = controller()
        await("list") { c.state.value.banner() != null }
        assertEquals("o1", c.state.value.onceCard()?.id)
        c.dismiss(c.state.value.banner()!!)
        assertNull(c.state.value.banner())
        await("dismiss sent") { requests.contains("POST notifications/announcements/n1/dismiss") }
        assertTrue(store.load("u1").isEmpty())
    }

    @Test
    fun `a grant without notifications write dismisses locally, per id, for good`() {
        listBody = """{"announcements":[${once("o1")}]}"""
        dismissAnswer = err(403, "INSUFFICIENT_SCOPE")
        val c = controller()
        await("card") { c.state.value.onceCard() != null }
        c.dismiss(c.state.value.onceCard()!!)
        await("persisted") { store.load("u1") == setOf("o1") }
        // A fresh process for the same account: the server still lists it, the app does not.
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val again = controller()
        await("list again") { again.state.value.items.isNotEmpty() }
        assertNull(again.state.value.onceCard())
    }

    @Test
    fun `an ack_required is never offered to dismiss`() {
        pendingField = pending("a1")
        listBody = """{"announcements":[${ackItem("a1", sha1)}]}"""
        val c = controller()
        await("gate") { c.state.value.gate()?.content != null }
        c.dismiss(c.state.value.gate()!!.content!!)
        settle()
        assertTrue(requests.none { it.endsWith("/dismiss") })
        assertNotNull(c.state.value.gate())
    }

    @Test
    fun `seen is reported once per item`() {
        listBody = """{"announcements":[${notice("n1")}]}"""
        val c = controller()
        await("banner") { c.state.value.banner() != null }
        val item = c.state.value.banner()!!
        c.markSeen(item)
        c.markSeen(item)
        await("seen") { requests.contains("POST notifications/announcements/n1/seen") }
        settle()
        assertEquals(1, requests.count { it.endsWith("/seen") })
    }

    // ── Triggers ────────────────────────────────────────────────────────────────

    @Test
    fun `announcement changed forces a refresh of auth me and the list`() {
        val c = controller(minInterval = 60_000L)
        await("first") { meCalls.get() == 1 }
        await("first list") { requests.count { it.startsWith("GET notifications/announcements?") } == 1 }
        c.onForeground() // inside the interval: collapsed
        settle()
        assertEquals(1, meCalls.get())
        pendingField = pending("a1")
        items["a1"] = ackItem("a1", sha1)
        c.onRealtimeChanged("a1")
        await("gate") { c.state.value.gate()?.content != null }
        assertEquals(2, meCalls.get())
    }

    @Test
    fun `another account starts clean`() {
        listBody = """{"announcements":[${notice("n1")}]}"""
        val c = controller()
        await("banner") { c.state.value.banner() != null }
        c.dismiss(c.state.value.banner()!!)
        listBody = """{"announcements":[]}"""
        auth.value = AuthState.LoggedIn(SessionUser.unknown().copy(id = "u2"))
        await("reset") { c.state.value.accountId == "u2" }
        assertTrue(c.state.value.hidden.isEmpty())
        auth.value = AuthState.LoggedOut
        await("signed out") { c.state.value.accountId == null }
        assertNull(c.state.value.banner())
    }

    // ── The debug preview ───────────────────────────────────────────────────────

    @Test
    fun `the preview drives all three surfaces and never touches the network`() {
        val c = controller()
        await("first") { requests.isNotEmpty() }
        settle()
        val before = requests.size
        c.preview(AnnouncementKind.NOTICE)
        c.preview(AnnouncementKind.ONCE)
        c.preview(AnnouncementKind.ACK_REQUIRED)
        val s = c.state.value
        assertEquals(AnnouncementPreviews.NOTICE_ID, s.banner()?.id)
        assertEquals(AnnouncementPreviews.ONCE_ID, s.onceCard()?.id)
        val g = s.gate()!!
        assertTrue(g.preview)
        assertTrue(g.canAcknowledge)
        assertNotNull(g.content!!.bodyDoc)
        c.switchGateLocale("de")
        assertEquals("de", c.state.value.gate()!!.content!!.contentLocale)
        c.markSeen(s.banner()!!)
        c.dismiss(s.banner()!!)
        c.dismiss(s.onceCard()!!)
        c.acknowledge()
        settle()
        assertNull(c.state.value.gate())
        assertNull(c.state.value.banner())
        assertNull(c.state.value.onceCard())
        assertEquals("the preview reached the server", before, requests.size)
    }

    private fun MockWebServer.takeRequestMatching(suffix: String): String {
        while (true) {
            val r = takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("no $suffix request")
            if (r.path!!.substringBefore('?').endsWith(suffix)) return r.body.readUtf8()
        }
    }
}

/** The pure rules the surfaces read: windows, order, the Confirm rule. */
class AnnouncementsDerivationsTest {

    private fun a(id: String, kind: AnnouncementKind, endsAt: Long? = null, startsAt: Long? = null) = Announcement(
        id = id, kind = kind, kindWire = kind.wire, severity = AnnouncementSeverity.INFO,
        title = id, body = "b", startsAtMs = startsAt, endsAtMs = endsAt,
        contentSha256 = "s".repeat(64), contentLocale = "en",
    )

    private fun state(vararg items: Announcement, now: Long = 1_000L, pending: PendingAcks = PendingAcks.Unknown) =
        AnnouncementsState(accountId = "u1", items = items.toList(), pending = pending, nowMs = now)

    @Test
    fun `a notice hides AT its endsAt`() {
        val s = state(a("n1", AnnouncementKind.NOTICE, endsAt = 2_000L))
        assertEquals("n1", s.copy(nowMs = 1_999L).banner()?.id)
        assertNull(s.copy(nowMs = 2_000L).banner())
        assertEquals(2_000L, s.nextBoundaryMs())
    }

    @Test
    fun `a once card waits for its startsAt and hides at its endsAt`() {
        val s = state(a("o1", AnnouncementKind.ONCE, startsAt = 1_500L, endsAt = 3_000L))
        assertNull(s.onceCard())
        assertEquals(1_500L, s.nextBoundaryMs())
        assertEquals("o1", s.copy(nowMs = 1_500L).onceCard()?.id)
        assertEquals(3_000L, s.copy(nowMs = 1_500L).nextBoundaryMs())
        assertNull(s.copy(nowMs = 3_000L).onceCard())
    }

    @Test
    fun `surfaces keep server order and never show the wrong kind`() {
        val s = state(
            a("ack", AnnouncementKind.ACK_REQUIRED),
            a("o1", AnnouncementKind.ONCE),
            a("o2", AnnouncementKind.ONCE),
            a("n1", AnnouncementKind.NOTICE),
            a("n2", AnnouncementKind.NOTICE),
        )
        assertEquals("n1", s.banner()?.id)
        assertEquals("o1", s.onceCard()?.id)
        assertEquals("n2", s.copy(hidden = setOf("n1")).banner()?.id)
        // An ack_required in the list is content, never a banner or a gate by itself.
        assertNull(s.gate())
    }

    @Test
    fun `an ended or archived item is not shown`() {
        val ended = a("n1", AnnouncementKind.NOTICE).copy(status = AnnouncementStatus.ENDED)
        assertNull(state(ended).banner())
    }

    @Test
    fun `signed out shows nothing`() {
        val s = state(a("n1", AnnouncementKind.NOTICE)).copy(accountId = null)
        assertNull(s.banner())
        assertTrue(s.gateQueue().isEmpty())
    }

    @Test
    fun `a pending obligation lapses out of the queue at its endsAt`() {
        val s = state(pending = PendingAcks.Known(listOf(PendingAcknowledgement("p1", endsAtMs = 5_000L), PendingAcknowledgement("p2"))))
        assertEquals(listOf("p1", "p2"), s.gateQueue().map { it.id })
        assertEquals(listOf("p2"), s.copy(nowMs = 5_000L).gateQueue().map { it.id })
        assertEquals(listOf("p2"), s.copy(resolved = setOf("p1")).gateQueue().map { it.id })
    }

    @Test
    fun `Confirm is enabled only when ticked and a hashed text is on screen`() {
        val content = a("p1", AnnouncementKind.ACK_REQUIRED)
        val base = state(pending = PendingAcks.Known(listOf(PendingAcknowledgement("p1"))))
            .copy(gateContent = mapOf("p1" to content))
        val gate = base.gate()!!
        assertFalse("unticked", ackConfirmEnabled(false, gate))
        assertTrue("ticked", ackConfirmEnabled(true, gate))
        assertFalse("in flight", ackConfirmEnabled(true, base.copy(ackBusy = true).gate()!!))
        assertFalse("no text yet", ackConfirmEnabled(true, base.copy(gateContent = emptyMap()).gate()!!))
        assertFalse("loading", ackConfirmEnabled(true, base.copy(gateLoading = setOf("p1")).gate()!!))
        assertFalse(
            "no server hash",
            ackConfirmEnabled(true, base.copy(gateContent = mapOf("p1" to content.copy(contentSha256 = null))).gate()!!),
        )
    }
}
