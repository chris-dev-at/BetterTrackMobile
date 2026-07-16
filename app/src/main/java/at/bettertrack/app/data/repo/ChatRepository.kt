package at.bettertrack.app.data.repo

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.CHAT_MESSAGE_MAX
import at.bettertrack.app.data.api.dto.ChatChipDto
import at.bettertrack.app.data.api.dto.ChatChipRefDto
import at.bettertrack.app.data.api.dto.ChatConversationDto
import at.bettertrack.app.data.api.dto.ChatMessageDto
import at.bettertrack.app.data.api.dto.OpenConversationRequest
import at.bettertrack.app.data.api.dto.SendChatMessageRequest
import at.bettertrack.app.data.api.parseApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Friend chat (Step 15, §6.10) — **LIVE** on the platform backend (#349 endpoints +
 * realtime, #386 `chat:read`/`chat:write`). [DefaultChatRepository] is the real
 * adapter over the `/chat` routes; the former in-memory stub is gone. 1:1 friend-only
 * conversations (one per pair), server-derived unread, cursor-paged history,
 * share-in-chat chips resolved per-viewer by the server, realtime invalidation
 * over `/ws` with a foreground polling fallback. No groups/reactions/read-receipts.
 */

/** Feature flag — chat shipped (#349 + #386), so it is live in debug and release. */
object ChatFlags {
    /** Chat is fully wired to the platform; the surface is always available now. */
    val enabled: Boolean = true
}

// ── Domain models (decoupled from the wire DTOs; the UI binds to these) ──────

enum class ShareChipKind(val wire: String) {
    Asset("asset"),
    Portfolio("portfolio"),
    Watchlist("watchlist"),
    Conglomerate("conglomerate"),

    /**
     * An unrecognized chip kind on the wire — e.g. a future `idea` chip (board
     * #502/#503) whose UI is out of app-v1 scope. Renders a NEUTRAL, non-navigating
     * chip so an unknown kind can never be mistaken for — or (as it used to) tapped
     * through AS — an asset with a bogus refId. Its empty [wire] is never emitted by
     * the app (attachables only ever build the four real kinds) and never matched by
     * [fromWire]; it is produced solely as the fallback for a kind the app doesn't model.
     */
    Unknown(""),
    ;

    companion object {
        /**
         * Map a wire kind to its enum. An absent/blank or unrecognized kind (a new
         * server chip the app doesn't model yet) falls back to [Unknown] — NOT [Asset]
         * — so it degrades to a neutral, tap-safe chip instead of a bogus asset link.
         */
        fun fromWire(w: String?): ShareChipKind =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire == w } ?: Unknown
    }
}

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

/** Whether the caller may still post to a thread (drives the composer). */
enum class ThreadAvailability {
    /** Normal: friend + participant; composer live. */
    Available,

    /** Unfriended/closed: history stays readable, composer disabled with a quiet notice. */
    ReadOnly,

    /** 404 on open (non-participant / never-shared): a friendly "not available" state. */
    NotAvailable,
}

/** Everything a thread screen renders, single-flow. */
data class ThreadState(
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    val availability: ThreadAvailability = ThreadAvailability.Available,
    val friendUserId: String? = null,
    val friendUsername: String = "",
    val error: String? = null,
)

// ── Pure helpers (unit-tested; Context/Android-free) ─────────────────────────

/**
 * Pure preview text for a conversation row: a share chip renders as "📎 Shared …"
 * (chip takes precedence over any accompanying body), and my own messages are
 * prefixed "You: ".
 */
internal fun chatMessagePreview(fromMe: Boolean, body: String?, chipLabel: String?): String {
    val base = if (chipLabel != null) "📎 Shared $chipLabel" else body.orEmpty()
    return if (fromMe) "You: $base" else base
}

/**
 * The i18n-safe phrase for a chip preview in the conversation list — the server
 * sends only the chip KIND for previews (never the resolved name), so this never
 * leaks a non-shared item's identity.
 */
internal fun chipKindPhrase(kind: String?): String = when (kind) {
    ShareChipKind.Asset.wire -> "an asset"
    ShareChipKind.Portfolio.wire -> "a portfolio"
    ShareChipKind.Watchlist.wire -> "a watchlist"
    ShareChipKind.Conglomerate.wire -> "a basket"
    else -> "an item"
}

