package at.bettertrack.app.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.update.AvailableUpdate
import at.bettertrack.app.data.update.UpdateChecker
import at.bettertrack.app.data.update.UpdateInstallState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/** Open a web URL in the browser (no Activity result needed). */
fun openUrlInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Deep-link to this app's "install unknown apps" permission screen (API 26+). */
fun openInstallUnknownAppsSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Hosts the dev-update dialog as an app-level overlay. It starts as a one-per-
 * version OFFER (Download & Install / Go to GitHub / Remind me later / Ignore) and,
 * once the user starts an in-app install, swaps its content to live download
 * progress → system-installer hand-off → (on failure) Retry. Rendered from the root
 * so it shows regardless of auth state.
 */
@Composable
fun UpdateNotifierHost() {
    val checker = AppGraph.updateChecker
    val installer = AppGraph.updateInstaller
    val pending by checker.pendingDialog.collectAsStateWithLifecycle()
    val install by installer.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val visible = pending != null || install !is UpdateInstallState.Idle
    if (!visible) return

    // Scrim / back behaviour: never let it dismiss mid-download or mid-install.
    val onScrimDismiss: () -> Unit = when (install) {
        is UpdateInstallState.Downloading, is UpdateInstallState.Installing -> ({})
        is UpdateInstallState.Failed -> ({ installer.reset(); checker.remindLater() })
        UpdateInstallState.Idle -> ({ checker.remindLater() })
    }

    Dialog(onDismissRequest = onScrimDismiss) {
        BtCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val s = install) {
                    is UpdateInstallState.Downloading ->
                        DownloadingBody(s, onCancel = { installer.cancel() })

                    is UpdateInstallState.Installing ->
                        InstallingBody(onCancel = { installer.cancel() })

                    is UpdateInstallState.Failed ->
                        FailedBody(
                            state = s,
                            onRetry = { installer.retry() },
                            onGitHub = {
                                openUrlInBrowser(context, UpdateChecker.RELEASE_PAGE_URL)
                                installer.reset()
                                checker.dismissDialog()
                            },
                            onDismiss = { installer.reset(); checker.remindLater() },
                        )

                    UpdateInstallState.Idle -> {
                        val update = pending
                        if (update != null) {
                            OfferBody(
                                update = update,
                                onDownload = { installer.start(update) },
                                onGitHub = {
                                    openUrlInBrowser(context, UpdateChecker.RELEASE_PAGE_URL)
                                    checker.dismissDialog()
                                },
                                onRemind = { checker.remindLater() },
                                onIgnore = { checker.ignorePending() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Initial actions: Download & Install (primary) ▸ Go to GitHub ▸ later / ignore. */
@Composable
private fun OfferBody(
    update: AvailableUpdate,
    onDownload: () -> Unit,
    onGitHub: () -> Unit,
    onRemind: () -> Unit,
    onIgnore: () -> Unit,
) {
    val bt = BtTheme.colors
    Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = bt.gold, modifier = Modifier.size(36.dp))
    Text(
        text = stringResource(R.string.bt_update_dialog_title),
        style = MaterialTheme.typography.titleLarge,
        color = bt.textPrimary,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.bt_update_dialog_message, update.versionName),
        style = MaterialTheme.typography.bodyMedium,
        color = bt.textSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    BtPrimaryButton(
        text = stringResource(R.string.bt_update_download_install),
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    )
    BtSecondaryButton(
        text = stringResource(R.string.bt_update_go_github),
        onClick = onGitHub,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    )
    // Tertiary actions side by side (owner ask 2026-07-12): remind ▸ ignore.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onRemind,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.bt_update_remind_later),
                color = bt.textSecondary,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
        TextButton(
            onClick = onIgnore,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.bt_update_ignore),
                color = bt.textMuted,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Live download: determinate % (or indeterminate) + install-permission hint. */
@Composable
private fun DownloadingBody(state: UpdateInstallState.Downloading, onCancel: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val canInstall = rememberCanInstallPackages()

    Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, tint = bt.gold, modifier = Modifier.size(36.dp))
    Text(
        text = if (state.percent == null) {
            stringResource(R.string.bt_update_preparing)
        } else {
            stringResource(R.string.bt_update_downloading, state.versionName)
        },
        style = MaterialTheme.typography.titleMedium,
        color = bt.textPrimary,
        textAlign = TextAlign.Center,
    )
    val fraction = state.percent?.let { it / 100f }
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = bt.gold,
            trackColor = bt.border,
        )
        Text(
            text = "${state.percent}%",
            style = MaterialTheme.typography.titleMedium,
            color = bt.textSecondary,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = bt.gold,
            trackColor = bt.border,
        )
    }
    InstallPermissionHint(canInstall = canInstall, onOpenSettings = { openInstallUnknownAppsSettings(context) })
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bt_update_cancel), color = bt.textMuted)
    }
}

/**
 * APK written; the system confirm sheet is up. Keep the hint reachable, and keep a
 * Cancel affordance so the dialog is never a dead-end if the confirm sheet can't
 * show yet (e.g. the source permission is still being granted).
 */
@Composable
private fun InstallingBody(onCancel: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val canInstall = rememberCanInstallPackages()

    Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, tint = bt.gold, modifier = Modifier.size(36.dp))
    Text(
        text = stringResource(R.string.bt_update_installing),
        style = MaterialTheme.typography.titleMedium,
        color = bt.textPrimary,
        textAlign = TextAlign.Center,
    )
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = bt.gold,
        trackColor = bt.border,
    )
    InstallPermissionHint(canInstall = canInstall, onOpenSettings = { openInstallUnknownAppsSettings(context) })
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bt_update_cancel), color = bt.textMuted)
    }
}

/** Download/install failed or aborted: Retry (primary) ▸ Go to GitHub ▸ dismiss. */
@Composable
private fun FailedBody(
    state: UpdateInstallState.Failed,
    onRetry: () -> Unit,
    onGitHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = bt.loss, modifier = Modifier.size(36.dp))
    Text(
        text = stringResource(R.string.bt_update_error),
        style = MaterialTheme.typography.titleMedium,
        color = bt.textPrimary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    BtPrimaryButton(
        text = stringResource(R.string.bt_update_retry),
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    )
    BtSecondaryButton(
        text = stringResource(R.string.bt_update_go_github),
        onClick = onGitHub,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    )
    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bt_update_dismiss), color = bt.textMuted)
    }
}

/**
 * The "install unknown apps" hint. When the permission is missing it is prominent
 * (gold-tinted card + a deep-link button to grant it); once granted it collapses
 * to a subtle one-line reassurance.
 */
@Composable
private fun InstallPermissionHint(canInstall: Boolean, onOpenSettings: () -> Unit) {
    val bt = BtTheme.colors
    if (canInstall) {
        Text(
            text = stringResource(R.string.bt_update_install_hint_ok),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Surface(
            color = bt.goldSurface,
            border = BorderStroke(1.dp, bt.goldSurfaceStrong),
            shape = BtShapes.card,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = bt.goldSoft, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.bt_update_install_hint_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = bt.textPrimary,
                    )
                }
                Text(
                    text = stringResource(R.string.bt_update_install_hint_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
                BtSecondaryButton(
                    text = stringResource(R.string.bt_update_install_hint_action),
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
            }
        }
    }
}

/** Whether the app may request package installs, re-read on every ON_RESUME. */
@Composable
private fun rememberCanInstallPackages(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = context.packageManager.canRequestPackageInstalls()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return canInstall
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
