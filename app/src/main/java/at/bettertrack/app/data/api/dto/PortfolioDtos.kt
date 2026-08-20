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
    /**
     * The portfolio's icon/kind: `private|family|business|savings|property`.
     *
     * Nullable because the server distinguishes "never chosen" from a concrete
     * choice — a null must not be read as `private`, or the one-time migration
     * of a locally-chosen icon would have nothing to detect.
     */
    val kind: String? = null,
    /** v5: present when this portfolio is a mirrorchain (group) copy. */
    val mirror: PortfolioMirrorBadgeDto? = null,
    /** v5: present when it USED to be one. Mutually exclusive with [mirror]. */
    val mirrorFork: PortfolioMirrorForkDto? = null,
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

/**
 * PATCH /portfolios/{id} — rename, visibility, cash-coupling default, icon.
 *
 * Every field is nullable and the shared `Json` has `explicitNulls = false`, so
 * a request carries only what actually changed. The server's schema is strict,
 * which is the other half of why this must not gain speculative fields.
 */
@Serializable
data class UpdatePortfolioRequest(
    val name: String? = null,
    val visibility: String? = null,
    val defaultPayFromCash: Boolean? = null,
    /** `private|family|business|savings|property`. */
    val kind: String? = null,
)

// ── GET /portfolios/{id}/history — the §6.1 graph (server-computed series) ──
// Ranges are 1D|1W|1M|6M|1Y|5Y|MAX. 1D/1W/1M come back as sub-daily intraday
// curves; 6M/1Y/5Y are the daily snapshot series downsampled to the point
// budget; MAX is the full daily since-inception curve. `performance` is the
// server-computed time-weighted % series — the app never derives it locally.
@Serializable
data class PortfolioHistoryResponse(
    val range: String,
    /**
     * The grid the server ACTUALLY served (platform IN3, board #76 item 2) —
     * `5m`|`15m`|`30m`|`1h`|`144m`|`1d`.
     *
     * Required on the wire, but modelled nullable-with-default here for the same
     * reason every other field on this surface is: a build that meets a server
     * predating the field must still decode. `ignoreUnknownKeys = true` already
     * kept the app from throwing when this appeared; carrying it explicitly is
     * what lets the app *read* it.
     *
     * The app sends no `interval` on the request, so this is always the server's
     * `auto` resolution: **1D ⇒ `5m`** (~156 points across a market-hours day),
     * 1W ⇒ `1h`, 1M ⇒ `144m`, everything longer ⇒ `1d`. Requesting a grid finer
     * than a range can serve is COARSENED to the finest that fits rather than
     * rejected, so this echo — not the request — is the only honest answer to
     * "what am I looking at".
     */
    val interval: String? = null,
    val baseCurrency: String,
    val points: List<HistoryPointDto> = emptyList(),
    val performance: List<PerformancePointDto> = emptyList(),
    /**
     * Every held asset's own daily close series — present **only** when the
     * request carried `overlay=true` (platform issue #122), absent otherwise.
     *
     * Nullable rather than defaulted-empty on purpose: `null` means "not asked
     * for / not answered", `[]` means "asked for and the portfolio has no priced
     * asset". A caller that batches its per-asset reads through this field has to
     * be able to tell those apart, because only the second one is an answer.
     */
    val assets: List<HistoryOverlayAssetDto>? = null,
)

/**
 * One held asset's daily price series inside a `overlay=true` history response.
 *
 * Verified against the deployed `https://api.bettertrack.at/openapi.json`
 * (`PortfolioHistoryResponse.assets[]`, read 2026-08-20) and the platform
 * contract `packages/contracts/src/portfolio.ts`
 * (`portfolioHistoryOverlaySchema`). Every field below is `required` there.
 *
 * ## What the closes are, exactly
 *
 * [points] is a **daily** carry-forward close series in the asset's **native
 * [currency]** — never EUR-converted, never the portfolio's base. The server
 * expands a sparse provider series into one close per calendar day over the
 * requested window, repeating the last known close across weekends, holidays
 * and provider gaps (`packages/domain/src/holdings.ts` `dailyCloseSeries`), and
 * drops an asset entirely when nothing of its data falls inside the window.
 *
 * That is a different grid from `GET /assets/{id}/history`, which serves 1W/1M
 * as INTRADAY candles (15m/30m). Both are the server's own closes and a
 * first-to-last ratio inside one series is legitimate for either, but they are
 * not the same series, so a reader must not treat the two as interchangeable
 * inputs to the same number.
 */
