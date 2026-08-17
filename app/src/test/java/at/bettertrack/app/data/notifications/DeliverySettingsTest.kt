package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.NotificationSettingsResponse
import at.bettertrack.app.data.api.dto.UpdateNotificationSettingsRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** JUnit4's assertNotNull returns void; this one narrows the type so the test can go on. */
private fun <T : Any> T?.orFail(what: String): T = this ?: throw AssertionError("expected a $what, got null")

/**
 * Digest cadence + quiet hours (platform v5) — wire contract and pure logic.
 *
 * The server schema is `.strict()` at EVERY level and rejects an empty `{}` body,
 * so the exact bytes the app PATCHes matter more than usual. These tests pin them
 * literally, using the app's real Json configuration.
 */
class DeliverySettingsTest {

    // Mirror AppGraph.json exactly.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Every type, all `instant` — the shape a fresh account returns. */
    private val allInstant: Map<String, String> =
        NotifCatalog.allTypes.associateWith { "instant" }

    private val serverQuietHours = QuietHours(
        enabled = false,
        startMinute = 1320,
        endMinute = 420,
        timezone = "Europe/Vienna",
    )

    // ── Echo-verbatim: a pre-v5 server must never see either new key ────────────

    @Test
    fun `a pre-v5 GET carries no cadence and no quietHours`() {
        val body = """
            {"matrix":{"friend.request":{"inapp":true,"email":true,"push":true,"webpush":true}},
             "muted":false,
             "channels":{"inapp":true,"email":true,"push":true,"webpush":true}}
        """.trimIndent()
        val resp = json.decodeFromString<NotificationSettingsResponse>(body)
        assertNull(resp.cadence)
        assertNull(resp.quietHours)
        // …which is what hides the whole Delivery section.
        assertFalse(DeliveryState(resp.cadence, resp.quietHours?.toQuietHours()).supported)
    }

    @Test
    fun `a pre-v5 GET round-trips to a PATCH body containing NEITHER key`() {
        val resp = json.decodeFromString<NotificationSettingsResponse>(
            """{"matrix":{"friend.request":{"inapp":true,"email":false,"push":true,"webpush":false}}}""",
        )
        val state = DeliveryState(resp.cadence, resp.quietHours?.toQuietHours())

        // Neither user action can produce a patch against a server that modelled nothing.
        assertNull(typeCadencePatch(state.cadence, "friend.request", DigestCadence.Daily))
        assertNull(state.quietHours)

        // The matrix patch the screen still sends must carry ONLY `matrix`.
        val body = json.encodeToString(
            UpdateNotificationSettingsRequest(
                matrix = mapOf("friend.request" to resp.matrix.getValue("friend.request")),
            ),
        )
        assertEquals(
            """{"matrix":{"friend.request":{"inapp":true,"email":false,"push":true,"webpush":false}}}""",
            body,
        )
        assertFalse(body.contains("cadence"))
        assertFalse(body.contains("quietHours"))
    }

    // ── v5 GET decoding ─────────────────────────────────────────────────────────

    @Test
    fun `a v5 GET decodes cadence and the quiet-hours defaults`() {
        val body = """
            {"matrix":{"friend.request":{"inapp":true,"email":true,"push":true,"webpush":true,"telegram":false,"discord":false}},
             "cadence":{"friend.request":"daily","alert.triggered":"instant"},
             "quietHours":{"enabled":false,"startMinute":1320,"endMinute":420,"timezone":null},
             "muted":false,
             "channels":{"inapp":true,"email":true,"telegram":false,"discord":false,"push":true,"webpush":true},
             "webPushPublicKey":null}
        """.trimIndent()
        val resp = json.decodeFromString<NotificationSettingsResponse>(body)
        assertEquals("daily", resp.cadence?.get("friend.request"))
        // An explicit null timezone must decode, not crash (no coerceInputValues).
        val qh = resp.quietHours.orFail("quietHours object").toQuietHours()
        assertFalse(qh.enabled)
        assertEquals(1320, qh.startMinute)
        assertEquals(420, qh.endMinute)
        assertNull(qh.timezone)
        assertTrue(DeliveryState(resp.cadence, qh).supported)
    }

    // ── Cadence patches ─────────────────────────────────────────────────────────

    // Cadence is PER TYPE since the 2026-08-17 parity rebuild. The retired group
    // chooser patched 18 keys at once from an editorial "digestible" list the
    // server never agreed with; these pin the single-key shape that replaced it.

