package at.bettertrack.app.ui.components

/**
 * Which state surface a data-backed screen should show.
 *
 * @see resolveListSurface
 */
enum class BtListSurface {
    /** Real rows. */
    CONTENT,

    /** Placeholders — the answer is not known yet. */
    SKELETON,

    /** The read failed and there is nothing to fall back on. */
    ERROR,

    /** The read failed because there is no connection, and nothing is cached. */
    OFFLINE,

    /** The read succeeded and the answer is genuinely "none". */
    EMPTY,
}

/**
 * The one decision every data-backed screen in this app has to make, made once.
 *
 * ## Why this is a function and not a `when` in each screen
 *
 * The R-arc audit found the same bug in screen after screen: a failed first
 * fetch rendering as *"you have nothing yet"*. Transactions said "No
 * transactions yet" to accounts that had transactions; Cash said "no movements";
 * the conglomerate list said "not enough history" whenever a request merely
 * dropped. Each screen had written its own `when`, each had collapsed two
 * different situations into one branch, and each had to be found and fixed
 * separately — which is the definition of a rule that should have been code.
 *
 * The conflation is easy to write because the *symptom* of both cases is the
 * same: an empty list. The difference is entirely in **why** it is empty, and
 * that is knowable only from the flags below. So the flags are the arguments,
 * the answer is an enum, and the screens stop guessing.
 *
 * ## The rules, in priority order
 *
 * 1. **Anything to show wins.** If [hasContent], render it — even over a
 *    failure. A refresh that failed on top of cached rows is a *notice*, not a
 *    takeover: blanking real data the user is reading, to announce that the
 *    newer copy did not arrive, loses more than it explains. (Screens pair this
 *    with a dismissible refresh banner; that is a separate, additive signal.)
 * 2. **Unknown beats empty.** While [firstLoadPending] and nothing has failed,
 *    the honest answer is "not known yet" — [SKELETON]. This is the branch whose
 *    absence caused the original bug, because a screen with only two states has
 *    to call an unanswered question "empty".
 * 3. **A failure is a failure.** With no content and a [failed] read, show
 *    [OFFLINE] when [isOnline] is false and [ERROR] otherwise. They are split
 *    because the user can act on one of them: turning the network back on is a
 *    fix, and "something went wrong" is not.
 * 4. **Only then, empty.** [EMPTY] is reserved for a read that *succeeded* and
 *    returned nothing. That is the only situation in which the app is entitled
 *    to tell a user they have no data.
 *
 * Note that offline is judged only when the read actually failed. Being offline
 * while holding cached rows is not a state this returns — rule 1 already showed
 * the rows, and an offline banner is the right weight for that.
 *
 * @param hasContent anything at all worth rendering: server rows, cached rows,
 *   or pending local writes not yet synced.
 * @param firstLoadPending true until the first read for this screen has
 *   answered, either way. Deliberately NOT "a refresh is in flight" — later
 *   refreshes happen over content and must not replace it with placeholders.
 * @param failed whether the most recent read failed.
 * @param isOnline connectivity as the app currently understands it.
 */
fun resolveListSurface(
    hasContent: Boolean,
    firstLoadPending: Boolean,
    failed: Boolean,
    isOnline: Boolean = true,
): BtListSurface = when {
    hasContent -> BtListSurface.CONTENT
    firstLoadPending && !failed -> BtListSurface.SKELETON
    failed && !isOnline -> BtListSurface.OFFLINE
    failed -> BtListSurface.ERROR
    else -> BtListSurface.EMPTY
}
