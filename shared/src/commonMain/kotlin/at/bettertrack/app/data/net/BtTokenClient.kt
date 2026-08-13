package at.bettertrack.app.data.net

import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import at.bettertrack.app.data.api.dto.TokenResponse

/**
 * The BARE token contract — the platform-neutral mirror of Android's `TokenApi`.
 * It is deliberately SEPARATE from [BtApiClient]: its implementation must run on
 * a client with NO auth plugin, so a refresh can never recurse through the 401
 * machinery. The real [TokenRefresher] drives its exchange/refresh (a later
 * chunk); it is defined here so the contract's token half is complete and the
 * "separate bare client" boundary is explicit in the type system.
 */
interface BtTokenClient {

    /** POST /oauth/token (grant_type=authorization_code) — code → tokens. */
    suspend fun exchange(body: TokenExchangeRequest): ApiResponse<TokenResponse>

    /** POST /oauth/token (grant_type=refresh_token) — rotates the token pair. */
    suspend fun refresh(body: TokenRefreshRequest): ApiResponse<TokenResponse>
}
