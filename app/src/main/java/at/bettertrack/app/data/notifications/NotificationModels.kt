package at.bettertrack.app.data.notifications

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Notifications domain model (Step 16, §6.11; archive/delete on Notifications-v3,
 * platform #437).
 *
 * The platform's notification `type` is a free string (`friend.request`,
 * `portfolio.shared`, `alert.triggered`, …). We map it to a [NotifKind] for
 * presentation (icon + channel + display group) and to a [NotifDeepLink] for the
 * tap-through target, refined by the (nullable) `payload`. The mapping is a pure
 * function so it is unit-tested and shared by the in-app inbox, the FCM
 * `onMessageReceived` path, and the notification-tap intent handler.
 */

/** A single inbox notification (server row OR a locally-received push). */
data class AppNotification(
    val id: String,
    /** Raw platform type string, e.g. "friend.request". */
    val type: String,
    val title: String,
    val body: String,
    /** Opaque server payload; refines the deep link (assetId / portfolioId / …). */
    val payload: JsonElement? = null,
    /** Epoch-ms the notification was read, or null when still unread. */
    val readAtMs: Long? = null,
    /**
     * Epoch-ms the notification was archived, or null when still ACTIVE. Archived
     * rows never appear on the bell surface; they live under the "Archived"/"All"
     * inbox filters. On v4 (platform PR #486) mark-read ARCHIVES eagerly — read ==
     * archived, so the default (active) list is unread-only and read history lives
     * under Archive. (The pre-v4 lazy ~7-day auto-archive sweep is retired; the app
     * stays server-driven and does not assume which behaviour a deployment runs.)
     */
    val archivedAtMs: Long? = null,
    val createdAtMs: Long,
) {
    val isUnread: Boolean get() = readAtMs == null
    val isArchived: Boolean get() = archivedAtMs != null
    val kind: NotifKind get() = NotifKind.fromType(type)
}

/**
 * Which slice of the inbox to show (Notifications-v3 #437 — `GET /notifications?view=`).
 * The bell defaults to [Active] (unread + recent, never archived); the full inbox
 * offers all three via a segmented filter. [badge = unread ACTIVE only].
 */
enum class NotifView(val wire: String) {
    Active("active"),
    Archived("archived"),
    All("all"),
}

/**
 * Presentation family for a notification type. The server matrix models the seven
 * app-configurable types (friend.request, friend.accepted, portfolio.shared,
 * alert.triggered, chat.message, account.invite, account.temp_password). The
 * remaining platform events — friend.activity, watchlist.shared, conglomerate.shared,
 * the follow-graph trio (follow.published, follow.alert.created, follow.alert.fired)
 * and the account.notice announcement — get first-class inbox rows (icon + deep
 * link) but are NOT user-configurable in the settings grid (they are not in
 * [NotificationSettingsStore.configurableKinds], so [serverModeled] = false keeps
 * them out of the settings PATCH). Each kind carries a notification [channelId] and
 * a Material icon name resolved in the UI layer.
 */
enum class NotifKind(
    val typeKey: String?,
    val channelId: String,
    /** Whether the settings grid PATCHes per-channel prefs for it (see [NotificationSettingsStore]). */
    val serverModeled: Boolean,
) {
    FriendRequest("friend.request", NotifChannels.SOCIAL, serverModeled = true),
    FriendAccepted("friend.accepted", NotifChannels.SOCIAL, serverModeled = true),
    PortfolioShared("portfolio.shared", NotifChannels.SOCIAL, serverModeled = true),
    AlertTriggered("alert.triggered", NotifChannels.PORTFOLIO, serverModeled = true),
    AccountInvite("account.invite", NotifChannels.ACCOUNT, serverModeled = true),
    AccountTempPassword("account.temp_password", NotifChannels.ACCOUNT, serverModeled = true),
    // chat.message joined the server matrix on Notifications-v2 (PR #427).
    ChatMessage("chat.message", NotifChannels.SOCIAL, serverModeled = true),
    // Platform share/activity events (#437 note): first-class inbox rows, but not
    // surfaced in the settings grid → serverModeled = false.
    FriendActivity("friend.activity", NotifChannels.SOCIAL, serverModeled = false),
    WatchlistShared("watchlist.shared", NotifChannels.SOCIAL, serverModeled = false),
    ConglomerateShared("conglomerate.shared", NotifChannels.SOCIAL, serverModeled = false),
    // Follow-graph events (V4-P0c, mobile-push.md §3.1/§4): a followed user's
    // publish / alert lifecycle. First-class inbox rows, not in the settings grid.
    // follow.published → the actor's public profile (username); follow.alert.* → the
    // asset the followed alert watches (assetId).
    FollowPublished("follow.published", NotifChannels.SOCIAL, serverModeled = false),
    FollowAlertCreated("follow.alert.created", NotifChannels.PORTFOLIO, serverModeled = false),
    FollowAlertFired("follow.alert.fired", NotifChannels.PORTFOLIO, serverModeled = false),
    // One-off in-app announcement (board #493): server-provided title/body, General
    // family, deep-links to the notification-settings screen. Per-user dismissal is
    // server-tracked via normal read/archive — nothing special client-side.
    AccountNotice("account.notice", NotifChannels.GENERAL, serverModeled = false),
    System(null, NotifChannels.GENERAL, serverModeled = false),
    ;

    companion object {
        fun fromType(type: String?): NotifKind =
            entries.firstOrNull { it.typeKey != null && it.typeKey == type } ?: System
    }
}

