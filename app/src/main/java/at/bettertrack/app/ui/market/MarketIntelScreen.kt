package at.bettertrack.app.ui.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.DividendCalendarEntryDto
import at.bettertrack.app.data.api.dto.DividendCalendarResponse
import at.bettertrack.app.data.api.dto.DividendProjectionResponse
import at.bettertrack.app.data.api.dto.EarningsCalendarEntryDto
import at.bettertrack.app.data.api.dto.EarningsCalendarResponse
import at.bettertrack.app.data.api.dto.NewsDigestGroupDto
import at.bettertrack.app.data.api.dto.NewsDigestResponse
import at.bettertrack.app.data.api.dto.ProjectedDividendHoldingDto
import at.bettertrack.app.data.repo.MarketIntelRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatMoney
import at.bettertrack.app.ui.portfolio.formatQuantity
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * V5 S2c-2 — **portfolio-wide market intel**: what's about to happen across
 * everything the account holds or watches.
 *
 * Four independent reads on one screen — the earnings calendar, the dividend
 * calendar, the projected dividend income and the grouped news digest — and each
 * one keeps its own three-way outcome:
 *
 *  - **unavailable** (`available:false`) ⇒ the section is ABSENT, header and all;
 *  - **available and empty** ⇒ the section renders with a calm empty line,
 *    because "nothing is coming up" is an answer;
 *  - **failed** ⇒ an inline error with retry for THAT section only, while the
 *    other three keep whatever they successfully loaded.
 *
 * They are never collapsed into one screen-level state, with exactly one
 * exception: when all four come back unavailable there is nothing truthful to
 * put on the screen at all, so it says so once ([MarketIntelUiState.allUnavailable])
 * instead of rendering four absences under a title.
 *
 * Three of the four reads are killed by paranoid mode (403), which arrives here
 * as a per-section [IntelBlockUi.Failed] carrying the server's own explanation —
 * an honest "we can't fetch this" rather than a fabricated empty list.
 *
 * Every EUR figure goes through [formatEur] and every native amount through
 * [formatMoney], so discreet mode masks the whole screen without this file
 * knowing the feature exists.
 */

// ═════════════════════════ One section's outcome ════════════════════════════

/**
 * A portfolio-wide block's state. [Unavailable] is deliberately distinct from an
 * empty [Ready] payload and from [Failed]; flattening any two of them would make
 * the screen lie about one of the three situations.
 */
sealed interface IntelBlockUi<out T> {
    data object Loading : IntelBlockUi<Nothing>
    data class Ready<T>(val value: T) : IntelBlockUi<T>

    /** The server says it cannot serve this block — render nothing at all. */
    data object Unavailable : IntelBlockUi<Nothing>

    /** The request failed (offline / 401 / paranoid 403) — render a retry. */
    data class Failed(val message: BtMessage) : IntelBlockUi<Nothing>
}

/**
 * Map one read into a block state.
 *
 * The split that matters: a transport failure becomes [IntelBlockUi.Failed]
 * (retryable), while a 200 whose body says `available:false` becomes
 * [IntelBlockUi.Unavailable] (absent). The repository keeps the `BtResult` for
 * exactly this reason.
 */
fun <T : Any> intelBlockOf(result: BtResult<T>, available: (T) -> Boolean): IntelBlockUi<T> =
    when (result) {
        is BtResult.Ok -> if (available(result.value)) {
            IntelBlockUi.Ready(result.value)
        } else {
            IntelBlockUi.Unavailable
        }

        is BtResult.Err -> IntelBlockUi.Failed(result.error.asMessage())
    }

/**
 * Which of a calendar entry's two dates the row leads with, and whether that is
 * the ex-date. The wire orders entries by the EARLIER of the two, so leading
 * with anything else would make the list look unsorted.
 */
data class IntelCalendarDate(val iso: String, val isExDate: Boolean)

fun intelCalendarPrimaryDate(entry: DividendCalendarEntryDto): IntelCalendarDate? {
    val ex = intelTimeMs(entry.exDate)
    val pay = intelTimeMs(entry.payDate)
    return when {
        ex != null && (pay == null || ex <= pay) -> IntelCalendarDate(entry.exDate!!, true)
        pay != null -> IntelCalendarDate(entry.payDate!!, false)
        else -> null
    }
}

