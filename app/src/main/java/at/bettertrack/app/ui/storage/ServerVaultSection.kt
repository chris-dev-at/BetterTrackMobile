package at.bettertrack.app.ui.storage

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.DataHomeMedium
import at.bettertrack.app.vault.VaultSyncStatus
import at.bettertrack.app.vault.server.ServerMediumStatus
import at.bettertrack.app.vault.server.ServerVaultAbsence
import at.bettertrack.app.vault.server.ServerVaultAdoptionResult
import at.bettertrack.app.vault.server.ServerVaultHistoryEntry
import at.bettertrack.app.vault.server.ServerVaultHistoryResult
import kotlinx.coroutines.launch

/**
 * The BetterTrack medium's row in "Where your data lives" (S5).
 *
 * ## Why this is a section and not another line in the sync chip
 *
 * With a media set, "is my data safe?" stops having one answer. "Backed up to
 * Drive · 2 min ago" and "BetterTrack · sign out and back in" are two
 * independent facts, and any design that renders one sentence for two places has
 * to silently pick a winner — which means the user is sometimes reassured about
 * a copy that is actually stale. So each medium gets a row, and the chip above
 * keeps showing the honest floor across all of them.
 *
 * ## Absence is not failure
 *
 * Most accounts will render [ServerMediumStatus.NoServerVault] forever, and that
 * is correct, not broken: a normal BetterTrack account keeps its portfolio on
 * the server in the ordinary way and has no vault at all. The copy says so in
 * plain language and points at where the setting lives (the web app), rather
 * than showing an error with a retry that could never succeed.
 */
@Composable
fun ServerVaultSection() {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScopeCompat()
    val status by AppGraph.serverVaultConnection.status.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // Resolve once on entry; the screen is where a user comes to find out.
    LaunchedEffect(Unit) { AppGraph.serverVaultConnection.refresh() }

    Surface(
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (status is ServerMediumStatus.Connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = if (status is ServerMediumStatus.Connected) bt.gain else bt.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.bt_server_vault_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = bt.textPrimary,
                    )
                    Text(
                        text = stringResource(status.sentenceRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textSecondary,
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = bt.gold,
                    )
                }
            }

            // The one actionable failure, and the one act that fixes it. A retry
            // button here would be a lie: the token is what is wrong, not the call.
            if (status is ServerMediumStatus.ScopeMissing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bt_server_vault_scope_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }

            if (status is ServerMediumStatus.Connected) {
                Spacer(Modifier.height(12.dp))
                BtSecondaryButton(
                    text = stringResource(R.string.bt_server_vault_history),
                    onClick = { showHistory = !showHistory },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
                if (showHistory) {
                    Spacer(Modifier.height(12.dp))
                    ServerVaultHistoryList()
                }
            } else {
                Spacer(Modifier.height(12.dp))
                BtSecondaryButton(
                    text = stringResource(R.string.bt_server_vault_check_again),
                    onClick = {
                        refreshing = true
                        scope.launch {
                            AppGraph.serverVaultConnection.refresh()
                            refreshing = false
                        }
                    },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
            }
        }
    }
}

/**
 * The restore net — earlier versions BetterTrack still retains.
 *
 * Read-only on purpose in this cut. Listing what can be recovered is what turns
 * "my vault is damaged" from a dead end into a survivable event, and it is
 * honest about scope: the server keeps only *superseded* versions (the current
 * one is never in the list — confirmed live), so an untouched vault legitimately
 * shows nothing.
 */
