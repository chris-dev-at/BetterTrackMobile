package at.bettertrack.app.data.notifications

import android.util.Log
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.MarkReadAllRequest
import at.bettertrack.app.data.api.dto.MarkReadIdsRequest
import at.bettertrack.app.data.api.dto.NotificationItemDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Step-16 feature flags. */
object NotificationFlags {
    /**
     * When the live `GET /notifications` read is unavailable (no notifications
     * scope granted to the mobile client yet), seed a debug sample inbox so the
     * whole UI + badge + deep-link flow is device-verifiable. Never in release.
     */
    val stubInbox: Boolean = BuildConfig.DEBUG

    /**
     * Device-token register/refresh/delete is stubbed: the platform exposes NO
     * device-token endpoint yet (confirmed against production OpenAPI). Obtaining
     * the FCM token itself works without the platform and is verified for real.
     */
    const val stubDeviceRegistration: Boolean = true
}

/** Which backend is currently feeding the inbox. */
enum class NotifSource { Unknown, Live, Stub }

/**
 * Notifications repository (Step 16, §6.11). The clean seam between the app's
 * full inbox UI and the platform's current coverage.
 *
 * LIVE reads/writes are wired for real against `GET /notifications` +
 * `POST /notifications/mark-read`; they light up automatically the moment the
 * mobile client is granted a notifications read scope. Until then the read 403s
 * (INSUFFICIENT_SCOPE) and we fall back to a debug sample inbox + local mark-read,
 * logging the precise probe outcome. Incoming FCM pushes ([addReceived]) and the
 * debug simulate action feed the same in-memory inbox regardless of source.
 */
interface NotificationRepository {
    val items: StateFlow<List<AppNotification>>
    val unreadCount: StateFlow<Int>
    val source: StateFlow<NotifSource>

    suspend fun refresh(): BtResult<Unit>
    suspend fun markRead(ids: List<String>)
    suspend fun markAllRead()

    /** Insert a received/simulated push into the inbox (already gated by prefs). */
    fun addReceived(notification: AppNotification)

    /** GET the server matrix and seed the in-app/email columns (best-effort). */
    suspend fun loadServerSettings(): BtResult<Unit>
    /** PATCH the in-app/email columns to the server (best-effort; local persists). */
    suspend fun pushServerSettings(): BtResult<Unit>
}

