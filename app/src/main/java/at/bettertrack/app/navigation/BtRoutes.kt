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

// ── Tabs (top-level) ───────────────────────────────────────────────────────
@Serializable data object PortfolioTabRoute     // portfolio overview — TODO(step 6)
@Serializable data object AssetsTabRoute        // watchlists + search entry — TODO(steps 11–12)
@Serializable data object SocialTabRoute        // friends — TODO(step 14)
@Serializable data object WorkboardTabRoute     // conglomerate list — TODO(step 13)

// ── Portfolio ──────────────────────────────────────────────────────────────
@Serializable data class HoldingDetailRoute(val holdingId: String)          // TODO(step 7)
@Serializable data class TransactionsRoute(val portfolioId: String? = null) // TODO(step 7)
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
 */
@Serializable data class CashRoute(
    val portfolioId: String? = null,
    val editOpId: Long? = null,
)

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
@Serializable data class AssetPageRoute(val assetId: String)                // TODO(step 11)
@Serializable data object SearchRoute                                       // TODO(step 11)
// S6 P2-19: WatchlistRoute is gone — watchlists are a PANEL inside the Assets
// tab (WatchlistPanel), never a destination of their own.

// ── Workboard ──────────────────────────────────────────────────────────────
// S6 P2-19: ConglomerateListRoute is gone — the list is a SEGMENT of the
// Workboard tab, composed directly by WorkboardScreen.
@Serializable data class ConglomerateBuilderRoute(val conglomerateId: String? = null) // TODO(step 13)
@Serializable data class ConglomerateDetailRoute(val conglomerateId: String) // TODO(step 13)

// ── Social ─────────────────────────────────────────────────────────────────
/** Per-friend overview (Social v2): profile + everything they share + go-to-chat + remove. */
@Serializable data class FriendOverviewRoute(val userId: String, val username: String)
/** Read-only friend-shared views (Step 14, §6.9). */
@Serializable data class SharedPortfolioViewRoute(val portfolioId: String)
@Serializable data class SharedWatchlistViewRoute(val watchlistId: String, val ownerName: String)
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
@Serializable data object NotificationsInboxRoute                           // TODO(step 16)

// ── Settings (spec §6.12) ────────────────────────────────────────────────────
@Serializable data object SettingsRoute
// S6 P2-19: SettingsAccountRoute is gone — account settings live on SettingsRoute
// itself; the separate route only ever rendered "Under construction".
@Serializable data object SettingsSecurityRoute
@Serializable data object SettingsNotificationsRoute
@Serializable data object SettingsLanguageRoute
@Serializable data object SettingsAboutRoute
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

// ── Sync & debug ───────────────────────────────────────────────────────────
@Serializable data object PendingSyncRoute                                  // TODO(step 8, §7.4)
@Serializable data object GalleryRoute                                      // debug component gallery
@Serializable data object SyncDebugRoute                                    // Step-5 sync-queue debug screen
@Serializable data object DevBackendRoute                                   // V5 S1 dev API/web origin override
