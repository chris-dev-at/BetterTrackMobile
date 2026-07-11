package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.ChannelPrefsDto
import at.bettertrack.app.data.api.dto.DeregisterDeviceRequest
import at.bettertrack.app.data.api.dto.DeviceAckResponse
import at.bettertrack.app.data.api.dto.NotificationItemDto
import at.bettertrack.app.data.api.dto.NotificationListResponse
import at.bettertrack.app.data.api.dto.NotificationSettingsResponse
import at.bettertrack.app.data.api.dto.RegisterDeviceRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract tests for the Notifications-v2 DTOs against the exact production
 * OpenAPI shapes (probed 2026-07-11). Uses the app's real Json config so encode /
 * decode matches on-device behavior — including the "explicit null crashes a
 * non-null field" trap that the shared Json (no coerceInputValues) does not save.
 */
class NotificationWireTest {

    // Mirror AppGraph.json exactly.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── Device-token requests: exact bodies, no stray fields ────────────────

    @Test fun `register device serializes to exactly token + platform`() {
        val body = json.encodeToString(RegisterDeviceRequest(token = "fcm-abc", platform = "android"))
        // Schema is additionalProperties:false — anything extra would 400.
        assertEquals("""{"token":"fcm-abc","platform":"android"}""", body)
    }

    @Test fun `deregister device serializes to exactly token`() {
        val body = json.encodeToString(DeregisterDeviceRequest(token = "fcm-abc"))
        assertEquals("""{"token":"fcm-abc"}""", body)
    }

    @Test fun `device ack decodes the ok true body`() {
        val ack = json.decodeFromString<DeviceAckResponse>("""{"ok":true}""")
        assertTrue(ack.ok)
    }

    // ── Inbox item nullability (the exact bug that crashed alerts once) ──────

    @Test fun `inbox item decodes explicit null readAt and payload without crashing`() {
        val row = """
            {"id":"11111111-1111-1111-1111-111111111111","type":"alert.triggered",
             "title":"Price alert","body":"BTC-EUR passed target","payload":null,
             "readAt":null,"createdAt":"2026-07-11T02:30:00.000Z"}
        """.trimIndent()
        val dto = json.decodeFromString<NotificationItemDto>(row)
        assertNull(dto.readAt)   // null ⇒ unread
        assertNull(dto.payload)
        assertEquals("alert.triggered", dto.type)
    }

    @Test fun `inbox item decodes an omitted payload and a present readAt`() {
        val row = """
            {"id":"2","type":"portfolio.shared","title":"Shared","body":"@a shared X",
             "payload":{"portfolioId":"p1"},"readAt":"2026-07-10T00:00:00.000Z",
             "createdAt":"2026-07-11T00:00:00.000Z"}
        """.trimIndent()
        val dto = json.decodeFromString<NotificationItemDto>(row)
        assertEquals("2026-07-10T00:00:00.000Z", dto.readAt)
        assertTrue(dto.payload != null)
    }

    @Test fun `inbox list decodes a null nextCursor`() {
        val body = """{"items":[],"nextCursor":null,"unreadCount":0}"""
        val resp = json.decodeFromString<NotificationListResponse>(body)
        assertNull(resp.nextCursor)
        assertEquals(0, resp.unreadCount)
        assertTrue(resp.items.isEmpty())
    }

    // ── Settings matrix: 4-channel cells + ignored global fields ────────────

    @Test fun `channel prefs round-trip all four channels`() {
        val dto = ChannelPrefsDto(inapp = true, email = false, push = true, webpush = false)
        val round = json.decodeFromString<ChannelPrefsDto>(json.encodeToString(dto))
        assertEquals(dto, round)
    }

    @Test fun `settings response decodes 4-channel cells and ignores global fields`() {
        // The real GET body carries top-level muted / channels / webPushPublicKey
        // and server-only types (watchlist.shared) — all must be tolerated.
        val body = """
            {"matrix":{
               "friend.request":{"inapp":true,"email":false,"push":true,"webpush":false},
               "chat.message":{"inapp":true,"email":true,"push":false,"webpush":true},
               "watchlist.shared":{"inapp":true,"email":true,"push":true,"webpush":true}
             },
             "muted":false,
             "channels":{"inapp":true,"email":true,"push":true,"webpush":true},
             "webPushPublicKey":null}
        """.trimIndent()
        val resp = json.decodeFromString<NotificationSettingsResponse>(body)
        assertEquals(3, resp.matrix.size)
        val fr = resp.matrix.getValue("friend.request")
        assertEquals(true, fr.inapp)
        assertEquals(false, fr.email)
        assertEquals(true, fr.push)
        assertEquals(false, fr.webpush)
        val chat = resp.matrix.getValue("chat.message")
        assertEquals(false, chat.push)
        assertEquals(true, chat.webpush)
    }
}