/** Notification-channel identifiers (created in [PushChannels]). */
object NotifChannels {
    const val SOCIAL = "bt_social"
    const val PORTFOLIO = "bt_portfolio"
    const val ACCOUNT = "bt_account"
    const val GENERAL = "bt_general"

    val all: List<String> = listOf(SOCIAL, PORTFOLIO, ACCOUNT, GENERAL)
}

/**
 * An abstract tap-through target. Kept in the data layer (no navigation
 * dependency) — the UI maps it to a concrete route. `null` ⇒ no deep link
 * (the notification just opens the inbox).
 */
sealed interface NotifDeepLink {
    /** Social tab (friends / requests). */
    data object Social : NotifDeepLink
    /** Social tab → Shared-with-me, or a specific shared portfolio. */
    data class SharedPortfolio(val portfolioId: String) : NotifDeepLink
    /** A specific friend's overview (friend.activity, when the payload identifies them). */
    data class FriendOverview(val userId: String, val username: String) : NotifDeepLink
    /**
     * An actor's public profile addressed by username only (friend.activity /
     * follow.published from FCM, which carries `data.username` but no userId; web
     * `/u/{username}`). The nav layer resolves it: a friend by that username opens
     * their overview, anyone else lands on the Social tab (never a dead tap).
     */
    data class PublicProfile(val username: String) : NotifDeepLink
    /** A friend's shared conglomerate (read-only view). */
    data class SharedConglomerate(val conglomerateId: String) : NotifDeepLink
    /** Social tab → chat list, or a specific conversation. */
    data class Chat(val conversationId: String?) : NotifDeepLink
    /** An asset page (price alerts). */
    data class Asset(val assetId: String) : NotifDeepLink
    /** A held-position detail (portfolio-scoped alerts). */
    data class Holding(val assetId: String) : NotifDeepLink
    /** Account settings (invites). */
    data object Settings : NotifDeepLink
    /** Security settings (temp-password / security events). */
    data object Security : NotifDeepLink
    /** The notification-settings screen (account.notice announcements). */
    data object NotificationSettings : NotifDeepLink
}

/**
 * Resolve the deep-link target for a notification. Pure + null-safe so it works
 * identically from the inbox row, the FCM payload, and the tapped-intent extras.
 */
fun resolveDeepLink(type: String?, payload: JsonElement?): NotifDeepLink? {
    val p = payload as? JsonObject
    fun str(vararg keys: String): String? {
        for (key in keys) {
            val v = p?.get(key)?.let { it.jsonPrimitive.contentOrNull }?.takeIf { it.isNotBlank() }
            if (v != null) return v
        }
        return null
    }

    return when (NotifKind.fromType(type)) {
        NotifKind.FriendRequest, NotifKind.FriendAccepted -> NotifDeepLink.Social
        NotifKind.PortfolioShared -> {
            val pid = str("portfolioId", "id")
            if (pid != null) NotifDeepLink.SharedPortfolio(pid) else NotifDeepLink.Social
        }
        NotifKind.FriendActivity -> {
            // In-app rows can carry BOTH id + name → open the friend's overview directly.
            // FCM twins carry only `data.username` (no userId) → PublicProfile, which the
            // nav layer resolves against the friends list (the actor IS a friend) or falls
            // back to Social. No username at all → Social. Never a dead tap (§4).
            val uid = str("friendId", "friendUserId", "userId", "actorId", "actorUserId")
            val uname = str("friendUsername", "username", "actorUsername", "actorName", "name")
            when {
                uid != null && uname != null -> NotifDeepLink.FriendOverview(uid, uname)
                uname != null -> NotifDeepLink.PublicProfile(uname)
                else -> NotifDeepLink.Social
            }
        }
        NotifKind.FollowPublished -> {
            // The actor's public profile by username. A followed user may be a
            // non-friend, so the nav layer lands on Social when no friend matches (§4).
            val uname = str("username", "actorUsername", "actorName", "name")
            if (uname != null) NotifDeepLink.PublicProfile(uname) else NotifDeepLink.Social
        }
        NotifKind.FollowAlertCreated, NotifKind.FollowAlertFired -> {
            // The asset the followed alert watches. Missing id → the inbox: the app has
            // no standalone Alerts list, so the inbox is the alerts landing surface (§4).
            val assetId = str("assetId", "symbol")
            if (assetId != null) NotifDeepLink.Asset(assetId) else null
        }
        NotifKind.ConglomerateShared -> {
            val cid = str("conglomerateId", "subjectId", "id")
            if (cid != null) NotifDeepLink.SharedConglomerate(cid) else NotifDeepLink.Social
        }
        // Watchlist-shared read view needs the owner's name, which is not reliably
        // in a notification payload → route to the Social "Shared with me" tab.
        NotifKind.WatchlistShared -> NotifDeepLink.Social
        NotifKind.ChatMessage -> NotifDeepLink.Chat(str("conversationId"))
        NotifKind.AlertTriggered -> {
            // Own price alert. Missing assetId → the inbox surface (no standalone Alerts
            // list in-app), rather than a dead tap (§4 fallback).
            val assetId = str("assetId", "symbol")
            when {
                assetId == null -> null
                str("portfolioId") != null -> NotifDeepLink.Holding(assetId)
                else -> NotifDeepLink.Asset(assetId)
            }
        }
        NotifKind.AccountInvite -> NotifDeepLink.Settings
        NotifKind.AccountTempPassword -> NotifDeepLink.Security
        // Announcement: land on the notification-settings screen (web `/settings/notifications`).
        NotifKind.AccountNotice -> NotifDeepLink.NotificationSettings
        NotifKind.System -> null
    }
}

