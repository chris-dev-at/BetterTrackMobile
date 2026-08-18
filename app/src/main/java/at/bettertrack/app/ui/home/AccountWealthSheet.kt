package at.bettertrack.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.charts.BtAreaChart
import at.bettertrack.app.ui.charts.rangeLabel
import at.bettertrack.app.ui.charts.rangeWord
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtRangeSegmented
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.portfolio.deltaColor
import at.bettertrack.app.ui.portfolio.formatChartScrubDate
import at.bettertrack.app.ui.portfolio.rangeDeltaEur
import at.bettertrack.app.ui.prices.NetWorthState
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The account-wealth graph — every active portfolio's **server** curve, and no
 * curve the app made up.
 *
 * ## What this surface is, in one sentence
 *
 * The number at the top of Overview says what the whole account is worth *now*;
 * this says what it has been doing. See [AccountWealthLogic]'s file KDoc for why
 * that cannot currently be one line, and for the §7.1 rule that keeps it from
 * being one line drawn client-side.
 *
 * ## Why a bottom sheet hung off the hero, and not a page
 *
 * It is the same reach argument the picker family already made and the same idiom
 * the rest of the app uses — user-facing surfaces come up from the bottom here,
 * never out of an anchored dropdown. But the deciding reason is *where the user
 * is standing*: this view answers a question you have while looking at the net
 * worth figure, so it belongs a thumb's travel from that figure and it must give
 * it back without a navigation hop. A pushed page would put the account's total
 * and the account's history on two different screens, which is exactly the split
 * this feature exists to close.
 *
 * It also costs the navigation graph nothing: no route, no back-stack entry, no
 * deep-link surface — the door is [AccountWealthRow], directly under the hero.
 *
 * ## The states, all of them
 *
 *  · **Signed out** — a 401 on the fan-out. Nothing here can be true after that,
 *    so the whole body is replaced by the session-expired error.
 *  · **Empty** — no active portfolio, so there is no account history to draw.
 *  · **Loading** — skeletons, per slot, never a flat line at zero.
 *  · **Error** — a slot whose fetch failed with nothing cached says so on its own;
 *    the other slots keep drawing, because one portfolio's failure is not the
 *    account's.
 *  · **Offline** — the cached series render under the standard [OfflineBanner],
 *    stamped with the OLDEST sync among them (see [accountWealthAsOfMs]).
 *
 * Discreet mode needs no handling here: every figure goes through [MoneyText] /
 * [BtAreaChart], both of which mask themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountWealthSheet(
    /**
     * The hero state Overview already computed — passed in rather than recomputed
     * so the sheet's headline and the number the user tapped are, by construction,
     * the same figure produced by the same sanctioned totals roll-up.
     */
    hero: HomeHeroState,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val vm: AccountWealthViewModel = viewModel {
        AccountWealthViewModel(
            repo = AppGraph.portfolioRepository,
            connectivity = AppGraph.connectivityMonitor,
        )
    }

    val scope by vm.scope.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val series by vm.series.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val signedOut by vm.signedOut.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()

    // The fetch is driven from HERE, not from the view model's `init`, so it is
    // bound to the sheet being on screen: the fan-out starts when the sheet opens,
    // re-runs when the window or the portfolio set changes, and stops existing the
    // moment the sheet is dismissed. A collector in `init` would keep every
    // per-portfolio Room flow hot for the rest of the tab's life.
    val chartedIds = remember(scope) { scope.chartedIds() }
    LaunchedEffect(chartedIds, range, isOnline) { vm.refresh() }

    // Capped for the same reason the picker family caps: content that fills the
    // full height makes the inner scroll and the sheet's own drag fight over every
    // fling. Taller than a picker's 0.55 because this one is charts, not rows.
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * SHEET_BODY_FRACTION).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = bt.textMuted) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(
                text = stringResource(R.string.bt_account_wealth_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))

            // Full-bleed: the banner draws its own divider edge to edge, exactly
            // as it does at the top of every other screen.
            if (!isOnline) {
                OfflineBanner(asOfMs = accountWealthAsOfMs(series.values))
                Spacer(Modifier.height(10.dp))
            }

            if (signedOut) {
                BtErrorState(
                    message = BtMessage(R.string.bt_err_unauthenticated),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                when (val s = scope) {
                    AccountWealthScope.None -> BtEmptyState(
                        title = stringResource(R.string.bt_home_no_portfolios_title),
                        message = stringResource(R.string.bt_home_no_portfolios_body),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )

                    is AccountWealthScope.Single -> SinglePortfolioWealth(
                        hero = hero,
                        history = series[s.portfolio.id],
                        failed = s.portfolio.id in failed,
                        range = range,
                        onRange = vm::setRange,
                    )

                    is AccountWealthScope.Multi -> MultiPortfolioWealth(
                        scope = s,
                        hero = hero,
                        series = series,
                        failed = failed,
                        range = range,
                        onRange = vm::setRange,
                        maxBodyHeight = maxBodyHeight,
                    )
                }
            }
        }
    }
}

