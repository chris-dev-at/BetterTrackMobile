package at.bettertrack.app.ui.storage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.StorageTransition
import at.bettertrack.app.data.storage.SwitchResult
import at.bettertrack.app.data.storage.SwitchWarning
import at.bettertrack.app.data.storage.TransitionBlocker
import at.bettertrack.app.data.storage.TransitionOutcome
import at.bettertrack.app.data.storage.availableTransitions
import at.bettertrack.app.data.storage.effective
import at.bettertrack.app.data.storage.evaluateTransition
import at.bettertrack.app.data.storage.holdsVault
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.VaultSyncStatus
import kotlinx.coroutines.launch

/**
 * Settings → **Where your data lives** (S3/S4 plan §4.2 step 5).
 *
 * One screen that answers, in this order, the four questions a user actually has
 * about their storage: *where is it*, *is the backup current*, *what can I do
 * with the key*, and *can I change my mind*. The destructive device actions sit
 * last, behind their own section and their own type-to-confirm — the same shape
 * `DeleteAccountScreen` uses, because it is the same class of decision.
 *
 * Nothing here reports a success it did not achieve. A transition whose
 * prerequisite is missing renders the reason inline and changes nothing; a Drive
 * delete that failed says so rather than quietly leaving the file behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhereYourDataLivesScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    var section by remember { mutableStateOf(DataHomeSection.MAIN) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            when (section) {
                                DataHomeSection.MAIN -> R.string.bt_storage_settings_row
                                DataHomeSection.REKEY -> R.string.bt_vault_rekey_title
                                DataHomeSection.DELETE -> R.string.bt_storage_delete_everything
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (section == DataHomeSection.MAIN) onBack() else section = DataHomeSection.MAIN
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = if (section == DataHomeSection.DELETE) bt.loss else bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                DataHomeSection.MAIN -> MainSection(
                    onOpenRekey = { section = DataHomeSection.REKEY },
                    onOpenDelete = { section = DataHomeSection.DELETE },
                )

                DataHomeSection.REKEY -> RekeySection(onDone = { section = DataHomeSection.MAIN })
                DataHomeSection.DELETE -> DeleteEverythingSection(onDone = onBack)
            }
        }
    }
}

private enum class DataHomeSection { MAIN, REKEY, DELETE }

@Composable
private fun MainSection(onOpenRekey: () -> Unit, onOpenDelete: () -> Unit) {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScope()
    val storedMode by AppGraph.storageModeStore.mode.collectAsStateWithLifecycle()
    // Gated, like every other mode read in the UI: this screen must describe the
    // mode the app is REALLY running, never the one a stale pref claims.
    val effective = AppGraph.gatedStorageMode(storedMode).effective

    // ── Where it lives ──────────────────────────────────────────────────────
    ModeCard(effective)

    if (effective == StorageMode.DRIVE) {
        Text(
            text = stringResource(R.string.bt_storage_drive_absent_note),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // ── Backup status (only modes that actually have a vault) ───────────────
    if (effective.holdsVault) {
        SectionLabel(stringResource(R.string.bt_storage_section_backup))
        VaultSyncCard()

        SectionLabel(stringResource(R.string.bt_storage_section_vault))
        StorageNavRow(
            icon = Icons.Outlined.Key,
            title = stringResource(R.string.bt_vault_change_pass),
            subtitle = stringResource(R.string.bt_vault_change_pass_sub),
            onClick = onOpenRekey,
        )
        NewRecoveryKitRow()
        LockVaultRow()
    }

    // ── Change where it lives ───────────────────────────────────────────────
    val transitions = availableTransitions(effective)
    if (transitions.isNotEmpty()) {
        SectionLabel(stringResource(R.string.bt_storage_section_change))
        var message by remember { mutableStateOf<Int?>(null) }
        for (transition in transitions) {
            TransitionRow(
                transition = transition,
                onResult = { result ->
                    message = when (result) {
                        is SwitchResult.Blocked -> result.blocker.messageRes()
                        is SwitchResult.Partial ->
                            if (result.warning == SwitchWarning.REMOTE_VAULT_NOT_DELETED) {
                                R.string.bt_storage_remote_delete_failed
                            } else {
                                R.string.bt_storage_switch_done
                            }

                        is SwitchResult.Applied -> R.string.bt_storage_switch_done
                        is SwitchResult.NeedsFlow -> result.transition.flowUnavailableMessage()
                    }
                },
                scope = scope,
            )
        }
        message?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // ── This device (plan §4.4 row 1: Drive mode has no "log out") ──────────
    if (effective == StorageMode.DRIVE) {
        SectionLabel(stringResource(R.string.bt_storage_section_device))
        DisconnectDriveRow()
        Surface(
            onClick = onOpenDelete,
            color = bt.surface,
            border = BorderStroke(1.dp, bt.loss.copy(alpha = 0.35f)),
            shape = BtShapes.card,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = bt.loss, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.bt_storage_delete_everything),
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.loss,
                    )
                    Text(
                        stringResource(R.string.bt_storage_delete_everything_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ModeCard(mode: StorageMode) {
    val bt = BtTheme.colors
    Surface(
        color = bt.gold.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.35f)),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.bt_storage_settings_row).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(mode.labelRes()),
                style = MaterialTheme.typography.titleMedium,
                color = bt.gold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(mode.explainerRes()),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
        }
    }
}

/**
 * The vault-sync chip's full-size sibling (plan §1.2).
 *
 * Every status maps to a sentence that tells the user where their data is *right
 * now* — never a spinner that could mean either "working" or "stuck". The
 * signed-out-Google case is its own line rather than a generic error, because
 * with the placeholder auth provider it is the normal state of this build and
 * calling it an error would be wrong.
 */
