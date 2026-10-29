package at.bettertrack.app.data.auth

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.util.concurrent.TimeUnit

/**
 * The bare OkHttp client `POST /oauth/token` rides — no auth interceptor (a
 * bearer on the token endpoint is a `401 API_KEY_INVALID`, see
 * [refreshRefusalIsDefinitive]), and three settings that exist because the
 * refresh token ROTATES:
 *
 * 1. **[OneShotRequestBodies]: OkHttp may never re-send a refresh on its own.**
 *    OkHttp's `RetryAndFollowUpInterceptor` silently retries a request after an
 *    `IOException` even when the request had already been SENT (it only refuses
 *    for a one-shot body or a timeout), and on a reused pooled connection it does
 *    so without a second route to try (`ExchangeFinder.retryAfterFailure`:
 *    `routeSelector ?: return true`). For a refresh that is a hidden replay: the
 *    server rotated, the connection reset before the answer arrived, OkHttp
 *    presented the consumed token again on a fresh connection, and the app saw
 *    only the `400 INVALID_GRANT` that came back — a "definitive" refusal it
 *    wiped the session for, recorded as a plain `REFRESH_REJECTED` with no trace
 *    of the lost answer behind it. A one-shot body keeps OkHttp's harmless
 *    retries (a connect failure, where nothing was sent) and hands every
 *    after-send failure to [TokenManager], which replays deliberately, persisted
 *    and single-flight. It also stops OkHttp's own `408` / `503 Retry-After: 0`
 *    follow-ups from re-sending the body.
 * 2. **No pooled connections** (`ConnectionPool(0, …)`): a refresh happens about
 *    once an hour, by which time a pooled connection is usually stale; writing a
 *    refresh into one that the edge already closed is exactly the failure (1)
 *    used to paper over. A fresh handshake per refresh costs nothing measurable.
 * 3. **A 25 s call timeout**: the platform's rotation grace (PR-A) is 60 s from
 *    the server consuming the token, so a lost answer must be noticed — and
 *    replayed (1-3 s later) — well inside it. 25 + 3 + 25 < 60.
 *
 * @param outer interceptors that run before everything else (the debug-only
 *   fault injector).
 * @param logging the debug-only BASIC logger, innermost.
 */
internal fun tokenHttpClient(
    outer: List<Interceptor> = emptyList(),
    logging: Interceptor? = null,
): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(TOKEN_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .apply { outer.forEach { addInterceptor(it) } }
        .addInterceptor(OneShotRequestBodies)
        .apply { logging?.let { addInterceptor(it) } }
        .build()

internal const val TOKEN_CALL_TIMEOUT_SECONDS = 25L

/**
 * Marks every request body one-shot, so OkHttp never transmits it twice. An
 * APPLICATION interceptor on purpose: it must run before
 * `RetryAndFollowUpInterceptor`, which is what consults `isOneShot()`.
 */
internal object OneShotRequestBodies : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body ?: return chain.proceed(request)
        if (body.isOneShot()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().method(request.method, OneShot(body)).build())
    }

    private class OneShot(private val delegate: RequestBody) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot(): Boolean = true
    }
}
