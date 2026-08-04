package at.bettertrack.app.ui.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.auth.LoginScreen
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.RecoveryKitDownload

/**
 * The first-run storage wizard (S3/S4 plan §4.2).
 *
 * Rendered by `BtRoot` above the auth gate whenever the stored `StorageMode` is
 * UNSET — i.e. on a genuinely clean install only. An install that has ever held a
 * session was grandfathered to SERVER before this composes (plan §4.3), so an
 * upgrade-in-place never sees this screen.
 *
 * The wizard has no `onFinished` callback by design: it finishes by **persisting
 * the mode**, and `BtRoot` is already observing that value, so the gate simply
 * stops selecting the wizard. One source of truth for "which mode am I in", and
 * no window in which the mode is written but the UI has not caught up.
 */
@Composable
fun StorageSetupWizard(
    onStartLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val vm: StorageWizardViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val keyError by vm.keyError.collectAsStateWithLifecycle()
    val kit by vm.kit.collectAsStateWithLifecycle()

    // The server/both branches hand off to the real login screen; a successful
    // session is what advances them, exactly as it does everywhere else in the app.
    val authState by AppGraph.authRepository.authState.collectAsStateWithLifecycle()
    LaunchedEffect(authState, state.step) {
        if (state.step == WizardStep.SERVER_LOGIN && authState is AuthState.LoggedIn) {
            vm.onServerLoginSucceeded()
        }
    }
    LaunchedEffect(state.step, state.choice) {
        if (state.step == WizardStep.DONE && state.choice == WizardChoice.SERVER) {
            vm.completeServerChoice()
        }
    }

    val path = state.choice?.let { wizardPath(it) } ?: wizardPath(WizardChoice.DRIVE)
    val stepIndex = path.indexOf(state.step).coerceAtLeast(0)
    // DONE is a destination, not a step to show progress for.
    val stepCount = (path.size - 1).coerceAtLeast(1)

    when (state.step) {
        WizardStep.SERVER_LOGIN -> {
            val phase by AppGraph.authRepository.loginPhase.collectAsStateWithLifecycle()
            // The existing login screen, unchanged (plan §4.2 step 2).
            LoginScreen(
                phase = phase,
                onLogin = onStartLogin,
                onNeedAccount = { onOpenUrl(AppGraph.authRepository.needAccountUrl()) },
                onForgotPassword = { onOpenUrl(AppGraph.authRepository.forgotPasswordUrl()) },
                onBack = { vm.goBack() },
                // Plan §4.2 step 6. It lives HERE, inside the wizard, and nowhere
                // else: an established SERVER user never reaches this screen, so
                // the affordance cannot suggest to someone with a live account
                // that they should abandon it. Someone standing on the wizard's
                // login step, on the other hand, has not committed to anything.
                onUseWithoutAccount = {
                    vm.choose(WizardChoice.DRIVE)
                    vm.next()
                },
            )
        }

        WizardStep.CHOOSE -> ChooseStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onChoose = vm::choose,
            onNext = vm::next,
        )

        WizardStep.GOOGLE_CONNECT -> GoogleConnectStep(
            isBothBranch = state.choice == WizardChoice.BOTH,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onBack = { vm.goBack() },
            onContinueLocal = vm::continueWithoutGoogle,
            onServerOnly = vm::settleForServerOnly,
            onDriveOnly = vm::switchToDriveOnly,
        )

        WizardStep.PASSPHRASE -> PassphraseStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            busy = busy,
            keyError = keyError,
            onPassphrase = vm::setPassphrase,
            onConfirm = vm::setConfirm,
            onBack = { vm.goBack() },
            onNext = vm::next,
        )

        WizardStep.RECOVERY_KIT -> RecoveryKitStep(
            state = state,
            kit = kit,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onTick = vm::markKitSaved,
            onBack = { vm.goBack() },
            onNext = vm::next,
        )

        WizardStep.ACKNOWLEDGE -> AcknowledgeStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onToggle = { vm.setAcknowledged(!state.acknowledged) },
            onBack = { vm.goBack() },
            onNext = vm::next,
        )

        WizardStep.FIRST_PORTFOLIO -> FirstPortfolioStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onName = vm::setPortfolioName,
            onBack = { vm.goBack() },
            onNext = vm::next,
        )

        WizardStep.WORKING -> WorkingStep(
            state = state,
            stepIndex = stepIndex,
            stepCount = stepCount,
            onRetry = vm::retryAfterFailure,
        )

        // The mode has been persisted; BtRoot's gate is about to swap this
        // whole subtree out. Render the neutral background rather than a flash.
        WizardStep.DONE -> Box(Modifier.fillMaxSize().background(BtTheme.colors.bg))
    }
}