/** Tolerant ISO-8601 → epoch-ms (server sends `…Z` datetimes). 0 on failure. */
internal fun isoToEpochMs(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

/** Map a server-resolved chip to the UI model. Not-viewable ⇒ title/subtitle null. */
internal fun ChatChipDto.toDomain(): ShareChip {
    val k = ShareChipKind.fromWire(kind)
    return ShareChip(
        kind = k,
        refId = subjectId,
        label = title.orEmpty(),
        // For an asset the server's `title` IS the symbol; others carry the name in `title`.
        symbol = if (k == ShareChipKind.Asset) title else null,
        ownerName = subtitle,
        viewable = viewable,
    )
}

internal fun ChatMessageDto.toDomain(myUserId: String?): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    fromMe = senderId == myUserId,
    body = body,
    chip = chip?.toDomain(),
    sentAtMs = isoToEpochMs(createdAt),
)

internal fun ChatConversationDto.toDomain(myUserId: String?): Conversation {
    val preview = lastMessage?.let {
        chatMessagePreview(
            fromMe = it.senderId == myUserId,
            body = it.body,
            chipLabel = it.chipKind?.let { k -> chipKindPhrase(k) },
        )
    }.orEmpty()
    // A freshly-opened, empty thread (null lastMessageAt) sorts as "just now".
    val at = lastMessageAt?.let { isoToEpochMs(it) }?.takeIf { it > 0L } ?: System.currentTimeMillis()
    // `user` is null when the other participant deleted their account (#362):
    // history stays readable, anonymized; the empty id makes the thread read-only.
    return Conversation(
        id = id,
        friendUserId = user?.id.orEmpty(),
        friendUsername = user?.username ?: "deleted",
        lastPreview = preview,
        lastAtMs = at,
        unread = unreadCount,
    )
}

/**
 * Merge message pages: dedup by id (incoming wins → fresh per-viewer chip
 * resolution replaces any stale one), sorted oldest→newest by (sentAt, id).
 * UUIDv7 ids are time-ordered, so the id tiebreak matches chronology.
 */
internal fun mergeMessages(existing: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty() && incoming.size == 1) return incoming
    val byId = LinkedHashMap<String, ChatMessage>(existing.size + incoming.size)
    for (m in existing) byId[m.id] = m
    for (m in incoming) byId[m.id] = m
    return byId.values.sortedWith(compareBy({ it.sentAtMs }, { it.id }))
}

/** Total unread = sum of per-conversation unread (kept locally consistent with mark-read). */
internal fun conversationsToTotalUnread(list: List<Conversation>): Int = list.sumOf { it.unread }

/** Sort conversations newest-active first. */
internal fun sortConversations(list: List<Conversation>): List<Conversation> =
    list.sortedByDescending { it.lastAtMs }

// ── Repository contract ──────────────────────────────────────────────────────

interface ChatRepository {
    val conversations: StateFlow<List<Conversation>>
    val totalUnread: StateFlow<Int>

    /** Refetch the conversation list + unread badge from the server. */
    suspend fun refreshConversations(): BtResult<Unit>

    /** The single reactive state for one thread (messages + availability + paging). */
    fun thread(conversationId: String): StateFlow<ThreadState>

    /** Load the newest page + summary for a thread (idempotent; drives availability). */
    suspend fun loadThread(conversationId: String): BtResult<Unit>

    /** Page one step of older history (keyset by cursor). No-op when [ThreadState.hasMore] is false. */
    suspend fun loadOlder(conversationId: String): BtResult<Unit>

    /** Open (or resolve) the 1:1 conversation with a friend; returns its id. Non-friend → 404. */
    suspend fun openConversationWith(friendUserId: String, friendUsername: String): BtResult<String>

    /** Send text and/or a share chip (body clamped to [CHAT_MESSAGE_MAX] client-side too). */
    suspend fun send(conversationId: String, body: String?, chip: ShareChip?): BtResult<Unit>

    /** Mark the conversation read on the server + clear its badge locally. */
    suspend fun markRead(conversationId: String)

    /** My own shareable items (portfolios / conglomerates / watchlists) as attachable chips. */
    suspend fun attachables(): BtResult<List<ShareChip>>

