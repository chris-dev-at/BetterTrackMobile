package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the portfolio-scope module endpoints (Step 5). Field names
 * follow the OpenAPI contract exactly (camelCase). Request bodies deliberately
 * contain ONLY documented fields — the API validates with
 * `additionalProperties: false`, and there is NO idempotency-key/client-
 * reference field yet (§7.3 platform-prereq, gap noted in TODO.md).
 */

// ── Shared asset identity (embedded in holdings / transactions) ─────────────
@Serializable
data class AssetDto(
    val id: String,
    val symbol: String,
    val name: String,
    val exchange: String? = null,
    val currency: String,
    /** "stock" | "etf" | "index" | "fx" | "commodity" | "crypto" | "custom". */
    val type: String,
    val isCustom: Boolean = false,
)

// ── GET /portfolios ──────────────────────────────────────────────────────────
@Serializable
data class PortfolioListResponse(
    val portfolios: List<PortfolioDto> = emptyList(),
)

@Serializable
data class PortfolioDto(
    val id: String,
    val name: String,
    val visibility: String,
    val sortOrder: Int,
    val isDefault: Boolean,
    val defaultPayFromCash: Boolean,
    val archivedAt: String? = null,
)

/** POST /portfolios — used by the Step-5 debug screen's E2E test-data setup. */
@Serializable
data class CreatePortfolioRequest(
    val name: String,
)

@Serializable
data class PortfolioMutationResponse(
    val portfolio: PortfolioDto,
)

/** PATCH /portfolios/{id} — rename and/or change visibility (Step 6 switcher). */
@Serializable
data class UpdatePortfolioRequest(
    val name: String? = null,
    val visibility: String? = null,
    val defaultPayFromCash: Boolean? = null,
)

// ── GET /portfolios/{id}/history — the §6.1 graph (server-computed series) ──
// The endpoint is DAY-granular and supports ONLY range=1M|6M|1Y|MAX — there is
// no server-side 1D/1W/3M portfolio window (platform gap noted in TODO.md; the
// web app offers the same subset for the same reason). `performance` is the
// server-computed time-weighted % series — the app never derives it locally.
@Serializable
data class PortfolioHistoryResponse(
    val range: String,
    val baseCurrency: String,
    val points: List<HistoryPointDto> = emptyList(),
    val performance: List<PerformancePointDto> = emptyList(),
)

@Serializable
data class HistoryPointDto(
    /** Calendar date `yyyy-MM-dd`. */
    val date: String,
    val valueEur: Double,
)

@Serializable
data class PerformancePointDto(
    /** Calendar date `yyyy-MM-dd`. */
    val date: String,
    /** Server-computed performance % since range start (percent units). */
    val pct: Double,
)

// ── GET /portfolios/{id} — holdings + server-computed totals ────────────────
@Serializable
data class PortfolioDetailResponse(
    val baseCurrency: String,
    val holdings: List<HoldingDto> = emptyList(),
    val totals: PortfolioTotalsDto,
)

@Serializable
data class HoldingDto(
    val asset: AssetDto,
    val quantity: Double,
    val avgCost: Double,
    val realizedPnl: Double,
    val price: Double? = null,
    val marketValueEur: Double? = null,
    val costBasisEur: Double? = null,
    val unrealizedPnlEur: Double? = null,
    val unrealizedPnlPct: Double? = null,
    val dayChangeEur: Double? = null,
    val dayChangePct: Double? = null,
)

@Serializable
data class PortfolioTotalsDto(
    val marketValueEur: Double,
    val investedEur: Double,
    val unrealizedPnlEur: Double,
    val unrealizedPnlPct: Double? = null,
    val dayChangeEur: Double,
    val dayChangePct: Double? = null,
    val cashEur: Double,
    val totalValueEur: Double,
)

