package at.bettertrack.app.ui.vault.qr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtActionSheet
import at.bettertrack.app.ui.components.BtSheetAction
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags

/**
 * "This vault's phrase is not on this phone" → how do you want to put it here?
 *
 * §12 makes this a hard invariant: every surface that renders a vault or a
 * locked stub must carry its state's next action inline, because the recorded
 * anti-pattern of the torn-down v2 surface was a locked vault with no way to
 * unlock it. For the *not-on-this-endpoint* state that action is this choice —
 * scan a transfer QR from a device that already holds the phrase, or type the 12
 * words.
 *
 * A bottom sheet and not an anchored menu: everything user-facing in this app
 * pops from the bottom (owner order 2026-08-16), and both options are equal
 * peers rather than a primary with an escape hatch — manual entry is the
 * fallback §13 requires to exist everywhere the QR is offered, not a lesser
 * choice.
 *
 * @param onScan opens [VaultQrScanScreen].
 * @param onTypeWords opens the custody surface's word entry.
 */
@Composable
fun VaultQrEntrySheet(
    vaultName: String?,
    onScan: () -> Unit,
    onTypeWords: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!ParanoidVaultsFlags.enabled) return

    BtActionSheet(
        title = stringResource(R.string.bt_pv_qr_sheet_title),
        subtitle = vaultName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.bt_pv_qr_result_unnamed),
        onDismiss = onDismiss,
        actions = listOf(
            BtSheetAction(
                label = stringResource(R.string.bt_pv_qr_sheet_scan),
                icon = Icons.Outlined.QrCodeScanner,
                onClick = onScan,
            ),
            BtSheetAction(
                label = stringResource(R.string.bt_pv_qr_sheet_type),
                icon = Icons.Outlined.Keyboard,
                onClick = onTypeWords,
            ),
        ),
    )
}
