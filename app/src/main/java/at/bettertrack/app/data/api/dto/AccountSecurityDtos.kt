package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Settings → Account & Security surface (spec §6.12), all under
 * the bearer + `account:security` scope (verified LIVE on production 2026-07-10:
 * the `/auth/change-password`, `/auth/2fa/…`, `/auth/sessions*` group and
 * `DELETE /account` all accept the app's OAuth bearer once it carries
 * `account:security`). Field names follow the `@bettertrack/contracts` `auth.ts`
 * / `settings.ts` schemas exactly (camelCase); `ignoreUnknownKeys = true` keeps
 * them resilient to the API adding fields.
 */

// ── POST /auth/change-password ───────────────────────────────────────────────
// Voluntary change: currentPassword is required and verified server-side (a wrong
// one → 401 INVALID_CREDENTIALS "Current password is incorrect."). The 200 body is
// the refreshed user (MeResponse) — the app never reaches it during verification
// (the rail forbids ever completing a real change on the production account).
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String? = null,
    val newPassword: String,
)

// ── GET /auth/2fa/status ─────────────────────────────────────────────────────
@Serializable
data class TwoFactorStatusResponse(
    /** Authenticator-app (TOTP) method on (a code confirmed enrollment). */
    val totpEnabled: Boolean = false,
    /** A TOTP secret is enrolled but not yet confirmed (awaiting a code). */
    val totpPending: Boolean = false,
    /** Email-code method on. */
    val emailEnabled: Boolean = false,
    /** Unused recovery codes remaining (shared across both methods). */
    val recoveryCodesRemaining: Int = 0,
)

// ── POST /auth/2fa/enroll ────────────────────────────────────────────────────
// Begins TOTP enrollment: mints a provisional secret (method still OFF — 2FA is
// NOT armed and NO recovery codes are issued until /confirm). Safe to fetch + render
// + abandon; leaves totpPending=true which the API cannot clear (no cancel-enroll
// endpoint) — documented as harmless residue.
@Serializable
data class TwoFactorEnrollResponse(
    /** The `otpauth://totp/...` URI an authenticator app scans as a QR code. */
    val otpauthUri: String,
    /** The base32 secret, for manual entry when a QR can't be scanned. */
    val secret: String,
)

// ── POST /auth/2fa/confirm  &  /auth/2fa/email/confirm ───────────────────────
@Serializable
data class TwoFactorCodeRequest(val code: String)

/**
 * Result of enabling a 2FA method. `recoveryCodes` carries the one-time plaintext
 * set only when this is the FIRST method enabled; `null` when another method was
 * already active (the existing codes stay valid, not re-shown).
 */
@Serializable
data class TwoFactorMethodEnabledResponse(
    val recoveryCodes: List<String>? = null,
)

// ── POST /auth/2fa/disable ───────────────────────────────────────────────────
// A valid factor authorizes it: a 6-digit TOTP code or one unused recovery code.
@Serializable
data class TwoFactorDisableRequest(val code: String)

// ── POST /auth/2fa/recovery-codes (regenerate) ───────────────────────────────
@Serializable
data class TwoFactorRecoveryCodesResponse(
    val recoveryCodes: List<String> = emptyList(),
)

// ── GET /auth/sessions ───────────────────────────────────────────────────────
// The account's active *web/cookie* sessions. A bearer caller has NO session, so
// `current` is never true for the app itself; these are the user's browser + other
// logins. Revoking targets a session by its opaque `id` handle.
@Serializable
data class SessionSummaryDto(
    /** Opaque revocation handle (SHA-256 of the session id), safe to expose. */
    val id: String,
    /** Human device/browser label parsed from the User-Agent, or "Unknown device". */
    val device: String = "",
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    /** True for the caller's own session (never for a bearer principal). */
    val current: Boolean = false,
)

@Serializable
data class SessionListResponse(
    val sessions: List<SessionSummaryDto> = emptyList(),
)

// ── POST /auth/sessions/revoke-others ────────────────────────────────────────
@Serializable
data class RevokeSessionsResponse(val revoked: Int = 0)

// ── DELETE /account (#362 — LIVE on prod, verified 2026-07-10) ────────────────
// Hard, irreversible deletion. Typed username confirmation + re-auth (password, or
// a fresh TOTP / recovery code for 2FA accounts). At least one re-auth field is
// required (server refine). The app builds the full flow but gates the destructive
// submit behind DeleteAccountFeature.armed (OFF while pointed at the real account).
@Serializable
data class DeleteAccountRequest(
    val confirmUsername: String,
    val password: String? = null,
    val code: String? = null,
    val recoveryCode: String? = null,
)

// ── GET / PATCH /settings/account ────────────────────────────────────────────
// Where the server-side UI-language preference (`locale`) lives. The in-app
// language switch is authoritative locally (per-app locale); this mirrors the
// choice to the account so a later web login shows the same language. Gated on
// social:read/social:write (both held).
@Serializable
data class AccountSettingsResponse(
    val defaultPortfolioVisibility: String = "private",
    val locale: String = "en",
    val baseCurrency: String = "EUR",
)

@Serializable
data class UpdateAccountSettingsRequest(
    val locale: String? = null,
)
