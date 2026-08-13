package at.bettertrack.app.data.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

/** What the server actually received — the assertion surface for the proofs. */
class RecordedRequest(
    val method: String,
    val url: String,
    val encodedPath: String,
    val ifNoneMatch: String?,
    val authorization: String?,
    val noReauth: String?,
    val idempotencyKey: String?,
)

/**
 * A scripted MockEngine: [enqueue] a response (or a `throw` for a transport
 * failure) per expected request; every request is RECORDED first so the proofs
 * can assert exactly what went on the wire (validator, bearer, opt-out header)
 * and exactly how many requests were sent.
 */
class MockServer {
    val requests = mutableListOf<RecordedRequest>()
    private val steps = ArrayDeque<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()

    fun enqueue(step: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): MockServer {
        steps.add(step)
        return this
    }

    val engine: MockEngine = MockEngine { req ->
        requests.add(
            RecordedRequest(
                method = req.method.value,
                url = req.url.toString(),
                encodedPath = req.url.encodedPath,
                ifNoneMatch = req.headers[HttpHeaders.IfNoneMatch],
                authorization = req.headers[HttpHeaders.Authorization],
                noReauth = req.headers[BtNetHeaders.NO_REAUTH],
                idempotencyKey = req.headers["Idempotency-Key"],
            ),
        )
        val step = steps.removeFirstOrNull()
            ?: error("MockServer: no scripted response for request #${requests.size} ${req.method.value} ${req.url}")
        step(this, req)
    }
}

/** A 200 JSON response, optionally carrying a (weak) ETag validator. */
fun MockRequestHandleScope.okJson(body: String, etag: String? = null): HttpResponseData {
    val headers = HeadersBuilder().apply {
        append(HttpHeaders.ContentType, "application/json")
        if (etag != null) append(HttpHeaders.ETag, etag)
    }.build()
    return respond(body, HttpStatusCode.OK, headers)
}

/** A 401 (empty body) — the token-expiry signal the auth plugin reacts to. */
fun MockRequestHandleScope.unauthorized(): HttpResponseData =
    respond("", HttpStatusCode.Unauthorized)

/** A 304 Not Modified (empty body) — the conditional-GET validator hit. */
fun MockRequestHandleScope.notModified(): HttpResponseData =
    respond("", HttpStatusCode.NotModified)

/** An error response (default 403) that may carry a DECOY ETag validator. */
fun MockRequestHandleScope.errorWithEtag(
    status: HttpStatusCode,
    etag: String?,
    body: String = "error",
): HttpResponseData {
    val headers = HeadersBuilder().apply {
        if (etag != null) append(HttpHeaders.ETag, etag)
    }.build()
    return respond(body, status, headers)
}
