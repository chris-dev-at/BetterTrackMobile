package at.bettertrack.app.data.net

import at.bettertrack.app.data.api.dto.CreateCustomAssetRequest
import at.bettertrack.app.data.api.dto.CreateCustomAssetResponse
import at.bettertrack.app.data.api.dto.PinVerifyRequest
import at.bettertrack.app.data.api.dto.PinVerifyResponse
import at.bettertrack.app.data.api.dto.PortfolioDetailResponse
import at.bettertrack.app.data.api.dto.PortfolioHistoryResponse
import at.bettertrack.app.data.api.dto.PortfolioListResponse
import at.bettertrack.app.data.api.dto.SearchResponse

/**
 * The common, plain-Kotlin API contract — the KMP/iOS port's platform-neutral
 * successor to the Retrofit `at.bettertrack.app.data.api.BtApi`. It returns a
 * common [ApiResponse] instead of a `retrofit2.Response`, so a repository can be
 * migrated to it and then run UNCHANGED on both platforms: Android behind a thin
 * Retrofit adapter, iOS behind the Ktor client in `iosMain`.
 *
 * NAMED distinctly from the Retrofit `BtApi` ON PURPOSE: the 189-endpoint
 * Retrofit interface and its 14 injecting repositories stay byte-identical this
 * chunk (Option B — Android's production session path does not change). The two
 * coexist; the repositories adopt this contract in the later repo-move chunk.
 *
 * This is a REPRESENTATIVE slice, not the full 189 — enough to prove the shape
 * (path params, query params, an Idempotency-Key mutation, and the
 * `X-Bt-No-Reauth` opt-out) and to drive the session-integrity proofs. The full
 * surface is reproduced in the repo-move chunk.
 */
interface BtApiClient {

    /** GET /portfolios/{id} — an ETag'd conditional-GET target. */
    suspend fun getPortfolio(id: String): ApiResponse<PortfolioDetailResponse>

    /** GET /portfolios/{id}/history?range= — an ETag'd conditional-GET target. */
    suspend fun getPortfolioHistory(id: String, range: String): ApiResponse<PortfolioHistoryResponse>

    /** GET /search?q= — an ETag'd conditional-GET target. */
    suspend fun search(q: String): ApiResponse<SearchResponse>

    /** GET /portfolios — NOT an ETag target (proves non-targets pass through). */
    suspend fun getPortfolios(): ApiResponse<PortfolioListResponse>

    /**
     * POST /custom-assets — carries an optional `Idempotency-Key` (platform #432):
     * a resend of a queued mutation after a lost response must not double-apply.
     */
    suspend fun createCustomAsset(
        body: CreateCustomAssetRequest,
        idempotencyKey: String?,
    ): ApiResponse<CreateCustomAssetResponse>

    /**
     * POST /auth/pin/verify — carries `X-Bt-No-Reauth`. A 401 here is a DOMAIN
     * answer (wrong PIN), NOT an expired token: the auth plugin must NOT refresh
     * and retry, or it would silently double-count the attempt against the
     * server's PIN limiter.
     */
    suspend fun pinVerify(body: PinVerifyRequest): ApiResponse<PinVerifyResponse>
}
