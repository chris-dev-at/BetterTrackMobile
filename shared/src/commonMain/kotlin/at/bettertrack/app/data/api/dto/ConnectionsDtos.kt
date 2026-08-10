package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for **Connections** — the Google sign-in identity half of the web's
 * Control Center → Connections panel (platform `§13.4 V4-P4b`, moved to
 * Connections in `V5-P0c`).
 *
 * The OAuth-grants half of that surface ("Authorized apps") is NOT here: it
 * already exists as [OAuthGrantListResponse] / [OAuthGrant] in `OAuthDtos.kt`,
 * where the logout-time revocation path put it. One shape, one place.
 *
 * The Drive half of the web panel has no DTO at all, deliberately: this app
 * manages the paranoid vault's Drive medium on "Where your data lives", so the
 * Connections screen links into that surface instead of carrying a second copy
 * of the media controller.
 */

/**
 * `GET /auth/google/link-status`.
 *
 * The platform contract (`googleLinkStatusResponseSchema`) is a **strict**
 * object: every key below is always present, and the two nullable ones arrive
 * as an explicit `null` rather than being omitted. They still carry a `null`
 * default so a deployment that predates one of them degrades to "not linked"
 * instead of throwing a `MissingFieldException` that the app would then have to
 * render as a mystery failure.
 *
 * The three booleans have NO default on purpose — each is load-bearing:
 *  - [enabled] `false` (or a 404 on the route) means this deployment has no
 *    Google client configured, and the whole group renders nothing;
 *  - [linked] selects between the linked identity and the connect affordance;
 *  - [canUnlink] is `false` while Google is the account's ONLY usable sign-in
 *    method, which is the one case where the unlink must not even be offered.
 *
 * Guessing any of them from an absent key would put a wrong, irreversible-looking
 * control on screen, so a malformed body is better surfaced as a failure.
 */
@Serializable
data class GoogleLinkStatusResponse(
    /** Whether this deployment has Google OAuth configured at all (env-gated). */
    val enabled: Boolean,
    /** Whether the caller's account has a linked Google identity. */
    val linked: Boolean,
    /** The linked Google email; `null` when not linked. */
    val email: String? = null,
    /** ISO instant the identity was linked; `null` when not linked. */
    val linkedAt: String? = null,
    /** `false` while Google is the caller's only usable sign-in method. */
    val canUnlink: Boolean,
)

/**
 * `POST /auth/google/unlink` — the account password, re-authenticating the
 * caller before the link is removed.
 *
 * The password lives in this object for exactly the length of one request. It is
 * never persisted, never logged (the OkHttp logger is BASIC — method, url,
 * status, timing — so no body ever reaches logcat) and never leaves the
 * authenticated client.
 */
@Serializable
data class GoogleUnlinkRequest(
    val password: String,
)
