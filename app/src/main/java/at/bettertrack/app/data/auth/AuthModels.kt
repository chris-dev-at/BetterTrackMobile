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

/** Localizable login failure reasons (message strings resolved in the UI). */
enum class LoginError {
    GENERIC,
    NETWORK,
    STATE_MISMATCH,
    EXCHANGE_FAILED,
    ACCOUNT_DISABLED,
    ADMIN_NOT_ALLOWED,
    SERVER_DENIED,
}

/** The login screen's transient state (button progress + error surface). */
sealed interface LoginPhase {
    data object Idle : LoginPhase
    /** Custom Tab is open and/or the token exchange is running. */
    data object InProgress : LoginPhase
    data class Failed(val error: LoginError, val detail: String? = null) : LoginPhase
}
