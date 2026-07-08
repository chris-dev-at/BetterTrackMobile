package at.bettertrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The ONE unread-count badge for the whole app (Step 16): a solid-gold pill with
 * bold near-black digits. Unifies the notification bell, the inbox header, the
 * Social "Messages" header and the chat list under a single badge language
 * (gold count-pills; the translucent [BtBadge] stays for status tags). Renders
 * nothing when [count] <= 0.
 */
@Composable
fun BtCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    max: Int = 99,
) {
    if (count <= 0) return
    val bt = BtTheme.colors
    Surface(shape = BtShapes.pill, color = bt.gold, modifier = modifier) {
        Text(
            text = if (count > max) "$max+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = bt.onGold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 18.dp)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/**
 * A compact count badge sized to overlay an icon (e.g. the bell). A stroke in
 * the bar background keeps it legible where it overlaps the glyph. Renders a
 * bare dot when [count] <= 0 but [showDot] is set (unused for the bell today).
 */
@Composable
fun BtBadgeOverlay(
    count: Int,
    modifier: Modifier = Modifier,
    max: Int = 9,
) {
    if (count <= 0) return
    val bt = BtTheme.colors
    Box(
        modifier = modifier
            .background(bt.gold, CircleShape)
            .border(1.5.dp, bt.bg, CircleShape)
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > max) "$max+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = bt.onGold,
            textAlign = TextAlign.Center,
        )
    }
}

/** A small gold unread dot for list rows (no count). */
@Composable
fun BtUnreadDot(modifier: Modifier = Modifier, size: Int = 8) {
    val bt = BtTheme.colors
    Box(modifier = modifier.size(size.dp).background(bt.gold, CircleShape))
}
