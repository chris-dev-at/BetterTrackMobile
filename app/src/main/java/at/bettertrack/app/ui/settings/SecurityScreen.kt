package at.bettertrack.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import androidx.compose.material.icons.outlined.Password
import androidx.compose.runtime.LaunchedEffect
import at.bettertrack.app.data.account.AccountPinState
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.BT_PIN_IDLE_DEFAULT
import at.bettertrack.app.data.applock.AfkThreshold
import at.bettertrack.app.data.applock.PinSource
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.applock.BiometricAvailability
import at.bettertrack.app.ui.applock.rememberBiometricAvailability
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtWebLinkRow
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.rememberBtHaptics
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Settings → Security (spec §6.12). Step 17 fills in the **app lock** config
 * (enable/disable, change PIN, biometric unlock, and the AFK re-lock threshold),
 * all LIVE against the local [at.bettertrack.app.data.applock.AppLockController].
 * Step 18 extends this same screen with 2FA management + active sessions above
 * the app-lock section.
 *
 * ## R2 visual pass
 *
 * Two sections, two [BtGroup]s. Before, each of the six rows was its own
 * bordered surface, so "account security" and "app lock" looked like one
 * undifferentiated stack of six boxes — the section labels were the only thing
 * saying otherwise, and a label loses that argument against a border. The tonal
 * step now does the grouping (mandate §4) and the labels merely name what is
 * already visibly one block. The app-lock footnote stays outside both groups: it
 * is prose about the feature, not a row of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    onSetupPin: () -> Unit,
    onChangePin: () -> Unit,
    onOpenTwoFactor: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
    onOpenAccountPin: () -> Unit = {},
) {
    val bt = BtTheme.colors
    val controller = AppGraph.appLockController
    val config by controller.config.collectAsStateWithLifecycle()

    var showDisableConfirm by remember { mutableStateOf(false) }
    var showThresholdPicker by remember { mutableStateOf(false) }

    // The account PIN's state, for the row's subtitle only. A failed read leaves
    // it null and the row says nothing about the state rather than guessing —
    // "Off" on a network error would be a lie about a security setting, and the
    // screen behind the row reports the failure properly.
    var accountPin by remember { mutableStateOf<AccountPinState?>(null) }
    LaunchedEffect(Unit) {
        accountPin = (AppGraph.accountRepository.accountPinState() as? BtResult.Ok)?.value
    }

    val lockOn = config.enabled && config.hasPin

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_settings_security),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
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
            // ── Account security (2FA + passkeys + sessions) — bearer + account:security ──
            BtSectionHeader(stringResource(R.string.bt_settings_account_security_section))
            BtGroup {
                BtGroupRow(
                    icon = Icons.Outlined.Shield,
                    title = stringResource(R.string.bt_dest_two_factor),
                    subtitle = stringResource(R.string.bt_settings_2fa_sub),
                    onClick = onOpenTwoFactor,
                )
                // Passkeys — deferred to the web (parity ruling 2026-08-08), and
                // placed HERE rather than anywhere else in Settings because this
                // group is where "how you prove you are you" lives: it sits
                // directly under two-factor, which is the same question answered
                // with a different credential, and above active sessions, which is
                // the *result* of having proved it. The app-lock section below is
                // a different subject entirely — that PIN guards this phone, not
                // the account.
                //
                // Not reimplemented natively on purpose: registering a passkey is
                // a WebAuthn ceremony bound to an origin, and doing it in a Custom
                // Tab on the real web origin is the ONLY way the credential ends
                // up scoped to the same origin the browser will later be asked to
                // authenticate against.
                BtWebLinkRow(
                    icon = Icons.Outlined.Fingerprint,
                    title = stringResource(R.string.bt_settings_passkeys),
                    subtitle = stringResource(R.string.bt_settings_managed_on_web),
                    path = "/control/sign-in",
                )
                // The ACCOUNT PIN — the credential the account itself carries,
                // asked for wherever the user signs in. It belongs in this group
                // for the same reason as two-factor and passkeys: this is the
                // "how you prove you are you" block. The app-lock section below
                // is a different subject, and the two must stay legible as two —
                // hence a distinct title, a distinct icon, its own screen and its
                // own `bt_accountpin_*` string namespace.
                //
                // Until the platform's bearer allowlist reached PUT/DELETE
                // /auth/pin, this was a "change it on the web" note. It is not
                // one any more.
                BtGroupRow(
                    icon = Icons.Outlined.Password,
                    title = stringResource(R.string.bt_accountpin_title),
                    subtitle = accountPinSubtitle(accountPin),
                    onClick = onOpenAccountPin,
                )
                BtGroupRow(
                    icon = Icons.Outlined.Devices,
                    title = stringResource(R.string.bt_dest_active_sessions),
                    subtitle = stringResource(R.string.bt_settings_sessions_sub),
                    onClick = onOpenSessions,
                )
            }

            Spacer(Modifier.height(4.dp))

            BtSectionHeader(stringResource(R.string.bt_settings_applock_section))

            // Everything the app lock IS lives in one group: the master switch and
            // the three settings it governs. That the extra rows appear inside the
            // block the master switch is already in is the clearest possible way to
            // say they belong to it — the old layout put them in separate boxes and
            // relied on adjacency alone.
            BtGroup {
                // Master enable/disable — turning ON opens the set-up flow; the lock
                // only actually enables once a PIN is confirmed there.
                SecurityToggleRow(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.bt_settings_applock_title),
                    subtitle = stringResource(R.string.bt_settings_applock_sub),
                    checked = lockOn,
                    enabled = true,
                    onCheckedChange = { want ->
                        if (want) onSetupPin() else showDisableConfirm = true
                    },
                )

                if (lockOn) {
                    // Change PIN. When the active lock reuses the BetterTrack account
                    // (web) PIN, changing it is managed on the web — not bridged to the
                    // API (owner directive 2026-07-09). The row stays full-size but is
                    // disabled with a short explanation; a device PIN keeps the normal
                    // change flow.
                    val webPinLock = config.pinSource == PinSource.BETTERTRACK
                    BtGroupRow(
                        icon = Icons.Outlined.Lock,
                        // `BtGroupRow` has no `enabled`, and it does not need one: a
                        // null `onClick` already means "not tappable" AND drops the
                        // chevron, so a managed row cannot advertise a destination it
                        // will not take you to. The muted title/icon carry the rest,
                        // exactly as the bordered row did.
                        iconTint = if (webPinLock) bt.textMuted else null,
                        titleColor = if (webPinLock) bt.textMuted else null,
                        title = stringResource(R.string.bt_settings_applock_change),
                        subtitle = stringResource(
                            if (webPinLock) R.string.bt_settings_applock_change_managed
                            else R.string.bt_settings_applock_change_sub,
                        ),
                        onClick = if (webPinLock) null else onChangePin,
                    )

                    // Biometric convenience — GATED on real availability (Step-17
                    // refinement). The toggle can only be turned ON when a biometric is
                    // actually enrolled + usable; otherwise it's greyed with a hint that
                    // says what to do. Availability re-reads on resume, so enrolling a
                    // fingerprint in Android settings and returning enables it live.
                    val biometricAvail = rememberBiometricAvailability()
                    val biometricReady = biometricAvail == BiometricAvailability.AVAILABLE
                    SecurityToggleRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = stringResource(R.string.bt_settings_applock_biometric),
                        subtitle = stringResource(
                            when (biometricAvail) {
                                BiometricAvailability.AVAILABLE -> R.string.bt_settings_applock_biometric_sub
                                BiometricAvailability.NONE_ENROLLED -> R.string.bt_settings_applock_biometric_none
                                BiometricAvailability.UNAVAILABLE -> R.string.bt_settings_applock_biometric_unavailable
                            },
                        ),
                        // A greyed toggle always reads OFF — it cannot be turned on until
                        // a biometric exists (guards against a stale stored "on").
                        checked = config.biometricEnabled && biometricReady,
                        enabled = biometricReady,
                        onCheckedChange = { controller.setBiometricEnabled(it) },
                    )

                    BtGroupRow(
                        icon = Icons.Outlined.Timer,
                        title = stringResource(R.string.bt_settings_applock_threshold),
                        subtitle = thresholdLabel(config.afkThreshold),
                        onClick = { showThresholdPicker = true },
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.bt_settings_applock_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }

    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            containerColor = bt.surfaceHigh,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_settings_applock_disable_title)) },
            text = { Text(stringResource(R.string.bt_settings_applock_disable_message)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.disableLock()
                    showDisableConfirm = false
                }) { Text(stringResource(R.string.bt_settings_applock_disable_confirm), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirm = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }

    if (showThresholdPicker) {
        ThresholdPickerDialog(
            current = config.afkThreshold,
            onPick = { controller.setAfkThreshold(it); showThresholdPicker = false },
            onDismiss = { showThresholdPicker = false },
        )
    }
}

