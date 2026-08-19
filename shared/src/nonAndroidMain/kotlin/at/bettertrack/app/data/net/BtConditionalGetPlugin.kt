package at.bettertrack.app.data.net

import io.ktor.client.call.body
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

/** Config seam so the owning client can share ONE cache (and clear it on logout). */
class BtConditionalGetPluginConfig {
    var cache: ConditionalGetCache = ConditionalGetCache()
}

/**
 * Reproduces Android's `ConditionalGetInterceptor` EXACTLY for iOS. Applies ONLY
 * to GET on the three ETag'd targets (see [isConditionalGetTarget]); everything
 * else passes through untouched and is never cached.
 *
 * Installed OUTSIDE [BtAuthPlugin] (first in the client config) so — like the
 * OkHttp application interceptor sitting above the Authenticator — it sees the
 * FINAL response after any 401→refresh→retry, not the intermediate 401.
 *
 * The whole flow lives in the `Send` lambda so `proceed` (the pipeline
 * continuation) is in scope; the LRU store is [ConditionalGetCache].
 */
val BtConditionalGetPlugin = createClientPlugin(
    "BtConditionalGetPlugin",
    ::BtConditionalGetPluginConfig,
) {
    val cache = pluginConfig.cache
    val client = client
    on(Send) { request ->
        val built = request.url.build()
        if (request.method != HttpMethod.Get || !isConditionalGetTarget(built.encodedPath)) {
            proceed(request)
        } else {
            val url = built.toString()
            val cached = cache.get(url)
            if (cached != null && request.headers[HttpHeaders.IfNoneMatch] == null) {
                request.headers.append(HttpHeaders.IfNoneMatch, cached.etag)
            }
            val call = proceed(request)
            val status = call.response.status
            when {
                // 304 + cached -> replay the stored body as a synthetic 200.
                status.value == 304 && cached != null -> buildSyntheticCall(
                    client, call, HttpStatusCode.OK, cached.body,
                    call.response.headers, cached.contentType, fromEtagCache = true,
                )
                // 304 we cannot honour (evicted between request and response):
                // drop the validator and REFETCH rather than return an empty body.
                status.value == 304 -> {
                    cache.remove(url)
                    request.headers.remove(HttpHeaders.IfNoneMatch)
                    proceed(request)
                }
                // DECOY DEFENSE: cache-write is gated on 2xx. A 4xx/5xx carrying an
                // ETag is NEVER cached, so a decoy validator cannot later replay as 200.
                status.isSuccess() -> {
                    val etag = call.response.headers[HttpHeaders.ETag]
                    if (etag.isNullOrBlank()) {
                        call
                    } else {
                        val bytes = call.body<ByteArray>() // consumes single-shot body
                        val contentType = call.response.headers[HttpHeaders.ContentType]
                        cache.put(url, etag, bytes, contentType)
                        // Hand back a re-readable equivalent (the original is spent).
                        buildSyntheticCall(
                            client, call, status, bytes,
                            call.response.headers, contentType, fromEtagCache = false,
                        )
                    }
                }
                else -> call
            }
        }
    }
}
