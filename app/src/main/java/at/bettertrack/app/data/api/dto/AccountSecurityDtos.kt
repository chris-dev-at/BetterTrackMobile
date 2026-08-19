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
    /**
     * v5 discreet mode. The server ONLY persists this flag — hiding amounts is
     * entirely a client rendering rule (see
     * [at.bettertrack.app.ui.format.BtDiscreetMode]). Defaults false so a pre-v5
     * server, which omits the key, reads as "off" rather than crashing.
     */
    val discreetMode: Boolean = false,
)

/**
 * PATCH /settings/account. The schema is `.strict()` and requires at least one
 * field, so every property is nullable and `explicitNulls = false` drops the
 * ones the caller didn't set — a locale change never silently rewrites the
 * user's discreet-mode flag, and vice versa.
 */
@Serializable
data class UpdateAccountSettingsRequest(
    val locale: String? = null,
    val discreetMode: Boolean? = null,
    /**
     * `EUR` | `USD` | `CHF` | `GBP` — exactly these four (`BASE_CURRENCIES`).
     * A read-time RENDER parameter only: stored amounts stay in their native
     * currency, so changing this never rewrites a single row.
     */
    val baseCurrency: String? = null,
    /**
     * `private` | `friends`. Applies at portfolio CREATION time only — existing
     * portfolios keep whatever they were set to, which is why the settings copy
     * says "new portfolios are" rather than "portfolios are".
     */
    val defaultPortfolioVisibility: String? = null,
)

/**
 * The four display currencies the platform converts totals to
 * (`BASE_CURRENCIES` in `packages/contracts/src/settings.ts`). A closed enum on
 * the wire, so the picker is a fixed list rather than a text field.
 */
val BT_BASE_CURRENCIES: List<String> = listOf("EUR", "USD", "CHF", "GBP")

// ── GET /social/profile · PUT /social/profile ────────────────────────────────

/**
 * The caller's own profile. `profileIcon` is one of [BT_PROFILE_ICONS] or null
 * (never picked) — clients resolve the id to a bundled asset themselves; the
 * platform ships no image and no URL.
 */
@Serializable
data class ProfileSettingsResponse(
    val username: String = "",
    val isPublic: Boolean = false,
    val bio: String? = null,
    val publicItemCount: Int = 0,
    val profileIcon: String? = null,
)

/**
 * PUT /social/profile.
 *
 * Two traps live in this body, both of which the app has to respect rather than
 * work around:
 *
 *  - **`isPublic` is REQUIRED**, even when all you are changing is the icon. The
 *    route is a PUT, not a PATCH: a caller that omitted it would be asking to
 *    make the profile private. Every call must therefore send the value the
 *    profile currently has, which is why the icon picker reads the profile first.
 *  - **`acknowledgePublic` must be `true` when turning `isPublic` ON** (the §16
 *    friction ladder, enforced server-side). It is meaningless otherwise.
 *
 * `profileIcon` follows the omitted-vs-null rule: omit to leave it untouched,
 * send `null` to clear it back to the default, send an id to set it. An unknown
 * id is a 400.
 */
@Serializable
data class UpdateProfileSettingsRequest(
    val isPublic: Boolean,
    val bio: String? = null,
    val acknowledgePublic: Boolean? = null,
    val profileIcon: String? = null,
)

/**
 * The curated profile-icon set (`PROFILE_ICON_IDS`, `packages/contracts/src/social.ts`).
 *
 * Order is the picker's render order and is part of the contract — new icons are
 * APPENDED, never inserted, so a user's icon never silently becomes a different
 * one. There are no uploads and no external URLs by design.
 */
val BT_PROFILE_ICONS: List<String> = listOf(
    "astronaut", "fox", "panda", "robot", "star", "wave", "mountain", "leaf",
    "flame", "bolt", "moon", "planet", "ghost", "crown", "compass", "anchor",
)