@Composable
private fun VaultSyncCard() {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScope()
    val coordinator = AppGraph.vaultSyncCoordinator
    val state by (coordinator?.state ?: AppGraph.emptyVaultSyncState).collectAsStateWithLifecycle()
    var syncing by remember { mutableStateOf(false) }
    val googleConnected = AppGraph.isGoogleConnected

    val statusRes = when {
        !googleConnected && state.status != VaultSyncStatus.SYNCED -> R.string.bt_vault_sync_pending_google
        else -> when (state.status) {
            VaultSyncStatus.IDLE -> R.string.bt_vault_sync_idle
            VaultSyncStatus.SYNCING -> R.string.bt_vault_sync_syncing
            VaultSyncStatus.SYNCED -> R.string.bt_vault_sync_synced
            VaultSyncStatus.SAVED_LOCALLY -> R.string.bt_vault_sync_saved_locally
            VaultSyncStatus.SIGN_IN_REQUIRED -> R.string.bt_vault_sync_sign_in
            VaultSyncStatus.QUOTA_FULL -> R.string.bt_vault_sync_quota
            VaultSyncStatus.OFFLINE -> R.string.bt_vault_sync_offline
            VaultSyncStatus.LOCKED -> R.string.bt_vault_sync_locked
            VaultSyncStatus.NEEDS_ATTENTION -> R.string.bt_vault_sync_attention
        }
    }
    val tint = when (state.status) {
        VaultSyncStatus.SYNCED -> bt.gain
        VaultSyncStatus.NEEDS_ATTENTION, VaultSyncStatus.QUOTA_FULL -> bt.loss
        else -> bt.textSecondary
    }

    Surface(
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.status == VaultSyncStatus.SYNCED) Icons.Outlined.CloudQueue else Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(statusRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = bt.gold,
                    )
                }
            }
            if (state.hasUnpushedChanges) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bt_vault_sync_unpushed),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            if (coordinator != null) {
                Spacer(Modifier.height(12.dp))
                BtSecondaryButton(
                    text = stringResource(R.string.bt_storage_sync_now),
                    onClick = {
                        syncing = true
                        scope.launch {
                            coordinator.pushNow()
                            syncing = false
                        }
                    },
                    enabled = !syncing,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
            }
        }
    }
}

