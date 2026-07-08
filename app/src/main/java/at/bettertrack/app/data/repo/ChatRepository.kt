package at.bettertrack.app.data.repo

import android.content.Context
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Friend chat (Step 15, §6.10). The platform has NO chat endpoints yet — this is
 * a UI-first pre-build on a clean [ChatRepository] seam + a [ChatGateway] realtime
 * abstraction (socket-or-poll), backed ENTIRELY by an in-memory stub in debug and
 * a "coming soon" state in release. DTO/model shapes mirror web PROJECTPLAN V3-P8
 * so the real adapter is a thin swap. No groups/reactions/read-receipts (spec).
 */

/** Feature flag — the whole chat surface is stub until the platform ships it. */
object ChatFlags {
    /** Debug: simulated conversations + a live incoming message to demo badges. */
    val enabled: Boolean = BuildConfig.DEBUG
}

enum class ShareChipKind { Asset, Portfolio, Watchlist, Conglomerate }

data class ShareChip(
    val kind: ShareChipKind,
    val refId: String,
    val label: String,
    val symbol: String? = null,
    val ownerName: String? = null,
    /** Recipient resolution — false ⇒ "not shared with you" (never leaks data). */
    val viewable: Boolean = true,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val fromMe: Boolean,
    val body: String? = null,
    val chip: ShareChip? = null,
    val sentAtMs: Long,
)

data class Conversation(
    val id: String,
    val friendUserId: String,
    val friendUsername: String,
    val lastPreview: String,
    val lastAtMs: Long,
    val unread: Int,
)

/**
 * Realtime delivery abstraction (§4.5 gateway or polling fallback). The real impl
 * will bridge the authenticated WebSocket `/ws` (room `user:{id}`) to [onMessage];
 * the stub simulates one incoming push to prove the badge/unread plumbing.
 */
interface ChatGateway {
    fun connect(scope: CoroutineScope, onMessage: (ChatMessage) -> Unit)
    fun disconnect()
}

interface ChatRepository {
    val conversations: StateFlow<List<Conversation>>
    val totalUnread: StateFlow<Int>

    fun messages(conversationId: String): StateFlow<List<ChatMessage>>

    suspend fun send(conversationId: String, body: String?, chip: ShareChip?): BtResult<Unit>
    suspend fun markRead(conversationId: String)

    /** Open (or lazily create) the 1:1 conversation with a friend; returns its id. */
    suspend fun conversationWith(friendUserId: String, friendUsername: String): BtResult<String>

    /** Shareable items the user can attach as chips (their own + a couple of assets). */
    fun attachables(): List<ShareChip>

    fun connectRealtime(scope: CoroutineScope)
    fun disconnectRealtime()
}

