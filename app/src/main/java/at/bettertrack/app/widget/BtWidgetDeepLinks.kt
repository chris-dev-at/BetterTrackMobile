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

/** A portfolio widget: the Portfolio tab with that portfolio selected. */
const val BT_WIDGET_TARGET_PORTFOLIO: String = "portfolio"

/** Quick actions: the blank transaction form. */
const val BT_WIDGET_TARGET_ADD_TRANSACTION: String = "add_transaction"

/** Quick actions: the Cash screen, poised for a new entry. */
const val BT_WIDGET_TARGET_ADD_CASH: String = "add_cash"

/** Quick actions: the Markets tab (global search). */
const val BT_WIDGET_TARGET_SEARCH: String = "search"

// ── Quick Links catalog (2026-08-17) ─────────────────────────────────────────
//
// The round-3 study's nine pictograms need nine destinations. Six of them
// already existed for the widgets above; these three are what the icon grid
// added, and every one of them resolves to a screen the app genuinely has.

/** Quick Links: the chat list. */
const val BT_WIDGET_TARGET_CHAT: String = "chat"

/** Quick Links: the social feed. */
const val BT_WIDGET_TARGET_SOCIAL: String = "social"

/** Quick Links: the watchlist — a panel on the Markets tab, see [NotifDeepLink.Watchlist]. */
const val BT_WIDGET_TARGET_WATCHLIST: String = "watchlist"

/**
 * Cash Wallet: a new entry against a NAMED source, in a named direction.
 *
 * Distinct from [BT_WIDGET_TARGET_ADD_CASH] on purpose. That target is the
 * generic "open the cash screen" shortcut and stays parameterless; this one
 * always carries a source and a direction, and a tap that lost either would
 * book against the wrong wallet or with the wrong sign.
 */
const val BT_WIDGET_TARGET_CASH_ENTRY: String = "cash_entry"

/** Intent extra carrying the cash source a wallet action books against. */
const val BT_WIDGET_EXTRA_SOURCE_ID: String = "bt_widget_source_id"

/** Intent extra carrying the wallet action's direction ("1" in, "0" out). */
const val BT_WIDGET_EXTRA_INFLOW: String = "bt_widget_inflow"

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
    sourceId: String? = null,
    inflow: Boolean? = null,
): NotifDeepLink? = when (target) {
    BT_WIDGET_TARGET_OVERVIEW -> NotifDeepLink.Overview
    BT_WIDGET_TARGET_ASSET ->
        assetId?.takeIf { it.isNotBlank() }?.let { NotifDeepLink.Asset(it) }
            ?: NotifDeepLink.Overview
    // A Quick-Links tile may aim this at ONE wallet (owner 2026-08-18: "3
    // buttons that each bring me to the overview of another cash source"), and
    // the Cash screen scopes itself to it. Both stay blank-tolerant, so the
    // budget widget's parameterless Cash tap is unchanged.
    BT_WIDGET_TARGET_CASH -> NotifDeepLink.Cash(
        portfolioId = portfolioId?.takeIf { it.isNotBlank() },
        sourceId = sourceId?.takeIf { it.isNotBlank() },
    )
    // A portfolio target with no usable id falls back to the Overview — the one
    // place every portfolio is visible — by the same never-a-dead-tap rule the
    // asset target follows above.
    BT_WIDGET_TARGET_PORTFOLIO ->
        portfolioId?.takeIf { it.isNotBlank() }?.let { NotifDeepLink.Portfolio(it) }
            ?: NotifDeepLink.Overview
    // The Quick-actions tiles (2026-08-16). Both now carry the tile's optional
    // aim (2026-08-18) — "add transaction, but where to?" — and both degrade to
    // exactly their old parameterless behaviour when the tile names nothing.
    //
    // Note ADD_CASH deliberately passes NO direction: it opens the ledger entry
    // on the named wallet with no sign preselected, unlike the Cash Wallet
    // widget's Bezahlt/Erhalten buttons below, which always carry one.
    BT_WIDGET_TARGET_ADD_TRANSACTION ->
        NotifDeepLink.AddTransaction(portfolioId?.takeIf { it.isNotBlank() })
    BT_WIDGET_TARGET_ADD_CASH -> NotifDeepLink.AddCashEntry(
        portfolioId = portfolioId?.takeIf { it.isNotBlank() },
        sourceId = sourceId?.takeIf { it.isNotBlank() },
    )
    BT_WIDGET_TARGET_SEARCH -> NotifDeepLink.MarketSearch
    // The Quick Links catalog's three additions (2026-08-17). Chat takes the
    // nullable conversation id its target already carries — a launcher tile
    // opens the LIST, never someone's thread.
    BT_WIDGET_TARGET_CHAT -> NotifDeepLink.Chat(null)
    BT_WIDGET_TARGET_SOCIAL -> NotifDeepLink.Social
    BT_WIDGET_TARGET_WATCHLIST -> NotifDeepLink.Watchlist
    /*
     * A Cash Wallet posting button. The direction is REQUIRED: without it the
     * tap would land on a blank sheet, which is the "never a dead tap" rule's
     * money-shaped cousin — a button labelled "Bezahlt" that opens something
     * neutral has lied about what it does. So an unknown direction degrades to
     * the plain Cash screen for that portfolio rather than to a half-preselected
     * entry sheet. A blank source is tolerated: the sheet's own primary-source
     * default is a correct answer, just not a preselected one.
     */
    BT_WIDGET_TARGET_CASH_ENTRY ->
        if (inflow == null) {
            NotifDeepLink.Cash(portfolioId?.takeIf { it.isNotBlank() })
        } else {
            NotifDeepLink.AddCashEntry(
                portfolioId = portfolioId?.takeIf { it.isNotBlank() },
                sourceId = sourceId?.takeIf { it.isNotBlank() },
                inflow = inflow,
            )
        }
    else -> null
}

