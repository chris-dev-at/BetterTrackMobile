package at.bettertrack.app.ui.notifications

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.notifications.QuietHours
import at.bettertrack.app.data.notifications.hourOfMinuteOfDay
import at.bettertrack.app.data.notifications.minuteOfDayOf
import at.bettertrack.app.data.notifications.minuteOfMinuteOfDay
import at.bettertrack.app.data.notifications.quietWindowDisplay
import at.bettertrack.app.data.notifications.timeZonePickerOptions
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtLazyPickerSheet
import at.bettertrack.app.ui.components.BtPickerRow
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.ZoneId

/**
 * Quiet hours — the outbound delivery window, and the one scheduling control the
 * app keeps native.
 *
 * ## Why the digest cadence is no longer here
 *
 * This block used to carry a cadence chooser too. Under the owner's web-parity
 * ruling (*match the web exactly or link to the web*) the per-type matrix and its
 * cadence moved to `/control/notifications` — the web sets cadence PER TYPE, and
 * the app's three-segment "whole group" chooser was a lossy paraphrase of it that
 * could not even show what the account actually held (it rendered "mixed" and gave
 * up). A control that cannot express the state it is editing is not parity.
 *
 * Quiet hours stayed because it is genuinely the same control on both sides — one
 * window, one zone, account-wide — and because a time window is a thing you reach
 * for on a phone at night.
 *
 * Visibility follows the echo-verbatim rule: this renders only for what the last
 * GET actually carried, so a pre-v5 server shows nothing here at all.
 *
 * @param enabled `false` dims the card to the web's muted opacity and stops it
 *   taking input — used while the account-wide mute is on, because a window that
 *   decides WHEN things are held back means nothing when nothing is being sent at
 *   all. The settings are kept, not cleared, and the mute row says so.
 *
 *   NOTE, one deliberate deviation from the web: the web dims only the ROUTING
 *   grid under a mute (`gridDisabled = busy || settings.muted`) and leaves its
 *   quiet-hours fold live. The app has no routing grid left to dim — it moved to
 *   the web — so if nothing here responded, the mute switch would be the only
 *   control on the screen with no visible consequence. The web's dim is applied
 *   to the nearest thing the mute actually overrides.
 */
@Composable
fun NotificationDeliverySection(
    quietHours: QuietHours?,
    enabled: Boolean,
    onQuietHours: (QuietHours) -> Unit,
) {
    // Echo-verbatim: a server that modelled no quiet hours gets no quiet-hours UI.
    if (quietHours == null) return
    QuietHoursCard(quietHours = quietHours, enabled = enabled, onChange = onQuietHours)
}

// ── Quiet hours ──────────────────────────────────────────────────────────────

/** Which end of the window a time dialog is editing. */
private enum class QuietEdge { Start, End }

@Composable
private fun QuietHoursCard(quietHours: QuietHours, enabled: Boolean, onChange: (QuietHours) -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val use24Hour = DateFormat.is24HourFormat(context)
    val locale = rememberBtLocale()
    val deviceZone = remember { ZoneId.systemDefault().id }

    var editing by remember { mutableStateOf<QuietEdge?>(null) }
    var pickingZone by remember { mutableStateOf(false) }

    BtCard(modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)) {
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
                    enabled = enabled,
                    // Enabling used to smuggle the DEVICE time zone into the same
                    // patch when the account had none. It read as helpful and was
                    // not: it wrote a setting the user never chose, from a value
                    // that changes the moment they cross a border, and it did it
                    // silently. Enabling now enables, nothing else — a `null` zone
                    // is a real state (UTC), and the row below says so.
                    onCheckedChange = { on -> onChange(quietHours.copy(enabled = on)) },
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
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { editing = QuietEdge.Start },
                    )
                    TimeField(
                        label = stringResource(R.string.bt_notif_quiet_end),
                        value = window.end,
                        enabled = enabled,
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
                    zone = quietHours.timezone,
                    enabled = enabled,
                    onClick = { pickingZone = true },
                )
                // The web's "Use my timezone (Europe/Vienna)" shortcut, shown on the
                // same condition it uses: there is a detected zone and it is not the
                // one already stored. This is the honest replacement for the silent
                // auto-injection — the same one tap, except the user makes it.
                if (quietHours.timezone != deviceZone) {
                    TextButton(
                        onClick = { onChange(quietHours.copy(timezone = deviceZone)) },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.bt_notif_quiet_timezone_use_detected, deviceZone),
                            style = MaterialTheme.typography.labelLarge,
                            color = bt.goldInk,
                        )
                    }
                }
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
        TimezonePickerSheet(
            selected = quietHours.timezone,
            deviceZone = deviceZone,
            onDismiss = { pickingZone = false },
            onPick = { zone ->
                pickingZone = false
                onChange(quietHours.copy(timezone = zone))
            },
        )
    }
}

