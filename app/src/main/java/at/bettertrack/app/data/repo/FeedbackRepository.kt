package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.FeedbackContextDto
import at.bettertrack.app.data.api.dto.SubmitFeedbackRequest
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.shows
import kotlinx.serialization.json.Json

/**
 * In-app feedback (platform #1315 / #1316 / #1317).
 *
 * ## Live since 2026-08-19 (platform deploy 2026-08-18)
 *
 * `POST /feedback` is on production and accepts a **bearer** token: the live
 * `openapi.json` lists the route with `security: [sessionCookie, apiKeyBearer]`,
 * and the `MODULE_POLICIES` row exists, so the `403 API_KEY_FORBIDDEN` this app
 * measured on 2026-08-17 is gone. `feedback:write` is in the scope catalog and in
 * the BetterTrackMobile client's scope ceiling (which the openapi enumerates as 20
 * scopes, `feedback:write` among them); the platform's seed unions rather than
 * narrows and an additive migration widened the consents that already exist — so
 * **no re-login and no re-authorize is required** for a session that predates the
 * deploy. [at.bettertrack.app.data.auth.OAuthConfig.FEEDBACK_SCOPE_ENABLED] is on
 * too, so every future authorize asks for the scope as well.
 *
 * HISTORY: this surface shipped dark from 2026-08-17. The scope was not seeded
 * yet, and requesting an un-seeded scope at authorize time does not merely drop
 * that scope — it **hard-rejects the entire login** ("This app's authorization
 * request is invalid"), which is how the alerts scopes broke sign-in once already
 * ([at.bettertrack.app.data.auth.OAuthConfig.ALERTS_SCOPES_ENABLED] carries that
 * history). If the platform ever retracts the seed, the same lever applies: both
 * flags back to `false`, rebuild, re-verify.
 *
 * ## v2 is NOT live — do not build against it
 *
 * `GET /feedback/mine`, the per-submission thread, `PATCH /feedback/{id}` and the
 * status/notification model are platform issues **#1338–#1342**, queued behind the
 * admin inbox (**#1316**). Only v1 — one fire-and-forget POST — exists. A
 * consequence worth knowing while #1316 is unmerged: submissions are stored and
 * readable in the database, but nobody can triage them in the admin panel yet.
 */
object FeedbackFlags {
    /**
     * Whether the feedback composer exists in the UI at all. **ON since
     * 2026-08-19** (platform GO-LIVE tick for #1315, deployed 2026-08-18T09:38Z).
     *
     * This is one of two independent switches and it is the narrower one: it gates
     * the two entry rows (Settings → About group, and the bottom of the About
     * screen's link group). The other,
     * [at.bettertrack.app.data.auth.OAuthConfig.FEEDBACK_SCOPE_ENABLED], decides
     * whether `feedback:write` rides along in the authorize request — i.e. whether
     * tokens minted from here on carry the capability at all. Turning this one on
     * alone would work today (existing consents were widened server-side) and then
     * silently lose the capability at the next re-login, so the two move together.
     *
     * This flag is NOT the account check. A Drive-autonomous install has no
     * BetterTrack account and no bearer token to send, so the entry rows are gated
     * on [feedbackEntryVisible], which is this flag AND
     * [at.bettertrack.app.data.storage.BtSurface.ACCOUNT_SETTINGS].
     */
    const val enabled: Boolean = true
}

/**
 * Whether either feedback entry row may be rendered in [mode].
 *
 * Two conditions, one place. [FeedbackFlags.enabled] is the capability switch, and
 * `ACCOUNT_SETTINGS` is the honest question underneath it: `POST /feedback` is a
 * *server* route authenticated by a bearer token, and a Drive-autonomous install
 * has neither — no BetterTrack account, no token, nothing for the endpoint to
 * attribute the report to. Showing the row there would open a composer whose Send
 * button is permanently disabled behind its signed-in check, which is precisely
 * the "a row that opens a form that can only fail" outcome the flag was created to
 * prevent.
 *
 * Kept here rather than duplicated at the two call sites for the reason
 * [at.bettertrack.app.data.storage.surfaceAvailability] states for the whole
 * surface table: two independent copies of a visibility rule drift, and the drift
 * is invisible until a Drive user finds the dead row.
 */
fun feedbackEntryVisible(mode: StorageMode): Boolean =
    FeedbackFlags.enabled && mode.shows(BtSurface.ACCOUNT_SETTINGS)

/** The three wire categories. The wire values are ASCII and never translated. */
enum class FeedbackCategory(val wire: String) {
    Feature("feature"),
    Bug("bug"),
    Other("other"),
    ;

