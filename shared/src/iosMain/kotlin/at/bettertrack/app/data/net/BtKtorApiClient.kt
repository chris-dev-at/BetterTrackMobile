package at.bettertrack.app.data.net

import at.bettertrack.app.data.api.dto.CreateCustomAssetRequest
import at.bettertrack.app.data.api.dto.CreateCustomAssetResponse
import at.bettertrack.app.data.api.dto.PinVerifyRequest
import at.bettertrack.app.data.api.dto.PinVerifyResponse
import at.bettertrack.app.data.api.dto.PortfolioDetailResponse
import at.bettertrack.app.data.api.dto.PortfolioHistoryResponse
import at.bettertrack.app.data.api.dto.PortfolioListResponse
import at.bettertrack.app.data.api.dto.SearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

/**
 * The iOS implementation of [BtApiClient], on a Ktor Darwin client that carries
 * the two session-critical plugins. It is constructed with an [HttpClientEngine]
 * so tests inject Ktor's MockEngine; production passes `Darwin.create()`.
 *
 * Plugin order is LOAD-BEARING: [BtConditionalGetPlugin] is installed FIRST
 * (outer) and [BtAuthPlugin] SECOND (inner), so conditional-GET replay sees the
 * response AFTER any 401→refresh→retry — mirroring OkHttp, where the ETag
 * application-interceptor sits above the Authenticator.
 */
class BtKtorApiClient(
    engine: HttpClientEngine,
    refresher: TokenRefresher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = defaultJson,
) : BtApiClient {

    private val conditionalGetCache = ConditionalGetCache()

    private val client = HttpClient(engine) {
        expectSuccess = false // inspect 4xx/5xx as responses, never as thrown errors
        install(BtConditionalGetPlugin) { cache = conditionalGetCache }
        install(BtAuthPlugin) { this.refresher = refresher }
    }

    /** The raw client — visible to the in-module session-integrity proofs only. */
    internal val http: HttpClient get() = client

    /** Drops every cached body — logout/account-switch, no body outlives a session. */
    suspend fun clearConditionalGetCache() = conditionalGetCache.clear()

    /**
     * Sends one request and maps the response to [ApiResponse], mirroring the app's
     * `apiCall` read of a Retrofit `Response<T>`: decode [body] on 2xx only; on an
     * error status leave body null and capture the RAW [errorBody] text. A transport
     * failure throws out of `client.request` and propagates untouched (never a 401).
     */
    private suspend fun <T> execute(
        deserializer: DeserializationStrategy<T>,
        method: HttpMethod,
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): ApiResponse<T> {
        val response = client.request {
            this.method = method
            url(baseUrl + path)
            configure()
        }
        val code = response.status.value
        return if (response.status.isSuccess()) {
            ApiResponse(true, code, json.decodeFromString(deserializer, response.bodyAsText()), null)
        } else {
            ApiResponse(false, code, null, response.bodyAsText())
        }
    }

    override suspend fun getPortfolio(id: String): ApiResponse<PortfolioDetailResponse> =
        execute(PortfolioDetailResponse.serializer(), HttpMethod.Get, "portfolios/$id")

    override suspend fun getPortfolioHistory(id: String, range: String): ApiResponse<PortfolioHistoryResponse> =
        execute(PortfolioHistoryResponse.serializer(), HttpMethod.Get, "portfolios/$id/history") {
            parameter("range", range)
        }

    override suspend fun search(q: String): ApiResponse<SearchResponse> =
        execute(SearchResponse.serializer(), HttpMethod.Get, "search") { parameter("q", q) }

    override suspend fun getPortfolios(): ApiResponse<PortfolioListResponse> =
        execute(PortfolioListResponse.serializer(), HttpMethod.Get, "portfolios")

    override suspend fun createCustomAsset(
        body: CreateCustomAssetRequest,
        idempotencyKey: String?,
    ): ApiResponse<CreateCustomAssetResponse> =
        execute(CreateCustomAssetResponse.serializer(), HttpMethod.Post, "custom-assets") {
            contentType(ContentType.Application.Json)
            idempotencyKey?.let { header("Idempotency-Key", it) }
            setBody(json.encodeToString(CreateCustomAssetRequest.serializer(), body))
        }

    override suspend fun pinVerify(body: PinVerifyRequest): ApiResponse<PinVerifyResponse> =
        execute(PinVerifyResponse.serializer(), HttpMethod.Post, "auth/pin/verify") {
            contentType(ContentType.Application.Json)
            header(BtNetHeaders.NO_REAUTH, "1")
            setBody(json.encodeToString(PinVerifyRequest.serializer(), body))
        }

    companion object {
        /** Placeholder — production wiring to `ServerOrigins` is a later chunk. */
        const val DEFAULT_BASE_URL = "https://api.bettertrack.invalid/api/v1/"

        /** Tolerant JSON, matching the app's `apiCall` decoder. */
        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