/**
 * How far a muted block recedes — `0.6`, the web's own
 * `opacity: settings.muted ? 0.6 : undefined` on the routing grid, so the two
 * clients say "this is not in effect" at the same strength.
 */
internal const val DISABLED_ALPHA = 0.6f

/** Read-only, tappable time cell — label above a large tabular value. */
@Composable
private fun TimeField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
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

/**
 * The zone the window is evaluated in.
 *
 * [zone] `null` is NOT "unknown" and is no longer dressed up as the phone's zone:
 * it is the server's stored state and, per the platform contract
 * (`ianaTimeZoneSchema` — *"null everywhere means no timezone set: quiet hours
 * and digest boundaries then fall back to UTC"*), it means UTC. Rendering the
 * phone's zone with a "from this device" caption while the server evaluated in
 * UTC was a nine-hour lie for anyone outside that offset — the exact class of
 * thing the honest-states rule exists to stop. It now reads what the web reads:
 * "UTC (no timezone set)".
 */
@Composable
private fun TimezoneRow(zone: String?, enabled: Boolean, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
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
                Text(
                    zone ?: stringResource(R.string.bt_notif_quiet_timezone_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textPrimary,
                )
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
 * IANA time-zone override — the app's shape of the web's `<Select>`.
 *
 * The option set comes from [timeZonePickerOptions], which mirrors the web's
 * `timeZoneOptions()` including the part that is easy to miss: the currently-set
 * and detected zones are force-added, so the picker can always show what is
 * actually selected. The "UTC (no timezone set)" row is the web's
 * `<option value="">` — always first, never filtered by the search, because it is
 * the CLEAR action wearing a list row rather than a search result.
 *
 * The only thing the phone adds is the search field: a `<select>` on desktop is
 * type-to-jump, and 400+ rows on a phone without one would be unusable.
 *
 * ## Why this is a sheet
 *
 * It was the last centre `AlertDialog` in the picker family — every other picker
 * in the app moved to [BtLazyPickerSheet]'s parent, and one dialog left behind is
 * not a smaller version of the problem, it is the same picker answered a second
 * way. The migration is [BtLazyPickerSheet] rather than the plain sheet for the
 * reason that made this list awkward in the dialog too: it is 400+ rows, and it
 * is now the reason the component grew a search field of its own.
 *
 * The selected row is not tappable, matching `BtChoiceSheet`: re-picking the
 * current zone would round-trip to arrive where it already is.
 */
@Composable
private fun TimezonePickerSheet(
    selected: String?,
    deviceZone: String,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val zones = remember(selected, deviceZone) {
        timeZonePickerOptions(ZoneId.getAvailableZoneIds(), current = selected, detected = deviceZone)
    }
    val shown = remember(query, zones) {
        val q = query.trim()
        if (q.isEmpty()) zones else zones.filter { it.contains(q, ignoreCase = true) }
    }
    // Hoisted out of the lazy item: `stringResource` inside `items` would be
    // resolved once per visible row per scroll frame for a constant string.
    val deviceCaption = stringResource(R.string.bt_notif_quiet_timezone_device)

    // Open ON the zone the account actually has. Alphabetically, "Europe/Vienna"
    // is ~370 rows below "Africa/Abidjan": a picker that opens at the top shows
    // a list in which nothing appears chosen, which is the exact impression the
    // tick exists to prevent. The `+ 1` is the "no timezone set" row, which is
    // always index 0 and never part of [shown].
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val index = shown.indexOf(selected)
        if (index >= 0) listState.scrollToItem(index + 1)
    }
    // A search starts a new read of the list, so it starts at the top of it —
    // otherwise the first keystroke leaves the user parked mid-way down results
    // they have not seen.
    LaunchedEffect(query) { if (query.isNotEmpty()) listState.scrollToItem(0) }

    BtLazyPickerSheet(
        title = stringResource(R.string.bt_notif_quiet_timezone_title),
        onDismiss = onDismiss,
        searchQuery = query,
        searchLabel = stringResource(R.string.bt_notif_quiet_timezone_search),
        onSearchQueryChange = { query = it },
        closeLabel = stringResource(R.string.bt_action_cancel),
        state = listState,
    ) {
        // Always first, never filtered: this is the "clear it" action wearing a
        // list row, not a search result.
        item(key = "__none__") {
            BtPickerRow(
                label = stringResource(R.string.bt_notif_quiet_timezone_none),
                selected = selected == null,
                onClick = if (selected == null) null else ({ onPick(null) }),
            )
        }
        items(shown, key = { it }) { zone ->
            BtPickerRow(
                label = zone,
                supporting = if (zone == deviceZone) deviceCaption else null,
                selected = zone == selected,
                onClick = if (zone == selected) null else ({ onPick(zone) }),
            )
        }
    }
}
