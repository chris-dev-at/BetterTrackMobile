package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for in-app feedback (platform issues #1315 / #1316 / #1317 for the
 * write half, #1338–#1342 for the read half; contract locked 2026-08-17).
 *
 *  - POST /api/v1/feedback       → [SubmitFeedbackRequest] → 201 [FeedbackCreatedResponse]
 *  - GET  /api/v1/feedback/mine  → [MyFeedbackResponse]
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
 * ## The read half went live 2026-08-20
 *
 * `GET /feedback/mine` is on production and modelled below. It is the FIRST of
 * feedback v2 to land; the per-submission reply thread and `PATCH /feedback/{id}`
 * (the rest of #1338–#1342) still are not, which is why
 * [MyFeedbackItemDto.unreadReplyCount] is documented as reserved rather than used.
 *
 * The bearer path needs the module's READ scope, `feedback:read`, which the app
 * does NOT request yet — see
 * [at.bettertrack.app.data.auth.OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED] for the
 * reason and the flip signal.
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

// ── GET /feedback/mine — the read half (live 2026-08-20) ─────────────────────

/**
 * `200 { submissions: [...] }` — every submission the CALLER owns, with its
 * lifecycle status.
 *
 * A wrapper object rather than a bare array, verified against the deployed
 * `openapi.json`: `MyFeedbackResponse` is `{ submissions: [...] }` with
 * `required: ["submissions"]` and `additionalProperties: false`. There is no
 * pagination — that belongs to the ADMIN list (`AdminFeedbackListResponse`), which
 * is a different schema this client can never reach (bearer requests are barred
 * from admin routes).
 *
 * The default keeps a `{}` (or a future `{ submissions: null }`) decoding to "no
 * submissions" rather than throwing: an empty list is the exact truth for a caller
 * who has never written anything, so tolerating the shape costs no honesty.
 */
@Serializable
data class MyFeedbackResponse(
    val submissions: List<MyFeedbackItemDto> = emptyList(),
)

/**
 * One submission of mine.
 *
 * ## Byte-checked against production's `openapi.json` (2026-08-20)
 *
 * `required` is exactly `[id, category, subject, message, status,
 * lastStatusChangeAt, declinedReason, shippedVersion, unreadReplyCount,
 * createdAt]`, `additionalProperties: false`. Note what is NOT there: **no
 * `updatedAt`**. The admin row (`AdminFeedbackListResponse`) carries one, and the
 * brief for this screen named one, but the caller-facing item does not — so it is
 * not modelled here. Reporting a field the contract does not declare would be the
 * app inventing a wire fact, and `lastStatusChangeAt` is the stamp this screen
 * actually wants anyway.
 *
 * Every field carries a default because this is a READ model and the house rule
 * for those is flat and tolerant (see `IdeaDtos`): one item with a shape the
 * server changed under us must degrade to a row that says less, never take the
 * whole list down with a `MissingFieldException`.
 *
 * @param id UUID. The row key and, once the thread ships, the thread's id.
 * @param category the WIRE enum `feature` | `bug` | `other` — never translated.
 *   Mapped through [at.bettertrack.app.data.repo.FeedbackCategory.fromWire], which
 *   returns `null` for anything the contract does not name.
 * @param subject nullable by contract; a submission sent without one has `null`
 *   here, and the row falls back to the message.
 * @param message the full text, 1..5000 chars. The list shows an excerpt of it;
 *   the detail sheet shows all of it.
 * @param status the WIRE enum — see [FeedbackStatus]. Never translated; the German
 *   and English display names are the app's own choice.
 * @param lastStatusChangeAt ISO-8601 instant. When the status last moved — which
 *   for a `new` submission is simply when it arrived.
 * @param declinedReason **server invariant**: non-null if and only if [status] is
 *   `declined`. Max 1000 chars.
 * @param shippedVersion **server invariant**: non-null if and only if [status] is
 *   `shipped`. Max 64 chars, and rendered VERBATIM — the app never prepends a "v"
 *   or reformats it, because the version string is the platform's to spell.
 * @param unreadReplyCount **RESERVED — always 0 today.** The per-submission reply
 *   thread is not live, so the server has nothing to count. It is modelled and
 *   rendered behind a `> 0` gate so the badge lights up on the day threads ship
 *   without an app release; today it is never drawn.
 * @param createdAt ISO-8601 instant. The list's sort key (newest first).
 */
@Serializable
data class MyFeedbackItemDto(
    val id: String = "",
    val category: String = "",
    val subject: String? = null,
    val message: String = "",
    val status: String = "",
    val lastStatusChangeAt: String? = null,
    val declinedReason: String? = null,
    val shippedVersion: String? = null,
    val unreadReplyCount: Int = 0,
    val createdAt: String? = null,
)

/**
 * The submission lifecycle, wire-final:
 * `new → triaged → working_on_it | saved_as_future_idea | declined | shipped`.
 *
 * The wire values are ASCII and are NEVER translated — only the display copy is,
 * and choosing that copy well was the whole product point of the feature (the
 * owner's words: *"give them status rejected or like saved as future idea. or
 * working on rn… make up better names but like that"*). The mapping from these
 * constants to the German and English chip labels lives in
 * `ui/feedback/FeedbackSubmissionsScreen.kt` as a `when` with no `else`, so adding
 * a constant here fails to compile until somebody writes both languages for it.
 *
 * ## Why this enum lives with the DTO and [at.bettertrack.app.data.repo.FeedbackCategory] does not
 *
 * `FeedbackCategory` is a WRITE enum: it is part of the composer's draft model,
 * validated before a request exists, and it belongs with the draft. This one is a
 * pure decode of a field the server owns — the app never sends a status and can
 * never choose one — so it belongs with the shape it decodes.
 */
enum class FeedbackStatus(val wire: String) {
    New("new"),
    Triaged("triaged"),
    WorkingOnIt("working_on_it"),
    SavedAsFutureIdea("saved_as_future_idea"),
    Declined("declined"),
    Shipped("shipped"),
    ;

    companion object {
        /**
         * `null` for anything the contract does not name — never a guessed
         * default, and specifically never [New].
         *
         * A status the platform adds next month must not silently render as
         * "Eingegangen" (a lie about where the submission stands) and must not
         * crash the list. `null` is the third answer, and the screen draws it as
         * the raw wire string in a neutral chip: unbeautiful, unmistakably
         * unknown, and truthful.
         */
        fun fromWire(wire: String?): FeedbackStatus? = entries.firstOrNull { it.wire == wire }
    }
}
