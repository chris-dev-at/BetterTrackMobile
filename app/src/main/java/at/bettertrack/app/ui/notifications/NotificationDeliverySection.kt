package at.bettertrack.app.ui.notifications

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.notifications.DeliveryState
import at.bettertrack.app.data.notifications.DigestCadence
import at.bettertrack.app.data.notifications.QuietHours
import at.bettertrack.app.data.notifications.groupCadence
import at.bettertrack.app.data.notifications.hourOfMinuteOfDay
import at.bettertrack.app.data.notifications.minuteOfDayOf
import at.bettertrack.app.data.notifications.minuteOfMinuteOfDay
import at.bettertrack.app.data.notifications.quietWindowDisplay
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.btFieldColors
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.ZoneId

/**
 * The v5 "Delivery" block of the notification settings screen: one digest-cadence
 * chooser for the batchable types plus the quiet-hours window.
 *
 * Deliberately ONE compact section rather than a control on every type row — the
 * per-type grid below it already carries the channel matrix, and a 25-row cadence
 * grid would be unreadable on a phone. Cadence applies to the digestible group
 * ([at.bettertrack.app.data.notifications.DeliveryTypes.digestible]); urgent and
 * actionable types stay instant and the copy says so.
 *
 * Visibility follows the echo-verbatim rule: the section renders only for what the
 * last GET actually carried, so a pre-v5 server shows nothing here at all.
 */
@Composable
fun NotificationDeliverySection(
    delivery: DeliveryState,
    onCadence: (DigestCadence) -> Unit,
    onQuietHours: (QuietHours) -> Unit,
) {
    if (!delivery.supported) return
    val bt = BtTheme.colors

    Text(
        stringResource(R.string.bt_notif_delivery_section).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = bt.textMuted,
        modifier = Modifier.padding(top = 4.dp),
    )

    delivery.cadence?.let { cadence ->
        CadenceCard(selected = groupCadence(cadence), onSelect = onCadence)
    }
    delivery.quietHours?.let { quietHours ->
        QuietHoursCard(quietHours = quietHours, onChange = onQuietHours)
    }

    // The one thing a user must not have to guess: none of this touches the bell.
    Text(
        stringResource(R.string.bt_notif_delivery_inbox_hint),
        style = MaterialTheme.typography.bodySmall,
        color = bt.textMuted,
    )
}

// ── Cadence ──────────────────────────────────────────────────────────────────

