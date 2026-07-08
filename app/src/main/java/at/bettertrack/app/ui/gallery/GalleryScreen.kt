package at.bettertrack.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.ListCard
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.StatCard
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.debug.DebugPreviewState
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.theme.BtTheme
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
            item { StatCardSection() }
            item { ListCardSection() }
            item { ButtonSection() }
            item { ChipBadgeSection() }
            item { SkeletonSection() }
            item { EmptyStateSection() }
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

@Composable
private fun StatCardSection() {
    val bt = BtTheme.colors
    val locale = Locale.getDefault()
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

@Composable
private fun EmptyStateSection() {
    GallerySection("Empty state") {
        at.bettertrack.app.ui.components.BtCard {
            BtEmptyState(
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                title = "No transactions yet",
                message = "Your buys and sells will appear here.",
                action = { BtSecondaryButton(text = "Add transaction", onClick = {}) },
            )
        }
    }
}

@Composable
private fun ErrorStateSection() {
    GallerySection("Error state") {
        at.bettertrack.app.ui.components.BtCard {
            BtErrorState(onRetry = {})
        }
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
