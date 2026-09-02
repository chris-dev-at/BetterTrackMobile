package at.bettertrack.app.data.auth

import android.util.Log
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import at.bettertrack.app.data.api.parseApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * Owns the token lifecycle (spec §4): code exchange, storage, proactive +
 * reactive refresh, and single-flight so a burst of parallel calls triggers at
 * most ONE refresh (no thundering herd). Emits [sessionInvalidated] when a
 * refresh is genuinely rejected so the app can drop to the login screen.
 *
 * Depends only on the bare [TokenApi] + [SessionStore] — never on the
 * authenticated client — so refresh can never recurse through the 401 machinery.
 *
 * ## The one rule this class exists to keep
 *
 * **Only a definitive server verdict may end a session.** Everything else —
 * a dead socket, a 5xx, a rate limit, an unreadable Keystore, a 400 the server
 * sent for a reason that has nothing to do with the token — keeps the session
 * and surfaces as [sessionDegraded]. The owner was being thrown back to the
 * login screen with no explanation; the classification below, [SecureStore]'s
 * non-destructive reads and the [SignOutLedger] are the three halves of that fix.
 */
class TokenManager(
    private val tokenApi: TokenApi,
    private val store: SessionStore,
    private val json: Json,
    private val clientId: String,
    private val redirectUri: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val refreshMutex = Mutex()

    /**
     * When the last refresh failed for a TRANSPORT reason (`httpStatus == 0`), or
     * 0 if the last one succeeded / failed for a server reason.
     *
     * This exists because of what an unreachable origin does to the PROACTIVE
     * path. Once the access token is past its skew window, every single
     * authenticated request asks [proactiveRefreshIfNeeded] first — and with the
     * server gone that refresh burns a full connect timeout before failing, while
     * holding [refreshMutex] and (via `AuthInterceptor.runBlocking`) an OkHttp
     * dispatcher thread. A screen that fires eight calls then serialises eight
     * doomed refreshes ahead of eight doomed requests: minutes of blocked threads
     * for a result that was known after the first one.
     *
     * So a transport failure suppresses the proactive attempt for
     * [NETWORK_BACKOFF_MS]. Nothing is lost by skipping it: the request goes out
     * with the token we have, and when the server returns, its 401 drives the
     * REACTIVE refresh in [refreshOn401] — which is deliberately NOT gated here,
     * because a 401 is proof the server is reachable.
     */
    @Volatile
    private var networkFailureAtMs: Long = 0L

    /**
     * True when the PREVIOUS refresh went out and its answer never came back.
     *
     * Refresh rotates: the server invalidates the presented refresh token the
     * moment it issues the successor. If the response is lost in flight the
     * server has rotated and the phone has not, so the token still on disk is
     * already spent — and presenting it next time looks to the server exactly
     * like a stolen token being replayed. Whether that costs the whole grant is
     * the server's rule, not ours; what this flag buys is that the ledger can
     * say **which** of the two stories happened, instead of the owner and the
     * next engineer guessing.
     */
    @Volatile
    private var lastRefreshLostInFlight: Boolean = false

    /**
     * The refresh token whose fate is unknown, and when it was last offered.
     *
     * See [atRiskReplaySuppressed] — this pair is the whole guard against the
     * app replaying a token the server has already consumed.
     */
    @Volatile
    private var atRiskRefreshToken: String? = null

    @Volatile
    private var atRiskLastAttemptMs: Long = 0L

    /**
     * `replay = 1` is load-bearing. `AuthRepository` subscribes from a coroutine
     * launched in its `init`, so there is a window at cold start where nothing is
     * collecting yet — and a refresh rejected inside that window used to be
     * dropped on the floor, leaving the UI claiming a session whose credentials
     * had just been wiped. Exactly one subscriber exists for the life of the
     * process, so replaying the last verdict to it costs nothing.
     */
    private val _sessionInvalidated =
        MutableSharedFlow<SessionInvalidation>(replay = 1, extraBufferCapacity = 1)
    val sessionInvalidated: SharedFlow<SessionInvalidation> = _sessionInvalidated

    private val _sessionDegraded = MutableStateFlow(false)

    /**
     * The session is intact but the app currently cannot renew it — a dead
     * network, a 5xx, a rate limit. This is the state that used to be a logout.
     * Cleared by the first successful refresh.
     */
    val sessionDegraded: StateFlow<Boolean> = _sessionDegraded.asStateFlow()

    fun currentAccessToken(): String? = store.loadTokens()?.accessToken

    /** True only when a USABLE pair is on disk. */
    fun hasTokens(): Boolean = store.readTokens() is TokenRead.Present

    /**
     * What storage actually says, including "cannot tell right now". The startup
     * router needs the difference; nothing else does.
     */
    fun sessionPresence(): TokenRead = store.readTokens()

    /** Exchange an authorization code (+ our PKCE verifier) for tokens. */
    suspend fun exchange(code: String, codeVerifier: String): BtResult<AuthTokens> {
        val result = apiCall(json) {
            tokenApi.exchange(
                TokenExchangeRequest(
                    code = code,
                    redirectUri = redirectUri,
                    clientId = clientId,
                    codeVerifier = codeVerifier,
                ),
            )
        }
        return when (result) {
            is BtResult.Ok -> {
                val tokens = result.value.toAuthTokens()
                store.saveTokens(tokens)
                lastRefreshLostInFlight = false
                networkFailureAtMs = 0L
                _sessionDegraded.value = false
                BtResult.Ok(tokens)
            }

            is BtResult.Err -> result
        }
    }

    /** Refresh proactively if the access token is within the skew window. */
    suspend fun proactiveRefreshIfNeeded() {
        val current = (store.readTokens() as? TokenRead.Present)?.tokens ?: return
        if (!current.isExpiringWithin(REFRESH_SKEW_MS)) return
        // The server was unreachable moments ago — do not pay another connect
        // timeout per request to re-learn that. See [networkFailureAtMs].
        if (inNetworkBackoff()) return
        refreshMutex.withLock {
            val latest = (store.readTokens() as? TokenRead.Present)?.tokens ?: return
            // Another coroutine may have refreshed while we waited for the lock.
            if (!latest.isExpiringWithin(REFRESH_SKEW_MS)) return
            // Re-check under the lock: the caller we queued behind may have just
            // discovered the server is gone, and this is where a burst collapses
            // from N doomed refreshes into one.
            if (inNetworkBackoff()) return
            doRefresh(latest.refreshToken)
        }
    }

    /**
     * User-visible retry behind the "reconnecting" banner: refresh now, ignoring
     * the transport backoff (the person tapping has just told us to try again).
     *
     * It does NOT override the at-risk quarantine. Impatience is not evidence:
     * if the last refresh's answer was lost, sending that token again is the one
     * move that can revoke the grant on every device the account has, and a tap
     * cannot make it safer. The banner simply stays up until the cool-off ends.
     *
     * @return true if the session is healthy afterwards.
     */
    suspend fun retryRefreshNow(): Boolean = refreshMutex.withLock {
        val latest = (store.readTokens() as? TokenRead.Present)?.tokens ?: return false
        networkFailureAtMs = 0L
        doRefresh(latest.refreshToken) != null
    }

    /** True while a recent transport failure suppresses the proactive refresh. */
    private fun inNetworkBackoff(): Boolean =
        proactiveRefreshSuppressed(networkFailureAtMs, nowMs(), NETWORK_BACKOFF_MS)

    /**
     * Reactive refresh after a 401. Returns the new access token, or null if the
     * refresh failed (session wiped) or someone else already rotated the token
     * that the caller had — in which case we hand back the current one to retry.
     */
    suspend fun refreshOn401(failedAccessToken: String): String? =
        refreshMutex.withLock {
            val current = (store.readTokens() as? TokenRead.Present)?.tokens ?: return null
            if (current.accessToken != failedAccessToken) {
                // Already refreshed by a concurrent caller — retry with the fresh token.
                return current.accessToken
            }
            doRefresh(current.refreshToken)?.accessToken
        }

    /** Local wipe of tokens + any in-flight PKCE (does not touch cached user). */
    fun clear() {
        store.clearTokens()
        store.clearPending()
    }

    private suspend fun doRefresh(presentedRefreshToken: String): AuthTokens? {
        if (
            atRiskReplaySuppressed(
                atRiskToken = atRiskRefreshToken,
                presentedToken = presentedRefreshToken,
                lastAttemptMs = atRiskLastAttemptMs,
                nowMs = nowMs(),
                coolOffMs = AT_RISK_COOLOFF_MS,
            )
        ) {
            Log.w(TAG, "Refresh token is at risk (a previous answer was lost); not replaying it yet.")
            _sessionDegraded.value = true
            return null
        }
        if (presentedRefreshToken == atRiskRefreshToken) atRiskLastAttemptMs = nowMs()

        return when (val attempt = postRefresh(presentedRefreshToken)) {
            is RefreshAttempt.Rotated -> {
                // Persisted SYNCHRONOUSLY before any caller is allowed to proceed:
                // the pair we were just handed is the only live one, and a caller
                // that raced ahead of the write could re-present the dead token.
                if (!store.saveTokens(attempt.tokens)) {
                    Log.w(TAG, "Rotated tokens could not be persisted.")
                }
                networkFailureAtMs = 0L
                lastRefreshLostInFlight = false
                atRiskRefreshToken = null
                _sessionDegraded.value = false
                attempt.tokens
            }

            is RefreshAttempt.Refused -> onRefused(attempt.error, presentedRefreshToken)

            is RefreshAttempt.Transport -> {
                networkFailureAtMs = nowMs()
                lastRefreshLostInFlight = attempt.deliveryUnknown
                if (attempt.deliveryUnknown) {
                    // The bytes went out and nothing came back: assume the server
                    // rotated. From here the token is quarantined.
                    atRiskRefreshToken = presentedRefreshToken
                    atRiskLastAttemptMs = nowMs()
                }
                _sessionDegraded.value = true
                Log.w(
                    TAG,
                    "Refresh failed transiently (${attempt.what}, " +
                        "delivered=${if (attempt.deliveryUnknown) "unknown" else "no"}); session kept.",
                )
                null
            }
        }
    }

    private fun onRefused(error: BtApiError, presentedRefreshToken: String): AuthTokens? {
        if (!refreshRefusalIsDefinitive(error.httpStatus, error.code)) {
            // A 5xx, a 429, a 403, a 400 the server sent for a reason that is not
            // "this token is dead". None of those is proof of anything about the
            // session, so it survives.
            Log.w(TAG, "Refresh refused transiently: HTTP ${error.httpStatus} [${error.code}]; session kept.")
            _sessionDegraded.value = true
            return null
        }

        // Reuse-race safety. A definitive refusal for a token that is no longer
        // the one on disk means somebody else already rotated past it — the
        // session is fine and the refusal is stale.
        when (val latest = store.readTokens()) {
            is TokenRead.Present ->
                if (latest.tokens.refreshToken != presentedRefreshToken) {
                    Log.i(TAG, "Refusal was for a superseded refresh token; keeping the rotated session.")
                    return latest.tokens
                }

            is TokenRead.Unavailable -> {
                // We cannot even read what we are about to destroy. Never wipe on
                // that: the bytes may be perfectly good and merely unreadable now.
                Log.w(TAG, "Definitive refusal but storage is unreadable; session kept.")
                _sessionDegraded.value = true
                return null
            }

            TokenRead.None -> Unit // already gone; fall through and emit once
        }

        val reason = if (lastRefreshLostInFlight) {
            SignOutReason.REFRESH_REJECTED_AFTER_LOST_RESPONSE
        } else {
            SignOutReason.REFRESH_REJECTED
        }
        Log.w(TAG, "Refresh rejected definitively (${error.code}); wiping session. reason=$reason")
        networkFailureAtMs = 0L
        lastRefreshLostInFlight = false
        atRiskRefreshToken = null
        _sessionDegraded.value = false
        store.wipeAll()
        _sessionInvalidated.tryEmit(
            SessionInvalidation(
                reason = reason,
                httpStatus = error.httpStatus,
                errorCode = error.code,
                caller = "TokenManager.doRefresh",
            ),
        )
        return null
    }

    /**
     * The refresh POST, kept out of [apiCall] on purpose: this is the one call
     * site that needs to know WHICH transport failure happened, because
     * "the connection was never made" and "we sent it and never heard back" have
     * opposite consequences for a rotating refresh token.
     */
    private suspend fun postRefresh(refreshToken: String): RefreshAttempt =
        try {
            val resp = tokenApi.refresh(
                TokenRefreshRequest(refreshToken = refreshToken, clientId = clientId),
            )
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                RefreshAttempt.Rotated(body.toAuthTokens())
            } else if (resp.isSuccessful) {
                RefreshAttempt.Refused(BtApiError(resp.code(), "EMPTY_RESPONSE"))
            } else {
                RefreshAttempt.Refused(parseApiError(json, resp.code(), resp.errorBody()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RefreshAttempt.Transport(
                deliveryUnknown = requestMayHaveBeenDelivered(e),
                what = e.javaClass.simpleName,
            )
        }

    private sealed interface RefreshAttempt {
        data class Rotated(val tokens: AuthTokens) : RefreshAttempt
        data class Refused(val error: BtApiError) : RefreshAttempt
        data class Transport(val deliveryUnknown: Boolean, val what: String) : RefreshAttempt
    }

    private companion object {
        const val TAG = "BtTokenManager"

        /**
         * Refresh this long before expiry.
         *
         * Raised from one minute to five (2026-09-02). One minute meant the
         * rotation happened with essentially no cover: if its answer was lost,
         * the access token expired seconds later and every queued call fell
         * through to the 401 path at once. Five minutes of still-valid access
         * token is the headroom the at-risk quarantine needs — the app keeps
         * working while it waits, instead of stampeding the refresh endpoint
         * with a token that may already be spent.
         *
         * Access-token TTL is 3600 s (`OAUTH_ACCESS_TOKEN_TTL_SECONDS`,
         * `packages/contracts/src/oauth.ts:29-34`), so this is 8 % of the life
         * of the token and does not measurably raise the rotation rate.
         */
        const val REFRESH_SKEW_MS = 300_000L

        /**
         * How long a refresh token whose answer was lost is left alone before it
         * is offered again. Long enough that a burst of queued callers cannot
         * turn one lost response into a replay; short enough that a session
         * recovers within one screen's patience if the token was in fact fine.
         */
        const val AT_RISK_COOLOFF_MS = 10 * 60_000L

        /**
         * How long a transport failure suppresses the PROACTIVE refresh. Short
         * enough that a returning server is picked up within a screen's worth of
         * interaction; long enough that one dead-origin burst costs one timeout
         * instead of one per request.
         */
        const val NETWORK_BACKOFF_MS = 30_000L
    }
}

/** Why the session ended, carried to whoever writes it into the ledger. */
data class SessionInvalidation(
    val reason: SignOutReason,
    val httpStatus: Int?,
    val errorCode: String?,
    val caller: String,
)

/**
 * Could the failed request have reached the server?
 *
 * A name that did not resolve or a port that refused the connection means the
 * bytes never left; anything else (a read timeout, a reset mid-response, a TLS
 * failure after the handshake) means the server may well have processed it. For
 * a rotating refresh token that distinction is the difference between "nothing
 * happened" and "your refresh token is already spent".
 */
internal fun requestMayHaveBeenDelivered(e: Throwable): Boolean = when (e) {
    is UnknownHostException, is ConnectException -> false
    is IOException -> true
    else -> true
}

/**
 * The single rule for *"may this refusal end the session?"*.
 *
 * `BtApiError.isAuthHardFailure` — which this replaces on the refresh path —
 * said yes to **every** 400 and 401. Both halves of that are wrong against the
 * platform this app talks to.
 *
 * **Every** answer that actually means "your refresh token is dead" is
 * `400` with `code = "INVALID_GRANT"` — unknown token, wrong client, expired,
 * revoked grant, replayed token, suspended account, all five of them:
 *
 * ```
 * _source: platform repo, apps/api/src/services/oauth/oauthService.ts @ origin/main
 *   :912-915  badRequest('Invalid refresh token.',            'INVALID_GRANT')
 *   :916-918  badRequest('This authorization has been revoked.','INVALID_GRANT')
 *   :919-930  badRequest('Refresh token has already been used.','INVALID_GRANT')
 *   :931-933  badRequest('Refresh token has expired.',         'INVALID_GRANT')
 *   :946-954  badRequest('Refresh token has already been used.','INVALID_GRANT')
 * ```
 *
 * Everything else the endpoint can answer is about the REQUEST, not the session:
 *  - `400 VALIDATION_ERROR` — the body schema is `.strict()`, so one stray field
 *    or a form-encoded body is a 400 with a perfectly healthy token;
 *  - `400 INVALID_CLIENT` — a wrong/rotated `client_id`, i.e. a build problem;
 *  - `429 RATE_LIMITED` — the general limiter is keyed by **IP**, 60 requests per
 *    10 s, shared with everyone behind the same NAT;
 *  - `500 INTERNAL`, and every transport failure.
 *
 * And a **401 is never a token verdict here**: the token endpoint is
 * unauthenticated, so the only thing that produces one is the bearer middleware
 * mounted above `/api/v1` rejecting an `Authorization` header that should never
 * have been attached to a refresh (`bearerAuth.ts:371-413`, `401
 * API_KEY_INVALID`). That is a client bug to fix, not a session to destroy —
 * this app's refresh rides a deliberately bare OkHttp client with no auth
 * interceptor (`AppGraph.tokenClient`), and `ForcedSignOutTest` pins that no
 * `Authorization` header goes out on the wire.
 */
internal fun refreshRefusalIsDefinitive(httpStatus: Int, code: String): Boolean =
    httpStatus == 400 && code.uppercase() in DEFINITIVE_REFRESH_REFUSAL_CODES

/**
 * The 400 error codes that genuinely mean "this refresh token is dead".
 *
 * Exactly one, and deliberately so — see [refreshRefusalIsDefinitive] for the
 * per-line provenance. An unrecognised refusal keeps the session and lands in
 * the ledger, which is the failure mode we want: a visible diagnostic row
 * rather than an unexplained logout.
 */
internal val DEFINITIVE_REFRESH_REFUSAL_CODES: Set<String> = setOf("INVALID_GRANT")

/**
 * Whether an at-risk refresh token must NOT be presented right now.
 *
 * The rule that stops the app from killing its own grant. When a refresh goes
 * out and its answer never arrives, the server has very likely rotated: the
 * token still on disk is consumed, and presenting a consumed token is, to this
 * platform, an attack —
 *
 * ```
 * _source: platform repo @ origin/main
 *   oauthService.ts:919-930   consumed token   → revokeGrant(...) + INVALID_GRANT
 *   oauthRepository.ts:646-700 lost the atomic consume → revoke the whole grant
 * ```
 *
 * — and the grant is the ONLY one this user has for this client, so revoking it
 * signs out every install at once. There is no grace window, not one second.
 *
 * Before this guard, one lost response reliably produced a replay within
 * milliseconds: the refresh mutex released, the next queued 401 saw an unchanged
 * access token, and re-presented the very same refresh token. So the token is
 * quarantined and offered again at most once per [coolOffMs]; in between, the
 * still-valid access token keeps working and the UI shows "reconnecting".
 */
internal fun atRiskReplaySuppressed(
    atRiskToken: String?,
    presentedToken: String,
    lastAttemptMs: Long,
    nowMs: Long,
    coolOffMs: Long,
): Boolean {
    if (atRiskToken == null || atRiskToken != presentedToken) return false
    val elapsed = nowMs - lastAttemptMs
    // A backwards clock re-arms rather than stranding the session forever.
    return elapsed in 0 until coolOffMs
}

/**
 * Whether the PROACTIVE token refresh should be skipped right now.
 *
 * Pure, so the rule that keeps an unreachable origin from multiplying one
 * connect timeout by every in-flight request is stated once and asserted
 * without a Keystore, a Context or a socket.
 *
 * @param networkFailureAtMs when the last refresh failed for a TRANSPORT reason
 *   (`0` = never, or the last attempt reached the server).
 * @return true while the failure is still inside [backoffMs].
 */
internal fun proactiveRefreshSuppressed(
    networkFailureAtMs: Long,
    nowMs: Long,
    backoffMs: Long,
): Boolean {
    if (networkFailureAtMs == 0L) return false
    val elapsed = nowMs - networkFailureAtMs
    // A NEGATIVE elapsed time means the wall clock moved backwards (an NTP
    // correction, a user setting the date). Treating that as "still inside the
    // window" would strand the refresh until the clock caught up, so it re-arms.
    return elapsed in 0 until backoffMs
}