/**
 * The account-PIN row's subtitle.
 *
 * Null state (the read failed or has not landed) falls back to the generic
 * one-line description rather than claiming the PIN is off.
 */
@Composable
private fun accountPinSubtitle(state: AccountPinState?): String = when {
    state == null -> stringResource(R.string.bt_accountpin_intro)
    !state.pinSet -> stringResource(R.string.bt_accountpin_row_sub_off)
    else -> stringResource(
        R.string.bt_accountpin_row_sub_on,
        accountPinIdleText(state.idleMinutes ?: BT_PIN_IDLE_DEFAULT),
    )
}

@Composable
private fun accountPinIdleText(minutes: Int): String = when (minutes) {
    1 -> stringResource(R.string.bt_accountpin_1m)
    5 -> stringResource(R.string.bt_accountpin_5m)
    10 -> stringResource(R.string.bt_accountpin_10m)
    15 -> stringResource(R.string.bt_accountpin_15m)
    30 -> stringResource(R.string.bt_accountpin_30m)
    60 -> stringResource(R.string.bt_accountpin_60m)
    else -> "$minutes min"
}

@Composable
private fun thresholdLabel(threshold: AfkThreshold): String = stringResource(
    when (threshold) {
        AfkThreshold.Immediately -> R.string.bt_applock_afk_immediately
        AfkThreshold.OneMinute -> R.string.bt_applock_afk_1m
        AfkThreshold.FiveMinutes -> R.string.bt_applock_afk_5m
        AfkThreshold.FifteenMinutes -> R.string.bt_applock_afk_15m
    },
)