// ═════════════════════════════════ ViewModel ════════════════════════════════

/** The four blocks, plus the one screen-level question worth asking. */
data class MarketIntelUiState(
    val earnings: IntelBlockUi<EarningsCalendarResponse> = IntelBlockUi.Loading,
    val dividends: IntelBlockUi<DividendCalendarResponse> = IntelBlockUi.Loading,
    val projection: IntelBlockUi<DividendProjectionResponse> = IntelBlockUi.Loading,
    val digest: IntelBlockUi<NewsDigestResponse> = IntelBlockUi.Loading,
) {
    private val blocks: List<IntelBlockUi<*>> get() = listOf(earnings, dividends, projection, digest)

    /** Nothing can be served at all — say it ONCE, not four times over. */
    val allUnavailable: Boolean get() = blocks.all { it is IntelBlockUi.Unavailable }

    val anyLoading: Boolean get() = blocks.any { it is IntelBlockUi.Loading }
}

class MarketIntelViewModel(private val repo: MarketIntelRepository) : ViewModel() {

    private val _state = MutableStateFlow(MarketIntelUiState())
    val state: StateFlow<MarketIntelUiState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        loadAll(showSkeletons = true)
    }

    fun refresh() = loadAll(showSkeletons = false)

    // Retry is PER SECTION. A screen-wide reload would blank three healthy
    // sections back to skeletons — and re-spend three provider round-trips —
    // because a fourth one failed.
    fun retryEarnings() = reloadOne(
        place = { s, b -> s.copy(earnings = b) },
        read = { intelBlockOf(repo.earningsCalendar()) { it.available } },
    )

    fun retryDividends() = reloadOne(
        place = { s, b -> s.copy(dividends = b) },
        read = { intelBlockOf(repo.dividendCalendar()) { it.available } },
    )

    fun retryProjection() = reloadOne(
        place = { s, b -> s.copy(projection = b) },
        read = { intelBlockOf(repo.dividendProjection()) { it.available } },
    )

    fun retryDigest() = reloadOne(
        place = { s, b -> s.copy(digest = b) },
        read = { intelBlockOf(repo.newsDigest()) { it.available } },
    )

    /**
     * All four in parallel — they are independent reads and serialising them
     * would put four provider round-trips end to end on a screen the user is
     * staring at.
     *
     * A pull-to-refresh keeps the current content on screen ([showSkeletons] =
     * false) instead of blanking it back to placeholders.
     */
    private fun loadAll(showSkeletons: Boolean) {
        viewModelScope.launch {
            if (showSkeletons) _state.value = MarketIntelUiState() else _refreshing.value = true
            coroutineScope {
                val earnings = async { intelBlockOf(repo.earningsCalendar()) { it.available } }
                val dividends = async { intelBlockOf(repo.dividendCalendar()) { it.available } }
                val projection = async { intelBlockOf(repo.dividendProjection()) { it.available } }
                val digest = async { intelBlockOf(repo.newsDigest()) { it.available } }
                _state.value = MarketIntelUiState(
                    earnings = earnings.await(),
                    dividends = dividends.await(),
                    projection = projection.await(),
                    digest = digest.await(),
                )
            }
            _refreshing.value = false
        }
    }

    /** Put one section back into Loading, re-read only it, and drop it back in. */
    private fun <T> reloadOne(
        place: (MarketIntelUiState, IntelBlockUi<T>) -> MarketIntelUiState,
        read: suspend () -> IntelBlockUi<T>,
    ) {
        viewModelScope.launch {
            _state.value = place(_state.value, IntelBlockUi.Loading)
            _state.value = place(_state.value, read())
        }
    }
}

