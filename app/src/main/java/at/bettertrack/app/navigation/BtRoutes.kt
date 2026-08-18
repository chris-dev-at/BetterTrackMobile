package at.bettertrack.app.navigation

import kotlinx.serialization.Serializable

/**
 * Typed navigation routes for the WHOLE app — every future destination is
 * registered up-front (Step 3) so later steps only replace placeholder content,
 * never rewire navigation. Placeholder params use nullable defaults where the
 * real identifier arrives in a later step.
 */

// ── Auth & lock ────────────────────────────────────────────────────────────
// S6 P2-19: LoginRoute / AppLockRoute were declared here and registered as
// "Under construction" placeholders, but nothing ever navigated to them — login
// and the app lock are BtRoot gates that run OUTSIDE this graph. Both are gone.
/** Step 17 (§5): set-up / change-PIN flow. [change] gates verify-current-first. */
@Serializable data class AppLockSetupRoute(val change: Boolean = false)

// ── The sheet stack's floor ────────────────────────────────────────────────
/**
 * The graph's start destination, and the only one that draws nothing.
 *
 * Architecture change 2026-08-08: the four tabs left this graph for the live
 * pager ([at.bettertrack.app.ui.shell.BtTabPager]) and every remaining route
 * became a full-screen sheet over them ([at.bettertrack.app.ui.shell.BtSheet]).
 * A `NavHost` still needs somewhere to start and something for the last sheet to
 * pop back to, and "the tabs" is no longer an answer it can give — so the floor
 * is this: an empty destination whose whole meaning is *no sheet is open*.
 *
 * It is a real route rather than, say, a null check on the back-stack size
 * because the shell asks the question constantly (`sheetsClosed`) and a typed
 * `hasRoute` is the one form of that question that cannot go stale.
 */
@Serializable data object SheetRootRoute

// ── Tabs (top-level) ───────────────────────────────────────────────────────
//
// R-arc R1 (mandate §2) declared FIVE destinations, in bar order —
// Home · Portfolio · Workbench · Markets · People. Three of them were renamed
// rather than replaced: the *screens* behind Markets and People are the same
// ones Assets and Social hosted, and Workbench is the Workboard tab under the
// label the mandate asks for. Renaming the route objects (not aliasing them)
// keeps exactly one name per destination, so nothing in the graph can drift.
//
// Owner IA change 2026-08-05: `HomeTabRoute` is GONE — four tabs, Portfolio
// first and start. Home's content did not go with it: it is now "Overview", the
// pinned first entry of the portfolio switcher, rendered by the Portfolio tab.
// A route object with no destination is exactly the kind of drift the comment
// above warns about, so it was deleted rather than deprecated.
/*
 * The four `...TabRoute` objects are DELETED (architecture change 2026-08-08).
 *
 * Portfolio · Markets · Workbench · People are not destinations any more. They
 * are four permanently-composed pages in a pager under this whole graph — see
 * [at.bettertrack.app.ui.shell.BtTabPager] for why, and [SheetRootRoute] for what
 * the graph starts on instead. Route objects nothing registers and nothing
 * navigates to are exactly the drift the note at the top of this section warns
 * about, so they went with the destinations rather than being left behind as
 * names. The tab set itself lives on as [BtTab].
 */

// ── Portfolio ──────────────────────────────────────────────────────────────
@Serializable data class HoldingDetailRoute(val holdingId: String)
@Serializable data class TransactionsRoute(val portfolioId: String? = null)
/**
 * Buy/sell form (Step 8, §6.2). Exactly one mode:
 *  - [opId] set        ⇒ edit a QUEUED op (pending / needs-attention retry);
 *  - [transactionId]   ⇒ edit a SYNCED transaction (online-only, §7.2);
 *  - neither           ⇒ record a new transaction ([assetId] pre-fills the
 *                        asset from holding detail; [portfolioId] overrides
 *                        the governing switcher selection).
 */
