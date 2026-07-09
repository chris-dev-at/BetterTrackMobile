package at.bettertrack.app.data.api

import at.bettertrack.app.data.api.dto.CashEntryRequest
import at.bettertrack.app.data.api.dto.CashMovementResponse
import at.bettertrack.app.data.api.dto.CashMovementsResponse
import at.bettertrack.app.data.api.dto.CashSourceListResponse
import at.bettertrack.app.data.api.dto.CashSourceRequest
import at.bettertrack.app.data.api.dto.CashSourceResponse
import at.bettertrack.app.data.api.dto.CashTransferRequest
import at.bettertrack.app.data.api.dto.CashTransferResponse
import at.bettertrack.app.data.api.dto.ActivityAlertStateDto
import at.bettertrack.app.data.api.dto.AudienceMutationResponse
import at.bettertrack.app.data.api.dto.AudienceStateDto
import at.bettertrack.app.data.api.dto.CreateFriendRequestRequest
import at.bettertrack.app.data.api.dto.CreateWatchlistRequest
import at.bettertrack.app.data.api.dto.RenameWatchlistRequest
import at.bettertrack.app.data.api.dto.SetActivityAlertRequest
import at.bettertrack.app.data.api.dto.SetAudienceRequest
import at.bettertrack.app.data.api.dto.WatchlistListResponse
import at.bettertrack.app.data.api.dto.WatchlistSummaryDto
import at.bettertrack.app.data.api.dto.FriendRequestListResponse
import at.bettertrack.app.data.api.dto.FriendsListResponse
import at.bettertrack.app.data.api.dto.MySharedResponse
import at.bettertrack.app.data.api.dto.SharedConglomerateDetailResponse
import at.bettertrack.app.data.api.dto.SharedPortfolioDetailResponse
import at.bettertrack.app.data.api.dto.SharedWatchlistDetailResponse
import at.bettertrack.app.data.api.dto.SharedWithMeResponse
import at.bettertrack.app.data.api.dto.UpdateConglomerateRequest
import at.bettertrack.app.data.api.dto.UpdateWatchlistSharingRequest
import at.bettertrack.app.data.api.dto.WatchlistSharingResponse
import at.bettertrack.app.data.api.dto.AddToWorkboardRequest
import at.bettertrack.app.data.api.dto.AllocateRequest
import at.bettertrack.app.data.api.dto.AllocateResponse
import at.bettertrack.app.data.api.dto.AssetDetailResponse
import at.bettertrack.app.data.api.dto.BacktestPreviewRequest
import at.bettertrack.app.data.api.dto.BacktestResponse
import at.bettertrack.app.data.api.dto.ConglomerateDetailResponse
import at.bettertrack.app.data.api.dto.CreateConglomerateRequest
import at.bettertrack.app.data.api.dto.ReplacePositionsRequest
import at.bettertrack.app.data.api.dto.AssetHistoryResponse
import at.bettertrack.app.data.api.dto.ConglomerateListResponse
import at.bettertrack.app.data.api.dto.CreateCustomAssetRequest
import at.bettertrack.app.data.api.dto.DailyClosesResponse
import at.bettertrack.app.data.api.dto.CreateCustomAssetResponse
import at.bettertrack.app.data.api.dto.CreatePortfolioRequest
import at.bettertrack.app.data.api.dto.CreateTransactionRequest
import at.bettertrack.app.data.api.dto.CreateTransactionsResponse
import at.bettertrack.app.data.api.dto.MarkReadAllRequest
import at.bettertrack.app.data.api.dto.MarkReadIdsRequest
import at.bettertrack.app.data.api.dto.MeResponse
import at.bettertrack.app.data.api.dto.NotificationListResponse
import at.bettertrack.app.data.api.dto.NotificationSettingsResponse
import at.bettertrack.app.data.api.dto.OAuthGrantListResponse
import at.bettertrack.app.data.api.dto.PinStatusResponse
import at.bettertrack.app.data.api.dto.PinVerifyRequest
import at.bettertrack.app.data.api.dto.PinVerifyResponse
import at.bettertrack.app.data.api.dto.UpdateNotificationSettingsRequest
import at.bettertrack.app.data.api.dto.PortfolioDetailResponse
import at.bettertrack.app.data.api.dto.PortfolioHistoryResponse
import at.bettertrack.app.data.api.dto.PortfolioListResponse
import at.bettertrack.app.data.api.dto.PortfolioMutationResponse
import at.bettertrack.app.data.api.dto.PutValuePointsRequest
import at.bettertrack.app.data.api.dto.QuoteResponse
import at.bettertrack.app.data.api.dto.SearchResponse
import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import at.bettertrack.app.data.api.dto.TokenResponse
import at.bettertrack.app.data.api.dto.TransactionListResponse
import at.bettertrack.app.data.api.dto.UpdateCustomAssetRequest
import at.bettertrack.app.data.api.dto.UpdateCustomAssetResponse
import at.bettertrack.app.data.api.dto.UpdatePortfolioRequest
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.api.dto.UpdateTransactionResponse
import at.bettertrack.app.data.api.dto.ValuePointsResponse
import at.bettertrack.app.data.api.dto.WorkboardItemDto
import at.bettertrack.app.data.api.dto.WorkboardListResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The public OAuth token endpoint. Deliberately a SEPARATE interface served by a
 * bare OkHttp client (no auth interceptor / authenticator) so token exchange and
 * refresh can never recurse through the 401→refresh machinery.
 */