// ═══════════════════════════════════ UI ═════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketIntelScreen(onBack: () -> Unit, onOpenAsset: (String) -> Unit) {
    val vm: MarketIntelViewModel = viewModel {
        MarketIntelViewModel(AppGraph.marketIntelRepository)
    }
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    // Monthly reads as "what lands in a normal month"; the yearly figure is one
    // tap away rather than two numbers competing for the same line.
    var yearly by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.bt_intel_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = bt.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // Four absences under a title is not a screen. One honest sentence is.
            if (state.allUnavailable) {
                BtEmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    icon = Icons.Outlined.Summarize,
                    title = stringResource(R.string.bt_intel_unavailable_title),
                    message = stringResource(R.string.bt_intel_unavailable_message),
                )
                return@Box
            }

            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = bt.surface,
                        color = bt.gold,
                    )
                },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "subtitle") {
                        Text(
                            text = stringResource(R.string.bt_intel_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }

                    earningsCalendarSection(state.earnings, locale, onOpenAsset) { vm.retryEarnings() }
                    dividendCalendarSection(state.dividends, locale, onOpenAsset) { vm.retryDividends() }
                    dividendProjectionSection(
                        state = state.projection,
                        locale = locale,
                        yearly = yearly,
                        onYearly = { yearly = it },
                        onOpenAsset = onOpenAsset,
                        onRetry = { vm.retryProjection() },
                    )
                    newsDigestSection(state.digest, onOpenAsset) { vm.retryDigest() }
                }
            }
        }
    }
}

// ── Section scaffolding ─────────────────────────────────────────────────────

/**
 * Emit one section: header, then whatever its state calls for.
 *
 * An [IntelBlockUi.Unavailable] section emits NOTHING — not even its header.
 * That absence is the feature: a heading over "no data" would be the app
 * answering a question the server refused to answer.
 */
private fun <T> LazyListScope.intelSection(
    key: String,
    state: IntelBlockUi<T>,
    header: @Composable () -> Unit,
    onRetry: () -> Unit,
    rows: LazyListScope.(T) -> Unit,
) {
    if (state is IntelBlockUi.Unavailable) return
    item(key = "$key-header") { header() }
    when (state) {
        IntelBlockUi.Loading -> item(key = "$key-loading") { IntelRowsSkeleton() }
        is IntelBlockUi.Failed -> item(key = "$key-error") {
            IntelInlineError(message = state.message, onRetry = onRetry)
        }

        is IntelBlockUi.Ready -> rows(state.value)
        IntelBlockUi.Unavailable -> Unit
    }
}

@Composable
private fun IntelSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = BtTheme.colors.textPrimary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun IntelRowsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(2) { BtSkeleton(Modifier.fillMaxWidth().height(58.dp)) }
    }
}

// ── Earnings calendar ───────────────────────────────────────────────────────

private fun LazyListScope.earningsCalendarSection(
    state: IntelBlockUi<EarningsCalendarResponse>,
    locale: Locale,
    onOpenAsset: (String) -> Unit,
    onRetry: () -> Unit,
) = intelSection(
    key = "earnings",
    state = state,
    header = { IntelSectionHeader(stringResource(R.string.bt_intel_earnings_calendar)) },
    onRetry = onRetry,
) { response ->
    if (response.entries.isEmpty()) {
        item(key = "earnings-empty") {
            IntelEmptyLine(stringResource(R.string.bt_intel_calendar_empty))
        }
        return@intelSection
    }
    items(count = response.entries.size, key = { "earnings-${response.entries[it].assetId}-$it" }) { i ->
        EarningsCalendarRow(response.entries[i], locale, onOpenAsset)
    }
}