@Composable
private fun NewRecoveryKitRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var kit by remember { mutableStateOf<at.bettertrack.app.vault.RecoveryKitDownload?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val bytes = kit?.bytes ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        }
        kit = null
    }
    var lockedNotice by remember { mutableStateOf(false) }
    StorageNavRow(
        icon = Icons.Outlined.Key,
        title = stringResource(R.string.bt_vault_new_kit),
        subtitle = stringResource(R.string.bt_vault_new_kit_sub),
        onClick = {
            // Only an UNLOCKED vault can export a kit — the kit *is* the key.
            val produced = AppGraph.vaultKeyCustody.recoveryKit()
            if (produced != null) {
                lockedNotice = false
                kit = produced
                launcher.launch(produced.filename)
            } else {
                // A locked vault cannot hand out its key. Saying so beats a tap
                // that appears to do nothing at all.
                lockedNotice = true
            }
        },
    )
    if (lockedNotice) {
        Text(
            text = stringResource(R.string.bt_storage_blocked_unlock),
            style = MaterialTheme.typography.bodySmall,
            color = BtTheme.colors.goldSoft,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun LockVaultRow() {
    StorageNavRow(
        icon = Icons.Outlined.Lock,
        title = stringResource(R.string.bt_vault_lock_action),
        subtitle = stringResource(R.string.bt_vault_lock_sub),
        onClick = { AppGraph.vaultKeyCustody.lock() },
    )
}

@Composable
private fun DisconnectDriveRow() {
    StorageNavRow(
        icon = Icons.Outlined.CloudOff,
        title = stringResource(R.string.bt_storage_disconnect_drive),
        subtitle = stringResource(R.string.bt_storage_disconnect_drive_sub),
        onClick = { AppGraph.disconnectGoogle() },
    )
}

@Composable
private fun TransitionRow(
    transition: StorageTransition,
    onResult: (SwitchResult) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val bt = BtTheme.colors
    val outcome = evaluateTransition(transition, AppGraph.transitionCapabilities())
    val blocker = (outcome as? TransitionOutcome.Blocked)?.blocker
    Surface(
        onClick = { scope.launch { onResult(AppGraph.storageModeSwitcher.apply(transition)) } },
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(transition.titleRes()),
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(transition.subtitleRes()),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            if (blocker != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(blocker.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.goldSoft,
                )
            }
        }
    }
}

// ── Change passphrase ───────────────────────────────────────────────────────

@Composable
private fun RekeySection(onDone: () -> Unit) {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var isError by remember { mutableStateOf(false) }

    val ready = current.isNotEmpty() && passphrasePairAccepted(next, confirm) && !working

    WizardNote(title = null, body = stringResource(R.string.bt_vault_rekey_body))
    BtTextField(
        value = current,
        onValueChange = { current = it; message = null },
        label = stringResource(R.string.bt_vault_rekey_current),
        isPassword = true,
        imeAction = ImeAction.Next,
    )
    BtTextField(
        value = next,
        onValueChange = { next = it; message = null },
        label = stringResource(R.string.bt_vault_rekey_new),
        isPassword = true,
        isError = next.isNotEmpty() && passphraseStrength(next) == PassphraseStrength.TOO_SHORT,
        imeAction = ImeAction.Next,
        supportingText = if (next.isNotEmpty() && passphraseStrength(next) == PassphraseStrength.TOO_SHORT) {
            stringResource(R.string.bt_storage_pass_too_short, MIN_PASSPHRASE_LENGTH)
        } else {
            null
        },
    )
    BtTextField(
        value = confirm,
        onValueChange = { confirm = it; message = null },
        label = stringResource(R.string.bt_vault_rekey_confirm),
        isPassword = true,
        isError = confirm.isNotEmpty() && confirm != next,
        imeAction = ImeAction.Done,
        supportingText = if (confirm.isNotEmpty() && confirm != next) {
            stringResource(R.string.bt_storage_pass_mismatch)
        } else {
            null
        },
    )
    message?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) bt.loss else bt.gain,
        )
    }
    BtPrimaryButton(
        text = stringResource(R.string.bt_vault_change_pass),
        onClick = {
            working = true
            message = null
            scope.launch {
                val ok = AppGraph.vaultKeyCustody.changePassphrase(current, next)
                if (ok) {
                    // Push immediately: until the envelope carries the new
                    // wrapper, the copy at rest still opens with the old
                    // passphrase — true but surprising, so close the gap now.
                    AppGraph.vaultSyncCoordinator?.pushNow()
                }
                working = false
                isError = !ok
                message = if (ok) R.string.bt_vault_rekey_done else R.string.bt_vault_rekey_wrong
                if (ok) onDone()
            }
        },
        enabled = ready,
        loading = working,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    )
}

