package at.bettertrack.app.ui.social

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.SharedConglomerateDetailResponse
import at.bettertrack.app.data.api.dto.SharedPortfolioDetailResponse
import at.bettertrack.app.data.api.dto.SharedWatchlistDetailResponse
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.theme.BtTheme
import java.util.Locale

/** Read-only friend-shared portfolio (§6.9 — no edit affordances anywhere). */
@Composable
fun SharedPortfolioViewScreen(portfolioId: String, onBack: () -> Unit) {
    val state by produceState<BtResult<SharedPortfolioDetailResponse>?>(initialValue = null, portfolioId) {
        value = AppGraph.socialRepository.sharedPortfolio(portfolioId)
    }
    SharedScaffold(title = stringResource(R.string.bt_social_shared_portfolio_title), onBack = onBack) {
        Loaded(state, onRetry = null) { d ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { OwnerHeader(d.owner.username, stringResource(R.string.bt_social_read_only_named, d.name)) }
                item {
                    val bt = BtTheme.colors
                    BtCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.bt_social_net_worth), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
                            MoneyText(value = d.totals.totalValueEur, style = BtTheme.type.moneyLarge)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MoneyText(
                                    value = d.totals.dayChangeEur,
                                    style = MaterialTheme.typography.bodyMedium,
                                    colorMode = MoneyColorMode.GainLoss,
                                    showSign = true,
                                )
                                d.totals.dayChangePct?.let {
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        formatPercent(it, Locale.getDefault()),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (it >= 0) bt.gain else bt.loss,
                                    )
                                }
                                Text(stringResource(R.string.bt_social_today), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MiniStat(stringResource(R.string.bt_social_invested), d.totals.investedEur, Modifier.weight(1f))
                        MiniStat(stringResource(R.string.bt_social_unrealized_pl), d.totals.unrealizedPnlEur, Modifier.weight(1f), gainLoss = true)
                        MiniStat(stringResource(R.string.bt_social_cash), d.totals.cashEur, Modifier.weight(1f))
                    }
                }
                item {
                    Text(
                        stringResource(R.string.bt_social_holdings),
                        style = MaterialTheme.typography.titleSmall,
                        color = BtTheme.colors.textPrimary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(d.holdings, key = { it.asset.symbol + it.quantity }) { h ->
                    val bt = BtTheme.colors
                    BtCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(h.asset.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                                Text(h.asset.name, style = MaterialTheme.typography.bodySmall, color = bt.textMuted, maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                MoneyText(value = h.marketValueEur ?: 0.0, style = MaterialTheme.typography.titleSmall)
                                h.unrealizedPnlPct?.let {
                                    Text(
                                        formatPercent(it, Locale.getDefault()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (it >= 0) bt.gain else bt.loss,
                                    )
                                }
                            }
                        }
                    }
                }
                item { ReadOnlyFooter(d.owner.username) }
            }
        }
    }
}

/** Read-only friend-shared watchlist. */
@Composable
fun SharedWatchlistViewScreen(watchlistId: String, ownerName: String, onBack: () -> Unit) {
    val state by produceState<BtResult<SharedWatchlistDetailResponse>?>(initialValue = null, watchlistId) {
        value = AppGraph.socialRepository.sharedWatchlist(watchlistId)
    }
    SharedScaffold(title = stringResource(R.string.bt_social_shared_watchlist_title), onBack = onBack) {
        Loaded(state, onRetry = null) { d ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { OwnerHeader(d.owner.username, stringResource(R.string.bt_social_read_only_watchlist)) }
                items(d.items, key = { it.id }) { it2 ->
                    val bt = BtTheme.colors
                    BtCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(it2.asset.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                                Text(it2.asset.name, style = MaterialTheme.typography.bodySmall, color = bt.textMuted, maxLines = 1)
                            }
                            Text(it2.asset.currency, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
                        }
                    }
                }
                item { ReadOnlyFooter(d.owner.username) }
            }
        }
    }
}

/** Read-only friend-shared conglomerate. */
@Composable
fun SharedConglomerateViewScreen(conglomerateId: String, onBack: () -> Unit) {
    val state by produceState<BtResult<SharedConglomerateDetailResponse>?>(initialValue = null, conglomerateId) {
        value = AppGraph.socialRepository.sharedConglomerate(conglomerateId)
    }
    SharedScaffold(title = stringResource(R.string.bt_social_shared_conglomerate_title), onBack = onBack) {
        Loaded(state, onRetry = null) { d ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { OwnerHeader(d.owner.username, stringResource(R.string.bt_social_read_only_named, d.name)) }
                d.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    item {
                        Text(desc, style = MaterialTheme.typography.bodyMedium, color = BtTheme.colors.textSecondary)
                    }
                }
                item {
                    Text(stringResource(R.string.bt_social_composition), style = MaterialTheme.typography.titleSmall, color = BtTheme.colors.textPrimary)
                }
                items(d.positions, key = { it.assetId }) { p ->
                    val bt = BtTheme.colors
                    BtCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.asset.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                                Text(p.asset.name, style = MaterialTheme.typography.bodySmall, color = bt.textMuted, maxLines = 1)
                            }
                            Text(
                                "${formatWeight(p.weightPct)}%",
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.goldEmphasis,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                item { ReadOnlyFooter(d.owner.username) }
            }
        }
    }
}

// ── Shared building blocks ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val bt = BtTheme.colors
    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
        // The Scaffold's inner padding (app-bar height + status-bar inset) must be
        // applied HERE, centrally — the content lambdas render LazyColumns with a
        // bare fillMaxSize() and previously dropped this padding, so the first list
        // item clipped under the app bar (owner bug, 2026-07-12). Mirrors the
        // fillMaxSize().padding(pad) pattern used by ChatListScreen/ChatThreadScreen.
    ) { pad -> Box(Modifier.fillMaxSize().padding(pad)) { content() } }
}

@Composable
private fun <T> Loaded(
    state: BtResult<T>?,
    onRetry: (() -> Unit)?,
    content: @Composable (T) -> Unit,
) {
    val bt = BtTheme.colors
    when (val s = state) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = bt.gold)
        }
        is BtResult.Err -> BtErrorState(
            message = if (s.error.httpStatus == 404) stringResource(R.string.bt_social_not_shared_anymore) else s.error.userMessage,
            onRetry = onRetry,
        )
        is BtResult.Ok -> content(s.value)
    }
}

@Composable
private fun OwnerHeader(username: String, subtitle: String) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BtAvatar(name = username, size = 44.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text("@$username", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
        }
        BtBadge(text = stringResource(R.string.bt_social_read_only_badge), kind = BtBadgeKind.Neutral)
    }
}

@Composable
private fun MiniStat(label: String, value: Double, modifier: Modifier = Modifier, gainLoss: Boolean = false) {
    val bt = BtTheme.colors
    BtCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = bt.textMuted, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            MoneyText(
                value = value,
                style = MaterialTheme.typography.titleSmall,
                colorMode = if (gainLoss) MoneyColorMode.GainLoss else MoneyColorMode.Neutral,
                showSign = gainLoss,
            )
        }
    }
}

@Composable
private fun ReadOnlyFooter(username: String) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.bt_social_shared_by_footer, username), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
    }
}

private fun formatWeight(pct: Double): String {
    val r = Math.round(pct * 100.0) / 100.0
    return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
}
