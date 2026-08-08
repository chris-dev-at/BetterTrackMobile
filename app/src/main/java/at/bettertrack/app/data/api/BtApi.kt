package at.bettertrack.app.data.api

import at.bettertrack.app.data.api.dto.CashDeletionResponse
import at.bettertrack.app.data.api.dto.CashEntryRequest
import at.bettertrack.app.data.api.dto.CashMovementResponse
import at.bettertrack.app.data.api.dto.CashMovementsResponse
import at.bettertrack.app.data.api.dto.CashSourceListResponse
import at.bettertrack.app.data.api.dto.CashSourceRequest
import at.bettertrack.app.data.api.dto.CashSourceResponse
import at.bettertrack.app.data.api.dto.CashTransferRequest
import at.bettertrack.app.data.api.dto.CashTransferResponse
import at.bettertrack.app.data.api.dto.CashBudgetListResponse
import at.bettertrack.app.data.api.dto.CashBudgetResponse
import at.bettertrack.app.data.api.dto.CashMovementTagsResponse
import at.bettertrack.app.data.api.dto.CashRuleApplyResponse
import at.bettertrack.app.data.api.dto.CashRuleListResponse
import at.bettertrack.app.data.api.dto.CashRulePreviewRequest
import at.bettertrack.app.data.api.dto.CashRulePreviewResponse
import at.bettertrack.app.data.api.dto.CashRuleResponse
import at.bettertrack.app.data.api.dto.CashSummaryResponse
import at.bettertrack.app.data.api.dto.CashTagListResponse
import at.bettertrack.app.data.api.dto.CashTagResponse
import at.bettertrack.app.data.api.dto.CashTrendResponse
import at.bettertrack.app.data.api.dto.CreateCashBudgetRequest
import at.bettertrack.app.data.api.dto.CreateCashRuleRequest
import at.bettertrack.app.data.api.dto.CreateCashTagRequest
import at.bettertrack.app.data.api.dto.CreateStandingOrderRequest
import at.bettertrack.app.data.api.dto.SetCashMovementTagsRequest
import at.bettertrack.app.data.api.dto.StandingOrderDto
import at.bettertrack.app.data.api.dto.StandingOrderListResponse
import at.bettertrack.app.data.api.dto.UpdateCashBudgetRequest
import at.bettertrack.app.data.api.dto.UpdateCashRuleRequest
import at.bettertrack.app.data.api.dto.UpdateCashTagRequest
import at.bettertrack.app.data.api.dto.AccountSettingsResponse
import at.bettertrack.app.data.api.dto.PortfolioTaxSettingsResponse
import at.bettertrack.app.data.api.dto.ProfileSettingsResponse
import at.bettertrack.app.data.api.dto.UpdateProfileSettingsRequest
import at.bettertrack.app.data.api.dto.TaxSettingsDto
import at.bettertrack.app.data.api.dto.TaxYearListResponse
import at.bettertrack.app.data.api.dto.TaxYearReportResponse
import at.bettertrack.app.data.api.dto.UpdateTaxSettingsRequest
import at.bettertrack.app.data.api.dto.ChangePasswordRequest
import at.bettertrack.app.data.api.dto.DeleteAccountRequest
import at.bettertrack.app.data.api.dto.RevokeSessionsResponse
import at.bettertrack.app.data.api.dto.SessionListResponse
import at.bettertrack.app.data.api.dto.TwoFactorCodeRequest
import at.bettertrack.app.data.api.dto.TwoFactorDisableRequest
import at.bettertrack.app.data.api.dto.TwoFactorEnrollResponse
import at.bettertrack.app.data.api.dto.TwoFactorMethodEnabledResponse
import at.bettertrack.app.data.api.dto.TwoFactorRecoveryCodesResponse
import at.bettertrack.app.data.api.dto.TwoFactorStatusResponse
import at.bettertrack.app.data.api.dto.UpdateAccountSettingsRequest
import at.bettertrack.app.data.api.dto.ChatConversationListResponse
import at.bettertrack.app.data.api.dto.ChatThreadResponse
import at.bettertrack.app.data.api.dto.ConversationResponse
import at.bettertrack.app.data.api.dto.OpenConversationRequest
import at.bettertrack.app.data.api.dto.SendChatMessageRequest
import at.bettertrack.app.data.api.dto.SendChatMessageResponse
import at.bettertrack.app.data.api.dto.ActivityAlertStateDto
import at.bettertrack.app.data.api.dto.AlertDto
import at.bettertrack.app.data.api.dto.AlertsListResponse
import at.bettertrack.app.data.api.dto.CreateAlertRequest
import at.bettertrack.app.data.api.dto.UpdateAlertRequest
import at.bettertrack.app.data.api.dto.AddGroupMemberRequest
import at.bettertrack.app.data.api.dto.AudienceMutationResponse
import at.bettertrack.app.data.api.dto.AudienceStateDto
import at.bettertrack.app.data.api.dto.CommentThreadResponse
import at.bettertrack.app.data.api.dto.CreateCommentRequest
import at.bettertrack.app.data.api.dto.DividendCalendarResponse
import at.bettertrack.app.data.api.dto.DividendProjectionResponse
import at.bettertrack.app.data.api.dto.DividendsResponse
import at.bettertrack.app.data.api.dto.EarningsCalendarResponse
import at.bettertrack.app.data.api.dto.EarningsResponse
import at.bettertrack.app.data.api.dto.FriendGroupDto
import at.bettertrack.app.data.api.dto.FriendGroupListResponse
import at.bettertrack.app.data.api.dto.FriendGroupNameRequest
import at.bettertrack.app.data.api.dto.IdeaListResponse
import at.bettertrack.app.data.api.dto.IdeaResponse
import at.bettertrack.app.data.api.dto.ItemCommentDto
import at.bettertrack.app.data.api.dto.MarketIntelStatusResponse
import at.bettertrack.app.data.api.dto.MirrorAcceptInviteResponse
import at.bettertrack.app.data.api.dto.MirrorActivityResponse
import at.bettertrack.app.data.api.dto.MirrorChainListResponse
import at.bettertrack.app.data.api.dto.MirrorChainSummaryDto
import at.bettertrack.app.data.api.dto.MirrorConvertRequest
import at.bettertrack.app.data.api.dto.MirrorCreateInviteRequest
import at.bettertrack.app.data.api.dto.MirrorInviteListResponse
import at.bettertrack.app.data.api.dto.MirrorMemberListResponse
import at.bettertrack.app.data.api.dto.MirrorOkResponse
import at.bettertrack.app.data.api.dto.MirrorRenameChainRequest
import at.bettertrack.app.data.api.dto.MirrorSetRoleRequest
import at.bettertrack.app.data.api.dto.MirrorTransferRequest
import at.bettertrack.app.data.api.dto.NewsDigestResponse
import at.bettertrack.app.data.api.dto.NewsResponse
import at.bettertrack.app.data.api.dto.ReactionListResponse
import at.bettertrack.app.data.api.dto.SplitsResponse
import at.bettertrack.app.data.api.dto.ToggleReactionRequest
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
import at.bettertrack.app.data.api.dto.CustomAssetListResponse
import at.bettertrack.app.data.api.dto.DailyClosesResponse
import at.bettertrack.app.data.api.dto.CreateCustomAssetResponse
import at.bettertrack.app.data.api.dto.CreatePortfolioRequest
import at.bettertrack.app.data.api.dto.CreateTransactionRequest
import at.bettertrack.app.data.api.dto.CreateTransactionsResponse
import at.bettertrack.app.data.api.dto.DeregisterDeviceRequest
import at.bettertrack.app.data.api.dto.DeviceAckResponse
import at.bettertrack.app.data.api.dto.GoogleLinkStatusResponse
import at.bettertrack.app.data.api.dto.GoogleUnlinkRequest
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
import at.bettertrack.app.data.api.dto.RegisterDeviceRequest
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
import at.bettertrack.app.data.api.dto.VersionResponse
import at.bettertrack.app.data.api.dto.WorkboardItemDto
import at.bettertrack.app.data.api.dto.WorkboardListResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import kotlinx.serialization.json.JsonObject
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

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

    /**
     * Apps the user has authorized — the **Authorized apps** screen's whole read,
     * and the lookup that finds our own grant for logout revocation.
     *
     * Session-only on the platform's bearer allowlist today, so this answers a
     * bearer with `403 API_KEY_FORBIDDEN`. That is not a bug to route around: it
     * is the capability signal
     * [at.bettertrack.app.data.repo.ConnectionsRepository.authorizedApps] probes
     * on, which is what lets the screen render its designed "not released yet"
     * state and light up on a platform config flip with no app release.
     */
    @GET("settings/oauth-grants")
    suspend fun oauthGrants(): Response<OAuthGrantListResponse>

    /** Revoke an authorized app; kills its access + refresh tokens instantly. */
    @DELETE("settings/oauth-grants/{id}")
    suspend fun revokeOAuthGrant(@Path("id") id: String): Response<Unit>

    /**
     * The account's Google sign-in identity (§13.4 V4-P4b) — the Connections
     * screen's Google group.
     *
     * Three answers matter and they are all different: a **200** carries the
     * status; a **404** means this deployment has no Google client configured at
     * all (the group renders nothing, exactly as the web panel does); a **403**
     * is the bearer allowlist refusing the route, which the app renders as its
     * "manage on the web" state rather than as an error. [account:security]
     */
    @GET("auth/google/link-status")
    suspend fun googleLinkStatus(): Response<GoogleLinkStatusResponse>

    /**
     * Remove the Google link after a password re-auth. `409 GOOGLE_ONLY_SIGN_IN`
     * while Google is the account's only usable sign-in method — pre-empted by
     * `canUnlink`, but still handled, because the status can go stale.
     *
     * `X-Bt-No-Reauth` for the same reason [pinVerify] carries it: **a 401 here
     * is a domain answer** ("that password is not correct"), not an expired
     * access token. Without it [TokenAuthenticator] would refresh and silently
     * re-submit the wrong password against the server's re-auth limiter, and the
     * user would be told nothing. The access token is proactively refreshed
     * before the call by [AuthInterceptor], so a genuine expiry landing here is
     * not the case this trades away. [account:security]
     */
    @Headers("Content-Type: application/json", "X-Bt-No-Reauth: 1")
    @POST("auth/google/unlink")
    suspend fun unlinkGoogle(@Body body: GoogleUnlinkRequest): Response<Unit>

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

    /**
     * ALL custom assets the caller owns (#387) — INCLUDING zero-holding ones,
     * each with its latest value point. Real list source (replaces holdings
     * inference). Bearer scope portfolio:read.
     */
    @GET("custom-assets")
    suspend fun customAssets(): Response<CustomAssetListResponse>

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

    // ── V5: market intel (`market:read`) ─────────────────────────────────────
    // Mounted on the same `/assets` base as the block above. Note every path
    // carries an `/intel` segment — there is no `GET /assets/{id}/dividends`.
    //
    // NONE of these ever fail loudly: a disabled feature flag, a provider that
    // lacks the capability (every custom asset), and an upstream timeout all
    // answer **200** with the "unavailable" body. So the app reads the flag and
    // hides the surface; there is no error path to design here. What it must
    // NOT do is conflate `available:false` (we can't tell you) with
    // `available:true, []` (there is nothing to tell) — the first hides the
    // block, the second shows an empty state.
    //
    // The three `/assets/portfolio/*` reads are killed in paranoid mode
    // (403 PARANOID_MODE, caught by the global interceptor); the per-asset
    // intel reads and the earnings calendar survive it.

    /** Is intel on at all, and which of the four capabilities this asset supports. */
    @GET("assets/{id}/intel")
    suspend fun assetIntel(@Path("id") id: String): Response<MarketIntelStatusResponse>

    /** Dividend history + announced upcoming + forward yield (a FRACTION, not %). */
    @GET("assets/{id}/intel/dividends")
    suspend fun assetDividends(@Path("id") id: String): Response<DividendsResponse>

    /** Next report + recent reports, with EPS estimate/actual. */
    @GET("assets/{id}/intel/earnings")
    suspend fun assetEarnings(@Path("id") id: String): Response<EarningsResponse>

    /** Up to 20 provider headlines (10-minute server cache). */
    @GET("assets/{id}/intel/news")
    suspend fun assetNews(@Path("id") id: String): Response<NewsResponse>

    /** Historical splits (`upcoming` is always empty with today's provider). */
    @GET("assets/{id}/intel/splits")
    suspend fun assetSplits(@Path("id") id: String): Response<SplitsResponse>

    /** Earnings dates across everything I hold or watch, ascending. */
    @GET("assets/intel/earnings-calendar")
    suspend fun earningsCalendar(): Response<EarningsCalendarResponse>

    /** Upcoming dividends across holdings + watchlists, today onwards. */
    @GET("assets/portfolio/dividend-calendar")
    suspend fun dividendCalendar(): Response<DividendCalendarResponse>

    /** Projected dividend income in EUR — all-or-nothing if any FX leg fails. */
    @GET("assets/portfolio/dividend-projection")
    suspend fun dividendProjection(): Response<DividendProjectionResponse>

    /** Headlines grouped by held/watched asset, newest group first. */
    @GET("assets/portfolio/news-digest")
    suspend fun newsDigest(): Response<NewsDigestResponse>

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

    // ── Price alerts (owner ask 2026-07-10, Workboard; online-only) ──────────

    /** The caller's price alerts. */
    @GET("alerts")
    suspend fun alerts(): Response<AlertsListResponse>

    /** Create a price alert (server captures refPrice for the from-ref kinds). */
    @Headers("Content-Type: application/json")
    @POST("alerts")
    suspend fun createAlert(@Body body: CreateAlertRequest): Response<AlertDto>

    /** Update an alert's threshold and/or repeat behaviour (delta PATCH). */
    @Headers("Content-Type: application/json")
    @PATCH("alerts/{id}")
    suspend fun updateAlert(
        @Path("id") id: String,
        @Body body: UpdateAlertRequest,
    ): Response<AlertDto>

    /** Delete a price alert. */
    @DELETE("alerts/{id}")
    suspend fun deleteAlert(@Path("id") id: String): Response<Unit>

    /** Re-arm a fired one-shot alert back to active. */
    @POST("alerts/{id}/rearm")
    suspend fun rearmAlert(@Path("id") id: String): Response<AlertDto>

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
    //
    // Idempotency (platform #432, PLATFORM_ASKS #9): the offline queue attaches
    // `Idempotency-Key: <op clientId UUID>` to every send of a queued mutation
    // so a replayed retry runs exactly once (byte-identical 2xx). The param is
    // nullable + defaulted — a null value makes Retrofit omit the header, so any
    // non-queue (online-direct) caller keeps unchanged, header-free behavior.

    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/transactions")
    suspend fun createTransaction(
        @Path("portfolioId") portfolioId: String,
        @Body body: CreateTransactionRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): Response<CreateTransactionsResponse>

    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/cash/deposit")
    suspend fun cashDeposit(
        @Path("portfolioId") portfolioId: String,
        @Body body: CashEntryRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): Response<CashMovementResponse>

    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/cash/withdraw")
    suspend fun cashWithdraw(
        @Path("portfolioId") portfolioId: String,
        @Body body: CashEntryRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): Response<CashMovementResponse>

    /**
     * v5: a standalone COST. Mechanically identical to a withdrawal (same
     * overdraw gate) but kept apart because a fee drags performance and a
     * withdrawal does not.
     */
    @Headers("Content-Type: application/json")
    @POST("portfolios/{portfolioId}/cash/fee")
    suspend fun cashFee(
        @Path("portfolioId") portfolioId: String,
        @Body body: CashEntryRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): Response<CashMovementResponse>

    /**
     * v5 correction op. Only hand-typed kinds (deposit/withdrawal/fee) may be
     * patched — a derived row answers 409 `CASH_MOVEMENT_NOT_EDITABLE`. The body
     * is a raw [JsonObject] so the app can send exactly the changed keys and can
     * express `"note": null` (clear) distinctly from an omitted note (leave).
     */
    @Headers("Content-Type: application/json")
    @PATCH("portfolios/{portfolioId}/cash/movements/{movementId}")
    suspend fun updateCashMovement(
        @Path("portfolioId") portfolioId: String,
        @Path("movementId") movementId: String,
        @Body body: JsonObject,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): Response<CashMovementResponse>

    /** v5 correction op — answers **200** with fresh balances, not 204. */
    @DELETE("portfolios/{portfolioId}/cash/movements/{movementId}")
    suspend fun deleteCashMovement(
        @Path("portfolioId") portfolioId: String,
        @Path("movementId") movementId: String,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): Response<CashDeletionResponse>

    /** Full-replace of a custom asset's value points (the only write the API has). */
    @Headers("Content-Type: application/json")
    @PUT("custom-assets/{id}/value-points")
    suspend fun putValuePoints(
        @Path("id") assetId: String,
        @Body body: PutValuePointsRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
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

    /**
     * Hard-delete a portfolio (platform #412, LIVE). 204 on success. Cascades
     * everything server-side (transactions, cash + sources, dividends, share
     * audiences + public links) and auto-promotes the derived default. Bearer
     * `portfolio:write`. `400 { code: "LAST_ACTIVE_PORTFOLIO" }` when it is the
     * account's only ACTIVE portfolio; archived portfolios are always deletable;
     * 404 for a foreign/unknown id (and any second delete of the same id).
     */
    @DELETE("portfolios/{portfolioId}")
    suspend fun deletePortfolio(@Path("portfolioId") portfolioId: String): Response<Unit>

    @DELETE("portfolios/{portfolioId}/transactions/{txId}")
    suspend fun deleteTransaction(
        @Path("portfolioId") portfolioId: String,
        @Path("txId") txId: String,
        // Idempotency-Key (platform #432, accepted on all portfolio mutations): a
        // per-delete UUID so a retry after a lost 204 replays the stored 2xx
        // instead of hitting a spurious 404 for the already-removed row.
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): Response<Unit>

    /** Edit a SYNCED transaction (Step 8, online-only §7.2; re-validates oversell). */
    @Headers("Content-Type: application/json")
    @PATCH("portfolios/{portfolioId}/transactions/{txId}")
    suspend fun updateTransaction(
        @Path("portfolioId") portfolioId: String,
        @Path("txId") txId: String,
        @Body body: UpdateTransactionRequest,
        // Idempotency-Key: a per-submission UUID so a resend after a lost response
        // replays the stored 2xx (the PATCH is field-absolute, so also naturally safe).
        @Header("Idempotency-Key") idempotencyKey: String? = null,
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
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): Response<CashTransferResponse>

    // ── V5: cash classification (tags / budgets / rules / dashboards) ────────
    // Gated on cash:read / cash:write — a SEPARATE scope pair from the cash
    // MOVEMENTS above, which ride portfolio:* under /portfolios/{id}/cash/…
    //
    // No Idempotency-Key on any of these: the platform mounts that middleware
    // per-route and mounts none here, and the writes are naturally
    // idempotent/replaceable anyway (whole-set PUT, field-absolute PATCH, an
    // additive `apply` that reports 0 on a second press).

    /** The caller's cash-flow tags, app-owned ones included. [cash:read] */
    @GET("cash/tags")
    suspend fun cashTags(): Response<CashTagListResponse>

    /** Create a user tag; a case-insensitive duplicate name is 409 CASH_TAG_NAME_TAKEN. */
    @Headers("Content-Type: application/json")
    @POST("cash/tags")
    suspend fun createCashTag(@Body body: CreateCashTagRequest): Response<CashTagResponse>

    /** Rename and/or re-tint. System tags accept both; only DELETE is refused. */
    @Headers("Content-Type: application/json")
    @PATCH("cash/tags/{tagId}")
    suspend fun updateCashTag(
        @Path("tagId") tagId: String,
        @Body body: UpdateCashTagRequest,
    ): Response<CashTagResponse>

    /** 204. A SYSTEM tag answers 409 `CASH_TAG_SYSTEM_PROTECTED`. */
    @DELETE("cash/tags/{tagId}")
    suspend fun deleteCashTag(@Path("tagId") tagId: String): Response<Unit>

    /** Whole-set replace of one movement's tags; `[]` clears them. */
    @Headers("Content-Type: application/json")
    @PUT("cash/movements/{movementId}/tags")
    suspend fun setCashMovementTags(
        @Path("movementId") movementId: String,
        @Body body: SetCashMovementTagsRequest,
    ): Response<CashMovementTagsResponse>

    /** Budgets + this month's progress. [portfolioId] REQUIRED; omitted month ⇒ current. */
    @GET("cash/budgets")
    suspend fun cashBudgets(
        @Query("portfolioId") portfolioId: String,
        @Query("month") month: String? = null,
    ): Response<CashBudgetListResponse>

    /** One budget per (portfolio, tag, period) — a second is 409 CASH_BUDGET_EXISTS. */
    @Headers("Content-Type: application/json")
    @POST("cash/budgets")
    suspend fun createCashBudget(
        @Body body: CreateCashBudgetRequest,
    ): Response<CashBudgetResponse>

    @Headers("Content-Type: application/json")
    @PATCH("cash/budgets/{budgetId}")
    suspend fun updateCashBudget(
        @Path("budgetId") budgetId: String,
        @Body body: UpdateCashBudgetRequest,
    ): Response<CashBudgetResponse>

    @DELETE("cash/budgets/{budgetId}")
    suspend fun deleteCashBudget(@Path("budgetId") budgetId: String): Response<Unit>

    /** Already in EVALUATION order (ascending priority, then age) — do not re-sort. */
    @GET("cash/rules")
    suspend fun cashRules(): Response<CashRuleListResponse>

    @Headers("Content-Type: application/json")
    @POST("cash/rules")
    suspend fun createCashRule(@Body body: CreateCashRuleRequest): Response<CashRuleResponse>

    /** Every field optional; `tagIds` REPLACES the set. */
    @Headers("Content-Type: application/json")
    @PATCH("cash/rules/{ruleId}")
    suspend fun updateCashRule(
        @Path("ruleId") ruleId: String,
        @Body body: UpdateCashRuleRequest,
    ): Response<CashRuleResponse>

    @DELETE("cash/rules/{ruleId}")
    suspend fun deleteCashRule(@Path("ruleId") ruleId: String): Response<Unit>

    /**
     * Run every enabled rule over the existing movements. NO BODY — Retrofit needs
     * none for a bodyless POST. Additive + idempotent: a second press honestly
     * reports 0 rather than re-counting the same rows.
     */
    @POST("cash/rules/apply")
    suspend fun applyCashRules(): Response<CashRuleApplyResponse>

    /** What the rules WOULD tag this note as (first match wins). Empty note ⇒ `[]`. */
    @Headers("Content-Type: application/json")
    @POST("cash/rules/preview")
    suspend fun previewCashRules(
        @Body body: CashRulePreviewRequest,
    ): Response<CashRulePreviewResponse>

    /** One portfolio's month. The per-tag rows do NOT sum to the totals — see the DTO. */
    @GET("cash/summary")
    suspend fun cashSummary(
        @Query("portfolioId") portfolioId: String,
        @Query("month") month: String? = null,
    ): Response<CashSummaryResponse>

    /** Trailing inflow/outflow per month, oldest→newest, gaps as zeros. months = 1..24. */
    @GET("cash/trends")
    suspend fun cashTrends(
        @Query("portfolioId") portfolioId: String,
        @Query("months") months: Int? = null,
    ): Response<CashTrendResponse>

    // ── V5: standing orders (§13.5 V5-P6b) ──────────────────────────────────
    // Same portfolio:* scope pair as the rest of the portfolio surface.
    // ONLY the list is enveloped (`{"orders":[…]}`); every single-order response
    // is the BARE object — verified in the platform monorepo, see
    // [at.bettertrack.app.data.api.dto.StandingOrderDto]. No Idempotency-Key:
    // standingOrdersRoutes.ts mounts no idempotency middleware.

    /** The caller's standing orders, optionally narrowed to one portfolio. */
    @GET("standing-orders")
    suspend fun standingOrders(
        @Query("portfolioId") portfolioId: String? = null,
    ): Response<StandingOrderListResponse>

    /** 201 with the BARE created order. */
    @Headers("Content-Type: application/json")
    @POST("standing-orders")
    suspend fun createStandingOrder(
        @Body body: CreateStandingOrderRequest,
    ): Response<StandingOrderDto>

    @GET("standing-orders/{id}")
    suspend fun standingOrder(@Path("id") id: String): Response<StandingOrderDto>

    /**
     * Edit amount / label / endDate ONLY — kind, asset, portfolio, cadence,
     * anchorDay and startDate are immutable so a live order's period identity
     * never shifts under it. The body is a raw [JsonObject] because `label` and
     * `endDate` are nullish server-side: the app must be able to send
     * `"label": null` (CLEAR) distinctly from an omitted label (LEAVE), which a
     * nullable DTO field cannot express under `explicitNulls = false`. Built by
     * [at.bettertrack.app.data.standingorders.buildStandingOrderPatch].
     */
    @Headers("Content-Type: application/json")
    @PATCH("standing-orders/{id}")
    suspend fun updateStandingOrder(
        @Path("id") id: String,
        @Body body: JsonObject,
    ): Response<StandingOrderDto>

    /** Stop firing; keeps history. Resuming never back-fills the paused periods. */
    @POST("standing-orders/{id}/pause")
    suspend fun pauseStandingOrder(@Path("id") id: String): Response<StandingOrderDto>

    @POST("standing-orders/{id}/resume")
    suspend fun resumeStandingOrder(@Path("id") id: String): Response<StandingOrderDto>

    /** 204 — the order's run history cascades. */
    @DELETE("standing-orders/{id}")
    suspend fun deleteStandingOrder(@Path("id") id: String): Response<Unit>

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

    // ── V5 social: comments + emoji reactions on shared items ────────────────
    // `{kind}` is the full share ladder INCLUDING `idea`. Authorization is
    // re-derived per request from the item's current audience, and a caller who
    // may not read the item gets **404, never 403** — so a thread screen treats
    // 404 as "not shared with you", exactly like the shared detail views.
    // Writes ride the shared social rate limiter (30/hour → 429 RATE_LIMITED).

    /** Comments (oldest first, unpaged) + the item-level reaction tally. [social:read] */
    @GET("social/items/{kind}/{subjectId}/thread")
    suspend fun itemThread(
        @Path("kind") kind: String,
        @Path("subjectId") subjectId: String,
    ): Response<CommentThreadResponse>

    /** Post a comment (server trims, then enforces 1..2000). 201 → the new comment. [social:write] */
    @Headers("Content-Type: application/json")
    @POST("social/items/{kind}/{subjectId}/comments")
    suspend fun createItemComment(
        @Path("kind") kind: String,
        @Path("subjectId") subjectId: String,
        @Body body: CreateCommentRequest,
    ): Response<ItemCommentDto>

    /** Toggle one of the six emojis on the ITEM; answers the fresh full tally. [social:write] */
    @Headers("Content-Type: application/json")
    @POST("social/items/{kind}/{subjectId}/reactions")
    suspend fun toggleItemReaction(
        @Path("kind") kind: String,
        @Path("subjectId") subjectId: String,
        @Body body: ToggleReactionRequest,
    ): Response<ReactionListResponse>

    /** Toggle an emoji on one COMMENT; answers that comment's fresh tally. [social:write] */
    @Headers("Content-Type: application/json")
    @POST("social/comments/{commentId}/reactions")
    suspend fun toggleCommentReaction(
        @Path("commentId") commentId: String,
        @Body body: ToggleReactionRequest,
    ): Response<ReactionListResponse>

    /**
     * Delete a comment — own comment, or any comment on an item you own
     * (moderation). 204. A comment you may not delete answers **404
     * COMMENT_NOT_FOUND**, deliberately indistinguishable from a missing one.
     * [social:write]
     */
    @DELETE("social/comments/{commentId}")
    suspend fun deleteComment(@Path("commentId") commentId: String): Response<Unit>

    // ── V5 social: friend groups as sharing audiences ────────────────────────
    // Note the asymmetry the platform chose: adding a member PUTs the user id in
    // the BODY, removing puts it in the PATH, and removal answers 200 with the
    // refreshed group rather than 204.

    /** The caller's friend groups, each with its full member roster. [social:read] */
    @GET("social/groups")
    suspend fun friendGroups(): Response<FriendGroupListResponse>

    /** Create a group (name trimmed, 1..60; names are NOT unique). 201. [social:write] */
    @Headers("Content-Type: application/json")
    @POST("social/groups")
    suspend fun createFriendGroup(@Body body: FriendGroupNameRequest): Response<FriendGroupDto>

    /** Rename a group. [social:write] */
    @Headers("Content-Type: application/json")
    @PATCH("social/groups/{groupId}")
    suspend fun renameFriendGroup(
        @Path("groupId") groupId: String,
        @Body body: FriendGroupNameRequest,
    ): Response<FriendGroupDto>

    /** Delete a group. 204. Items shared to it then resolve to nobody. [social:write] */
    @DELETE("social/groups/{groupId}")
    suspend fun deleteFriendGroup(@Path("groupId") groupId: String): Response<Unit>

    /**
     * Add an accepted friend to a group (idempotent). A non-friend is refused
     * with 400 `GROUP_MEMBER_NOT_FRIEND`. Answers the refreshed group. [social:write]
     */
    @Headers("Content-Type: application/json")
    @POST("social/groups/{groupId}/members")
    suspend fun addFriendGroupMember(
        @Path("groupId") groupId: String,
        @Body body: AddGroupMemberRequest,
    ): Response<FriendGroupDto>

    /** Remove a member — **200 with the refreshed group**, not 204. [social:write] */
    @DELETE("social/groups/{groupId}/members/{userId}")
    suspend fun removeFriendGroupMember(
        @Path("groupId") groupId: String,
        @Path("userId") userId: String,
    ): Response<FriendGroupDto>

    // ── V5: mirrorchain participation (group portfolios) ─────────────────────
    // ONLY these seven routes accept a bearer — chain administration (create,
    // rename, invite, revoke, roles, transfer, kick, dissolve) is session-only
    // by a deliberate method-aware allowlist and answers 403 API_KEY_FORBIDDEN.
    // The app therefore never renders those actions at all. [mirrorchain:*]

    /** Chains I participate in, each with MY role and MY catch-up state. */
    @GET("mirrorchain/chains")
    suspend fun mirrorChains(): Response<MirrorChainListResponse>

    /** Roster + roles + per-member sync for one chain. */
    @GET("mirrorchain/chains/{chainId}/members")
    suspend fun mirrorChainMembers(
        @Path("chainId") chainId: String,
    ): Response<MirrorMemberListResponse>

    /** Newest-first activity page; pass the previous `nextCursor` as [before]. */
    @GET("mirrorchain/chains/{chainId}/activity")
    suspend fun mirrorChainActivity(
        @Path("chainId") chainId: String,
        @Query("before") before: Int? = null,
        @Query("limit") limit: Int? = null,
    ): Response<MirrorActivityResponse>

    /** Pending invites in both directions (expired ones are already filtered out). */
    @GET("mirrorchain/invites")
    suspend fun mirrorInvites(): Response<MirrorInviteListResponse>

    /** Accept an invite — materializes a local copy and returns its portfolio id. */
    @POST("mirrorchain/invites/{inviteId}/accept")
    suspend fun acceptMirrorInvite(
        @Path("inviteId") inviteId: String,
    ): Response<MirrorAcceptInviteResponse>

    /** Decline an invite. */
    @POST("mirrorchain/invites/{inviteId}/decline")
    suspend fun declineMirrorInvite(@Path("inviteId") inviteId: String): Response<MirrorOkResponse>

    /**
     * Leave a chain. Ownership succeeds to the oldest manager, or the chain
     * dissolves — there is no last-admin refusal any more (the contract's
     * `MIRROR_OWNER_TRANSFER_REQUIRED` is deprecated and never emitted). The
     * departing member keeps an un-synced fork of the portfolio.
     */
    @POST("mirrorchain/chains/{chainId}/leave")
    suspend fun leaveMirrorChain(@Path("chainId") chainId: String): Response<MirrorOkResponse>

    // ── Chain ADMINISTRATION — session-only on the platform today ────────────
    //
    // Every call below is refused for a bearer with `403 API_KEY_FORBIDDEN`, by
    // a deliberate method-aware allowlist in the platform's `bearerAuth`
    // middleware (and pinned there by a test, so it will not drift silently).
    // The mobile client holds a bearer and nothing else, so today all of these
    // fail — verified live, see the probe in [MirrorchainRepository].
    //
    // They are declared anyway, and that is a considered choice rather than
    // dead code. The app now draws the admin surface in a designed
    // "manage on the web" state and runs ONE capability probe per session; when
    // the platform adds these routes to the allowlist, the probe stops seeing
    // 403 and the same screens light up with no further app change. Declaring
    // the calls is what makes that a config change on their side instead of a
    // release on ours — and it keeps the exact request shapes reviewed against
    // the contract now, while the contract is in front of us.
    //
    // Roles (§5): rename/invite/revoke/kick-member = owner or manager;
    // kick-manager/roles/transfer/dissolve = owner only.

    /** Rename a chain. Owner or manager. */
    @Headers("Content-Type: application/json")
    @PATCH("mirrorchain/chains/{chainId}")
    suspend fun renameMirrorChain(
        @Path("chainId") chainId: String,
        @Body body: MirrorRenameChainRequest,
    ): Response<MirrorChainSummaryDto>

    /** Invite a friend. Owner or manager; the invitee must already be a friend. */
    @Headers("Content-Type: application/json")
    @POST("mirrorchain/chains/{chainId}/invites")
    suspend fun createMirrorInvite(
        @Path("chainId") chainId: String,
        @Body body: MirrorCreateInviteRequest,
    ): Response<MirrorOkResponse>

    /** Revoke an invite you sent. Owner or manager. */
    @POST("mirrorchain/invites/{inviteId}/revoke")
    suspend fun revokeMirrorInvite(@Path("inviteId") inviteId: String): Response<MirrorOkResponse>

    /**
     * Grant or revoke manager. Owner only. `owner` is NOT assignable here — the
     * only route to it is [transferMirrorChain].
     */
    @Headers("Content-Type: application/json")
    @PATCH("mirrorchain/chains/{chainId}/members/{userId}/role")
    suspend fun setMirrorMemberRole(
        @Path("chainId") chainId: String,
        @Path("userId") userId: String,
        @Body body: MirrorSetRoleRequest,
    ): Response<MirrorOkResponse>

    /** Hand the chain to another member. Owner only. */
    @Headers("Content-Type: application/json")
    @POST("mirrorchain/chains/{chainId}/transfer")
    suspend fun transferMirrorChain(
        @Path("chainId") chainId: String,
        @Body body: MirrorTransferRequest,
    ): Response<MirrorOkResponse>

    /** Remove a member. The owner can never be removed; use Leave for yourself. */
    @DELETE("mirrorchain/chains/{chainId}/members/{userId}")
    suspend fun removeMirrorMember(
        @Path("chainId") chainId: String,
        @Path("userId") userId: String,
    ): Response<Unit>

    /** Dissolve the chain for everyone. Owner only. Irreversible. */
    @DELETE("mirrorchain/chains/{chainId}")
    suspend fun dissolveMirrorChain(@Path("chainId") chainId: String): Response<Unit>

    /** Turn one of my own portfolios into a group portfolio. */
    @Headers("Content-Type: application/json")
    @POST("mirrorchain/chains/convert")
    suspend fun convertPortfolioToChain(
        @Body body: MirrorConvertRequest,
    ): Response<MirrorChainSummaryDto>

    // ── V5: workboard ideas (saved backtest analyses) ────────────────────────
    // Bearer-reachable under the same `workboard:*` pair as conglomerates and
    // backtest (its own MODULE_POLICIES row). Write bodies are composed as
    // JsonObject because the state schema is a strict discriminated union with a
    // required-but-nullable benchmark — see IdeaDtos for the full reasoning.

    /** The caller's ideas, newest first. Unpaged. [workboard:read] */
    @GET("ideas")
    suspend fun ideas(): Response<IdeaListResponse>

    /** One idea — **owner only**; a friend's shared idea 404s here. [workboard:read] */
    @GET("ideas/{ideaId}")
    suspend fun idea(@Path("ideaId") ideaId: String): Response<IdeaResponse>

    /** Create an idea. 201. [workboard:write] */
    @Headers("Content-Type: application/json")
    @POST("ideas")
    suspend fun createIdea(@Body body: JsonObject): Response<IdeaResponse>

    /**
     * Update an idea. Omitting `thesis` leaves it untouched; sending it as an
     * explicit null clears it — which is exactly why this body is a JsonObject.
     * [workboard:write]
     */
    @Headers("Content-Type: application/json")
    @PATCH("ideas/{ideaId}")
    suspend fun updateIdea(
        @Path("ideaId") ideaId: String,
        @Body body: JsonObject,
    ): Response<IdeaResponse>

    /** Delete an idea. 204. [workboard:write] */
    @DELETE("ideas/{ideaId}")
    suspend fun deleteIdea(@Path("ideaId") ideaId: String): Response<Unit>

    /**
     * Clone a friend's shared idea into my own list (the only way a non-owner
     * ever reads an idea's full state). 201. [workboard:write]
     */
    @POST("ideas/{ideaId}/clone")
    suspend fun cloneIdea(@Path("ideaId") ideaId: String): Response<IdeaResponse>

    // ── Step 16: notifications (§6.11 — LIVE on Notifications-v2, PR #427) ────
    // Bearer-auth: `notifications:read` (GETs) / `notifications:write` (writes) —
    // both in the mobile client's granted ceiling (no new consent). The OpenAPI
    // per-route `security` here actually lists apiKeyBearer too; either way the
    // bearer works. Real FCM *sends* stay dark until the Firebase key lands on the
    // server (platform #421) — token registration below is live regardless.

    /**
     * In-app inbox: newest-first cursor-paged notifications + unread count.
     * `view` = active | archived | all (Notifications-v3 #437; default active,
     * omitted for pre-v3 servers → same as active). Badge = unread ACTIVE only.
     */
    @GET("notifications")
    suspend fun notifications(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("view") view: String? = null,
    ): Response<NotificationListResponse>

    /** Mark specific notifications read (1–200 ids). */
    @Headers("Content-Type: application/json")
    @POST("notifications/mark-read")
    suspend fun markNotificationsRead(@Body body: MarkReadIdsRequest): Response<Unit>

    /** Mark ALL notifications read. */
    @Headers("Content-Type: application/json")
    @POST("notifications/mark-read")
    suspend fun markAllNotificationsRead(@Body body: MarkReadAllRequest): Response<Unit>

    // ── Notifications-v3: archive / delete (platform #437) ───────────────────
    // archive implies read; archived rows leave the bell surface but stay under
    // the Archived/All filters. Per-item + bulk. All bearer `notifications:write`.
    // Empty-body 2xx → Response<Unit> (checked via isSuccessful, not apiCall).

    /** Archive one notification (implies read). */
    @POST("notifications/{id}/archive")
    suspend fun archiveNotification(@Path("id") id: String): Response<Unit>

    /** Restore one archived notification to ACTIVE (read state unchanged). */
    @POST("notifications/{id}/unarchive")
    suspend fun unarchiveNotification(@Path("id") id: String): Response<Unit>

    /** Bulk: archive every already-read active notification. */
    @POST("notifications/archive-all-read")
    suspend fun archiveAllReadNotifications(): Response<Unit>

    /** Delete one notification (hard delete). */
    @DELETE("notifications/{id}")
    suspend fun deleteNotification(@Path("id") id: String): Response<Unit>

    /** Bulk delete: `scope` = archived | all (no body). */
    @DELETE("notifications")
    suspend fun deleteNotifications(@Query("scope") scope: String): Response<Unit>

    /** Upsert this install's FCM device token `{ token, platform:"android" }`. [notifications:write] */
    @Headers("Content-Type: application/json")
    @POST("notifications/devices")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<DeviceAckResponse>

    /**
     * Deregister this install's FCM token on logout `{ token }`. DELETE carries a
     * body → `@HTTP(hasBody = true)` (same shape as [deleteAccount]). [notifications:write]
     */
    @Headers("Content-Type: application/json")
    @HTTP(method = "DELETE", path = "notifications/devices", hasBody = true)
    suspend fun deregisterDevice(@Body body: DeregisterDeviceRequest): Response<DeviceAckResponse>

    /** The per-type × per-channel notification preference matrix (mirrors web). */
    @GET("settings/notifications")
    suspend fun notificationSettings(): Response<NotificationSettingsResponse>

    /** Update the in-app/email/push matrix (webpush echoed; per-type Mute stays app-local). */
    @Headers("Content-Type: application/json")
    @PATCH("settings/notifications")
    suspend fun updateNotificationSettings(
        @Body body: UpdateNotificationSettingsRequest,
    ): Response<NotificationSettingsResponse>

    // ── Step 15: friend chat (§6.10 — LIVE on #349 + #386) ───────────────────
    // 1:1 friend-only conversations (one per pair). Gate on chat:read (GET) /
    // chat:write (POST) — granted, need the ACTIVATION re-login. Non-friend /
    // never-participant → 404 (never data); unfriending closes new messages but
    // history stays readable. Realtime is the /ws gateway (invalidation only) with
    // a polling fallback — see DefaultChatRepository.

    /** The caller's conversations, newest-active first, with per-thread + total unread. */
    @GET("chat/conversations")
    suspend fun chatConversations(): Response<ChatConversationListResponse>

    /** Open (or resolve) the 1:1 conversation with a friend; non-friend → 404. [chat:write] */
    @Headers("Content-Type: application/json")
    @POST("chat/conversations")
    suspend fun openChatConversation(
        @Body body: OpenConversationRequest,
    ): Response<ConversationResponse>

    /** A page of a thread (newest-first, keyset by message id) + the conversation summary. */
    @GET("chat/conversations/{conversationId}/messages")
    suspend fun chatThread(
        @Path("conversationId") conversationId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<ChatThreadResponse>

    /** Send a message (text, a share chip, or both; body ≤ CHAT_MESSAGE_MAX). [chat:write] */
    @Headers("Content-Type: application/json")
    @POST("chat/conversations/{conversationId}/messages")
    suspend fun sendChatMessage(
        @Path("conversationId") conversationId: String,
        @Body body: SendChatMessageRequest,
    ): Response<SendChatMessageResponse>

    /** Mark the whole conversation read (drives the unread badges). [chat:write] */
    @POST("chat/conversations/{conversationId}/read")
    suspend fun markChatRead(
        @Path("conversationId") conversationId: String,
    ): Response<Unit>

    // ── Step 18: account & security management (§6.12) ───────────────────────
    // Bearer-reachable via the `account:security` scope (#361, verified LIVE on
    // production 2026-07-10). The OpenAPI + a stale inline comment on the sessions
    // route say "cookie-only" — that is the known docs bug; the #361 bearer
    // middleware carve-out (apps/api/.../bearerAuth.ts resolveAuthPolicy) is truth.

    /**
     * Change the account password (§6.12). Voluntary change: [currentPassword] is
     * required and server-verified — a wrong one is 401 INVALID_CREDENTIALS. 200
     * returns the refreshed user. [account:security]
     */
    @Headers("Content-Type: application/json")
    @POST("auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Response<MeResponse>

    /** The caller's current 2FA method state (TOTP / email / recovery). [account:security] */
    @GET("auth/2fa/status")
    suspend fun twoFactorStatus(): Response<TwoFactorStatusResponse>

    /**
     * Begin TOTP enrollment — provisional secret + otpauth URI; the method is NOT
     * yet on and NO recovery codes issue until [twoFactorConfirm]. [account:security]
     */
    @POST("auth/2fa/enroll")
    suspend fun twoFactorEnroll(): Response<TwoFactorEnrollResponse>

    /** Confirm TOTP enrollment with a current code → enables it (+ first-method recovery codes). [account:security] */
    @Headers("Content-Type: application/json")
    @POST("auth/2fa/confirm")
    suspend fun twoFactorConfirm(@Body body: TwoFactorCodeRequest): Response<TwoFactorMethodEnabledResponse>

    /** Disable the TOTP method; authorized by a TOTP code or an unused recovery code. [account:security] */
    @Headers("Content-Type: application/json")
    @POST("auth/2fa/disable")
    suspend fun twoFactorDisable(@Body body: TwoFactorDisableRequest): Response<Unit>

    /** Begin email-method enrollment — sends a setup code to the account email. [account:security] */
    @POST("auth/2fa/email/enroll")
    suspend fun twoFactorEmailEnroll(): Response<Unit>

    /** Confirm the email method with the emailed code → enables it. [account:security] */
    @Headers("Content-Type: application/json")
    @POST("auth/2fa/email/confirm")
    suspend fun twoFactorEmailConfirm(@Body body: TwoFactorCodeRequest): Response<TwoFactorMethodEnabledResponse>

    /** Disable the email method (authenticated session alone). [account:security] */
    @POST("auth/2fa/email/disable")
    suspend fun twoFactorEmailDisable(): Response<Unit>

    /** Regenerate the recovery codes (voids the old set); requires a method on. [account:security] */
    @POST("auth/2fa/recovery-codes")
    suspend fun twoFactorRegenerateRecoveryCodes(): Response<TwoFactorRecoveryCodesResponse>

    /** The account's active web/cookie sessions (browser + other logins). [account:security] */
    @GET("auth/sessions")
    suspend fun sessions(): Response<SessionListResponse>

    /** Revoke ONE session by its opaque handle ("log out that device"). [account:security] */
    @DELETE("auth/sessions/{id}")
    suspend fun revokeSession(@Path("id") id: String): Response<Unit>

    /** Log out every OTHER session, keeping the caller's. From a bearer (no session)
     *  this revokes ALL web sessions — offered with a strong confirm. [account:security] */
    @POST("auth/sessions/revoke-others")
    suspend fun revokeOtherSessions(): Response<RevokeSessionsResponse>

    /** The account defaults incl. the server-side UI `locale`. [social:read] */
    @GET("settings/account")
    suspend fun accountSettings(): Response<AccountSettingsResponse>

    /** Mirror the in-app language choice to the account `locale`. [social:write] */
    @Headers("Content-Type: application/json")
    @PATCH("settings/account")
    suspend fun updateAccountSettings(@Body body: UpdateAccountSettingsRequest): Response<AccountSettingsResponse>

    /** The caller's own profile — username, public flag, bio, icon. [social:read] */
    @GET("social/profile")
    suspend fun socialProfile(): Response<ProfileSettingsResponse>

    /**
     * Replace the caller's profile. A PUT, not a PATCH: `isPublic` must always
     * be sent, and turning it on additionally requires `acknowledgePublic=true`.
     *
     * The body is a raw [JsonObject] rather than [UpdateProfileSettingsRequest],
     * for one reason that is invisible until you hit it: `profileIcon` uses the
     * omitted-vs-null distinction — **omitted** means "leave it alone", **null**
     * means "clear it back to the default". The app's shared `Json` is configured
     * `explicitNulls = false`, which DROPS a null property, so a typed DTO
     * physically cannot express "clear". Clearing an icon would silently become a
     * no-op that the server answers 200 to.
     *
     * Composing the object by hand is what makes both intents sendable. See
     * [at.bettertrack.app.data.account.AccountRepository.updateProfileIcon].
     * [social:write]
     */
    @Headers("Content-Type: application/json")
    @PUT("social/profile")
    suspend fun updateSocialProfile(
        @Body body: JsonObject,
    ): Response<ProfileSettingsResponse>

    // ── Taxes (V3-P4 / V5-P4) ────────────────────────────────────────────────
    //
    // Three tiers, and they are genuinely different resources rather than one
    // resource read three ways:
    //   1. `/settings/taxes`                     — the USER's default, i.e. what a
    //      newly created portfolio inherits.
    //   2. `/portfolios/:id/settings/tax`        — ONE portfolio's override, plus
    //      the resolved cascade (`effective`/`override`/`userDefault`/`source`).
    //   3. `/portfolios/:id/reports/tax-years…`  — the derived per-year report.
    //
    // Note the path asymmetry, which is the contract's and not a typo to be
    // "fixed" here: the user-level route is plural (`taxes`), the per-portfolio
    // one is singular (`tax`).

    /** The caller's user-level tax default. [portfolio:read] */
    @GET("settings/taxes")
    suspend fun taxSettings(): Response<TaxSettingsDto>

    /**
     * Replace the user-level tax default. The body's mode-dependent fields are
     * validated by a `superRefine` that rejects an inconsistent combination
     * (a `country` outside `country_specific`, `custom` params outside `custom`,
     * a manual default outside `manual_per_trade`, or an amount AND a rate), so
     * the caller must send the exact field set the mode allows — see
     * [at.bettertrack.app.domain.TaxSettingsDraft]. [portfolio:write]
     */
    @Headers("Content-Type: application/json")
    @PATCH("settings/taxes")
    suspend fun updateTaxSettings(@Body body: UpdateTaxSettingsRequest): Response<TaxSettingsDto>

    /** One portfolio's tax treatment, resolved through the cascade. [portfolio:read] */
    @GET("portfolios/{portfolioId}/settings/tax")
    suspend fun portfolioTaxSettings(
        @Path("portfolioId") portfolioId: String,
    ): Response<PortfolioTaxSettingsResponse>

    /** Pin an override on this portfolio. [portfolio:write] */
    @Headers("Content-Type: application/json")
    @PUT("portfolios/{portfolioId}/settings/tax")
    suspend fun putPortfolioTaxSettings(
        @Path("portfolioId") portfolioId: String,
        @Body body: UpdateTaxSettingsRequest,
    ): Response<PortfolioTaxSettingsResponse>

    /**
     * Drop the override so the portfolio inherits the user default again.
     * Returns the re-resolved cascade rather than 204, so the caller never has
     * to guess what it fell back TO. [portfolio:write]
     */
    @DELETE("portfolios/{portfolioId}/settings/tax")
    suspend fun deletePortfolioTaxSettings(
        @Path("portfolioId") portfolioId: String,
    ): Response<PortfolioTaxSettingsResponse>

    /** Every Vienna calendar year this portfolio has tax facts for, newest first. [portfolio:read] */
    @GET("portfolios/{portfolioId}/reports/tax-years")
    suspend fun taxYears(@Path("portfolioId") portfolioId: String): Response<TaxYearListResponse>

    /** One year's drill-down: summary + per-position sells and dividends. [portfolio:read] */
    @GET("portfolios/{portfolioId}/reports/tax-years/{year}")
    suspend fun taxYearReport(
        @Path("portfolioId") portfolioId: String,
        @Path("year") year: Int,
    ): Response<TaxYearReportResponse>

    /**
     * The same year, serialized as a labeled-section CSV (V5-P4b).
     *
     * `locale` (`en` | `de`) picks the header/label language ONLY — the numbers
     * are the identical source-of-truth values [taxYearReport] returns, because
     * the server serializes that response rather than recomputing. So the export
     * can never disagree with the screen the user is looking at.
     *
     * `@Streaming` because this is a file, not a model: without it OkHttp buffers
     * the whole body into memory before Retrofit hands it over, which is the
     * wrong shape for something whose only destination is a file on disk.
     * [portfolio:read]
     */
    @Streaming
    @GET("portfolios/{portfolioId}/reports/tax-years/{year}/export.csv")
    suspend fun taxYearCsv(
        @Path("portfolioId") portfolioId: String,
        @Path("year") year: Int,
        @Query("locale") locale: String? = null,
    ): Response<ResponseBody>

    /**
     * Hard-delete the account (#362, spec §6.12; a Play publishing requirement).
     * Irreversible: typed username confirmation + re-auth (password, or a TOTP /
     * recovery code for 2FA accounts). DELETE with a body needs @HTTP. The app
     * gates the actual call behind [at.bettertrack.app.data.account.DeleteAccountFeature]
     * so it can never fire against the live production account during testing.
     * [account:security]
     */
    @Headers("Content-Type: application/json")
    @HTTP(method = "DELETE", path = "account", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequest): Response<Unit>

    /**
     * The running build of the live server (public, no auth). Cosmetic "API build"
     * row on the About screen — loaded fail-soft. The authenticated client is used
     * for convenience; the endpoint ignores the bearer.
     */
    @GET("version")
    suspend fun version(): Response<VersionResponse>
}
