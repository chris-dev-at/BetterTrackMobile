package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.FeedbackContextDto
import at.bettertrack.app.data.api.dto.FeedbackStatus
import at.bettertrack.app.data.api.dto.MyFeedbackItemDto
import at.bettertrack.app.data.api.dto.SubmitFeedbackRequest
import at.bettertrack.app.data.api.unitApiCall
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
 * ## v2's READ half is live since 2026-08-20
 *
 * `GET /feedback/mine` is on production (platform #1338) and modelled here as
 * [FeedbackRepository.mine]. The per-submission reply thread and
 * `PATCH /feedback/{id}` (the rest of #1338–#1342) are still not live, which is why
 * `unreadReplyCount` is carried but never drawn.
 *
 * The module now has a read/write scope split — `read: feedback:read`,
 * `write: feedback:write` — and the app requests both halves; see
 * [at.bettertrack.app.data.auth.OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED] for the
 * evidence trail that flipped the read half on 2026-08-20. A token minted before
 * the platform's grant-widening can still answer `403 INSUFFICIENT_SCOPE`, and the
 * submissions screen renders the catalogued sign-out/in copy for exactly that.
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

/**
 * The five wire categories. The wire values are ASCII and never translated.
 *
 * ## Declared in the deployed enum's own order
 *
 * Verified against production's `openapi.json` on 2026-08-20:
 * `CreateFeedbackRequest.category.enum` is
 * `["feature", "bug", "other", "help", "improvement"]` — and `MyFeedbackResponse`'s
 * row carries the identical five. The widening (platform #1400) appended `help`
 * and `improvement`; the first three are byte-unchanged, so every submission this
 * app has already sent still reads back with the same label.
 *
 * The order here is the WIRE order, not the order the composer draws. Those are
 * two different facts and conflating them would mean a platform reordering its
 * enum silently reshuffles the user's picker — see `FEEDBACK_CATEGORY_ORDER` in
 * `ui/feedback/FeedbackScreen.kt` for the display order and the test that keeps
 * it exhaustive.
 *
 * ## `improvement` is why the `feature` LABEL had to split
 *
 * Until the widening this app drew `feature` as "Feature/Verbesserung" / "Feature
 * or improvement", because one wire value had to cover both meanings. It no longer
 * does: a user who picks "Verbesserung" must now land on `improvement`, so the
 * label lost its second half the moment the second value existed. Leaving it would
 * have routed every improvement to the wrong bucket while looking correct.
 */
enum class FeedbackCategory(val wire: String) {
    Feature("feature"),
    Bug("bug"),
    Other("other"),
    Help("help"),
    Improvement("improvement"),
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
 * `FEEDBACK_OPEN_SUBMISSION_LIMIT` — how many submissions may be open at once
 * before the server refuses a new one with `FEEDBACK_OPEN_LIMIT` (platform #1400).
 *
 * Mirrored here for ONE reason and it is not client-side enforcement: the app
 * cannot know how many of the caller's submissions are still "open" (the wire
 * carries a status per row, not the server's own definition of open), so the
 * composer never pre-refuses on this. It exists so the number in the refusal copy
 * has a constant to be checked against — `FeedbackFailureCopyTest` asserts both
 * language strings still name it, which is what turns a platform change of the cap
 * into a failing build rather than into a sentence that lies by one order.
 */
const val FEEDBACK_OPEN_SUBMISSION_LIMIT = 20

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

// ── "Meine Einreichungen" — the read model behind GET /feedback/mine ─────────

/**
 * One row of the submissions list: the wire item, decoded once.
 *
 * A domain model rather than the raw DTO because three things have to happen
 * exactly once and be testable without a phone: the two wire enums resolve (to
 * `null` when the server names something this build does not know), the two ISO
 * stamps parse to epoch millis, and the list sorts newest-first. Doing any of that
 * in the composable would make it untestable and would re-parse on every
 * recomposition.
 *
 * Both `…Wire` fields are kept ALONGSIDE the resolved enums, and that is the whole
 * unknown-value strategy: [status] is `null` for a value the contract does not
 * name, and [statusWire] still carries what the server actually said, so the chip
 * can print it verbatim instead of vanishing or guessing.
 *
 * @param createdAtMs `null` only if the server sent an unparseable stamp. Such a
 *   row sorts LAST rather than being dropped — a submission the user wrote is not
 *   something to hide over a bad timestamp.
 * @param unreadReplyCount RESERVED, always 0 today (no reply thread exists yet).
 */
data class FeedbackSubmission(
    val id: String,
    val category: FeedbackCategory?,
    val categoryWire: String,
    val subject: String?,
    val message: String,
    val status: FeedbackStatus?,
    val statusWire: String,
    val createdAtMs: Long?,
    val lastStatusChangeAtMs: Long?,
    val declinedReason: String?,
    val shippedVersion: String?,
    val unreadReplyCount: Int,
)

/**
 * ISO-8601 instant → epoch millis, `null` for absent or unparseable.
 *
 * Two attempts, the same pair the portfolio and chat repositories use: `Instant`
 * for the `…Z` form the platform emits today, `OffsetDateTime` for an explicit
 * offset (`…+02:00`), which the contract's `format: date-time` also permits.
 */
private fun feedbackStampMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: java.time.format.DateTimeParseException) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }
}

/** Decode one wire item. Blank optional strings become `null` — see [toSubmissions]. */
internal fun MyFeedbackItemDto.toSubmission(): FeedbackSubmission = FeedbackSubmission(
    id = id,
    category = FeedbackCategory.fromWire(category),
    categoryWire = category,
    subject = subject?.trim()?.ifBlank { null },
    message = message,
    status = FeedbackStatus.fromWire(status),
    statusWire = status,
    createdAtMs = feedbackStampMs(createdAt),
    lastStatusChangeAtMs = feedbackStampMs(lastStatusChangeAt),
    // The two server invariants are honoured as READ rules, not re-asserted as
    // writes: the contract says `declinedReason` is set only on `declined` and
    // `shippedVersion` only on `shipped`, so a value arriving on any other status
    // is a server bug — and one this client must not amplify by rendering a
    // decline reason next to "In Arbeit". Dropping it is the conservative read.
    declinedReason = declinedReason?.trim()?.ifBlank { null }
        ?.takeIf { FeedbackStatus.fromWire(status) == FeedbackStatus.Declined },
    shippedVersion = shippedVersion?.trim()?.ifBlank { null }
        ?.takeIf { FeedbackStatus.fromWire(status) == FeedbackStatus.Shipped },
    unreadReplyCount = unreadReplyCount.coerceAtLeast(0),
)

/**
 * Decode the whole list, **newest first by `createdAt`**.
 *
 * The route documents no ordering, so the app imposes one rather than rendering
 * whatever order a query planner happened to produce. Rows whose stamp did not
 * parse sort last (they have no place on a timeline), and `id` is the tie-break so
 * two submissions written in the same millisecond do not swap places between two
 * loads of the same screen.
 */
internal fun List<MyFeedbackItemDto>.toSubmissions(): List<FeedbackSubmission> =
    map { it.toSubmission() }
        .sortedWith(compareByDescending<FeedbackSubmission> { it.createdAtMs ?: Long.MIN_VALUE }.thenBy { it.id })

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

    /**
     * `GET /feedback/mine` — my submissions, newest first.
     *
     * Same error discipline as [submit], for the same reason and one more: every
     * failure comes back verbatim as a [BtApiError], with no retry loop and no
     * softening to an empty list. An empty list is a real and common answer here
     * ("you have not written anything yet"), so a failure that returned one would
     * be indistinguishable from the truth — and would tell a user whose token
     * lacks `feedback:read` that their submissions do not exist.
     *
     * The `403 INSUFFICIENT_SCOPE` this returns on today's tokens is deliberately
     * NOT special-cased here: it is an ordinary [BtApiError] whose code the app's
     * error catalogue already owns copy for (`bt_err_insufficient_scope`, whose
     * remedy — sign out and back in — is exactly right once the scope is
     * requested). The screen renders it as its error state.
     */
    suspend fun mine(): BtResult<List<FeedbackSubmission>>

    /**
     * `DELETE /feedback/{id}` — hide one of MY submissions (platform #1400).
     *
     * Verified against production's `openapi.json` on 2026-08-20: the route
     * exists, is summarised *"Hide one caller-owned submission while retaining an
     * admin-visible tombstone"*, takes a `uuid` path parameter, answers **204 No
     * Content**, and declares 400 / 401 / the generic envelope and **no 404** —
     * which is the contract saying, in schema, that the call is idempotent.
     *
     * So a success here proves nothing about the row on its own, exactly as a
     * trusted-device revoke does not: an id that was already deleted answers 204
     * too. The caller re-reads [mine] afterwards and lets the LIST decide what the
     * user is told. This method therefore returns [Unit] rather than a
     * "was it deleted" boolean — a boolean would have to be invented from the
     * status code, and inventing it is the lie.
     *
     * `feedback:write` on the bearer path, which this app already holds (the same
     * scope `POST /feedback` uses), so no re-authorize is involved.
     */
    suspend fun delete(id: String): BtResult<Unit>
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

    override suspend fun mine(): BtResult<List<FeedbackSubmission>> =
        when (val r = apiCall(json) { api.myFeedback() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.submissions.toSubmissions())
            is BtResult.Err -> r
        }

    // `unitApiCall`, not `apiCall`: the route answers 204 with no body, and
    // `apiCall` treats an absent body as `APP_EMPTY_RESPONSE` — it would turn
    // every successful delete into an error.
    override suspend fun delete(id: String): BtResult<Unit> =
        unitApiCall(json) { api.deleteFeedback(id) }
}
