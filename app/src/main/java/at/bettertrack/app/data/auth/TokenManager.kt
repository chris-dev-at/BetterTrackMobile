package at.bettertrack.app.data.auth

import android.util.Log
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Owns the token lifecycle (spec §4): code exchange, storage, proactive +
 * reactive refresh, and single-flight so a burst of parallel calls triggers at
 * most ONE refresh (no thundering herd). Emits [sessionInvalidated] when a
 * refresh is genuinely rejected so the app can drop to the login screen.
 *
 * Depends only on the bare [TokenApi] + [SecureStore] — never on the
 * authenticated client — so refresh can never recurse through the 401 machinery.
 */
class TokenManager(
    private val tokenApi: TokenApi,
    private val store: SecureStore,
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

    private val _sessionInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionInvalidated: SharedFlow<Unit> = _sessionInvalidated

    fun currentAccessToken(): String? = store.loadTokens()?.accessToken

    fun hasTokens(): Boolean = store.loadTokens() != null

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
                BtResult.Ok(tokens)
            }

            is BtResult.Err -> result
        }
    }

    /** Refresh proactively if the access token is within the skew window. */
    suspend fun proactiveRefreshIfNeeded() {
        val current = store.loadTokens() ?: return
        if (!current.isExpiringWithin(REFRESH_SKEW_MS)) return
        // The server was unreachable moments ago — do not pay another connect
        // timeout per request to re-learn that. See [networkFailureAtMs].
        if (inNetworkBackoff()) return
        refreshMutex.withLock {
            val latest = store.loadTokens() ?: return
            // Another coroutine may have refreshed while we waited for the lock.
            if (!latest.isExpiringWithin(REFRESH_SKEW_MS)) return
            // Re-check under the lock: the caller we queued behind may have just
            // discovered the server is gone, and this is where a burst collapses
            // from N doomed refreshes into one.
            if (inNetworkBackoff()) return
            doRefresh(latest.refreshToken)
        }
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
            val current = store.loadTokens() ?: return null
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

    private suspend fun doRefresh(refreshToken: String): AuthTokens? {
        val result = apiCall(json) {
            tokenApi.refresh(TokenRefreshRequest(refreshToken = refreshToken, clientId = clientId))
        }
        return when (result) {
            is BtResult.Ok -> {
                // Refresh ROTATES the refresh token — persist the new pair.
                val tokens = result.value.toAuthTokens()
                store.saveTokens(tokens)
                networkFailureAtMs = 0L
                tokens
            }

            is BtResult.Err -> {
                if (result.error.isAuthHardFailure) {
                    // Refresh token is dead ⇒ force re-login. Reachable ONLY on a
                    // 400/401, i.e. only when the server answered: a transport
                    // failure carries httpStatus 0 and can never land here, which
                    // is what stops a maintenance window from logging everyone out.
                    Log.i(TAG, "Refresh rejected (${result.error.code}); wiping session.")
                    networkFailureAtMs = 0L
                    store.wipeAll()
                    _sessionInvalidated.tryEmit(Unit)
                } else {
                    // Transient (network / 5xx): keep the session, surface the failure.
                    if (result.error.isNetwork) networkFailureAtMs = nowMs()
                    Log.w(TAG, "Refresh failed transiently: ${result.error.message}")
                }
                null
            }
        }
    }

    private companion object {
        const val TAG = "BtTokenManager"
        /** Refresh this long before expiry to avoid racing a live request. */
        const val REFRESH_SKEW_MS = 60_000L

        /**
         * How long a transport failure suppresses the PROACTIVE refresh. Short
         * enough that a returning server is picked up within a screen's worth of
         * interaction; long enough that one dead-origin burst costs one timeout
         * instead of one per request.
         */
        const val NETWORK_BACKOFF_MS = 30_000L
    }
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