// ── Delete everything on this device (plan §4.4 row 1) ──────────────────────

@Composable
private fun DeleteEverythingSection(onDone: () -> Unit) {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScope()
    val word = stringResource(R.string.bt_storage_delete_confirm_word)
    var typed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val matches = typed.trim() == word

    Surface(
        color = bt.lossSurface,
        border = BorderStroke(1.dp, bt.loss.copy(alpha = 0.4f)),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = bt.loss, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.bt_storage_delete_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.loss,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.bt_storage_delete_warning_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
            }
        }
    }

    Text(
        text = stringResource(R.string.bt_storage_delete_confirm_label, word),
        style = MaterialTheme.typography.bodyMedium,
        color = bt.textPrimary,
    )
    BtTextField(
        value = typed,
        onValueChange = { typed = it },
        label = word,
        isError = typed.isNotEmpty() && !matches,
        imeAction = ImeAction.Done,
        supportingText = if (typed.isNotEmpty() && !matches) {
            stringResource(R.string.bt_storage_delete_mismatch)
        } else {
            null
        },
    )
    Button(
        onClick = {
            working = true
            scope.launch {
                AppGraph.deleteEverythingOnThisDevice()
                working = false
                onDone()
            }
        },
        enabled = matches && !working,
        shape = BtShapes.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = bt.loss,
            contentColor = bt.bg,
            disabledContainerColor = bt.border,
            disabledContentColor = bt.textMuted,
        ),
        elevation = null,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.bt_storage_delete_action))
        }
    }
}

// ── Shared bits ─────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = BtTheme.colors.textMuted)
}

@Composable
private fun StorageNavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

// ── String mapping (kept next to the screen that renders it) ────────────────

internal fun StorageMode.labelRes(): Int = when (effective) {
    StorageMode.DRIVE -> R.string.bt_storage_mode_drive
    StorageMode.BOTH -> R.string.bt_storage_mode_both
    else -> R.string.bt_storage_mode_server
}

private fun StorageMode.explainerRes(): Int = when (effective) {
    StorageMode.DRIVE -> R.string.bt_storage_choose_drive_body
    StorageMode.BOTH -> R.string.bt_storage_choose_both_body
    else -> R.string.bt_storage_choose_server_body
}

private fun StorageTransition.titleRes(): Int = when (this) {
    StorageTransition.SERVER_TO_BOTH -> R.string.bt_storage_tr_server_to_both
    StorageTransition.DRIVE_TO_BOTH -> R.string.bt_storage_tr_drive_to_both
    StorageTransition.BOTH_TO_DRIVE -> R.string.bt_storage_tr_both_to_drive
    StorageTransition.BOTH_TO_SERVER -> R.string.bt_storage_tr_both_to_server
}

private fun StorageTransition.subtitleRes(): Int = when (this) {
    StorageTransition.SERVER_TO_BOTH -> R.string.bt_storage_tr_server_to_both_sub
    StorageTransition.DRIVE_TO_BOTH -> R.string.bt_storage_tr_drive_to_both_sub
    StorageTransition.BOTH_TO_DRIVE -> R.string.bt_storage_tr_both_to_drive_sub
    StorageTransition.BOTH_TO_SERVER -> R.string.bt_storage_tr_both_to_server_sub
}

/** What to say when an additive transition is allowed but its flow is not built. */
private fun StorageTransition.flowUnavailableMessage(): Int = when (this) {
    StorageTransition.SERVER_TO_BOTH -> R.string.bt_storage_blocked_google
    else -> R.string.bt_storage_blocked_attach
}

internal fun TransitionBlocker.messageRes(): Int = when (this) {
    TransitionBlocker.NEEDS_GOOGLE -> R.string.bt_storage_blocked_google
    TransitionBlocker.NEEDS_SERVER_ACCOUNT -> R.string.bt_storage_blocked_server
    TransitionBlocker.NEEDS_VAULT_UNLOCK -> R.string.bt_storage_blocked_unlock
    TransitionBlocker.ATTACH_REPLAY_UNAVAILABLE -> R.string.bt_storage_blocked_attach
}
