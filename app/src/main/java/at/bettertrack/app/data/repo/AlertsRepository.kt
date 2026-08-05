package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AlertDto
import at.bettertrack.app.data.api.dto.CreateAlertRequest
import at.bettertrack.app.data.api.dto.UpdateAlertRequest
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.shows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * How many of these alerts have actually FIRED.
 *
 * A free function rather than a method so the one counting rule that now feeds
 * three surfaces — the Workbench segment badge, the Workbench tab dot and Home's
 * "Needs you" row — is stated once and unit-tested once. `Disabled` alerts are
 * deliberately not counted even if they fired before they were switched off:
 * the number answers "is something waiting for me?", not "what happened here
 * historically?".
 */
fun countTriggered(alerts: List<PriceAlert>): Int =
    alerts.count { it.status == AlertStatus.Triggered }

/**
 * Price alerts repository (owner ask 2026-07-10 — Workboard tab). Online-only
 * management per §7.2 (like conglomerates): the server owns evaluation, the
 * reference price and the trigger state (§7.1); the app renders and mutates.
 */
class AlertsRepository(
    private val api: BtApi,
    private val json: Json,
) {

    // ── The shell-visible triggered count (R-arc R1) ─────────────────────────
    //
    // The mandate moves the alerts signal OUT of the top bar and into two places
    // that are not the alerts screen: a dot on the Workbench tab and a row on
    // Home. Both need the same number, and neither can reach the Workboard's
    // nav-entry-scoped `AlertsViewModel` that computed it until now. So the
    // count is cached here — one fetch, two readers — rather than fetched twice
    // by two composables that would then be free to disagree.
    private val _triggered = MutableStateFlow(0)

    /** How many alerts have actually FIRED. Zero until [refreshTriggered] runs. */
    val triggered: StateFlow<Int> = _triggered.asStateFlow()

    /**
     * Refresh the cached [triggered] count.
     *
     * Gated on the mode INSIDE the repository rather than at each call site: a
     * Drive-only install has no alert engine (§4.5 `ALERTS_NOTIFICATIONS` is
     * ABSENT), so asking would be a guaranteed failed request on every launch,
     * and two call sites gating it independently is exactly the drift the
     * surfaces table exists to prevent.
     *
     * A failed fetch keeps the last known count rather than zeroing it: "we
     * could not reach the server" is not "your alerts stopped firing", and a dot
     * that vanishes on a flaky connection is worse than a slightly stale one.
     */
    suspend fun refreshTriggered(mode: StorageMode) {
        if (!mode.shows(BtSurface.ALERTS_NOTIFICATIONS)) {
            _triggered.value = 0
            return
        }
        when (val r = list()) {
            is BtResult.Ok -> _triggered.value = countTriggered(r.value)
            is BtResult.Err -> Unit
        }
    }

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
                BtApiError(200, BtErrorCopy.AppCodes.UNKNOWN_ALERT_KIND),
            )
        is BtResult.Err -> this
    }
}
