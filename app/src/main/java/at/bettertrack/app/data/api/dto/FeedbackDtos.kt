package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for in-app feedback submission (platform issues #1315 / #1316 / #1317,
 * contract locked 2026-08-17).
 *
 *  - POST /api/v1/feedback → [SubmitFeedbackRequest] → 201 [FeedbackCreatedResponse]
 *
 * LIVE on production since the platform's 2026-08-18 deploy. The route accepts BOTH
 * a session cookie and a bearer token (`security: [sessionCookie, apiKeyBearer]` in
 * the live `openapi.json`); the bearer path carries the `feedback:write` scope,
 * which is seeded to the BetterTrackMobile client and already present on existing
 * consents, so no re-login was needed. The surface is on behind
 * [at.bettertrack.app.data.repo.FeedbackFlags.enabled] and the scope is requested at
 * authorize time — see
 * [at.bettertrack.app.data.auth.OAuthConfig.FEEDBACK_SCOPE_ENABLED], which also
 * records why the flag existed at all (an UN-seeded scope in an authorize request is
 * not a soft failure but a hard reject of the entire login).
 *
 * `GET /feedback/mine` is deliberately NOT modelled, and still is not: feedback v2
 * (mine + status model + the per-submission thread + `PATCH /feedback/{id}`) is
 * platform #1338–#1342, queued behind the admin inbox #1316 and NOT live. A DTO for
 * a screen nobody builds is a promise the app does not keep.
 */

/**
 * The submission body.
 *
 * @param category REQUIRED, exactly one of `feature` | `bug` | `other`. This is the
 *   WIRE enum and it is never translated — the German UI shows
 *   "Feature/Verbesserung · Bug · Sonstiges" and still sends these three ASCII
 *   values. See [at.bettertrack.app.data.repo.FeedbackCategory].
 * @param message REQUIRED, 1..5000 characters. The composer hard-caps the input at
 *   5000 so the length rule is enforced before the request rather than discovered
 *   from a 400 — but the server's refusal is still surfaced verbatim if it happens.
 * @param subject optional, ≤120 characters. Omitted entirely when blank (an empty
 *   string is not "no subject", it is a subject that says nothing).
 * @param context optional. Stored server-side as opaque JSON; the inner keys are
 *   explicitly NOT schema-locked, so this sends what the device actually knows and
 *   omits what it does not. Nothing here is user-entered.
 */
@Serializable
data class SubmitFeedbackRequest(
    val category: String,
    val message: String,
    val subject: String? = null,
    val context: FeedbackContextDto? = null,
)

/**
 * Auto-attached diagnostic context. Every field is nullable and dropped when null
 * (the shared Json runs `explicitNulls = false`), because the contract says the
 * inner keys are not schema-locked: sending `"device": null` would be inventing a
 * fact ("we looked and there is no device") where omission says the honest thing.
 *
 * Deliberately carries NO account identifier, no token, no portfolio data and no
 * free text the user did not type: the server already knows who is calling from the
 * credential, and a feedback form is not a place to widen what leaves the phone.
 */
@Serializable
data class FeedbackContextDto(
    /** Always `"android"` from this client. */
    val platform: String? = null,
    /** `BuildConfig.VERSION_NAME` (+ build number), e.g. `"1.4.2 (142)"`. */
    val appVersion: String? = null,
    /** `"Android 15 (API 35)"`. */
    val osVersion: String? = null,
    /** Manufacturer + model, e.g. `"samsung SM-S911B"`. */
    val device: String? = null,
    /** BCP-47 tag of the app's effective locale, e.g. `"de-AT"`. */
    val locale: String? = null,
    /** Where the composer was opened from — `"settings"` or `"about"`. */
    val screen: String? = null,
)

/**
 * `201 { id, createdAt }`.
 *
 * Both fields carry defaults on purpose. The contract names them, but a decode
 * failure on an otherwise-successful 201 would tell the user their feedback failed
 * when it is already stored — the one lie this screen must not tell. The app never
 * displays either value (there is no "my feedback" list in v1), so tolerating a
 * shape drift costs nothing and protects the only outcome the user cares about.
 */
@Serializable
data class FeedbackCreatedResponse(
    val id: String = "",
    val createdAt: String? = null,
)