/**
 * The slug for the target-less "just open the app" tap.
 *
 * Deliberately NOT one of the `BT_WIDGET_TARGET_*` constants above and
 * deliberately never written into [BT_WIDGET_EXTRA_TARGET]: those name a
 * DESTINATION, and this one exists precisely to say there is no destination.
 * It lives here only so [BT_WIDGET_LAUNCH_ACTION] is built out of the same
 * vocabulary as every other widget action string, which is what keeps the
 * uniqueness property one function's business.
 *
 * [btWidgetDeepLink] therefore returns `null` for it — pinned by
 * `BtWidgetLaunchIntentTest` — so even if it ever leaked into an extra, the
 * tap would open the app on no page rather than somewhere invented.
 */
const val BT_WIDGET_LAUNCH_SLUG: String = "launch"

/**
 * The action on the plain-launch intent.
 *
 * A distinct action for the same reason every targeted intent has one:
 * `PendingIntent` equality is `Intent.filterEquals`, which ignores extras. All
 * ten widgets share this one string on purpose — every plain launch does the
 * identical thing, so collapsing them onto one `PendingIntent` is correct — but
 * it can never collapse into a TARGETED intent, because no
 * `btWidgetIntentAction(target, …)` over the `BT_WIDGET_TARGET_*` vocabulary
 * can produce it.
 */
val BT_WIDGET_LAUNCH_ACTION: String = btWidgetIntentAction(BT_WIDGET_LAUNCH_SLUG, null)

/**
 * **Just open the app.** The whole-card tap of a widget that is not pointing at
 * one specific thing (owner ruling 2026-08-18: *"the default thing if you click
 * any widget should be not the overview but just open the app. on no specific
 * page. because always getting set to overview is annoying when you click the
 * edge of a widget."*).
 *
 * What makes it a no-op navigationally is what it does NOT carry: no
 * [BT_WIDGET_EXTRA_TARGET], so `MainActivity.handleWidgetIntent` returns on its
 * first line, nothing is parked on `AppGraph.pendingDeepLink`, and `AppShell`
 * runs none of its landing discipline. The app simply comes forward on whatever
 * screen it was left on.
 *
 * `FLAG_ACTIVITY_CLEAR_TOP` is absent for the same reason, and that absence is
 * the load-bearing difference from [btWidgetIntent]: CLEAR_TOP tears the task
 * back down to this activity, which is the opposite of "wherever you left it".
 * `FLAG_ACTIVITY_NEW_TASK` is required because the caller is the launcher's
 * process, not an activity of ours. `CATEGORY_LAUNCHER` states what this start
 * is — the same thing tapping the app icon does — even though the explicit
 * component means nothing has to resolve it.
 */
fun btWidgetLaunchIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        action = BT_WIDGET_LAUNCH_ACTION
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

/**
 * The intent a widget click launches — the TARGETED one. See
 * [btWidgetLaunchIntent] for the whole-card default, which carries no target at
 * all.
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
    sourceId: String? = null,
    inflow: Boolean? = null,
): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        action = btWidgetIntentAction(
            target,
            assetId ?: portfolioId,
            // Bezahlt and Erhalten differ ONLY in these two, and PendingIntent
            // equality ignores extras — without them in the action string the
            // launcher would collapse both buttons onto whichever was
            // registered first, and every "Erhalten" tap would book an outflow.
            // This is the same collapse the per-row asset id prevents above,
            // with money on the line instead of a wrong asset page.
            qualifier = listOfNotNull(
                sourceId?.takeIf { it.isNotBlank() },
                inflow?.let { if (it) "in" else "out" },
            ).takeIf { it.isNotEmpty() }?.joinToString("."),
        )
        putExtra(BT_WIDGET_EXTRA_TARGET, target)
        if (assetId != null) putExtra(BT_WIDGET_EXTRA_ASSET_ID, assetId)
        if (portfolioId != null) putExtra(BT_WIDGET_EXTRA_PORTFOLIO_ID, portfolioId)
        if (sourceId != null) putExtra(BT_WIDGET_EXTRA_SOURCE_ID, sourceId)
        if (inflow != null) putExtra(BT_WIDGET_EXTRA_INFLOW, if (inflow) "1" else "0")
    }

/**
 * Pure, so the uniqueness property can be asserted without an Android context.
 * [discriminator] is the per-row id (an asset, or a portfolio) that makes two
 * otherwise-identical targets distinct PendingIntents; [qualifier] carries the
 * rest of what distinguishes one tile from another that shares both (a cash
 * source and a money direction).
 */
fun btWidgetIntentAction(
    target: String,
    discriminator: String?,
    qualifier: String? = null,
): String = buildString {
    append("bt.widget.open.")
    append(target)
    discriminator?.let { append('.').append(it) }
    qualifier?.let { append('.').append(it) }
}