interface TokenApi {
    @Headers("Content-Type: application/json")
    @POST("oauth/token")
    suspend fun exchange(@Body body: TokenExchangeRequest): Response<TokenResponse>

    @Headers("Content-Type: application/json")
    @POST("oauth/token")
    suspend fun refresh(@Body body: TokenRefreshRequest): Response<TokenResponse>
}

/**
 * The authenticated BetterTrack API surface used in Step 4. Served by the OkHttp
 * client that injects `Authorization: Bearer …` and drives 401→refresh→retry.
 * Later milestones add their endpoints to this same client.
 */
interface BtApi {
    /** The signed-in user — username/email for display, role/status gating. */
    @GET("auth/me")
    suspend fun me(): Response<MeResponse>

    /**
     * Does the signed-in account have a web PIN? The dedicated, lightweight gate
     * for the "use my BetterTrack PIN" app-lock option (§5) — the option is
     * offered only when `pinSet == true`. Read-only; never sets or changes the PIN.
     */
    @GET("auth/pin/status")
    suspend fun pinStatus(): Response<PinStatusResponse>

    /**
     * Verify the account's existing web PIN — the "use my BetterTrack PIN"
     * app-lock option (§5). 200 = match; 401 = wrong PIN; 400 = the account has no
     * web PIN. This only REUSES the PIN (never sets/changes it).
     *
     * The 200 body is a small confirmation object (see [PinVerifyResponse]) — the
     * app reads NOTHING from it; the 200 status alone is the "verified" signal — so
     * it is decoded into a tolerant empty shape. (It is deliberately NOT [MeResponse]:
     * the verify body lacks that DTO's required fields, so typing it as MeResponse
     * made a correct PIN's 200 fail to parse and surface as a generic error.)
     *
     * `X-Bt-No-Reauth` tells [TokenAuthenticator] NOT to treat a 401 here as an
     * expired access token: a wrong PIN is a domain answer, and a refresh+retry
     * would silently double-submit the attempt against the server's PIN limiter.
     */
    @Headers("Content-Type: application/json", "X-Bt-No-Reauth: 1")
    @POST("auth/pin/verify")
    suspend fun pinVerify(@Body body: PinVerifyRequest): Response<PinVerifyResponse>

    /** Apps the user has authorized — used to find our grant for logout revocation. */
    @GET("settings/oauth-grants")
    suspend fun oauthGrants(): Response<OAuthGrantListResponse>

    /** Revoke an authorized app; kills its access + refresh tokens instantly. */
    @DELETE("settings/oauth-grants/{id}")
    suspend fun revokeOAuthGrant(@Path("id") id: String): Response<Unit>

    // ── Step 5: portfolio-scope reads (network → Room, spec §7.1) ────────────
    // NOTE: the OpenAPI's per-route `security` metadata claims sessionCookie-
    // only for all of these — that is a known docs bug; at runtime OAuth bearer
    // tokens work on the module routes, scope-gated (read scope for GET, write
    // scope for mutations).

    @GET("portfolios")
    suspend fun portfolios(
        @Query("includeArchived") includeArchived: String = "true",
    ): Response<PortfolioListResponse>

    /** Holdings + server-computed totals — the server is the only calculator. */
    @GET("portfolios/{portfolioId}")
    suspend fun portfolioDetail(
        @Path("portfolioId") portfolioId: String,
    ): Response<PortfolioDetailResponse>