// ── Pure archive/delete logic (Notifications-v3 #437, unit-tested) ───────────────
// The list the repository holds is the CURRENTLY-DISPLAYED view. These helpers are
// pure functions of (items, view) so the view-filtering, archive-implies-read
// mapping, badge recompute, and bulk transitions are verified without Android.

/** Whether an item belongs in [view] (archived rows never appear under Active). */
fun AppNotification.matchesView(view: NotifView): Boolean = when (view) {
    NotifView.Active -> !isArchived
    NotifView.Archived -> isArchived
    NotifView.All -> true
}

/** Filter a raw set down to a view. */
fun List<AppNotification>.forView(view: NotifView): List<AppNotification> =
    filter { it.matchesView(view) }

/** The bell badge count: UNREAD + ACTIVE only (archived never counts). */
fun List<AppNotification>.activeUnreadCount(): Int = count { it.isUnread && !it.isArchived }

/** Archive-implies-read: archiving stamps both timestamps (read stamp only if unread). */
fun AppNotification.asArchived(nowMs: Long): AppNotification =
    copy(archivedAtMs = archivedAtMs ?: nowMs, readAtMs = readAtMs ?: nowMs)

/** Unarchive restores ACTIVE; it does NOT change read state (archived items are read). */
fun AppNotification.asUnarchived(): AppNotification = copy(archivedAtMs = null)

/** Mark a single item read (no-op if already read). */
fun AppNotification.asRead(nowMs: Long): AppNotification =
    if (isUnread) copy(readAtMs = nowMs) else this

/**
 * Apply a single archive to the displayed [view] list: in Active the row leaves the
 * list; in All it stays but flips to archived+read; Archived is not an archive
 * source, so it is unchanged.
 */
fun List<AppNotification>.archiveInView(id: String, view: NotifView, nowMs: Long): List<AppNotification> =
    when (view) {
        NotifView.Active -> filterNot { it.id == id }
        NotifView.All -> map { if (it.id == id) it.asArchived(nowMs) else it }
        NotifView.Archived -> this
    }

/**
 * Apply a single unarchive: in Archived the row leaves; in All it flips to active.
 * In Active the row is normally absent (archiving removed it) — the snackbar-Undo
 * path passes the archived row as [restore], and it is re-inserted at its
 * newest-first position so the undo is visible instantly, not on the next fetch.
 */
fun List<AppNotification>.unarchiveInView(
    id: String,
    view: NotifView,
    restore: AppNotification? = null,
): List<AppNotification> =
    when (view) {
        NotifView.Archived -> filterNot { it.id == id }
        NotifView.All ->
            if (any { it.id == id }) map { if (it.id == id) it.asUnarchived() else it }
            else insertNewestFirst(restore?.asUnarchived())
        NotifView.Active ->
            if (any { it.id == id }) this
            else insertNewestFirst(restore?.asUnarchived())
    }

/** Insert [row] into this newest-first list by createdAt (no-op when null). */
private fun List<AppNotification>.insertNewestFirst(row: AppNotification?): List<AppNotification> {
    if (row == null) return this
    val at = indexOfFirst { it.createdAtMs < row.createdAtMs }
    return if (at < 0) this + row else subList(0, at) + row + subList(at, size)
}

/** Delete a single item — it leaves every view. */
fun List<AppNotification>.deleteInView(id: String): List<AppNotification> = filterNot { it.id == id }

/** Bulk "archive all read": read+active rows leave Active, flip to archived under All. */
fun List<AppNotification>.archiveAllReadInView(view: NotifView, nowMs: Long): List<AppNotification> =
    when (view) {
        NotifView.Active -> filterNot { !it.isUnread && !it.isArchived }
        NotifView.All -> map { if (!it.isUnread && !it.isArchived) it.asArchived(nowMs) else it }
        NotifView.Archived -> this
    }

/** Bulk "delete all archived": archived rows leave every view. */
fun List<AppNotification>.deleteArchivedInView(): List<AppNotification> = filterNot { it.isArchived }

/** Bulk "delete all": nothing remains. */
fun List<AppNotification>.deleteAllInView(): List<AppNotification> = emptyList()
