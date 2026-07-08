package at.bettertrack.app.data.api

import at.bettertrack.app.data.api.dto.CashEntryRequest
import at.bettertrack.app.data.api.dto.CashMovementResponse
import at.bettertrack.app.data.api.dto.CashMovementsResponse
import at.bettertrack.app.data.api.dto.CashSourceListResponse
import at.bettertrack.app.data.api.dto.CashSourceRequest
import at.bettertrack.app.data.api.dto.CashSourceResponse
import at.bettertrack.app.data.api.dto.CashTransferRequest
import at.bettertrack.app.data.api.dto.CashTransferResponse
import at.bettertrack.app.data.api.dto.AddToWorkboardRequest
import at.bettertrack.app.data.api.dto.AssetDetailResponse
import at.bettertrack.app.data.api.dto.AssetHistoryResponse
import at.bettertrack.app.data.api.dto.ConglomerateListResponse
import at.bettertrack.app.data.api.dto.CreateCustomAssetRequest
import at.bettertrack.app.data.api.dto.DailyClosesResponse
import at.bettertrack.app.data.api.dto.CreateCustomAssetResponse
import at.bettertrack.app.data.api.dto.CreatePortfolioRequest
import at.bettertrack.app.data.api.dto.CreateTransactionRequest
import at.bettertrack.app.data.api.dto.CreateTransactionsResponse
import at.bettertrack.app.data.api.dto.MeResponse
import at.bettertrack.app.data.api.dto.OAuthGrantListResponse
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

    // ── Step 11/12: the single unnamed workboard watchlist (§6.6) ────────────

    @GET("workboard")
    suspend fun workboard(): Response<WorkboardListResponse>

    /** Add an asset to the workboard watchlist; returns the created item. */
    @Headers("Content-Type: application/json")
    @POST("workboard")
    suspend fun addToWorkboard(@Body body: AddToWorkboardRequest): Response<WorkboardItemDto>

    /** Remove a workboard item (by ITEM id, not asset id). */
    @DELETE("workboard/{itemId}")
    suspend fun removeFromWorkboard(@Path("itemId") itemId: String): Response<Unit>

    @GET("conglomerates")
    suspend fun conglomerates(): Response<ConglomerateListResponse>

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
}
