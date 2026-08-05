package at.bettertrack.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.btPressScale
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The two tab hosts that are thin enough to live outside their own package.
 *
 * R-arc R1 dead-code sweep: this file used to open with a pull-to-refresh
 * scaffold wrapped around a branded empty state, plus the People-tab stub that
 * was its last caller. Both were Step-3 scaffolding for tabs that have been real
 * screens for many milestones — People has had `SocialScreen` since Step 14 — so
 * they rendered "Invest together / friends live here" for nobody. Verified zero
 * call sites, then deleted, together with the generic under-construction
 * destination screen, which was the same idea one level up. ~200 lines.
 */

/**
 * The **Markets** tab (mandate §2 renamed it from Assets — "it reads clearer").
 *
 * ## The R2 hierarchy (§3), and the one thing that moved
 *
 * 1. **The search field leads** — review-blessed, and unchanged. It is the
 *    reason people open this tab, and after R1 it is also the tab's ONLY search
 *    entry: the duplicate top-bar Search glyph is gone (S6 P1-11 killed at the
 *    root rather than restyled). It stays pinned under the collapsing header
 *    rather than scrolling with the list, because a search entry you have to
 *    scroll back up to reach is a search entry that has stopped leading.
 * 2. **Watchlists with live quotes** — the tab's actual content.
 * 3. **The market-intel doorway** — *moved*. It used to sit above the
 *    watchlists, which put a page you visit occasionally ahead of the rows you
 *    came to read; §3 ranks it below, so it now rides at the end of the
 *    watchlist's own scroll (see `WatchlistPanel`'s `footer`).
 *
 * The custom-assets link keeps its place beside the watchlist heading: it
 * manages the things IN that list, so it belongs to that heading and nowhere
 * else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketsTabScreen(
    onOpenSearch: () -> Unit = {},
    onOpenCustomAssets: () -> Unit = {},
    onOpenAsset: (String) -> Unit = {},
    onAddToWatchlist: () -> Unit = {},
    onOpenMarketIntel: () -> Unit = {},
) {
    val bt = BtTheme.colors
    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Column(
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        BtCollapsingHeader(
            title = stringResource(R.string.bt_tab_markets),
            scrollBehavior = scrollBehavior,
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SearchBarButton(onClick = onOpenSearch)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bt_watchlist_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onOpenCustomAssets) {
                    Text(stringResource(R.string.bt_custom_manage), color = bt.textSecondary)
                }
            }
            at.bettertrack.app.ui.watchlist.WatchlistPanel(
                onOpenAsset = onOpenAsset,
                onAddAsset = { onAddToWatchlist() },
                modifier = Modifier.weight(1f),
                footer = { MarketIntelEntryRow(onClick = onOpenMarketIntel) },
            )
        }
    }
}

/**
 * The Markets tab's entry into the portfolio-wide market intel screen.
 *
 * A single row rather than a card: it is a doorway, and the tab's content is the
 * watchlists it now sits *below* (R2, §3 — it used to sit above them, ahead of
 * the rows the user actually came for). The chevron and the button role carry
 * the affordance; nothing here previews the data, because every block behind it
 * is availability-gated and a preview that renders "—" would be worse than none.
 */
@Composable
private fun MarketIntelEntryRow(onClick: () -> Unit) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.bt_assets_intel_row_title)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .btPressScale(interaction, pressedScale = 0.985f)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        shape = BtShapes.control,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.EventNote,
                contentDescription = null,
                tint = bt.goldEmphasis,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                )
                Text(
                    text = stringResource(R.string.bt_assets_intel_row_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The Markets tab's search entry.
 *
 * S6 P1-11 — design call, both halves of the audited option taken: the field
 * SHAPE stays (a full-width input silhouette is the single strongest "you can
 * search here" signal on Android), but it stops pretending to BE an input:
 *  · semantics declare it a button with a spoken label, so TalkBack no longer
 *    announces an editable text field that cannot be edited;
 *  · [at.bettertrack.app.ui.market.SearchScreen] raises the keyboard itself on
 *    entry, so tapping this behaves exactly like tapping a real field would —
 *    one tap, caret blinking, keyboard up. That was the actual broken promise;
 *    the styling never was.
 *
 * R1 completes the fix by removing the *other* half of the duplication: the top
 * bar no longer carries a competing Search glyph on this tab.
 */
@Composable
private fun SearchBarButton(onClick: () -> Unit) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.bt_assets_search_bar)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .btPressScale(interaction, pressedScale = 0.985f)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        shape = BtShapes.control,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = bt.textMuted)
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = bt.textMuted,
            )
        }
    }
}

/**
 * The **Workbench** tab (mandate §2 renamed it from Workboard).
 *
 * Conglomerates · ideas · alerts behind the segmented host. The rename is a
 * label change only: the surface constant stays `BtSurface.CONGLOMERATES`
 * because that name mirrors the storage plan's §4.5 table, and drifting the app
 * from the document it implements would cost more than it buys.
 */
@Composable
fun WorkbenchTabScreen(
    onOpenConglomerate: (String) -> Unit,
    onCreateConglomerate: () -> Unit,
    onOpenAsset: (String) -> Unit,
    onOpenIdea: (String) -> Unit,
) {
    at.bettertrack.app.ui.workboard.WorkboardScreen(
        onOpenConglomerate = onOpenConglomerate,
        onCreateConglomerate = onCreateConglomerate,
        onOpenAsset = onOpenAsset,
        onOpenIdea = onOpenIdea,
    )
}
