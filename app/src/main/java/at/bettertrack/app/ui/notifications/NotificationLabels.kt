package at.bettertrack.app.ui.notifications

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R
import at.bettertrack.app.data.notifications.DigestCadence
import at.bettertrack.app.data.notifications.NotifCatalog
import at.bettertrack.app.data.notifications.NotifChannel

/**
 * Display names for the routing matrix: the platform's 26 wire types, its 8
 * categories, and its 6 channels.
 *
 * ## Why this is a second family of type labels
 *
 * `strings.xml` already has a `bt_notif_type_*` family. Those name the app's INBOX
 * presentation kinds ([at.bettertrack.app.data.notifications.NotifKind]) — seven
 * display groups with subtitles, several of which fold multiple wire types
 * together and one of which ("System") is a catch-all. The routing grid needs one
 * label per WIRE type, worded as the web words it, so the two surfaces can be read
 * side by side. The sets neither match one-to-one nor should be forced to: merging
 * them would make the inbox rename its rows to suit a settings screen.
 *
 * ## Unknown types are named, not hidden
 *
 * [notifTypeLabel] falls back to the raw wire key. If the platform ships a
 * twenty-seventh type before the app learns its name, the row appears with
 * `standing_order.retried` as its title — ugly, and far better than a setting the
 * user owns being invisible.
 */
@StringRes
fun notifTypeLabelRes(type: String): Int? = when (type) {
    "friend.request" -> R.string.bt_ntype_friend_request
    "friend.accepted" -> R.string.bt_ntype_friend_accepted
    "portfolio.shared" -> R.string.bt_ntype_portfolio_shared
    "watchlist.shared" -> R.string.bt_ntype_watchlist_shared
    "conglomerate.shared" -> R.string.bt_ntype_conglomerate_shared
    "friend.activity" -> R.string.bt_ntype_friend_activity
    "follow.published" -> R.string.bt_ntype_follow_published
    "follow.alert.created" -> R.string.bt_ntype_follow_alert_created
    "follow.alert.fired" -> R.string.bt_ntype_follow_alert_fired
    "chat.message" -> R.string.bt_ntype_chat_message
    "alert.triggered" -> R.string.bt_ntype_alert_triggered
    "standing_order.skipped" -> R.string.bt_ntype_standing_order_skipped
    "budget.exceeded" -> R.string.bt_ntype_budget_exceeded
    "earnings.reminder" -> R.string.bt_ntype_earnings_reminder
    "dividend.event" -> R.string.bt_ntype_dividend_event
    "mirror.invite" -> R.string.bt_ntype_mirror_invite
    "mirror.member_joined" -> R.string.bt_ntype_mirror_member_joined
    "mirror.member_left" -> R.string.bt_ntype_mirror_member_left
    "mirror.member_removed" -> R.string.bt_ntype_mirror_member_removed
    "mirror.removed" -> R.string.bt_ntype_mirror_removed
    "mirror.ownership_transferred" -> R.string.bt_ntype_mirror_ownership_transferred
    "mirror.chain_dissolved" -> R.string.bt_ntype_mirror_chain_dissolved
    "mirror.sync_stalled" -> R.string.bt_ntype_mirror_sync_stalled
    "account.invite" -> R.string.bt_ntype_account_invite
    "account.temp_password" -> R.string.bt_ntype_account_temp_password
    "account.data_export" -> R.string.bt_ntype_account_data_export
    else -> null
}

/** The type's display name, or the raw wire key when the catalogue predates it. */
@Composable
fun notifTypeLabel(type: String): String =
    notifTypeLabelRes(type)?.let { stringResource(it) } ?: type

@StringRes
fun notifCategoryLabelRes(key: String): Int = when (key) {
    NotifCatalog.CAT_SOCIAL -> R.string.bt_ncat_social
    NotifCatalog.CAT_SHARING -> R.string.bt_ncat_sharing
    NotifCatalog.CAT_CHAT -> R.string.bt_ncat_chat
    NotifCatalog.CAT_ALERTS -> R.string.bt_ncat_alerts
    NotifCatalog.CAT_BUDGETS -> R.string.bt_ncat_budgets
    NotifCatalog.CAT_MARKETS -> R.string.bt_ncat_markets
    NotifCatalog.CAT_MIRRORCHAIN -> R.string.bt_ncat_mirrorchain
    else -> R.string.bt_ncat_account
}

@StringRes
fun notifChannelLabelRes(channel: NotifChannel): Int = when (channel) {
    NotifChannel.InApp -> R.string.bt_nch_inapp
    NotifChannel.Email -> R.string.bt_nch_email
    NotifChannel.Telegram -> R.string.bt_nch_telegram
    NotifChannel.Discord -> R.string.bt_nch_discord
    NotifChannel.Push -> R.string.bt_nch_push
    NotifChannel.WebPush -> R.string.bt_nch_webpush
}

@StringRes
fun notifCadenceLabelRes(cadence: DigestCadence): Int = when (cadence) {
    DigestCadence.Instant -> R.string.bt_notif_cadence_instant
    DigestCadence.Daily -> R.string.bt_notif_cadence_daily
    DigestCadence.Weekly -> R.string.bt_notif_cadence_weekly
}

/**
 * The locked-row note, or `null` for the types that have none.
 *
 * Only two of the four locked cases carry a note on the web, and the asymmetry is
 * deliberate there: `account.invite` and `account.temp_password` need explaining
 * ("why can't I change this?"), while a locked email cell on `budget.exceeded` or
 * `account.data_export` gets nothing. Mirrored rather than improved, so the two
 * surfaces say the same thing.
 */
@StringRes
fun notifLockedNoteRes(type: String): Int? = when (type) {
    NotifCatalog.ACCOUNT_INVITE -> R.string.bt_notif_lock_invite
    "account.temp_password" -> R.string.bt_notif_lock_temp_password
    else -> null
}
