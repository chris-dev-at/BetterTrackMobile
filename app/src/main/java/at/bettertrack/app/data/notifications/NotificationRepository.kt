package at.bettertrack.app.data.notifications

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.ChannelPrefsDto
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

    /** GET the whole settings shape and seed the local mirror. */
    suspend fun loadServerSettings(): BtResult<Unit>

    // ── The routing matrix (26 types × 6 channels) ─────────────────────────────
    //
    // Every one of these builds its body with a pure function from
    // `NotificationCatalog.kt`, applies it optimistically, and rolls the local
    // cache back if the server refuses. They all surface their errors: these are
    // switches the user just flipped, and one that springs back in silence looks
    // broken. A `null` body from the builder means "nothing to send" and returns
    // `Ok` without a request — an empty `{}` PATCH is itself a 400.

    /** Flip ONE cell. The rest of that type's six booleans ride along unchanged. */
    suspend fun setCell(type: String, channel: NotifChannel, on: Boolean): BtResult<Unit>

    /**
     * Flip one channel across all eight mirrorchain types — the collapsed group
     * row's switch.
     */
    suspend fun setMirrorChannel(channel: NotifChannel, on: Boolean): BtResult<Unit>

    /**
     * A category master toggle: every unlocked cell on every VISIBLE channel, for
     * every type in the category except `account.invite`.
     */
    suspend fun setCategory(category: NotifCatalog.Category, on: Boolean): BtResult<Unit>

    /**
     * Mute or unmute a whole type — the platform's "all channels false" state.
     *
     * Muting snapshots the current routing first so unmuting restores the user's
     * own previous choice rather than a guessed default. The snapshot is written
     * BEFORE the request and cleared only once the server has accepted the unmute,
     * so a refused round trip never leaves the phone unable to undo.
     */
    suspend fun setTypeMuted(type: String, muted: Boolean): BtResult<Unit>

    // ── v5 delivery (per-type digest cadence + quiet hours) ────────────────────
    /**
     * Set ONE type's digest cadence. `cadence` is sparse across types, so the body
     * is a single entry. A no-op never leaves the device. Rolls back on refusal.
     */
    suspend fun setTypeCadence(type: String, cadence: DigestCadence): BtResult<Unit>
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
                // `channels` gates which matrix COLUMNS exist; `channelsConfigurable`
                // gates whether the Telegram/Discord SETUP cards exist. They are not
                // the same question — see the DTO's KDoc for why conflating them
                // produces a chicken-and-egg.
                settings.setAvailability(channelAvailabilityOf(r.value.channels))
                settings.setConfigurable(channelsConfigurableOf(r.value.channelsConfigurable))
                // v5 delivery: echoed verbatim. Absent (pre-v5) ⇒ null ⇒ the control
                // stays hidden and the key can never reach a PATCH body.
                settings.syncDeliveryFromServer(r.value.cadence, r.value.quietHours)
                // The account-wide mute, same rule: a GET is the ONLY thing allowed to
                // establish the tri-state, so it is echoed verbatim including `null`
                // (⇒ this server has no account mute ⇒ no switch, ever).
                settings.setAccountMuted(r.value.muted)
                Log.i(
                    TAG,
                    "Live notification settings loaded (${r.value.matrix.size} types; " +
                        "cols=${settings.availability.value.visible.size} " +
                        "conf=${settings.configurable.value}; " +
                        "cadence=${r.value.cadence != null} quietHours=${r.value.quietHours != null} muted=${r.value.muted}).",
                )
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                Log.w(TAG, "GET /settings/notifications unavailable (HTTP ${r.error.httpStatus} ${r.error.code}); matrix is local-only.")
                if (r.error.isForbidden || r.error.isInsufficientScope || r.error.isNetwork) BtResult.Ok(Unit) else r.map()
            }
        }

    // ── The routing matrix ─────────────────────────────────────────────────────

    override suspend fun setCell(type: String, channel: NotifChannel, on: Boolean): BtResult<Unit> {
        val rows = settings.matrix.value.rows
        val patch = cellPatch(type, rows, channel, on, settings.serverTypes.value)
            ?: return BtResult.Ok(Unit)
        return patchMatrix(rows, patch)
    }

    override suspend fun setMirrorChannel(channel: NotifChannel, on: Boolean): BtResult<Unit> {
        val rows = settings.matrix.value.rows
        val patch = mirrorPatch(rows, channel, on, settings.serverTypes.value)
            ?: return BtResult.Ok(Unit)
        return patchMatrix(rows, patch)
    }

    override suspend fun setCategory(category: NotifCatalog.Category, on: Boolean): BtResult<Unit> {
        val rows = settings.matrix.value.rows
        val patch = categoryPatch(
            category = category,
            rows = rows,
            visibleChannels = settings.availability.value.visible,
            on = on,
            serverTypes = settings.serverTypes.value,
        ) ?: return BtResult.Ok(Unit)
        return patchMatrix(rows, patch)
    }

    override suspend fun setTypeMuted(type: String, muted: Boolean): BtResult<Unit> {
        val rows = settings.matrix.value.rows
        val current = rows[type]
        // Snapshot BEFORE the write, so a refused mute still leaves an undo path and
        // a successful one can be reversed exactly. Only ever taken from a routing
        // that is actually delivering something — snapshotting an already-muted row
        // would store the very state the snapshot exists to escape.
        if (muted && current != null && !current.isMuted()) {
            settings.setPremuteSnapshot(type, current)
        }
        val patch = mutePatch(
            type = type,
            rows = rows,
            muted = muted,
            snapshot = settings.premuteSnapshot(type),
            serverTypes = settings.serverTypes.value,
        ) ?: return BtResult.Ok(Unit)
        val result = patchMatrix(rows, patch)
        // The snapshot has done its job once the server has accepted an unmute.
        // Cleared only on success: a failed unmute must keep the way back.
        if (!muted && result is BtResult.Ok) settings.setPremuteSnapshot(type, null)
        return result
    }

    /**
     * Send a matrix delta: apply it locally first so the switch moves under the
     * finger, then PATCH. Success re-seeds from the server's full echo rather than
     * trusting the optimistic write; failure restores exactly the rows that were
     * touched, so the screen never shows a state the server does not hold.
     */
    private suspend fun patchMatrix(
        before: Map<String, TypePrefs>,
        patch: Map<String, ChannelPrefsDto>,
    ): BtResult<Unit> {
        val optimistic = patch.mapValues { (type, dto) ->
            (before[type] ?: TypePrefs()).mergedFrom(dto)
        }
        settings.applyMatrix(optimistic)
        return when (
            val r = apiCall(json) {
                api.updateNotificationSettings(UpdateNotificationSettingsRequest(matrix = patch))
            }
        ) {
            is BtResult.Ok -> {
                applyServerEcho(r.value)
                BtResult.Ok(Unit)
            }
            is BtResult.Err -> {
                settings.applyMatrix(patch.keys.mapNotNull { t -> before[t]?.let { t to it } }.toMap())
                Log.w(TAG, "PATCH /settings/notifications (matrix) failed (HTTP ${r.error.httpStatus} ${r.error.code}); reverted.")
                // Deliberately NOT softened: a switch that silently springs back is
                // worse than a visible error.
                r.map()
            }
        }
    }

    // ── v5 delivery (per-type digest cadence + quiet hours) ────────────────────

    override suspend fun setTypeCadence(type: String, cadence: DigestCadence): BtResult<Unit> {
        val before = settings.delivery.value
        // Nothing to change (or a pre-v5 server) ⇒ no request at all: `{}` is a 400.
        val patch = typeCadencePatch(before.cadence, type, cadence) ?: return BtResult.Ok(Unit)
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
            // Every caller of this is a control the user just operated (a cadence
            // chooser, a quiet-hours field). Softening offline/forbidden to `Ok` here
            // used to be defensible when the writes were background best-effort; now
            // it would roll the control back and say nothing about why.
            r.map()
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