@Composable
private fun ThresholdPickerDialog(
    current: AfkThreshold,
    onPick: (AfkThreshold) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surfaceHigh,
        titleContentColor = bt.textPrimary,
        title = { Text(stringResource(R.string.bt_settings_applock_threshold)) },
        text = {
            Column {
                AfkThreshold.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == current, onClick = { onPick(option) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == current,
                            onClick = { onPick(option) },
                            colors = RadioButtonDefaults.colors(selectedColor = bt.gold, unselectedColor = bt.textMuted),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(thresholdLabel(option), color = bt.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_action_done), color = bt.goldInk) }
        },
    )
}

// R2: `SectionLabel` and `SecurityNavRow` are gone — `BtSectionHeader` and
// `BtGroupRow` replace them, which is what stops each settings subscreen growing
// its own slightly-different row.

/**
 * A switch row inside a [BtGroup].
 *
 * Local rather than inlined at both call sites because `BtGroupRow` owns no
 * switch: the [Switch] goes in its `trailing` slot, which also suppresses the
 * chevron so the row never suggests it navigates. The whole row is the tap
 * target when [enabled] — a switch is a 32dp target at the far edge of a 360dp
 * screen, and making the label work is the difference between a settings list
 * you can use one-handed and one you have to aim at.
 *
 * [enabled] false mutes the title/icon and removes BOTH targets (row and
 * switch), which is the greyed state the biometric row needs when no biometric
 * is enrolled.
 */
@Composable
private fun SecurityToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    // R3 §4 — same toggle haptic as every other settings switch (see
    // SettingsScreen's SettingsToggleRow); the two rows look alike, so they must
    // feel alike.
    val haptics = rememberBtHaptics()
    val commit: (Boolean) -> Unit = { on -> haptics.toggle(on); onCheckedChange(on) }
    val rowClick: (() -> Unit)? = if (enabled) ({ commit(!checked) }) else null
    BtGroupRow(
        icon = icon,
        iconTint = if (enabled) null else bt.textMuted,
        title = title,
        titleColor = if (enabled) null else bt.textMuted,
        subtitle = subtitle,
        onClick = rowClick,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = commit,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = bt.onGold,
                    checkedTrackColor = bt.gold,
                    checkedBorderColor = bt.gold,
                    uncheckedThumbColor = bt.textMuted,
                    uncheckedTrackColor = bt.surface,
                    uncheckedBorderColor = bt.borderStrong,
                ),
            )
        },
    )
}
