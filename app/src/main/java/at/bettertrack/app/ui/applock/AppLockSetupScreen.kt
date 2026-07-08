package at.bettertrack.app.ui.applock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.applock.AccountPinStatus
import at.bettertrack.app.data.applock.AppLockFeatures
import at.bettertrack.app.data.applock.BtPinVerifyOutcome
import at.bettertrack.app.data.applock.PIN_MAX_LENGTH
import at.bettertrack.app.data.applock.PIN_MIN_LENGTH
import at.bettertrack.app.data.applock.PinSource
import at.bettertrack.app.data.applock.fixedPinLengthFor
import at.bettertrack.app.data.applock.shouldOfferBetterTrackPin
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class SetupPhase { Choose, VerifyCurrent, Enter, Confirm }

/**
 * The set-up / change-PIN flow (spec §5), reached from Settings → Security.
 *  - **change = false** creates a first PIN (enabling the lock).
 *    - When [AppLockFeatures.betterTrackPinLock] is ON it opens on a **source
 *      choice**: a fresh *device* PIN (4–6 digits, invented here) or the user's
 *      existing *BetterTrack account* PIN (exactly 4 digits, **verified live**
 *      against `POST /auth/pin/verify` before the lock activates; on success only
 *      a local Keystore hash is stored, never the PIN). The account-PIN card is
 *      offered only when `GET /auth/pin/status` reports the account has a web PIN.
 *    - When it is OFF the chooser is skipped entirely: setup goes straight to a
 *      **device PIN** (the only supported source). See [AppLockFeatures].
 *  - **change = true** first verifies the current PIN, then takes a new one.
 * A device PIN is entered then re-entered to confirm (mismatch bounces back); a
 * BetterTrack PIN is entered once and the server is the confirmation. After a
 * first-time set-up, if a biometric is enrolled the user is offered fingerprint/
 * face unlock. On success we pop back to Security.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSetupScreen(
    change: Boolean,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val bt = BtTheme.colors
    val controller = AppGraph.appLockController
    val pinService = AppGraph.accountPinService
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()

    // The BetterTrack-PIN option is gated behind a feature switch (now ON — the
    // platform grants the mobile bearer access to the PIN endpoints).
    val betterTrackEnabled = AppLockFeatures.betterTrackPinLock

    val storedLength = remember { controller.config.value.pinLength.coerceIn(PIN_MIN_LENGTH, PIN_MAX_LENGTH) }
    // Changing preserves the existing source (only meaningful when the option is
    // enabled); a fresh set-up starts as a device PIN unless the chooser changes it.
    var pinSource by remember {
        mutableStateOf(if (change && betterTrackEnabled) controller.config.value.pinSource else PinSource.DEVICE)
    }
    // Fresh set-up opens on the chooser only when the BetterTrack option exists;
    // otherwise it's device-PIN-only and jumps straight to PIN entry.
    var phase by remember {
        mutableStateOf(
            when {
                change -> SetupPhase.VerifyCurrent
                betterTrackEnabled -> SetupPhase.Choose
                else -> SetupPhase.Enter
            },
        )
    }
    var entered by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    var showBiometricOffer by remember { mutableStateOf(false) }
    // BetterTrack path only: live-verify state + the "no web PIN on the account" dialog.
    var verifying by remember { mutableStateOf(false) }
    var showNoPinDialog by remember { mutableStateOf(false) }
    var accountStatus by remember { mutableStateOf<AccountPinStatus?>(null) }

    // The fixed length this phase expects, or null when the user is free to pick
    // 4–6 (only a *device* PIN in the Enter phase).
    val fixedLen = fixedPinLengthFor(pinSource)

    // The dot count shown: max slots while choosing a variable-length new PIN,
    // exact when the target length is known (verifying / BetterTrack / confirming).
    val totalDots = when (phase) {
        SetupPhase.Choose -> 0
        SetupPhase.VerifyCurrent -> storedLength
        SetupPhase.Enter -> fixedLen ?: PIN_MAX_LENGTH
        SetupPhase.Confirm -> firstPin.length
    }

    fun fail(res: Int) {
        errorRes = res
        shakeTrigger++
    }

    // After a wrong/mismatched entry: brief red, then clear the pad.
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            delay(450)
            entered = ""
            errorRes = null
        }
    }

    // Gate the option on the account actually having a web PIN (owner's rule),
    // via the dedicated GET /auth/pin/status (pinSet). Only a confirmed web PIN
    // keeps the chooser; no PIN, forbidden, offline, or error ⇒ fall straight
    // through to a device-only PIN. Runs only when the option is enabled.
    LaunchedEffect(betterTrackEnabled, change) {
        if (betterTrackEnabled && !change) {
            val status = pinService.fetchPinStatus()
            accountStatus = status
            // No web PIN to reuse (or we couldn't confirm one) ⇒ device-PIN-only.
            if (!shouldOfferBetterTrackPin(status) && phase == SetupPhase.Choose) {
                pinSource = PinSource.DEVICE
                phase = SetupPhase.Enter
                entered = ""
            }
        }
    }

    fun finishAfterSave() {
        val available = biometricAvailability(context) == BiometricAvailability.AVAILABLE
        if (!change && available) showBiometricOffer = true else onDone()
    }

    // Verify an entered BetterTrack account PIN against the server, then activate
    // the lock (storing ONLY a local Keystore hash) — the PIN is never persisted.
    fun verifyBetterTrackAndActivate(pin: String) {
        verifying = true
        errorRes = null
        scope.launch {
            val outcome = pinService.verifyBetterTrackPin(pin)
            verifying = false
            when (outcome) {
                BtPinVerifyOutcome.Correct -> {
                    controller.setupPin(pin, PinSource.BETTERTRACK)
                    finishAfterSave()
                }
                BtPinVerifyOutcome.WrongPin -> fail(R.string.bt_applock_bt_wrong)
                BtPinVerifyOutcome.NoPinSet -> {
                    entered = ""
                    showNoPinDialog = true
                }
                BtPinVerifyOutcome.Offline -> fail(R.string.bt_applock_bt_offline)
                // Forbidden shouldn't occur while the option is enabled (the probe
                // gates that), but map it to a safe generic retry either way.
                BtPinVerifyOutcome.Forbidden, BtPinVerifyOutcome.Error -> fail(R.string.bt_applock_bt_error)
            }
        }
    }

    fun onComplete(pin: String) {
        when (phase) {
            SetupPhase.Choose -> Unit // no PIN entry in the chooser

            SetupPhase.VerifyCurrent ->
                if (controller.checkPin(pin)) {
                    phase = SetupPhase.Enter
                    entered = ""
                } else {
                    fail(R.string.bt_applock_wrong_pin)
                }

            SetupPhase.Enter ->
                if (pinSource == PinSource.BETTERTRACK) {
                    // The server is the confirmation — no local re-enter step.
                    verifyBetterTrackAndActivate(pin)
                } else {
                    firstPin = pin
                    phase = SetupPhase.Confirm
                    entered = ""
                }

            SetupPhase.Confirm ->
                if (pin == firstPin) {
                    // Device PIN: stored locally (Keystore-hashed), tagged DEVICE.
                    controller.setupPin(pin, pinSource)
                    finishAfterSave()
                } else {
                    firstPin = ""
                    phase = SetupPhase.Enter
                    fail(R.string.bt_applock_mismatch)
                }
        }
    }

    // In the fixed-length phases, auto-submit on reaching the target length.
    val autoSubmitLength = when (phase) {
        SetupPhase.Choose -> null
        SetupPhase.VerifyCurrent -> storedLength
        SetupPhase.Confirm -> firstPin.length
        SetupPhase.Enter -> fixedLen // null for a device PIN ⇒ explicit Continue
    }

    val inputBlocked = errorRes != null || verifying
    val onDigit: (Int) -> Unit = onDigit@{ d ->
        if (inputBlocked) return@onDigit
        val cap = if (phase == SetupPhase.Enter) (fixedLen ?: PIN_MAX_LENGTH) else (autoSubmitLength ?: PIN_MAX_LENGTH)
        if (entered.length >= cap) return@onDigit
        entered += d.toString()
        if (autoSubmitLength != null && entered.length == autoSubmitLength) onComplete(entered)
    }
    val onBackspace: () -> Unit = onBackspace@{
        if (inputBlocked) return@onBackspace
        if (entered.isNotEmpty()) entered = entered.dropLast(1)
    }

    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0 && !reducedMotion) {
            shakeX.snapTo(0f)
            for (target in listOf(18f, -18f, 12f, -12f, 6f, 0f)) shakeX.animateTo(target, tween(46))
        }
    }

    // Back within a fresh set-up returns to the chooser (only when the chooser
    // exists); otherwise it exits.
    val onNavBack: () -> Unit = {
        if (!change && betterTrackEnabled && (phase == SetupPhase.Enter || phase == SetupPhase.Confirm)) {
            phase = SetupPhase.Choose
            entered = ""
            firstPin = ""
            errorRes = null
            verifying = false
        } else {
            onBack()
        }
    }

    val titleRes = when (phase) {
        SetupPhase.Choose -> R.string.bt_applock_setup_choose_title
        SetupPhase.VerifyCurrent -> R.string.bt_applock_setup_current
        SetupPhase.Enter -> when {
            change -> R.string.bt_applock_setup_new
            pinSource == PinSource.BETTERTRACK -> R.string.bt_applock_setup_bt_enter
            else -> R.string.bt_applock_setup_create
        }
        SetupPhase.Confirm -> R.string.bt_applock_setup_confirm
    }
    val hintRes = if (pinSource == PinSource.BETTERTRACK) {
        R.string.bt_applock_setup_bt_hint
    } else {
        R.string.bt_applock_setup_hint
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_applock_setup_bar), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { pad ->
        if (phase == SetupPhase.Choose) {
            // While the status is still loading (null) offer optimistically; once it
            // resolves, the gate decides (and a non-offer result has already jumped
            // this flow to device-only PIN entry via the LaunchedEffect above).
            val showBetterTrack = accountStatus?.let { shouldOfferBetterTrackPin(it) } ?: true
            ChooseSourceContent(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp),
                showBetterTrack = showBetterTrack,
                onPick = { source ->
                    pinSource = source
                    entered = ""
                    firstPin = ""
                    phase = SetupPhase.Enter
                },
            )
            return@Scaffold
        }

        // Bottom-weighted keypad layout (Step-17 refinement): prompt up top, the
        // pad anchored low in the comfortable one-handed thumb zone.
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(hintRes),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Box(modifier = Modifier.offset { IntOffset(shakeX.value.roundToInt(), 0) }) {
                PinDots(filled = entered.length, total = totalDots, error = errorRes != null)
            }

            Box(Modifier.height(36.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
                when {
                    verifying -> Text(
                        stringResource(R.string.bt_applock_bt_verifying),
                        color = bt.textMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    errorRes != null -> Text(
                        stringResource(errorRes!!),
                        color = bt.loss,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.weight(0.55f))

            PinKeypad(onDigit = onDigit, onBackspace = onBackspace, enabled = !inputBlocked)

            Spacer(Modifier.height(20.dp))
            // Explicit Continue only in the variable-length device "choose a PIN"
            // phase; fixed-length phases (BetterTrack / confirm / verify) auto-submit.
            if (phase == SetupPhase.Enter && fixedLen == null) {
                BtPrimaryButton(
                    text = stringResource(R.string.bt_applock_continue),
                    onClick = { onComplete(entered) },
                    enabled = entered.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && !inputBlocked,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            } else {
                Spacer(Modifier.height(48.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showBiometricOffer) {
        AlertDialog(
            onDismissRequest = { showBiometricOffer = false; onDone() },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_applock_biometric_offer_title)) },
            text = { Text(stringResource(R.string.bt_applock_biometric_offer_message)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.setBiometricEnabled(true)
                    showBiometricOffer = false
                    onDone()
                }) { Text(stringResource(R.string.bt_applock_biometric_offer_enable), color = bt.gold) }
            },
            dismissButton = {
                TextButton(onClick = { showBiometricOffer = false; onDone() }) {
                    Text(stringResource(R.string.bt_applock_not_now), color = bt.textSecondary)
                }
            },
        )
    }

    // The account has no web PIN to reuse — offer a device PIN instead (spec §5).
    if (showNoPinDialog) {
        AlertDialog(
            onDismissRequest = { showNoPinDialog = false; phase = SetupPhase.Choose; entered = "" },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_applock_bt_nopin_title)) },
            text = { Text(stringResource(R.string.bt_applock_bt_nopin_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showNoPinDialog = false
                    pinSource = PinSource.DEVICE
                    phase = SetupPhase.Enter
                    entered = ""
                    errorRes = null
                }) { Text(stringResource(R.string.bt_applock_bt_nopin_device), color = bt.gold) }
            },
            dismissButton = {
                TextButton(onClick = { showNoPinDialog = false; phase = SetupPhase.Choose; entered = "" }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

/**
 * First-run PIN-source chooser: a fresh device PIN, or the user's existing
 * BetterTrack account PIN (server-verified on entry). The BetterTrack card is
 * shown only when [showBetterTrack] is true (the account has a web PIN, or we
 * couldn't read `pinEnabled` and let the verify call gate it).
 */
@Composable
private fun ChooseSourceContent(
    modifier: Modifier,
    showBetterTrack: Boolean,
    onPick: (PinSource) -> Unit,
) {
    val bt = BtTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.bt_applock_setup_choose_title),
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.bt_applock_setup_choose_hint),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        SetupChoiceCard(
            icon = Icons.Outlined.Dialpad,
            title = stringResource(R.string.bt_applock_setup_choice_device_title),
            subtitle = stringResource(R.string.bt_applock_setup_choice_device_sub),
            onClick = { onPick(PinSource.DEVICE) },
        )
        if (showBetterTrack) {
            Spacer(Modifier.height(12.dp))
            SetupChoiceCard(
                icon = Icons.Outlined.Badge,
                title = stringResource(R.string.bt_applock_setup_choice_bt_title),
                subtitle = stringResource(R.string.bt_applock_setup_choice_bt_sub),
                onClick = { onPick(PinSource.BETTERTRACK) },
            )
        }
        Spacer(Modifier.weight(1.3f))
    }
}

@Composable
private fun SetupChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = bt.gold, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}