// ── Steps ───────────────────────────────────────────────────────────────────

@Composable
private fun ChooseStep(
    state: WizardState,
    stepIndex: Int,
    stepCount: Int,
    onChoose: (WizardChoice) -> Unit,
    onNext: () -> Unit,
) {
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_storage_wizard_title),
        subtitle = stringResource(R.string.bt_storage_wizard_intro),
        onBack = null,
        primaryText = stringResource(R.string.bt_storage_continue),
        primaryEnabled = state.choice != null,
        onPrimary = onNext,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WizardChoiceCard(
                title = stringResource(R.string.bt_storage_choose_server_title),
                body = stringResource(R.string.bt_storage_choose_server_body),
                missing = stringResource(R.string.bt_storage_choose_server_missing),
                selected = state.choice == WizardChoice.SERVER,
                onClick = { onChoose(WizardChoice.SERVER) },
            )
            WizardChoiceCard(
                title = stringResource(R.string.bt_storage_choose_drive_title),
                body = stringResource(R.string.bt_storage_choose_drive_body),
                missing = stringResource(R.string.bt_storage_choose_drive_missing),
                selected = state.choice == WizardChoice.DRIVE,
                onClick = { onChoose(WizardChoice.DRIVE) },
            )
            WizardChoiceCard(
                title = stringResource(R.string.bt_storage_choose_both_title),
                body = stringResource(R.string.bt_storage_choose_both_body),
                missing = stringResource(R.string.bt_storage_choose_both_missing),
                selected = state.choice == WizardChoice.BOTH,
                onClick = { onChoose(WizardChoice.BOTH) },
            )
        }
    }
}

/**
 * The Google step, in the state it is genuinely in.
 *
 * The OAuth client for `at.bettertrack.app` does not exist yet (plan §6.8, an
 * owner action), so the shipped [at.bettertrack.app.vault.drive.GoogleAuthProvider]
 * is the signed-out placeholder and there is nothing here to tap. The honest
 * design is not a disabled "Connect" button — a button that can never work is a
 * worse lie than no button — it is to say plainly what will happen and let the
 * user carry on. A device-local vault is fully functional
 * ([at.bettertrack.app.vault.LocalDataHome]); it simply has one medium, and the
 * sync chip says so on every screen that shows it.
 */
