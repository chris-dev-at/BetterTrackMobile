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

    /** The 25 v5 types, all `instant` — the shape a fresh v5 account returns. */
    private val allInstant: Map<String, String> =
        (DeliveryTypes.digestible + DeliveryTypes.alwaysInstant).associateWith { "instant" }

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
        assertNull(cadencePatch(state.cadence, DigestCadence.Daily))
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

    @Test
    fun `picking Daily patches exactly the digestible group and no urgent type`() {
        val patch = cadencePatch(allInstant, DigestCadence.Daily).orFail("cadence patch")
        assertEquals(DeliveryTypes.digestible.toSet(), patch.keys)
        assertTrue(patch.values.all { it == "daily" })
        DeliveryTypes.alwaysInstant.forEach { assertFalse("$it must never be batched", it in patch.keys) }

        val body = json.encodeToString(UpdateNotificationSettingsRequest(cadence = patch))
        assertTrue(body.startsWith("""{"cadence":{"""))
        assertFalse(body.contains("matrix"))
        assertFalse(body.contains("quietHours"))
        assertFalse(body.contains("alert.triggered"))
        assertFalse(body.contains("account.temp_password"))
        assertFalse(body.contains("chat.message"))
    }

    @Test
    fun `a cadence change sends only the changed type key`() {
        // Everything digestible is already daily except one type.
        val server = allInstant.toMutableMap()
        DeliveryTypes.digestible.forEach { server[it] = "daily" }
        server["portfolio.shared"] = "weekly"

        val patch = cadencePatch(server, DigestCadence.Daily).orFail("cadence patch")
        assertEquals(mapOf("portfolio.shared" to "daily"), patch)
        assertEquals(
            """{"cadence":{"portfolio.shared":"daily"}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(cadence = patch)),
        )
    }

    @Test
    fun `a no-op cadence pick produces no patch at all`() {
        // An empty `{}` body is itself a 400 — the app must not send anything.
        assertNull(cadencePatch(allInstant, DigestCadence.Instant))
    }

    @Test
    fun `cadence only ever names types the GET actually carried`() {
        val partial = mapOf("friend.request" to "instant", "alert.triggered" to "instant")
        val patch = cadencePatch(partial, DigestCadence.Weekly).orFail("cadence patch")
        assertEquals(mapOf("friend.request" to "weekly"), patch)
    }

    @Test
    fun `the group cadence reads back what the server holds and reports mixed`() {
        assertEquals(DigestCadence.Instant, groupCadence(allInstant))
        assertEquals(DigestCadence.Daily, groupCadence(allInstant + cadencePatch(allInstant, DigestCadence.Daily)!!))
        // Mixed group ⇒ no segment is selected.
        assertNull(groupCadence(allInstant + mapOf("friend.request" to "weekly")))
        // Pre-v5 ⇒ nothing to show.
        assertNull(groupCadence(null))
    }

    @Test
    fun `every one of the 25 v5 types is classified exactly once`() {
        val all = listOf(
            "friend.request", "friend.accepted", "portfolio.shared", "watchlist.shared",
            "conglomerate.shared", "friend.activity", "follow.published", "follow.alert.created",
            "follow.alert.fired", "account.invite", "account.temp_password", "account.data_export",
            "alert.triggered", "earnings.reminder", "chat.message", "dividend.event",
            "budget.exceeded", "mirror.invite", "mirror.member_joined", "mirror.member_left",
            "mirror.member_removed", "mirror.removed", "mirror.ownership_transferred",
            "mirror.chain_dissolved", "mirror.sync_stalled",
        )
        assertEquals(25, all.size)
        assertEquals(all.toSet(), DeliveryTypes.digestible.toSet() + DeliveryTypes.alwaysInstant)
        assertTrue(DeliveryTypes.digestible.none { it in DeliveryTypes.alwaysInstant })
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
    fun `enabling with no server timezone carries the device zone in the same patch`() {
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
        // A null timezone is never diffed down — the app cannot clear one it did not set.
        assertNull(quietHoursPatch(serverQuietHours, serverQuietHours.copy(timezone = null)))
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