@Serializable
data class HistoryOverlayAssetDto(
    val assetId: String,
    val symbol: String,
    val name: String,
    /** ISO-4217 of the closes below. NOT converted — see the class doc. */
    val currency: String,
    val points: List<HistoryOverlayPointDto> = emptyList(),
)

/** One daily close of an overlay series. Day granularity — there is no `time`. */
@Serializable
data class HistoryOverlayPointDto(
    /** Calendar date `yyyy-MM-dd`, UTC. */
    val date: String,
    val close: Double,
)

@Serializable
data class HistoryPointDto(
    /** Calendar date `yyyy-MM-dd` — always present, day granularity. */
    val date: String,
    val valueEur: Double,
    /**
     * V5: optional ISO-8601 instant for SUB-DAILY points (`1D`/`1W`/`1M` now come
     * back as dense intraday curves rather than one close per day). When present
     * it is the authoritative x-position; [date] alone would collapse every point
     * of a day onto the same coordinate and draw a vertical picket fence.
     */
    val time: String? = null,
)

@Serializable
data class PerformancePointDto(
    /** Calendar date `yyyy-MM-dd` — always present, day granularity. */
    val date: String,
    /** Server-computed performance % since range start (percent units). */
    val pct: Double,
    /** V5: optional ISO-8601 instant for sub-daily points (see [HistoryPointDto.time]). */
    val time: String? = null,
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
    /** v5 provenance — see [CashMovementDto.source]. */
    val source: String? = null,
    /** v5: chain provenance when this portfolio is a mirror copy. */
    val mirror: MirrorRowInfoDto? = null,
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
     * WHICH wallet the coupled leg uses (`transactionInputSchema`: uuid,
     * optional, meaningful only together with one of the two flags above; the
     * server defaults to the Main source when it is omitted).
     *
     * Added 2026-08-16 for the cash-linked EDIT path. A re-book has to restore
     * the transaction's original wallet, and without this field every re-booked
     * trade would silently land on Main — so a position funded from "Savings
     * account" would quietly move its money to a different wallet as a side
     * effect of the user correcting a typo in the price. That is a worse defect
     * than the one the edit path exists to fix.
     *
     * Left null by the ordinary create flow, which has no wallet picker and is
     * happy with the server's Main default.
     */
    val cashSourceId: String? = null,
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
    /**
     * One of the 10 `cash_movement_kind` values — see [at.bettertrack.app.ui.cash.CashKind],
     * which owns the label/icon/editability mapping.
     */
    val kind: String,
    /** SIGNED: inflow positive, outflow negative. Requests carry a positive magnitude. */
    val amountEur: Double,
    /** Owning source (Step 9 — every movement belongs to a source). */
    val sourceId: String? = null,
    val transactionId: String? = null,
    /** Pairs the two legs of a transfer. */
    val transferId: String? = null,
    /** The other source of a transfer leg. */
    val counterpartSourceId: String? = null,
    /** v5: set on a `dividend` row — the parent to edit instead. */
    val dividendId: String? = null,
    /** v5: set on `tax_withholding` / `tax_refund` rows. */
    val taxYear: Int? = null,
    val executedAt: String,
    val note: String? = null,
    val createdAt: String,
    /**
     * v5 provenance: "manual" | "standing-order" | "import:<slug>" | "sync:<slug>".
     * Server-assigned and never client-settable. Modelled as a plain String on
     * purpose — the platform validates it with a REGEX, not a closed enum, so a
     * new import/sync slug must not break parsing.
     */
    val source: String? = null,
    /** v5 cash-classification tag ids. Present on the list endpoint, absent on write responses. */
    val tags: List<String>? = null,
    /** v5: chain provenance when this portfolio is a mirror copy. */
    val mirror: MirrorRowInfoDto? = null,
    /** v5: original currency when the movement was booked in something other than EUR. */
    val originalCurrency: String? = null,
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
    /** Balance of the movement's OWN source after the write (v5). */
    val sourceBalanceEur: Double? = null,
    /** Portfolio-wide roll-up across all sources. */
    val balanceEur: Double,
)

/** DELETE /portfolios/{id}/cash/movements/{movementId} — 200 with balances to repaint from. */
@Serializable
data class CashDeletionResponse(
    val sourceId: String? = null,
    val sourceBalanceEur: Double? = null,
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
