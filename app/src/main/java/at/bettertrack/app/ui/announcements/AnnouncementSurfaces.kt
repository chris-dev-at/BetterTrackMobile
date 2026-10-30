package at.bettertrack.app.ui.announcements

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.announcements.Announcement
import at.bettertrack.app.data.announcements.AnnouncementSeverity
import at.bettertrack.app.data.announcements.AnnouncementsFlags
import at.bettertrack.app.data.announcements.banner
import at.bettertrack.app.data.announcements.onceCard
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The `notice` surface: a banner in the shell's banner slot, under the
 * offline / reconnect line and in exactly their idiom — `surface` strip, 16dp
 * gold glyph, `bodySmall`, a hairline under it. It adds only what a notice
 * needs: its title, a two-line summary that expands in place to the full body
 * (the typed tree), and a 48dp close target. Closing is the per-user dismissal.
 *
 * Self-contained: reads the pipeline, renders nothing (zero height) when there
 * is nothing to say, and reports `seen` once it has been on screen.
 */
@Composable
fun AnnouncementBannerSlot(modifier: Modifier = Modifier) {
    if (!AnnouncementsFlags.enabled) return
    val controller = AppGraph.announcements
    val state by controller.state.collectAsStateWithLifecycle()
    val item = state.banner() ?: return
    LaunchedEffect(item.id) { controller.markSeen(item) }
    AnnouncementBanner(item = item, onClose = { controller.dismiss(item) }, modifier = modifier)
}

@Composable
fun AnnouncementBanner(
    item: Announcement,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val doc = item.renderDoc()
    val summary = doc.plainText()
    val stateCd = stringResource(if (expanded) R.string.bt_ann_collapse_cd else R.string.bt_ann_expand_cd)
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            // A critical notice earns a faint gold wash — never red: red is money
            // direction and destruction only (handoff §5).
            color = if (item.severity == AnnouncementSeverity.CRITICAL) bt.wash(bt.gold, 0.10f) else bt.surface,
            contentColor = bt.textSecondary,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button, onClickLabel = stateCd) { expanded = !expanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = severityIcon(item.severity, banner = true),
                        contentDescription = null,
                        tint = bt.goldEmphasis,
                        modifier = Modifier.padding(top = 1.dp).size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = bt.textPrimary,
                                maxLines = if (expanded) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (item.preview) PreviewTag()
                        }
                        if (expanded) {
                            Spacer(Modifier.height(4.dp))
                            AnnouncementBody(doc = doc, textStyle = MaterialTheme.typography.bodySmall)
                        } else if (summary.isNotBlank()) {
                            Text(
                                text = summary.replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.bt_ann_close_cd),
                        tint = bt.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = bt.border)
    }
}

/**
 * The `once` surface: a card on the Overview (the former Home tab), shown until
 * the user closes it — and closing it is the dismissal, so it never returns
 * (contract §2). Placed directly under the hero by the caller: value first,
 * then this, then what moved.
 *
 * Zero height when there is nothing to show — the caller's padding is applied
 * only around a card that actually draws.
 */
@Composable
fun AnnouncementOnceCardSlot(modifier: Modifier = Modifier) {
    if (!AnnouncementsFlags.enabled) return
    val controller = AppGraph.announcements
    val state by controller.state.collectAsStateWithLifecycle()
    val item = state.onceCard() ?: return
    LaunchedEffect(item.id) { controller.markSeen(item) }
    AnnouncementOnceCard(item = item, onClose = { controller.dismiss(item) }, modifier = modifier)
}

@Composable
fun AnnouncementOnceCard(
    item: Announcement,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BtShapes.card,
        color = bt.surface,
        contentColor = bt.textPrimary,
        // Borders, not shadows: a gold hairline is the whole emphasis.
        border = BorderStroke(1.dp, bt.edge(bt.gold, 0.35f)),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = BtShapes.pill, color = bt.goldWash, modifier = Modifier.size(32.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = severityIcon(item.severity, banner = true),
                                contentDescription = null,
                                tint = bt.goldInk,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.preview) PreviewTag()
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.bt_ann_close_cd),
                        tint = bt.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            AnnouncementBody(
                doc = item.renderDoc(),
                modifier = Modifier.padding(start = 44.dp, end = 16.dp, top = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.bt_ann_once_got_it), color = bt.goldInk)
                }
            }
        }
    }
}

@Composable
internal fun PreviewTag() {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.pill,
        color = bt.goldWash,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.bt_ann_preview_tag),
            style = MaterialTheme.typography.labelSmall,
            color = bt.goldInk,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Severity is styling only (contract §2): a glyph, never a different behaviour. */
internal fun severityIcon(severity: AnnouncementSeverity, banner: Boolean = false): ImageVector = when (severity) {
    AnnouncementSeverity.INFO -> if (banner) Icons.Outlined.Campaign else Icons.Outlined.Info
    AnnouncementSeverity.WARNING -> Icons.Outlined.WarningAmber
    AnnouncementSeverity.CRITICAL -> Icons.Outlined.ErrorOutline
}

@Composable
internal fun severityLabel(severity: AnnouncementSeverity): String = stringResource(
    when (severity) {
        AnnouncementSeverity.INFO -> R.string.bt_ann_severity_info
        AnnouncementSeverity.WARNING -> R.string.bt_ann_severity_warning
        AnnouncementSeverity.CRITICAL -> R.string.bt_ann_severity_critical
    },
)
