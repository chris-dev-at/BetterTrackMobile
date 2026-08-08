package at.bettertrack.app.data.notifications

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.MarkReadAllRequest
import at.bettertrack.app.data.api.dto.MarkReadIdsRequest
import at.bettertrack.app.data.api.dto.NotificationItemDto
import at.bettertrack.app.data.api.dto.UpdateNotificationSettingsRequest
import at.bettertrack.app.data.api.unitApiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import retrofit2.Response
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

    // ── v5 delivery (digest cadence + quiet hours) ──────────────────────────────
    /**
     * Apply [cadence] to the digestible type group. Sends ONLY the types whose
     * value actually changes; a no-op change never leaves the device (an empty
     * PATCH body is a 400). Rolls the local cache back if the server rejects.
     */
    suspend fun setDigestCadence(cadence: DigestCadence): BtResult<Unit>
    /**
     * Apply a quiet-hours change. Sends ONLY the fields that differ from the last
     * server state (`quietHours` is the one field-partial object in the schema).
     * Rolls the local cache back if the server rejects.
     */
    suspend fun setQuietHours(next: QuietHours): BtResult<Unit>

    /**
     * Flip the ACCOUNT-WIDE mute — the web's single "silence everything" switch —
     * as `PATCH /settings/notifications {"muted": <bool>}`.
     *
     * Sends nothing at all when the last GET carried no `muted` key (the switch is
     * not rendered in that case) or when the value is already what was asked for.
     * The local flag flips optimistically and is rolled back if the server rejects.
     *
     * Unlike the other writes here this one surfaces EVERY error, including the
     * offline/forbidden classes the rest of this file softens to `Ok`. The reason
     * is the rollback: this is a switch the user just flipped, and if it snaps back
     * with no message the screen is showing a state nobody chose and not saying why.
     */
    suspend fun setAccountMuted(muted: Boolean): BtResult<Unit>
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
                // v5 delivery: echoed verbatim. Absent (pre-v5) ⇒ null ⇒ the Delivery
                // section stays hidden and neither key can reach a PATCH body.
                settings.syncDeliveryFromServer(r.value.cadence, r.value.quietHours)
                // The account-wide mute, same rule: a GET is the ONLY thing allowed to
                // establish the tri-state, so it is echoed verbatim including `null`
                // (⇒ this server has no account mute ⇒ no switch, ever).
                settings.setAccountMuted(r.value.muted)
                Log.i(TAG, "Live notification settings loaded (${r.value.matrix.size} types; tg=${ch?.telegram == true} dc=${ch?.discord == true}; cadence=${r.value.cadence != null} quietHours=${r.value.quietHours != null} muted=${r.value.muted}).")
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                Log.w(TAG, "GET /settings/notifications unavailable (HTTP ${r.error.httpStatus} ${r.error.code}); matrix is local-only.")
                if (r.error.isForbidden || r.error.isInsufficientScope || r.error.isNetwork) BtResult.Ok(Unit) else r.map()
            }
        }

    override suspend fun pushServerSettings(): BtResult<Unit> {
        // Local persistence already happened in the store; mirror in-app / email /
        // push to the server (webpush echoed verbatim). `cadence`/`quietHours`/`muted`
        // are left null ⇒ dropped from the body: a matrix edit must never restate a
        // setting it did not change.
        //
        // NOTE: the app no longer offers a matrix EDITOR (it moved to the web under
        // the parity ruling), so nothing calls this today. It is kept because the
        // seam is the only correct place a matrix write would go if one returns —
        // the alternative is the next such feature inventing its own PATCH.
        return when (val r = apiCall(json) {
            api.updateNotificationSettings(
                UpdateNotificationSettingsRequest(matrix = settings.serverMatrixForPatch()),
            )
        }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> {
                Log.w(TAG, "PATCH /settings/notifications unavailable (HTTP ${r.error.httpStatus}); kept locally.")
                if (r.error.isForbidden || r.error.isInsufficientScope || r.error.isNetwork) BtResult.Ok(Unit) else r.map()
            }
        }
    }

    // ── v5 delivery (digest cadence + quiet hours) ──────────────────────────────

    override suspend fun setDigestCadence(cadence: DigestCadence): BtResult<Unit> {
        val before = settings.delivery.value
        // Nothing to change (or a pre-v5 server) ⇒ no request at all: `{}` is a 400.
        val patch = cadencePatch(before.cadence, cadence) ?: return BtResult.Ok(Unit)
        settings.setDelivery(before.copy(cadence = before.cadence.orEmpty() + patch))
        return patchDelivery(before, UpdateNotificationSettingsRequest(cadence = patch))
    }

    override suspend fun setQuietHours(next: QuietHours): BtResult<Unit> {
        val before = settings.delivery.value
        val current = before.quietHours ?: return BtResult.Ok(Unit)
        val patch = quietHoursPatch(current, next) ?: return BtResult.Ok(Unit)
        settings.setDelivery(before.copy(quietHours = next))
        return patchDelivery(before, UpdateNotificationSettingsRequest(quietHours = patch))
    }

    override suspend fun setAccountMuted(muted: Boolean): BtResult<Unit> {
        val before = settings.accountMuted.value
        // `null` current ⇒ the server never modelled the key (sending it would be a
        // `.strict()` 400); unchanged ⇒ an empty-ish no-op patch. Either way: silence.
        val patch = accountMutePatch(before, muted) ?: return BtResult.Ok(Unit)
        settings.setAccountMuted(patch)
        return when (
            val r = apiCall(json) {
                api.updateNotificationSettings(UpdateNotificationSettingsRequest(muted = patch))
            }
        ) {
            is BtResult.Ok -> {
                applyServerEcho(r.value)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                settings.setAccountMuted(before)
                Log.w(TAG, "PATCH /settings/notifications (muted) failed (HTTP ${r.error.httpStatus} ${r.error.code}); reverted.")
                // Deliberately NOT softened — see the interface KDoc: a switch that
                // silently springs back is worse than a visible error.
                r.map()
            }
        }
    }

    /**
     * Re-seed from a PATCH response, which carries the FULL settings shape.
     *
     * The account mute is the one field applied only when PRESENT: a GET is the
     * authority on whether the server models it at all, and letting a narrower
     * write response erase that tri-state would make the switch vanish mid-use.
     */
    private fun applyServerEcho(resp: at.bettertrack.app.data.api.dto.NotificationSettingsResponse) {
        settings.syncFromServer(resp.matrix)
        settings.syncDeliveryFromServer(resp.cadence, resp.quietHours)
        resp.muted?.let(settings::setAccountMuted)
    }

    /**
     * Send a delivery PATCH. The response is the FULL settings shape, so a success
     * re-seeds from the server rather than trusting the optimistic local write; a
     * failure restores [rollbackTo] so the screen never shows a state the server
     * does not hold.
     */
    private suspend fun patchDelivery(
        rollbackTo: DeliveryState,
        body: UpdateNotificationSettingsRequest,
    ): BtResult<Unit> = when (val r = apiCall(json) { api.updateNotificationSettings(body) }) {
        is BtResult.Ok -> {
            applyServerEcho(r.value)
            BtResult.Ok(Unit)
        }
        is BtResult.Err -> {
            settings.setDelivery(rollbackTo)
            Log.w(TAG, "PATCH /settings/notifications (delivery) failed (HTTP ${r.error.httpStatus} ${r.error.code}); reverted.")
            if (r.error.isForbidden || r.error.isInsufficientScope || r.error.isNetwork) BtResult.Ok(Unit) else r.map()
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
        val result = unitApiCall(json, call)
        if (result is BtResult.Err) {
            _items.value = rollbackTo
            recomputeBadge()
            Log.w(TAG, "notification write failed: HTTP ${result.error.httpStatus} ${result.error.code}")
        }
        return result
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
