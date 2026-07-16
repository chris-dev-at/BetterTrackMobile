package at.bettertrack.app.data.notifications

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.api.dto.MarkReadAllRequest
import at.bettertrack.app.data.api.dto.MarkReadIdsRequest
import at.bettertrack.app.data.api.dto.NotificationItemDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
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

    /**
     * Notification ARCHIVE + DELETE UX (Notifications-v3, platform #437).
     *
     * When `true`: the inbox shows the Active|Archived|All filter, per-item
     * archive/unarchive/delete + bulk archive-all-read / delete-all-archived /
     * delete-all, and `GET /notifications` is called with `view=`. The bell badge
     * is unread-ACTIVE only.
     *
     * When `false`: the app behaves EXACTLY as before #437 — a single flat inbox,
     * mark-read only, no `view=` param, no archive/delete affordances. This keeps
     * the UI truthful while the platform ships #437 (never fake archive/delete
     * locally — a refresh would resurrect the rows). Kept as a kill-switch.
     *
     * ON since 2026-07-11: platform PR #440 live on prod (`fb09efd`), as-shipped
     * semantics in PLATFORM_ASKS.md update #13 — no deviations from the built
     * shape except the bulk path (`archive-all-read`), already reconciled.
     */
    const val archiveDeleteEnabled: Boolean = true
}

/** Which backend is currently feeding the inbox. */
enum class NotifSource { Unknown, Live }

/**
 * Notifications repository (Step 16 → LIVE on Notifications-v2; archive/delete on
 * Notifications-v3 #437, §6.11).
 *
 * `GET /notifications[?view=]` is AUTHORITATIVE: a 2xx (even empty) replaces the
 * inbox for the current view and drives the unread-ACTIVE badge; the write ops
 * (`mark-read`, `archive`/`unarchive`, `delete`, bulk) apply an OPTIMISTIC update
 * to the in-memory list and roll it back if the server rejects. Incoming FCM pushes
 * and the debug "simulate" action feed the same in-memory inbox via [addReceived]
 * and are reconciled on the next refresh.
 */
interface NotificationRepository {
    /** The list for the currently-loaded [NotifView]. */
    val items: StateFlow<List<AppNotification>>
    /** Bell badge: unread + ACTIVE only (archived never counts). */
    val unreadCount: StateFlow<Int>
    val source: StateFlow<NotifSource>

    /** Fetch a view (default Active — the bell surface). Sends `view=` only when #437 is enabled. */
    suspend fun refresh(view: NotifView = NotifView.Active): BtResult<Unit>
    /**
     * Mark rows read. On v4 (PR #486) the server ARCHIVES them (read == archived), so
     * on the next Active refresh they drop out of the list — that is correct, not a
     * sync error. Optimistic locally (sets readAt); the server drives the archive.
     */
    suspend fun markRead(ids: List<String>)
    /** Mark all read → on v4 archives everything; reconciles from the server after. */
    suspend fun markAllRead()

    // ── Notifications-v3 archive/delete (#437) ──────────────────────────────
    /** Archive one row (implies read); rolls back on error. */
    suspend fun archive(id: String): BtResult<Unit>
    /** Restore one archived row to active; rolls back on error. */
    /** [restore] = the archived row, for instant re-insert on snackbar Undo. */
    suspend fun unarchive(id: String, restore: AppNotification? = null): BtResult<Unit>
    /** Hard-delete one row; rolls back on error. */
    suspend fun delete(id: String): BtResult<Unit>
    /** Bulk: archive every already-read active row; rolls back on error. */
    suspend fun archiveAllRead(): BtResult<Unit>
    /** Bulk: hard-delete every archived row; rolls back on error. */
    suspend fun deleteAllArchived(): BtResult<Unit>
    /** Bulk: hard-delete every row; rolls back on error. */
    suspend fun deleteAll(): BtResult<Unit>

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

    /** The view [items] currently holds — governs how optimistic ops mutate the list. */
    private var lastView: NotifView = NotifView.Active

