package at.bettertrack.app.ui.paranoid

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.auth.FreshStartNoticeFlags
import at.bettertrack.app.data.auth.FreshStartNoticeSession
import at.bettertrack.app.data.auth.SessionUser
import at.bettertrack.app.data.auth.freshStartNoticeDue
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtWebLinkRow
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * Where the web app manages paranoid vaults — the same destination the web's own
 * fresh-start notice links to (`apps/web/src/user/components/FreshStartNotice.tsx`
 * → `/control/privacy`).
 *
 * A path, never a URL: [BtWebLinkRow] joins it to the **effective** web origin,
 * so a self-hosted or dev stack lands on its own server.
 */
internal const val WEB_PARANOID_VAULTS_PATH = "/control/privacy"

/**
 * The one-time **fresh-start notice** — `paranoid-design.md` §17 step 3 (PARANOID
 * E9, platform tick 2026-08-29).
 *
 * ## What it says, and what it refuses to be
 *
 * §17 words it as *"a one-time in-app notice at next login"*, and rules out three
 * things by name: a conversion ceremony, a legacy-passphrase prompt, and — via
 * §21 ruling 4's wording style — an alarm. So this is a short, calm bottom sheet
 * with two paragraphs and one button. Paragraph one is what happened (the
 * verified backup, the removal from the server, the reset to a normal account,
 * the dead passphrase). Paragraph two is what it means (the account is whole;
 * paranoid mode is per-portfolio now). Nothing is red, nothing is a banner, and
 * there is nothing to fill in.
 *
 * ## Why a compact sheet and not a `btSheet<>` subpage
 *
 * Sheets are the app's one transient surface (owner order 2026-08-16 — anchored
 * dropdowns are rejected), but the shell's `btSheet` family is for near
 * FULL-HEIGHT subpages the user NAVIGATED to, and this is neither: two
 * paragraphs stretched to the top of the screen is precisely the blank void the
 * compact-beats-spacious rule forbids, and the notice arrives on its own rather
 * than from a tap, so a back-stack entry for it would be a page the user never
 * asked for. [BtPickerSheet] is the same bottom-sheet chrome at content height —
 * `surfaceHigh`, drag handle, nav-bar insets — and it already owns the two
 * behaviours this needs: `busy` freezes the drag while a write is in flight, and
 * an inline `message` reports the outcome without a snackbar.
 *
 * ## Outcomes are INLINE
 *
 * Progress rides the button, failure is a [BtMessage] under it, and the retry is
 * the same button (relabelled "Try again"). The shell snackbar is deliberately
 * not used: a verdict about the account's own history must stay readable in the
 * surface that asked for it.
 *
 * ## "Once, until acknowledged"
 *
 * Three facts, and only the last one is local:
 *
 *  1. The **server** decides whether the notice is owed —
 *     `MeResponse.paranoidFreshStartPending`, spent by a set-once acknowledgement.
 *  2. Acknowledging answers the fresh `/auth/me`, so the session's copy of that
 *     flag is rebuilt from the server's own body, never from an optimistic
 *     local `false`.
 *  3. [FreshStartNoticeSession] remembers only that the sheet has been PUT ON
 *     SCREEN in this process, so a dismissal does not re-open it a frame later.
 *     It is in memory only and it is not a "seen" flag: dismiss the sheet, or
 *     fail the acknowledgement, and the server flag still stands — so the next
 *     app start asks again.
 *
 * @param user the signed-in session user, straight from the auth gate.
 */
@Composable
internal fun FreshStartNoticeHost(user: SessionUser) {
    // The outer gate. While the flag is off nothing below runs — no state, no
    // effect, no composition — so the build is behaviourally identical to one
    // without the feature. See [FreshStartNoticeFlags.enabled] for why it is off
    // (the acknowledge route refuses this app's bearer today).
    if (!FreshStartNoticeFlags.enabled) return

    val auth = AppGraph.authRepository
    val scope = rememberCoroutineScope()
    // Saveable so a rotation does not drop a notice the user is reading; keyed on
    // the account so a sign-out-and-in as somebody else starts clean.
    var visible by rememberSaveable(user.id) { mutableStateOf(false) }
    var busy by remember(user.id) { mutableStateOf(false) }
    var failure by remember(user.id) { mutableStateOf<BtMessage?>(null) }

    LaunchedEffect(user.id, user.paranoidFreshStartPending) {
        val due = freshStartNoticeDue(
            signedIn = user.id.isNotEmpty(),
            pending = user.paranoidFreshStartPending,
            shownThisSession = FreshStartNoticeSession.wasShown(user.id),
        )
        if (due) {
            FreshStartNoticeSession.markShown(user.id)
            visible = true
        }
    }

    if (!visible) return

    FreshStartNoticeSheet(
        busy = busy,
        failure = failure,
        // Unconditional, even while busy — the same rule [BtPickerSheet]'s
        // scaffold documents: the scrim and system back HIDE the sheet before
        // this fires, so refusing here would strand an invisible modal over the
        // app. Refusing the drag is `busy`'s job. The request runs on this
        // composable's scope and settles either way; a dismissal simply means
        // the server flag stands and the notice returns next start.
        onDismiss = { visible = false },
        onAcknowledge = {
            busy = true
            failure = null
            scope.launch {
                when (val r = auth.acknowledgeFreshStartNotice()) {
                    is BtResult.Ok -> {
                        busy = false
                        visible = false
                    }

                    is BtResult.Err -> {
                        failure = r.error.asMessage()
                        busy = false
                    }
                }
            }
        },
    )
}

/**
 * The notice itself, as a pure function of its inputs so it can be previewed and
 * reasoned about without a session.
 */
@Composable
private fun FreshStartNoticeSheet(
    busy: Boolean,
    failure: BtMessage?,
    onDismiss: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val bt = BtTheme.colors
    BtPickerSheet(
        title = stringResource(R.string.bt_fresh_start_title),
        onDismiss = onDismiss,
        busy = busy,
        message = failure,
        footer = {
            BtPrimaryButton(
                // The button IS the retry. A failed acknowledgement leaves the
                // notice owed, so offering a second, differently-worded control
                // beside it would suggest two outcomes where there is one.
                text = stringResource(
                    if (failure == null) R.string.bt_fresh_start_ack else R.string.bt_action_retry,
                ),
                onClick = onAcknowledge,
                enabled = !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Text(
            text = stringResource(R.string.bt_fresh_start_what),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
        Text(
            text = stringResource(R.string.bt_fresh_start_means),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
        Spacer(Modifier.height(2.dp))
        // ONE labelled row for ONE web-only job (owner rule 2026-08-09): vault
        // creation is not in the app — `ParanoidVaultsFlags.enabled` is false —
        // so the notice must not imply it is, and must not fold the hand-off
        // into a blanket "the rest is on the web".
        BtGroup {
            BtWebLinkRow(
                title = stringResource(R.string.bt_fresh_start_web_title),
                subtitle = stringResource(R.string.bt_fresh_start_web_sub),
                icon = Icons.Outlined.Lock,
                path = WEB_PARANOID_VAULTS_PATH,
            )
        }
    }
}
