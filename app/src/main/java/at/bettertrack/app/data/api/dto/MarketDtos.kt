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

// ── POST /workboard (add to the single watchlist, §6.6) ──────────────────────
@Serializable
data class AddToWorkboardRequest(
    val assetId: String,
)
