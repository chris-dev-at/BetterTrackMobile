package at.bettertrack.app.ui.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.components.rememberBtHaptics
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Drive-mode gate: no vault key in memory ⇒ no data on screen.
 *
 * ## Deliberately the app-lock screen's twin
 *
 * Plan §2.7/§4.4 is explicit that there is **one** lock model, not two. A user
 * who has learned that the app asks for a secret on a wordmark-only screen, with
 * a shake and a haptic when it is wrong, should meet the same screen here — the
 * only difference being that the secret is a passphrase rather than a PIN,
 * because that is what actually differs. So this mirrors
 * `ui/applock/AppLockScreen.kt`'s layout, its wrong-entry feedback and its
 * bottom-weighted thumb zone rather than inventing a second visual language for
 * the same idea.
 *
 * ## The spinner is load-bearing
 *
 * Unlocking runs Argon2id at m=64 MiB, t=3 — hundreds of milliseconds on a real
 * phone, and the parameters are not negotiable because they are baked into every
 * vault the web client ever wrote (plan §6.7). Custody confines the derivation to
 * its own dispatcher; this screen's job is to make the wait legible, so nobody
 * concludes the button is broken and taps it four more times.
 */
@Composable
fun VaultUnlockGate(content: @Composable () -> Unit) {
    val custody = AppGraph.vaultKeyCustody
    val locked by custody.locked.collectAsState()
    if (!locked) {
        content()
        return
    }
    VaultUnlockScreen()
}

@Composable
private fun VaultUnlockScreen() {
    val bt = BtTheme.colors
    val custody = AppGraph.vaultKeyCustody
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = rememberBtHaptics()
    val reducedMotion = rememberReducedMotion()

    var passphrase by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var shakeTrigger by remember { mutableIntStateOf(0) }

    // Recovery-kit unlock — the path that exists because a passphrase can be
    // forgotten and the kit holds the raw key (custody deliberately does NOT
    // rewrap here; changing the passphrase stays a separate, deliberate act).
    val kitLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || !custody.unlockWithRecoveryKit(bytes)) {
            error = R.string.bt_vault_unlock_kit_wrong
            shakeTrigger++
        }
    }

    fun submit() {
        if (working || passphrase.isEmpty()) return
        working = true
        error = null
        scope.launch {
            val ok = custody.unlock(passphrase)
            working = false
            if (!ok) {
                error = R.string.bt_vault_unlock_wrong
                shakeTrigger++
                haptics.reject()
                passphrase = ""
            }
            // On success the gate recomposes away as `locked` flips — the same
            // way the PIN screen does. No navigation, no flash of the old screen.
        }
    }

    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0 && !reducedMotion) {
            shakeX.snapTo(0f)
            for (target in listOf(16f, -16f, 10f, -10f, 5f, 0f)) {
                shakeX.animateTo(target, animationSpec = tween(46))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bt.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Wordmark(fontSize = 30.sp, edition = stringResource(R.string.bt_edition_app))
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.bt_vault_unlock_title),
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.bt_vault_unlock_body),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
        Spacer(Modifier.height(28.dp))

        Box(modifier = Modifier.offset { IntOffset(shakeX.value.roundToInt(), 0) }) {
            BtTextField(
                value = passphrase,
                onValueChange = { passphrase = it; error = null },
                label = stringResource(R.string.bt_storage_pass_label),
                isPassword = true,
                isError = error != null,
                enabled = !working,
                imeAction = ImeAction.Done,
            )
        }

        // Reserved status line so the button never jumps when an error appears.
        Box(Modifier.height(36.dp).padding(top = 10.dp), contentAlignment = Alignment.Center) {
            error?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.loss,
                )
            }
        }

        Spacer(Modifier.weight(0.6f))

        BtPrimaryButton(
            text = if (working) {
                stringResource(R.string.bt_vault_unlock_working)
            } else {
                stringResource(R.string.bt_vault_unlock_action)
            },
            onClick = { submit() },
            enabled = passphrase.isNotEmpty() && !working,
            loading = working,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.bt_vault_unlock_kit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
        TextButton(onClick = { kitLauncher.launch(arrayOf("text/plain", "*/*")) }) {
            Text(
                text = stringResource(R.string.bt_vault_unlock_kit_action),
                style = MaterialTheme.typography.labelLarge,
                color = bt.textSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
