package at.bettertrack.app.ui.applock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.data.applock.PinVerifyResult
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The app-lock screen (spec §5): wordmark + PIN pad, no data. Shown by
 * [at.bettertrack.app.ui.shell.BtRoot] whenever a logged-in session is locked
 * (cold start / AFK return). Offers biometric unlock (auto-prompted when
 * enrolled + enabled) with the PIN as the always-present fallback, a
 * reduced-motion-aware shake + haptic on a wrong entry, progressive lockout with
 * a live countdown, and "Forgot PIN?" → log out.
 *
 * @param onForgotPin wipes local data + logs out (re-login lets the user set a
 *        new PIN); wired in BtRoot so it can reach the auth repository.
 */
@Composable
fun AppLockScreen(onForgotPin: () -> Unit) {
    val bt = BtTheme.colors
    val controller = AppGraph.appLockController
    val config by controller.config.collectAsState()
    val attempts by controller.attempts.collectAsState()

    val context = LocalContext.current
    val activity = remember { context.findFragmentActivity() }
    val reducedMotion = rememberReducedMotion()
    val haptic = LocalHapticFeedback.current

    val pinLength = config.pinLength.coerceIn(4, 6)
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    var showForgot by remember { mutableStateOf(false) }

    // Live lockout countdown — recomputed whenever the attempt state changes.
    var lockoutRemaining by remember { mutableLongStateOf(controller.currentLockoutRemaining()) }
    LaunchedEffect(attempts) {
        lockoutRemaining = controller.currentLockoutRemaining()
        while (lockoutRemaining > 0) {
            delay(250)
            lockoutRemaining = controller.currentLockoutRemaining()
        }
    }
    val lockedOut = lockoutRemaining > 0

    // Biometric availability resolved once for this screen instance.
    val biometricUsable = remember(config.biometricEnabled) {
        config.biometricEnabled && activity != null &&
            biometricAvailability(context) == BiometricAvailability.AVAILABLE
    }
    val biometricTitle = stringResource(R.string.bt_applock_biometric_title)
    val biometricSubtitle = stringResource(R.string.bt_applock_biometric_subtitle)
    val usePinText = stringResource(R.string.bt_applock_use_pin)
    val launchBiometric: () -> Unit = launch@{
        val act = activity ?: return@launch
        promptBiometric(
            activity = act,
            title = biometricTitle,
            subtitle = biometricSubtitle,
            negativeText = usePinText,
            onSuccess = { controller.onBiometricSuccess() },
            onError = { /* fall back to the PIN pad — it's already on screen */ },
        )
    }
    // Auto-offer biometrics on entry (not while locked out).
    LaunchedEffect(Unit) {
        if (biometricUsable && !lockedOut) launchBiometric()
    }

    fun submit(pin: String) {
        when (val r = controller.verifyPin(pin)) {
            is PinVerifyResult.Success -> Unit // BtRoot swaps away as `locked` flips
            is PinVerifyResult.LockedOut -> {
                entered = ""
                lockoutRemaining = r.remainingMillis
            }
            is PinVerifyResult.Wrong -> {
                error = true
                shakeTrigger++
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    // Clear the wrong-entry error shortly after showing it, then reset the pad.
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            delay(450)
            entered = ""
            error = false
        }
    }

    val onDigit: (Int) -> Unit = onDigit@{ d ->
        if (lockedOut || error) return@onDigit
        if (entered.length >= pinLength) return@onDigit
        entered += d.toString()
        if (entered.length == pinLength) submit(entered)
    }
    val onBackspace: () -> Unit = onBackspace@{
        if (lockedOut || error) return@onBackspace
        if (entered.isNotEmpty()) entered = entered.dropLast(1)
    }

    // Reduced-motion-aware shake of the dots row on a wrong entry.
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0 && !reducedMotion) {
            shakeX.snapTo(0f)
            for (target in listOf(18f, -18f, 12f, -12f, 6f, 0f)) {
                shakeX.animateTo(target, animationSpec = tween(46))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bt.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(8.dp))
        Wordmark(fontSize = 30.sp, edition = stringResource(R.string.bt_edition_app))
        Spacer(Modifier.height(28.dp))

        Text(
            text = if (lockedOut) {
                stringResource(R.string.bt_applock_too_many)
            } else {
                stringResource(R.string.bt_applock_enter_pin)
            },
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
        )
        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier.offset { IntOffset(shakeX.value.roundToInt(), 0) },
        ) {
            PinDots(filled = entered.length, total = pinLength, error = error)
        }

        // Reserved status line (keeps the layout stable across states).
        Box(Modifier.height(40.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
            when {
                lockedOut -> Text(
                    text = stringResource(R.string.bt_applock_try_again_in, formatCountdown(lockoutRemaining)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.loss,
                )
                error -> Text(
                    text = stringResource(R.string.bt_applock_wrong_pin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.loss,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        PinKeypad(
            onDigit = onDigit,
            onBackspace = onBackspace,
            enabled = !lockedOut,
            leadingSlot = if (biometricUsable) {
                { BiometricKey(onClick = launchBiometric) }
            } else {
                null
            },
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showForgot = true }) {
            Text(stringResource(R.string.bt_applock_forgot_pin), color = bt.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showForgot) {
        AlertDialog(
            onDismissRequest = { showForgot = false },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_applock_forgot_title)) },
            text = { Text(stringResource(R.string.bt_applock_forgot_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showForgot = false
                    onForgotPin()
                }) { Text(stringResource(R.string.bt_action_logout), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { showForgot = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun BiometricKey(onClick: () -> Unit) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = bt.gold),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Fingerprint,
            contentDescription = stringResource(R.string.bt_applock_biometric_cd),
            tint = bt.gold,
            modifier = Modifier.size(30.dp),
        )
    }
}

/** mm:ss for a remaining-millis countdown (e.g. 29 000 → "0:29"). */
private fun formatCountdown(ms: Long): String {
    val totalSeconds = ((ms + 999) / 1000).toInt() // round up so it hits 0 exactly
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