    @Test
    fun `a cadence pick sends exactly one type key and nothing else`() {
        val patch = typeCadencePatch(allInstant, "portfolio.shared", DigestCadence.Daily)
            .orFail("cadence patch")
        assertEquals(mapOf("portfolio.shared" to "daily"), patch)

        val body = json.encodeToString(UpdateNotificationSettingsRequest(cadence = patch))
        assertEquals("""{"cadence":{"portfolio.shared":"daily"}}""", body)
        assertFalse(body.contains("matrix"))
        assertFalse(body.contains("quietHours"))
        assertFalse(body.contains("muted"))
    }

    @Test
    fun `a no-op cadence pick produces no patch at all`() {
        // An empty `{}` body is itself a 400 — the app must not send anything.
        assertNull(typeCadencePatch(allInstant, "friend.request", DigestCadence.Instant))
    }

    @Test
    fun `cadence only ever names a type the GET actually carried`() {
        val partial = mapOf("friend.request" to "instant")
        assertEquals(
            mapOf("friend.request" to "weekly"),
            typeCadencePatch(partial, "friend.request", DigestCadence.Weekly),
        )
        // The server never sent this one, so there is no stored value to change and
        // naming it in a strict-map PATCH would be inventing state.
        assertNull(typeCadencePatch(partial, "alert.triggered", DigestCadence.Weekly))
    }

    @Test
    fun `account invite is never given a cadence`() {
        // The web filters it out client-side (no per-user routing ⇒ no meaningful
        // cadence) even though the server would accept one. Same rule here.
        assertFalse(NotifCatalog.ACCOUNT_INVITE in NotifCatalog.cadenceTypes)
        assertNull(typeCadencePatch(allInstant, NotifCatalog.ACCOUNT_INVITE, DigestCadence.Daily))
    }

    @Test
    fun `every type the platform batches can be given all three cadences`() {
        // The whole point of the rebuild: no type is silently denied a control the
        // account genuinely has. 26 types minus account.invite.
        assertEquals(25, NotifCatalog.cadenceTypes.size)
        for (type in NotifCatalog.cadenceTypes) {
            for (choice in listOf(DigestCadence.Daily, DigestCadence.Weekly)) {
                assertEquals(
                    "$type must be settable to ${choice.wire}",
                    mapOf(type to choice.wire),
                    typeCadencePatch(allInstant, type, choice),
                )
            }
        }
        // Including the seven the old editorial split refused to offer at all.
        listOf(
            "alert.triggered", "account.temp_password", "account.data_export",
            "chat.message", "mirror.invite", "mirror.sync_stalled",
        ).forEach {
            assertTrue("$it lost its cadence control", it in NotifCatalog.cadenceTypes)
        }
    }

    @Test
    fun `the timing summary reads back a shared cadence and reports mixed otherwise`() {
        val types = NotifCatalog.allTypes
        assertEquals(DigestCadence.Instant, sharedCadence(allInstant, types))
        assertEquals(
            DigestCadence.Daily,
            sharedCadence(allInstant.mapValues { "daily" }, types),
        )
        // One type differs ⇒ "Set per type", not a lie about which value is active.
        assertNull(sharedCadence(allInstant + mapOf("friend.request" to "weekly"), types))
        // Pre-v5 ⇒ nothing to show.
        assertNull(sharedCadence(null, types))
        // account.invite is excluded from the summary too, so a stray value on it
        // cannot make an otherwise-uniform account read as mixed.
        assertEquals(
            DigestCadence.Instant,
            sharedCadence(allInstant + mapOf(NotifCatalog.ACCOUNT_INVITE to "weekly"), types),
        )
    }

    // ── Quiet-hours patches (the one field-partial object in the schema) ─────────

    @Test
    fun `changing only enabled produces exactly the enabled key`() {
        val patch = quietHoursPatch(serverQuietHours, serverQuietHours.copy(enabled = true)).orFail("quiet-hours patch")
        assertEquals(
            """{"quietHours":{"enabled":true}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(quietHours = patch)),
        )
    }

    @Test
    fun `turning quiet hours off also sends exactly one key`() {
        val on = serverQuietHours.copy(enabled = true)
        val patch = quietHoursPatch(on, on.copy(enabled = false)).orFail("quiet-hours patch")
        assertEquals(
            """{"quietHours":{"enabled":false}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(quietHours = patch)),
        )
    }

    @Test
    fun `changing one window edge sends only that minute`() {
        val patch = quietHoursPatch(serverQuietHours, serverQuietHours.copy(startMinute = 1350)).orFail("quiet-hours patch")
        assertEquals(
            """{"quietHours":{"startMinute":1350}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(quietHours = patch)),
        )
        val end = quietHoursPatch(serverQuietHours, serverQuietHours.copy(endMinute = 0)).orFail("quiet-hours patch")
        assertEquals(
            """{"quietHours":{"endMinute":0}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(quietHours = end)),
        )
    }

