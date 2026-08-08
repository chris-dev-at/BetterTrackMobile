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
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LightMode
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.data.api.dto.BT_PROFILE_ICONS
import at.bettertrack.app.data.repo.BtPortfolioKind
import at.bettertrack.app.debug.DebugPreviewState
import at.bettertrack.app.ui.components.BtAvatar
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
import at.bettertrack.app.ui.components.BtRangeSegmented
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtTabBadgeDot
import at.bettertrack.app.ui.components.ListCard
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.StatCard
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.components.rememberBtPinnedHeaderBehavior
import at.bettertrack.app.ui.portfolio.BtPortfolioChip
import at.bettertrack.app.ui.portfolio.BtPortfolioChipSize
import at.bettertrack.app.ui.portfolio.BtPortfolioChipSizeLarge
import at.bettertrack.app.ui.portfolio.portfolioKindLabel
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.ui.theme.BetterTrackTheme
import at.bettertrack.app.ui.theme.BtIcons
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale

/**
 * Debug-only component gallery: renders EVERY design-system component in all
 * meaningful states for visual verification against the brand (spec §3).
 * Hidden entry: long-press the top-bar wordmark in debug builds.
 *
 * ## The theme toggle
 *
 * This screen is the **only** place the light colour table is reachable while
 * `BtThemeFeatures.LIGHT_MODE_PUBLIC` is false (B2 §1.6). That is deliberate and
 * it is the reason the toggle ships in package B2-A rather than with the
 * Settings → Appearance picker: the 16 sections below are the entire shared
 * component system in one scroll, so 16 × 2 screenshots catch component-level
 * light-mode defects *before* any real screen is migrated.
 *
 * It re-wraps its own subtree in [BetterTrackTheme] with `allowLight = true`,
 * so nothing outside this composable can observe the light table, and closing
 * the gallery returns to the app's own theme with no state to unwind.
 */
