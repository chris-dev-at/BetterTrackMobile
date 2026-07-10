package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AlertDto
import at.bettertrack.app.data.api.dto.CreateAlertRequest
import at.bettertrack.app.data.api.dto.UpdateAlertRequest
import at.bettertrack.app.data.api.parseApiError
import kotlinx.serialization.json.Json

// ── Domain models ────────────────────────────────────────────────────────────

/**
 * The six server alert kinds (owner ask 2026-07-10). [isPercent] picks the
 * threshold unit: a target price in the asset's native currency vs a percent
 * move. The from-ref kinds measure from the server-captured [PriceAlert.refPrice]
 * (the price at creation); the day kinds measure the day's move.
 */
enum class AlertKind(val wire: String, val isPercent: Boolean) {
    PriceAbove("price_above", false),
    PriceBelow("price_below", false),
    PctUpFromRef("pct_up_from_ref", true),
    PctDownFromRef("pct_down_from_ref", true),
    PctDayUp("pct_day_up", true),
    PctDayDown("pct_day_down", true);

    companion object {
        fun fromWire(wire: String): AlertKind? = entries.firstOrNull { it.wire == wire }
    }
}

enum class AlertStatus(val wire: String) {
    Active("active"),
    Triggered("triggered"),
    Disabled("disabled");

    companion object {
        fun fromWire(wire: String): AlertStatus =
            entries.firstOrNull { it.wire == wire } ?: Active
    }
}

data class AlertAsset(
    val id: String,
    val symbol: String,
    val name: String,
    val currency: String,
    val type: String,
)

data class PriceAlert(
    val id: String,
    val kind: AlertKind,
    val threshold: Double,
    /** Server-captured price at creation — the baseline for from-ref kinds. */
    val refPrice: Double,
    val repeat: Boolean,
    val status: AlertStatus,
    val lastTriggeredAt: String?,
    val asset: AlertAsset,
)

/** Wire→domain; an unknown future kind is skipped, never a crash. */
internal fun AlertDto.toDomainOrNull(): PriceAlert? {
    val k = AlertKind.fromWire(kind) ?: return null
    return PriceAlert(
        id = id,
        kind = k,
        threshold = threshold,
        // null for the non-ref kinds; only the *_from_ref lines read it.
        refPrice = refPrice ?: 0.0,
        repeat = repeat,
        status = AlertStatus.fromWire(status),
        lastTriggeredAt = lastTriggeredAt?.takeIf { it.isNotBlank() },
        asset = AlertAsset(asset.id, asset.symbol, asset.name, asset.currency, asset.type),
    )
}

/**
 * Price alerts repository (owner ask 2026-07-10 — Workboard tab). Online-only
 * management per §7.2 (like conglomerates): the server owns evaluation, the
 * reference price and the trigger state (§7.1); the app renders and mutates.
 */
class AlertsRepository(
    private val api: BtApi,
    private val json: Json,
) {

    suspend fun list(): BtResult<List<PriceAlert>> =
        when (val r = apiCall(json) { api.alerts() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.items.mapNotNull { it.toDomainOrNull() })
            is BtResult.Err -> r
        }

    suspend fun create(
        assetId: String,
        kind: AlertKind,
        threshold: Double,
        repeat: Boolean,
    ): BtResult<PriceAlert> =
        apiCall(json) { api.createAlert(CreateAlertRequest(assetId, kind.wire, threshold, repeat)) }
            .toDomain()

    suspend fun update(id: String, threshold: Double?, repeat: Boolean?): BtResult<PriceAlert> =
        apiCall(json) { api.updateAlert(id, UpdateAlertRequest(threshold, repeat)) }.toDomain()

    suspend fun delete(id: String): BtResult<Unit> {
        val resp = try {
            api.deleteAlert(id)
        } catch (_: java.io.IOException) {
            return BtResult.Err(BtApiError(0, BtApiError.Codes.NETWORK, "No connection."))
        }
        return if (resp.isSuccessful) BtResult.Ok(Unit)
        else BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
    }

    suspend fun rearm(id: String): BtResult<PriceAlert> =
        apiCall(json) { api.rearmAlert(id) }.toDomain()

    private fun BtResult<AlertDto>.toDomain(): BtResult<PriceAlert> = when (this) {
        is BtResult.Ok -> value.toDomainOrNull()
            ?.let { BtResult.Ok(it) }
            ?: BtResult.Err(
                BtApiError(
                    200,
                    BtApiError.Codes.UNKNOWN,
                    "BetterTrack returned an alert kind this app version doesn't know.",
                ),
            )
        is BtResult.Err -> this
    }
}
