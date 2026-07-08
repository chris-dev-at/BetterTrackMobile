package at.bettertrack.app.data.applock

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.PinVerifyRequest
import kotlinx.serialization.json.Json

/**
 * What the setup chooser can learn about the account's web PIN from `/auth/me`.
 *  - [Available] — `/auth/me` was readable with the bearer; [pinEnabled] tells
 *    whether a web PIN exists (so the option can be shown only when it does).
 *  - [Forbidden] — the bearer can't read `/auth/me` (session-only). We can't know
 *    `pinEnabled`, so the verify call becomes the gate instead.
 *  - [Offline]   — no connection; can't check right now.
 *  - [Error]     — any other failure.
 */
sealed interface AccountPinStatus {
    data class Available(
        val pinEnabled: Boolean,
        val username: String,
        val email: String,
    ) : AccountPinStatus

    data object Forbidden : AccountPinStatus
    data object Offline : AccountPinStatus
    data class Error(val httpStatus: Int, val code: String) : AccountPinStatus
}

/**
 * The network seam for the "use my BetterTrack account PIN" app-lock option
 * (spec §5). It REUSES/verifies the user's existing web PIN — it NEVER sets or
 * changes it. Two calls, both on the authenticated [BtApi] (OAuth bearer):
 *  - [fetchAccountPin]     reads `/auth/me` for `pinEnabled` (+ identity),
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
    /** Read `/auth/me` and classify what it tells us about the account's web PIN. */
    suspend fun fetchAccountPin(): AccountPinStatus {
        val result = apiCall(json) { api.me() }
        val status: AccountPinStatus = when (result) {
            is BtResult.Ok -> AccountPinStatus.Available(
                pinEnabled = result.value.pinEnabled,
                username = result.value.username,
                email = result.value.email,
            )

            is BtResult.Err -> when (meAccessFor(result.error.httpStatus)) {
                MeAccess.Forbidden -> AccountPinStatus.Forbidden
                MeAccess.Offline -> AccountPinStatus.Offline
                MeAccess.Ok, MeAccess.Error ->
                    AccountPinStatus.Error(result.error.httpStatus, result.error.code)
            }
        }
        val httpStatus = (result as? BtResult.Err)?.error?.httpStatus ?: 200
        val code = (result as? BtResult.Err)?.error?.code ?: "OK"
        val pinEnabled = (status as? AccountPinStatus.Available)?.pinEnabled
        Log.i(TAG, "auth/me -> HTTP $httpStatus [$code] pinEnabled=$pinEnabled")
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
