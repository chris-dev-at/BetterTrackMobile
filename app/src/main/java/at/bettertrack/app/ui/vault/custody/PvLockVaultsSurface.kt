package at.bettertrack.app.ui.vault.custody

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags
import at.bettertrack.app.vault.pv.custody.PvCustodyAction
import at.bettertrack.app.vault.pv.custody.PvCustodyState
import at.bettertrack.app.vault.pv.custody.nextAction

/**
 * "Lock vaults" — the explicit end of a §12 session.
 *
 * One of the three session terminators (process death, this action, the
 * existing app-lock idle timer) and the only one the user can reach on purpose,
 * which is why it is a visible row rather than something buried behind a menu.
 * It is deliberately not confirmed: locking destroys nothing, and the state it
 * produces has an obvious way back — the unlock sheet.
 *
 * The subline says which state the endpoint is in, because a "Lock vaults" row
 * that looks identical whether or not anything is unlocked teaches the user
 * nothing and invites a pointless tap.
 */
@Composable
fun PvLockVaultsRow(
    unlocked: Boolean,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ParanoidVaultsFlags.enabled) return
    val bt = BtTheme.colors
    Surface(
        onClick = onLock,
        enabled = unlocked,
        shape = BtShapes.card,
        color = bt.surface,
        contentColor = bt.textPrimary,
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (unlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                contentDescription = null,
                tint = if (unlocked) bt.goldInk else bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bt_pv_custody_lock_action),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (unlocked) bt.textPrimary else bt.textMuted,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (unlocked) {
                            R.string.bt_pv_custody_lock_sub_unlocked
                        } else {
                            R.string.bt_pv_custody_lock_sub_locked
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }
    }
}

/**
 * One vault, with its §12 state's affordance **inline**.
 *
 * This row is where the binding invariant becomes visible: *"a state without a
 * next action is a design bug"*, and *"every surface that renders a vault or
 * locked stub carries its state's action inline"*. The action label comes from
 * an exhaustive `when` over [PvCustodyAction] with no `else`, so a fourth state
 * cannot reach a screen without someone deciding what it offers —
 * `PvCustodyStateTest` pins the mapping and this `when` refuses to compile
 * without it.
 *
 * The subline is the *reason* for the affordance, not a repetition of it: a row
 * that only says "Unlock" leaves "why does this one ask and that one not?"
 * unanswered, and that question is the entire difference between wrapped and
 * plain custody.
 */
@Composable
fun PvVaultCustodyRow(
    state: PvCustodyState,
    vaultName: String?,
    onAction: (PvCustodyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ParanoidVaultsFlags.enabled) return
    PvVaultCustodyRowContent(state = state, vaultName = vaultName, onAction = onAction, modifier = modifier)
}

/** The row's body, ungated — see [PvVaultCustodyRow]. */
@Composable
internal fun PvVaultCustodyRowContent(
    state: PvCustodyState,
    vaultName: String?,
    onAction: (PvCustodyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val action = state.nextAction()
    Surface(
        onClick = { onAction(action) },
        shape = BtShapes.card,
        color = bt.surface,
        contentColor = bt.textPrimary,
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = vaultName ?: stringResource(R.string.bt_pv_custody_vault_fallback),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(pvCustodyStateSubline(state)),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(pvCustodyActionLabel(action)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.goldInk,
                maxLines = 1,
            )
        }
    }
}

/** The affordance's label. Exhaustive by construction — see [PvVaultCustodyRow]. */
internal fun pvCustodyActionLabel(action: PvCustodyAction): Int = when (action) {
    PvCustodyAction.Unlock -> R.string.bt_pv_custody_unlock_action
    PvCustodyAction.Open -> R.string.bt_pv_custody_state_plain_action
    PvCustodyAction.Acquire -> R.string.bt_pv_custody_state_absent_action
}

/** Why the row offers what it offers. Exhaustive by construction. */
internal fun pvCustodyStateSubline(state: PvCustodyState): Int = when (state) {
    is PvCustodyState.Wrapped -> R.string.bt_pv_custody_state_wrapped_sub
    is PvCustodyState.Plain -> R.string.bt_pv_custody_state_plain_sub
    is PvCustodyState.Absent -> R.string.bt_pv_custody_state_absent_sub
}
