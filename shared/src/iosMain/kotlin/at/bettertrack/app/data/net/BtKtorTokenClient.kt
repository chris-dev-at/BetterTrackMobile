package at.bettertrack.app.data.net

import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import at.bettertrack.app.data.api.dto.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * The iOS [BtTokenClient] — deliberately on a BARE Ktor client with NO auth
 * plugin, so a token exchange/refresh can never recurse through the 401 machinery
 * (the exact reason Android's `TokenManager` depends on the bare `TokenApi`, never
 * on the authenticated client). Not exercised by this chunk's proofs; it makes the
 * "separate bare client" boundary explicit and satisfiable on iOS.
 */
class BtKtorTokenClient(
    engine: HttpClientEngine,
    private val baseUrl: String = BtKtorApiClient.DEFAULT_BASE_URL,
    private val json: Json = BtKtorApiClient.defaultJson,
) : BtTokenClient {

    private val client = HttpClient(engine) { expectSuccess = false }

    private suspend fun <B, T> postToken(
        requestSerializer: SerializationStrategy<B>,
        responseDeserializer: DeserializationStrategy<T>,
        body: B,
    ): ApiResponse<T> {
        val response = client.post {
            url(baseUrl + "oauth/token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(requestSerializer, body))
        }
        val code = response.status.value
        return if (response.status.isSuccess()) {
            ApiResponse(true, code, json.decodeFromString(responseDeserializer, response.bodyAsText()), null)
        } else {
            ApiResponse(false, code, null, response.bodyAsText())
        }
    }

    override suspend fun exchange(body: TokenExchangeRequest): ApiResponse<TokenResponse> =
        postToken(TokenExchangeRequest.serializer(), TokenResponse.serializer(), body)

    override suspend fun refresh(body: TokenRefreshRequest): ApiResponse<TokenResponse> =
        postToken(TokenRefreshRequest.serializer(), TokenResponse.serializer(), body)
}
