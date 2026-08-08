package at.bettertrack.app.ui.auth

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The BetterTrack login screen (spec §3.2 / §4): the wordmark + "App" edition
 * large, the muted tagline, a single gold "Login with BetterTrack" primary
 * action, and subtle "Need an account?" / "Forgot password?" links that open the
 * web. An in-progress state covers the Custom Tab + token exchange; a
 * human-readable error surfaces on failure (a user closing the tab is silent).
 *
 * V5 W5 adds two optional affordances, both `null` in the ordinary logged-out
 * gate so that screen is unchanged:
 *
 * @param onUseWithoutAccount when non-null, offers the Drive-only branch (plan
 *   §4.2 step 6). Passed **only** from inside the first-run wizard: someone who
 *   already has a BetterTrack account must never be nudged toward abandoning it,
 *   and someone who has not chosen yet deserves to know the option exists.
 * @param onBack when non-null, shows a back affordance — the wizard's login step
 *   is reachable by choice and must be leavable the same way.
 *
 * ## Pre-login settings (owner order 2026-08-08)
 *
 * Owner verbatim: *"on the login page there should be a small setting thing up
 * top corner so you move the change server and other settings you need to do
 * before login there. still keep the display of the server on the bottom but
 * only visual text no button."*
 *
 * So the screen has exactly one control for everything you may need to set
 * before you can sign in — a quiet gear in the top-end corner, opening
 * [PreLoginSettingsSheet] — and the bottom "Server: <host>" line is now what it
 * always read as: a **statement**, not a button. It answers "which backend am I
 * signing in to" at a glance, which is what almost everyone needs from it; the
 * one person who needs to *change* it goes to the corner, the same corner the
 * gear lives in on every tab once they are inside the app.
 *
 * The corner is also why the gear is the only thing that grew: the 2026-08-04
 * ask that put the server affordance here in the first place is unchanged in
 * substance — you have to be able to pick a server BEFORE you sign in to one,
 * because Settings lives behind the login.
 *
 * @param serverHost when non-null, shows that bottom "Server: <host>" line.
 *   Passed only where the setting exists (the `github` flavor).
 * @param onOpenServer opens the Server screen, from the **sheet's** Server row.
 *   Null where the flavor has no server setting, which is what removes that row.
 */
@Composable
fun LoginScreen(
    phase: LoginPhase,
    onLogin: () -> Unit,
    onNeedAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPressWordmark: () -> Unit = {},
    onUseWithoutAccount: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    serverHost: String? = null,
    onOpenServer: (() -> Unit)? = null,
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

    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entrance
                    translationY = (1f - entrance) * 28.dp.toPx()
                }
                .safeDrawingPadding(),
        ) {
            // Layout is deliberately thumb-anchored (spec §3 / §6.13): the brand
            // sits in the upper-center, and the primary action + links live in the
            // lower third so the CTA falls under the thumb on a tall phone.
            // Weighted spacers keep it balanced across screen sizes without
            // hard-coded offsets.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The chrome row below is drawn OVER this column rather than in
                // it, so that whether `onBack` exists cannot move the brand. This
                // reserves its height instead.
                Spacer(Modifier.height(CHROME_HEIGHT))
                Spacer(Modifier.weight(1f))

                // ── Brand block ─────────────────────────────────────────────
                Image(
                    painter = painterResource(R.drawable.splash_bt_glyph),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(22.dp))
                // Long-press = the hidden dev-backend screen on debug builds (V5
                // S1). Inert in release: BtRoot ignores the callback outside
                // BuildConfig.DEBUG. Kept as-is next to the gear: it is a
                // developer shortcut, not a second front door.
                Box(
                    modifier = Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onLongClick = onLongPressWordmark,
                        onClick = {},
                    ),
                ) {
                    Wordmark(
                        fontSize = 40.sp,
                        edition = stringResource(R.string.bt_edition_app),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.bt_login_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textMuted,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.weight(1.25f))

                // ── Action block (thumb zone) ───────────────────────────────
                BtPrimaryButton(
                    text = stringResource(R.string.bt_login_button),
                    onClick = onLogin,
                    loading = inProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 480.dp)
                        .height(54.dp),
                )

                // Human-readable error surface (never a raw string). A fixed
                // min-height reserves the line so the button doesn't jump when an
                // error appears.
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

                // The Drive-only escape hatch. Set apart from the account links
                // above by a divider-free gap and muted styling: it is a genuinely
                // different kind of choice, not a third variation on "sign in".
                if (onUseWithoutAccount != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onUseWithoutAccount, enabled = !inProgress) {
                        Text(
                            text = stringResource(R.string.bt_login_use_without_account),
                            style = MaterialTheme.typography.labelLarge,
                            color = bt.goldInk,
                        )
                    }
                }

                // ── Server display ─────────────────────────────────────────
                // Owner order 2026-08-08: *"still keep the display of the server
                // on the bottom but only visual text no button."* So this is a
                // plain [Text] — no ripple, no click, no button semantics, nothing
                // for a screen reader or a focus ring to land on. It states the
                // current host, which is all almost anyone needs from it; changing
                // it is the gear's job now, in the corner where the gear lives
                // everywhere else in the app.
                if (serverHost != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.bt_login_server_label, serverHost),
                        style = MaterialTheme.typography.labelMedium,
                        color = bt.textMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                } else {
                    Spacer(Modifier.height(24.dp))
                }
            }

            // ── Chrome row ─────────────────────────────────────────────────
            // Back at the start edge (wizard only), the settings gear at the end
            // edge, both at the true corners rather than inside the content's 32dp
            // gutter. Out of the column's flow on purpose: the gear's address must
            // not depend on whether this instance of the screen has a back
            // affordance. Disabled mid-login like every other control here — a
            // Custom Tab is open and the server is about to change under it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .height(CHROME_HEIGHT)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    TextButton(onClick = onBack, enabled = !inProgress) {
                        Text(
                            text = stringResource(R.string.bt_action_back),
                            style = MaterialTheme.typography.labelLarge,
                            color = bt.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showSettings = true },
                    enabled = !inProgress,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.bt_dest_settings),
                        // The screen's chrome tone, and a glyph two dp smaller
                        // than the app's own gear: this one sits on a page with
                        // nothing else on it, so it reads plenty loud at 22dp. The
                        // 48dp IconButton target around it is untouched.
                        tint = bt.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        if (showSettings) {
            PreLoginSettingsSheet(
                onDismiss = { showSettings = false },
                onOpenServer = onOpenServer,
            )
        }
    }
}

/**
 * The height reserved for the top chrome row — one standard 48dp touch target.
 * Stated once because the column reserves it and the row occupies it, and the
 * two must not drift.
 */
private val CHROME_HEIGHT = 48.dp

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
