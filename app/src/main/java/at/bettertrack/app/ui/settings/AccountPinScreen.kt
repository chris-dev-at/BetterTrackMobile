package at.bettertrack.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.account.AccountPinState
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.BT_ACCOUNT_PIN_LENGTH
import at.bettertrack.app.data.api.dto.BT_PIN_IDLE_DEFAULT
import at.bettertrack.app.data.api.dto.BT_PIN_IDLE_PRESETS
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.applock.PinDots
import at.bettertrack.app.ui.applock.PinKeypad
import at.bettertrack.app.ui.components.BtChoiceSheet
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtPickerOption
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * Settings → Security → **Account PIN**.
 *
 * ## What this is, and what it deliberately is not
 *
 * This screen manages the PIN stored on the BetterTrack ACCOUNT — the same one
 * the web asks for, applying wherever the user signs in. It is NOT the device
 * app lock, whose PIN, biometric toggle and AFK threshold live one screen up
 * under "App lock" and guard this handset only.
 *
 * The two were merged in the user's head for a while precisely because both are
 * called "PIN" and both have an idle timeout, so this screen states the
 * distinction in prose rather than trusting placement to carry it. They also
 * keep separate string namespaces (`bt_accountpin_*` vs `bt_applock_*`) so a
 * copy edit to one can never silently reword the other.
 *
 * The note the app used to show — "Managed by your BetterTrack account — change
 * it on the web" — predates the bearer allowlist reaching `PUT/DELETE /auth/pin`.
 * The phone can do this now, so it does.
 *
 * ## Why setting a PIN asks for no credential
 *
 * Because the server asks for none: `PUT /auth/pin` takes the new PIN and
 * nothing else. That is the platform's deliberate design — the PIN is a privacy
 * curtain over an already-authenticated session rather than a second factor —
 * and inventing a confirmation the server does not require would be theatre
 * that teaches the wrong thing about what the PIN protects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPinScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val scope = rememberCoroutineScope()
    val snackbar = LocalBtSnackbar.current

    var state by remember { mutableStateOf<AccountPinState?>(null) }
    var loadFailure by remember { mutableStateOf<BtMessage?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    var writeFailure by remember { mutableStateOf<BtMessage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var entryOpen by remember { mutableStateOf(false) }
    var idleOpen by remember { mutableStateOf(false) }
    var disableOpen by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loaded = false
        when (val r = repo.accountPinState()) {
            is BtResult.Ok -> {
                state = r.value
                loadFailure = null
            }

            is BtResult.Err -> loadFailure = r.error.asMessage()
        }
        loaded = true
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_accountpin_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_accountpin_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            val current = state
            when {
                !loaded -> {
                    BtSkeleton(Modifier.fillMaxWidth().height(56.dp), shape = BtShapes.group)
                    BtSkeleton(Modifier.fillMaxWidth().height(112.dp), shape = BtShapes.card)
                }

                current == null -> BtScrollFill {
                    BtInlineError(
                        message = loadFailure ?: BtMessage.generic,
                        onRetry = { reload++ },
                    )
                }

                else -> {
                    BtSectionHeader(stringResource(R.string.bt_accountpin_section))
                    BtGroup {
                        BtGroupRow(
                            icon = Icons.Outlined.Password,
                            title = stringResource(
                                if (current.pinSet) R.string.bt_accountpin_change
                                else R.string.bt_accountpin_set,
                            ),
                            subtitle = stringResource(
                                if (current.pinSet) R.string.bt_accountpin_change_sub
                                else R.string.bt_accountpin_set_sub,
                            ),
                            onClick = if (busy) null else ({ writeFailure = null; entryOpen = true }),
                        )

                        if (current.pinSet) {
                            // The idle timeout only means something while a PIN
                            // exists; showing it otherwise would be a control over
                            // nothing.
                            BtGroupRow(
                                icon = Icons.Outlined.Timer,
                                title = stringResource(R.string.bt_accountpin_idle),
                                subtitle = idleLabel(current.idleMinutes),
                                onClick = if (busy) null else ({ writeFailure = null; idleOpen = true }),
                            )
                            BtGroupRow(
                                icon = Icons.Outlined.LockReset,
                                iconTint = bt.loss,
                                titleColor = bt.loss,
                                title = stringResource(R.string.bt_accountpin_disable),
                                subtitle = stringResource(R.string.bt_accountpin_disable_sub),
                                onClick = if (busy) null else ({ writeFailure = null; disableOpen = true }),
                            )
                        }
                    }

                    writeFailure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }

                    Spacer(Modifier.height(2.dp))
                    // Only while a PIN exists. Device QA caught this line reading
                    // "…asked again only after THIS long" on a screen with no
                    // timeout row and no duration on it — a sentence pointing at
                    // something that was not there.
                    if (current.pinSet) {
                        Text(
                            text = stringResource(R.string.bt_accountpin_idle_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                    Text(
                        text = stringResource(R.string.bt_accountpin_applock_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
        }
    }

    if (entryOpen) {
        AccountPinEntrySheet(
            busy = busy,
            message = writeFailure,
            onDismiss = { if (!busy) entryOpen = false },
            onSubmit = { pin ->
                busy = true
                writeFailure = null
                scope.launch {
                    when (val r = repo.setAccountPin(pin)) {
                        is BtResult.Ok -> {
                            state = r.value
                            busy = false
                            entryOpen = false
                            snackbar.show(R.string.bt_accountpin_saved)
                        }

                        is BtResult.Err -> {
                            writeFailure = r.error.asMessage()
                            busy = false
                        }
                    }
                }
            },
        )
    }

    if (idleOpen) {
        val currentMinutes = state?.idleMinutes ?: BT_PIN_IDLE_DEFAULT
        BtChoiceSheet(
            title = stringResource(R.string.bt_accountpin_idle),
            subtitle = stringResource(R.string.bt_accountpin_idle_sub),
            options = BT_PIN_IDLE_PRESETS.map { minutes ->
                BtPickerOption(value = minutes.toString(), label = idleMinutesLabel(minutes))
            },
            selected = currentMinutes.toString(),
            busy = busy,
            onPick = { value ->
                val minutes = value.toIntOrNull() ?: return@BtChoiceSheet
                busy = true
                writeFailure = null
                scope.launch {
                    when (val r = repo.setPinIdleTimeout(minutes)) {
                        is BtResult.Ok -> state = r.value
                        is BtResult.Err -> writeFailure = r.error.asMessage()
                    }
                    busy = false
                    idleOpen = false
                }
            },
            onDismiss = { if (!busy) idleOpen = false },
        )
    }

    if (disableOpen) {
        AlertDialog(
            onDismissRequest = { if (!busy) disableOpen = false },
            containerColor = bt.surfaceHigh,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_accountpin_disable_title)) },
            text = { Text(stringResource(R.string.bt_accountpin_disable_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        writeFailure = null
                        scope.launch {
                            when (val r = repo.disableAccountPin()) {
                                is BtResult.Ok -> {
                                    state = r.value
                                    disableOpen = false
                                    snackbar.show(R.string.bt_accountpin_disabled)
                                }

                                is BtResult.Err -> {
                                    writeFailure = r.error.asMessage()
                                    disableOpen = false
                                }
                            }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.bt_accountpin_disable_confirm), color = bt.loss) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { disableOpen = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

/**
 * Enter a new PIN twice.
 *
 * Two stages in one sheet rather than two screens: the second entry is a
 * confirmation of the first, and putting it behind a navigation step would make
 * a typo feel like a journey. A mismatch resets to stage one with the error
 * showing, because the honest recovery from "these did not match" is to choose
 * again, not to re-confirm a PIN the user may have mistyped first.
 */
@Composable
private fun AccountPinEntrySheet(
    busy: Boolean,
    message: BtMessage?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var entered by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    at.bettertrack.app.ui.components.BtPickerSheet(
        title = stringResource(
            if (confirming) R.string.bt_accountpin_confirm else R.string.bt_accountpin_enter,
        ),
        subtitle = stringResource(R.string.bt_accountpin_hint),
        busy = busy,
        message = message,
        onDismiss = onDismiss,
    ) {
        val bt = BtTheme.colors
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PinDots(
                filled = entered.length,
                total = BT_ACCOUNT_PIN_LENGTH,
                error = mismatch,
            )
            if (mismatch) {
                Text(
                    text = stringResource(R.string.bt_accountpin_mismatch),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }
            PinKeypad(
                enabled = !busy,
                onDigit = { digit ->
                    if (entered.length >= BT_ACCOUNT_PIN_LENGTH) return@PinKeypad
                    mismatch = false
                    val next = entered + digit
                    entered = next
                    if (next.length < BT_ACCOUNT_PIN_LENGTH) return@PinKeypad
                    if (!confirming) {
                        first = next
                        entered = ""
                        confirming = true
                    } else if (next == first) {
                        onSubmit(next)
                    } else {
                        // Back to the start: a mismatch means one of the two was
                        // wrong and we cannot know which.
                        first = ""
                        entered = ""
                        confirming = false
                        mismatch = true
                    }
                },
                onBackspace = {
                    mismatch = false
                    entered = entered.dropLast(1)
                },
            )
        }
    }
}

/** The idle-timeout row's value, naming the server default when none is chosen. */
@Composable
private fun idleLabel(idleMinutes: Int?): String =
    if (idleMinutes == null) {
        stringResource(R.string.bt_accountpin_idle_default, idleMinutesLabel(BT_PIN_IDLE_DEFAULT))
    } else {
        idleMinutesLabel(idleMinutes)
    }

/**
 * A minute count as words.
 *
 * The presets are a closed set, but the server accepts 1–1440 and the account
 * may already hold a value chosen elsewhere. An unrecognised one is printed as
 * minutes rather than rounded to the nearest preset — showing "15 minutes" for a
 * stored 20 would be a quiet lie about the user's own setting.
 */
@Composable
private fun idleMinutesLabel(minutes: Int): String = when (minutes) {
    1 -> stringResource(R.string.bt_accountpin_1m)
    5 -> stringResource(R.string.bt_accountpin_5m)
    10 -> stringResource(R.string.bt_accountpin_10m)
    15 -> stringResource(R.string.bt_accountpin_15m)
    30 -> stringResource(R.string.bt_accountpin_30m)
    60 -> stringResource(R.string.bt_accountpin_60m)
    else -> "$minutes min"
}