    @Test
    fun `enabling and picking a zone in one go sends both keys`() {
        // NOTE: the app no longer PRODUCES this combination on its own — enabling
        // quiet hours used to smuggle the device zone into the same patch, and that
        // auto-injection is gone. The diff must still handle two changed fields.
        val noZone = serverQuietHours.copy(timezone = null)
        val patch = quietHoursPatch(noZone, noZone.copy(enabled = true, timezone = "Europe/Vienna"))
            .orFail("quiet-hours patch")
        assertEquals(
            """{"quietHours":{"enabled":true,"timezone":"Europe/Vienna"}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(quietHours = patch)),
        )
    }

    @Test
    fun `a no-op quiet-hours change produces no patch`() {
        assertNull(quietHoursPatch(serverQuietHours, serverQuietHours))
        // Both zones already absent ⇒ still nothing to say.
        val noZone = serverQuietHours.copy(timezone = null)
        assertNull(quietHoursPatch(noZone, noZone))
    }

    // ── SharedPreferences codec for the cadence tri-state ───────────────────────

    @Test
    fun `the cadence cache round-trips and keeps the never-modelled tri-state`() {
        val cadence = mapOf("friend.request" to "daily", "alert.triggered" to "instant")
        assertEquals(cadence, decodeCadence(encodeCadence(cadence)))
        assertNull("no stored set must stay null (pre-v5), not an empty map", decodeCadence(null))
        assertEquals(emptyMap<String, String>(), decodeCadence(emptySet()))
    }

    // ── Minute-of-day ↔ display time ────────────────────────────────────────────

    @Test
    fun `minutes format in 24-hour and 12-hour form including the boundaries`() {
        assertEquals("22:00", formatMinuteOfDay(1320, use24Hour = true, locale = Locale.US))
        assertEquals("07:00", formatMinuteOfDay(420, use24Hour = true, locale = Locale.US))
        // 0 and 1439 are the exact ends of the server's range.
        assertEquals("00:00", formatMinuteOfDay(0, use24Hour = true, locale = Locale.US))
        assertEquals("23:59", formatMinuteOfDay(1439, use24Hour = true, locale = Locale.US))
        assertEquals("10:00 PM", formatMinuteOfDay(1320, use24Hour = false, locale = Locale.US))
        assertEquals("7:00 AM", formatMinuteOfDay(420, use24Hour = false, locale = Locale.US))
        assertEquals("12:00 AM", formatMinuteOfDay(0, use24Hour = false, locale = Locale.US))
        assertEquals("11:59 PM", formatMinuteOfDay(1439, use24Hour = false, locale = Locale.US))
        assertEquals("12:30 PM", formatMinuteOfDay(750, use24Hour = false, locale = Locale.US))
    }

    @Test
    fun `minute-of-day components and the picker inverse agree`() {
        assertEquals(22, hourOfMinuteOfDay(1320))
        assertEquals(0, minuteOfMinuteOfDay(1320))
        assertEquals(23, hourOfMinuteOfDay(1439))
        assertEquals(59, minuteOfMinuteOfDay(1439))
        assertEquals(1320, minuteOfDayOf(22, 0))
        assertEquals(0, minuteOfDayOf(0, 0))
        assertEquals(1439, minuteOfDayOf(23, 59))
        // Wrap-around never escapes 0..1439 (the server range).
        assertEquals(0, minuteOfDayOf(24, 0))
        assertEquals(1380, normalizeMinuteOfDay(-60))
        assertEquals(0, normalizeMinuteOfDay(MINUTES_PER_DAY))
    }

    @Test
    fun `an overnight window is labelled and a same-day one is not`() {
        val overnight = quietWindowDisplay(1320, 420, use24Hour = true, locale = Locale.GERMANY)
        assertEquals("22:00", overnight.start)
        assertEquals("07:00", overnight.end)
        assertTrue(overnight.overnight)

        val sameDay = quietWindowDisplay(540, 1020, use24Hour = true, locale = Locale.GERMANY)
        assertFalse(sameDay.overnight)

        // Boundary cases: equal ends are not overnight; 1439→0 is.
        assertFalse(isOvernightWindow(0, 0))
        assertFalse(isOvernightWindow(0, 1439))
        assertTrue(isOvernightWindow(1439, 0))
    }
}
