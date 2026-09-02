package at.bettertrack.app.ui.format

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R

/**
 * The app's COMPACT relative-time stamp — `jetzt`, `5 Min.`, `3 Std.`, `2 T.`,
 * `2 Wo.` — for a list row whose timestamp sits in the corner and must not push the
 * content aside.
 *
 * ## Why it exists (device QA 2026-09-01, defects #8 and #9)
 *
 * The German chat list rendered `now`, `2w`, `6w`, `7w`, and the notification inbox
 * rendered `1w`. Both came from a hardcoded English `when`, and there were TWO of
 * them — `ui/chat/ChatListScreen.relativeTime` and
 * `ui/notifications/NotificationsInboxScreen.inboxRelativeTime`, character for
 * character identical, so localizing one would have left the other in English.
 * This is the single replacement for both.
 *
 * ## Why it is not `bt_intel_age_*`
 *
 * `IntelAge` (market intel) already buckets on exactly these boundaries and is
 * already localized, but its copy is the LONG form ("vor 2 Wo. ago"-style prose)
 * because it labels a headline's age in running text. A conversation row has one
 * corner to spend, so it needs the compact form. The two families share the
 * boundaries and nothing else; keeping them apart is what stops a prose change on
 * one surface from widening a column on the other.
 *
 * ## Shape
 *
 * Split into a pure bucketer and a composable labeller, the way `IntelAge` and
 * `SessionRecency` already are. The bucketer takes [nowMs] as a parameter rather
 * than reading the clock, which is the only reason the boundaries are testable at
 * all — the functions this replaced called `System.currentTimeMillis()` internally
 * and their tests had to compute an offset from the live clock to hit them.
 */
sealed interface BtTimeAgo {

    /** Under a minute. */
    data object Now : BtTimeAgo

    data class Minutes(val value: Int) : BtTimeAgo

    data class Hours(val value: Int) : BtTimeAgo

    data class Days(val value: Int) : BtTimeAgo

    /** A week or more — the ladder deliberately stops here rather than adding
     *  months and years: past a few weeks the exact age stops being the thing a
     *  reader wants from a chat row, and a two-character corner cannot carry it. */
    data class Weeks(val value: Int) : BtTimeAgo
}

/**
 * Which bucket [epochMs] falls into, as of [nowMs]. A future timestamp (clock skew
 * between phone and server) clamps to [BtTimeAgo.Now] rather than reporting a
 * negative age.
 */
fun btTimeAgo(epochMs: Long, nowMs: Long): BtTimeAgo {
    val minutes = (nowMs - epochMs).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> BtTimeAgo.Now
        minutes < 60 -> BtTimeAgo.Minutes(minutes.toInt())
        minutes < 60 * 24 -> BtTimeAgo.Hours((minutes / 60).toInt())
        minutes < 60 * 24 * 7 -> BtTimeAgo.Days((minutes / (60 * 24)).toInt())
        else -> BtTimeAgo.Weeks((minutes / (60 * 24 * 7)).toInt())
    }
}

/** The string resource each bucket labels itself with. Pure, so it is testable. */
@StringRes
internal fun btTimeAgoRes(age: BtTimeAgo): Int = when (age) {
    BtTimeAgo.Now -> R.string.bt_time_ago_now
    is BtTimeAgo.Minutes -> R.string.bt_time_ago_minutes
    is BtTimeAgo.Hours -> R.string.bt_time_ago_hours
    is BtTimeAgo.Days -> R.string.bt_time_ago_days
    is BtTimeAgo.Weeks -> R.string.bt_time_ago_weeks
}

/** The bucket's count, or null for [BtTimeAgo.Now], which takes no argument. */
internal fun btTimeAgoValue(age: BtTimeAgo): Int? = when (age) {
    BtTimeAgo.Now -> null
    is BtTimeAgo.Minutes -> age.value
    is BtTimeAgo.Hours -> age.value
    is BtTimeAgo.Days -> age.value
    is BtTimeAgo.Weeks -> age.value
}

/** The rendered compact stamp for [age]. */
@Composable
fun btTimeAgoLabel(age: BtTimeAgo): String {
    val res = btTimeAgoRes(age)
    val value = btTimeAgoValue(age)
    return if (value == null) stringResource(res) else stringResource(res, value)
}

/** The rendered compact stamp for a timestamp, bucketed against the live clock. */
@Composable
fun btTimeAgoLabel(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String =
    btTimeAgoLabel(btTimeAgo(epochMs, nowMs))