// ── PUT /auth/pin — set OR change the ACCOUNT PIN ────────────────────────────
/**
 * The account PIN write.
 *
 * Three properties of this route surprise people, and all three are the
 * platform's deliberate design rather than an oversight to compensate for:
 *
 *  - **One route does both set and change.** There is no separate "change"
 *    endpoint and no `currentPin` field — the new value simply replaces the old.
 *  - **No re-authentication.** Neither the current PIN nor the account password
 *    is required; the bearer is the authority. The PIN is a privacy curtain over
 *    an already-authenticated session, not a second factor, and the platform's
 *    own web UI changes it with no credential either.
 *  - **Exactly four digits.** `^\d{4}$`, enforced server-side. Verification
 *    tolerates 4–10 for PINs set before that rule, but nothing may write one.
 *
 * The response is the full refreshed [MeResponse].
 */
@Serializable
data class SetAccountPinRequest(
    /** Exactly 4 digits. Never logged, never persisted. */
    val pin: String,
)

// ── PUT /auth/pin/idle-timeout ───────────────────────────────────────────────
/**
 * How long the account may sit idle before the PIN is asked again.
 *
 * This is an ACCOUNT preference that travels to every front-end — it is NOT the
 * device app-lock's AFK threshold, which is local to this phone and lives in
 * [at.bettertrack.app.data.applock.AfkThreshold]. The two are deliberately
 * separate settings on separate screens and must never be merged: one decides
 * when the web asks for the PIN again, the other when this handset does.
 *
 * Server range is 1–1440 minutes, or `null` for "use the default" (10). The app
 * only ever writes the presets the web offers, so it never needs to send null —
 * which matters, because `explicitNulls = false` would drop the key and the
 * server would read that as "leave unchanged" rather than "clear".
 */
@Serializable
data class SetPinIdleTimeoutRequest(
    val idleMinutes: Int,
)

/** The idle-timeout presets the web offers, in minutes. Order is display order. */
val BT_PIN_IDLE_PRESETS: List<Int> = listOf(1, 5, 10, 15, 30, 60)

/** What the server uses when the account has never chosen an idle timeout. */
const val BT_PIN_IDLE_DEFAULT: Int = 10

/** Exactly the digit count the server accepts for a NEW account PIN. */
const val BT_ACCOUNT_PIN_LENGTH: Int = 4

// ── Account data export (POST/GET /account/export, POST /account/export/download)

/**
 * The re-auth gate on `POST /account/export`.
 *
 * At least one of the three must be present. `explicitNulls = false` drops the
 * unset ones, which is exactly the wire shape the server's `.refine()` wants.
 */
@Serializable
data class ExportRequest(
    val password: String? = null,
    /** A fresh TOTP code — 2FA-enrolled accounts may use this instead. */
    val code: String? = null,
    /** An unused recovery code. Consumed even on a failed match. */
    val recoveryCode: String? = null,
)

/**
 * The answer to a fresh export request.
 *
 * [downloadToken] is shown EXACTLY ONCE — the server persists only its hash, and
 * the download consumes it. It must be held in memory for the life of the
 * screen and never written to disk, a log, or a saved instance state.
 */
@Serializable
data class ExportRequestResponse(
    val jobId: String,
    val status: String,
    val downloadToken: String,
)

/**
 * `GET /account/export` — the poll.
 *
 * Every field is nullable, and all-null is the honest answer for an account that
 * has never requested an export. [status] is `pending | ready | failed | expired`.
 */
@Serializable
data class ExportStatusResponse(
    val status: String? = null,
    val jobId: String? = null,
    val requestedAt: String? = null,
    /** When a ready file stops being downloadable. */
    val expiresAt: String? = null,
    val sizeBytes: Long? = null,
)

@Serializable
data class ExportDownloadRequest(
    val token: String,
)

/** Lifecycle states of an export job, as the server names them. */
object BtExportStatus {
    const val PENDING = "pending"
    const val READY = "ready"
    const val FAILED = "failed"
    const val EXPIRED = "expired"
}

