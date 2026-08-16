package at.bettertrack.app.widget

import android.content.Context
import android.content.Intent
import at.bettertrack.app.MainActivity
import at.bettertrack.app.data.notifications.NotifDeepLink

/**
 * Where a widget tap lands.
 *
 * ## Why this reuses [NotifDeepLink] instead of adding a nav scheme
 *
 * The app already has exactly one vocabulary of tap-through targets and one
 * mechanism for delivering them from outside: `MainActivity` parks a
 * [NotifDeepLink] on `AppGraph.pendingDeepLink`, and `AppShell` consumes it once
 * with the full landing discipline — clear the sheet stack, switch to the tab
 * that OWNS the target, then push. A widget that navigated by any other route
 * would miss all three, and the saved-state hazard `owningTab` exists to prevent
 * would come straight back.
 *
 * So a widget tap is resolved to the same sealed interface, and the only thing
 * added to it is [NotifDeepLink.Overview] — a target the app genuinely did not
 * have, because Overview stopped being a tab in the 2026-08-05 IA change and
 * nothing external had needed to address it since.
 *
 * ## Why not the notification extras
 *
 * `resolveDeepLink(type, payload)` maps the FCM WIRE — server notification kinds
 * to targets. A widget is not a notification, and dressing an Overview tap up as
 * an `alert.triggered` to smuggle it through that function would put a fiction
 * in the one place the push contract is written down. These extras are separate
 * and resolved by [btWidgetDeepLink], which is pure and therefore testable.
 */

/** Intent extra naming the widget target. */
const val BT_WIDGET_EXTRA_TARGET: String = "bt_widget_target"

/** Intent extra carrying the asset a watchlist / movers row points at. */
const val BT_WIDGET_EXTRA_ASSET_ID: String = "bt_widget_asset_id"

/** Intent extra carrying the portfolio a budget widget's Cash tap opens. */
const val BT_WIDGET_EXTRA_PORTFOLIO_ID: String = "bt_widget_portfolio_id"

/** The net-worth / stats widgets: the Overview the app opens on. */
const val BT_WIDGET_TARGET_OVERVIEW: String = "overview"

/** A watchlist / movers row: that asset's market page. */
const val BT_WIDGET_TARGET_ASSET: String = "asset"

/** The budget widget: the Cash screen for the budgeted portfolio. */
const val BT_WIDGET_TARGET_CASH: String = "cash"

/**
 * Resolve widget extras to a target. Pure and null-safe, in the same shape as
 * `resolveDeepLink`, so the mapping can be pinned on the JVM.
 *
 * An asset target with no usable id falls back to Overview rather than to
 * `null`: a tap that opens the app somewhere sensible is always better than a
 * tap that appears to do nothing, which is the bug
 * [NotifDeepLink.Inbox] was introduced to fix on the push path. The Cash target
 * tolerates a null portfolio — [NotifDeepLink.Cash] carries it as nullable and the
 * Cash screen resolves the selected portfolio itself when it is absent.
 */
fun btWidgetDeepLink(
    target: String?,
    assetId: String?,
    portfolioId: String? = null,
): NotifDeepLink? = when (target) {
    BT_WIDGET_TARGET_OVERVIEW -> NotifDeepLink.Overview
    BT_WIDGET_TARGET_ASSET ->
        assetId?.takeIf { it.isNotBlank() }?.let { NotifDeepLink.Asset(it) }
            ?: NotifDeepLink.Overview
    BT_WIDGET_TARGET_CASH -> NotifDeepLink.Cash(portfolioId?.takeIf { it.isNotBlank() })
    else -> null
}

/**
 * The intent a widget click launches.
 *
 * The unique [Intent.setAction] is load-bearing, and for the same reason
 * `BtMessagingService` sets one: `PendingIntent` equality ignores extras, so
 * without a distinct action every row in the watchlist would collapse onto
 * whichever target happened to be registered first.
 */
fun btWidgetIntent(
    context: Context,
    target: String,
    assetId: String? = null,
    portfolioId: String? = null,
): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        action = btWidgetIntentAction(target, assetId ?: portfolioId)
        putExtra(BT_WIDGET_EXTRA_TARGET, target)
        if (assetId != null) putExtra(BT_WIDGET_EXTRA_ASSET_ID, assetId)
        if (portfolioId != null) putExtra(BT_WIDGET_EXTRA_PORTFOLIO_ID, portfolioId)
    }

/**
 * Pure, so the uniqueness property can be asserted without an Android context.
 * [discriminator] is the per-row id (an asset, or a portfolio) that makes two
 * otherwise-identical targets distinct PendingIntents.
 */
fun btWidgetIntentAction(target: String, discriminator: String?): String =
    if (discriminator == null) "bt.widget.open.$target" else "bt.widget.open.$target.$discriminator"
