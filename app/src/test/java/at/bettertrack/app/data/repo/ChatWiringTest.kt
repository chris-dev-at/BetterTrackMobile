package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.dto.ChatChipDto
import at.bettertrack.app.data.api.dto.ChatConversationDto
import at.bettertrack.app.data.api.dto.ChatMessageDto
import at.bettertrack.app.data.api.dto.ChatMessagePreviewDto
import at.bettertrack.app.data.api.dto.SocialUserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the LIVE chat wiring (Step 15, §6.10): DTO→domain mapping,
 * per-viewer chip resolution, cursor-page merge (dedup + chronology), unread math,
 * preview formatting, and the realtime gateway's URL/backoff. All Context-free.
 */
class ChatWiringTest {

    private fun msg(id: String, ms: Long, from: String = "u1", conv: String = "c1", body: String? = null) =
        ChatMessage(id = id, conversationId = conv, fromMe = from == "me", body = body, chip = null, sentAtMs = ms)

    // ── mergeMessages: dedup + chronological ordering (UUIDv7 id tiebreak) ───────

    @Test
    fun merge_sorts_oldest_to_newest() {
        val merged = mergeMessages(listOf(msg("m2", 200)), listOf(msg("m1", 100)))
        assertEquals(listOf("m1", "m2"), merged.map { it.id })
    }

    @Test
    fun merge_dedups_by_id_incoming_wins() {
        val old = msg("m1", 100, body = "old")
        val fresh = msg("m1", 100, body = "new")
        val merged = mergeMessages(listOf(old), listOf(fresh))
        assertEquals(1, merged.size)
        assertEquals("new", merged[0].body) // incoming wins ⇒ re-resolved chip replaces stale
    }

    @Test
    fun merge_prepends_older_page_without_duplication() {
        val existing = listOf(msg("m3", 300), msg("m4", 400)) // current newest page
        val older = listOf(msg("m1", 100), msg("m2", 200))    // fetched older page
        val merged = mergeMessages(existing, older)
        assertEquals(listOf("m1", "m2", "m3", "m4"), merged.map { it.id })
    }

    @Test
    fun merge_empty_incoming_returns_existing() {
        val existing = listOf(msg("m1", 100))
        assertEquals(existing, mergeMessages(existing, emptyList()))
    }

    // ── unread math ──────────────────────────────────────────────────────────────

    @Test
    fun total_unread_sums_conversations() {
        val list = listOf(
            Conversation("c1", "u1", "a", "p", 10L, unread = 2),
            Conversation("c2", "u2", "b", "p", 20L, unread = 3),
            Conversation("c3", "u3", "c", "p", 30L, unread = 0),
        )
        assertEquals(5, conversationsToTotalUnread(list))
    }

    @Test
    fun conversations_sorted_newest_first() {
        val a = Conversation("c1", "u1", "a", "", 100L, 0)
        val b = Conversation("c2", "u2", "b", "", 300L, 0)
        val c = Conversation("c3", "u3", "c", "", 200L, 0)
        assertEquals(listOf("c2", "c3", "c1"), sortConversations(listOf(a, b, c)).map { it.id })
    }

    // ── chip resolution mapping ──────────────────────────────────────────────────

    @Test
    fun chip_viewable_portfolio_maps_name_and_owner() {
        val c = ChatChipDto(kind = "portfolio", subjectId = "p1", viewable = true, title = "Main", subtitle = "anna_m").toDomain()
        assertEquals(ShareChipKind.Portfolio, c.kind)
        assertEquals("p1", c.refId)
        assertEquals("Main", c.label)
        assertNull(c.symbol)
        assertEquals("anna_m", c.ownerName)
        assertTrue(c.viewable)
    }

    @Test
    fun chip_viewable_asset_puts_symbol_in_symbol_slot() {
        val c = ChatChipDto(kind = "asset", subjectId = "a1", viewable = true, title = "AAPL", subtitle = "Apple Inc.").toDomain()
        assertEquals(ShareChipKind.Asset, c.kind)
        assertEquals("AAPL", c.symbol)
        assertEquals("Apple Inc.", c.ownerName)
    }

    @Test
    fun chip_not_viewable_leaks_nothing() {
        val c = ChatChipDto(kind = "conglomerate", subjectId = "x", viewable = false, title = null, subtitle = null).toDomain()
        assertEquals(ShareChipKind.Conglomerate, c.kind)
        assertFalse(c.viewable)
        assertEquals("", c.label)
        assertNull(c.symbol)
        assertNull(c.ownerName)
    }

    // ── message + conversation DTO → domain ──────────────────────────────────────

    @Test
    fun message_from_me_detected_by_sender_id() {
        val dto = ChatMessageDto("m1", "c1", "me", body = "hi", chip = null, createdAt = "2026-07-09T20:00:00.000Z")
        assertTrue(dto.toDomain("me").fromMe)
        assertFalse(dto.toDomain("someone-else").fromMe)
        assertTrue(dto.toDomain("me").sentAtMs > 0L)
    }

