package at.bettertrack.app.data.api

import at.bettertrack.app.data.api.dto.ApiErrorEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

/**
 * A typed BetterTrack API error, mapped from the platform envelope
 * `{ error: { code, message, details? } }` (spec §6.13). This is the single
 * error currency the whole app speaks — every later milestone builds on it.
 *
 * [httpStatus] `0` means a transport/network failure (no HTTP response).
 */
class BtApiError(
    val httpStatus: Int,
    val code: String,
    /** Human-readable, safe to surface (never a raw stack/string). */
    val userMessage: String,
    val details: JsonElement? = null,
    /**
     * The server's own message, when the app substituted its own copy for
     * [userMessage] (V5 mirror seam). Null when the two are the same.
     */
    val serverMessage: String? = null,
) : Exception("HTTP $httpStatus [$code] $userMessage") {

    val isNetwork: Boolean get() = httpStatus == 0
    val isUnauthorized: Boolean get() = httpStatus == 401
    val isForbidden: Boolean get() = httpStatus == 403

    /**
     * A refresh/token call that fails this way means the refresh token is
     * genuinely dead (invalid/expired/revoked) ⇒ wipe + re-login. A network
     * failure (httpStatus 0) or a 5xx must NOT log the user out.
     */
    val isAuthHardFailure: Boolean get() = httpStatus == 400 || httpStatus == 401

    val isPasswordChangeRequired: Boolean get() = code == Codes.PASSWORD_CHANGE_REQUIRED
    val isInsufficientScope: Boolean get() = code == Codes.INSUFFICIENT_SCOPE
    val isAccountDisabled: Boolean
        get() = httpStatus == 403 && (code == Codes.ACCOUNT_DISABLED || code == Codes.USER_DISABLED)

    /**
     * The server refused a portfolio DELETE because it is the account's LAST
     * ACTIVE portfolio (platform #412 → `400 { code: "LAST_ACTIVE_PORTFOLIO" }`).
     * Surfaced inline as a friendly, app-authored message (never the raw string).
     */
    val isLastActivePortfolio: Boolean get() = code == Codes.LAST_ACTIVE_PORTFOLIO

    object Codes {
        const val NETWORK = "NETWORK_ERROR"
        const val UNKNOWN = "UNKNOWN"
        const val VALIDATION_ERROR = "VALIDATION_ERROR"
        const val INSUFFICIENT_SCOPE = "INSUFFICIENT_SCOPE"
        const val PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED"
        const val ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
        const val USER_DISABLED = "USER_DISABLED"
        const val LAST_ACTIVE_PORTFOLIO = "LAST_ACTIVE_PORTFOLIO"

        // ── Idempotency keys on portfolio mutations (platform #432, PLATFORM_ASKS #9) ──
        /** 409 — a same-key mutation is still in progress server-side (transient → retry). */
        const val IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS"
        /** 409 — same key seen with a different body/endpoint (must not happen → permanent). */
        const val IDEMPOTENCY_KEY_MISMATCH = "IDEMPOTENCY_KEY_MISMATCH"
        /** 400 — the supplied key was not a UUID (regenerate once, then permanent). */
        const val IDEMPOTENCY_KEY_INVALID = "IDEMPOTENCY_KEY_INVALID"

        // ── V5 mirror seam (group-portfolio copies) + cash correction semantics ──
        /** 409 — the row moved under us (optimistic `baseSeq` lost). Refetch, then redo. */
        const val MIRROR_CONFLICT = "MIRROR_CONFLICT"
        /** 409 — the row this change targets no longer exists in the mirror. */
        const val MIRROR_ROW_DELETED = "MIRROR_ROW_DELETED"
        /** 409 — a DERIVED cash movement (trade leg, dividend, tax, transfer leg) is not editable. */
        const val CASH_MOVEMENT_NOT_EDITABLE = "CASH_MOVEMENT_NOT_EDITABLE"
        /** 503 — the mirror sync is stalled server-side. Transient: back off and retry. */
        const val MIRROR_SYNC_STALLED = "MIRROR_SYNC_STALLED"

        /** V5: the account's portfolio family is server-blind (403). */
        const val PARANOID_MODE = "PARANOID_MODE"
    }

    /** True for the three 409 mirror-seam refusals that are PERMANENT for this attempt. */
    val isMirrorSeamConflict: Boolean get() = code in MIRROR_SEAM_CONFLICT_CODES

    /** True for the transient 503 the queue must retry rather than park. */
    val isMirrorSyncStalled: Boolean get() = code == Codes.MIRROR_SYNC_STALLED

    val isParanoidMode: Boolean get() = code == Codes.PARANOID_MODE
}

