package at.bettertrack.app.data.auth

/**
 * The two login-screen state types, moved out of `AuthModels.kt` into
 * `:shared/commonMain` by the web port, Phase W1.
 *
 * They travel with [at.bettertrack.app.ui.auth.BtLoginScreen] because they are
 * its input, and they are pure Kotlin — no `android.*`, no Retrofit, no
 * platform anything. Their package is unchanged, so no import in `:app` moved.
 * The rest of `AuthModels.kt` (the session/token models and the OAuth plumbing)
 * stays in `:app`: a browser has no custom-scheme return leg, so that half is a
 * W5 redesign rather than a move.
 */

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