@Composable
private fun EarningsCalendarRow(
    entry: EarningsCalendarEntryDto,
    locale: Locale,
    onOpenAsset: (String) -> Unit,
) {
    val bt = BtTheme.colors
    IntelAssetRow(
        symbol = entry.symbol,
        name = entry.name,
        held = entry.held,
        watched = entry.watched,
        onClick = { onOpenAsset(entry.assetId) },
    ) {
        Text(
            text = intelDate(entry.date, locale) ?: stringResource(R.string.bt_intel_date_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textPrimary,
            textAlign = TextAlign.End,
        )
        Text(
            // Estimated vs confirmed is stated on EVERY row: a calendar whose
            // entries are silently half-guesses is worse than no calendar.
            text = stringResource(
                if (entry.estimated) R.string.bt_intel_date_estimated else R.string.bt_intel_date_confirmed,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = bt.textMuted,
            textAlign = TextAlign.End,
        )
        entry.epsEstimate?.takeIf { it.isFinite() }?.let {
            Text(
                text = stringResource(R.string.bt_intel_eps_estimate_value, formatQuantity(it, locale)),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textSecondary,
                textAlign = TextAlign.End,
            )
        }
    }
}

// ── Dividend calendar ───────────────────────────────────────────────────────

private fun LazyListScope.dividendCalendarSection(
    state: IntelBlockUi<DividendCalendarResponse>,
    locale: Locale,
    onOpenAsset: (String) -> Unit,
    onRetry: () -> Unit,
) = intelSection(
    key = "divcal",
    state = state,
    header = { IntelSectionHeader(stringResource(R.string.bt_intel_dividend_calendar)) },
    onRetry = onRetry,
) { response ->
    if (response.entries.isEmpty()) {
        item(key = "divcal-empty") {
            IntelEmptyLine(stringResource(R.string.bt_intel_div_calendar_empty))
        }
        return@intelSection
    }
    items(count = response.entries.size, key = { "divcal-${response.entries[it].assetId}-$it" }) { i ->
        DividendCalendarRow(response.entries[i], locale, onOpenAsset)
    }
}

@Composable
private fun DividendCalendarRow(
    entry: DividendCalendarEntryDto,
    locale: Locale,
    onOpenAsset: (String) -> Unit,
) {
    val bt = BtTheme.colors
    val primary = intelCalendarPrimaryDate(entry)
    IntelAssetRow(
        symbol = entry.symbol,
        name = entry.name,
        // `source` is the server's own held-wins-over-watched decision; the row
        // repeats it rather than second-guessing it.
        held = entry.source == "holding",
        watched = entry.source == "watchlist",
        onClick = { onOpenAsset(entry.assetId) },
    ) {
        Text(
            text = primary?.let { p ->
                val date = intelDate(p.iso, locale) ?: p.iso
                stringResource(
                    if (p.isExDate) R.string.bt_intel_ex_date else R.string.bt_intel_pay_date,
                    date,
                )
            } ?: stringResource(R.string.bt_intel_date_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textPrimary,
            textAlign = TextAlign.End,
        )
        // No currency ⇒ no amount. The provider's bare number could be dollars;
        // printing it with a € would be a fabrication (see intelAmountRenderable).
        if (intelAmountRenderable(entry.amount, entry.currency)) {
            Text(
                text = formatMoney(entry.amount!!, entry.currency!!, locale),
                style = BtTheme.type.numberCaption,
                color = bt.textSecondary,
                textAlign = TextAlign.End,
            )
        }
    }
}

// ── Dividend projection ─────────────────────────────────────────────────────

private fun LazyListScope.dividendProjectionSection(
    state: IntelBlockUi<DividendProjectionResponse>,
    locale: Locale,
    yearly: Boolean,
    onYearly: (Boolean) -> Unit,
    onOpenAsset: (String) -> Unit,
    onRetry: () -> Unit,
) = intelSection(
    key = "projection",
    state = state,
    header = { IntelSectionHeader(stringResource(R.string.bt_intel_dividend_projection)) },
    onRetry = onRetry,
) { response ->
    item(key = "projection-total") {
        ProjectionTotalCard(response, locale, yearly, onYearly)
    }
    if (response.holdings.isEmpty()) {
        item(key = "projection-empty") {
            IntelEmptyLine(stringResource(R.string.bt_intel_projection_empty))
        }
        return@intelSection
    }
    // Server order is descending by annual income — the ranking IS the content.
    items(count = response.holdings.size, key = { "proj-${response.holdings[it].assetId}-$it" }) { i ->
        ProjectionHoldingRow(response.holdings[i], locale, onOpenAsset)
    }
}

@Composable
private fun ProjectionTotalCard(
    response: DividendProjectionResponse,
    locale: Locale,
    yearly: Boolean,
    onYearly: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = formatEur(
                    if (yearly) response.yearlyTotalEur else response.monthlyTotalEur,
                    locale,
                ),
                style = BtTheme.type.moneyMedium,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BtChip(
                    text = stringResource(R.string.bt_intel_per_month),
                    selected = !yearly,
                    onClick = { onYearly(false) },
                )
                BtChip(
                    text = stringResource(R.string.bt_intel_per_year),
                    selected = yearly,
                    onClick = { onYearly(true) },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                // The server is all-or-nothing on FX, so this total is either
                // complete or absent — never a partial figure dressed as one.
                text = stringResource(R.string.bt_intel_projection_note),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
        }
    }
}

@Composable
private fun ProjectionHoldingRow(
    holding: ProjectedDividendHoldingDto,
    locale: Locale,
    onOpenAsset: (String) -> Unit,
) {
    val bt = BtTheme.colors
    val openLabel = stringResource(R.string.bt_intel_open_asset)
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = openLabel) { onOpenAsset(holding.assetId) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = holding.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = holding.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The per-share rate is in the DIVIDEND's currency, not EUR —
                    // only the income column is converted.
                    text = stringResource(
                        R.string.bt_intel_projection_holding_line,
                        formatQuantity(holding.quantity, locale),
                        formatMoney(holding.annualPerShare, holding.currency, locale),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatEur(holding.annualIncomeEur, locale),
                style = BtTheme.type.moneySmall,
                color = bt.textPrimary,
            )
        }
    }
}

