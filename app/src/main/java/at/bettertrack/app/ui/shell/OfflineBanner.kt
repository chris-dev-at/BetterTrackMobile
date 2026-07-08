package at.bettertrack.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.theme.BtTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Global offline indicator (spec §7.4): persistent but unobtrusive —
 * "Offline — showing data as of 14:32". Driven by real connectivity and the
 * cached-data age since Step 5. TODO(step 8): tapping opens the Pending-sync
 * screen.
 */
@Composable
fun OfflineBanner(
    modifier: Modifier = Modifier,
    /** Wall-clock ms of the last successful sync; null = nothing cached yet. */
    asOfMs: Long? = null,
    /** Step 8 (§7.4): tapping the indicator opens the Pending-sync screen. */
    onClick: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val text = asOfMs?.let { stringResource(R.string.bt_offline_banner, formatAsOf(it)) }
        ?: stringResource(R.string.bt_offline_banner_no_data)
    val tapCd = stringResource(R.string.bt_offline_banner_tap_cd)
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = bt.goldEmphasis,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = bt.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (onClick != null) {
            Surface(
                onClick = onClick,
                color = bt.surface,
                contentColor = bt.textSecondary,
                modifier = Modifier.semantics { contentDescription = tapCd },
            ) { content() }
        } else {
            Surface(color = bt.surface, contentColor = bt.textSecondary) { content() }
        }
        HorizontalDivider(thickness = 1.dp, color = bt.border)
    }
}

/** "14:32" for a same-day sync, "5 Jul, 14:32" otherwise. */
fun formatAsOf(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(epochMs).atZone(zone)
    val pattern = if (dateTime.toLocalDate() == LocalDate.now(zone)) "HH:mm" else "d MMM, HH:mm"
    return dateTime.format(DateTimeFormatter.ofPattern(pattern))
}
