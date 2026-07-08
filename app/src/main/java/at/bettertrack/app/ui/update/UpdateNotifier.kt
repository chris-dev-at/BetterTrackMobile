package at.bettertrack.app.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.update.AvailableUpdate
import at.bettertrack.app.data.update.UpdateChecker
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtTheme

/** Open a web URL in the browser (no Activity result needed). */
fun openUrlInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Hosts the dev-update dialog (Step V) as an app-level overlay: when a check
 * finds a newer build (and it hasn't been ignored/snoozed), one prompt appears
 * with Go to GitHub / Remind me later / Ignore this version. Rendered from the
 * root so it shows regardless of auth state.
 */
@Composable
fun UpdateNotifierHost() {
    val checker = AppGraph.updateChecker
    val pending by checker.pendingDialog.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bt = BtTheme.colors

    pending?.let { update ->
        Dialog(onDismissRequest = { checker.remindLater() }) {
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = bt.gold,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = stringResource(R.string.bt_update_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = bt.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.bt_update_dialog_message, update.versionName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_update_go_github),
                        onClick = {
                            openUrlInBrowser(context, UpdateChecker.RELEASE_PAGE_URL)
                            checker.dismissDialog()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
                    BtSecondaryButton(
                        text = stringResource(R.string.bt_update_remind_later),
                        onClick = { checker.remindLater() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
                    TextButton(
                        onClick = { checker.ignorePending() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.bt_update_ignore),
                            color = bt.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The persistent "Update available" settings row (Step V) — gold badge + the new
 * version, taps through to the GitHub release. Rendered by Settings whenever a
 * newer build exists.
 */
@Composable
fun UpdateAvailableRow(update: AvailableUpdate) {
    val context = LocalContext.current
    val bt = BtTheme.colors
    BtCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { openUrlInBrowser(context, UpdateChecker.RELEASE_PAGE_URL) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.SystemUpdateAlt,
                contentDescription = null,
                tint = bt.gold,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bt_update_badge_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                )
                Text(
                    text = stringResource(R.string.bt_update_badge_version, update.versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            BtBadge(text = stringResource(R.string.bt_update_badge_pill), kind = BtBadgeKind.Gold)
        }
    }
}