@Composable
private fun GoogleConnectStep(
    isBothBranch: Boolean,
    stepIndex: Int,
    stepCount: Int,
    onBack: () -> Unit,
    onContinueLocal: () -> Unit,
    onServerOnly: () -> Unit,
    onDriveOnly: () -> Unit,
) {
    val bt = BtTheme.colors
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_storage_google_title),
        subtitle = stringResource(R.string.bt_storage_google_body),
        onBack = onBack,
        // "Both" cannot continue here: without Google there is no second medium,
        // and recording BOTH would make the app claim a backup that does not
        // exist. Its primary action becomes the exit that IS true.
        primaryText = stringResource(
            if (isBothBranch) R.string.bt_storage_both_settle_server else R.string.bt_storage_google_continue_local,
        ),
        onPrimary = if (isBothBranch) onServerOnly else onContinueLocal,
        secondary = if (isBothBranch) {
            {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_storage_both_settle_drive),
                    onClick = onDriveOnly,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        } else {
            null
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WizardNote(
                title = stringResource(R.string.bt_storage_google_unavailable_title),
                body = stringResource(
                    if (isBothBranch) {
                        R.string.bt_storage_both_blocked_body
                    } else {
                        R.string.bt_storage_google_unavailable_body
                    },
                ),
                tone = if (isBothBranch) NoteTone.LOSS else NoteTone.GOLD,
            )
            Text(
                text = stringResource(R.string.bt_storage_google_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

@Composable
private fun PassphraseStep(
    state: WizardState,
    stepIndex: Int,
    stepCount: Int,
    busy: Boolean,
    keyError: Boolean,
    onPassphrase: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val bt = BtTheme.colors
    val strength = passphraseStrength(state.passphrase)
    val tooShort = state.passphrase.isNotEmpty() && strength == PassphraseStrength.TOO_SHORT
    val mismatch = state.confirm.isNotEmpty() && state.passphrase != state.confirm

    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_storage_pass_title),
        subtitle = stringResource(R.string.bt_storage_pass_body),
        onBack = onBack,
        primaryText = stringResource(R.string.bt_storage_continue),
        primaryEnabled = canAdvance(state) && !busy,
        primaryLoading = busy,
        onPrimary = onNext,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BtTextField(
                value = state.passphrase,
                onValueChange = onPassphrase,
                label = stringResource(R.string.bt_storage_pass_label),
                isPassword = true,
                isError = tooShort,
                imeAction = ImeAction.Next,
                supportingText = if (tooShort) {
                    stringResource(R.string.bt_storage_pass_too_short, MIN_PASSPHRASE_LENGTH)
                } else {
                    null
                },
            )
            if (state.passphrase.isNotEmpty() && !tooShort) StrengthMeter(strength)
            BtTextField(
                value = state.confirm,
                onValueChange = onConfirm,
                label = stringResource(R.string.bt_storage_pass_confirm_label),
                isPassword = true,
                isError = mismatch,
                imeAction = ImeAction.Done,
                supportingText = if (mismatch) stringResource(R.string.bt_storage_pass_mismatch) else null,
            )
            if (strength == PassphraseStrength.WEAK) {
                Text(
                    text = stringResource(R.string.bt_storage_pass_weak_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            if (keyError) {
                WizardNote(
                    title = stringResource(R.string.bt_storage_fail_crypto_title),
                    body = stringResource(R.string.bt_storage_fail_crypto_body),
                    tone = NoteTone.LOSS,
                )
            }
        }
    }
}

/**
 * A three-segment strength rail.
 *
 * Deliberately the same visual grammar as the wizard's own progress rail: the
 * user has already learned that "filled gold segments = further along", so the
 * meter needs no legend and no colour-coded traffic light to be understood at a
 * glance.
 */
@Composable
private fun StrengthMeter(strength: PassphraseStrength) {
    val bt = BtTheme.colors
    val filled = when (strength) {
        PassphraseStrength.TOO_SHORT -> 0
        PassphraseStrength.WEAK -> 1
        PassphraseStrength.FAIR -> 2
        PassphraseStrength.STRONG -> 3
    }
    val label = stringResource(
        when (strength) {
            PassphraseStrength.STRONG -> R.string.bt_storage_strength_strong
            PassphraseStrength.FAIR -> R.string.bt_storage_strength_fair
            else -> R.string.bt_storage_strength_weak
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .then(
                        if (index < filled) {
                            Modifier.background(bt.gold)
                        } else {
                            Modifier.background(bt.border)
                        },
                    ),
            )
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (strength == PassphraseStrength.WEAK) bt.textMuted else bt.textSecondary,
        )
    }
}

@Composable
private fun RecoveryKitStep(
    state: WizardState,
    kit: RecoveryKitDownload?,
    stepIndex: Int,
    stepCount: Int,
    onTick: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val bt = BtTheme.colors

    // SAF: the user picks where the kit goes. Nothing about the destination is
    // chosen for them — this file is the last resort for their own data.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RECOVERY_KIT_MIME),
    ) { uri ->
        val bytes = kit?.bytes ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        }
    }

    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_storage_kit_title),
        subtitle = stringResource(R.string.bt_storage_kit_body),
        onBack = onBack,
        primaryText = stringResource(R.string.bt_storage_continue),
        primaryEnabled = canAdvance(state),
        onPrimary = onNext,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (kit == null) {
                WizardNote(
                    title = null,
                    body = stringResource(R.string.bt_storage_kit_failed),
                    tone = NoteTone.LOSS,
                )
            } else {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_storage_kit_save),
                    onClick = { saveLauncher.launch(kit.filename) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
                Text(
                    text = stringResource(R.string.bt_storage_kit_created),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            RequiredTick(
                label = stringResource(R.string.bt_storage_kit_ack),
                checked = state.kitSaved,
                enabled = kit != null,
                onToggle = { onTick(!state.kitSaved) },
            )
        }
    }
}

