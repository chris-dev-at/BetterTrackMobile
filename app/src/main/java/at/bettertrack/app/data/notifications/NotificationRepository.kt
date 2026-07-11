package at.bettertrack.app.data.notifications

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.MarkReadAllRequest
import at.bettertrack.app.data.api.dto.MarkReadIdsRequest
import at.bettertrack.app.data.api.dto.NotificationItemDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.time.Instant

/** Notifications feature flags (tripwire-tested: see NotificationLogicTest). */
object NotificationFlags {
    /**
     * Device-token register/refresh/delete against `POST|DELETE /notifications/devices`.
     * LIVE on Notifications-v2 (platform PR #427) → `false`. Kept as an explicit
     * kill-switch: flip back to `true` to disable all device-token traffic without
     * ripping out the wiring. (Real FCM *sends* are still dark until the owner
     * installs the Firebase key server-side — platform #421 — but token
     * registration itself is live and verifiable.)
     */
    const val stubDeviceRegistration: Boolean = false
}

/** Which backend is currently feeding the inbox. */
enum class NotifSource { Unknown, Live }

/**
 * Notifications repository (Step 16 → LIVE on Notifications-v2, §6.11).
 *
 * `GET /notifications` is now AUTHORITATIVE: a 2xx (even empty) replaces the inbox
 * and drives the unread badge, and `POST /notifications/mark-read` persists read
 * state server-side. A forbidden/scope read (not expected now the scope is granted)
 * degrades to a soft empty state; network / 5xx surface as a real error so the UI
 * can offer retry. Incoming FCM pushes and the debug "simulate" action feed the
 * same in-memory inbox via [addReceived] and are reconciled on the next refresh.
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

    /** GET the server matrix and seed the in-app/email/push columns (best-effort). */
    suspend fun loadServerSettings(): BtResult<Unit>
    /** PATCH the in-app/email/push columns to the server (best-effort; local persists). */
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
                // Server rows are authoritative — replace the inbox (a persisted push
                // re-appears here with its real server id, so no duplicate lingers).
                _items.value = mapped
                _unread.value = if (r.value.unreadCount > 0) r.value.unreadCount else mapped.count { it.isUnread }
                Log.i(TAG, "Live inbox: ${mapped.size} items, ${_unread.value} unread.")
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                val e = r.error
                _source.value = NotifSource.Live
                Log.w(TAG, "GET /notifications failed (HTTP ${e.httpStatus} ${e.code}).")
                // The notifications:read scope is granted, so a forbidden/scope read
                // is not expected — degrade it to a soft empty state. Network / 5xx
                // surface so the inbox shows its error+retry rather than a misleading
                // "no notifications".
                if (e.isForbidden || e.isInsufficientScope) BtResult.Ok(Unit) else r.map()
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
        // Local persistence already happened in the store; mirror in-app / email /
        // push to the server (webpush echoed verbatim). Only per-type Mute stays local.
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
