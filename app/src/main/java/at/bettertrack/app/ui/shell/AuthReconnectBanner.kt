package at.bettertrack.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.theme.BtTheme

/**
 * "Reconnecting to your account" — the state that used to be a logout.
 *
 * The app can be online and still unable to renew its session: the origin
 * answers 5xx, the shared-IP rate limiter is in a cooldown, or a refresh went
 * out and its answer was lost so the token is quarantined
 * (`TokenManager.atRiskReplaySuppressed`). Every one of those used to end at the
 * login screen. They now keep the session and raise this line instead, with a
 * retry for the person who does not want to wait for the next attempt.
 *
 * Deliberately in the same slot and the same idiom as [OfflineBanner], and
 * mutually exclusive with it: offline is the more specific and more actionable
 * of the two, so it wins when both are true.
 */
@Composable
fun AuthReconnectBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val retryCd = stringResource(R.string.bt_auth_reconnecting_retry)
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = onRetry,
            color = bt.surface,
            contentColor = bt.textSecondary,
            modifier = Modifier.semantics { contentDescription = retryCd },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    text = stringResource(R.string.bt_auth_reconnecting),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = bt.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = bt.border)
    }
}
