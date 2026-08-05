package at.bettertrack.app.data.standingorders

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.StandingOrderDto
import at.bettertrack.app.data.api.unitApiCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import retrofit2.Response

/**
 * The v5 **standing orders** repository — scheduled recurring buys and cash
 * movements, managed here and executed by a daily server job.
 *
 * **Network-only, no Room.** Nothing about an order is renderable from stale
 * data: `nextRunDate` is computed per request against the server's calendar day,
 * and `status` / `lastRunAt` change without the phone doing anything. A cached
 * order would therefore show a next-run date that has already passed, which is
 * worse than an offline state. The screens read this live and show the standard
 * offline treatment when the call fails.
 *
 * Only the LIST response is enveloped; every single-order call answers the bare
 * object (verified in the platform monorepo — see
 * [at.bettertrack.app.data.api.dto.StandingOrderDto]).
 *
 * Errors use the app's single currency, [BtResult] / [BtApiError]. Two refusals
 * worth branching on: 400 `STANDING_ORDER_ASSET_NOT_FOUND` (the chosen asset is
 * not visible to the caller) and 400 `STANDING_ORDER_END_BEFORE_START` (which
 * [validateStandingOrder] normally catches first, except when `startDate` was
 * omitted and the server substituted its own today).
 */
class StandingOrderRepository(
    private val api: BtApi,
    private val json: Json,
) {

    /** The caller's orders, optionally narrowed to one portfolio. */
    suspend fun list(portfolioId: String? = null): BtResult<List<StandingOrderDto>> =
        when (val r = apiCall(json) { api.standingOrders(portfolioId) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.orders)
            is BtResult.Err -> r
        }

    suspend fun get(id: String): BtResult<StandingOrderDto> =
        apiCall(json) { api.standingOrder(id) }

    /**
     * Create an order from a validated [draft].
     *
     * Call [validateStandingOrder] first — this does NOT validate, it only
     * normalises the two keys the server rejects outright (`assetId` on a cash
     * kind, `anchorDay` on a daily cadence) so a mismatched draft cannot turn
     * into a `.strict()` 400 by accident. See [toCreateRequest].
     */
    suspend fun create(draft: StandingOrderDraft): BtResult<StandingOrderDto> =
        apiCall(json) { api.createStandingOrder(draft.toCreateRequest()) }

    /**
     * Edit amount / label / end date — the only three mutable fields.
     *
     * Pass `clearLabel = true` / `clearEndDate = true` to null a field out; that
     * is a different wire fact from leaving it alone. Returns
     * `BtResult.Ok(current)` **without a request** when nothing changed, because
     * the server's `.strict()` schema 400s an empty body — hence the [id] round
     * trip via [get] so the caller still receives a real order.
     */
    suspend fun update(
        id: String,
        amount: Double? = null,
        label: String? = null,
        clearLabel: Boolean = false,
        endDate: String? = null,
        clearEndDate: Boolean = false,
    ): BtResult<StandingOrderDto> {
        val patch: JsonObject = buildStandingOrderPatch(
            amount = amount,
            label = label,
            clearLabel = clearLabel,
            endDate = endDate,
            clearEndDate = clearEndDate,
        ) ?: return get(id)
        return apiCall(json) { api.updateStandingOrder(id, patch) }
    }

    /** Stop firing. History is kept; resuming never back-fills the paused periods. */
    suspend fun pause(id: String): BtResult<StandingOrderDto> =
        apiCall(json) { api.pauseStandingOrder(id) }

    /** Resume from the current period onward. */
    suspend fun resume(id: String): BtResult<StandingOrderDto> =
        apiCall(json) { api.resumeStandingOrder(id) }

    /** Delete (204) — the order's run history cascades server-side. */
    suspend fun delete(id: String): BtResult<Unit> = unitCall { api.deleteStandingOrder(id) }

    /** A 204 endpoint; [apiCall] insists on a non-null body, so use the bodyless twin. */
    private suspend fun unitCall(call: suspend () -> Response<Unit>): BtResult<Unit> =
        unitApiCall(json, call)
}
