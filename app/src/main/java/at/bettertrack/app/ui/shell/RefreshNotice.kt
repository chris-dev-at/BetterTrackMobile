package at.bettertrack.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One pull-to-refresh attempt, with the three guarantees the *gesture* depends
 * on — not the data, which is each screen's own business.
 *
 * ## Why the five refresh screens could not keep doing this by hand
 *
 * Every one of them had grown the same four lines: set a flag, await the repo,
 * unset the flag. That reads as obviously correct and is wrong offline in three
 * separate ways, all of which land on the sheet's dismiss gesture rather than on
 * the data:
 *
 *  1. **The flag could be stranded.** `_refreshing = true` … `_refreshing =
 *     false` on a straight line has no `finally`, so a throw or a cancelled
 *     `viewModelScope` between them leaves the indicator up forever. That is not
 *     cosmetic: Material3's `consumeAvailableOffset` returns **zero** while
 *     `isRefreshing` is true, so a stuck indicator hands every subsequent pull
 *     straight up to [BtSheet]'s connection — the sheet starts sliding toward
 *     dismissal every time the user tries to refresh. A stuck spinner and "the
 *     page closes when I pull" are the same bug.
 *  2. **The attempt was unbounded.** The authenticated OkHttp client sets no
 *     `callTimeout` (connect 20 s, read 30 s), `AuthInterceptor` may prepend a
 *     doomed token refresh that pays its own connect timeout, and two of the
 *     five screens fire two calls in sequence. Against a host that accepts
 *     nothing and refuses nothing, the indicator could stay up for the better
 *     part of a minute — with (1)'s consequence live the whole time.
 *  3. **The attempt could be invisible.** With the radio off the call fails in
 *     single-digit milliseconds, so `true` and `false` land in the same
 *     `StateFlow` before the collector is ever resumed. The value is conflated,
 *     the indicator never renders a frame, and the pull reads as having done
 *     nothing at all. This is the owner's *"offline the spinners behave
 *     differently"*, exactly.
 *
 * So the floor and the ceiling are both here, once, and the five screens keep
 * only the part that differs: what to do with the outcome.
 *
 * @param refreshing the screen's own indicator flag. Held true for the whole
 *   attempt and retired in a `finally`, so no path — return, throw, timeout or
 *   cancellation — can leave it up.
 * @param timeoutMs the ceiling. `null` is returned when it is reached; callers
 *   treat that exactly as a transport failure, because that is what it is.
 * @param minVisibleMs the floor, so a failure the network refuses in 3 ms still
 *   *looks* like an attempt. Runs concurrently with [attempt], so it costs a
 *   refresh that takes longer than the floor nothing at all.
 * @return the attempt's own value, or `null` if [timeoutMs] was reached first.
 */
suspend fun <T> btRefreshAttempt(
    refreshing: MutableStateFlow<Boolean>,
    timeoutMs: Long = BT_REFRESH_TIMEOUT_MS,
    minVisibleMs: Long = BT_REFRESH_MIN_VISIBLE_MS,
    attempt: suspend () -> T,
): T? {
    refreshing.value = true
    return try {
        coroutineScope {
            val floor = launch { delay(minVisibleMs) }
            val outcome = withTimeoutOrNull(timeoutMs) { attempt() }
            floor.join()
            outcome
        }
    } finally {
        refreshing.value = false
    }
}

/**
 * What a refresh that reached [BT_REFRESH_TIMEOUT_MS] tells the user.
 *
 * The same sentence a refused connection gets, and deliberately so: a request
 * the app gave up waiting for and a request the network refused are one event
 * to the person holding the phone, and inventing a second copy string for the
 * distinction would only ask them to care about it.
 */
fun btRefreshTimedOutMessage(): BtMessage =
    BtApiError(httpStatus = 0, code = BtApiError.Codes.NETWORK).asMessage()

/**
 * The ceiling on one pull-to-refresh.
 *
 * Chosen against the transport rather than against a feeling: a healthy read on
 * this API is well under two seconds, while the offline worst case (a doomed
 * proactive token refresh, then two sequential doomed calls, each with a 20 s
 * connect timeout and no `callTimeout` above them) runs far past this. So this
 * cuts off only journeys that were already lost, and the user gets a retry
 * rather than an indicator that never ends.
 *
 * Deliberately NOT an OkHttp `callTimeout`: that would put a ceiling on every
 * request the app makes, including uploads and the sync drain, to fix a problem
 * that belongs to one gesture.
 */
const val BT_REFRESH_TIMEOUT_MS: Long = 25_000L

/**
 * The floor on one pull-to-refresh indicator.
 *
 * Short enough to be invisible on a healthy refresh (which takes longer than
 * this anyway, so the floor never fires) and long enough that a refusal the
 * radio produces in 3 ms still renders as a spinner that came and went. Without
 * it the offline gesture's entire feedback is that nothing happened.
 */
const val BT_REFRESH_MIN_VISIBLE_MS: Long = 320L

/**
 * State of the inline "couldn't refresh" notice (S6 P0-5).
 *
 * A failed refresh used to be `is BtResult.Err -> Unit` with a comment claiming
 * the offline banner explained it — but that banner only renders on top-level
 * routes, so on a pushed route (Transactions) a failed pull-to-refresh was
 * completely silent: the spinner stopped, nothing changed, and the stale rows
 * looked freshly loaded.
 *
 * Kept as a plain value type so the whole rule is unit-testable without Compose
 * or a ViewModel harness.
 */
data class RefreshNoticeState(
    /** The most recent refresh/loadMore attempt failed. */
    val failed: Boolean = false,
    /** The user dismissed the notice for THIS failure. */
    val dismissed: Boolean = false,
) {
    /** A refresh landed: the cached rows are current again, so the notice goes. */
    fun onSuccess(): RefreshNoticeState = RefreshNoticeState(failed = false, dismissed = false)

    /**
     * A refresh failed. A NEW failure re-arms the notice even if the previous
     * one was dismissed — the user dismissed a message about an older attempt.
     */
    fun onFailure(): RefreshNoticeState = RefreshNoticeState(failed = true, dismissed = false)

    /** The user tapped the ✕. */
    fun onDismiss(): RefreshNoticeState = copy(dismissed = true)

    /**
     * Whether the row renders.
     *
     * Suppressed while offline: [OfflineBanner] is already on screen there and
     * says the same thing better ("showing data as of 14:32"). Two banners
     * stacked for one cause is noise, not honesty.
     */
    fun visible(isOnline: Boolean): Boolean = failed && !dismissed && isOnline
}

/**
 * "Couldn't refresh — showing saved data": the pushed-route counterpart of
 * [OfflineBanner], in the same visual language (full-bleed surface strip, 16 dp
 * icon, bodySmall text, hairline divider) so it reads as the same system rather
 * than a new idiom. Dismissible; optionally offers a retry.
 */
@Composable
fun RefreshFailedBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(color = bt.surface, contentColor = bt.textSecondary) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SyncProblem,
                    contentDescription = null,
                    tint = bt.goldEmphasis,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.bt_refresh_failed_banner),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (onRetry != null) {
                    TextButton(onClick = onRetry) {
                        Text(
                            text = stringResource(R.string.bt_action_retry),
                            style = MaterialTheme.typography.labelLarge,
                            color = bt.goldInk,
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.bt_refresh_failed_dismiss),
                        tint = bt.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = bt.border)
    }
}