// ── News digest ─────────────────────────────────────────────────────────────

private fun LazyListScope.newsDigestSection(
    state: IntelBlockUi<NewsDigestResponse>,
    onOpenAsset: (String) -> Unit,
    onRetry: () -> Unit,
) = intelSection(
    key = "digest",
    state = state,
    header = { IntelSectionHeader(stringResource(R.string.bt_intel_news_digest)) },
    onRetry = onRetry,
) { response ->
    if (response.groups.isEmpty()) {
        item(key = "digest-empty") {
            IntelEmptyLine(stringResource(R.string.bt_intel_news_empty))
        }
        return@intelSection
    }
    // One card per asset rather than one giant card: the digest is the longest
    // block on the screen and this keeps it lazily composed.
    items(count = response.groups.size, key = { "digest-${response.groups[it].assetId}-$it" }) { i ->
        NewsDigestGroupCard(response.groups[i], onOpenAsset)
    }
}

@Composable
private fun NewsDigestGroupCard(group: NewsDigestGroupDto, onOpenAsset: (String) -> Unit) {
    val bt = BtTheme.colors
    val openLabel = stringResource(R.string.bt_intel_open_asset)
    val nowMs = System.currentTimeMillis()
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = openLabel) { onOpenAsset(group.assetId) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = group.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IntelSourceChips(held = group.held, watched = group.watched)
            }
            group.headlines.forEach { IntelHeadlineRow(it, nowMs) }
        }
    }
}

// ── Shared row furniture ────────────────────────────────────────────────────

/**
 * A calendar row: symbol + name + provenance chips on the left, whatever the
 * calling section wants stacked on the right. Tapping it opens the asset.
 */
@Composable
private fun IntelAssetRow(
    symbol: String,
    name: String,
    held: Boolean,
    watched: Boolean,
    onClick: () -> Unit,
    trailing: @Composable ColumnScope.() -> Unit,
) {
    val bt = BtTheme.colors
    val openLabel = stringResource(R.string.bt_intel_open_asset)
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = openLabel, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    IntelSourceChips(held = held, watched = watched)
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, content = trailing)
        }
    }
}

/**
 * "Held" and "Watching" are independent on the wire — an asset can be both, and
 * showing only one would hide half of why the row is on the screen.
 */
@Composable
private fun IntelSourceChips(held: Boolean, watched: Boolean) {
    if (held) {
        BtBadge(text = stringResource(R.string.bt_intel_held), kind = BtBadgeKind.Gold)
    }
    if (watched) {
        if (held) Spacer(Modifier.width(6.dp))
        BtBadge(text = stringResource(R.string.bt_intel_watched), kind = BtBadgeKind.Neutral)
    }
}
