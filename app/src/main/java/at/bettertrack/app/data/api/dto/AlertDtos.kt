package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for price alerts (owner ask 2026-07-10; Workboard tab). Field names
 * mirror the live OpenAPI contract (`GET/POST /alerts`, `PATCH/DELETE
 * /alerts/{id}`, `POST /alerts/{id}/rearm`). The server owns evaluation and
 * the reference price (§7.1) — the app only creates/edits/deletes/re-arms.
 */

@Serializable
data class AlertsListResponse(
    val items: List<AlertDto> = emptyList(),
)

@Serializable
data class AlertDto(
    val id: String,
    /**
     * price_above | price_below | pct_up_from_ref | pct_down_from_ref |
     * pct_day_up | pct_day_down.
     */
    val kind: String,
    val threshold: Double,
    /**
     * Server-captured reference price for the `*_from_ref` kinds; **null** for
     * every other kind (contract `refPrice: number|null`). Nullable on purpose —
     * the shared Json has no `coerceInputValues`, so a non-null field would throw
     * on the null the server sends for the four non-ref kinds.
     */
    val refPrice: Double? = null,
    /** true = re-arms itself after firing; false = one-shot (manual re-arm). */
    val repeat: Boolean = false,
    /** active | triggered | disabled. */
    val status: String = "active",
    val lastTriggeredAt: String? = null,
    val asset: AlertAssetDto,
)

@Serializable
data class AlertAssetDto(
    val id: String,
    val symbol: String,
    val name: String,
    val currency: String,
    val type: String,
)

@Serializable
data class CreateAlertRequest(
    val assetId: String,
    val kind: String,
    val threshold: Double,
    val repeat: Boolean = false,
)

/** Delta PATCH — omitted (null) fields are not sent (`explicitNulls = false`). */
@Serializable
data class UpdateAlertRequest(
    val threshold: Double? = null,
    val repeat: Boolean? = null,
)