    /** Newest-first cursor-paged ledger. Step 5 caches page 1 (limit ≤ 200). */
    @GET("portfolios/{portfolioId}/transactions")
    suspend fun transactions(
        @Path("portfolioId") portfolioId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 200,
    ): Response<TransactionListResponse>

    @GET("portfolios/{portfolioId}/cash")
    suspend fun cash(@Path("portfolioId") portfolioId: String): Response<CashMovementsResponse>

    /**
     * Value-over-time + server-computed performance series (§6.1 graph).
     * Supported ranges: 1M | 6M | 1Y | MAX (day-granular; no 1D/1W/3M window —
     * platform gap, the web app offers the same subset).
     */
    @GET("portfolios/{portfolioId}/history")
    suspend fun portfolioHistory(
        @Path("portfolioId") portfolioId: String,
        @Query("range") range: String,
    ): Response<PortfolioHistoryResponse>

    @GET("custom-assets/{id}/value-points")
    suspend fun valuePoints(@Path("id") assetId: String): Response<ValuePointsResponse>

    // ── Step 10: custom asset management (§6.4, online-only per §7.2) ─────────

    @Headers("Content-Type: application/json")
    @POST("custom-assets")
    suspend fun createCustomAsset(@Body body: CreateCustomAssetRequest): Response<CreateCustomAssetResponse>

    @Headers("Content-Type: application/json")
    @PATCH("custom-assets/{id}")
    suspend fun updateCustomAsset(
        @Path("id") id: String,
        @Body body: UpdateCustomAssetRequest,
    ): Response<UpdateCustomAssetResponse>

    @DELETE("custom-assets/{id}")
    suspend fun deleteCustomAsset(@Path("id") id: String): Response<Unit>

    // ── Step 11: market search + asset pages (§6.5, online-only) ─────────────

    /** Fuzzy asset search; `enriching=true` ⇒ providers still resolving, refetch. */
    @GET("search")
    suspend fun search(@Query("q") q: String): Response<SearchResponse>

    /** Asset identity + quote + server-converted EUR price. */
    @GET("assets/{id}")
    suspend fun assetDetail(@Path("id") id: String): Response<AssetDetailResponse>

    /** Latest quote only (lighter than the full detail). */
    @GET("assets/{id}/quote")
    suspend fun assetQuote(@Path("id") id: String): Response<QuoteResponse>

    /** Close-price series; ranges 1D|1W|1M|3M|6M|1Y|5Y|MAX (server picks interval). */
    @GET("assets/{id}/history")
    suspend fun assetHistory(
        @Path("id") id: String,
        @Query("range") range: String,
    ): Response<AssetHistoryResponse>

    /** Daily closes — the date→price source for the buy/sell form's date link. */
    @GET("assets/{id}/daily-closes")
    suspend fun assetDailyCloses(@Path("id") id: String): Response<DailyClosesResponse>

    // ── Step 11/12 + V3-P5: named watchlists (§6.6) ──────────────────────────

    /** Items in the caller's watchlist(s); scope to one named list via [watchlistId]. */
    @GET("workboard")
    suspend fun workboard(
        @Query("watchlistId") watchlistId: String? = null,
    ): Response<WorkboardListResponse>

    /** Add an asset to a watchlist (default General when [AddToWorkboardRequest.watchlistId] is null). */
    @Headers("Content-Type: application/json")
    @POST("workboard")
    suspend fun addToWorkboard(@Body body: AddToWorkboardRequest): Response<WorkboardItemDto>

    /** Remove a workboard item (by ITEM id, not asset id). */
    @DELETE("workboard/{itemId}")
    suspend fun removeFromWorkboard(@Path("itemId") itemId: String): Response<Unit>

    /** The caller's named watchlists (V3-P5), General first. [workboard:read] */
    @GET("workboard/watchlists")
    suspend fun watchlists(): Response<WatchlistListResponse>

    /** Create a named watchlist. [workboard:write] */
    @Headers("Content-Type: application/json")
    @POST("workboard/watchlists")
    suspend fun createWatchlist(@Body body: CreateWatchlistRequest): Response<WatchlistSummaryDto>

    /** Rename a named watchlist (never the default General). [workboard:write] */
    @Headers("Content-Type: application/json")
    @PATCH("workboard/watchlists/{watchlistId}")
    suspend fun renameWatchlist(
        @Path("watchlistId") watchlistId: String,
        @Body body: RenameWatchlistRequest,
    ): Response<WatchlistSummaryDto>

