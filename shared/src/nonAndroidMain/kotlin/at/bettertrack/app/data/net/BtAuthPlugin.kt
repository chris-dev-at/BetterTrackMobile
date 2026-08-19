package at.bettertrack.app.data.net

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders

/** Config seam so the plugin can be driven by a fake [TokenRefresher] in tests. */
class BtAuthPluginConfig {
    lateinit var refresher: TokenRefresher
}

/**
 * Reproduces Android's `AuthInterceptor` + `TokenAuthenticator` EXACTLY, as one
 * Ktor `Send` interceptor (which, like an OkHttp application interceptor sitting
 * above the Authenticator, runs once per call and drives the single retry):
 *
 *  - PROACTIVE: before the request, `proactiveRefreshIfNeeded()`, then attach
 *    `Authorization: Bearer <currentAccessToken()>` when a token exists (no token
 *    ⇒ send unauthenticated).
 *  - REACTIVE on a 401: opt-out via `X-Bt-No-Reauth` ⇒ return the 401 as-is; else
 *    refresh ONCE with the failed bearer and retry ONCE. A null refresh ⇒ give up
 *    (return the 401; upstream reads null-refresh as session end → logout).
 *  - The retry re-sends via `proceed` at most once — never a loop.
 *
 * CRITICAL INVARIANT: a TRANSPORT failure is not a 401. `proceed` THROWS on a
 * connection/timeout/DNS/TLS error, and this interceptor neither catches it nor
 * calls `refreshOn401` — the error propagates untouched, so a network blip can
 * NEVER end the session. Only a real 401 status drives a refresh; only a null
 * refresh result ends the session.
 *
 * The refresh HTTP call itself is single-flighted inside the [TokenRefresher] and
 * runs on a SEPARATE bare client with no auth plugin, so it can never recurse.
 */
val BtAuthPlugin = createClientPlugin("BtAuthPlugin", ::BtAuthPluginConfig) {
    val refresher = pluginConfig.refresher
    on(Send) { request ->
        refresher.proactiveRefreshIfNeeded()
        attachBearer(request, refresher.currentAccessToken())
        val call = proceed(request)
        when {
            call.response.status.value != 401 -> call
            // A 401 here is a DOMAIN answer (e.g. wrong PIN): never refresh/retry.
            request.headers[BtNetHeaders.NO_REAUTH] != null -> call
            else -> {
                val failed = bearerOf(request)
                // null failed-token OR null refresh ⇒ give up (session end upstream).
                // Non-null ⇒ replace the bearer on the SAME builder and retry ONCE.
                val newToken = if (failed != null) refresher.refreshOn401(failed) else null
                if (newToken == null) {
                    call
                } else {
                    attachBearer(request, newToken)
                    proceed(request) // the single retry — never a loop
                }
            }
        }
    }
}

private fun attachBearer(builder: HttpRequestBuilder, token: String?) {
    if (token == null) return
    builder.headers.remove(HttpHeaders.Authorization)
    builder.headers.append(HttpHeaders.Authorization, "Bearer $token")
}

private fun bearerOf(builder: HttpRequestBuilder): String? =
    builder.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
