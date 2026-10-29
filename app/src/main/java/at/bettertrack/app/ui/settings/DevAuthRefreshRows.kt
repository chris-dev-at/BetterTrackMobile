package at.bettertrack.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.AuthFault
import at.bettertrack.app.data.auth.RefreshJournalEntry
import at.bettertrack.app.data.auth.RefreshStatus
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.LocalBtSnackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DEBUG ONLY (rendered inside Settings' hidden Developer group, which is itself
 * behind `BuildConfig.DEBUG`): drive and watch the token refresh rail on a
 * device.
 *
 *  - **Auth faults: next refresh** cycles the one-shot fault the debug-only
 *    `AuthFaultInterceptor` applies to the next `POST /oauth/token` refresh.
 *  - **Refresh token now** runs one refresh through the real rail
 *    (`TokenManager.retryRefreshNow`), in the app scope so leaving the screen
 *    cannot cancel it.
 *  - **Token refresh state** is the live rail state plus the newest journal rows
 *    — machine text, untranslated, like the sign-out diagnostics' second line.
 */
@Composable
internal fun DevAuthRefreshRows() {
    val prefs = AppGraph.devicePrefs
    val tokenManager = AppGraph.tokenManager
    val snackbar = LocalBtSnackbar.current
    val scope = rememberCoroutineScope()
    val fault by prefs.debugAuthFault.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf(RefreshStatus()) }
    var journal by remember { mutableStateOf(emptyList<RefreshJournalEntry>()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            status = tokenManager.refreshStatusSnapshot()
            journal = AppGraph.refreshJournal.recent().take(DEV_JOURNAL_ROWS)
            delay(1_000L)
        }
    }

    BtGroupRow(
        icon = Icons.Outlined.BugReport,
        title = stringResource(R.string.bt_settings_dev_auth_fault),
        subtitle = stringResource(faultLabel(fault)),
        onClick = { prefs.setDebugAuthFault(nextFault(fault)) },
    )
    BtGroupRow(
        icon = Icons.Outlined.Refresh,
        title = stringResource(R.string.bt_settings_dev_auth_refresh_now),
        subtitle = stringResource(R.string.bt_settings_dev_auth_refresh_now_sub),
        onClick = {
            if (busy) return@BtGroupRow
            busy = true
            // The app scope, not the composition: the refresh is non-cancellable
            // once it starts anyway, and the snackbar is best-effort.
            AppGraph.appScope.launch {
                val ok = tokenManager.retryRefreshNow()
                scope.launch {
                    snackbar.show(
                        if (ok) R.string.bt_settings_dev_auth_refresh_ok else R.string.bt_settings_dev_auth_refresh_failed,
                    )
                    busy = false
                }
            }
        },
    )
    BtGroupRow(
        icon = Icons.Outlined.Key,
        title = stringResource(R.string.bt_settings_dev_auth_state),
        subtitle = devRefreshStateText(status, journal, System.currentTimeMillis()),
        subtitleMaxLines = 24,
    )
}

private const val DEV_JOURNAL_ROWS = 8

internal fun nextFault(current: AuthFault): AuthFault =
    AuthFault.entries[(current.ordinal + 1) % AuthFault.entries.size]

private fun faultLabel(fault: AuthFault): Int = when (fault) {
    AuthFault.NONE -> R.string.bt_settings_dev_auth_fault_none
    AuthFault.LOST_ANSWER -> R.string.bt_settings_dev_auth_fault_lost
    AuthFault.RATE_LIMITED -> R.string.bt_settings_dev_auth_fault_429
    AuthFault.TRANSPORT -> R.string.bt_settings_dev_auth_fault_transport
    AuthFault.KILL_AFTER_SEND -> R.string.bt_settings_dev_auth_fault_kill
}

/** The panel's text. Pure; no token or fingerprint can reach it ([RefreshStatus] carries none it prints). */
internal fun devRefreshStateText(status: RefreshStatus, journal: List<RefreshJournalEntry>, nowMs: Long): String =
    buildString {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        fun t(ms: Long) = clock.format(Date(ms))
        append("degraded=").append(if (status.degraded) "yes" else "no")
        status.backoff?.let { b ->
            val left = (b.notBeforeMs - nowMs).coerceAtLeast(0L) / 1000L
            append("\nbackoff=").append(b.kind).append(" until ").append(t(b.notBeforeMs))
                .append(" (").append(left).append(" s left, failures ").append(b.failures).append(")")
        } ?: append("\nbackoff=none")
        status.pending?.let { p ->
            append("\npending replay: attempt ").append(p.attempts)
                .append(", first sent ").append(t(p.firstSentAtMs))
                .append(", last sent ").append(t(p.lastSentAtMs))
        } ?: append("\npending replay: none")
        status.lastVerdict?.let { v ->
            append("\nlast=").append(v)
            status.lastVerdictAtMs?.let { append(" at ").append(t(it)) }
        }
        status.accessExpiresAtMs?.let { append("\naccess token expires ").append(t(it)) }
        if (journal.isNotEmpty()) append("\n— journal (newest first) —")
        journal.forEach { e ->
            append("\n").append(t(e.at)).append(' ').append(e.caller).append(" #").append(e.attempt)
                .append(' ').append(e.verdict)
            e.httpStatus?.let { append(" HTTP ").append(it) }
            e.errorCode?.let { append(' ').append(it) }
            e.waitMs?.let { append(" wait ").append(it).append(" ms") }
        }
    }
