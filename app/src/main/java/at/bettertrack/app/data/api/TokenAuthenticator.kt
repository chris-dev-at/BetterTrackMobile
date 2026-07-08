package at.bettertrack.app.data.api

import at.bettertrack.app.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Reactive 401 handling (spec §4): when an authenticated call comes back 401,
 * refresh once (single-flighted) and retry the request with the new token. If
 * the refresh fails the token set is wiped by [TokenManager] and we return null
 * (stop) — the app then observes the session invalidation and returns to login.
 */
class TokenAuthenticator(
    private val tokenManager: TokenManager,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
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
}
