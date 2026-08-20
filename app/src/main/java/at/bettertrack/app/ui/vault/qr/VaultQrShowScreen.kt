package at.bettertrack.app.ui.vault.qr

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BT_QR_CHARSET_UTF8
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtQrCode
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags
import at.bettertrack.app.vault.pv.VaultQrContract
import at.bettertrack.app.vault.pv.VaultQrPayload
import at.bettertrack.app.vault.pv.buildVaultQrPayload
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

/**
 * §13 **sender leg** — "Show transfer QR".
 *
 * The screen displays a vault's 12-word seed phrase as a machine-readable image.
 * That is the entire threat model in one sentence: whoever photographs this owns
 * the vault. Everything on the screen follows from it.
 *
 * - **`FLAG_SECURE` while visible** ([SecureScreenEffect]) — no screenshot, no
 *   screen recording, no recents thumbnail, blacked out on external displays.
 * - **A 60-second TTL that blanks the code**, not a passive countdown: after
 *   [VaultQrContract.DISPLAY_TTL_SECONDS] the QR is replaced by a hidden-state
 *   card with a manual "Show again". A phrase left glowing on a table is the
 *   realistic leak, not an attacker with a long lens. (The 120 s window of the
 *   retired v2 handoff is dead — that payload was code-wrapped and a photograph
 *   of it alone was useless; this one is the secret itself.)
 * - **No clipboard path.** There is no copy button and the screen never writes
 *   to the clipboard: on Android the clipboard is readable by the system's
 *   clipboard history and, on older releases, by any focused app.
 * - **Nothing logged, nothing persisted.** No `Log` call, no `rememberSaveable`
 *   — the payload is built into composition-scoped memory and dies with the
 *   screen. It is deliberately not hoisted into a ViewModel: a saved-state
 *   handle is disk.
 * - **Display → camera is the whole channel.** Nothing here touches the network.
 *
 * Reaching this screen is itself a step-up act (a live unlock plus, for wrapped
 * custody, a password entry no older than 60 s). That gate belongs to the
 * custody surface; this composable renders a phrase it has already been handed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultQrShowScreen(
    mnemonic: String,
    vaultId: String,
    onBack: () -> Unit,
    vaultName: String? = null,
    keyFingerprint: String? = null,
) {
    // The program's single gate. Nothing navigates here while it is off; this is
    // the structural belt to that braces (see ParanoidVaultsFlags).
    if (!ParanoidVaultsFlags.enabled) return

    val bt = BtTheme.colors
    SecureScreenEffect()

    val payload = remember(mnemonic, vaultId, vaultName, keyFingerprint) {
        runCatching {
            buildVaultQrPayload(
                mnemonic = mnemonic,
                vaultId = vaultId,
                name = vaultName,
                fingerprint = keyFingerprint,
            )
        }.getOrNull()
    }

    // `generation` restarts the TTL; `remaining` drives the visible countdown.
    var generation by remember { mutableIntStateOf(0) }
    var remaining by remember { mutableIntStateOf(VaultQrContract.DISPLAY_TTL_SECONDS) }
    LaunchedEffect(generation) {
        remaining = VaultQrContract.DISPLAY_TTL_SECONDS
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }
    val visible = remaining > 0

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bt_pv_qr_show_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CaptureWarning()

            if (payload == null) {
                // The caller handed over something that is not a transferable
                // phrase. Say so plainly rather than rendering an empty frame.
                Text(
                    stringResource(R.string.bt_pv_qr_show_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                    textAlign = TextAlign.Center,
                )
            } else if (visible) {
                BtQrCode(
                    data = payload,
                    // §13: byte mode, UTF-8, error correction M. Byte mode falls
                    // out of the payload itself (lowercase words are outside QR
                    // alphanumeric mode); the other two are stated here.
                    errorCorrection = ErrorCorrectionLevel.M,
                    characterSet = BT_QR_CHARSET_UTF8,
                    size = 260.dp,
                )
                Text(
                    stringResource(R.string.bt_pv_qr_show_expires, remaining),
                    style = MaterialTheme.typography.labelLarge,
                    color = bt.textMuted,
                )
                Text(
                    stringResource(R.string.bt_pv_qr_show_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                    textAlign = TextAlign.Center,
                )
            } else {
                HiddenCode(onShowAgain = { generation += 1 })
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.bt_pv_qr_show_no_clipboard),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The §13 banner — stated once, at the top, in the danger ink. */
@Composable
private fun CaptureWarning() {
    val bt = BtTheme.colors
    Surface(
        color = bt.lossWash,
        border = BorderStroke(1.dp, bt.edge(bt.loss, 0.4f)),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = bt.loss,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.bt_pv_qr_show_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textPrimary,
            )
        }
    }
}

/** The blanked state. A hidden code with no way back would be a dead end. */
@Composable
private fun HiddenCode(onShowAgain: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        color = bt.surfaceQuiet,
        border = BorderStroke(1.dp, bt.groupBorder),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                    tint = bt.textMuted,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                stringResource(R.string.bt_pv_qr_show_expired_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            Text(
                stringResource(R.string.bt_pv_qr_show_expired_body),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
                textAlign = TextAlign.Center,
            )
            BtPrimaryButton(
                text = stringResource(R.string.bt_pv_qr_show_again),
                onClick = onShowAgain,
            )
        }
    }
}

/**
 * Convenience overload for a caller that already holds a parsed payload (the
 * re-show path after a round trip). Identical rendering; it exists so no caller
 * has to take the phrase apart and put it back together.
 */
@Composable
fun VaultQrShowScreen(
    payload: VaultQrPayload,
    onBack: () -> Unit,
) = VaultQrShowScreen(
    mnemonic = payload.mnemonic,
    vaultId = payload.vaultId,
    onBack = onBack,
    vaultName = payload.name,
    keyFingerprint = payload.fingerprint,
)