    companion object {
        /** `null` for anything the contract does not name — never a guessed default. */
        fun fromWire(wire: String?): FeedbackCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/** Server-enforced limits, mirrored client-side so the composer refuses first. */
const val FEEDBACK_MESSAGE_MAX = 5000
const val FEEDBACK_SUBJECT_MAX = 120

/**
 * What the user has typed so far. Pure data so the validation and the exact request
 * body are unit-tested without a UI or a network.
 */
data class FeedbackDraft(
    val category: FeedbackCategory? = null,
    val subject: String = "",
    val message: String = "",
)

/**
 * Whether [FeedbackDraft] can be sent.
 *
 * `message` is checked with [String.isBlank], not `isNotEmpty`: 200 spaces satisfies
 * the server's `min(1)` and is not feedback. The length ceilings are checked too
 * even though the composer hard-caps its fields — the cap is a UI affordance, and a
 * rule that only lives in the UI is a rule that a future caller can walk around.
 */
fun FeedbackDraft.isSendable(): Boolean =
    category != null &&
        message.isNotBlank() &&
        message.length <= FEEDBACK_MESSAGE_MAX &&
        subject.length <= FEEDBACK_SUBJECT_MAX

/**
 * Build the exact POST body.
 *
 * Trims both text fields, because leading/trailing whitespace is never part of what
 * somebody meant to say, and drops a blank subject entirely — an empty string is not
 * "no subject", it is a subject that says nothing, and the field is optional
 * precisely so it can be absent.
 *
 * Returns `null` when the draft is not sendable, so "don't send" is a value the
 * caller must handle rather than a silently-malformed request.
 */
fun FeedbackDraft.toRequest(context: FeedbackContextDto?): SubmitFeedbackRequest? {
    if (!isSendable()) return null
    return SubmitFeedbackRequest(
        category = category!!.wire,
        message = message.trim(),
        subject = subject.trim().ifBlank { null },
        context = context,
    )
}

/** The `context.platform` value this client sends. Never anything else. */
const val FEEDBACK_PLATFORM = "android"

/** Where the composer was opened from, for `context.screen`. */
object FeedbackOrigin {
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

/**
 * Assemble the auto-attached context, normalising blanks to `null`.
 *
 * Kept a pure function so the exact JSON the phone attaches is unit-tested without
 * an emulator: the Android-specific gathering (`Build`, `BuildConfig`, the locale)
 * happens in the composable that calls this, and every value arrives here as a
 * plain string.
 *
 * A blank becomes `null` and is then dropped from the body entirely — the inner
 * keys are explicitly not schema-locked, so `"device": ""` would be the app
 * asserting it looked and found an empty device, where absence says the true thing.
 */
fun feedbackContextOf(
    appVersion: String?,
    osVersion: String?,
    device: String?,
    locale: String?,
    screen: String?,
): FeedbackContextDto = FeedbackContextDto(
    platform = FEEDBACK_PLATFORM,
    appVersion = appVersion?.takeIf { it.isNotBlank() },
    osVersion = osVersion?.takeIf { it.isNotBlank() },
    device = device?.takeIf { it.isNotBlank() },
    locale = locale?.takeIf { it.isNotBlank() },
    screen = screen?.takeIf { it.isNotBlank() },
)

interface FeedbackRepository {
    /**
     * POST the draft. `Ok` means the server stored it (201); every failure is
     * surfaced verbatim, including the 429 the ~5/hour rate limiter raises and the
     * validation envelope a bad body returns.
     *
     * Deliberately NOT softened the way the notification writes soften
     * forbidden/offline to `Ok`: those are background best-effort syncs, this is a
     * button the user pressed and waited on. Telling somebody their feedback was
     * sent when it was not is the single worst thing this screen could do.
     */
    suspend fun submit(draft: FeedbackDraft, context: FeedbackContextDto?): BtResult<Unit>
}

class DefaultFeedbackRepository(
    private val api: BtApi,
    private val json: Json,
) : FeedbackRepository {

    override suspend fun submit(
        draft: FeedbackDraft,
        context: FeedbackContextDto?,
    ): BtResult<Unit> {
        // An unsendable draft never reaches the network: the server would answer
        // with a validation envelope the user cannot act on any better than the
        // composer's own disabled Send button already told them.
        // `httpStatus = 400`, not `0`: a zero status means "transport failure" to
        // the rest of the app (`BtApiError.isNetwork`) and would tell the user their
        // connection dropped when in fact nothing was ever sent.
        val body = draft.toRequest(context) ?: return BtResult.Err(
            BtApiError(httpStatus = 400, code = BtApiError.Codes.VALIDATION_ERROR),
        )
        return when (val r = apiCall(json) { api.submitFeedback(body) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }
    }
}
