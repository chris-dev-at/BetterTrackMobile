package at.bettertrack.app.ui.paranoid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The designed "this account is in paranoid mode" state (V5 S2a).
 *
 * Shown INSTEAD of the portfolio surfaces once the server has refused a call
 * with `403 PARANOID_MODE`. It exists so the app never renders the two wrong
 * answers a naive client would give: a generic "something went wrong", or an
 * apparently-real **€0 portfolio** (paranoid accounts hold their positions in a
 * client-side vault the server genuinely cannot see).
 *
 * The copy is deliberately reassuring and specific: nothing is lost, the money
 * features live on the web app for now, and the social half of the app still
 * works right here — which is true, because only the portfolio family is routed
 * to this screen.
 */
@Composable
fun ParanoidModeScreen(
    onOpenWeb: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Surface(
            shape = CircleShape,
            color = bt.goldSurface,
            border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.4f)),
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = null,
                tint = bt.gold,
                modifier = Modifier.padding(18.dp).size(32.dp),
            )
        }

        Text(
            text = stringResource(R.string.bt_paranoid_title),
            style = MaterialTheme.typography.headlineSmall,
            color = bt.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.bt_paranoid_body),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        // What still works right here — the honest half of the message.
        BtCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.bt_paranoid_works_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = bt.textPrimary,
                )
                WorksRow(stringResource(R.string.bt_paranoid_works_social))
                WorksRow(stringResource(R.string.bt_paranoid_works_watchlists))
                WorksRow(stringResource(R.string.bt_paranoid_works_alerts))
                WorksRow(stringResource(R.string.bt_paranoid_works_notifications))
            }
        }

        Text(
            text = stringResource(R.string.bt_paranoid_web_hint),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            textAlign = TextAlign.Center,
        )

        if (onOpenWeb != null) {
            BtSecondaryButton(
                text = stringResource(R.string.bt_paranoid_open_web),
                onClick = onOpenWeb,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

@Composable
private fun WorksRow(text: String) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Check,
            contentDescription = null,
            tint = bt.gain,
            modifier = Modifier.size(16.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
    }
}