    @Test
    fun conversation_preview_from_chip_kind_is_i18n_safe() {
        val prev = ChatMessagePreviewDto(senderId = "me", body = null, chipKind = "portfolio", createdAt = "2026-07-09T20:00:00.000Z")
        val dto = ChatConversationDto("c1", SocialUserDto("u1", "anna"), unreadCount = 3, lastMessage = prev, lastMessageAt = "2026-07-09T20:00:00.000Z")
        val c = dto.toDomain("me")
        assertEquals("u1", c.friendUserId)
        assertEquals("anna", c.friendUsername)
        assertEquals(3, c.unread)
        assertEquals("You: 📎 Shared a portfolio", c.lastPreview) // never the item's name
    }

    @Test
    fun conversation_incoming_text_preview_plain() {
        val prev = ChatMessagePreviewDto(senderId = "u1", body = "hey there", chipKind = null, createdAt = "2026-07-09T20:00:00.000Z")
        val dto = ChatConversationDto("c1", SocialUserDto("u1", "anna"), unreadCount = 1, lastMessage = prev, lastMessageAt = "2026-07-09T20:00:00.000Z")
        assertEquals("hey there", dto.toDomain("me").lastPreview)
    }

    @Test
    fun conversation_empty_thread_has_recent_timestamp() {
        val dto = ChatConversationDto("c1", SocialUserDto("u1", "anna"), unreadCount = 0, lastMessage = null, lastMessageAt = null)
        val c = dto.toDomain("me")
        assertEquals("", c.lastPreview)
        assertTrue(c.lastAtMs > 0L) // sorts as "just now" rather than epoch 0
    }

    @Test
    fun chip_kind_phrase_never_leaks_name() {
        assertEquals("a portfolio", chipKindPhrase("portfolio"))
        assertEquals("an asset", chipKindPhrase("asset"))
        assertEquals("a watchlist", chipKindPhrase("watchlist"))
        assertEquals("a basket", chipKindPhrase("conglomerate"))
        assertEquals("an item", chipKindPhrase("something-new"))
    }

    // ── iso parsing ──────────────────────────────────────────────────────────────

    @Test
    fun iso_parsing_is_tolerant() {
        assertTrue(isoToEpochMs("2026-07-09T20:00:00.000Z") > 0L)
        assertEquals(0L, isoToEpochMs(null))
        assertEquals(0L, isoToEpochMs(""))
        assertEquals(0L, isoToEpochMs("not-a-date"))
    }

    // ── realtime gateway: URL + backoff ──────────────────────────────────────────

    @Test
    fun ws_url_derives_from_api_origin() {
        assertEquals(
            "wss://api.bettertrack.at/ws/?EIO=4&transport=websocket",
            SocketIoChatGateway.buildWsUrl("https://api.bettertrack.at"),
        )
        assertEquals(
            "ws://10.0.2.2:3000/ws/?EIO=4&transport=websocket",
            SocketIoChatGateway.buildWsUrl("http://10.0.2.2:3000/"),
        )
    }

    @Test
    fun backoff_is_capped_exponential() {
        assertEquals(1000L, SocketIoChatGateway.backoffMs(0))
        assertEquals(2000L, SocketIoChatGateway.backoffMs(1))
        assertEquals(4000L, SocketIoChatGateway.backoffMs(2))
        assertEquals(30_000L, SocketIoChatGateway.backoffMs(10))
    }

    // ── Contract-nullable decode hardening (#362 deleted accounts) ────────────────
    // Regression pins for the owner-reported "thread history doesn't load" bug:
    // one null `senderId` (or a deleted participant) must never fail a whole page.

    private val wireJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test
    fun thread_page_with_deleted_sender_decodes_and_maps() {
        val dto = wireJson.decodeFromString(
            at.bettertrack.app.data.api.dto.ChatThreadResponse.serializer(),
            """{
              "conversation": {"id":"c1","user":{"id":"u2","username":"anna"},"unreadCount":0,
                               "lastMessage":null,"lastMessageAt":null},
              "messages": [
                {"id":"m1","conversationId":"c1","senderId":null,"body":"hi","chip":null,
                 "createdAt":"2026-07-01T10:00:00.000Z"},
                {"id":"m2","conversationId":"c1","senderId":"u2","body":"hello","chip":null,
                 "createdAt":"2026-07-01T10:01:00.000Z"}
              ],
              "nextCursor": null
            }""",
        )
        assertEquals(2, dto.messages.size)
        val anon = dto.messages[0].toDomain(myUserId = "me")
        assertFalse(anon.fromMe)
        assertEquals("hi", anon.body)
    }

    @Test
    fun conversation_list_with_deleted_participant_decodes_read_only() {
        val resp = wireJson.decodeFromString(
            at.bettertrack.app.data.api.dto.ChatConversationListResponse.serializer(),
            """{"conversations":[
                 {"id":"c9","user":null,"unreadCount":1,
                  "lastMessage":{"senderId":null,"body":"bye","chipKind":null,
                                 "createdAt":"2026-06-30T09:00:00.000Z"},
                  "lastMessageAt":"2026-06-30T09:00:00.000Z"}
               ],"unreadTotal":1}""",
        )
        val conv = resp.conversations.single().toDomain(myUserId = "me")
        assertEquals("c9", conv.id)
        assertEquals("", conv.friendUserId) // empty id ⇒ read-only computation kicks in
        assertEquals("deleted", conv.friendUsername)
        assertEquals(1, conv.unread)
    }
}
