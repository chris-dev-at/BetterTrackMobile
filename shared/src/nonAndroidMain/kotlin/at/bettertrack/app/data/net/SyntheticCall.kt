package at.bettertrack.app.data.net

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Job

/**
 * Fabricate an [HttpClientCall] carrying [bodyBytes] and [status], reusing the
 * original request. This is how the ConditionalGet plugin replays a 304 as a
 * synthetic 200, and how it hands a re-readable copy of a buffered 2xx body back
 * downstream (the raw body channel is single-shot, exactly as OkHttp's is). It is
 * the same shape MockEngine and the engine layer build, so `bodyAsText()` /
 * `body<T>()` read it normally.
 *
 * Content-Length from the source is dropped (we set a fresh body); an explicit
 * [contentType] and the `X-BT-From-ETag-Cache` marker are re-applied to match the
 * Android interceptor's replayed response byte-for-byte.
 */
@OptIn(InternalAPI::class)
internal fun buildSyntheticCall(
    client: HttpClient,
    original: HttpClientCall,
    status: HttpStatusCode,
    bodyBytes: ByteArray,
    sourceHeaders: Headers,
    contentType: String?,
    fromEtagCache: Boolean,
): HttpClientCall {
    val req = original.request
    val requestData = HttpRequestData(
        req.url,
        req.method,
        req.headers,
        req.content,
        req.coroutineContext[Job] ?: Job(),
        req.attributes,
    )
    val headers = HeadersBuilder().apply {
        sourceHeaders.forEach { name, values ->
            if (!name.equals(HttpHeaders.ContentLength, ignoreCase = true)) {
                values.forEach { append(name, it) }
            }
        }
        if (contentType != null && get(HttpHeaders.ContentType) == null) {
            append(HttpHeaders.ContentType, contentType)
        }
        if (fromEtagCache) append(BtNetHeaders.FROM_ETAG_CACHE, "1")
    }.build()
    val responseData = HttpResponseData(
        statusCode = status,
        requestTime = GMTDate(),
        headers = headers,
        version = HttpProtocolVersion.HTTP_1_1,
        body = ByteReadChannel(bodyBytes),
        callContext = original.coroutineContext,
    )
    return HttpClientCall(client, requestData, responseData)
}
