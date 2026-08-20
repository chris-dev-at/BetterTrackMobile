package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.dto.MeResponse
import at.bettertrack.app.data.api.dto.TokenResponse
import kotlinx.serialization.Serializable

/** Persisted token set (EncryptedSharedPreferences). */
@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val scope: String,
    /** Absolute wall-clock expiry of the access token, epoch millis. */
    val expiresAtEpochMs: Long,
) {
    fun isExpiringWithin(skewMs: Long): Boolean =
        System.currentTimeMillis() >= (expiresAtEpochMs - skewMs)
}

/** Turn a token response into a stored token set with an absolute expiry. */
fun TokenResponse.toAuthTokens(now: Long = System.currentTimeMillis()): AuthTokens =
    AuthTokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
        scope = scope,
        expiresAtEpochMs = now + (expiresIn * 1000L),
    )

/** The signed-in user, cached for startup routing + display (Settings, §6.12). */
@Serializable
data class SessionUser(
    val id: String,
    val username: String,
    val email: String,
    val role: String,
    val status: String,
    val mustChangePassword: Boolean,
    val baseCurrency: String,
    /**
     * The account's `privacyMode` as last seen on `/auth/me` (`"normal"` /
     * `"paranoid"`, or null on a pre-v5 server). Persisted with the rest of the
     * session so a COLD START can route a paranoid account to the explainer
     * before `/auth/me` has answered — the whole point of the pre-flight signal.
     * Null-defaulted so an existing stored session deserializes unchanged.
     */
    val privacyMode: String? = null,
    /**
     * The account's `createdAt` as last seen on `/auth/me` — an ISO-8601 instant,
     * rendered by Settings as the "Member since" row (web parity, 2026-08-08).
     *
     * Named for what it MEANS rather than for the wire field it comes from: the
     * app has exactly one use for the account's creation timestamp, and calling
     * the session field `createdAt` would invite the reading "when this session
     * was created", which is a different and much shorter-lived thing.
     *
     * Null-defaulted for the same two reasons `privacyMode` is: a pre-v5 server
     * omits the key entirely, and an existing stored session — written by a build
     * that had no such field — must keep deserializing. Absent means absent; the
     * row is simply not rendered (see `formatMemberSince`).
     */
    val memberSince: String? = null,
) {
    companion object {
        /** Placeholder used when a valid token exists but /auth/me hasn't resolved yet. */
        fun unknown(): SessionUser = SessionUser(
            id = "",
            username = "",
            email = "",
            role = "user",
            status = "active",
            mustChangePassword = false,
            baseCurrency = "EUR",
            privacyMode = null,
            memberSince = null,
        )
    }
}

fun MeResponse.toSessionUser(): SessionUser = SessionUser(
    id = id,
    username = username,
    email = email,
    role = role,
    status = status,
    mustChangePassword = mustChangePassword,
    baseCurrency = baseCurrency,
    privacyMode = privacyMode,
    memberSince = createdAt,
)

/**
 * Top-level auth state that gates navigation (spec §4): logged out ⇒ login only;
 * logged in ⇒ the 4-tab shell; a forced-password-change gate sits in between.
 */
sealed interface AuthState {
    /** Startup: reading persisted session. Resolves immediately to one of the below. */
    data object Unknown : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val user: SessionUser) : AuthState
    data class PasswordChangeRequired(val user: SessionUser) : AuthState
}

// [LoginError] and [LoginPhase] moved to :shared/commonMain (web port, W1):
// they are the two types the shared login screen takes, and they are pure
// Kotlin. Package unchanged, so nothing that reads them moved. The rest of this
// file — the token/session models and the OAuth plumbing around them — stays
// here until W5 settles browser auth.