@Composable
private fun AcknowledgeStep(
    state: WizardState,
    stepIndex: Int,
    stepCount: Int,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_storage_ack_title),
        subtitle = null,
        onBack = onBack,
        primaryText = stringResource(R.string.bt_storage_continue),
        primaryEnabled = canAdvance(state),
        onPrimary = onNext,
    ) {
        BlockingAcknowledgment(
            title = stringResource(R.string.bt_storage_ack_title),
            body = stringResource(R.string.bt_storage_ack_body),
            checkboxLabel = stringResource(R.string.bt_storage_ack_checkbox),
            checked = state.acknowledged,
            onToggle = onToggle,
        )
    }
}

@Composable
private fun FirstPortfolioStep(
    state: WizardState,
    stepIndex: Int,
    stepCount: Int,
    onName: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val default = stringResource(R.string.bt_storage_first_default)
    LaunchedEffect(Unit) { if (state.portfolioName.isEmpty()) onName(default) }
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_storage_first_title),
        subtitle = stringResource(R.string.bt_storage_first_body),
        onBack = onBack,
        primaryText = stringResource(R.string.bt_storage_create),
        primaryEnabled = canAdvance(state),
        onPrimary = onNext,
    ) {
        BtTextField(
            value = state.portfolioName,
            onValueChange = onName,
            label = stringResource(R.string.bt_storage_first_label),
            imeAction = ImeAction.Done,
        )
    }
}

@Composable
private fun WorkingStep(
    state: WizardState,
    stepIndex: Int,
    stepCount: Int,
    onRetry: () -> Unit,
) {
    val bt = BtTheme.colors
    val failure = state.failure
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(
            when (failure) {
                WizardFailure.ROUND_TRIP_FAILED -> R.string.bt_storage_fail_round_trip_title
                WizardFailure.CRYPTO_FAILED -> R.string.bt_storage_fail_crypto_title
                WizardFailure.VAULT_WRITE_FAILED -> R.string.bt_storage_fail_write_title
                null -> R.string.bt_storage_working_title
            },
        ),
        subtitle = stringResource(
            when (failure) {
                WizardFailure.ROUND_TRIP_FAILED -> R.string.bt_storage_fail_round_trip_body
                WizardFailure.CRYPTO_FAILED -> R.string.bt_storage_fail_crypto_body
                WizardFailure.VAULT_WRITE_FAILED -> R.string.bt_storage_fail_write_body
                null -> R.string.bt_storage_working_body
            },
        ),
        onBack = null,
        primaryText = if (failure != null) stringResource(R.string.bt_action_retry) else null,
        onPrimary = if (failure != null) onRetry else null,
    ) {
        if (failure == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = bt.gold,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.bt_storage_working_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )
            }
        }
    }
}

// ── Recovery-kit delivery ───────────────────────────────────────────────────

/**
 * The kit is delivered through the Storage Access Framework and nowhere else.
 *
 * A share sheet would need the bytes staged in app cache behind a FileProvider,
 * and those bytes are the **raw vault key** — a plaintext copy of the thing the
 * whole feature exists to protect, sitting on disk until some sweep removes it.
 * SAF hands the stream straight to the destination the user picked, so the key
 * never has a second resting place. (A share-sheet variant is a device-pass
 * decision, not a code gap.)
 */
private const val RECOVERY_KIT_MIME = "text/plain"
