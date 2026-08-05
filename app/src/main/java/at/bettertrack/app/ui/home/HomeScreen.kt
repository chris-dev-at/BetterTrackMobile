package at.bettertrack.app.ui.home

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.shows
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.format.BtDiscreetMode
import at.bettertrack.app.ui.portfolio.deltaColor
import at.bettertrack.app.ui.prices.NetWorthState
import at.bettertrack.app.ui.prices.NoPricesHero
import at.bettertrack.app.ui.prices.UnpricedNote
import at.bettertrack.app.ui.prices.priceCoverage
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.workboard.WorkboardEntry
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

/**
 * The Home tab — the app's front door (R-arc mandate §2/§3).
 *
 * ## Home is an index, and that decides its whole shape
 *
 * Every row Home offers is *owned* by another tab. It holds no data of its own,
 * it duplicates no screen, and it must never become a fifth place where a
 * feature half-lives. That is why this composable takes exactly two navigation
 * callbacks and no navigation controller:
 *
 *  - [onOpen] routes through the shell's deep-link helper, which switches to the
 *    target's OWNING tab before pushing its detail;
 *  - [onSwitchTab] switches to a tab with bottom-bar semantics.
 *
 * A bare push from here would stack a Portfolio-owned or People-owned detail on
 * the Home tab, and the next bottom-bar tap would save it under the wrong tab
 * and bounce the user back into it — precisely the bug S6 P1-8 fixed. Keeping
 * the seam this narrow makes that class of mistake impossible to write rather
 * than merely discouraged, which is why this package holds no navigation-
 * controller reference at all: it is a one-line grep in review, and it stays
 * true as R1-B fills the screen in.
 *
 * ## R1-A scope
 *
 * This is the skeleton package's Home: the hero, and the one actionable row
 * whose data source R1-A also built ([at.bettertrack.app.data.repo.AlertsRepository.triggered]).
 * Movers, the remaining "Needs you" rows, the portfolios list and pull-to-refresh
 * are R1-B's, and they compose onto this file without changing its signature.
 */
@Composable
fun HomeScreen(
    onOpen: (NotifDeepLink) -> Unit,
    onSwitchTab: (BtTab) -> Unit,
) {
    val storedMode by AppGraph.storageModeStore.mode.collectAsStateWithLifecycle()
    val storageMode = remember(storedMode) { AppGraph.gatedStorageMode(storedMode) }

    val portfolios by AppGraph.portfolioRepository.portfolios
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val active = remember(portfolios) { homeActivePortfolios(portfolios) }

    // Coverage crosses the portfolio boundary: the W6 caveat is about the union
    // of everything the hero claims to have summed, so the holdings flows of all
    // active portfolios are combined rather than read one at a time.
    val activeIds = remember(active) { active.map { it.id } }
    val holdingsFlow = remember(activeIds) {
        if (activeIds.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(activeIds.map { AppGraph.portfolioRepository.holdings(it) }) { lists ->
                lists.toList().flatten()
            }
        }
    }
    val holdings by holdingsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val coverage = remember(holdings) { priceCoverage(holdings) }
    val hero = remember(active, coverage) { homeNetWorth(active, coverage) }

    val triggeredAlerts by AppGraph.alertsRepository.triggered.collectAsStateWithLifecycle()
    val showAlerts = storageMode.shows(BtSurface.ALERTS_NOTIFICATIONS) && triggeredAlerts > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        // Generous rhythm (mandate §4: more whitespace, fewer boxes-in-boxes).
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        HomeHero(hero)
        if (showAlerts) {
            NeedsYouBlock {
                TriggeredAlertsRow(
                    count = triggeredAlerts,
                    onClick = {
                        // The alerts manager is a SEGMENT of Workbench, not a
                        // route: ask the tab to open that segment, then switch.
                        // Identical to the shell's own `NotifDeepLink.Alerts`
                        // handling, deliberately — one behaviour, two entries.
                        WorkboardEntry.requestAlerts()
                        onSwitchTab(BtTab.Workbench)
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Hero ────────────────────────────────────────────────────────────────────

/**
 * Net worth across every active portfolio.
 *
 * The three shapes [homeNetWorth] can return map onto three visually distinct
 * things on purpose: a confident number, a number with a stated scope, and no
 * number at all. Nothing here renders a figure the logic did not authorise.
 */
@Composable
private fun HomeHero(state: HomeHeroState) {
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    // Discreet mode: press and hold the hero to peek, release to re-hide. Bound
    // to the gesture rather than a latch, and only armed while masking — exactly
    // the Portfolio hero's contract, because it is the same act on a bigger
    // number and learning it twice would be absurd.
    val peek = if (BtDiscreetMode.enabled) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    BtDiscreetMode.setRevealing(true)
                    try {
                        awaitRelease()
                    } finally {
                        BtDiscreetMode.setRevealing(false)
                    }
                },
            )
        }
    } else {
        Modifier
    }

    Column(Modifier.fillMaxWidth().then(peek)) {
        Text(
            text = stringResource(R.string.bt_home_net_worth),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
        Spacer(Modifier.height(4.dp))
        when (state) {
            HomeHeroState.Loading -> {
                BtSkeleton(Modifier.width(240.dp).height(48.dp))
                Spacer(Modifier.height(8.dp))
                BtSkeleton(Modifier.width(140.dp).height(14.dp))
            }

            HomeHeroState.NoPortfolios -> {
                Text(
                    text = stringResource(R.string.bt_home_no_portfolios_title),
                    style = BtTheme.type.moneyLarge,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.bt_home_no_portfolios_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }

            is HomeHeroState.Ready -> when (val worth = state.netWorth) {
                is NetWorthState.Unpriceable -> NoPricesHero()

                is NetWorthState.Value -> {
                    MoneyText(value = worth.eur, style = BtTheme.type.moneyHero)
                    // Two independent caveats, both load-bearing, neither
                    // allowed to stand in for the other: how many PORTFOLIOS
                    // the sum covers, and how many HOLDINGS inside them could
                    // be priced at all.
                    if (state.partial) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.bt_home_across_portfolios,
                                state.covered,
                                state.active,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                    UnpricedNote(
                        coverage = worth.coverage,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (state.showDayChange) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MoneyText(
                                value = state.dayChangeEur,
                                style = BtTheme.type.numberCaption,
                                colorMode = MoneyColorMode.GainLoss,
                                showSign = true,
                            )
                            state.dayChangePct?.let { pct ->
                                Text(
                                    text = " (${formatPercent(pct, locale)})",
                                    style = BtTheme.type.numberCaption,
                                    color = deltaColor(pct),
                                )
                            }
                            Text(
                                text = " · " + stringResource(R.string.bt_overview_today),
                                style = BtTheme.type.numberCaption,
                                color = bt.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── "Needs you" ─────────────────────────────────────────────────────────────

/**
 * The actionable block (mandate §3: value first, then what needs a decision).
 *
 * Its header only exists when it has content — the whole block is absent when
 * every row's count is zero, never an empty card announcing that nothing needs
 * doing. §4.5's "absent, not greyed" rule applied to a section instead of a tab.
 */
@Composable
private fun NeedsYouBlock(content: @Composable () -> Unit) {
    val bt = BtTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.bt_home_needs_you),
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
        )
        content()
    }
}

/** "2 alerts triggered" → the Workbench alerts segment. */
@Composable
private fun TriggeredAlertsRow(count: Int, onClick: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = bt.goldEmphasis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(R.plurals.bt_home_alerts_triggered, count, count),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                )
                Text(
                    text = stringResource(R.string.bt_home_alerts_triggered_sub),
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
