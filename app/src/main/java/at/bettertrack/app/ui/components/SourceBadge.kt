package at.bettertrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.format.RowSource
import at.bettertrack.app.ui.format.parseRowSource
import at.bettertrack.app.ui.format.prettySourceSlug
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * A small NEUTRAL badge marking a row the user did not type themselves.
 *
 * Renders nothing at all for `manual`, which is the overwhelming majority of
 * rows — badging those would be pure noise. The badge is deliberately
 * low-contrast: it is provenance metadata, not a status the user must act on,
 * so it must never out-shout the amount on the same row.
 */
@Composable
fun SourceBadge(source: String?, modifier: Modifier = Modifier) {
    when (val parsed = parseRowSource(source)) {
        RowSource.Manual -> Unit
        RowSource.StandingOrder -> SourceBadgeChip(
            icon = Icons.Outlined.EventRepeat,
            label = stringResource(R.string.bt_source_standing_order),
            modifier = modifier,
        )

        is RowSource.Import -> SourceBadgeChip(
            icon = Icons.Outlined.FileDownload,
            label = stringResource(R.string.bt_source_import, prettySourceSlug(parsed.slug)),
            modifier = modifier,
        )

        is RowSource.Sync -> SourceBadgeChip(
            // A mirrorchain sync is the one slug worth its own glyph: it means
            // "this came from the group", which reads very differently from a
            // broker feed.
            icon = if (parsed.slug == MIRRORCHAIN_SLUG) Icons.Outlined.Groups else Icons.Outlined.CloudSync,
            label = if (parsed.slug == MIRRORCHAIN_SLUG) {
                stringResource(R.string.bt_source_sync_group)
            } else {
                stringResource(R.string.bt_source_sync, prettySourceSlug(parsed.slug))
            },
            modifier = modifier,
        )

        is RowSource.Unknown -> SourceBadgeChip(
            icon = Icons.Outlined.HelpOutline,
            // Never invent a label for a token this build doesn't model — show
            // it verbatim so the user (and a bug report) can see what it was.
            label = parsed.raw,
            modifier = modifier,
        )
    }
}

private const val MIRRORCHAIN_SLUG = "mirrorchain"

@Composable
private fun SourceBadgeChip(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Surface(
        modifier = modifier,
        shape = BtShapes.pill,
        color = bt.surface,
        contentColor = bt.textMuted,
        border = BorderStroke(1.dp, bt.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(11.dp), tint = bt.textMuted)
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "added by @name" attribution for a row that came in through a mirrorchain.
 *
 * Only rendered when the server actually sent a username — for a non-member
 * viewer it strips attribution to the literal "group member", and the mapping
 * layer turns a blank into absent, so there is never a dangling "@".
 */
@Composable
fun MirrorAttributionChip(username: String?, modifier: Modifier = Modifier) {
    val name = username?.takeIf { it.isNotBlank() } ?: return
    val bt = BtTheme.colors
    Text(
        text = stringResource(R.string.bt_mirror_added_by, name),
        style = MaterialTheme.typography.labelSmall,
        color = bt.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