    /** Which thread is on-screen (drives targeted realtime/poll refetch). null when none. */
    fun setActiveConversation(conversationId: String?)

    /** Ref-counted: connect the realtime gateway + start the foreground poll loop. */
    fun connectRealtime()
    fun disconnectRealtime()
}

// ── Live implementation ──────────────────────────────────────────────────────

class DefaultChatRepository(
    private val api: BtApi,
    private val json: Json,
    private val gateway: ChatRealtimeGateway,
    private val currentUserId: () -> String?,
    /** Current friend ids for read-only (unfriended) detection; null ⇒ couldn't determine. */
    private val friendIdsProvider: suspend () -> Set<String>?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val observeProcessLifecycle: Boolean = true,
) : ChatRepository {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations

    private val _totalUnread = MutableStateFlow(0)
    override val totalUnread: StateFlow<Int> = _totalUnread

    private val threadStores = mutableMapOf<String, MutableStateFlow<ThreadState>>()
    private val threadCursors = mutableMapOf<String, String?>()

    private val connectCount = AtomicInteger(0)
    private val foreground = MutableStateFlow(true)
    @Volatile private var pollJob: Job? = null
    @Volatile private var activeConversationId: String? = null

    @Volatile private var cachedFriendIds: Set<String>? = null
    @Volatile private var friendIdsAtMs: Long = 0L

    init {
        if (observeProcessLifecycle) {
            // ProcessLifecycleOwner must be touched on the main thread.
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        foreground.value = true
                        if (connectCount.get() > 0) gateway.connect(::onSignal, ::onConnectedChange)
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        // Never poll or hold a socket in the background.
                        foreground.value = false
                        gateway.disconnect()
                    }
                })
            }
        }
    }

    // ── Conversation list ─────────────────────────────────────────────────────

    override suspend fun refreshConversations(): BtResult<Unit> =
        when (val r = apiCall(json) { api.chatConversations() }) {
            is BtResult.Ok -> {
                val myId = currentUserId()
                val convs = sortConversations(r.value.conversations.map { it.toDomain(myId) })
                _conversations.value = convs
                _totalUnread.value = conversationsToTotalUnread(convs)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> r
        }

    // ── Thread ─────────────────────────────────────────────────────────────────

    private fun threadStore(id: String): MutableStateFlow<ThreadState> =
        threadStores.getOrPut(id) { MutableStateFlow(ThreadState(loading = true)) }

    override fun thread(conversationId: String): StateFlow<ThreadState> = threadStore(conversationId)

    override suspend fun loadThread(conversationId: String): BtResult<Unit> {
        val store = threadStore(conversationId)
        store.value = store.value.copy(loading = store.value.messages.isEmpty(), error = null)
        return when (val r = apiCall(json) { api.chatThread(conversationId, cursor = null, limit = PAGE) }) {
            is BtResult.Ok -> {
                val dto = r.value
                val myId = currentUserId()
                val merged = mergeMessages(store.value.messages, dto.messages.map { it.toDomain(myId) })
                // Deleted-account participant (#362): user is null → thread is
                // readable history, closed to new messages (read-only).
                val friendId = dto.conversation.user?.id
                val readOnly = friendId == null || computeReadOnly(friendId)
                threadCursors[conversationId] = dto.nextCursor
                store.value = ThreadState(
                    messages = merged,
                    loading = false,
                    loadingOlder = false,
                    hasMore = dto.nextCursor != null,
                    availability = if (readOnly) ThreadAvailability.ReadOnly else ThreadAvailability.Available,
                    friendUserId = friendId,
                    friendUsername = dto.conversation.user?.username ?: "deleted",
                    error = null,
                )
                upsertConversation(dto.conversation)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                store.value = if (r.error.httpStatus == 404) {
                    store.value.copy(loading = false, availability = ThreadAvailability.NotAvailable)
                } else {
                    store.value.copy(loading = false, error = r.error.userMessage)
                }
                r
            }
        }
    }

    override suspend fun loadOlder(conversationId: String): BtResult<Unit> {
        val cursor = threadCursors[conversationId] ?: return BtResult.Ok(Unit) // no older page
        val store = threadStore(conversationId)
        if (store.value.loadingOlder) return BtResult.Ok(Unit)
        store.value = store.value.copy(loadingOlder = true)
        return when (val r = apiCall(json) { api.chatThread(conversationId, cursor = cursor, limit = PAGE) }) {
            is BtResult.Ok -> {
                val myId = currentUserId()
                val merged = mergeMessages(store.value.messages, r.value.messages.map { it.toDomain(myId) })
                threadCursors[conversationId] = r.value.nextCursor
                store.value = store.value.copy(
                    messages = merged,
                    loadingOlder = false,
                    hasMore = r.value.nextCursor != null,
                )
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                store.value = store.value.copy(loadingOlder = false, error = r.error.userMessage)
                r
            }
        }
    }

    /** Refetch just the newest page and merge — used by realtime/poll (no spinner, keeps paging). */
    private suspend fun refetchNewest(conversationId: String) {
        val store = threadStores[conversationId] ?: return
        when (val r = apiCall(json) { api.chatThread(conversationId, cursor = null, limit = PAGE) }) {
            is BtResult.Ok -> {
                val myId = currentUserId()
                val merged = mergeMessages(store.value.messages, r.value.messages.map { it.toDomain(myId) })
                if (store.value.availability != ThreadAvailability.NotAvailable) {
                    store.value = store.value.copy(messages = merged)
                }
                upsertConversation(r.value.conversation)
            }
            is BtResult.Err -> Unit // transient; the next tick retries
        }
    }

    override suspend fun openConversationWith(friendUserId: String, friendUsername: String): BtResult<String> =
        when (val r = apiCall(json) { api.openChatConversation(OpenConversationRequest(friendUserId)) }) {
            is BtResult.Ok -> {
                upsertConversation(r.value.conversation)
                BtResult.Ok(r.value.conversation.id)
            }
            is BtResult.Err -> r
        }

    override suspend fun send(conversationId: String, body: String?, chip: ShareChip?): BtResult<Unit> {
        val trimmed = body?.trim()?.take(CHAT_MESSAGE_MAX)?.ifBlank { null }
        if (trimmed == null && chip == null) return BtResult.Ok(Unit)
        val req = SendChatMessageRequest(
            body = trimmed,
            chip = chip?.let { ChatChipRefDto(kind = it.kind.wire, subjectId = it.refId) },
        )
        return when (val r = apiCall(json) { api.sendChatMessage(conversationId, req) }) {
            is BtResult.Ok -> {
                val msg = r.value.message.toDomain(currentUserId())
                val store = threadStore(conversationId)
                store.value = store.value.copy(messages = mergeMessages(store.value.messages, listOf(msg)))
                bumpConversationPreview(conversationId, msg)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                // Unfriended / closed thread → history stays, composer disabled.
                if (r.error.httpStatus == 403 || r.error.httpStatus == 404) {
                    val store = threadStore(conversationId)
                    store.value = store.value.copy(availability = ThreadAvailability.ReadOnly)
                }
                r
            }
        }
    }

    override suspend fun markRead(conversationId: String) {
        // Optimistic local clear so the badge responds instantly; server re-syncs on refresh.
        val cleared = _conversations.value.map { if (it.id == conversationId) it.copy(unread = 0) else it }
        _conversations.value = cleared
        _totalUnread.value = conversationsToTotalUnread(cleared)
        unitCall { api.markChatRead(conversationId) }
    }

    override suspend fun attachables(): BtResult<List<ShareChip>> {
        val chips = mutableListOf<ShareChip>()
        var anyOk = false
        var lastErr: BtApiError? = null

        when (val r = apiCall(json) { api.portfolios(includeArchived = "false") }) {
            is BtResult.Ok -> {
                anyOk = true
                r.value.portfolios.filter { it.archivedAt == null }.forEach {
                    chips += ShareChip(ShareChipKind.Portfolio, it.id, it.name)
                }
            }
            is BtResult.Err -> lastErr = r.error
        }
        when (val r = apiCall(json) { api.conglomerates() }) {
            is BtResult.Ok -> {
                anyOk = true
                r.value.conglomerates.forEach { chips += ShareChip(ShareChipKind.Conglomerate, it.id, it.name) }
            }
            is BtResult.Err -> lastErr = r.error
        }
        when (val r = apiCall(json) { api.watchlists() }) {
            is BtResult.Ok -> {
                anyOk = true
                r.value.watchlists.forEach { chips += ShareChip(ShareChipKind.Watchlist, it.id, it.name) }
            }
            is BtResult.Err -> lastErr = r.error
        }
        return if (anyOk || lastErr == null) BtResult.Ok(chips) else BtResult.Err(lastErr)
    }

    // ── Realtime lifecycle (ref-counted; foreground-gated) ──────────────────────

    override fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
    }

    override fun connectRealtime() {
        if (connectCount.getAndIncrement() == 0) {
            startPolling()
            if (foreground.value) gateway.connect(::onSignal, ::onConnectedChange)
        }
    }

    override fun disconnectRealtime() {
        if (connectCount.decrementAndGet() <= 0) {
            connectCount.set(0)
            stopPolling()
            gateway.disconnect()
        }
    }

    private fun onSignal(conversationId: String?) {
        // Pure invalidation: refetch the list and the open thread (re-resolves chips).
        scope.launch {
            refreshConversations()
            (conversationId ?: activeConversationId)?.let { refetchNewest(it) }
            activeConversationId?.takeIf { it != conversationId }?.let { refetchNewest(it) }
        }
    }

    private fun onConnectedChange(connected: Boolean) { /* interval widens via gateway.connected */ }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            // Conversation list poll (foreground only).
            launch {
                while (isActive) {
                    foreground.first { it }
                    if (connectCount.get() > 0) refreshConversations()
                    delay(if (gateway.connected) LIST_POLL_LIVE_MS else LIST_POLL_MS)
                }
            }
            // Open-thread poll (foreground only).
            launch {
                while (isActive) {
                    foreground.first { it }
                    val cid = activeConversationId
                    if (cid != null && connectCount.get() > 0) refetchNewest(cid)
                    delay(if (gateway.connected) THREAD_POLL_LIVE_MS else THREAD_POLL_MS)
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ── Internals ───────────────────────────────────────────────────────────────

    private fun upsertConversation(dto: ChatConversationDto) {
        val conv = dto.toDomain(currentUserId())
        val next = _conversations.value.filterNot { it.id == conv.id } + conv
        val sorted = sortConversations(next)
        _conversations.value = sorted
        _totalUnread.value = conversationsToTotalUnread(sorted)
    }

    private fun bumpConversationPreview(conversationId: String, msg: ChatMessage) {
        val list = _conversations.value.toMutableList()
        val idx = list.indexOfFirst { it.id == conversationId }
        if (idx < 0) return
        val c = list.removeAt(idx)
        val preview = chatMessagePreview(
            fromMe = msg.fromMe,
            body = msg.body,
            chipLabel = msg.chip?.let { chipKindPhrase(it.kind.wire) },
        )
        list.add(0, c.copy(lastPreview = preview, lastAtMs = msg.sentAtMs))
        val sorted = sortConversations(list)
        _conversations.value = sorted
        _totalUnread.value = conversationsToTotalUnread(sorted)
    }

    private suspend fun computeReadOnly(friendUserId: String): Boolean {
        val ids = friendIds() ?: return false // unknown ⇒ don't disable the composer on a hiccup
        return friendUserId !in ids
    }

    private suspend fun friendIds(): Set<String>? {
        val now = System.currentTimeMillis()
        val cached = cachedFriendIds
        if (cached != null && now - friendIdsAtMs < FRIEND_CACHE_MS) return cached
        val fresh = friendIdsProvider()
        if (fresh != null) {
            cachedFriendIds = fresh
            friendIdsAtMs = now
        }
        return fresh ?: cached
    }

    /** For 200-with-body/empty writes (mark-read) that [apiCall] can't usefully decode. */
    private suspend fun unitCall(call: suspend () -> Response<Unit>): BtResult<Unit> =
        try {
            val resp = call()
            if (resp.isSuccessful) BtResult.Ok(Unit) else BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        } catch (_: IOException) {
            BtResult.Err(BtApiError(0, BtApiError.Codes.NETWORK, "No connection. Check your network and try again."))
        }

    private companion object {
        const val PAGE = 30
        const val LIST_POLL_MS = 30_000L
        const val THREAD_POLL_MS = 10_000L
        const val LIST_POLL_LIVE_MS = 60_000L
        const val THREAD_POLL_LIVE_MS = 25_000L
        const val FRIEND_CACHE_MS = 30_000L
    }
}