    override suspend fun refresh(view: NotifView): BtResult<Unit> {
        lastView = view
        // Only send `view=` once #437 is enabled; otherwise behave exactly as the
        // pre-v3 client (server default = active, archived rows simply absent).
        val viewParam = if (NotificationFlags.archiveDeleteEnabled) view.wire else null
        return when (val r = apiCall(json) { api.notifications(view = viewParam) }) {
            is BtResult.Ok -> {
                _source.value = NotifSource.Live
                val mapped = r.value.items.map(::mapItem).sortedByDescending { it.createdAtMs }
                // Server rows are authoritative — replace the view's list (a persisted
                // push re-appears here with its real server id, so no duplicate lingers).
                _items.value = mapped
                // Badge = unread ACTIVE only. Archive implies read, so there are no
                // unread archived rows ⇒ the active-unread count is well-defined from
                // any Active/All fetch. An Archived-only fetch carries no active rows,
                // so it must NOT zero the badge — leave the last known count.
                if (view != NotifView.Archived) {
                    _unread.value = if (r.value.unreadCount > 0) r.value.unreadCount else mapped.activeUnreadCount()
                }
                Log.i(TAG, "Live inbox[${view.wire}]: ${mapped.size} items, ${_unread.value} unread-active.")
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                val e = r.error
                _source.value = NotifSource.Live
                Log.w(TAG, "GET /notifications[${view.wire}] failed (HTTP ${e.httpStatus} ${e.code}).")
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
        recomputeBadge()
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
        recomputeBadge()
        if (_source.value == NotifSource.Live) {
            when (val r = apiCall(json) { api.markAllNotificationsRead(MarkReadAllRequest()) }) {
                is BtResult.Err -> Log.w(TAG, "mark-read(all) failed: HTTP ${r.error.httpStatus}")
                is BtResult.Ok -> {
                    // v4 (PR #486): mark-all-read ARCHIVES every row server-side, so the
                    // Active list should now be empty. Reconcile from the server rather
                    // than assuming: on v4 the archived rows drop out of Active; on a
                    // pre-v4 server they come back as read. Either outcome is correct and
                    // never treated as an error.
                    refresh(lastView)
                }
            }
        }
    }

    // ── Notifications-v3 archive/delete (#437) — optimistic + rollback ─────────

    override suspend fun archive(id: String): BtResult<Unit> {
        val prev = _items.value
        _items.value = prev.archiveInView(id, lastView, System.currentTimeMillis())
        recomputeBadge()
        return write(prev) { api.archiveNotification(id) }
    }

    override suspend fun unarchive(id: String, restore: AppNotification?): BtResult<Unit> {
        val prev = _items.value
        _items.value = prev.unarchiveInView(id, lastView, restore)
        recomputeBadge()
        return write(prev) { api.unarchiveNotification(id) }
    }

    override suspend fun delete(id: String): BtResult<Unit> {
        val prev = _items.value
        _items.value = prev.deleteInView(id)
        recomputeBadge()
        return write(prev) { api.deleteNotification(id) }
    }

    override suspend fun archiveAllRead(): BtResult<Unit> {
        val prev = _items.value
        _items.value = prev.archiveAllReadInView(lastView, System.currentTimeMillis())
        recomputeBadge()
        return write(prev) { api.archiveAllReadNotifications() }
    }

    override suspend fun deleteAllArchived(): BtResult<Unit> {
        val prev = _items.value
        _items.value = prev.deleteArchivedInView()
        recomputeBadge()
        return write(prev) { api.deleteNotifications("archived") }
    }

    override suspend fun deleteAll(): BtResult<Unit> {
        val prev = _items.value
        _items.value = prev.deleteAllInView()
        recomputeBadge()
        return write(prev) { api.deleteNotifications("all") }
    }

    override fun addReceived(notification: AppNotification) {
        _items.value = (listOf(notification) + _items.value).distinctBy { it.id }
        recomputeBadge()
    }

    override suspend fun loadServerSettings(): BtResult<Unit> =
        when (val r = apiCall(json) { api.notificationSettings() }) {
            is BtResult.Ok -> {
                settings.syncFromServer(r.value.matrix)
                // v4 `channels` availability gates the Telegram/Discord columns. Absent
                // (pre-v4) ⇒ both false ⇒ columns hidden (SMTP pattern).
                val ch = r.value.channels
                settings.setAvailability(
                    ChannelAvailability(
                        telegram = ch?.telegram == true,
                        discord = ch?.discord == true,
                    ),
                )
                Log.i(TAG, "Live notification settings loaded (${r.value.matrix.size} types; tg=${ch?.telegram == true} dc=${ch?.discord == true}).")
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

    /**
     * Fire an empty-body write. On any failure (network or non-2xx) restore
     * [rollbackTo] and surface the error so the UI can revert its optimistic change.
     */
    private suspend fun write(
        rollbackTo: List<AppNotification>,
        call: suspend () -> Response<Unit>,
    ): BtResult<Unit> {
        val resp = try {
            call()
        } catch (_: IOException) {
            _items.value = rollbackTo
            recomputeBadge()
            return BtResult.Err(BtApiError(0, BtApiError.Codes.NETWORK, "No connection."))
        }
        return if (resp.isSuccessful) {
            BtResult.Ok(Unit)
        } else {
            val err = parseApiError(json, resp.code(), resp.errorBody())
            _items.value = rollbackTo
            recomputeBadge()
            Log.w(TAG, "notification write failed: HTTP ${resp.code()} ${err.code}")
            BtResult.Err(err)
        }
    }

    private fun mapItem(dto: NotificationItemDto): AppNotification = AppNotification(
        id = dto.id,
        type = dto.type,
        title = dto.title,
        body = dto.body,
        payload = dto.payload,
        readAtMs = dto.readAt?.let(::parseIso),
        archivedAtMs = dto.archivedAt?.let(::parseIso),
        createdAtMs = parseIso(dto.createdAt) ?: System.currentTimeMillis(),
    )

    /**
     * Recompute the unread-ACTIVE badge from the current list — but only when that
     * list actually contains the active set (Active / All views). An Archived view
     * holds no active rows, so its badge is defined by the last Active/All load and
     * is left untouched (archive/unarchive/delete of archived rows never change the
     * active-unread count anyway).
     */
    private fun recomputeBadge() {
        if (lastView != NotifView.Archived) {
            _unread.value = _items.value.activeUnreadCount()
        }
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