@Serializable data class TransactionFormRoute(
    val transactionId: String? = null,
    val portfolioId: String? = null,
    val assetId: String? = null,
    val opId: Long? = null,
    /** Step 11 search-buy: asset identity passed through so a NOT-yet-held asset
     *  binds instantly without a holdings lookup; [sell] preselects the side. */
    val assetSymbol: String? = null,
    val assetName: String? = null,
    /** Native currency (Step 19) so a not-yet-held asset labels its price correctly. */
    val assetCurrency: String? = null,
    val sell: Boolean = false,
)
/**
 * Cash screen (Step 9, §6.3). [editOpId] deep-links straight into editing a
 * queued cash op (pending-sync "Edit & retry" for deposits/withdrawals/transfers).
 *
 * [initialSourceId] / [initialInflow] are the Cash Wallet widget's entry point
 * (2026-08-17): its `Bezahlt` / `Erhalten` buttons name a wallet and a
 * direction, so the screen opens the entry sheet already on both rather than
 * making the user re-pick what the widget already knew. Both default to null,
 * which is the pre-existing behaviour — no sheet, primary source.
 */
@Serializable data class CashRoute(
    val portfolioId: String? = null,
    val editOpId: Long? = null,
    val initialSourceId: String? = null,
    val initialInflow: Boolean? = null,
)

/**
 * The cash ledger subpage (owner UI batch 2026-08-16): the movement stream
 * moved OFF the cash overview onto its own page. [sourceId] carries the
 * overview's source-switcher selection through, so the list opens already
 * narrowed to what the user was looking at.
 *
 * [month] (`YYYY-MM`) is the same idea for the cash-flow chart's selected bar
 * (owner ask 2026-08-17): tapping a month's readout opens this list already
 * narrowed to that month's date range, so the bar and the rows behind it are
 * one tap apart instead of a filter the user has to rebuild by hand.
 */
@Serializable data class CashLedgerRoute(
    val portfolioId: String? = null,
    val sourceId: String? = null,
    val month: String? = null,
)

/**
 * The budgets subpage (same batch): the full budget management block — month
 * stepper, per-budget bars with edit/delete, creation — plus the month summary
 * that shares its stepper. The overview keeps only a brief per-budget bar list.
 */
@Serializable data class CashBudgetsRoute(val portfolioId: String? = null)

/**
 * V5 S2c cash-classification surfaces, reached from the cash screen's overflow.
 * Tags and rules are per USER, not per portfolio (a label means the same thing
 * in every ledger the account owns), so neither route carries a portfolio id.
 */
@Serializable data object CashTagsRoute                                     // manage tags (§v5 cash:*)
@Serializable data object CashRulesRoute                                    // auto-tag rules (§v5 cash:*)

/**
 * Standing orders — scheduled recurring buys / cash movements. Portfolio-scoped
 * because an order books into one ledger; a null id lists every portfolio's.
 */
@Serializable data class StandingOrdersRoute(val portfolioId: String? = null)
@Serializable data object CustomAssetsRoute                                 // custom-asset list (§6.4)
@Serializable data class CustomAssetDetailRoute(val assetId: String)        // custom-asset detail (§6.4)

// ── Market ─────────────────────────────────────────────────────────────────
@Serializable data class AssetPageRoute(val assetId: String)
@Serializable data object SearchRoute
/**
 * V5 S2c: portfolio-wide market intel — earnings + dividend calendars, the
 * projected-income summary and the grouped news digest. Reached from the Assets
 * tab. A destination rather than another Assets-tab panel because it is four
 * lists deep and would bury the watchlists it sits next to.
 */
@Serializable data object MarketIntelRoute
// S6 P2-19: WatchlistRoute is gone — watchlists are a PANEL inside the Assets
// tab (WatchlistPanel), never a destination of their own.

