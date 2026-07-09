package at.bettertrack.app.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.applock.AfkThreshold
import at.bettertrack.app.data.applock.PinSource
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.applock.BiometricAvailability
import at.bettertrack.app.ui.applock.rememberBiometricAvailability
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Settings → Security (spec §6.12). Step 17 fills in the **app lock** config
 * (enable/disable, change PIN, biometric unlock, and the AFK re-lock threshold),
 * all LIVE against the local [at.bettertrack.app.data.applock.AppLockController].
 * Step 18 extends this same screen with 2FA management + active sessions above
 * the app-lock section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    onSetupPin: () -> Unit,
    onChangePin: () -> Unit,
) {
    val bt = BtTheme.colors
    val controller = AppGraph.appLockController
    val config by controller.config.collectAsStateWithLifecycle()

    var showDisableConfirm by remember { mutableStateOf(false) }
    var showThresholdPicker by remember { mutableStateOf(false) }

    val lockOn = config.enabled && config.hasPin

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_settings_security), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.bt_settings_applock_section))

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
                SecurityNavRow(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.bt_settings_applock_change),
                    subtitle = stringResource(
                        if (webPinLock) R.string.bt_settings_applock_change_managed
                        else R.string.bt_settings_applock_change_sub,
                    ),
                    enabled = !webPinLock,
                    onClick = onChangePin,
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

                SecurityNavRow(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(R.string.bt_settings_applock_threshold),
                    subtitle = thresholdLabel(config.afkThreshold),
                    onClick = { showThresholdPicker = true },
                )
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.bt_settings_applock_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            // TODO(step 18): 2FA management + active-sessions sections mount here.
        }
    }

    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            containerColor = bt.surface,
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
        containerColor = bt.surface,
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_action_done), color = bt.gold) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = BtTheme.colors.textMuted,
    )
}

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
    Surface(color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) bt.textSecondary else bt.textMuted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) bt.textPrimary else bt.textMuted)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
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
        }
    }
}

@Composable
private fun SecurityNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // Height matches the enabled row so a disabled (managed) PIN row keeps
            // the same 48dp+ target and layout rhythm.
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) bt.textSecondary else bt.textMuted,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) bt.textPrimary else bt.textMuted,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            // A managed (disabled) row drops the chevron so it doesn't read as
            // tappable; the muted text carries the "managed on the web" meaning.
            if (enabled) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}
