package at.bettertrack.app.data.api

import at.bettertrack.app.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Reactive 401 handling (spec §4): when an authenticated call comes back 401,
 * refresh once (single-flighted) and retry the request with the new token.
 *
 * A 401 on an API call is never a sign-out by itself. If no refresh can be made
 * right now (offline, rate-limited, a replay pending) we return null and the call
 * fails with its 401 while the session is KEPT; only a definitive refusal of the
 * refresh itself (`400 INVALID_GRANT` / `INVALID_SCOPE`) makes [TokenManager]
 * wipe the session, which the app observes and answers with the login screen.
 */
class TokenAuthenticator(
    private val tokenManager: TokenManager,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // Endpoints where a 401 is a DOMAIN answer (e.g. a wrong PIN on
        // /auth/pin/verify), not an expired-token signal, opt out of reauth: a
        // refresh+retry would silently re-submit and double-count the attempt.
        if (response.request.header(NO_REAUTH_HEADER) != null) return null

        // Only ever retry ONCE — never loop on a persistently-401 endpoint.
        if (responseCount(response) >= 2) return null

        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            ?: return null

        val newToken = runBlocking { tokenManager.refreshOn401(failedToken) } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        /** Request header (see [at.bettertrack.app.data.api.BtApi.pinVerify]) that
         *  opts a call out of 401→refresh→retry. */
        const val NO_REAUTH_HEADER = "X-Bt-No-Reauth"
    }
}
