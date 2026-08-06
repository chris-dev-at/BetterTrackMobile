package at.bettertrack.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.debug.DebugPreviewState
import at.bettertrack.app.ui.charts.BtChartPalette
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtSettingsGear
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtNeedsYouGroup
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtTabBadgeDot
import at.bettertrack.app.ui.components.ListCard
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.StatCard
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale

/**
 * Debug-only component gallery: renders EVERY design-system component in all
 * meaningful states for visual verification against the brand (spec §3).
 * Hidden entry: long-press the top-bar wordmark in debug builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onClose: () -> Unit,
    /** Step-5 sync-queue debug screen entry (debug builds reach it from here). */
    onOpenSyncDebug: () -> Unit = {},
) {
    val bt = BtTheme.colors
    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text("Component gallery", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BtBadge("DEBUG", kind = BtBadgeKind.Gold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item { WordmarkSection() }
            item { MoneySection() }
            item { CollapsingHeaderSection() }
            item { GroupSection() }
            item { HomeCardsSection() }
            item { AllocationBarSection() }
            item { StatCardSection() }
            item { ListCardSection() }
            item { ButtonSection() }
            item { ChipBadgeSection() }
            item { SkeletonSection() }
            item { EmptyStateSection() }
            item { OfflineStateSection() }
            item { ErrorStateSection() }
            item { OfflineBannerSection() }
            item { SyncDebugSection(onOpenSyncDebug) }
        }
    }
}

@Composable
private fun GallerySection(title: String, content: @Composable ColumnScopeAlias.() -> Unit) {
    val bt = BtTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
            letterSpacing = 1.2.sp,
        )
        content(ColumnScopeAlias)
    }
}

// Small trick to keep the section slot simple.
object ColumnScopeAlias

