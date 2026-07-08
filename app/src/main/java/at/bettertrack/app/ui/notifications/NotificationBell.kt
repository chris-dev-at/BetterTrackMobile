package at.bettertrack.app.ui.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtBadgeOverlay
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Top-bar notification bell (Step 16, §6.11): the inbox entry point with the
 * unread badge overlaid on the glyph. One coherent gold badge language with the
 * inbox + Social + chat via [BtBadgeOverlay].
 */
@Composable
fun NotificationBell(
    unread: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Box(modifier = modifier) {
        IconButton(onClick = onClick) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = stringResource(R.string.bt_top_notifications),
                tint = bt.textSecondary,
            )
        }
        BtBadgeOverlay(
            count = unread,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 7.dp, end = 6.dp),
        )
    }
}
