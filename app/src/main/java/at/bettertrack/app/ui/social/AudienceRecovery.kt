package at.bettertrack.app.ui.social

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.SocialRepository

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