    /** Delete a named watchlist (never the default General). [workboard:write] */
    @DELETE("workboard/watchlists/{watchlistId}")
    suspend fun deleteWatchlist(@Path("watchlistId") watchlistId: String): Response<Unit>

    @GET("conglomerates")
    suspend fun conglomerates(): Response<ConglomerateListResponse>

    // ── Step 13: conglomerates lite (§6.7, online-only) ──────────────────────

    @Headers("Content-Type: application/json")
    @POST("conglomerates")
    suspend fun createConglomerate(@Body body: CreateConglomerateRequest): Response<ConglomerateDetailResponse>

    @GET("conglomerates/{id}")
    suspend fun conglomerateDetail(@Path("id") id: String): Response<ConglomerateDetailResponse>

    @DELETE("conglomerates/{id}")
    suspend fun deleteConglomerate(@Path("id") id: String): Response<Unit>

    /** Replace the weighted positions (builder save; server re-validates 100%). */
    @Headers("Content-Type: application/json")
    @PUT("conglomerates/{id}/positions")
    suspend fun replaceConglomeratePositions(
        @Path("id") id: String,
        @Body body: ReplacePositionsRequest,
    ): Response<ConglomerateDetailResponse>

    /** Budget calculator: budget → weighted buy list (server-computed). */
    @Headers("Content-Type: application/json")
    @POST("conglomerates/{id}/allocate")
    suspend fun allocateConglomerate(
        @Path("id") id: String,
        @Body body: AllocateRequest,
    ): Response<AllocateResponse>

    /** Past-performance backtest (single curve + stats, §6.7). */
    @Headers("Content-Type: application/json")
    @POST("backtest/preview")
    suspend fun backtestPreview(@Body body: BacktestPreviewRequest): Response<BacktestResponse>

    // ── Step 5: queue-drain mutations (§7.2 ledger-event set) ────────────────
    // The forms that enqueue these arrive in Steps 8–10; the sync engine's
    // op → API mapping layer uses them now.

    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/transactions")
    suspend fun createTransaction(
        @Path("portfolioId") portfolioId: String,
        @Body body: CreateTransactionRequest,
    ): Response<CreateTransactionsResponse>

    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/cash/deposit")
    suspend fun cashDeposit(
        @Path("portfolioId") portfolioId: String,
        @Body body: CashEntryRequest,
    ): Response<CashMovementResponse>

    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/cash/withdraw")
    suspend fun cashWithdraw(
        @Path("portfolioId") portfolioId: String,
        @Body body: CashEntryRequest,
    ): Response<CashMovementResponse>

    /** Full-replace of a custom asset's value points (the only write the API has). */
    @Headers("Content-Type: application/json")
    @PUT("custom-assets/{id}/value-points")
    suspend fun putValuePoints(
        @Path("id") assetId: String,
        @Body body: PutValuePointsRequest,
    ): Response<ValuePointsResponse>

    // ── Step 6: portfolio switcher management (create/rename/archive/restore,
    // §6.1 — online-only actions per §7.2) ───────────────────────────────────

    @Headers("Content-Type: application/json")
    @POST("portfolios")
    suspend fun createPortfolio(@Body body: CreatePortfolioRequest): Response<PortfolioMutationResponse>

    /** Rename and/or change visibility / default pay-from-cash. */
    @Headers("Content-Type: application/json")
    @PATCH("portfolios/{portfolioId}")
    suspend fun updatePortfolio(
        @Path("portfolioId") portfolioId: String,
        @Body body: UpdatePortfolioRequest,
    ): Response<PortfolioMutationResponse>

    /** Soft-archive — the platform's only way to remove a portfolio (no DELETE). */
    @POST("portfolios/{portfolioId}/archive")
    suspend fun archivePortfolio(@Path("portfolioId") portfolioId: String): Response<PortfolioMutationResponse>

    /** Restore an archived portfolio. */
    @POST("portfolios/{portfolioId}/restore")
    suspend fun restorePortfolio(@Path("portfolioId") portfolioId: String): Response<PortfolioMutationResponse>

    @DELETE("portfolios/{portfolioId}/transactions/{txId}")
    suspend fun deleteTransaction(
        @Path("portfolioId") portfolioId: String,
        @Path("txId") txId: String,
    ): Response<Unit>

