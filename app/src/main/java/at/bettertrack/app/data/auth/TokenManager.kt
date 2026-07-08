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
) {
    private val refreshMutex = Mutex()

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
        refreshMutex.withLock {
            val latest = store.loadTokens() ?: return
            // Another coroutine may have refreshed while we waited for the lock.
            if (!latest.isExpiringWithin(REFRESH_SKEW_MS)) return
            doRefresh(latest.refreshToken)
        }
    }

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
                tokens
            }

            is BtResult.Err -> {
                if (result.error.isAuthHardFailure) {
                    // Refresh token is dead ⇒ force re-login.
                    Log.i(TAG, "Refresh rejected (${result.error.code}); wiping session.")
                    store.wipeAll()
                    _sessionInvalidated.tryEmit(Unit)
                } else {
                    // Transient (network / 5xx): keep the session, surface the failure.
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
    }
}