@Composable
private fun CadenceCard(selected: DigestCadence?, onSelect: (DigestCadence) -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.bt_notif_cadence_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            Text(
                stringResource(R.string.bt_notif_cadence_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DigestCadence.entries.forEach { cadence ->
                    DeliverySegment(
                        label = stringResource(cadenceLabel(cadence)),
                        selected = selected == cadence,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(cadence) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // The web can set a cadence per type; when the group disagrees no segment
            // is selected and we say why rather than picking one at random.
            if (selected == null) {
                Text(
                    stringResource(R.string.bt_notif_cadence_mixed),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.goldEmphasis,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                stringResource(R.string.bt_notif_cadence_urgent_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

private fun cadenceLabel(cadence: DigestCadence): Int = when (cadence) {
    DigestCadence.Instant -> R.string.bt_notif_cadence_instant
    DigestCadence.Daily -> R.string.bt_notif_cadence_daily
    DigestCadence.Weekly -> R.string.bt_notif_cadence_weekly
}

/** Segmented-pill option — the same language as the inbox Active|Archived|All filter. */
@Composable
private fun DeliverySegment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        shape = BtShapes.pill,
        color = if (selected) bt.goldWash else bt.surface,
        contentColor = if (selected) bt.goldEmphasis else bt.textSecondary,
        border = BorderStroke(1.dp, if (selected) bt.edge(bt.gold, 0.45f) else bt.border),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}

// ── Quiet hours ──────────────────────────────────────────────────────────────

/** Which end of the window a time dialog is editing. */
private enum class QuietEdge { Start, End }

@Composable
private fun QuietHoursCard(quietHours: QuietHours, onChange: (QuietHours) -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val use24Hour = DateFormat.is24HourFormat(context)
    val locale = rememberBtLocale()
    val deviceZone = remember { ZoneId.systemDefault().id }
    // What the window is actually evaluated in. The server stores null until a zone
    // is set (it then falls back to UTC); the device zone is the honest fill-in.
    val effectiveZone = quietHours.timezone ?: deviceZone

    var editing by remember { mutableStateOf<QuietEdge?>(null) }
    var pickingZone by remember { mutableStateOf(false) }

    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.bt_notif_quiet_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = bt.textPrimary,
                    )
                    Text(
                        stringResource(R.string.bt_notif_quiet_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = quietHours.enabled,
                    onCheckedChange = { on ->
                        // Turning it on with no stored zone carries the device zone in the
                        // same patch, so the window means what the user sees.
                        val next = quietHours.copy(enabled = on)
                        onChange(if (on && quietHours.timezone == null) next.copy(timezone = deviceZone) else next)
                    },
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

            if (quietHours.enabled) {
                val window = quietWindowDisplay(quietHours.startMinute, quietHours.endMinute, use24Hour, locale)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(
                        label = stringResource(R.string.bt_notif_quiet_start),
                        value = window.start,
                        modifier = Modifier.weight(1f),
                        onClick = { editing = QuietEdge.Start },
                    )
                    TimeField(
                        label = stringResource(R.string.bt_notif_quiet_end),
                        value = window.end,
                        modifier = Modifier.weight(1f),
                        onClick = { editing = QuietEdge.End },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(
                        if (window.overnight) R.string.bt_notif_quiet_window_overnight else R.string.bt_notif_quiet_window,
                        window.start,
                        window.end,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                TimezoneRow(
                    zone = effectiveZone,
                    fromDevice = quietHours.timezone == null,
                    onClick = { pickingZone = true },
                )
            }
        }
    }

    editing?.let { edge ->
        val current = if (edge == QuietEdge.Start) quietHours.startMinute else quietHours.endMinute
        QuietTimeDialog(
            minuteOfDay = current,
            use24Hour = use24Hour,
            title = stringResource(
                if (edge == QuietEdge.Start) R.string.bt_notif_quiet_start else R.string.bt_notif_quiet_end,
            ),
            onDismiss = { editing = null },
            onPick = { picked ->
                editing = null
                onChange(
                    if (edge == QuietEdge.Start) quietHours.copy(startMinute = picked)
                    else quietHours.copy(endMinute = picked),
                )
            },
        )
    }

    if (pickingZone) {
        TimezonePickerDialog(
            selected = effectiveZone,
            deviceZone = deviceZone,
            onDismiss = { pickingZone = false },
            onPick = { zone ->
                pickingZone = false
                onChange(quietHours.copy(timezone = zone))
            },
        )
    }
}

/** Read-only, tappable time cell — label above a large tabular value. */
@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        color = bt.bg,
        border = BorderStroke(1.dp, bt.borderStrong),
        shape = BtShapes.card,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
        }
    }
}

@Composable
private fun TimezoneRow(zone: String, fromDevice: Boolean, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        color = bt.bg,
        border = BorderStroke(1.dp, bt.borderStrong),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.bt_notif_quiet_timezone),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                )
                Text(zone, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
                // Honest label: until the user confirms it, this zone is the phone's,
                // not something the server has stored.
                if (fromDevice) {
                    Text(
                        stringResource(R.string.bt_notif_quiet_timezone_device),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Time entry for one window edge. Uses [TimeInput] (keyboard entry) rather than the
 * clock dial: it honours the device's 12/24-hour setting, fits a dialog without
 * cramping, and picking "22:00" is two keystrokes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(
    minuteOfDay: Int,
    use24Hour: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val bt = BtTheme.colors
    val state = rememberTimePickerState(
        initialHour = hourOfMinuteOfDay(minuteOfDay),
        initialMinute = minuteOfMinuteOfDay(minuteOfDay),
        is24Hour = use24Hour,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surfaceHigh,
        title = { Text(title, color = bt.textPrimary) },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(minuteOfDayOf(state.hour, state.minute)) }) {
                Text(stringResource(R.string.bt_notif_quiet_set), color = bt.goldInk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

/**
 * IANA time-zone override. The device zone is pinned to the top (the default the
 * app fills in); everything else is the region-based zone list, filtered as you
 * type. The legacy Etc and SystemV id families are hidden — they are valid but
 * nobody means them.
 */
@Composable
private fun TimezonePickerDialog(
    selected: String,
    deviceZone: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val bt = BtTheme.colors
    var query by remember { mutableStateOf("") }
    val zones = remember {
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
    }
    val shown = remember(query, zones) {
        val q = query.trim()
        val matches = if (q.isEmpty()) zones else zones.filter { it.contains(q, ignoreCase = true) }
        (listOf(deviceZone) + matches.filter { it != deviceZone })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surfaceHigh,
        title = { Text(stringResource(R.string.bt_notif_quiet_timezone_title), color = bt.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.bt_notif_quiet_timezone_search)) },
                    singleLine = true,
                    colors = btFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(shown, key = { it }) { zone ->
                        Surface(
                            onClick = { onPick(zone) },
                            color = if (zone == selected) bt.goldWash else bt.surface,
                            contentColor = if (zone == selected) bt.goldEmphasis else bt.textPrimary,
                            shape = BtShapes.card,
                            // Unselected rows are `surface` = white on the
                            // all-white light table, inside a white dialog: with
                            // no edge there was nothing separating one timezone
                            // from the next except the gap between their text.
                            border = BorderStroke(1.dp, bt.groupBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(zone, style = MaterialTheme.typography.bodyMedium)
                                if (zone == deviceZone) {
                                    Text(
                                        stringResource(R.string.bt_notif_quiet_timezone_device),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = bt.textMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}