// ── Workboard ──────────────────────────────────────────────────────────────
// S6 P2-19: ConglomerateListRoute is gone — the list is a SEGMENT of the
// Workboard tab, composed directly by WorkboardScreen.
@Serializable data class ConglomerateBuilderRoute(val conglomerateId: String? = null)
@Serializable data class ConglomerateDetailRoute(val conglomerateId: String)
/**
 * V5 S2c: one saved workboard idea (name + thesis + the backtest setup behind
 * it). The list is a SEGMENT of the Workboard tab, like conglomerates and
 * alerts; only the detail is a destination.
 */
@Serializable data class IdeaDetailRoute(val ideaId: String)

// ── Social ─────────────────────────────────────────────────────────────────
/** Per-friend overview (Social v2): profile + everything they share + go-to-chat + remove. */
@Serializable data class FriendOverviewRoute(val userId: String, val username: String)
/**
 * V5 S2c: friend groups — named sets of friends that act as ONE sharing
 * audience. Reached from the Social tab's Friends section, and from the
 * audience picker when the user has no group to share to yet.
 */
@Serializable data object FriendGroupsRoute
/** Read-only friend-shared views (Step 14, §6.9). */
@Serializable data class SharedPortfolioViewRoute(val portfolioId: String)
// `ownerName` was dropped from this route 2026-08-09. The screen was handed it,
// threaded it through two callers, and rendered `d.owner.username` from the
// detail response instead — the argument was never read. Its two sibling routes
// (`SharedPortfolioViewRoute`, `SharedConglomerateViewRoute`) always carried the
// id alone; this one now matches them.
@Serializable data class SharedWatchlistViewRoute(val watchlistId: String)
@Serializable data class SharedConglomerateViewRoute(val conglomerateId: String)
@Serializable data object ChatListRoute                                     // Step 15
/**
 * A 1:1 thread (Step 15, §6.10). Either [conversationId] (open existing) or
 * [friendUserId]+[friendUsername] (open-or-create with a friend) is set.
 */
@Serializable data class ChatThreadRoute(
    val conversationId: String? = null,
    val friendUserId: String? = null,
    val friendUsername: String = "",
)
@Serializable data object NotificationsInboxRoute

// ── Settings (spec §6.12) ────────────────────────────────────────────────────
@Serializable data object SettingsRoute
// S6 P2-19: SettingsAccountRoute is gone — account settings live on SettingsRoute
// itself; the separate route only ever rendered "Under construction".
@Serializable data object SettingsSecurityRoute
@Serializable data object SettingsNotificationsRoute
@Serializable data object SettingsLanguageRoute
@Serializable data object SettingsAboutRoute

/**
 * The in-app feedback composer (platform #1315/#1316/#1317).
 *
 * [origin] is only ever `settings` or `about` and rides straight into the
 * submission's `context.screen`, so a report can be traced to where it was written
 * from. It is a route parameter rather than a constant because the two entry points
 * are genuinely different populations — a bug reported from About is usually about
 * the build, one from Settings usually about a setting.
 *
 * Registered unconditionally; the ENTRY POINTS are what
 * [at.bettertrack.app.data.repo.FeedbackFlags.enabled] gates. A route with no row
 * pointing at it is unreachable, and keeping it registered means turning the
 * feature on is one flag rather than a re-wire.
 */
@Serializable data class SettingsFeedbackRoute(val origin: String)

/**
 * The in-app widget builder (widget redesign 2026-08-16): preview every
 * home-screen widget with sample data, configure the configurable ones, and
 * pin them to the launcher pre-configured via `requestPinAppWidget`.
 */
@Serializable data object SettingsWidgetsRoute
/**
 * Settings → Security → Account PIN (the ACCOUNT credential).
 *
 * Deliberately a separate route from [AppLockSetupRoute]: that one sets the PIN
 * that guards THIS PHONE, this one the PIN the account carries everywhere. The
 * two screens must never be reachable from the same row.
 */
@Serializable data object AccountPinRoute
/**
 * The in-app heatmap (owner ask 2026-08-18): the account's own universes drawn
 * as area + direction. Named for what it shows, never "Markt" — the platform has
 * no market-wide endpoint to justify that word.
 */
