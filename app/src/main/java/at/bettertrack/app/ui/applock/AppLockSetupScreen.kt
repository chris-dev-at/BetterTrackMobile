package at.bettertrack.app.ui.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.applock.PIN_MAX_LENGTH
import at.bettertrack.app.data.applock.PIN_MIN_LENGTH
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class SetupPhase { VerifyCurrent, Enter, Confirm }

/**
 * The set-up / change-PIN flow (spec §5), reached from Settings → Security.
 *  - **change = true** first verifies the current PIN, then takes a new one;
 *  - **change = false** creates a first PIN (enabling the lock).
 * A PIN is entered then re-entered to confirm (mismatch bounces back with a
 * message). After a first-time set-up, if biometric hardware is enrolled the
 * user is offered fingerprint/face unlock. On success we pop back to Security.
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
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()

    val storedLength = remember { controller.config.value.pinLength.coerceIn(PIN_MIN_LENGTH, PIN_MAX_LENGTH) }
    var phase by remember { mutableStateOf(if (change) SetupPhase.VerifyCurrent else SetupPhase.Enter) }
    var entered by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    var showBiometricOffer by remember { mutableStateOf(false) }

    // The dot count shown: max slots while choosing a new PIN, exact when the
    // target length is already known (verifying current / confirming).
    val totalDots = when (phase) {
        SetupPhase.VerifyCurrent -> storedLength
        SetupPhase.Enter -> PIN_MAX_LENGTH
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

    fun finishAfterSave() {
        val available = biometricAvailability(context) == BiometricAvailability.AVAILABLE
        if (!change && available) showBiometricOffer = true else onDone()
    }

    fun onComplete(pin: String) {
        when (phase) {
            SetupPhase.VerifyCurrent ->
                if (controller.checkPin(pin)) {
                    phase = SetupPhase.Enter
                    entered = ""
                } else {
                    fail(R.string.bt_applock_wrong_pin)
                }

            SetupPhase.Enter -> {
                firstPin = pin
                phase = SetupPhase.Confirm
                entered = ""
            }

            SetupPhase.Confirm ->
                if (pin == firstPin) {
                    controller.setupPin(pin)
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
        SetupPhase.VerifyCurrent -> storedLength
        SetupPhase.Confirm -> firstPin.length
        SetupPhase.Enter -> null // variable length ⇒ explicit Continue
    }

    val onDigit: (Int) -> Unit = onDigit@{ d ->
        if (errorRes != null) return@onDigit
        val cap = if (phase == SetupPhase.Enter) PIN_MAX_LENGTH else (autoSubmitLength ?: PIN_MAX_LENGTH)
        if (entered.length >= cap) return@onDigit
        entered += d.toString()
        if (autoSubmitLength != null && entered.length == autoSubmitLength) onComplete(entered)
    }
    val onBackspace: () -> Unit = onBackspace@{
        if (errorRes != null) return@onBackspace
        if (entered.isNotEmpty()) entered = entered.dropLast(1)
    }

    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0 && !reducedMotion) {
            shakeX.snapTo(0f)
            for (target in listOf(18f, -18f, 12f, -12f, 6f, 0f)) shakeX.animateTo(target, tween(46))
        }
    }

    val titleRes = when (phase) {
        SetupPhase.VerifyCurrent -> R.string.bt_applock_setup_current
        SetupPhase.Enter -> if (change) R.string.bt_applock_setup_new else R.string.bt_applock_setup_create
        SetupPhase.Confirm -> R.string.bt_applock_setup_confirm
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_applock_setup_bar), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.bt_applock_setup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Box(modifier = Modifier.offset { IntOffset(shakeX.value.roundToInt(), 0) }) {
                PinDots(filled = entered.length, total = totalDots, error = errorRes != null)
            }

            Box(Modifier.height(36.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
                errorRes?.let { Text(stringResource(it), color = bt.loss, style = MaterialTheme.typography.bodyMedium) }
            }
            Spacer(Modifier.height(12.dp))

            PinKeypad(onDigit = onDigit, onBackspace = onBackspace, enabled = errorRes == null)

            Spacer(Modifier.height(20.dp))
            // Explicit Continue only in the variable-length "choose a PIN" phase.
            if (phase == SetupPhase.Enter) {
                BtPrimaryButton(
                    text = stringResource(R.string.bt_applock_continue),
                    onClick = { onComplete(entered) },
                    enabled = entered.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && errorRes == null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            } else {
                Spacer(Modifier.height(48.dp))
            }
            Spacer(Modifier.height(8.dp))
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
}
