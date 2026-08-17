package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.FeedbackContextDto
import at.bettertrack.app.data.api.dto.SubmitFeedbackRequest
import kotlinx.serialization.json.Json

/**
 * In-app feedback (platform #1315 / #1316 / #1317).
 *
 * ## Why this ships dark
 *
 * The contract is final and the route is real, but the bearer path needs a
 * `feedback:write` scope that the platform has not finished seeding to the
 * BetterTrackMobile OAuth client. Requesting an un-seeded scope at authorize time
 * does not merely drop that scope — it **hard-rejects the entire login** ("This
 * app's authorization request is invalid"), which is how the alerts scopes broke
 * sign-in once already
 * ([at.bettertrack.app.data.auth.OAuthConfig.ALERTS_SCOPES_ENABLED] carries that
 * history). So the scope stays out of the authorize request, every call from this
 * client would 403 INSUFFICIENT_SCOPE, and the whole surface is held behind
 * [FeedbackFlags.enabled].
 *
 * Turning it on is two flags and a re-login — see [FeedbackFlags.enabled].
 */
object FeedbackFlags {
    /**
     * Whether the feedback composer exists in the UI at all. **Default OFF.**
     *
     * ### What must happen before this flips
     *
     * 1. The platform confirms `feedback:write` is seeded to the BetterTrackMobile
     *    client (the tick on #1317). Until then the scope cannot be requested.
     * 2. Flip [at.bettertrack.app.data.auth.OAuthConfig.FEEDBACK_SCOPE_ENABLED] to
     *    `true` so the authorize request actually asks for it.
     * 3. Flip this to `true`.
     * 4. Re-login once — a token minted before step 2 does not carry the scope, and
     *    a stale token 403s the POST no matter what these flags say.
     *
     * Doing (3) without (1) and (2) would put a working-looking form in front of
     * the user that can only ever fail with INSUFFICIENT_SCOPE. Doing (2) without
     * (1) breaks sign-in for everybody. The order is not negotiable.
     */
    const val enabled: Boolean = false
}

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