@Composable
fun GalleryScreen(
    onClose: () -> Unit,
    /** Step-5 sync-queue debug screen entry (debug builds reach it from here). */
    onOpenSyncDebug: () -> Unit = {},
) {
    var light by remember { mutableStateOf(false) }
    BetterTrackTheme(
        mode = if (light) BtThemeMode.Light else BtThemeMode.Dark,
        allowLight = true,
    ) {
        GalleryContent(
            light = light,
            onToggleTheme = { light = !light },
            onClose = onClose,
            onOpenSyncDebug = onOpenSyncDebug,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryContent(
    light: Boolean,
    onToggleTheme: () -> Unit,
    onClose: () -> Unit,
    onOpenSyncDebug: () -> Unit,
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
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = if (light) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                            contentDescription = if (light) "Switch to dark theme" else "Switch to light theme",
                            tint = bt.goldInk,
                        )
                    }
                    BtBadge(if (light) "LIGHT" else "DARK", kind = BtBadgeKind.Gold)
                },
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
            item { SegmentedSection() }
            // The three identity sheets, together and in this order: a person, a
            // glyph, a portfolio. They are the app's only multi-hue surfaces, so
            // this is the one screen where the whole colour vocabulary can be
            // judged at once — and, with the toggle above, in both substrates.
            item { ProfileAvatarSection() }
            item { OriginIconSection() }
            item { PortfolioChipSection() }
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
 *
 * ## Why every specimen here takes a PINNED behaviour (bug fix 2026-08-08)
 *
 * These four are the only [BtCollapsingHeader]s in the app that are rendered as
 * scrollable **content** rather than as a screen's `topBar`, and that is what made
 * the gallery unscrollable: M3's `TwoRowsTopAppBar` hangs a vertical
 * `Modifier.draggable` on the whole bar whenever it is handed a behaviour whose
 * `isPinned` is false, so the user can resize the bar by dragging the bar itself.
 * That draggable does not participate in nested scroll — it simply eats the
 * gesture. Four specimens at 112–132dp each cover very nearly a full sheet
 * viewport, so once this section was on screen every mid-screen swipe was
 * swallowed and the list looked frozen.
 *
 * `isPinned` gates **only** that drag modifier — the two-row layout reads
 * `state.heightOffset` either way — so a pinned behaviour renders these
 * pixel-identically while leaving the gesture to the `LazyColumn` that owns it.
 * It is also what the paragraph above always claimed was happening ("rendered at
 * two pinned scroll positions"), and it makes the forced-collapse assignment below
 * stable: a pinned behaviour never writes `heightOffset` back.
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
                scrollBehavior = rememberBtPinnedHeaderBehavior(),
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
            val collapsed = rememberBtPinnedHeaderBehavior()
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
                scrollBehavior = rememberBtPinnedHeaderBehavior(),
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
                scrollBehavior = rememberBtPinnedHeaderBehavior(),
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
                            if (i < bt.chartSeries.size) bt.chartSeries[i] else bt.chartCash,
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
                            Box(Modifier.size(6.dp).background(bt.chartSeries[i], CircleShape))
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
        // Deliberately NOT range labels any more. This row used to demo the chip
        // with `1D 1W 1M 1Y Max`, which is exactly the use the range picker was
        // taken away from on 2026-08-08 — leaving it here would keep advertising
        // the retired pattern as the way to build one. A chip is for independent
        // filters; an exclusive choice is a segmented control, below.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Stocks", "ETFs", "Crypto").forEachIndexed { i, label ->
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
 * The two segmented controls that frame the hero chart, side by side on one page.
 *
 * They ship stacked around a canvas and can never be compared there, which is the
 * whole reason this section exists: the owner's ask on 2026-08-08 was that the
 * range picker be *"the same design as the €% € % thingy"*, and sameness is a
 * thing you check by putting two of them a few dp apart. Both width policies are
 * shown — the display picker sizing to its glyphs, the range picker dividing the
 * width it is given — because that difference is intentional and has to survive
 * looking right rather than only measuring right.
 */
@Composable
private fun SegmentedSection() {
    GallerySection("Segmented controls — chart mode + chart range") {
        var mode by remember { mutableStateOf("€%") }
        BtSegmented(
            options = listOf("€%", "€", "%"),
            selected = mode,
            label = { it },
            onSelect = { mode = it },
            minSegmentWidth = 46.dp,
        )
        var range by remember { mutableStateOf("1M") }
        // The portfolio hero's six, which divide the width.
        BtRangeSegmented(
            options = listOf("1D", "1W", "1M", "6M", "1Y", "Max"),
            selected = range,
            label = { it },
            onSelect = { range = it },
            modifier = Modifier.fillMaxWidth(),
        )
        var assetRange by remember { mutableStateOf("1M") }
        // The asset page's eight, which do not — this is the scrolling fallback.
        BtRangeSegmented(
            options = listOf("1D", "1W", "1M", "3M", "6M", "1Y", "5Y", "Max"),
            selected = assetRange,
            label = { it },
            onSelect = { assetRange = it },
            modifier = Modifier.fillMaxWidth(),
        )
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

/** Four columns: the widest grid that keeps a 48dp avatar and its id legible. */
private const val GALLERY_GRID_COLUMNS = 4

/**
 * One captioned specimen. The caption is the **identifier a builder would type**
 * — a wire id, a `BtIcons` property name, a kind label — because that is the
 * question this screen is asked ("which one is the fox?"), and a gallery that
 * shows artwork without naming it answers only half of it.
 */
@Composable
private fun GallerySpecimen(
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = BtTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A fixed-column grid of specimens.
 *
 * Deliberately `Row`s of weighted cells rather than a `LazyVerticalGrid`: this
 * whole screen is one `LazyColumn`, and nesting a lazy grid inside a lazy column
 * needs a hard-coded height — which for a 28-glyph sheet is exactly the number
 * that goes stale the next time a glyph is added.
 */
@Composable
private fun GallerySpecimenGrid(
    count: Int,
    cell: @Composable (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        (0 until count).chunked(GALLERY_GRID_COLUMNS).forEach { indices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                indices.forEach { i ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { cell(i) }
                }
                // A short last row must not stretch its cells wider than the
                // rows above it, or the grid stops reading as a grid.
                repeat(GALLERY_GRID_COLUMNS - indices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The 16 curated avatars, plus the name-derived fallback.
 *
 * The fallback row is the half that actually needs checking. `BtAvatar` picks it
 * with a literal port of the web's hash, so the same seed must produce the same
 * face in both clients — and the three seeds shown ("chris", "Christian", "cw")
 * are three different strings for one person, which is precisely the case where
 * a drifted hash would show three different faces and look like a bug in the
 * data rather than in the hash.
 */
@Composable
private fun ProfileAvatarSection() {
    val bt = BtTheme.colors
    GallerySection("Profile avatars — 16 curated ids (B2-C)") {
        GallerySpecimenGrid(count = BT_PROFILE_ICONS.size) { i ->
            val id = BT_PROFILE_ICONS[i]
            GallerySpecimen(caption = id) {
                BtAvatar(name = id, iconId = id, size = 48.dp)
            }
        }
        Text(
            text = "No iconId — the deterministic name-derived fallback, plus the gold self ring",
            style = MaterialTheme.typography.labelSmall,
            color = bt.textMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("chris", "Christian", "cw").forEach { seed ->
                GallerySpecimen(caption = "\"$seed\"", modifier = Modifier.weight(1f)) {
                    BtAvatar(name = seed, size = 48.dp)
                }
            }
            GallerySpecimen(caption = "gold = true", modifier = Modifier.weight(1f)) {
                BtAvatar(name = "Christian", iconId = "fox", size = 48.dp, gold = true)
            }
        }
    }
}

/**
 * Every glyph in [BtIcons], at the 24dp the chrome draws them.
 *
 * Shown as one sheet because the set's whole claim is *uniformity* — one 1.6
 * stroke, one cap style, one optical weight — and a stroke that is off by a
 * tenth is invisible next to Material but obvious next to its own siblings.
 * This is also the sheet that says which glyphs EXIST, so a builder reaching for
 * an Origin icon can see whether the one they want is in the set or whether they
 * are about to invent it (which is out of scope — see the [BtIcons] KDoc).
 */
@Composable
private fun OriginIconSection() {
    val bt = BtTheme.colors
    val glyphs = remember {
        listOf(
            "UserLock" to BtIcons.UserLock,
            "Family" to BtIcons.Family,
            "Briefcase" to BtIcons.Briefcase,
            "PiggyBank" to BtIcons.PiggyBank,
            "Building" to BtIcons.Building,
            "Users" to BtIcons.Users,
            "Pie" to BtIcons.Pie,
            "Workbench" to BtIcons.Workbench,
            "Assets" to BtIcons.Assets,
            "People" to BtIcons.People,
            "Search" to BtIcons.Search,
            "Settings" to BtIcons.Settings,
            "Bell" to BtIcons.Bell,
            "Inbox" to BtIcons.Inbox,
            "Plus" to BtIcons.Plus,
            "X" to BtIcons.X,
            "Check" to BtIcons.Check,
            "ChevronRight" to BtIcons.ChevronRight,
            "ChevronLeft" to BtIcons.ChevronLeft,
            "ChevronUp" to BtIcons.ChevronUp,
            "ChevronDown" to BtIcons.ChevronDown,
            "More" to BtIcons.More,
            "TrendingUp" to BtIcons.TrendingUp,
            "TrendingDown" to BtIcons.TrendingDown,
            "Sun" to BtIcons.Sun,
            "Moon" to BtIcons.Moon,
            "Home" to BtIcons.Home,
            "Wallet" to BtIcons.Wallet,
        )
    }
    GallerySection("Origin icons — ${glyphs.size} glyphs, 1.6 stroke, 24dp") {
        GallerySpecimenGrid(count = glyphs.size) { i ->
            val (name, glyph) = glyphs[i]
            GallerySpecimen(caption = name) {
                Icon(
                    imageVector = glyph,
                    contentDescription = null,
                    tint = bt.textSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = "Origin owns chrome + identity; Material Outlined owns the utility long tail. A single row group never mixes the two.",
            style = MaterialTheme.typography.labelSmall,
            color = bt.textMuted,
        )
    }
}

/**
 * The portfolio identity chip, in every hue it can take.
 *
 * All six at once is the point: the set has to survive being read as a COLUMN in
 * the switcher, where adjacent hues sit 60dp apart, and the check that matters
 * is whether any two are confusable *once the glyph is covered* — because if
 * they are, the glyph is doing all the work and the hue is decoration. Both
 * sizes are stacked per kind so the 26dp row chip and the 30dp trigger can be
 * compared without scrolling between them.
 */
@Composable
private fun PortfolioChipSection() {
    val bt = BtTheme.colors
    GallerySection("Portfolio icon chips — 26dp row · 30dp trigger") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BtPortfolioKind.entries.forEach { kind ->
                GallerySpecimen(
                    caption = portfolioKindLabel(kind),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BtPortfolioChip(kind = kind, size = BtPortfolioChipSize)
                        BtPortfolioChip(kind = kind, size = BtPortfolioChipSizeLarge)
                    }
                }
            }
        }
        Text(
            text = "group = true — a mirrorchain copy takes the trio glyph on the sixth hue, whichever Icon it is filed under",
            style = MaterialTheme.typography.labelSmall,
            color = bt.textMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GallerySpecimen(caption = "group · 26dp", modifier = Modifier.weight(1f)) {
                BtPortfolioChip(kind = BtPortfolioKind.Private, group = true)
            }
            GallerySpecimen(caption = "group · 30dp", modifier = Modifier.weight(1f)) {
                BtPortfolioChip(
                    kind = BtPortfolioKind.Private,
                    group = true,
                    size = BtPortfolioChipSizeLarge,
                )
            }
            Spacer(Modifier.weight(2f))
        }
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