@Serializable data object HeatmapRoute
/** Settings → Account → Public profile: the public opt-in and the bio. */
@Serializable data object PublicProfileRoute
/** Settings → Privacy → Export my data: request, poll, download. */
@Serializable data object DataExportRoute
/** Step 18: change the account password (current + new + confirm). */
@Serializable data object ChangePasswordRoute
/** Step 18: 2FA management (TOTP enroll/QR, email codes, recovery codes, disable). */
@Serializable data object TwoFactorRoute
/** Step 18: the account's active web/other-device sessions (list + revoke). */
@Serializable data object ActiveSessionsRoute
/** Step 18: type-to-confirm account deletion (destructive; submit safety-gated). */
@Serializable data object DeleteAccountRoute
/** In-app changelog / "New features" (owner 2026-07-09) — bundled per-version notes. */
@Serializable data object ChangelogRoute
/**
 * V5 W5 — Settings → "Where your data lives" (S3/S4 plan §4.2 step 5): the mode,
 * the backup status, the vault's key actions and the §1.4 medium changes.
 */
@Serializable data object StorageHomeRoute

/**
 * Settings → Taxes: the USER-level tax default, i.e. what a newly created
 * portfolio inherits. The per-portfolio override is [PortfolioTaxRoute].
 */
@Serializable data object TaxSettingsRoute

// ── Connections & API, native (owner order 2026-08-08) ─────────────────────
//
// These two were `BtWebLinkRow`s into `/control/connections` and
// `/control/authorized-apps` until the owner ruled that connections and
// authorized apps are handled INSIDE the app and must not redirect. They are
// now real screens at full web parity. API keys, OAuth apps and webhooks stay
// hand-offs — the owner named only these two, and each of those three shows a
// secret exactly once at creation time.

/** Settings → Connections: the Google identity, Drive, and the future connectors. */
@Serializable data object ConnectionsRoute

/** Settings → Authorized apps: the OAuth grants on this account, with revoke. */
@Serializable data object AuthorizedAppsRoute

// ── Management parity 2026-08-06 ───────────────────────────────────────────
//
// The owner's ask was full parity with the web app for portfolios and groups.
// These four routes are what that needed, and they are all portfolio-SCOPED —
// each carries the id it acts on rather than reading the ambient switcher
// selection. That is deliberate: a settings screen that silently retargeted
// itself because the selection changed underneath it would be the worst
// possible place for that class of bug.

/** One portfolio's settings: name, sharing, taxes, group, archive/delete. */
@Serializable data class PortfolioSettingsRoute(val portfolioId: String)

/**
 * The Insights Studio (owner UI batch 2026-08-16; grown into a studio by the
 * 2026-08-18 ask). Seeded with the portfolio it was opened from — the page can
 * widen its own scope to every portfolio, but it never silently retargets.
 */
@Serializable data class PortfolioInsightsRoute(val portfolioId: String)

/**
 * The Insights report builder.
 *
 * [preselect] carries a `BtInsight` name when the user arrived through one
 * card's `Als PDF exportieren`, so that card starts checked. Null means the
 * builder opens on the recommended selection.
 */
@Serializable data class InsightsReportRoute(
    val portfolioId: String,
    val preselect: String? = null,
)

/** One portfolio's tax override, rendered through the effective/override cascade. */
@Serializable data class PortfolioTaxRoute(val portfolioId: String)

/** One portfolio's tax years — the report list. */
@Serializable data class TaxYearsRoute(val portfolioId: String)

/** One tax year's drill-down, with the CSV export. */
@Serializable data class TaxYearRoute(val portfolioId: String, val year: Int)

/** Group (mirrorchain) administration for one chain. */
@Serializable data class ChainManageRoute(val chainId: String)

// ── Sync & debug ───────────────────────────────────────────────────────────
@Serializable data object PendingSyncRoute
@Serializable data object GalleryRoute                                      // debug component gallery
@Serializable data object SyncDebugRoute                                    // Step-5 sync-queue debug screen
@Serializable data object ServerRoute                                       // Settings → Server (github flavor)
