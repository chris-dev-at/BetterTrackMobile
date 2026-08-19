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
    /**
     * V5 (platform PR #1055, board tick 2026-08-04): the account's privacy mode,
     * `"normal"` or `"paranoid"`. This is the PRE-FLIGHT signal the app asked for
     * in PLATFORM_ASKS #39.1 — without it a paranoid account's first launch is a
     * wall of `403 PARANOID_MODE` refusals and the portfolio surfaces can only
     * learn the truth from a failure.
     *
     * Nullable with a null default on purpose: a pre-v5 server omits the key
     * entirely, and "absent" must mean "no opinion" (leave whatever the 403
     * interceptor has concluded alone), NOT "normal". See
     * [at.bettertrack.app.data.auth.privacyModeOrNull].
     */
    val privacyMode: String? = null,
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

// ── POST /api/v1/auth/pin/verify — 200 response ───────────────────────────────
// A correct PIN returns 200 with a SMALL confirmation body (observed on-device:
// an ~11-byte object, e.g. {"ok":true} — NOT the full user object). The app needs
// NOTHING from it: a 200 alone means "verified". So this is deliberately an empty,
// tolerant shape — with the JSON instance's `ignoreUnknownKeys = true` it decodes
// ANY 200 object body (whatever fields the server sends now or later) WITHOUT
// throwing. Previously this endpoint was typed as `MeResponse`, whose required
// id/email/username/role/status are absent from the tiny verify body, so a correct
// PIN's 200 threw a MissingFieldException inside Retrofit; `apiCall` mapped that to
// httpStatus -1 and the UI showed "Couldn't verify right now" instead of activating
// (the 401 wrong-PIN path never parses a body, so it was unaffected). See
// [at.bettertrack.app.data.applock.AccountPinService.verifyBetterTrackPin].
@Serializable
class PinVerifyResponse

// ── GET /api/v1/auth/pin/status — does the account have a web PIN ─────────────
// The dedicated, lightweight availability gate for the "use my BetterTrack PIN"
// app-lock option: it reports ONLY whether a web PIN exists, so the option is
// offered exactly when the account has one. Read-only — never sets/changes a PIN.
@Serializable
data class PinStatusResponse(
    val pinSet: Boolean = false,
)

// ── GET /api/v1/settings/oauth-grants ────────────────────────────────────────
// Two consumers: the Authorized-apps screen, and the best-effort self-revocation
// on logout. The list is NOT filtered first-party — the platform keeps this app's
// own grant in it deliberately, because that row is how a user kills a lost or
// stolen phone's access from a browser. See [OAuthGrant.firstParty].
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

    /**
     * Server-declared "this grant belongs to a first-party BetterTrack client".
     *
     * Platform #1390, **not live yet** — hence nullable with no default opinion.
     * `null` means "this deployment does not say", which is different from a
     * declared `false`, and only the declared answer may override the client-side
     * fallback ([at.bettertrack.app.ui.connections.isOwnGrant] matching on
     * `clientId`). Decoded now so the flag lights up on a server deploy rather
     * than an app release.
     */
    val firstParty: Boolean? = null,

    /**
     * Server-declared "this grant is the credential the calling request is riding".
     *
     * The precise thing the Authorized-apps screen needs and cannot compute:
     * `clientId` identifies the APP, but the same app on the user's tablet holds a
     * different grant with the same `clientId`. Derived server-side from the
     * presented token, so it is always `false` for a cookie caller.
     *
     * Also #1390 and also not live. Until it ships, the screen falls back to
     * `clientId`, which over-matches across the user's own devices — stated in
     * [at.bettertrack.app.ui.connections.isOwnGrant] rather than hidden.
     */
    val current: Boolean? = null,
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