/**
 * One active portfolio: its server series **is** the account's wealth history.
 *
 * Rendered exactly like the portfolio hero — headline, curve, range rail under the
 * canvas — and with no note of any kind, because there is nothing to qualify. This
 * is not an approximation of an account curve that the platform owes us; for a
 * single-portfolio account it is the complete, exact answer, and hedging it would
 * be worse than useless.
 */
@Composable
private fun SinglePortfolioWealth(
    hero: HomeHeroState,
    history: PortfolioHistory?,
    failed: Boolean,
    range: HistoryRange,
    onRange: (HistoryRange) -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val state = remember(history, failed) { accountSeriesState(history, failed) }

    // The scrub owns the headline while a finger is down — the same contract the
    // portfolio hero has, on the same gesture, so it is learned once.
    var scrub by remember { mutableStateOf<HistoryPoint?>(null) }
    // Keyed on the WINDOW only: a Room re-emission of the same series must not
    // yank the crosshair out from under a finger that is still down.
    LaunchedEffect(range) { scrub = null }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = scrub?.let { formatChartScrubDate(it.epochMillis, history?.isSubDaily == true, locale) }
                    ?: stringResource(R.string.bt_home_net_worth),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(2.dp))
            AccountHeadline(hero = hero, scrubEur = scrub?.valueEur)
            // Reserved height so scrubbing cannot shift the canvas below.
            Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                if (scrub == null) {
                    RangeDeltaLine(state = state, range = range)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        AccountCurve(
            state = state,
            height = SINGLE_CHART_HEIGHT,
            contentDescription = stringResource(R.string.bt_account_wealth_chart_cd),
            onScrub = { scrub = it },
        )
        Spacer(Modifier.height(12.dp))
        // Under the canvas, matching the portfolio hero: with one curve the rail
        // is that canvas's x-axis, chosen, so it sits against it.
        BtRangeSegmented(
            options = ACCOUNT_WEALTH_RANGES,
            selected = range,
            label = { rangeLabel(it) },
            onSelect = onRange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentDescription = stringResource(R.string.bt_chart_range_cd),
        )
    }
}

/**
 * Several active portfolios: the sanctioned snapshot total, then every portfolio's
 * own server curve as a small multiple.
 *
 * Small multiples rather than one combined line because a combined line would have
 * to be arithmetic this app is not allowed to do (see [AccountWealthLogic]). What
 * the user loses is a single silhouette; what they keep is every real shape in the
 * account, side by side, each one a series the server computed and labelled with
 * the portfolio it belongs to. The one quiet line above the list says which of
 * those two things they are looking at — stated once, in passing, because a banner
 * apologising for the platform on every open would be worse than the gap.
 *
 * The rail sits ABOVE the list here, not under a canvas: it governs every curve
 * below it, and a control for twelve charts placed after the twelfth would be a
 * control nobody finds.
 */