// ── GET/POST /portfolios/{id}/transactions ───────────────────────────────────
@Serializable
data class TransactionListResponse(
    val items: List<TransactionDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class TransactionDto(
    val id: String,
    val assetId: String,
    val side: String,
    val quantity: Double,
    val price: Double,
    val fee: Double,
    val executedAt: String,
    val note: String? = null,
    val asset: AssetDto,
)

/** Single-transaction create body (the queue drains ops one at a time, §7.3). */
@Serializable
data class CreateTransactionRequest(
    val assetId: String,
    val side: String,
    val quantity: Double,
    val price: Double,
    val fee: Double = 0.0,
    val executedAt: String,
    val note: String? = null,
    val payFromCash: Boolean? = null,
    val addProceedsToCash: Boolean? = null,
    /**
     * Backdated pay-from-cash settlement (contract #378): keep the stock trade on
     * its past [executedAt] but date the linked cash-withdrawal leg TODAY when the
     * Main wallet was short as of that date. Server ignores it when cash sufficed
     * at the buy date; omitted (null) on sells / non-coupled writes.
     */
    val settleCashAsOfToday: Boolean? = null,
    /**
     * Uncovered (over-)sell (contract PR #429): a SELL with quantity > held
     * (incl. zero holding) 400s `OVERSELL` unless this is `true`; then the
     * position closes at exactly 0 and full proceeds go to cash. Omitted (null)
     * on buys / covered sells.
     */
    val allowUncovered: Boolean? = null,
    /**
     * Optional native per-unit cost basis for the uncovered part; null ⇒ the
     * server bases it on the sale price (0 % realized on the uncovered part).
     */
    val uncoveredEntryPrice: Double? = null,
)

@Serializable
data class CreateTransactionsResponse(
    val transactions: List<TransactionDto> = emptyList(),
)

/**
 * PATCH /portfolios/{id}/transactions/{txId} (Step 8 — edit a SYNCED
 * transaction, online-only per §7.2). Every field optional; the contract has
 * NO `assetId` and NO cash-coupling flags — the asset and the original cash
 * movement can't be changed by an edit. The server re-validates oversell.
 */
@Serializable
data class UpdateTransactionRequest(
    val side: String? = null,
    val quantity: Double? = null,
    val price: Double? = null,
    val fee: Double? = null,
    val executedAt: String? = null,
    val note: String? = null,
    /**
     * Uncovered (over-)sell on the EDIT endpoint (contract PR #429): re-sending
     * an edit that raises the sold quantity past the held amount 400s `OVERSELL`
     * unless this is `true`. Sent when the edited sell is uncovered.
     */
    val allowUncovered: Boolean? = null,
    /** Optional native per-unit cost basis for the uncovered part (see create). */
    val uncoveredEntryPrice: Double? = null,
)

@Serializable
data class UpdateTransactionResponse(
    val transaction: TransactionDto,
)

// ── GET /portfolios/{id}/cash + deposit / withdraw ───────────────────────────
@Serializable
data class CashMovementsResponse(
    val balanceEur: Double,
    val movements: List<CashMovementDto> = emptyList(),
    /** Step 9: the platform now ships real named sources (Main first). */
    val sources: List<CashSourceDto> = emptyList(),
)

@Serializable
data class CashMovementDto(
    val id: String,
    /** "deposit" | "withdrawal" | "buy" | "sell_proceeds" | "transfer_out" | "transfer_in". */
    val kind: String,
    val amountEur: Double,
    /** Owning source (Step 9 — every movement belongs to a source). */
    val sourceId: String? = null,
    val transactionId: String? = null,
    /** Pairs the two legs of a transfer. */
    val transferId: String? = null,
    /** The other source of a transfer leg. */
    val counterpartSourceId: String? = null,
    val executedAt: String,
    val note: String? = null,
    val createdAt: String,
)

@Serializable
data class CashEntryRequest(
    val amountEur: Double,
    /** Target source; omitted = Main. */
    val sourceId: String? = null,
    val executedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class CashMovementResponse(
    val movement: CashMovementDto,
    val balanceEur: Double,
)

// ── Step 9: cash sources & transfers (§6.3) ─────────────────────────────────

@Serializable
data class CashSourceDto(
    val id: String,
    val name: String,
    /** "bank" | "retirement" | "cash" | "custom". */
    val type: String,
    val isMain: Boolean,
    val archivedAt: String? = null,
    val createdAt: String,
    val balanceEur: Double,
)

@Serializable
data class CashSourceListResponse(
    val sources: List<CashSourceDto> = emptyList(),
)

@Serializable
data class CashSourceResponse(
    val source: CashSourceDto,
)

/** POST /cash/sources (create) and PATCH /cash/sources/{id} (rename/relabel). */
@Serializable
data class CashSourceRequest(
    val name: String? = null,
    val type: String? = null,
)

/** POST /cash/transfer — atomic paired movements between two sources. */
@Serializable
data class CashTransferRequest(
    val fromSourceId: String,
    val toSourceId: String,
    val amountEur: Double,
    val executedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class CashTransferResponse(
    val outgoing: CashMovementDto,
    val incoming: CashMovementDto? = null,
)

// ── GET/PUT /custom-assets/{id}/value-points ─────────────────────────────────
@Serializable
data class ValuePointDto(
    /** Calendar date `yyyy-MM-dd`. */
    val date: String,
    val value: Double,
)

@Serializable
data class ValuePointsResponse(
    val points: List<ValuePointDto> = emptyList(),
)

@Serializable
data class PutValuePointsRequest(
    val points: List<ValuePointDto>,
)

// ── Step 10: custom asset management (§6.4) ─────────────────────────────────

@Serializable
data class CustomAssetDto(
    val id: String,
    val symbol: String,
    val name: String,
    /** V3-P2 catalog taxonomy: "stock" | "etf" | "crypto" | "commodity" | "cash_like" | "other". */
    val category: String? = null,
    val currency: String,
    val type: String = "custom",
    /** Value-smoothing toggle (V3-P2): false = step/carry-forward, true = linear interpolation. */
    val smoothing: Boolean = false,
)

/** POST /custom-assets — create (with an optional initial buy into a portfolio). */
@Serializable
data class CreateCustomAssetRequest(
    val name: String,
    val category: String,
    val currency: String = "EUR",
    /** V3-P2 value smoothing; server default is false (honest step treatment of sparse data). */
    val smoothing: Boolean = false,
    val initialPurchase: CustomAssetInitialPurchase? = null,
)

@Serializable
data class CustomAssetInitialPurchase(
    val quantity: Double,
    val price: Double,
    val fee: Double = 0.0,
    val executedAt: String,
    val note: String? = null,
)

@Serializable
data class CreateCustomAssetResponse(
    val asset: CustomAssetDto,
    val transactionId: String? = null,
)

/** PATCH /custom-assets/{id} — edit name/category/smoothing (currency immutable). */
@Serializable
data class UpdateCustomAssetRequest(
    val name: String? = null,
    val category: String? = null,
    /** Toggle value smoothing any time (V3-P2); null ⇒ leave unchanged (explicitNulls=false omits it). */
    val smoothing: Boolean? = null,
)

@Serializable
data class UpdateCustomAssetResponse(
    val asset: CustomAssetDto,
)

/**
 * GET /custom-assets (#387) — one entry per custom asset the caller owns,
 * INCLUDING zero-holding ones, with its most recent value point (or null). Lets
 * the app list/manage custom assets even with no current holding.
 */
@Serializable
data class CustomAssetListItemDto(
    val id: String,
    val symbol: String,
    val name: String,
    val category: String? = null,
    val currency: String,
    val type: String = "custom",
    val smoothing: Boolean = false,
    val latestValue: ValuePointDto? = null,
)

@Serializable
data class CustomAssetListResponse(
    val assets: List<CustomAssetListItemDto> = emptyList(),
)

// ── GET /workboard (the platform's single watchlist, §6.6) ───────────────────
@Serializable
data class WorkboardListResponse(
    val items: List<WorkboardItemDto> = emptyList(),
)

@Serializable
data class WorkboardItemDto(
    val id: String,
    val assetId: String,
    val sortOrder: Int,
    val note: String? = null,
    val asset: WorkboardAssetDto,
)

/** Workboard rows embed asset identity WITHOUT id/isCustom (API contract). */
@Serializable
data class WorkboardAssetDto(
    val symbol: String,
    val name: String,
    val exchange: String? = null,
    val currency: String,
    val type: String,
)

// ── GET /conglomerates (+ detail) — read models only in Step 5 ──────────────
@Serializable
data class ConglomerateListResponse(
    val conglomerates: List<ConglomerateDto> = emptyList(),
)

@Serializable
data class ConglomerateDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val status: String,
    val visibility: String,
    val positionCount: Int,
    val createdAt: String,
    val updatedAt: String,
)
