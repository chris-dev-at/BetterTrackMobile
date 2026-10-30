package at.bettertrack.app.data.announcements

import at.bettertrack.app.data.api.dto.MeResponse
import at.bettertrack.app.data.notifications.announcementKindOf
import at.bettertrack.app.data.repo.SocketIoChatGateway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire half of Announcements v2: both shapes decode, unknown values degrade
 * the way the contract says, and `pendingAcknowledgements` keeps its tri-state
 * (absent = unknown, `[]` = nothing owed) without ever being able to fail a
 * `MeResponse` decode.
 */
class AnnouncementModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private val oldShape = """
        {"announcements":[
          {"id":"a1","severity":"warning","title":"Maintenance","body":"Line one\nline two\n\nSecond paragraph","publishedAt":"2026-10-01T10:00:00Z"}
        ]}
    """.trimIndent()

    private val newItem = """
        {"id":"n1","severity":"critical","title":"Terms","body":"**Hi**","publishedAt":"2026-10-30T08:00:00Z",
         "kind":"ack_required","bodyFormat":"markdown",
         "bodyDoc":{"v":1,"blocks":[{"t":"p","c":[{"t":"strong","c":[{"t":"text","v":"Hi"}]}]}]},
         "contentLocale":"de","availableLocales":["en","de"],
         "contentSha256":"${"a".repeat(64)}",
         "startsAt":"2026-10-30T08:00:00Z","endsAt":null,"dismissible":false,"status":"active",
         "receipt":{"seenAt":"2026-10-30T09:00:00Z","dismissedAt":null,"acknowledgedAt":null,"lapsedAt":null,"lapseReason":null}}
    """.trimIndent()

    @Test
    fun `the old five-field shape still decodes as a passive notice`() {
        val items = parseAnnouncementList(obj(oldShape))
        assertEquals(1, items.size)
        val a = items.single()
        assertEquals(AnnouncementKind.NOTICE, a.kind)
        assertNull(a.kindWire)
        assertEquals(AnnouncementSeverity.WARNING, a.severity)
        assertEquals(AnnouncementStatus.ACTIVE, a.status)
        assertTrue(a.dismissible)
        assertFalse(a.acknowledgeable)
        assertNull(a.contentSha256)
        assertNull(a.bodyDoc)
        // Rendered from `body`: paragraphs at the blank line, the single break kept.
        val doc = a.renderDoc()
        assertEquals(2, doc.blocks.size)
        assertEquals(
            BodyDoc.Block.Paragraph(listOf(BodyDoc.Inline.Text("Line one"), BodyDoc.Inline.Break, BodyDoc.Inline.Text("line two"))),
            doc.blocks[0],
        )
        assertTrue(a.inWindow(0L)) // no window fields → always open
    }

    @Test
    fun `the new shape decodes every field`() {
        val a = parseAnnouncement(obj(newItem))!!
        assertEquals(AnnouncementKind.ACK_REQUIRED, a.kind)
        assertEquals(AnnouncementSeverity.CRITICAL, a.severity)
        assertEquals("markdown", a.bodyFormat)
        assertEquals("de", a.contentLocale)
        assertEquals(listOf("en", "de"), a.availableLocales)
        assertEquals("a".repeat(64), a.contentSha256)
        assertEquals(parseInstantMs("2026-10-30T08:00:00Z"), a.startsAtMs)
        assertNull(a.endsAtMs)
        assertFalse(a.dismissible)
        assertTrue(a.acknowledgeable)
        assertEquals(parseInstantMs("2026-10-30T09:00:00Z"), a.receipt?.seenAtMs)
        assertEquals(
            BodyDoc(listOf(BodyDoc.Block.Paragraph(listOf(BodyDoc.Inline.Strong(listOf(BodyDoc.Inline.Text("Hi"))))))),
            a.bodyDoc,
        )
    }

    @Test
    fun `an unknown kind renders as a notice and is never acknowledgeable`() {
        val a = parseAnnouncement(obj("""{"id":"x","kind":"poll","severity":"loud","title":"t","body":"b","status":"paused","dismissible":false}"""))!!
        assertEquals(AnnouncementKind.NOTICE, a.kind)
        assertEquals("poll", a.kindWire)
        assertFalse(a.acknowledgeable)
        assertEquals(AnnouncementSeverity.INFO, a.severity) // unknown severity → info
        assertEquals(AnnouncementStatus.ENDED, a.status) // unknown status → ended
        // The wire's `dismissible` is kept as data; the banner renders the item as a
        // notice and still offers its close (the contract's unknown-kind rule).
        assertFalse(a.dismissible)
        assertEquals(AnnouncementKind.NOTICE, AnnouncementKind.fromWire(null))
    }

    @Test
    fun `an ack_required is never dismissible whatever the wire says`() {
        val a = parseAnnouncement(obj("""{"id":"x","kind":"ack_required","title":"t","body":"b","dismissible":true}"""))!!
        assertFalse(a.dismissible)
    }

    @Test
    fun `the list keeps server order and drops only the malformed item`() {
        val list = parseAnnouncementList(
            obj(
                """{"announcements":[
                    {"id":"ack","kind":"ack_required","title":"A","body":"a"},
                    {"title":"no id"},
                    "not an object",
                    {"id":"once","kind":"once","title":"O","body":"o"},
                    {"id":"note","kind":"notice","title":"N","body":"n","availableLocales":"en"}
                ]}""",
            ),
        )
        assertEquals(listOf("ack", "once", "note"), list.map { it.id })
        assertEquals(emptyList<String>(), list[2].availableLocales) // wrong type → default, item kept
        assertEquals(emptyList<Announcement>(), parseAnnouncementList(obj("""{"items":[]}""")))
        assertEquals(emptyList<Announcement>(), parseAnnouncementList(null))
    }

    @Test
    fun `the window is half-open`() {
        val a = parseAnnouncement(obj("""{"id":"w","title":"t","body":"b","startsAt":"2026-10-30T10:00:00Z","endsAt":"2026-10-30T12:00:00Z"}"""))!!
        val start = parseInstantMs("2026-10-30T10:00:00Z")!!
        val end = parseInstantMs("2026-10-30T12:00:00Z")!!
        assertFalse(a.inWindow(start - 1))
        assertTrue(a.inWindow(start))
        assertTrue(a.inWindow(end - 1))
        assertFalse("endsAt is EXCLUSIVE", a.inWindow(end))
    }

    @Test
    fun `instants with an offset parse too`() {
        assertEquals(parseInstantMs("2026-10-30T10:00:00Z"), parseInstantMs("2026-10-30T11:00:00+01:00"))
        assertNull(parseInstantMs("yesterday"))
        assertNull(parseInstantMs(null))
    }

    // ── pendingAcknowledgements: the tri-state ─────────────────────────────────

    private fun me(extra: String) =
        json.decodeFromString(MeResponse.serializer(), """{"id":"u1","email":"e","username":"n","role":"user","status":"active"$extra}""")

    @Test
    fun `absent pendingAcknowledgements is UNKNOWN, not nothing owed`() {
        assertEquals(PendingAcks.Unknown, pendingAcksOf(me("").pendingAcknowledgements))
        assertEquals(PendingAcks.Unknown, pendingAcksOf(me(""","pendingAcknowledgements":null""").pendingAcknowledgements))
    }

    @Test
    fun `an empty pendingAcknowledgements is a known nothing-owed`() {
        assertEquals(PendingAcks.Known(emptyList()), pendingAcksOf(me(""","pendingAcknowledgements":[]""").pendingAcknowledgements))
    }

    @Test
    fun `pending items decode oldest-first and an entry without an id is skipped`() {
        val p = pendingAcksOf(
            me(
                ""","pendingAcknowledgements":[
                  {"id":"old","severity":"warning","title":"Old","startsAt":"2026-10-01T00:00:00Z","endsAt":null},
                  {"severity":"info","title":"no id"},
                  {"id":"new","severity":"nope","title":"New","startsAt":null,"endsAt":"2026-12-01T00:00:00Z"}
                ]""",
            ).pendingAcknowledgements,
        ) as PendingAcks.Known
        assertEquals(listOf("old", "new"), p.items.map { it.id })
        assertEquals(AnnouncementSeverity.WARNING, p.items[0].severity)
        assertEquals(AnnouncementSeverity.INFO, p.items[1].severity)
        assertEquals(parseInstantMs("2026-12-01T00:00:00Z"), p.items[1].endsAtMs)
    }

    @Test
    fun `a malformed pendingAcknowledgements can never break the MeResponse decode`() {
        val m = me(""","pendingAcknowledgements":"surprise"""")
        assertEquals("u1", m.id)
        assertEquals(PendingAcks.Unknown, pendingAcksOf(m.pendingAcknowledgements))
        val m2 = me(""","pendingAcknowledgements":{"id":"x"}""")
        assertEquals(PendingAcks.Unknown, pendingAcksOf(m2.pendingAcknowledgements))
    }

    @Test
    fun `a pending obligation lapses at its endsAt`() {
        val end = 1_000L
        val p = PendingAcknowledgement(id = "p", endsAtMs = end)
        assertTrue(p.isOpen(end - 1))
        assertFalse(p.isOpen(end))
        assertTrue(PendingAcknowledgement(id = "forever").isOpen(Long.MAX_VALUE - 1))
    }

    // ── The refusal, the inbox key, the realtime vocabulary ───────────────────

    @Test
    fun `a 403 ACKNOWLEDGEMENT_REQUIRED body yields its pending list`() {
        val body = """{"error":{"code":"ACKNOWLEDGEMENT_REQUIRED","message":"m","details":{"pendingAcknowledgements":[{"id":"p1","severity":"info","title":"T","startsAt":null,"endsAt":null}]}}}"""
        val r = acknowledgementRefusalOf(json, body)!!
        assertEquals(listOf("p1"), r.pending?.map { it.id })
        // The code without a usable list is still the refusal.
        assertNull(acknowledgementRefusalOf(json, """{"error":{"code":"ACKNOWLEDGEMENT_REQUIRED","message":"m"}}""")!!.pending)
        // Any other 403 is not.
        assertNull(acknowledgementRefusalOf(json, """{"error":{"code":"INSUFFICIENT_SCOPE","message":"m"}}"""))
        assertNull(acknowledgementRefusalOf(json, """{"error":{"code":"PARANOID_MODE","message":"m"}}"""))
        assertNull(acknowledgementRefusalOf(json, "<html>"))
        assertNull(acknowledgementRefusalOf(json, null))
    }

    @Test
    fun `inbox rows read announcementKind, absent on old rows`() {
        assertEquals(AnnouncementKind.ACK_REQUIRED, announcementKindOf(obj("""{"notice":"announcement","announcementId":"a","announcementKind":"ack_required"}""")))
        assertEquals(AnnouncementKind.NOTICE, announcementKindOf(obj("""{"announcementKind":"future"}""")))
        assertNull(announcementKindOf(obj("""{"notice":"announcement","announcementId":"a"}""")))
        assertNull(announcementKindOf(null))
    }

    @Test
    fun `the realtime connect error code is read from data first, then message`() {
        assertEquals(
            "ACKNOWLEDGEMENT_REQUIRED",
            SocketIoChatGateway.connectErrorCode(json, """{"message":"ACKNOWLEDGEMENT_REQUIRED","data":{"code":"ACKNOWLEDGEMENT_REQUIRED"}}"""),
        )
        assertEquals("UNAUTHORIZED", SocketIoChatGateway.connectErrorCode(json, """{"message":"UNAUTHORIZED"}"""))
        assertNull(SocketIoChatGateway.connectErrorCode(json, "garbage"))
    }

    @Test
    fun `the realtime announcement event maps to its handler, never to chat`() {
        var announced: String? = "unset"
        var gateRaised = 0
        val gateway = SocketIoChatGateway(
            apiOrigin = "http://127.0.0.1:9",
            client = okhttp3.OkHttpClient(),
            tokenProvider = { null },
            json = json,
            onReconnectSleep = {},
            onAnnouncementChanged = { announced = it },
            onAcknowledgementRequired = { gateRaised++ },
        )
        gateway.handleEvent("""["announcement.changed",{"announcementId":"a-1"}]""")
        assertEquals("a-1", announced)
        gateway.handleEvent("""["announcement.changed"]""")
        assertNull(announced)
        // Unrelated events do not reach the announcement handler.
        announced = "unset"
        gateway.handleEvent("""["portfolio.changed",{"portfolioId":"p"}]""")
        assertEquals("unset", announced)
        assertEquals(0, gateRaised)
    }

    @Test
    fun `the content locale reduces to en or de`() {
        assertEquals(LOCALE_DE, announcementLocaleOf("de-AT"))
        assertEquals(LOCALE_DE, announcementLocaleOf("DE"))
        assertEquals(LOCALE_EN, announcementLocaleOf("fr"))
        assertEquals(LOCALE_EN, announcementLocaleOf(null))
    }

    @Test
    fun `the strict acknowledge body carries exactly three fields`() {
        val body = json.encodeToString(
            AcknowledgeAnnouncementRequest.serializer(),
            AcknowledgeAnnouncementRequest(locale = "en", contentSha256 = "f".repeat(64)),
        )
        assertEquals("""{"confirm":true,"locale":"en","contentSha256":"${"f".repeat(64)}"}""", body)
    }
}