@Composable
private fun ServerVaultHistoryList() {
    val bt = BtTheme.colors
    var result by remember { mutableStateOf<ServerVaultHistoryResult?>(null) }

    LaunchedEffect(Unit) { result = AppGraph.serverVaultHistory() }

    when (val current = result) {
        null -> Text(
            text = stringResource(R.string.bt_server_vault_history_loading),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )

        is ServerVaultHistoryResult.ModeRequired -> Text(
            text = stringResource(R.string.bt_server_vault_history_mode),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )

        is ServerVaultHistoryResult.Failure -> Text(
            text = stringResource(R.string.bt_server_vault_history_failed),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )

        is ServerVaultHistoryResult.Ok -> if (current.items.isEmpty()) {
            Text(
                text = stringResource(R.string.bt_server_vault_history_empty),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                current.items.forEach { entry -> HistoryRow(entry) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ServerVaultHistoryEntry) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.bt_server_vault_history_version, entry.version),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textPrimary,
            modifier = Modifier.weight(1f),
        )
        entry.createdAt?.let { created ->
            Text(
                // The ISO date's day part only — a version list is about "which
                // one", not about the millisecond it was written.
                text = created.take(10),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

/**
 * **The paranoid payoff, as an offer.**
 *
 * Shown to an account that lives in paranoid mode on a device that cannot see
 * its portfolio. Before S5 that user's only truthful screen was "open the web
 * app"; this card is the way in — the same passphrase they set in the browser,
 * and their holdings render on the phone.
 *
 * Every refusal below is deliberately specific. "Wrong passphrase" invites a
 * retry; "sign out and back in" is a different act entirely; and an account that
 * simply is not paranoid must be told that rather than left typing passphrases
 * at a vault that does not exist.
 */
@Composable
fun ServerVaultSetupCard(onAdopted: () -> Unit = {}) {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScopeCompat()
    var passphrase by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ServerVaultAdoptionResult?>(null) }

    Surface(
        color = bt.surface,
        border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.4f)),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = bt.gold,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.bt_server_vault_setup_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bt_server_vault_setup_body),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            BtTextField(
                value = passphrase,
                onValueChange = { passphrase = it; outcome = null },
                label = stringResource(R.string.bt_storage_pass_label),
                isPassword = true,
                isError = outcome is ServerVaultAdoptionResult.WrongPassphrase,
                enabled = !working,
                imeAction = ImeAction.Done,
                // The distinction is a security property, not an inconvenience.
                supportingText = stringResource(R.string.bt_server_vault_setup_pass_hint),
            )

            outcome?.let { result ->
                if (result !is ServerVaultAdoptionResult.Adopted) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(result.sentenceRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.loss,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            BtPrimaryButton(
                text = stringResource(R.string.bt_server_vault_setup_action),
                onClick = {
                    working = true
                    scope.launch {
                        val result = AppGraph.serverVaultAdoption.adopt(passphrase)
                        outcome = result
                        working = false
                        if (result is ServerVaultAdoptionResult.Adopted) {
                            passphrase = ""
                            // The medium's disposition just changed under us.
                            AppGraph.serverVaultConnection.invalidate()
                            onAdopted()
                        }
                    }
                },
                enabled = !working && passphrase.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

// ── Sentence mapping (EN+DE via string resources) ───────────────────────────

/** The nine-status sentence mapping from W5, extended to one row per medium. */
internal fun VaultSyncStatus.mediumSentenceRes(medium: DataHomeMedium): Int = when (this) {
    VaultSyncStatus.SYNCED -> when (medium) {
        DataHomeMedium.SERVER -> R.string.bt_medium_synced_server
        else -> R.string.bt_vault_sync_synced
    }

    VaultSyncStatus.SIGN_IN_REQUIRED -> when (medium) {
        DataHomeMedium.SERVER -> R.string.bt_medium_sign_in_server
        else -> R.string.bt_vault_sync_sign_in
    }

    VaultSyncStatus.SYNCING -> R.string.bt_vault_sync_syncing
    VaultSyncStatus.SAVED_LOCALLY -> R.string.bt_vault_sync_saved_locally
    VaultSyncStatus.QUOTA_FULL -> R.string.bt_vault_sync_quota
    VaultSyncStatus.OFFLINE -> R.string.bt_vault_sync_offline
    VaultSyncStatus.LOCKED -> R.string.bt_vault_sync_locked
    VaultSyncStatus.NEEDS_ATTENTION -> R.string.bt_vault_sync_attention
    VaultSyncStatus.IDLE -> R.string.bt_vault_sync_idle
}

internal fun ServerMediumStatus.sentenceRes(): Int = when (this) {
    is ServerMediumStatus.Connected -> R.string.bt_medium_synced_server
    is ServerMediumStatus.ScopeMissing -> R.string.bt_medium_sign_in_server
    is ServerMediumStatus.NotSignedIn -> R.string.bt_server_vault_signed_out
    is ServerMediumStatus.Unreachable -> R.string.bt_server_vault_unreachable
    is ServerMediumStatus.Unreadable -> R.string.bt_server_vault_unreadable
    is ServerMediumStatus.Unknown -> R.string.bt_server_vault_checking
    is ServerMediumStatus.NoServerVault -> when (reason) {
        ServerVaultAbsence.ACCOUNT_IS_NORMAL -> R.string.bt_server_vault_absent_normal
        ServerVaultAbsence.DRIVE_ONLY_VAULT -> R.string.bt_server_vault_absent_drive_only
        ServerVaultAbsence.NO_BYTES_YET -> R.string.bt_server_vault_absent_pending
        ServerVaultAbsence.UNKNOWN -> R.string.bt_server_vault_absent_unknown
    }
}

internal fun ServerVaultAdoptionResult.sentenceRes(): Int = when (this) {
    is ServerVaultAdoptionResult.Adopted -> R.string.bt_server_vault_adopted
    is ServerVaultAdoptionResult.WrongPassphrase -> R.string.bt_server_vault_wrong_pass
    is ServerVaultAdoptionResult.NotSignedIn -> R.string.bt_server_vault_signed_out
    is ServerVaultAdoptionResult.ScopeMissing -> R.string.bt_medium_sign_in_server
    is ServerVaultAdoptionResult.UpdateRequired -> R.string.bt_server_vault_update_required
    is ServerVaultAdoptionResult.Unreadable -> R.string.bt_server_vault_unreadable
    is ServerVaultAdoptionResult.Offline -> R.string.bt_vault_sync_offline
    is ServerVaultAdoptionResult.Failed -> R.string.bt_server_vault_failed
    is ServerVaultAdoptionResult.Absent -> when (reason) {
        ServerVaultAbsence.ACCOUNT_IS_NORMAL -> R.string.bt_server_vault_absent_normal
        ServerVaultAbsence.DRIVE_ONLY_VAULT -> R.string.bt_server_vault_absent_drive_only
        ServerVaultAbsence.NO_BYTES_YET -> R.string.bt_server_vault_absent_pending
        ServerVaultAbsence.UNKNOWN -> R.string.bt_server_vault_absent_unknown
    }
}

/** Local alias so this file does not import the whole runtime surface twice. */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
