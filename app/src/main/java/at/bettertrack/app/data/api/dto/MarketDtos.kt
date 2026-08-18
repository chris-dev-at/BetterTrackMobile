package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the market module (Step 11, §6.5): global search, asset detail,
 * quote and price history. Field names mirror the OpenAPI contract exactly.
 * These are read-only endpoints (online-only per §7.2) — the server is the only
 * source of prices; the app only renders them.
 */

// ── GET /search?q= ───────────────────────────────────────────────────────────
@Serializable
data class SearchResponse(
    val results: List<SearchResultDto> = emptyList(),
    /** True while the server is still enriching from external providers — the
     *  client refetches once to merge the completed results ("searching…"). */
    val enriching: Boolean = false,
)

@Serializable
data class SearchResultDto(
    val id: String,
    val providerId: String? = null,
    val providerRef: String? = null,
    val symbol: String,
    val name: String,
    val exchange: String? = null,
    /** "stock" | "etf" | "index" | "fx" | "commodity" | "crypto" | "custom". */
    val type: String,
    val currency: String,
    val isCustom: Boolean = false,
)

// ── GET /assets/{id} ─────────────────────────────────────────────────────────
@Serializable
data class AssetDetailResponse(
    val asset: MarketAssetDto,
    val quote: QuoteDto? = null,
    val stale: Boolean = false,
    val asOf: String? = null,
    /** Server-converted EUR price (the app's only €-denominated price source). */
    val eurPrice: Double? = null,
)

@Serializable
data class MarketAssetDto(
    val id: String,
    val providerId: String? = null,
    val providerRef: String? = null,
    val symbol: String,
    val name: String,
    val exchange: String? = null,
    val currency: String,
    val type: String,
    val isCustom: Boolean = false,
)

@Serializable
data class QuoteDto(
    val price: Double,
    val currency: String,
    val prevClose: Double? = null,
    val dayChangePct: Double? = null,
    val asOf: String? = null,
)

// ── GET /assets/{id}/quote ───────────────────────────────────────────────────
@Serializable
data class QuoteResponse(
    val quote: QuoteDto,
    val stale: Boolean = false,
    val asOf: String? = null,
)

// ── GET /assets/quotes?ids=<uuid>,<uuid>,… ───────────────────────────────────

/**
 * Batch quotes — up to [BT_BATCH_QUOTES_MAX] assets in ONE call.
 *
 * Three parts of this contract bite if you guess at them:
 *  - `ids` is **one comma-separated string**, not repeated query params;
 *  - the query object is `.strict()` server-side, so ANY extra parameter (a
 *    cache-buster, `_t=`) is a `400`, not an ignored key;
 *  - duplicates are de-duped server-side, and every id that could not be
 *    resolved comes back in [failed] rather than as an error on the whole call.
 *
 * **This response carries no `eurPrice`.** The per-asset `GET /assets/{id}`
 * response has a server-converted euro figure; this one does not, at any level.
 * A caller that needs euros for a non-EUR quote therefore cannot get them here —
 * see [at.bettertrack.app.data.repo.eurDisplayPrice] for the identity read that
 * covers quotes already denominated in euros, and the watchlist fetch for the
 * per-row fallback that covers the rest. Never convert client-side.
 */
@Serializable
data class BatchQuotesResponse(
    val quotes: List<BatchQuoteRowDto> = emptyList(),
    /** Asset ids the server could not quote. Not an error — a per-row outcome. */
    val failed: List<String> = emptyList(),
)

@Serializable
data class BatchQuoteRowDto(
    val assetId: String,
    val quote: BatchQuoteDto? = null,
    val stale: Boolean = false,
    val asOf: String? = null,
)

/**
 * The batch response's inner quote. Same shape as [QuoteDto] plus [marketState],
 * and deliberately declared separately: these are two endpoints' contracts, and
 * silently sharing one class is how a field added to one starts being assumed of
 * the other.
 */
@Serializable
data class BatchQuoteDto(
    val price: Double,
    val currency: String,
    val prevClose: Double? = null,
    val dayChangePct: Double? = null,
    /** "open" | "closed" | "pre" | "post". */
    val marketState: String? = null,
    val asOf: String? = null,
)

/** Server cap on `ids` per batch-quote call; over it the server answers `400`. */
const val BT_BATCH_QUOTES_MAX: Int = 100

// ── GET /assets/{id}/history?range= ──────────────────────────────────────────
@Serializable
data class AssetHistoryResponse(
    /** Echoed range: 1D|1W|1M|3M|6M|1Y|5Y|MAX. */
    val range: String,
    /** Granularity the server chose: 1m|15m|30m|1d|1wk|1mo. */
    val interval: String? = null,
    val points: List<AssetHistoryPointDto> = emptyList(),
    val stale: Boolean = false,
    val asOf: String? = null,
)

@Serializable
data class AssetHistoryPointDto(
    /** ISO-8601 timestamp (intraday for 1D/1W, date for longer ranges). */
    val time: String,
    val close: Double,
)

// ── GET /assets/{id}/daily-closes (date↔price link, §6.2) ────────────────────
@Serializable
data class DailyClosesResponse(
    val points: List<AssetHistoryPointDto> = emptyList(),
    val stale: Boolean = false,
    val asOf: String? = null,
)

// ── POST /workboard (add to a watchlist, §6.6 / V3-P5 named lists) ────────────
@Serializable
data class AddToWorkboardRequest(
    val assetId: String,
    /**
     * Target a specific named list (V3-P5). When omitted the asset lands in the
     * caller's default **General** list, so every legacy add keeps working.
     */
    val watchlistId: String? = null,
)
