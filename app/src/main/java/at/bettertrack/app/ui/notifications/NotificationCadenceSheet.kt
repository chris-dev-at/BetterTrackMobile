package at.bettertrack.app.ui.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.notifications.DigestCadence
import at.bettertrack.app.data.notifications.NotifCatalog
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtLazyPickerSheet
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Delivery frequency — the digest cadence, **per notification type**.
 *
 * ## Why all 25 in one sheet and not one chooser per type row
 *
 * Because that is where a person looks for it. The web keeps cadence in its own
 * "Timing" group precisely because it is a different question from routing: not
 * *where does this go* but *how often*. Splitting it across 19 type sheets would
 * make "set everything to a daily digest" a nineteen-tap errand, and would leave
 * the mirrorchain types with nowhere to live at all — they collapse to one routing
 * row, but the server gives each of the eight its own cadence.
 *
 * So this sheet lists every cadenceable type individually, grouped by the same
 * categories, each with its own three-way control. 25 rows, which is what the web
 * shows, and the one place in the app where the mirrorchain family appears
 * unrolled.
 *
 * `account.invite` is absent — the web filters it out client-side because a type
 * with no per-user routing has no meaningful cadence. The server would accept one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCadenceSheet(
    cadence: Map<String, String>,
    serverTypes: List<String>,
    failure: BtMessage?,
    onDismiss: () -> Unit,
    onPick: (type: String, cadence: DigestCadence) -> Unit,
) {
    BtLazyPickerSheet(
        title = stringResource(R.string.bt_notif_cadence_title),
        subtitle = stringResource(R.string.bt_notif_cadence_sub),
        message = failure,
        onDismiss = onDismiss,
    ) {
        for (category in NotifCatalog.categories) {
            val types = category.types.filter { it in serverTypes && it in NotifCatalog.cadenceTypes }
            if (types.isEmpty()) continue
            item(key = "cat-${category.key}") {
                BtSectionHeader(stringResource(notifCategoryLabelRes(category.key)))
            }
            item(key = "grp-${category.key}") {
                BtGroup {
                    types.forEach { type ->
                        CadenceRow(
                            type = type,
                            // A type the server modelled no cadence for cannot be
                            // edited, so it renders on the server's own default
                            // rather than being hidden — the row still says what
                            // will happen.
                            current = DigestCadence.fromWire(cadence[type]) ?: DigestCadence.Instant,
                            onPick = { onPick(type, it) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        // No footer. The sheet's subtitle already says what a digest is, and
        // repeating it verbatim under the last row was two paragraphs of identical
        // text on one screen — visible in the on-device pass and removed there.
    }
}

/**
 * One type's cadence: label above, segmented control below.
 *
 * Two lines rather than a trailing control because three segments and a label like
 * "New alerts from people you follow" cannot share a row on a phone without one of
 * them being truncated, and a truncated notification type is exactly the "half-read
 * row" the owner rejects.
 */
@Composable
private fun CadenceRow(
    type: String,
    current: DigestCadence,
    onPick: (DigestCadence) -> Unit,
) {
    val bt = BtTheme.colors
    val label = notifTypeLabel(type)
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        BtSegmented(
            options = DigestCadence.entries,
            selected = current,
            label = { stringResource(notifCadenceLabelRes(it)) },
            onSelect = onPick,
            contentDescription = { stringResource(R.string.bt_notif_cadence_cd, label) },
            equalWidths = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
