package at.bettertrack.app.ui.social

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.AudienceState
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.SocialRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Recovering a sharing-sheet save that named someone who is no longer an active
 * friend (#1949 / platform PR #1956, board tick 2026-09-15).
 *
 * `PUT /social/audience/:kind/:subjectId` on the `specific_friends` rung now
 * answers `400 { error: { code: "GROUP_MEMBER_NOT_FRIEND" } }` for a `friendIds`
 * entry that is not an active accepted friend, and writes NOTHING — the item
 * keeps its previous audience, even when the other names were fine. Three causes
 * (disabled friend, not a friend, nobody) are byte-identical on purpose (§6.9: no
 * existence oracle), so the app neither can nor tries to say WHICH person.
 *
 * The realistic trigger is a stale friends list in the open sheet, so the answer
 * is the same on every surface that saves an audience:
 *
 *  1. say so specifically ([BtErrorCopy.Context.Audience]'s copy — not the
 *     generic save error, not the groups sentence);
 *  2. re-fetch `GET /social/friends` (already scoped to active friends) and
 *     re-render the picker from it;
 *  3. pre-select what the user had picked MINUS anyone the fresh list no longer
 *     has, and leave the next save to the user. The same set is never re-sent
 *     automatically, and no "Try again" is offered that would re-send it.
 */
object AudienceSaveRecovery {
    /** The one code this flow handles; the same constant the groups path uses. */
    const val CODE_RECIPIENT_NOT_FRIEND = "GROUP_MEMBER_NOT_FRIEND"

    /** 409 — a widening/replacing write without `confirmWiden: true` (nothing written). */
    const val CODE_WIDEN_CONFIRMATION_REQUIRED = "AUDIENCE_WIDEN_CONFIRMATION_REQUIRED"
}

/** True when a failed save is the recoverable "someone can no longer be added". */
fun isUnavailableRecipient(error: BtApiError): Boolean =
    error.code == AudienceSaveRecovery.CODE_RECIPIENT_NOT_FRIEND

/** The user-facing copy for a failed audience save, from the audience context. */
fun audienceSaveMessage(error: BtApiError): BtMessage = error.asMessage(BtErrorCopy.Context.Audience)

/**
 * The selection the re-rendered picker starts from: the user's previous pick,
 * restricted to the people the refreshed list still has. Order-free, and never
 * ADDS anyone — a recovery must not widen an audience behind the user's back.
 */
fun retainedAudienceSelection(previous: Set<String>, refreshed: List<Friend>): Set<String> {
    val active = refreshed.mapTo(HashSet()) { it.userId }
    return previous.filterTo(LinkedHashSet()) { it in active }
}

/**
 * The outcome of a recovery, ready for a ViewModel to fold into its state.
 *
 * @param friends the list to render — the refreshed one, or [previousFriends]
 *   unchanged when the refresh itself failed (the notice still shows; the next
 *   save simply runs into the same answer and the same recovery).
 * @param friendIds the pre-selection for the re-rendered picker.
 * @param refreshed whether `GET /social/friends` actually answered.
 */
data class AudienceRecovery(
    val notice: BtMessage,
    val friends: List<Friend>,
    val friendIds: Set<String>,
    val refreshed: Boolean,
)

/**
 * Run the recovery for a [SocialRepository.setAudience] failure, or return null
 * when the failure is not the recoverable one (callers keep their generic path).
 *
 * Exactly one network call: the friends re-fetch. It never calls `setAudience`.
 */
suspend fun recoverAudienceSave(
    error: BtApiError,
    audience: ShareAudience,
    sentFriendIds: Set<String>,
    previousFriends: List<Friend>,
    social: SocialRepository,
): AudienceRecovery? {
    if (!isUnavailableRecipient(error)) return null
    val fresh = (social.friends() as? BtResult.Ok)?.value
    val friends = fresh ?: previousFriends
    return AudienceRecovery(
        notice = audienceSaveMessage(error),
        friends = friends,
        // Only the specific-friends rung names people; any other rung keeps the
        // user's pick as it was (the server would not send this code for it).
        friendIds = if (audience == ShareAudience.SpecificFriends) {
            retainedAudienceSelection(sentFriendIds, friends)
        } else {
            sentFriendIds
        },
        refreshed = fresh != null,
    )
}

// ═══════════════════════════════════════════════════════════════════════════
//  Widening confirmation (409 AUDIENCE_WIDEN_CONFIRMATION_REQUIRED)
// ═══════════════════════════════════════════════════════════════════════════
//
// Every widening or recipient-replacing transition needs `confirmWiden: true`
// (contracts `setAudienceRequestSchema`, `classifyAudienceTransition`): private ->
// anything, adding a named friend, swapping groups, specific <-> group, any move
// up the ladder. Without it the server answers
//
//   409 { "error": { "code": "AUDIENCE_WIDEN_CONFIRMATION_REQUIRED",
//                    "message": "The current audience is \"private\". …",
//                    "details": { "currentAudience": "private" } } }
//
// and writes nothing. The body names only the CURRENT rung — not who would gain
// access — so the sheet names the reach itself, from the two selections it
// already holds ("from Private to 2 friends"), the way the web's AudiencePicker
// does with its `audienceChangeConfirm` line. Only the owner's Confirm re-sends,
// and it re-sends exactly the refused request plus `confirmWiden: true`.

/** One save, exactly as the user chose it — what a Confirm re-sends verbatim. */
data class PendingAudienceSave(
    val audience: ShareAudience,
    val friendIds: Set<String>,
    val groupId: String?,
    val acknowledge: Boolean,
) {
    /**
     * Whether the sheet's CURRENT selection is still this save. A confirmation
     * is only offered for the selection it was asked about: change a tick and
     * the sheet goes back to a plain Apply (the web resets its widen tick on
     * every edit for the same reason).
     */
    fun matches(audience: ShareAudience, friendIds: Set<String>, groupId: String?): Boolean =
        audience == this.audience &&
            (audience != ShareAudience.SpecificFriends || friendIds == this.friendIds) &&
            (audience != ShareAudience.Group || groupId == this.groupId)
}

/**
 * The confirmation step the sheet shows after the 409.
 *
 * @param from the rung the SERVER says is current (`details.currentAudience`).
 * @param fromFriendCount how many friends the current `specific_friends` share
 *   names, when the sheet's audience read describes that same rung; else null.
 * @param fromGroupId the current group, under the same condition.
 */
data class AudienceWidenConfirm(
    val pending: PendingAudienceSave,
    val from: ShareAudience,
    val fromFriendCount: Int? = null,
    val fromGroupId: String? = null,
)

/** True for the 409 that asks for an explicit widening confirmation. */
fun isWidenConfirmationRequired(error: BtApiError): Boolean =
    error.code == AudienceSaveRecovery.CODE_WIDEN_CONFIRMATION_REQUIRED

/**
 * Build the confirmation step for a 409, or null for any other failure.
 *
 * The "from" rung is the server's word when it sent one (it is the authority
 * on what is current); the sheet's own audience read only fills it in when the
 * server's details are missing or unreadable, and only lends its count/group
 * when it describes that same rung.
 */
fun widenConfirmationFor(
    error: BtApiError,
    pending: PendingAudienceSave,
    current: AudienceState?,
): AudienceWidenConfirm? {
    if (!isWidenConfirmationRequired(error)) return null
    val wire = ((error.details as? JsonObject)?.get("currentAudience") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
    val from = ShareAudience.entries.firstOrNull { it.wire == wire }
        ?: current?.audience
        ?: ShareAudience.Private
    val sameRung = current?.audience == from
    return AudienceWidenConfirm(
        pending = pending,
        from = from,
        fromFriendCount = current?.friendIds?.size?.takeIf { sameRung && from == ShareAudience.SpecificFriends },
        fromGroupId = current?.groupId?.takeIf { sameRung && from == ShareAudience.Group },
    )
}

// ═══════════════════════════════════════════════════════════════════════════
//  One failure fold for every audience-saving surface
// ═══════════════════════════════════════════════════════════════════════════

/** What the open sharing sheet shows about its last save. Always IN the sheet. */
sealed interface AudienceSheetFeedback {
    /** 409: ask the owner to confirm the widening; Confirm re-sends [AudienceWidenConfirm.pending]. */
    data class ConfirmWiden(val confirm: AudienceWidenConfirm) : AudienceSheetFeedback

    /** 400 `GROUP_MEMBER_NOT_FRIEND`, recovered: refreshed list, narrowed pick. */
    data class Recovered(val message: BtMessage, val refreshed: Boolean) : AudienceSheetFeedback

    /** Any other refusal, in the sheet's own words — never behind the scrim. */
    data class Failed(val message: BtMessage) : AudienceSheetFeedback
}

/**
 * The fold of one failed save.
 *
 * @param friends a refreshed friends list to adopt, or null to keep the current one.
 * @param reseedFriendIds a new pre-selection for the picker, or null to leave the
 *   user's ticks exactly as they are (a confirmation must not reset them).
 */
data class AudienceSaveResolution(
    val feedback: AudienceSheetFeedback,
    val friends: List<Friend>? = null,
    val reseedFriendIds: Set<String>? = null,
)

/**
 * Classify a failed audience save. At most ONE network call — the friends
 * re-fetch on `GROUP_MEMBER_NOT_FRIEND` — and never a `setAudience`.
 */
suspend fun resolveAudienceSaveFailure(
    error: BtApiError,
    pending: PendingAudienceSave,
    current: AudienceState?,
    previousFriends: List<Friend>,
    social: SocialRepository,
): AudienceSaveResolution {
    widenConfirmationFor(error, pending, current)?.let {
        return AudienceSaveResolution(AudienceSheetFeedback.ConfirmWiden(it))
    }
    recoverAudienceSave(error, pending.audience, pending.friendIds, previousFriends, social)?.let {
        return AudienceSaveResolution(
            feedback = AudienceSheetFeedback.Recovered(it.notice, it.refreshed),
            friends = it.friends,
            reseedFriendIds = it.friendIds,
        )
    }
    return AudienceSaveResolution(AudienceSheetFeedback.Failed(audienceSaveMessage(error)))
}
