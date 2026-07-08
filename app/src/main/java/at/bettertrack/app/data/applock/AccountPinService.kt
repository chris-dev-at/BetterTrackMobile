package at.bettertrack.app.data.applock

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.PinVerifyRequest
import kotlinx.serialization.json.Json

/**
 * The network seam for the "use my BetterTrack account PIN" app-lock option
 * (spec §5). It REUSES/verifies the user's existing web PIN — it NEVER sets or
 * changes it. Two calls, both on the authenticated [BtApi] (OAuth bearer):
 *  - [fetchPinStatus]       reads `GET /auth/pin/status` for `pinSet` — the
 *    dedicated, lightweight availability gate (offer the option only when the
 *    account actually has a web PIN),
 *  - [verifyBetterTrackPin] POSTs `/auth/pin/verify` with the entered PIN.
 *
 * Observability rule: every call logs ONLY the HTTP status, the platform error
 * code, and the derived outcome — never the PIN and never the token. The entered
 * PIN travels in the request body over TLS and is never persisted server-side by
 * the app; on a match only a local Keystore-HMAC hash is stored (see
 * [AppLockController.setupPin]).
 */
class AccountPinService(
    private val api: BtApi,
    private val json: Json,
) {
    /** Read `GET /auth/pin/status` and classify whether the account has a web PIN. */
    suspend fun fetchPinStatus(): AccountPinStatus {
        val result = apiCall(json) { api.pinStatus() }
        val status: AccountPinStatus = when (result) {
            is BtResult.Ok -> AccountPinStatus.Known(pinSet = result.value.pinSet)

            is BtResult.Err -> when (pinGateAccessFor(result.error.httpStatus)) {
                PinGateAccess.Forbidden -> AccountPinStatus.Forbidden
                PinGateAccess.Offline -> AccountPinStatus.Offline
                PinGateAccess.Ok, PinGateAccess.Error ->
                    AccountPinStatus.Error(result.error.httpStatus, result.error.code)
            }
        }
        val httpStatus = (result as? BtResult.Err)?.error?.httpStatus ?: 200
        val code = (result as? BtResult.Err)?.error?.code ?: "OK"
        val pinSet = (status as? AccountPinStatus.Known)?.pinSet
        Log.i(TAG, "auth/pin/status -> HTTP $httpStatus [$code] pinSet=$pinSet")
        return status
    }

    /**
     * POST the entered PIN to `/auth/pin/verify` and classify the result. The PIN
     * is passed through untouched to the request body and never logged.
     */
    suspend fun verifyBetterTrackPin(pin: String): BtPinVerifyOutcome {
        val result = apiCall(json) { api.pinVerify(PinVerifyRequest(pin = pin)) }
        val httpStatus = when (result) {
            is BtResult.Ok -> 200
            is BtResult.Err -> result.error.httpStatus
        }
        val code = (result as? BtResult.Err)?.error?.code ?: "OK"
        val outcome = pinVerifyOutcomeFor(httpStatus)
        // Deliberately no PIN and no token in this line — only the classification.
        Log.i(TAG, "auth/pin/verify -> HTTP $httpStatus [$code] => $outcome")
        return outcome
    }

    private companion object {
        const val TAG = "BtAccountPin"
    }
}
