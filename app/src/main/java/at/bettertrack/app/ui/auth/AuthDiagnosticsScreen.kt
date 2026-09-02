package at.bettertrack.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.SignOutEvent
import at.bettertrack.app.data.auth.SignOutReason
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.theme.BtTheme
import java.text.DateFormat
import java.util.Date

/**
 * "Why am I at the login screen again?" — the sign-out history, on the phone.
 *
 * The owner reported being thrown out of the app repeatedly and there was no way
 * to answer him: `logcat` holds hours, the session blob is erased by the very
 * event we want to explain, and a user logout and a server refusal looked
 * identical from the outside. Every transition to signed-out is now recorded
 * (see `SignOutLedger`) and this screen reads it back.
 *
 * It lives behind the login screen's settings gear because that is where the
 * person actually is when the question occurs to them. It renders app-authored
 * sentences, not error strings: the top line says what happened in the user's
 * language, and the second line carries the machine detail (rail, code site,
 * HTTP status, error code) so a report can name a line rather than a feeling.
 *
 * Nothing on it is sensitive — the ledger holds no tokens, no ids, no addresses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthDiagnosticsScreen(onClose: () -> Unit) {
    val bt = BtTheme.colors
    val events = remember { AppGraph.authRepository.signOutHistory() }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.bt_diag_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_diag_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            if (events.isEmpty()) {
                BtGroup {
                    BtGroupRow(
                        title = stringResource(R.string.bt_diag_empty),
                        subtitle = stringResource(R.string.bt_diag_empty_sub),
                    )
                }
            } else {
                BtGroup {
                    events.forEach { event ->
                        BtGroupRow(
                            icon = iconFor(event.reason),
                            title = stringResource(reasonLabelRes(event.reason)),
                            subtitle = eventSubtitle(event),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The second line: when it happened, then the machine facts.
 *
 * Deliberately NOT translated past the timestamp. `REFRESH · TokenManager.doRefresh
 * · HTTP 400 · INVALID_GRANT` is a diagnostic string that has to survive being
 * read aloud, screenshotted and pasted into an issue; a localised version of it
 * would be worse in every one of those uses.
 */
private fun eventSubtitle(event: SignOutEvent): String = buildString {
    append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.at)))
    append(" · ")
    append(event.trigger)
    append(" · ")
    append(event.caller)
    event.httpStatus?.let { append(" · HTTP $it") }
    event.errorCode?.let { append(" · $it") }
}

/** Reason code → the sentence the user reads. Unknown codes still get a row. */
private fun reasonLabelRes(reason: String): Int = when (reason) {
    SignOutReason.USER_LOGOUT.name -> R.string.bt_diag_reason_user_logout
    SignOutReason.APP_LOCK_FORGOT_PIN.name -> R.string.bt_diag_reason_forgot_pin
    SignOutReason.ACCOUNT_DELETED.name -> R.string.bt_diag_reason_account_deleted
    SignOutReason.STORAGE_MODE_SWITCH.name -> R.string.bt_diag_reason_storage_switch
    SignOutReason.ACCOUNT_GATE.name -> R.string.bt_diag_reason_account_gate
    SignOutReason.REFRESH_REJECTED.name -> R.string.bt_diag_reason_refresh_rejected
    SignOutReason.REFRESH_REJECTED_AFTER_LOST_RESPONSE.name -> R.string.bt_diag_reason_refresh_lost
    SignOutReason.SECURE_STORE_UNAVAILABLE.name -> R.string.bt_diag_reason_store_unavailable
    SignOutReason.SECURE_STORE_RECREATED.name -> R.string.bt_diag_reason_store_recreated
    SignOutReason.SESSION_DECODE_FAILED.name -> R.string.bt_diag_reason_decode_failed
    else -> R.string.bt_diag_reason_unknown
}

private fun iconFor(reason: String): ImageVector = when (reason) {
    SignOutReason.USER_LOGOUT.name,
    SignOutReason.ACCOUNT_DELETED.name,
    SignOutReason.STORAGE_MODE_SWITCH.name,
    -> Icons.AutoMirrored.Outlined.Logout

    SignOutReason.APP_LOCK_FORGOT_PIN.name -> Icons.Outlined.Lock

    SignOutReason.REFRESH_REJECTED.name,
    SignOutReason.REFRESH_REJECTED_AFTER_LOST_RESPONSE.name,
    -> Icons.Outlined.CloudOff

    SignOutReason.SECURE_STORE_UNAVAILABLE.name,
    SignOutReason.SECURE_STORE_RECREATED.name,
    SignOutReason.SESSION_DECODE_FAILED.name,
    -> Icons.Outlined.Key

    else -> Icons.Outlined.ReportProblem
}
