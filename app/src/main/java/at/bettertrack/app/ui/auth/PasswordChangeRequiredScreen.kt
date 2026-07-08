package at.bettertrack.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Forced-password-change gate (spec §4 / §6.13): when the account must change its
 * password (server 403 `PASSWORD_CHANGE_REQUIRED` or `mustChangePassword`), the
 * app can't proceed — it sends the user to finish on the web, with a log-out
 * escape hatch.
 */
@Composable
fun PasswordChangeRequiredScreen(
    onOpenWeb: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = bt.gold,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.bt_pwchange_title),
            style = MaterialTheme.typography.titleLarge,
            color = bt.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.bt_pwchange_message),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        BtPrimaryButton(
            text = stringResource(R.string.bt_pwchange_open_web),
            onClick = onOpenWeb,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onLogout) {
            Text(
                text = stringResource(R.string.bt_action_logout),
                color = bt.textSecondary,
            )
        }
    }
}