// ── Passkeys (GET /auth/passkeys · PATCH/DELETE /auth/passkeys/{id}) ─────────
//
// LIVE on production 2026-08-18/19, bearer-reachable under `account:security`
// (the app's shipped 19-scope list already carries it — no re-consent).
//
// Verified against the deployed `https://api.bettertrack.at/openapi.json` on
// 2026-08-19, and TWO things there are worth writing down because the go-live
// note said otherwise:
//
//  - the list is an **envelope**, `{ "passkeys": [...] }`, not a bare array;
//  - the path parameter is spelled **`{id}`** (`/auth/passkeys/{id}`), matching
//    `passkeyIdParamSchema` in `packages/contracts/src/auth.ts`, not the
//    `{passkeyId}` the go-live table used. Retrofit templates are local names,
//    so this build's `{passkeyId}` placeholder addresses the same route either
//    way — but the wire path is `/auth/passkeys/<uuid>` and nothing else.
//
// REGISTRATION IS NOT HERE ON PURPOSE. `/auth/passkeys/register/options` +
// `/verify` are a WebAuthn ceremony bound to the web ORIGIN; a credential minted
// anywhere else would not be the one the browser is later asked to present. The
// app links out for that one job and manages everything else natively.

/** One registered passkey (`passkeySchema`, `packages/contracts/src/auth.ts`). */
@Serializable
data class PasskeyDto(
    val id: String,
    /** The user-chosen label, max [BT_PASSKEY_NAME_MAX] characters. */
    val name: String = "",
    /** ISO-8601 registration instant. */
    val createdAt: String? = null,
    /** ISO-8601 last successful login, or null for a passkey never used since it was added. */
    val lastUsedAt: String? = null,
)

/** `GET /auth/passkeys` — newest first. */
@Serializable
data class PasskeyListResponse(
    val passkeys: List<PasskeyDto> = emptyList(),
)

/** `PATCH /auth/passkeys/{id}` — rename only; the server asks for no credential. */
@Serializable
data class PasskeyRenameRequest(val name: String)

/**
 * `DELETE /auth/passkeys/{id}` — re-auth gated, and therefore a DELETE **with a
 * body** (`@HTTP(hasBody = true)`, same shape as `DELETE /account`).
 *
 * At least one of the three must be present (server `.refine()`), and
 * `explicitNulls = false` drops the unset ones so the wire carries exactly the
 * one credential the user supplied. Removing the LAST passkey is allowed — the
 * contract says so in as many words — because password sign-in always remains;
 * the UI still warns, because losing it is a real consequence.
 */
@Serializable
data class PasskeyDeleteRequest(
    val password: String? = null,
    /** A fresh TOTP code — 2FA-enrolled accounts may use this instead. */
    val code: String? = null,
    /** An unused recovery code. Consumed even on a failed match. */
    val recoveryCode: String? = null,
)

/** Server ceiling on a passkey label (`PASSKEY_NAME_MAX`). */
const val BT_PASSKEY_NAME_MAX: Int = 64

// ── Remembered devices (GET/DELETE /auth/remembered-devices[/{handle}]) ─────
//
// The bindings that let a BROWSER skip the sign-in step on the web's OAuth PIN
// quick-auth path. LIVE on production 2026-08-18/19; openapi-verified 2026-08-19.
//
// Two facts shape everything below:
//
//  - **The app never mints one.** `POST /auth/remembered-device` is
//    session-cookie-only and belongs to the web login page's "remember me"
//    checkbox. This client signs in through a Custom-Tab OAuth leg and has no
//    quick-auth path at all, so every row a user sees here was created by their
//    browser. The screen says so, or the list is baffling.
//  - **Revocation is idempotent.** Unknown, expired and foreign handles all
//    answer 200, so a 200 is NOT evidence that anything was forgotten. The
//    screen re-reads the list after every revoke and lets the list be the truth.

/**
 * One live remembered-device binding.
 *
 * [handle] is a domain-separated SHA-256 digest in base64url — an opaque
 * revocation token, never a name. base64url's alphabet (`A–Z a–z 0–9 - _ =`)
 * contains nothing Retrofit percent-encodes in a path segment, so it travels
 * as-is; `RememberedDeviceWireTest` asserts that on the actual bytes.
 *
 * Every timestamp is nullable here even though the deployed schema marks
 * `expiresAt` required: bindings created before the metadata columns existed
 * carry no history, the go-live note called all three nullable, and a missing
 * key must degrade to "clause omitted" rather than to a decode failure that
 * empties the whole screen.
 */
@Serializable
data class RememberedDeviceDto(
    val handle: String,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val expiresAt: String? = null,
)

/** `GET /auth/remembered-devices` — envelope `{ "devices": [...] }`, openapi-verified. */
@Serializable
data class RememberedDeviceListResponse(
    val devices: List<RememberedDeviceDto> = emptyList(),
)
