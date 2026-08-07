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
import androidx.compose.ui.graphics.Color
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
 * A compact count badge sized to overlay an icon. A stroke in the bar background
 * keeps it legible where it overlaps the glyph.
 *
 * [showDot] renders a bare gold dot when [count] <= 0 — for the callers that
 * want "something is here" without claiming a number. (This parameter was
 * promised by this KDoc long before it existed in the signature; R1 made the
 * promise true rather than deleting it, because the tab badges below want
 * exactly that shape.)
 */
@Composable
fun BtBadgeOverlay(
    count: Int,
    modifier: Modifier = Modifier,
    max: Int = 9,
    showDot: Boolean = false,
) {
    val bt = BtTheme.colors
    if (count <= 0) {
        // Rings in the page, matching the counted variant's own `bg` ring below.
        if (showDot) BtBorderedDot(modifier, ring = bt.bg)
        return
    }
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

/**
 * The bottom-navigation badge (R-arc mandate §1).
 *
 * The mandate moves the unread-chat and triggered-alert signals off the top bar
 * and onto their owning tabs, and it asks for a **dot**, not a count. That is
 * the right call and worth stating: a number on a 24dp glyph inside a 56dp nav
 * item is unreadable at a glance and competes with the label directly under it,
 * while a dot answers the only question the bar is asked — "is there something
 * over there?" — in one saccade. The count itself lives one tap away, on the
 * screen that can afford to show it.
 *
 * Renders nothing when [show] is false, so callers can pass the raw predicate
 * without wrapping every use in an `if`.
 */
@Composable
fun BtTabBadgeDot(show: Boolean, modifier: Modifier = Modifier) {
    if (!show) return
    // The ring must match WHAT THE DOT SITS ON, and this one sits on the bottom
    // bar. Until B2-B the bar's container was `surface` — the card colour — so a
    // ring in `surface` happened to be right by coincidence. §6.2 gives the bar
    // its own `navBar` tone (that is the whole point: a bar that is not just a
    // stuck card), and the moment it did, a `surface` ring became a visible
    // halo around every badge. Naming the bar's colour keeps the two locked.
    BtBorderedDot(modifier, ring = BtTheme.colors.navBar)
}

/**
 * A gold dot ringed in whatever it overlaps, so it stays legible where it
 * crosses a glyph. Shared by [BtBadgeOverlay]'s dot mode and [BtTabBadgeDot]
 * precisely so the two can never drift into two different dots — but the RING
 * is the caller's, because only the caller knows the substrate.
 */
@Composable
private fun BtBorderedDot(modifier: Modifier = Modifier, ring: Color) {
    val bt = BtTheme.colors
    Box(
        modifier = modifier
            .size(10.dp)
            .background(bt.gold, CircleShape)
            .border(1.5.dp, ring, CircleShape),
    )
}