    /** Edit a SYNCED transaction (Step 8, online-only §7.2; re-validates oversell). */
    @Headers("Content-Type: application/json")
    @PATCH("portfolios/{portfolioId}/transactions/{txId}")
    suspend fun updateTransaction(
        @Path("portfolioId") portfolioId: String,
        @Path("txId") txId: String,
        @Body body: UpdateTransactionRequest,
    ): Response<UpdateTransactionResponse>

    // ── Step 9: cash sources & transfers (§6.3) ──────────────────────────────

    @GET("portfolios/{portfolioId}/cash/sources")
    suspend fun cashSources(
        @Path("portfolioId") portfolioId: String,
    ): Response<CashSourceListResponse>

    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/cash/sources")
    suspend fun createCashSource(
        @Path("portfolioId") portfolioId: String,
        @Body body: CashSourceRequest,
    ): Response<CashSourceResponse>

    /** Rename and/or relabel (type) a source. */
    @Headers("Content-Type: application/json")
    @PATCH("portfolios/{portfolioId}/cash/sources/{sourceId}")
    suspend fun updateCashSource(
        @Path("portfolioId") portfolioId: String,
        @Path("sourceId") sourceId: String,
        @Body body: CashSourceRequest,
    ): Response<CashSourceResponse>

    /** Archive — the server rejects Main and non-zero balances. */
    @POST("portfolios/{portfolioId}/cash/sources/{sourceId}/archive")
    suspend fun archiveCashSource(
        @Path("portfolioId") portfolioId: String,
        @Path("sourceId") sourceId: String,
    ): Response<CashSourceResponse>

    @POST("portfolios/{portfolioId}/cash/sources/{sourceId}/restore")
    suspend fun restoreCashSource(
        @Path("portfolioId") portfolioId: String,
        @Path("sourceId") sourceId: String,
    ): Response<CashSourceResponse>

    /** Atomic transfer between two sources (paired transfer_out/transfer_in). */
    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/cash/transfer")
    suspend fun cashTransfer(
        @Path("portfolioId") portfolioId: String,
        @Body body: CashTransferRequest,
    ): Response<CashTransferResponse>

    // ── Step 14: friends & sharing (§6.8/§6.9) ───────────────────────────────
    // READS gate on social:read (the mobile client HAS it → live).
    // WRITES (request/accept/decline/cancel/unfriend) gate on social:write, NOT
    // yet granted → SocialRepository routes them through a stub until it lands.
    // Sharing-visibility mutations ride portfolio:write / workboard:write (held).

    /** The caller's friends. */
    @GET("social/friends")
    suspend fun friends(): Response<FriendsListResponse>

    /** Pending incoming + outgoing friend requests. */
    @GET("social/requests")
    suspend fun friendRequests(): Response<FriendRequestListResponse>

    /** Request a friend by username or email (no enumeration). [social:write] */
    @Headers("Content-Type: application/json")
    @POST("social/requests")
    suspend fun createFriendRequest(@Body body: CreateFriendRequestRequest): Response<Unit>

    /** Accept an incoming request. [social:write] */
    @POST("social/requests/{id}/accept")
    suspend fun acceptFriendRequest(@Path("id") id: String): Response<Unit>

    /** Decline an incoming request. [social:write] */
    @POST("social/requests/{id}/decline")
    suspend fun declineFriendRequest(@Path("id") id: String): Response<Unit>

    /** Cancel an outgoing request. [social:write] */
    @POST("social/requests/{id}/cancel")
    suspend fun cancelFriendRequest(@Path("id") id: String): Response<Unit>

    /** Remove a friendship. [social:write] */
    @DELETE("social/friends/{userId}")
    suspend fun removeFriend(@Path("userId") userId: String): Response<Unit>

    /** Everything my friends share with me — portfolios, conglomerates, watchlists. */
    @GET("social/shared")
    suspend fun sharedWithMe(): Response<SharedWithMeResponse>

    /** Everything I currently share with friends. */
    @GET("social/my-shared")
    suspend fun mySharedItems(): Response<MySharedResponse>

    /** Read-only overview of a friend-shared portfolio. */
    @GET("social/shared/{portfolioId}")
    suspend fun sharedPortfolioDetail(
        @Path("portfolioId") portfolioId: String,
    ): Response<SharedPortfolioDetailResponse>

