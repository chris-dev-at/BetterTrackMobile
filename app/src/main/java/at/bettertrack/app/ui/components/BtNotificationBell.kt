package at.bettertrack.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The notification bell — the app's ONE door to the inbox, in the shared tab bar.
 *
 * ## Why this component exists again (owner report 2026-08-09)
 *
 * *"add the notifications back. I don't know where they went."* He was right, and
 * the file name is the evidence: `ui/notifications/NotificationBell.kt` was
 * **deleted** in `2b7b0e9` (2026-08-05), when the five-tab redesign moved the
 * bell's job into an Overview overflow (⋮). That ⋮ was itself dissolved the next
 * day by the navigation restoration (`ec722a7`), which correctly forbade
 * page-level overflows — and correctly re-homed most of what they held, but left
 * the inbox with only *in-content* paths on Overview. Then `0e6fd58` demoted Home
 * to a selection inside the Portfolio switcher, and those in-content paths went
 * with it.
 *
 * The result was not a broken screen. [at.bettertrack.app.ui.notifications.NotificationsInboxScreen]
 * kept working the whole time, still registered as a sheet, still holding every
 * v4 semantic. It simply had **one** caller left in the entire app, four
 * navigation steps deep, and one of its two affordances only appeared when there
 * was already unread mail — so the surface a user goes looking for when they
 * suspect they missed something was hidden exactly when they had missed nothing.
 *
 * So the bell is chrome again, and this time it is chrome that cannot be demoted
 * by an IA change: it lives in the ONE shared bar
 * ([at.bettertrack.app.ui.shell.BtTabHeader]), which is drawn above the pager, so
 * it is on all four tabs by arithmetic rather than by four authors agreeing.
 *
 * ## Why the count, and not a dot
 *
 * The bottom-bar badges are dots on purpose — a number on a 24dp glyph under a
 * label is unreadable, and the bar is only ever asked "is there something over
 * there?" (see [BtTabBadgeDot]). The bell is the opposite case. It IS the
 * destination rather than a signpost to a tab that contains one, it has no label
 * competing under it, and the question it answers is "how much?" — which is the
 * question that decides whether you open it now. [BtBadgeOverlay] already draws
 * exactly this: a compact gold pill ringed in the bar's own `bg` so it stays
 * legible where it crosses the glyph, falling back to a bare dot past its `max`.
 *
 * Renders the bell whether or not there is unread mail. An affordance that
 * appears only when it has something to say is an affordance you cannot learn,
 * and "did I miss anything?" is a question asked most often when the answer is
 * no — that was half of the original defect.
 */
@Composable
fun BtNotificationBell(unread: Int, onClick: () -> Unit) {
    val bt = BtTheme.colors
    // The badge is a SIBLING of the button, not its content.
    //
    // Material3's IconButton clips to a 48dp CIRCLE (its ripple shape), and the
    // badge's home is the glyph's top-right — which is exactly where a circle
    // inscribed in that square has left the square. Nested inside, the badge came
    // out sliced along the diagonal with the digit cut off at the baseline; the
    // dotted `bg` ring made it read as a torn sticker rather than a count. Hoisting
    // it into an unclipped Box costs nothing: the button keeps its own 48dp target
    // and its ripple stays round, and the badge is not clickable in its own right
    // anyway — the whole affordance is one tap.
    Box {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                // The count is already announced by the badge's own text, so the
                // button says only what it IS. A label that folded the number in
                // would be re-read in full on every arrival.
                contentDescription = stringResource(R.string.bt_top_notifications),
                tint = bt.textSecondary,
            )
        }
        // Over the top-right of the 24dp glyph, which sits centred in the 48dp
        // button — so the badge's own box lands just inside the button's corner.
        // It must OVERLAP the bell (that is what marks it as the bell's count and
        // not a neighbouring element) without reaching the gear.
        BtBadgeOverlay(
            count = unread,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-3).dp, y = 3.dp),
        )
    }
}