class DefaultNotificationRepository(
    private val api: BtApi,
    private val json: Json,
    private val settings: NotificationSettingsStore,
) : NotificationRepository {

    private val _items = MutableStateFlow<List<AppNotification>>(emptyList())
    override val items: StateFlow<List<AppNotification>> = _items.asStateFlow()

    private val _unread = MutableStateFlow(0)
    override val unreadCount: StateFlow<Int> = _unread.asStateFlow()

    private val _source = MutableStateFlow(NotifSource.Unknown)
    override val source: StateFlow<NotifSource> = _source.asStateFlow()

    override suspend fun refresh(): BtResult<Unit> {
        return when (val r = apiCall(json) { api.notifications() }) {
            is BtResult.Ok -> {
                _source.value = NotifSource.Live
                val mapped = r.value.items.map(::mapItem).sortedByDescending { it.createdAtMs }
                _items.value = mapped
                _unread.value = if (r.value.unreadCount > 0) r.value.unreadCount else mapped.count { it.isUnread }
                Log.i(TAG, "Live inbox: ${mapped.size} items, ${_unread.value} unread.")
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                val e = r.error
                Log.w(
                    TAG,
                    "GET /notifications not available (HTTP ${e.httpStatus} ${e.code}); " +
                        if (NotificationFlags.stubInbox) "using debug sample inbox." else "inbox empty in release.",
                )
                if (_items.value.isEmpty()) {
                    _source.value = NotifSource.Stub
                    if (NotificationFlags.stubInbox) seedStub() else recompute()
                }
                // A missing scope / forbidden read is an EXPECTED platform gap, not a
                // user-facing error — surface success so the inbox shows content.
                if (e.isForbidden || e.isInsufficientScope || e.isNetwork) BtResult.Ok(Unit) else r.map()
            }
        }
    }

    override suspend fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        _items.value = _items.value.map {
            if (it.id in ids && it.isUnread) it.copy(readAtMs = System.currentTimeMillis()) else it
        }
        recompute()
        if (_source.value == NotifSource.Live) {
            when (val r = apiCall(json) { api.markNotificationsRead(MarkReadIdsRequest(ids)) }) {
                is BtResult.Err -> Log.w(TAG, "mark-read(ids) failed: HTTP ${r.error.httpStatus}")
                else -> {}
            }
        }
    }

    override suspend fun markAllRead() {
        val now = System.currentTimeMillis()
        _items.value = _items.value.map { if (it.isUnread) it.copy(readAtMs = now) else it }
        recompute()
        if (_source.value == NotifSource.Live) {
            when (val r = apiCall(json) { api.markAllNotificationsRead(MarkReadAllRequest()) }) {
                is BtResult.Err -> Log.w(TAG, "mark-read(all) failed: HTTP ${r.error.httpStatus}")
                else -> {}
            }
        }
    }

    override fun addReceived(notification: AppNotification) {
        _items.value = (listOf(notification) + _items.value).distinctBy { it.id }
        recompute()
    }

    override suspend fun loadServerSettings(): BtResult<Unit> =
        when (val r = apiCall(json) { api.notificationSettings() }) {
            is BtResult.Ok -> {
                settings.syncFromServer(r.value.matrix)
                Log.i(TAG, "Live notification settings loaded (${r.value.matrix.size} types).")
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                Log.w(TAG, "GET /settings/notifications unavailable (HTTP ${r.error.httpStatus} ${r.error.code}); matrix is local-only.")
                if (r.error.isForbidden || r.error.isInsufficientScope || r.error.isNetwork) BtResult.Ok(Unit) else r.map()
            }
        }

    override suspend fun pushServerSettings(): BtResult<Unit> {
        // Local persistence already happened in the store; try to mirror in-app/
        // email to the server. Push + Mute never leave the device.
        return when (val r = apiCall(json) {
            api.updateNotificationSettings(
                at.bettertrack.app.data.api.dto.UpdateNotificationSettingsRequest(settings.serverMatrixForPatch()),
            )
        }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> {
                Log.w(TAG, "PATCH /settings/notifications unavailable (HTTP ${r.error.httpStatus}); kept locally.")
                if (r.error.isForbidden || r.error.isInsufficientScope || r.error.isNetwork) BtResult.Ok(Unit) else r.map()
            }
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun mapItem(dto: NotificationItemDto): AppNotification = AppNotification(
        id = dto.id,
        type = dto.type,
        title = dto.title,
        body = dto.body,
        payload = dto.payload,
        readAtMs = dto.readAt?.let(::parseIso),
        createdAtMs = parseIso(dto.createdAt) ?: System.currentTimeMillis(),
    )

    private fun recompute() { _unread.value = _items.value.count { it.isUnread } }

    private fun seedStub() {
        val now = System.currentTimeMillis()
        val min = 60_000L
        fun payload(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
            pairs.forEach { (k, v) -> put(k, v) }
        }
        _items.value = listOf(
            AppNotification(
                id = "n-fr-1", type = "friend.request",
                title = "New friend request",
                body = "@marie_w wants to connect with you.",
                payload = null, readAtMs = null, createdAtMs = now - 8 * min,
            ),
            AppNotification(
                id = "n-ps-1", type = "portfolio.shared",
                title = "A portfolio was shared with you",
                body = "@mulham shared “Main” with you.",
                payload = payload("portfolioId" to "shared-demo"),
                readAtMs = null, createdAtMs = now - 55 * min,
            ),
            AppNotification(
                id = "n-al-1", type = "alert.triggered",
                title = "Price alert",
                body = "AAPL passed your €180,00 target.",
                payload = payload("assetId" to "AAPL"),
                readAtMs = null, createdAtMs = now - 3 * 60 * min,
            ),
            AppNotification(
                id = "n-fa-1", type = "friend.accepted",
                title = "Friend request accepted",
                body = "@lukas.k accepted your friend request.",
                payload = null, readAtMs = now - 20 * 60 * min, createdAtMs = now - 22 * 60 * min,
            ),
            AppNotification(
                id = "n-inv-1", type = "account.invite",
                title = "Welcome to BetterTrack",
                body = "Your account is ready. Tap to review your settings.",
                payload = null, readAtMs = now - 3L * 24 * 60 * min, createdAtMs = now - 3L * 24 * 60 * min,
            ),
        )
        recompute()
    }

    private fun parseIso(iso: String): Long? = try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private fun <T> BtResult<T>.map(): BtResult<Unit> = when (this) {
        is BtResult.Ok -> BtResult.Ok(Unit)
        is BtResult.Err -> this
    }

    private companion object {
        const val TAG = "BtNotif"
    }
}
