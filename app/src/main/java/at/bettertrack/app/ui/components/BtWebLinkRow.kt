package at.bettertrack.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import at.bettertrack.app.ui.paranoid.openBtWebApp
import at.bettertrack.app.ui.theme.BtTheme

/**
 * A row that hands one job over to the **web app**, in a Custom Tab.
 *
 * ## Why this exists as a component (web-parity ruling, 2026-08-08)
 *
 * The owner's rule for anything the web already owns is *"match exactly or link
 * to web"*. The parity audit turned that into a batch of surfaces the app will
 * NOT reimplement — the notification matrix and its cadence, profile extras,
 * data export, passkeys, connections, API keys, OAuth apps, authorized apps,
 * webhooks. Left to itself each of those would have grown its own row, its own
 * glyph and its own idea of how to say "this opens elsewhere", and the honest
 * signal — *you are about to leave the app* — would have been carried nine
 * different ways.
 *
 * So there is exactly one way to say it, and it is this row. It reads as a
 * deliberate destination rather than a dead end: the leading glyph is the
 * section's own, and the END of the row carries [Icons.Outlined.OpenInNew] in
 * `goldEmphasis` — NOT the chevron [BtGroupRow] would otherwise draw. That
 * distinction is the whole point of the component. A chevron promises a screen
 * inside the app that a back press will return from; this promises a browser.
 * Following the existing "Manage on the web" precedent in `ChainManageScreen`,
 * the open-in-new glyph is the app's single established word for that promise.
 *
 * Routing goes through [openBtWebApp], so the link lands on the **effective
 * origin** — the same one the paranoid gate and the OAuth launcher use. Hard
 * coding `bettertrack.at` here would send a user on a self-hosted or dev stack
 * to a server that is not theirs, which is the exact bug `ServerOrigins` exists
 * to prevent.
 *
 * @param path the web path to open, e.g. `/control/notifications`. Joined to the
 *   effective origin by `btWebUrl`, which owns the slash at the seam.
 */
@Composable
fun BtWebLinkRow(
    title: String,
    path: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    BtGroupRow(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = { openBtWebApp(context, path) },
        trailing = {
            // 20dp, exactly the chevron it replaces — the row must not shift
            // when a section mixes native rows and web hand-offs.
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                tint = bt.goldEmphasis,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}