/** Stub chat backed by in-memory state; read-state persists so unread survives restart. */
class StubChatRepository(
    context: Context,
    private val gateway: ChatGateway,
) : ChatRepository {

    private val prefs = context.getSharedPreferences("bt_chat_stub", Context.MODE_PRIVATE)

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations

    private val _totalUnread = MutableStateFlow(0)
    override val totalUnread: StateFlow<Int> = _totalUnread

    private val messageStores = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    init { if (ChatFlags.enabled) seed() }

    private fun store(id: String): MutableStateFlow<List<ChatMessage>> =
        messageStores.getOrPut(id) { MutableStateFlow(emptyList()) }

    override fun messages(conversationId: String): StateFlow<List<ChatMessage>> = store(conversationId)

    override suspend fun send(conversationId: String, body: String?, chip: ShareChip?): BtResult<Unit> {
        if (!ChatFlags.enabled) return comingSoon()
        if (body.isNullOrBlank() && chip == null) return BtResult.Ok(Unit)
        val now = System.currentTimeMillis()
        val msg = ChatMessage(UUID.randomUUID().toString(), conversationId, fromMe = true, body = body?.trim(), chip = chip, sentAtMs = now)
        store(conversationId).value = store(conversationId).value + msg
        bumpConversation(conversationId, preview = previewOf(msg), atMs = now, incomingUnread = false)
        return BtResult.Ok(Unit)
    }

    override suspend fun markRead(conversationId: String) {
        setRead(conversationId, true)
        _conversations.value = _conversations.value.map {
            if (it.id == conversationId) it.copy(unread = 0) else it
        }
        recomputeTotal()
    }

    override suspend fun conversationWith(friendUserId: String, friendUsername: String): BtResult<String> {
        if (!ChatFlags.enabled) return comingSoon()
        val existing = _conversations.value.firstOrNull { it.friendUserId == friendUserId }
        if (existing != null) return BtResult.Ok(existing.id)
        val id = "c-" + friendUserId.take(8)
        store(id).value = emptyList()
        _conversations.value = listOf(
            Conversation(id, friendUserId, friendUsername, "Say hi 👋", System.currentTimeMillis(), unread = 0),
        ) + _conversations.value
        return BtResult.Ok(id)
    }

    override fun attachables(): List<ShareChip> = listOf(
        ShareChip(ShareChipKind.Asset, "asset-aapl", "Apple", symbol = "AAPL"),
        ShareChip(ShareChipKind.Asset, "asset-nvda", "NVIDIA", symbol = "NVDA"),
        ShareChip(ShareChipKind.Portfolio, "pf-main", "My Main portfolio", ownerName = "you"),
        ShareChip(ShareChipKind.Conglomerate, "cg-demo", "My Tech basket", ownerName = "you"),
    )

    override fun connectRealtime(scope: CoroutineScope) {
        if (!ChatFlags.enabled) return
        gateway.connect(scope) { incoming ->
            store(incoming.conversationId).value = store(incoming.conversationId).value + incoming
            bumpConversation(incoming.conversationId, previewOf(incoming), incoming.sentAtMs, incomingUnread = true)
        }
    }

    override fun disconnectRealtime() = gateway.disconnect()

    // ── stub internals ───────────────────────────────────────────────────────

    private fun seed() {
        val now = System.currentTimeMillis()
        val min = 60_000L
        // Conversation with anna_m: unread, includes a viewable + a not-shared chip.
        val annaId = "c-anna"
        store(annaId).value = listOf(
            ChatMessage("m1", annaId, fromMe = false, body = "Hey! Did you see my portfolio? Up nicely this week 📈", sentAtMs = now - 40 * min),
            ChatMessage("m2", annaId, fromMe = true, body = "Nice! Send it over", sentAtMs = now - 38 * min),
            ChatMessage(
                "m3", annaId, fromMe = false,
                chip = ShareChip(ShareChipKind.Portfolio, "pf-anna", "Anna's Main", ownerName = "anna_m", viewable = true),
                body = null, sentAtMs = now - 37 * min,
            ),
            ChatMessage(
                "m4", annaId, fromMe = false,
                chip = ShareChip(ShareChipKind.Conglomerate, "cg-anna-priv", "Anna's private idea", ownerName = "anna_m", viewable = false),
                body = null, sentAtMs = now - 12 * min,
            ),
            ChatMessage("m5", annaId, fromMe = false, body = "Oops that one's private 🙈 the first link works though", sentAtMs = now - 11 * min),
        )
        // Conversation with lukas.k: fully read, last from me.
        val lukasId = "c-lukas"
        store(lukasId).value = listOf(
            ChatMessage("l1", lukasId, fromMe = false, body = "Thanks for the AAPL tip!", sentAtMs = now - 3 * 24 * 60 * min),
            ChatMessage("l2", lukasId, fromMe = true, body = "Anytime 🙌", sentAtMs = now - 3 * 24 * 60 * min + min),
        )
        val convs = listOf(
            Conversation(annaId, "u-anna", "anna_m", "Oops that one's private 🙈 the first link…", now - 11 * min, unread = readUnread(annaId, 2)),
            Conversation(lukasId, "u-lukas", "lukas.k", "You: Anytime 🙌", now - 3 * 24 * 60 * min + min, unread = readUnread(lukasId, 0)),
        )
        _conversations.value = convs
        recomputeTotal()
    }

    /** Seeded unread unless the conversation was already read (persists across restart). */
    private fun readUnread(id: String, seeded: Int): Int = if (isRead(id)) 0 else seeded

    private fun bumpConversation(id: String, preview: String, atMs: Long, incomingUnread: Boolean) {
        val list = _conversations.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val c = list.removeAt(idx)
            val newUnread = if (incomingUnread) c.unread + 1 else c.unread
            if (incomingUnread) setRead(id, false)
            list.add(0, c.copy(lastPreview = preview, lastAtMs = atMs, unread = newUnread))
            _conversations.value = list
            recomputeTotal()
        }
    }

    private fun previewOf(m: ChatMessage): String {
        val base = when {
            m.chip != null -> "📎 Shared ${m.chip.label}"
            else -> m.body.orEmpty()
        }
        return if (m.fromMe) "You: $base" else base
    }

    private fun recomputeTotal() { _totalUnread.value = _conversations.value.sumOf { it.unread } }

    private fun isRead(id: String): Boolean = prefs.getBoolean("read_$id", false)
    private fun setRead(id: String, read: Boolean) = prefs.edit().putBoolean("read_$id", read).apply()

    private fun comingSoon(): BtResult<Nothing> = BtResult.Err(
        BtApiError(0, BtApiError.Codes.UNKNOWN, "Chat is coming soon — the platform is still adding it."),
    )
}

/**
 * Debug gateway that simulates the realtime channel: once connected it delivers a
 * single incoming message after a short delay, proving the unread-badge + thread
 * plumbing without a server. The real gateway will bridge the `/ws` socket here.
 */
class StubChatGateway : ChatGateway {
    private var connected = false

    override fun connect(scope: CoroutineScope, onMessage: (ChatMessage) -> Unit) {
        if (!ChatFlags.enabled || connected) return
        connected = true
        scope.launch {
            delay(9_000)
            if (!isActive || !connected) return@launch
            onMessage(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = "c-anna",
                    fromMe = false,
                    body = "btw are you free to look at that basket later? 🙂",
                    sentAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun disconnect() { connected = false }
}