/** The 409 refusals that mean "this attempt can never succeed as written". */
internal val MIRROR_SEAM_CONFLICT_CODES = setOf(
    BtApiError.Codes.MIRROR_CONFLICT,
    BtApiError.Codes.MIRROR_ROW_DELETED,
    BtApiError.Codes.CASH_MOVEMENT_NOT_EDITABLE,
)

/**
 * App-authored one-line explanations for the V5 mirror-seam refusals.
 *
 * The server messages for these are written for the web app and lean on
 * vocabulary ("mirror", "baseSeq", "derived row") that means nothing to a phone
 * user mid-edit, so the app substitutes its own copy — same discipline as the
 * queue's `MSG_*` constants in `SyncEngine`, and English for the same reason
 * (the error-code→DE-string map is a tracked backlog item, not this batch).
 *
 * Returns null for every other code, so nothing else is ever rewritten.
 */
fun mirrorSeamMessageFor(code: String): String? = when (code) {
    BtApiError.Codes.MIRROR_CONFLICT ->
        "Someone else changed this shared entry first. Refresh and try again."
    BtApiError.Codes.MIRROR_ROW_DELETED ->
        "This entry was removed from the shared portfolio, so the change can't be applied."
    BtApiError.Codes.CASH_MOVEMENT_NOT_EDITABLE ->
        "This cash entry is created automatically from another entry — edit that one instead."
    BtApiError.Codes.MIRROR_SYNC_STALLED ->
        "The shared portfolio is still syncing. This will be retried automatically."
    else -> null
}

/** Minimal success/failure result so callers never see raw exceptions. */
sealed interface BtResult<out T> {
    data class Ok<T>(val value: T) : BtResult<T>
    data class Err(val error: BtApiError) : BtResult<Nothing>
}

/** Parse the platform error envelope out of a non-2xx response body. */
fun parseApiError(json: Json, httpStatus: Int, errorBody: ResponseBody?): BtApiError {
    val raw = try {
        errorBody?.string()
    } catch (_: Exception) {
        null
    }
    if (!raw.isNullOrBlank()) {
        try {
            val env = json.decodeFromString(ApiErrorEnvelope.serializer(), raw)
            // V5: the mirror-seam refusals get app-authored copy (see
            // [mirrorSeamMessageFor]); the server's own wording is preserved on
            // [BtApiError.serverMessage] so nothing is lost for logs/diagnostics.
            return BtApiError(
                httpStatus = httpStatus,
                code = env.error.code,
                userMessage = mirrorSeamMessageFor(env.error.code) ?: env.error.message,
                details = env.error.details,
                serverMessage = env.error.message,
            )
        } catch (_: Exception) {
            // Not the expected envelope — fall through to a generic mapping.
        }
    }
    return BtApiError(httpStatus, BtApiError.Codes.UNKNOWN, "Request failed (HTTP $httpStatus).")
}

/**
 * Runs a suspend Retrofit call and maps it into a [BtResult], translating
 * transport failures and error envelopes into a [BtApiError]. Used for every
 * body-returning endpoint.
 */
suspend fun <T : Any> apiCall(json: Json, call: suspend () -> Response<T>): BtResult<T> =
    try {
        val resp = call()
        if (resp.isSuccessful) {
            val body = resp.body()
            if (body != null) {
                BtResult.Ok(body)
            } else {
                BtResult.Err(
                    BtApiError(resp.code(), BtApiError.Codes.UNKNOWN, "Empty response body."),
                )
            }
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    } catch (_: IOException) {
        BtResult.Err(
            BtApiError(
                0,
                BtApiError.Codes.NETWORK,
                "No connection. Check your network and try again.",
            ),
        )
    } catch (e: Exception) {
        BtResult.Err(BtApiError(-1, BtApiError.Codes.UNKNOWN, e.message ?: "Unexpected error."))
    }