@Composable
private fun WordmarkSection() {
    val bt = BtTheme.colors
    GallerySection("Wordmark §3.2") {
        Wordmark(fontSize = 20.sp)
        Wordmark(fontSize = 28.sp, edition = "App")
        Wordmark(fontSize = 36.sp, edition = "App")
        Text(
            text = "BetterTrack — finances under your control",
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

@Composable
private fun MoneySection() {
    val bt = BtTheme.colors
    GallerySection("MoneyText — EUR, gain/loss, tabular digits") {
        MoneyText(value = 128450.32, style = BtTheme.type.moneyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MoneyText(value = 1234.56, colorMode = MoneyColorMode.GainLoss, showSign = true)
            MoneyText(value = -987.65, colorMode = MoneyColorMode.GainLoss)
            MoneyText(value = 0.0, colorMode = MoneyColorMode.GainLoss)
        }
        // Tabular alignment demo — decimal points must line up.
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(value = 1111.11, style = BtTheme.type.moneySmall)
            MoneyText(value = 8888.88, style = BtTheme.type.moneySmall)
            MoneyText(value = 90909.09, style = BtTheme.type.moneySmall)
        }
        Text(
            text = "digits align in columns (tnum)",
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

/**
 * [BtCollapsingHeader] in both of its states, side by side.
 *
 * Rendered at two pinned scroll positions rather than as one live header,
 * because the whole point of the entry is the comparison: the expanded row is the
 * screen's subject at `headlineSmall`, the collapsed one is a 64dp identity strip
 * at `titleMedium`, and a reviewer needs to see the two type sizes and the gold
 * chevron's two sizes together to judge the ramp. A live one would only ever show
 * whichever state the gallery's own scroll happened to leave it in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsingHeaderSection() {
    val bt = BtTheme.colors
    GallerySection("Collapsing header §4.1 (R-arc R1)") {
        Text("Expanded — tap-to-switch title", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        Surface(color = bt.bg, shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
            BtCollapsingHeader(
                title = "Main portfolio",
                scrollBehavior = rememberBtCollapsingHeaderBehavior(),
                // The gallery's Scaffold already consumed the status bar.
                windowInsets = WindowInsets(0, 0, 0, 0),
                onTitleClick = {},
                titleClickLabel = "Switch portfolio",
                // The gear, not a ⋮: after the 2026-08-06 navigation restoration
                // this is what a tab bar's trailing slot holds app-wide, so the
                // gallery's demo bar has to hold it too — a showcase that still
                // rendered the retired overflow would be teaching the old rule.
                settings = { BtSettingsGear({}) },
            )
        }
        Text("Collapsed — scrolled state (tonal lift, no divider)", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        Surface(color = bt.bg, shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
            val collapsed = rememberBtCollapsingHeaderBehavior()
            // Drive the state straight to fully-collapsed: heightOffsetLimit is
            // negative (the distance the bar may travel up), so pinning the offset
            // to it is exactly "the user has scrolled past the title".
            collapsed.state.heightOffsetLimit = -48f
            collapsed.state.heightOffset = -48f
            collapsed.state.contentOffset = -200f
            BtCollapsingHeader(
                title = "Main portfolio",
                scrollBehavior = collapsed,
                windowInsets = WindowInsets(0, 0, 0, 0),
                onTitleClick = {},
                titleClickLabel = "Switch portfolio",
                // The gear, not a ⋮: after the 2026-08-06 navigation restoration
                // this is what a tab bar's trailing slot holds app-wide, so the
                // gallery's demo bar has to hold it too — a showcase that still
                // rendered the retired overflow would be teaching the old rule.
                settings = { BtSettingsGear({}) },
            )
        }
        Text("No title action — a plain large title", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        Surface(color = bt.bg, shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
            BtCollapsingHeader(
                title = "Workbench",
                scrollBehavior = rememberBtCollapsingHeaderBehavior(),
                // The gallery's Scaffold already consumed the status bar.
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        }
        Text(
            "With subtitle (R2) — the two-line pushed-screen bar",
            style = MaterialTheme.typography.labelSmall,
            color = bt.textMuted,
        )
        Surface(color = bt.bg, shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
            BtCollapsingHeader(
                title = "Transactions",
                subtitle = "Main portfolio",
                scrollBehavior = rememberBtCollapsingHeaderBehavior(),
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = bt.textSecondary,
                        )
                    }
                },
            )
        }
    }
}

/**
 * The R2 grouping vocabulary (mandate §4) — the pair has to be judged together.
 *
 * The whole argument for [BtGroup] is comparative: a settings section used to be
 * N separately-bordered cards, and the claim is that ONE border-less tonal block
 * reads as a single subject while its rows read as parts of it. That claim is
 * only checkable with the two grouping styles stacked next to each other and
 * next to [BtNeedsYouGroup], which spends the screen's entire gold budget in one
 * place and must still not look like a warning.
 */
@Composable
private fun GroupSection() {
    val bt = BtTheme.colors
    GallerySection("Groups & Needs-you §4 (R-arc R2)") {
        Text("BtGroup — tonal, border-less, rows are parts of one subject", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        BtGroup {
            BtGroupRow(
                title = "Security",
                subtitle = "App lock, two-factor, sessions",
                icon = Icons.Outlined.Lock,
                onClick = {},
            )
            BtGroupRow(
                title = "Language",
                subtitle = "English",
                icon = Icons.Outlined.Translate,
                onClick = {},
            )
            BtGroupRow(
                title = "Discreet mode",
                subtitle = "Hide amounts across the app",
                icon = Icons.Outlined.VisibilityOff,
                trailing = { BtBadge(text = "Off", kind = BtBadgeKind.Neutral) },
            )
        }

        Text("BtSectionHeader — the one section label", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        BtSectionHeader("Friends", count = 12)

        Text("BtNeedsYouGroup — the §3 actionable lead, where the gold goes", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        BtNeedsYouGroup(title = "Needs you") {
            BtGroupRow(
                title = "AAPL",
                subtitle = "Above $150",
                icon = Icons.Outlined.NotificationsActive,
                iconTint = bt.goldEmphasis,
                onClick = {},
                trailing = { BtBadge(text = "Triggered", kind = BtBadgeKind.Gold) },
            )
            BtGroupRow(
                title = "Dividend basket",
                subtitle = "No thesis written yet",
                icon = Icons.Outlined.Lightbulb,
                onClick = {},
            )
        }

        Text("Empty by construction: the block is absent, not collapsed", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
    }
}

/**
 * Home's card vocabulary (R-arc R1 §3): the hero, an actionable row, a mover
 * card, and one quiet tail row.
 *
 * The point of grouping them here is the WEIGHT ramp — 44sp hero, gold only on
 * the row that can cost you money, muted rows at the bottom — which is only
 * judgeable when the four are stacked in the order the screen uses them.
 */
@Composable
private fun HomeCardsSection() {
    val bt = BtTheme.colors
    val locale = Locale.GERMANY
    GallerySection("Home cards §3 (R-arc R1)") {
        Column {
            Text("Net worth", style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            Spacer(Modifier.height(4.dp))
            MoneyText(value = 128_450.75, style = BtTheme.type.moneyHero)
            Spacer(Modifier.height(4.dp))
            Text(
                "Across 2 of 3 portfolios",
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MoneyText(
                    value = 1_204.20,
                    style = BtTheme.type.numberCaption,
                    colorMode = MoneyColorMode.GainLoss,
                    showSign = true,
                )
                Text(
                    " (${formatPercent(0.95, locale)}) · today",
                    style = BtTheme.type.numberCaption,
                    color = bt.gain,
                )
            }
        }
        // The actionable row, gold-led: the one Home card that can be about money
        // moving without the user.
        ListCard(
            title = "2 alerts triggered",
            subtitle = "Open the alerts manager",
            leading = {
                Icon(
                    Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = bt.goldEmphasis,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailing = {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = bt.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GalleryMoverCard("NVDA", 4.82, 12_400.0, locale)
            GalleryMoverCard("ASML", -3.10, 8_150.0, locale)
        }
        // The quiet tail: places to go, not things to do.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Inbox, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(14.dp))
            Text("Notifications", style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary, modifier = Modifier.weight(1f))
            Text("3", style = MaterialTheme.typography.labelMedium, color = bt.textMuted)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun GalleryMoverCard(symbol: String, pct: Double, value: Double, locale: Locale) {
    val bt = BtTheme.colors
    Surface(
        color = bt.surface,
        shape = BtShapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, bt.border),
        modifier = Modifier.width(132.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(symbol, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                formatPercent(pct, locale),
                style = BtTheme.type.moneyMedium,
                color = if (pct >= 0) bt.gain else bt.loss,
            )
            Spacer(Modifier.height(2.dp))
            MoneyText(value = value, style = BtTheme.type.numberCaption)
        }
    }
}

/**
 * The slim allocation bar that replaced the donut card above the holdings list
 * (decision O-5). Shown next to the palette it draws from, because the whole
 * question a reviewer has about a 10dp stacked bar is whether adjacent slices
 * stay distinguishable at that height.
 */
@Composable
private fun AllocationBarSection() {
    val bt = BtTheme.colors
    GallerySection("Allocation summary bar §4.2 (R-arc R1)") {
        val shares = listOf(0.34f, 0.24f, 0.16f, 0.11f, 0.08f, 0.05f, 0.02f)
        Row(
            modifier = Modifier.fillMaxWidth().height(10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            shares.forEachIndexed { i, share ->
                Box(
                    Modifier
                        .weight(share)
                        .height(10.dp)
                        .background(
                            if (i < BtChartPalette.series.size) BtChartPalette.series[i] else BtChartPalette.cash,
                            BtShapes.pill,
                        ),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("AAPL" to "34,0 %", "MSFT" to "24,0 %", "VWCE" to "16,0 %")
                .forEachIndexed { i, (label, pct) ->
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(BtChartPalette.series[i], CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium, color = bt.textSecondary)
                        }
                        Text(pct, style = BtTheme.type.numberCaption, color = bt.textPrimary)
                    }
                }
        }
    }
}

@Composable
private fun StatCardSection() {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    GallerySection("Stat cards") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "Net Worth",
                modifier = Modifier.weight(1f),
                deltaContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MoneyText(
                            value = 2350.12,
                            style = BtTheme.type.numberCaption,
                            colorMode = MoneyColorMode.GainLoss,
                            showSign = true,
                        )
                        Text(
                            text = formatPercent(1.87, locale),
                            style = BtTheme.type.numberCaption,
                            color = bt.gain,
                        )
                    }
                },
            ) {
                MoneyText(value = 128450.32, style = BtTheme.type.moneyMedium)
            }
            StatCard(
                label = "Today",
                modifier = Modifier.weight(1f),
                deltaContent = {
                    Text(
                        text = formatPercent(-0.42, locale),
                        style = BtTheme.type.numberCaption,
                        color = bt.loss,
                    )
                },
            ) {
                MoneyText(
                    value = -534.10,
                    style = BtTheme.type.moneyMedium,
                    colorMode = MoneyColorMode.GainLoss,
                )
            }
        }
        StatCard(label = "Cash", selected = true) {
            MoneyText(value = 4200.00, style = BtTheme.type.moneyMedium)
        }
        Text(
            text = "selected/highlighted card uses the amber-tinted surface",
            style = MaterialTheme.typography.bodySmall,
            color = BtTheme.colors.textMuted,
        )
    }
}

@Composable
private fun ListCardSection() {
    val bt = BtTheme.colors
    GallerySection("List cards") {
        ListCard(
            title = "Apple Inc.",
            subtitle = "12 shares · AAPL",
            onClick = {},
            leading = {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(bt.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("AA", style = MaterialTheme.typography.labelMedium, color = bt.textSecondary)
                }
            },
            trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(value = 2412.60, style = BtTheme.type.moneySmall)
                    MoneyText(
                        value = 231.40,
                        style = BtTheme.type.numberCaption,
                        colorMode = MoneyColorMode.GainLoss,
                        showSign = true,
                    )
                }
            },
        )
        ListCard(
            title = "Bitcoin",
            subtitle = "0.041 BTC · pending",
            trailing = { BtBadge("Pending sync", kind = BtBadgeKind.Gold) },
        )
        ListCard(title = "Plain row", subtitle = "no leading, no trailing")
    }
}

@Composable
private fun ButtonSection() {
    GallerySection("Buttons") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BtPrimaryButton(text = "Primary", onClick = {})
            BtPrimaryButton(text = "Disabled", onClick = {}, enabled = false)
        }
        BtPrimaryButton(text = "Loading", onClick = {}, loading = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BtSecondaryButton(text = "Secondary", onClick = {})
            BtSecondaryButton(text = "Disabled", onClick = {}, enabled = false)
        }
    }
}

@Composable
private fun ChipBadgeSection() {
    GallerySection("Chips & badges") {
        var selectedChip by remember { mutableStateOf(0) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1D", "1W", "1M", "1Y", "Max").forEachIndexed { i, label ->
                BtChip(text = label, selected = selectedChip == i, onClick = { selectedChip = i })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtBadge("Neutral")
            BtBadge("Default", kind = BtBadgeKind.Gold)
            BtBadge("+3.2%", kind = BtBadgeKind.Gain)
            BtBadge("−1.1%", kind = BtBadgeKind.Loss)
        }
        TabBadgeRow()
    }
}

/**
 * The R-arc bottom-navigation badge, in its real context.
 *
 * The mandate moved the chat-unread and triggered-alert signals off the top bar
 * and onto their owning tabs as DOTS, not counts — so the gallery shows the dot
 * where it actually lives (on a 24dp glyph, over the nav-bar surface), because
 * the only question worth checking on this component is whether it reads at that
 * size against that background without swallowing the icon.
 */
@Composable
private fun TabBadgeRow() {
    val bt = BtTheme.colors
    Text(
        text = "Tab badge dot — People (unread) · Workbench (alerts) · unbadged",
        style = MaterialTheme.typography.labelSmall,
        color = bt.textMuted,
    )
    Surface(color = bt.surface, shape = BtShapes.card) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            GalleryTabIcon(Icons.Outlined.People, badged = true)
            GalleryTabIcon(Icons.Outlined.Dashboard, badged = true)
            GalleryTabIcon(Icons.Outlined.PieChart, badged = false)
        }
    }
}

@Composable
private fun GalleryTabIcon(icon: ImageVector, badged: Boolean) {
    Box {
        Icon(icon, contentDescription = null, tint = BtTheme.colors.textMuted)
        BtTabBadgeDot(
            show = badged,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-3).dp),
        )
    }
}

@Composable
private fun SkeletonSection() {
    GallerySection("Loading skeleton (reduced-motion aware)") {
        BtSkeleton(Modifier.fillMaxWidth(0.55f).height(14.dp))
        BtSkeleton(Modifier.fillMaxWidth(0.8f).height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BtSkeleton(Modifier.size(36.dp), shape = CircleShape)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BtSkeleton(Modifier.fillMaxWidth(0.5f).height(12.dp))
                BtSkeleton(Modifier.fillMaxWidth(0.3f).height(10.dp))
            }
        }
        BtSkeleton(Modifier.fillMaxWidth().height(72.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
    }
}

// R3 §2/§6: these three demos are the app's reference for what a state LOOKS
// like, so what they show has to be what screens should copy. Empty and Error
// used to be demoed inside a `BtCard` — a bordered box around a state — which is
// exactly the box-in-box the R-arc removed everywhere else, taught from the one
// screen a builder consults before writing a new one. They are full-surface and
// borderless now, and the badge behind the glyph is tonal (see BtStates).
@Composable
private fun EmptyStateSection() {
    GallerySection("Empty state") {
        BtEmptyState(
            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
            title = "No transactions yet",
            message = "Your buys and sells will appear here.",
            action = { BtSecondaryButton(text = "Add transaction", onClick = {}) },
        )
    }
}

@Composable
private fun OfflineStateSection() {
    GallerySection("Offline state (one glyph app-wide)") {
        BtOfflineState(
            message = "Connect to see live market data.",
            onRetry = {},
        )
    }
}

@Composable
private fun ErrorStateSection() {
    GallerySection("Error state") {
        BtErrorState(onRetry = {})
    }
}

@Composable
private fun SyncDebugSection(onOpenSyncDebug: () -> Unit) {
    GallerySection("Sync engine §7.3 (debug)") {
        BtSecondaryButton(
            text = "Open sync queue debug screen",
            onClick = onOpenSyncDebug,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OfflineBannerSection() {
    val bt = BtTheme.colors
    GallerySection("Offline banner §7.4 (debug preview)") {
        OfflineBanner(asOfMs = System.currentTimeMillis() - 45 * 60_000L)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Show banner in app shell",
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            Switch(
                checked = DebugPreviewState.showOfflineBanner,
                onCheckedChange = { DebugPreviewState.showOfflineBanner = it },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = bt.gold,
                    checkedThumbColor = bt.onGold,
                    uncheckedTrackColor = bt.surface,
                    uncheckedThumbColor = bt.textMuted,
                    uncheckedBorderColor = bt.borderStrong,
                ),
            )
        }
    }
}