@Composable
private fun MultiPortfolioWealth(
    scope: AccountWealthScope.Multi,
    hero: HomeHeroState,
    series: Map<String, PortfolioHistory>,
    failed: Set<String>,
    range: HistoryRange,
    onRange: (HistoryRange) -> Unit,
    maxBodyHeight: Dp,
) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.bt_home_net_worth),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(2.dp))
            AccountHeadline(hero = hero, scrubEur = null)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bt_account_wealth_multi_note),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            BtRangeSegmented(
                options = ACCOUNT_WEALTH_RANGES,
                selected = range,
                label = { rangeLabel(it) },
                onSelect = onRange,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = stringResource(R.string.bt_chart_range_cd),
            )
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxBodyHeight),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(count = scope.charted.size, key = { scope.charted[it].id }) { index ->
                val portfolio = scope.charted[index]
                PortfolioSmallMultiple(
                    portfolio = portfolio,
                    state = accountSeriesState(series[portfolio.id], portfolio.id in failed),
                    range = range,
                )
            }
            if (scope.omitted > 0) {
                item(key = "omitted") {
                    Text(
                        text = pluralStringResource(
                            R.plurals.bt_account_wealth_not_charted,
                            scope.omitted,
                            scope.omitted,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }
}

/**
 * One portfolio's slot: name and server value on one line, the server's own range
 * performance beside it, and the server's balance curve underneath.
 *
 * The value is `totals.totalValueEur` — the same server scalar the Overview list
 * row prints — and the percentage is `PortfolioHistory.rangePerformancePct`, i.e.
 * the last point of the server's `performance` series, read verbatim. Neither is
 * derived here, and nothing on this row is combined with anything on another.
 */
@Composable
private fun PortfolioSmallMultiple(
    portfolio: PortfolioEntity,
    state: AccountSeriesState,
    range: HistoryRange,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val chartCd = stringResource(R.string.bt_account_wealth_series_cd, portfolio.name)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = portfolio.name,
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            val pct = (state as? AccountSeriesState.Curve)?.rangePerformancePct
            if (pct != null) {
                Text(
                    text = formatPercent(pct, locale),
                    style = BtTheme.type.numberCaption,
                    color = deltaColor(pct),
                )
                Spacer(Modifier.width(8.dp))
            }
            val totals = portfolio.totals
            if (totals != null) {
                MoneyText(
                    value = totals.totalValueEur,
                    style = BtTheme.type.moneySmall,
                    color = bt.textPrimary,
                )
            } else {
                // Not "0,00 €": this portfolio's detail has not landed yet, and the
                // headline above already states what it covers.
                BtSkeleton(Modifier.width(72.dp).height(14.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        AccountCurve(
            state = state,
            height = SMALL_MULTIPLE_HEIGHT,
            contentDescription = chartCd,
            onScrub = null,
        )
        // The window is named once per slot, in words, so a curve read out of
        // context cannot be mistaken for a different span than the rail selected.
        Text(
            text = rangeWord(range),
            style = MaterialTheme.typography.labelSmall,
            color = bt.textMuted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/**
 * The account's snapshot figure — [homeNetWorth]'s verdict, rendered, or the
 * scrubbed point when a finger is on a curve.
 *
 * Every branch here is a state the hero logic already decided; this composable
 * invents no number and skips no caveat. [NetWorthState.Unpriceable] deliberately
 * prints nothing rather than a confident zero (W6).
 */
@Composable
private fun AccountHeadline(hero: HomeHeroState, scrubEur: Double?) {
    val bt = BtTheme.colors
    if (scrubEur != null) {
        MoneyText(value = scrubEur, style = BtTheme.type.moneyLarge)
        return
    }
    when (hero) {
        HomeHeroState.Loading -> BtSkeleton(Modifier.width(200.dp).height(36.dp))
        HomeHeroState.NoPortfolios -> Unit
        is HomeHeroState.Ready -> when (val worth = hero.netWorth) {
            is NetWorthState.Unpriceable -> Text(
                text = stringResource(R.string.bt_overview_chart_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textMuted,
            )

            is NetWorthState.Value -> {
                MoneyText(value = worth.eur, style = BtTheme.type.moneyLarge)
                if (hero.partial) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.bt_home_across_portfolios,
                            hero.active,
                            hero.covered,
                            hero.active,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
        }
    }
}

/**
 * "+120,00 € · +2,1 % · past month" for the single-portfolio headline.
 *
 * The € is [rangeDeltaEur] — the server series' last point minus its first, the
 * same display subtraction of two rendered server values the portfolio hero makes
 * — and the % is the server's own `rangePerformancePct`. The two are separated by
 * a middot rather than written as `€ (%)` because outside a same-basis window they
 * are two measurements that merely share a span, which is the distinction
 * `samePairBasis` exists to make on the portfolio hero.
 */
@Composable
private fun RangeDeltaLine(state: AccountSeriesState, range: HistoryRange) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val curve = state as? AccountSeriesState.Curve ?: return
    val deltaEur = remember(curve.points) { rangeDeltaEur(curve.points) } ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        MoneyText(
            value = deltaEur,
            style = BtTheme.type.numberCaption,
            color = deltaColor(deltaEur),
            showSign = true,
        )
        curve.rangePerformancePct?.let { pct ->
            Text(
                text = " · " + formatPercent(pct, locale),
                style = BtTheme.type.numberCaption,
                color = deltaColor(pct),
            )
        }
        Text(
            text = " · " + rangeWord(range),
            style = BtTheme.type.numberCaption,
            color = bt.textMuted,
        )
    }
}

/**
 * One server curve, or the honest statement of why there is none.
 *
 * The four branches are [accountSeriesState]'s, one-for-one — a skeleton while the
 * verdict is unknown, a calm inline line when the server simply has no shape for
 * this window, a named failure when the fetch failed with nothing cached, and the
 * curve itself otherwise.
 */
@Composable
private fun AccountCurve(
    state: AccountSeriesState,
    height: Dp,
    contentDescription: String,
    onScrub: ((HistoryPoint?) -> Unit)?,
) {
    val bt = BtTheme.colors
    when (state) {
        AccountSeriesState.Loading -> Box(Modifier.fillMaxWidth().height(height)) {
            BtSkeleton(Modifier.fillMaxWidth().height(height).padding(horizontal = 20.dp))
        }

        AccountSeriesState.Failed -> Box(
            modifier = Modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center,
        ) {
            BtInlineEmpty(
                text = stringResource(R.string.bt_account_wealth_series_failed),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        AccountSeriesState.Empty -> Box(
            modifier = Modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center,
        ) {
            BtInlineEmpty(
                text = stringResource(R.string.bt_overview_chart_empty),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        is AccountSeriesState.Curve -> BtAreaChart(
            points = state.points,
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { this.contentDescription = contentDescription },
            lineColor = bt.gold,
            minimal = true,
            // A sheet, not the page: the scrub scrim has to be mixed from the
            // surface the chart is actually drawn on or the dimming reads as a
            // grey haze over the curve.
            scrimColor = bt.surfaceHigh,
            onScrub = onScrub,
        )
    }
}

/** The single account curve — hero-scale, because it is the whole answer. */
private val SINGLE_CHART_HEIGHT = 180.dp

/**
 * One portfolio's curve in the multiples list.
 *
 * Small on purpose: the list's job is comparison of SHAPES, and twelve full-height
 * charts would be twelve screens of scrolling in which nothing is ever next to
 * anything else. 72dp still resolves a trend and a drawdown at phone widths.
 */
private val SMALL_MULTIPLE_HEIGHT = 72.dp

/** See the cap's rationale at the call site. */
private const val SHEET_BODY_FRACTION = 0.62f

// ── State ───────────────────────────────────────────────────────────────────

/**
 * The fan-out behind [AccountWealthSheet]: N independent
 * `GET /portfolios/{id}/history` calls, cached per portfolio × range by
 * [PortfolioRepository], surfaced as a map the renderer reads by id.
 *
 * The map is the point. Anything that merged these series into one would be the
 * client-side money math §7.1 forbids, and keeping them keyed by the portfolio
 * they came from means the type itself never offers the temptation: there is no
 * combined series in this class to accidentally render.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountWealthViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    private val _range = MutableStateFlow(ACCOUNT_WEALTH_DEFAULT_RANGE)
    val range: StateFlow<HistoryRange> = _range.asStateFlow()

    /** Which portfolios are charted — active only, biggest first, capped. */
    val scope: StateFlow<AccountWealthScope> = repo.portfolios
        .map { accountWealthScope(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountWealthScope.None)

    /**
     * The cached series for the selected window, by portfolio id.
     *
     * `flatMapLatest` over the (ids, range) pair rather than over the scope, and
     * `distinctUntilChanged` in front of it, so a totals write — which happens on
     * every refresh and changes the scope's ENTITIES but not its ids — does not
     * tear down and rebuild every history subscription underneath the charts.
     */
    val series: StateFlow<Map<String, PortfolioHistory>> =
        combine(scope, _range) { s, r -> s.chartedIds() to r }
            .distinctUntilChanged()
            .flatMapLatest { (ids, r) ->
                if (ids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(ids.map { id -> repo.history(id, r).map { id to it } }) { pairs ->
                        pairs.mapNotNull { (id, h) -> h?.let { id to it } }.toMap()
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Ids whose fetch failed for the CURRENT window. Cleared on every attempt. */
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val failed: StateFlow<Set<String>> = _failed.asStateFlow()

    /**
     * A 401 came back. Distinct from a failed slot because it is not about one
     * portfolio: no call on this surface can succeed until the user signs in
     * again, so the whole body says so instead of twelve slots each saying it.
     */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * Refetch every charted portfolio's series for the selected window.
     *
     * Concurrent, because these are N unrelated GETs and the sheet is usable the
     * moment the first of them lands. Offline it does nothing at all rather than
     * marking every id as failed — the cache is what the user is looking at and
     * the banner above already explains it; poisoning the failed set here would
     * paint "couldn't load" over perfectly good cached curves.
     */
    fun refresh() {
        val ids = scope.value.chartedIds()
        if (ids.isEmpty() || !isOnline.value) return
        val window = _range.value
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val results = coroutineScope {
                ids.map { id -> async { id to repo.refreshHistory(id, window) } }.awaitAll()
            }
            // Recomputed wholesale rather than accumulated: a retry that succeeds
            // has to be able to CLEAR a slot's failure, and a stale id left in the
            // set would keep a curve hidden behind an error that no longer exists.
            _failed.value = results.mapNotNull { (id, r) -> id.takeIf { r is BtResult.Err } }.toSet()
            _signedOut.value = results.any { (_, r) -> r is BtResult.Err && r.error.isUnauthorized }
        }
    }

    /** Change the window. Guarded, so only a window this view offers can be set. */
    fun setRange(range: HistoryRange) {
        val next = accountWealthRangeOrDefault(range)
        if (next == _range.value) return
        _range.value = next
        // The failed set describes the PREVIOUS window; carrying it over would
        // show an error slot for a call that has not been made yet.
        _failed.value = emptySet()
    }
}
