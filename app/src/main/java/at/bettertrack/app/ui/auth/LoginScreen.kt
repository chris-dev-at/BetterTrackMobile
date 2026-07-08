package at.bettertrack.app.ui.auth

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.LoginError
import at.bettertrack.app.data.auth.LoginPhase
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The BetterTrack login screen (spec §3.2 / §4): the wordmark + "App" edition
 * large, the muted tagline, a single gold "Login with BetterTrack" primary
 * action, and subtle "Need an account?" / "Forgot password?" links that open the
 * web. An in-progress state covers the Custom Tab + token exchange; a
 * human-readable error surfaces on failure (a user closing the tab is silent).
 */
@Composable
fun LoginScreen(
    phase: LoginPhase,
    onLogin: () -> Unit,
    onNeedAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val inProgress = phase is LoginPhase.InProgress

    // Calm entrance (spec §3.7): the screen fades up and settles a few dp. Under
    // reduced motion it's simply present — no movement, no fade.
    val reducedMotion = rememberReducedMotion()
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val entrance by animateFloatAsState(
        targetValue = if (appeared || reducedMotion) 1f else 0f,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "loginEntrance",
    )

    // Layout is deliberately thumb-anchored (spec §3 / §6.13): the brand sits in
    // the upper-center, and the primary action + links live in the lower third so
    // the CTA falls under the thumb on a tall phone. Weighted spacers keep it
    // balanced across screen sizes without hard-coded offsets.
    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = entrance
                translationY = (1f - entrance) * 28.dp.toPx()
            }
            .safeDrawingPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        // ── Brand block ─────────────────────────────────────────────────────
        Image(
            painter = painterResource(R.drawable.splash_bt_glyph),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(22.dp))
        Wordmark(
            fontSize = 40.sp,
            edition = stringResource(R.string.bt_edition_app),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.bt_login_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1.25f))

        // ── Action block (thumb zone) ───────────────────────────────────────
        BtPrimaryButton(
            text = stringResource(R.string.bt_login_button),
            onClick = onLogin,
            loading = inProgress,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .height(54.dp),
        )

        // Human-readable error surface (never a raw string). A fixed min-height
        // reserves the line so the button doesn't jump when an error appears.
        val errorText = (phase as? LoginPhase.Failed)?.let { messageFor(it.error) }
        Spacer(Modifier.height(12.dp))
        Text(
            text = errorText.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = bt.loss,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 18.dp),
        )

        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onNeedAccount, enabled = !inProgress) {
                Text(
                    text = stringResource(R.string.bt_login_need_account),
                    style = MaterialTheme.typography.labelLarge,
                    color = bt.textSecondary,
                )
            }
            Text("·", color = bt.textMuted)
            TextButton(onClick = onForgotPassword, enabled = !inProgress) {
                Text(
                    text = stringResource(R.string.bt_login_forgot_password),
                    style = MaterialTheme.typography.labelLarge,
                    color = bt.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun messageFor(error: LoginError): String = stringResource(
    when (error) {
        LoginError.GENERIC -> R.string.bt_login_error_generic
        LoginError.NETWORK -> R.string.bt_login_error_network
        LoginError.STATE_MISMATCH -> R.string.bt_login_error_state
        LoginError.EXCHANGE_FAILED -> R.string.bt_login_error_exchange
        LoginError.ACCOUNT_DISABLED -> R.string.bt_login_error_disabled
        LoginError.ADMIN_NOT_ALLOWED -> R.string.bt_login_error_admin
        LoginError.SERVER_DENIED -> R.string.bt_login_error_denied
    },
)
