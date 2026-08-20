package at.bettertrack.app.data.api

import at.bettertrack.app.data.api.dto.ApiErrorEnvelope
import kotlinx.coroutines.CancellationException
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
    /**
     * The server's (or the JVM's) OWN words — English, written for the web app,
     * occasionally a raw exception string. **Never** the primary user-facing
     * line: [code] is, via `BtErrorCopy`. This is a dim secondary diagnostic,
     * surfaced only for codes the catalog does not cover (S6 P0-4).
     */
    val diagnostic: String? = null,
    val details: JsonElement? = null,
) : Exception("HTTP $httpStatus [$code] ${diagnostic.orEmpty()}") {

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

        // ── V5 cash classification (tags / budgets / rules) ──────────────────
        /** 409 — DELETE on an app-owned tag. Renaming and re-tinting it still work. */
        const val CASH_TAG_SYSTEM_PROTECTED = "CASH_TAG_SYSTEM_PROTECTED"
        /** 409 — the caller already has a tag with that name (compared case-INSENSITIVELY). */
        const val CASH_TAG_NAME_TAKEN = "CASH_TAG_NAME_TAKEN"
        /** 400 — a referenced tag id isn't one of the caller's tags. */
        const val CASH_TAG_REF_NOT_FOUND = "CASH_TAG_REF_NOT_FOUND"
        /** 409 — that (portfolio, tag, period) triple already has a budget. */
        const val CASH_BUDGET_EXISTS = "CASH_BUDGET_EXISTS"
        /** 400 — a `regex` rule pattern the server's RE2 engine won't accept. */
        const val CASH_RULE_REGEX_UNSUPPORTED = "CASH_RULE_REGEX_UNSUPPORTED"

        // ── Ledger invariants. The Drive-mode engine raises the SAME refusals
        // the server does, so `VaultOpExecutor` parks them under these codes and
        // a Drive-mode park reads identically to a server-mode one.
        /** 400 — selling more of an asset than the portfolio holds. */
        const val OVERSELL = "OVERSELL"
        /** 400 — the cash source cannot cover this entry. */
        const val INSUFFICIENT_CASH = "INSUFFICIENT_CASH"
        /** 400 — the change would overdraw the cash ledger at a later date. */
        const val CASH_LEDGER_WOULD_GO_NEGATIVE = "CASH_LEDGER_WOULD_GO_NEGATIVE"
        /** 400 — the default watchlist cannot be renamed or deleted (also enforced app-side). */
        const val WATCHLIST_DEFAULT_LOCKED = "WATCHLIST_DEFAULT_LOCKED"

        /**
         * `POST /feedback` refused: the caller already has
         * [at.bettertrack.app.data.repo.FEEDBACK_OPEN_SUBMISSION_LIMIT] submissions
         * open (platform #1400). Named as a constant because the feedback composer
         * has to compare against it BY CODE — its rate-limit branch keys off the
         * HTTP status, and whatever status this refusal arrives with must not be
         * allowed to swallow it.
         */
        const val FEEDBACK_OPEN_LIMIT = "FEEDBACK_OPEN_LIMIT"
    }

    /**
     * The server refused to delete an app-owned (system) tag. Distinguishable so
     * the UI can answer with "built-in tags can't be deleted — rename it instead"
     * rather than a generic failure; the server's own wording stays on
     * [userMessage] because it already says exactly that.
     */
    val isCashTagSystemProtected: Boolean get() = code == Codes.CASH_TAG_SYSTEM_PROTECTED

    /** The tag name collides with an existing one (case-insensitive). */
    val isCashTagNameTaken: Boolean get() = code == Codes.CASH_TAG_NAME_TAKEN

    /** That (portfolio, tag, period) already carries a budget. */
    val isCashBudgetExists: Boolean get() = code == Codes.CASH_BUDGET_EXISTS

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
            // The code IS the message: `BtErrorCopy` owns the sentence the user
            // reads, in their language. The server's own wording rides along as
            // the diagnostic so an UNMAPPED code still says something concrete.
            return BtApiError(
                httpStatus = httpStatus,
                code = env.error.code,
                diagnostic = env.error.message,
                details = env.error.details,
            )
        } catch (_: Exception) {
            // Not the expected envelope — fall through to a generic mapping.
        }
    }
    return BtApiError(httpStatus, BtErrorCopy.AppCodes.HTTP_FAILED, "HTTP $httpStatus")
}

/**
 * The single rule for "a call threw — what kind of failure is that?".
 *
 * Everything reachable from OkHttp/Retrofit lands in one of two buckets:
 *  - [IOException] (which is `UnknownHostException`, `ConnectException`,
 *    `SocketTimeoutException` and `SSLException` — i.e. *every* shape of "the
 *    server is not there") ⇒ a NETWORK error the whole app already renders;
 *  - anything else (a `SerializationException` because a maintenance proxy
 *    answered HTML, an `IllegalStateException` out of an interceptor) ⇒
 *    UNEXPECTED, carrying the JVM's words as a dim diagnostic.
 *
 * The second bucket is the one that used to escape: several call sites caught
 * only [IOException], so a non-JSON body from a dead origin propagated out of
 * the data layer and into whatever coroutine had called it.
 */
fun asBtApiError(e: Throwable): BtApiError = when (e) {
    // No diagnostic for NETWORK: the code is catalogued, so the app already owns
    // the sentence — the JVM's English one would only be dead weight.
    is IOException -> BtApiError(0, BtApiError.Codes.NETWORK)
    else -> BtApiError(-1, BtErrorCopy.AppCodes.UNEXPECTED, e.message)
}

/**
 * The catch-block form of [asBtApiError], for the call sites that need the raw
 * [Response] on success (they cache a body, delete a Room row) and so cannot go
 * through [apiCall] / [unitApiCall].
 *
 * **Re-throws [CancellationException]** — a cancelled call has no error to
 * report, and reporting one would leave the caller running inside a coroutine
 * that is already dead. Use it as the single `catch (e: Exception)` arm:
 * `} catch (e: Exception) { return transportErr(e) }`.
 */
fun transportErr(e: Exception): BtResult.Err {
    if (e is CancellationException) throw e
    return BtResult.Err(asBtApiError(e))
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
                    BtApiError(resp.code(), BtErrorCopy.AppCodes.EMPTY_RESPONSE),
                )
            }
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    } catch (e: CancellationException) {
        // A cancelled call is not a failed one. Swallowing it would leave the
        // caller running inside an already-cancelled coroutine.
        throw e
    } catch (e: Exception) {
        BtResult.Err(asBtApiError(e))
    }

/**
 * The [apiCall] of endpoints whose BODY IS IRRELEVANT — 204s and the
 * 200-with-empty-body writes. [apiCall] insists on a non-null body, so a dozen
 * call sites had each hand-rolled this shape; they now share one, which is what
 * makes "a transport failure is a `BtResult.Err`, never a throw" a property of
 * the boundary rather than of whoever wrote the call site.
 */
suspend fun unitApiCall(json: Json, call: suspend () -> Response<*>): BtResult<Unit> =
    try {
        val resp = call()
        if (resp.isSuccessful) {
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BtResult.Err(asBtApiError(e))
    }
