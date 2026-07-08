package at.bettertrack.app.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the BetterTrack API (Step 4). Field names follow the OpenAPI
 * contract exactly:
 *  - the OAuth token endpoint is snake_case (OAuth 2.0 convention),
 *  - the rest of the API is camelCase.
 * `ignoreUnknownKeys = true` on the JSON instance keeps these resilient to the
 * API adding fields later.
 */

// ── POST /api/v1/oauth/token — request (discriminated by grant_type) ─────────
// Public client (PKCE): we send code_verifier and NEVER a client_secret.

@Serializable
data class TokenExchangeRequest(
    @SerialName("grant_type") val grantType: String = "authorization_code",
    @SerialName("code") val code: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
data class TokenRefreshRequest(
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
)

// ── POST /api/v1/oauth/token — response ──────────────────────────────────────
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    // Refresh is ROTATED on every exchange/refresh — always persist the new one.
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("scope") val scope: String,
)

// ── GET /api/v1/auth/me ──────────────────────────────────────────────────────
@Serializable
data class MeResponse(
    val id: String,
    val email: String,
    val username: String,
    // "user" | "admin" — the app is for `user` accounts only (spec §1).
    val role: String,
    // "active" | "disabled".
    val status: String,
    val mustChangePassword: Boolean = false,
    val pinEnabled: Boolean = false,
    val pinLockIdleMinutes: Int? = null,
    val baseCurrency: String = "EUR",
    val lastLoginAt: String? = null,
    val createdAt: String? = null,
)

// ── POST /api/v1/auth/pin/verify — verify the account's web PIN ───────────────
// The "use my BetterTrack PIN" app-lock option REUSES the existing web PIN; it
// never sets or changes it. The server answers 200 (match, renews session) /
// 401 (wrong) / 400 (no PIN on the account). The PIN travels here over TLS and
// is never persisted by the app — on success only a local Keystore hash is kept.
@Serializable
data class PinVerifyRequest(
    // 4–10 digits, ^\d+$ (server-validated). The app only ever sends 4.
    val pin: String,
)

// ── GET /api/v1/auth/pin/status — does the account have a web PIN ─────────────
// The dedicated, lightweight availability gate for the "use my BetterTrack PIN"
// app-lock option: it reports ONLY whether a web PIN exists, so the option is
// offered exactly when the account has one. Read-only — never sets/changes a PIN.
@Serializable
data class PinStatusResponse(
    val pinSet: Boolean = false,
)

// ── GET /api/v1/settings/oauth-grants (best-effort logout revocation) ────────
@Serializable
data class OAuthGrantListResponse(
    val grants: List<OAuthGrant> = emptyList(),
)

@Serializable
data class OAuthGrant(
    val id: String,
    val clientId: String,
    val appName: String = "",
    val scopes: List<String> = emptyList(),
    val createdAt: String? = null,
    val lastUsedAt: String? = null,
)

// ── Error envelope: { error: { code, message, details? } } ───────────────────
@Serializable
data class ApiErrorEnvelope(
    val error: ApiErrorBody,
)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
)
