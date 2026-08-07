package at.bettertrack.app.ui.storage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.QueryStats
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.storage.PriceLookupAvailability
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
import at.bettertrack.app.data.storage.priceLookupAvailability
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.btExpandHeader
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.components.rememberReducedMotion
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
 *
 * ## R2 visual pass
 *
 * The row runs are [BtGroup]s now, like the rest of settings, and the bar is the
 * shared collapsing header.
 *
 * The one thing this screen needed that no other header did: its title is one of
 * three, chosen by [DataHomeSection], and the delete section renders it in
 * `bt.loss`. A red header is the strongest signal the screen has that you are
 * somewhere irreversible, and it arrives before any body text is read — so it
 * had to survive the conversion, and `BtCollapsingHeader` grew a `titleColor`
 * for it. The back button stays dual-purpose: it pops a sub-section first and
 * only leaves the screen from MAIN.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhereYourDataLivesScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    var section by remember { mutableStateOf(DataHomeSection.MAIN) }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    // Each section replaces the whole body, and the two sub-sections are short
    // forms. Carrying the previous section's collapse into them would leave a
    // half-height bar over content too short to scroll it back open — worst of
    // all on DELETE, where the title is the warning.
    //
    // R3 §1: animated rather than assigned, so the bar settles as the section
    // swaps instead of jumping 48dp in one frame under the reader's eyes.
    val reducedMotion = rememberReducedMotion()
    LaunchedEffect(section) { scrollBehavior.btExpandHeader(reducedMotion) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(
                    when (section) {
                        DataHomeSection.MAIN -> R.string.bt_storage_settings_row
                        DataHomeSection.REKEY -> R.string.bt_vault_rekey_title
                        DataHomeSection.DELETE -> R.string.bt_storage_delete_everything
                    },
                ),
                titleColor = if (section == DataHomeSection.DELETE) bt.loss else null,
                scrollBehavior = scrollBehavior,
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
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                // Edge-to-edge: the window does not resize for the keyboard and the
                // Scaffold's insets are system-bars only. The re-key and delete
                // sub-sections are passphrase forms — old, new, confirm, plus the
                // typed delete confirmation — and their submit buttons sit below
                // the last field, i.e. exactly where the keyboard lands. Consume
                // `inner` (nav bar) so imePadding() adds only the remainder.
                .consumeWindowInsets(inner)
                .imePadding()
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
        BtSectionHeader(stringResource(R.string.bt_storage_section_backup))
        // The two backup cards stay separate cards: each is a compound status
        // block (a sentence, per-medium lines, its own button), not a row, and
        // merging them would blur exactly the "these are two independent facts"
        // point `VaultSyncCard` and `ServerVaultSection` exist to make.
        VaultSyncCard()
        // S5: BetterTrack as a second storage place. Its own row, because with a
        // media set "is my data safe?" stops having one answer — see
        // `ServerVaultSection`'s doc.
        ServerVaultSection()

        // Three things you can do with the key — one subject, so one group.
        BtSectionHeader(stringResource(R.string.bt_storage_section_vault))
        BtGroup {
            BtGroupRow(
                icon = Icons.Outlined.Key,
                title = stringResource(R.string.bt_vault_change_pass),
                subtitle = stringResource(R.string.bt_vault_change_pass_sub),
                onClick = onOpenRekey,
            )
            NewRecoveryKitRow()
            LockVaultRow()
        }
    } else if (at.bettertrack.app.data.api.ParanoidModeState.active.collectAsStateWithLifecycle().value &&
        !AppGraph.vaultKeyCustody.hasVault
    ) {
        // S5 §1.5 → the real media set: a paranoid account on a device with no
        // vault. Server mode has nothing to render for this user — the kill-rail
        // blacked out every portfolio surface — so the honest offer is the way in,
        // not an empty backup section.
        BtSectionHeader(stringResource(R.string.bt_storage_section_backup))
        ServerVaultSetupCard()
    }

    // ── Prices (W6) ─────────────────────────────────────────────────────────
    PricesSection(effective)

    // ── Change where it lives ───────────────────────────────────────────────
    val transitions = availableTransitions(effective)
    if (transitions.isNotEmpty()) {
        BtSectionHeader(stringResource(R.string.bt_storage_section_change))
        var message by remember { mutableStateOf<Int?>(null) }
        // The offers are alternatives to each other — one group, so they read as
        // one decision with N answers rather than N separate propositions.
        BtGroup {
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
        }
        // The outcome of the LAST tap, outside the group: it reports on the act,
        // not on any one offer, and a row inside the block would read as a
        // property of whichever transition it happened to sit under.
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
        BtSectionHeader(stringResource(R.string.bt_storage_section_device))
        BtGroup { DisconnectDriveRow() }
        // Deliberately NOT in the group above, and the one thing on this screen
        // that keeps a border — the same shape `SettingsScreen`'s danger zone
        // uses. Every other block dropped its outline for a tonal step, so a
        // single red-edged block reads as "this one is not like the others"
        // without shouting; the emphasis is bought by the absence elsewhere.
        Surface(
            color = bt.surface,
            border = BorderStroke(1.dp, bt.edge(bt.loss, 0.35f)),
            shape = BtShapes.group,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BtGroupRow(
                icon = Icons.Outlined.WarningAmber,
                iconTint = bt.loss,
                title = stringResource(R.string.bt_storage_delete_everything),
                titleColor = bt.loss,
                subtitle = stringResource(R.string.bt_storage_delete_everything_sub),
                onClick = onOpenDelete,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ModeCard(mode: StorageMode) {
    val bt = BtTheme.colors
    Surface(
        color = bt.wash(bt.gold, 0.06f),
        border = BorderStroke(1.dp, bt.edge(bt.gold, 0.35f)),
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
                color = bt.goldInk,
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
                        color = bt.goldInk,
                    )
                }
            }
            // S5: with a media set the headline above is only the honest FLOOR
            // across every storage place. One row per medium is what stops a
            // successful Drive push from reassuring a user whose BetterTrack copy
            // is stale — the two are independent facts and must read as two.
            if (state.mediaRows.size > 1) {
                Spacer(Modifier.height(10.dp))
                state.mediaRows.forEach { row ->
                    Text(
                        text = stringResource(row.status.mediumSentenceRes(row.medium)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.status == VaultSyncStatus.SYNCED) bt.textSecondary else bt.textMuted,
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
    BtGroupRow(
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
        // Lives INSIDE the group, directly under its own row, so the reason
        // attaches to the tap that produced it. Indented to the group's 16dp
        // gutter rather than the page's 4dp one it used when it was a sibling of
        // a bordered card.
        Text(
            text = stringResource(R.string.bt_storage_blocked_unlock),
            style = MaterialTheme.typography.bodySmall,
            color = BtTheme.colors.goldSoft,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun LockVaultRow() {
    BtGroupRow(
        icon = Icons.Outlined.Lock,
        title = stringResource(R.string.bt_vault_lock_action),
        subtitle = stringResource(R.string.bt_vault_lock_sub),
        onClick = { AppGraph.vaultKeyCustody.lock() },
    )
}

@Composable
private fun DisconnectDriveRow() {
    BtGroupRow(
        icon = Icons.Outlined.CloudOff,
        title = stringResource(R.string.bt_storage_disconnect_drive),
        subtitle = stringResource(R.string.bt_storage_disconnect_drive_sub),
        onClick = { AppGraph.disconnectGoogle() },
    )
}

/**
 * One offer to move the data somewhere else, as a row in the transitions group.
 *
 * A blocked transition stays TAPPABLE on purpose: the blocker shown here is
 * evaluated ahead of the tap, but the switcher is the authority, and a row that
 * silently refused to respond would leave the user with no way to find out
 * whether the app or their setup is at fault. Tapping a blocked offer changes
 * nothing and prints the reason.
 */
@Composable
private fun TransitionRow(
    transition: StorageTransition,
    onResult: (SwitchResult) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val bt = BtTheme.colors
    val outcome = evaluateTransition(transition, AppGraph.transitionCapabilities())
    val blocker = (outcome as? TransitionOutcome.Blocked)?.blocker
    BtGroupRow(
        title = stringResource(transition.titleRes()),
        subtitle = stringResource(transition.subtitleRes()),
        onClick = { scope.launch { onResult(AppGraph.storageModeSwitcher.apply(transition)) } },
    )
    if (blocker != null) {
        // The prerequisite, kept as a third line under its own row rather than
        // squeezed into `subtitle`: the subtitle says what the move does and is
        // true whether or not you can make it, and merging the two would lose
        // that distinction the moment the blocker clears.
        Text(
            text = stringResource(blocker.messageRes()),
            style = MaterialTheme.typography.bodySmall,
            color = bt.goldSoft,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
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
        border = BorderStroke(1.dp, bt.edge(bt.loss, 0.4f)),
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

/**
 * The **"Use BetterTrack for prices only"** opt-in (S3/S4 plan §5 W6, item 3).
 *
 * Absent in SERVER mode — there prices already come from BetterTrack because
 * everything does, and a switch with nothing on the other side of it is clutter
 * (plan §4.5, "absent, not greyed").
 *
 * Present but **disabled** in Drive mode with no account. That is the one place
 * this screen deliberately greys rather than hides: "absent, not greyed" governs
 * features a mode does not have, and this is a feature it *could* have — the
 * user is one account away, and hiding the option would hide the trade rather
 * than let them judge it. The disabled subtitle says exactly what is missing and
 * where to fix it.
 *
 * The subtitle under the title is the plan's own sentence, verbatim, because it
 * is the whole offer: *"BetterTrack would see which assets you look up, never
 * what you own."* It is true by construction — the market seam and the portfolio
 * seam are separate routers, and the toggle can only reach the first.
 */
@Composable
private fun PricesSection(effective: StorageMode) {
    val availability = priceLookupAvailability(
        mode = effective,
        hasSession = AppGraph.hasServerSession(),
    )
    if (availability == PriceLookupAvailability.NOT_APPLICABLE) return

    val bt = BtTheme.colors
    val enabled by AppGraph.priceLookupStore.enabled.collectAsStateWithLifecycle()
    val offerable = availability == PriceLookupAvailability.AVAILABLE
    // A stored `true` on an install with no session is consent without
    // capability: it routes nowhere, so the switch must not claim it is on.
    val checked = enabled && offerable

    BtSectionHeader(stringResource(R.string.bt_prices_section))
    // Left as its own bordered card: it is a single control plus the sentence
    // that explains the trade, not a run of rows, and a one-row group here would
    // add a container without adding a grouping.
    Surface(
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.QueryStats,
                    contentDescription = null,
                    tint = if (offerable) bt.textSecondary else bt.textMuted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.bt_prices_toggle_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (offerable) bt.textPrimary else bt.textMuted,
                    )
                    Text(
                        stringResource(R.string.bt_prices_toggle_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = { AppGraph.priceLookupStore.set(it) },
                    enabled = offerable,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = bt.onGold,
                        checkedTrackColor = bt.gold,
                        checkedBorderColor = bt.gold,
                        uncheckedThumbColor = bt.textMuted,
                        uncheckedTrackColor = bt.surface,
                        uncheckedBorderColor = bt.border,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when {
                        !offerable -> R.string.bt_prices_toggle_needs_account
                        checked -> R.string.bt_prices_toggle_on_note
                        else -> R.string.bt_prices_toggle_off_note
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

// R2: `SectionLabel` and `StorageNavRow` are gone — `BtSectionHeader` and
// `BtGroupRow` do both jobs app-wide, so this screen no longer carries its own
// third copy of them.

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
