package at.bettertrack.app.data.api

import at.bettertrack.app.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer …` to every authenticated request and PROACTIVELY
 * refreshes the access token just before it expires (spec §4), so calls rarely
 * have to eat a 401. The refresh is single-flighted inside [TokenManager]; the
 * refresh HTTP call itself uses a separate bare client, so this never recurses.
 */
class AuthInterceptor(
    private val tokenManager: TokenManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        runBlocking { tokenManager.proactiveRefreshIfNeeded() }
        val token = tokenManager.currentAccessToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
