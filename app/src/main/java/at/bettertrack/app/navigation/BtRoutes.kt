package at.bettertrack.app.navigation

import kotlinx.serialization.Serializable

/**
 * Typed navigation routes for the WHOLE app — every future destination is
 * registered up-front (Step 3) so later steps only replace placeholder content,
 * never rewire navigation. Placeholder params use nullable defaults where the
 * real identifier arrives in a later step.
 */

// ── Auth & lock ────────────────────────────────────────────────────────────
@Serializable data object LoginRoute            // TODO(step 4)
@Serializable data object AppLockRoute          // reserved (lock is a BtRoot gate, not a nav route)
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
@Serializable data object CustomAssetsRoute                                 // custom-asset list (§6.4)
@Serializable data class CustomAssetDetailRoute(val assetId: String)        // custom-asset detail (§6.4)

// ── Market ─────────────────────────────────────────────────────────────────
@Serializable data class AssetPageRoute(val assetId: String)                // TODO(step 11)
@Serializable data object SearchRoute                                       // TODO(step 11)
@Serializable data class WatchlistRoute(val watchlistId: String? = null)    // TODO(step 12)

// ── Workboard ──────────────────────────────────────────────────────────────
@Serializable data object ConglomerateListRoute                             // TODO(step 13)
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

// ── Settings ───────────────────────────────────────────────────────────────
@Serializable data object SettingsRoute                                     // TODO(step 18)
@Serializable data object SettingsAccountRoute                              // TODO(step 18)
@Serializable data object SettingsSecurityRoute                             // TODO(steps 17–18)
@Serializable data object SettingsNotificationsRoute                        // TODO(steps 16, 18)
@Serializable data object SettingsLanguageRoute                             // TODO(step 18)
@Serializable data object SettingsAboutRoute                                // TODO(step 18)
/** In-app changelog / "New features" (owner 2026-07-09) — bundled per-version notes. */
@Serializable data object ChangelogRoute

// ── Sync & debug ───────────────────────────────────────────────────────────
@Serializable data object PendingSyncRoute                                  // TODO(step 8, §7.4)
@Serializable data object GalleryRoute                                      // debug component gallery
@Serializable data object SyncDebugRoute                                    // Step-5 sync-queue debug screen