    /** Read-only view of a friend's shared named watchlist (by list id). */
    @GET("social/shared/watchlists/{watchlistId}")
    suspend fun sharedWatchlistDetail(
        @Path("watchlistId") watchlistId: String,
    ): Response<SharedWatchlistDetailResponse>

    /** Read-only view of a friend-shared conglomerate. */
    @GET("social/shared/conglomerates/{conglomerateId}")
    suspend fun sharedConglomerateDetail(
        @Path("conglomerateId") conglomerateId: String,
    ): Response<SharedConglomerateDetailResponse>

    // ── Step 14: sharing-visibility mutations (audience = private | friends) ──

    /** Current watchlist audience. */
    @GET("workboard/sharing")
    suspend fun watchlistSharing(): Response<WatchlistSharingResponse>

    /** Set the single watchlist's audience. [workboard:write] */
    @Headers("Content-Type: application/json")
    @PATCH("workboard/sharing")
    suspend fun updateWatchlistSharing(
        @Body body: UpdateWatchlistSharingRequest,
    ): Response<WatchlistSharingResponse>

    /** Rename/describe a conglomerate (audience now via the unified endpoint). [workboard:write] */
    @Headers("Content-Type: application/json")
    @PATCH("conglomerates/{id}")
    suspend fun updateConglomerate(
        @Path("id") id: String,
        @Body body: UpdateConglomerateRequest,
    ): Response<ConglomerateDetailResponse>

    // ── V3-P5: unified audience model (private | specific_friends | all_friends
    // | public_link) across every portfolio / conglomerate / watchlist. ───────

    /** The owner's current audience for one shareable item ({kind}=portfolio|conglomerate|watchlist). */
    @GET("social/audience/{kind}/{subjectId}")
    suspend fun audience(
        @Path("kind") kind: String,
        @Path("subjectId") subjectId: String,
    ): Response<AudienceStateDto>

    /**
     * Set the audience for one item. Mints a hash-only public-link token ONCE when
     * moving to `public_link` (returned in `link` — never re-fetchable). [social:write]
     */
    @Headers("Content-Type: application/json")
    @PUT("social/audience/{kind}/{subjectId}")
    suspend fun setAudience(
        @Path("kind") kind: String,
        @Path("subjectId") subjectId: String,
        @Body body: SetAudienceRequest,
    ): Response<AudienceMutationResponse>

    /**
     * The viewer's per-item activity-alert opt-in on a friend's shared item
     * (V3-P6). Persist-only; delivery ships with Notifications-v2 (#368). [social:write]
     */
    @Headers("Content-Type: application/json")
    @PUT("social/shared/activity/{kind}/{subjectId}")
    suspend fun setActivityAlert(
        @Path("kind") kind: String,
        @Path("subjectId") subjectId: String,
        @Body body: SetActivityAlertRequest,
    ): Response<ActivityAlertStateDto>

    // ── Step 16: notifications (§6.11) ───────────────────────────────────────
    // Runtime auth is the OAuth bearer (the OpenAPI sessionCookie annotation is
    // the known docs bug); these additionally need a notifications read scope the
    // mobile client is NOT yet granted → the repository falls back to a stub inbox
    // + local mark-read, and lights up live the moment the scope lands. There is
    // NO device-token endpoint yet (push send/register is platform-gated).

    /** In-app inbox: newest-first cursor-paged notifications + unread count. */
    @GET("notifications")
    suspend fun notifications(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): Response<NotificationListResponse>

    /** Mark specific notifications read (1–200 ids). */
    @Headers("Content-Type: application/json")
    @POST("notifications/mark-read")
    suspend fun markNotificationsRead(@Body body: MarkReadIdsRequest): Response<Unit>

    /** Mark ALL notifications read. */
    @Headers("Content-Type: application/json")
    @POST("notifications/mark-read")
    suspend fun markAllNotificationsRead(@Body body: MarkReadAllRequest): Response<Unit>

    /** The per-type × in-app/email notification preference matrix (mirrors web). */
    @GET("settings/notifications")
    suspend fun notificationSettings(): Response<NotificationSettingsResponse>

    /** Update the in-app/email matrix (Push + Mute are app-local, never sent). */
    @Headers("Content-Type: application/json")
    @PATCH("settings/notifications")
    suspend fun updateNotificationSettings(
        @Body body: UpdateNotificationSettingsRequest,
    ): Response<NotificationSettingsResponse>
}
