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
import at.bettertrack.app.ui.theme.BtTheme

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
