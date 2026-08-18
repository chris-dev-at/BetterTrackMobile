package at.bettertrack.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bettertrack.app.data.cash.CashClassificationRepository
import at.bettertrack.app.data.cash.decodeTagIds
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.CashTagEntity
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.prefs.InsightsPrefs
import at.bettertrack.app.data.prefs.VizPrefs
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.TaxRepository
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.vizConfigDecode
import at.bettertrack.app.ui.portfolio.visibleHoldings
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State for the Insights Studio.
 *
 * ## Why one view model rather than one per card
 *
 * The twelve insights read seven overlapping sources — portfolios, holdings,
 * cash sources, cash movements, tags, budgets, tax years. Giving each card its
 * own subscription would mean the page opens eight copies of the holdings flow
 * and issues the tax call three times. One assembled [BtInsightSource] is read
 * by every visible card, and a card that is hidden costs nothing because its
 * builder is never called.
 *
 * ## What is fetched eagerly and what is not
 *
 * Cached rows (portfolios, holdings, cash, tags) are Room flows and cost nothing
 * to observe, so they are always live. Budgets and tax years are network calls,
 * so they are fetched **only when a card that needs them is visible** — opening
 * the page with the default five must not issue a tax request the user did not
 * ask for. [refreshDerived] is what decides, and it re-runs when the page layout
 * or the frame changes.
 *
 * ## Scope
 *
 * The page opens on the portfolio it was entered from and can widen to every
 * portfolio. It never silently retargets: the seed id is remembered, so
 * returning from `Alle Depots` to a single portfolio lands on the one the user
 * came in through rather than on whatever is currently selected elsewhere.
 */
class InsightsStudioViewModel(
    private val repo: PortfolioRepository,
    private val cashRepo: CashClassificationRepository,
    private val taxRepo: TaxRepository,
    private val prefs: InsightsPrefs,
    private val vizPrefs: VizPrefs,
    private val seedPortfolioId: String,
) : ViewModel() {

    // ── Frame: which portfolios, which period ───────────────────────────────

    private val _scopeIds = MutableStateFlow(setOf(seedPortfolioId))

    /** The portfolios in scope. Empty means every portfolio the account has. */
    val scopeIds: StateFlow<Set<String>> = _scopeIds.asStateFlow()

    private val _framePeriod = MutableStateFlow(BtInsightPeriod.ONE_YEAR)

    /** The page-level period. Cards may override it; the report replaces it. */
    val framePeriod: StateFlow<BtInsightPeriod> = _framePeriod.asStateFlow()

    val allPortfolios: StateFlow<List<PortfolioEntity>> = repo.portfolios
        .map { all -> all.filter { it.archivedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The portfolios the frame actually resolves to. */
    val scopedPortfolios: StateFlow<List<PortfolioEntity>> =
        combine(allPortfolios, _scopeIds) { all, ids ->
            if (ids.isEmpty()) all else all.filter { it.id in ids }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Layout and per-card configuration ───────────────────────────────────

    val page: StateFlow<BtInsightsPage> = prefs.page
        .map { insightsPageDecode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtInsightsPage.DEFAULT)

    /** Saved card overrides. A card absent from the map inherits its family. */
    val cards: StateFlow<Map<BtInsight, BtInsightConfig>> = prefs.cards
        .map { raw ->
            raw.mapNotNull { (key, encoded) ->
                BtInsight.entries.firstOrNull { it.name == key }?.let { it to insightConfigDecode(encoded) }
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The app-wide `Darstellung` defaults every card inherits from. */
    val familyConfigs: StateFlow<Map<String, BtVizConfig>> = vizPrefs.configs
        .map { raw -> raw.mapValues { vizConfigDecode(it.value) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── Cached rows ─────────────────────────────────────────────────────────

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val holdings: StateFlow<List<HoldingEntity>> = scopedPortfolios
        .map { it.map(PortfolioEntity::id) }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.map { repo.holdings(it) }) { lists ->
                    visibleHoldings(lists.toList().flatten())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val cashSources: StateFlow<List<CashSourceEntity>> = scopedPortfolios
        .map { it.map(PortfolioEntity::id) }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.map { repo.cashSources(it) }) { lists -> lists.toList().flatten() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val cashMovements: StateFlow<List<CashMovementEntity>> = scopedPortfolios
        .map { it.map(PortfolioEntity::id) }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.map { repo.cashMovements(it) }) { lists -> lists.toList().flatten() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val tags: StateFlow<List<CashTagEntity>> = cashRepo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Fetched-on-demand rows ──────────────────────────────────────────────

    private val _budgets = MutableStateFlow<List<BtInsightBudget>>(emptyList())
    private val _taxYears = MutableStateFlow<List<BtInsightTaxYear>>(emptyList())
    private val _taxPositions = MutableStateFlow<List<BtInsightTaxPosition>>(emptyList())
    private val _valueSeries = MutableStateFlow<Map<String, List<BtInsightPoint>>>(emptyMap())
    private val _performanceSeries = MutableStateFlow<Map<String, List<BtInsightPoint>>>(emptyMap())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** The moment the data on screen was fetched, for `Datenstand`. */
    private val _dataAsOfMs = MutableStateFlow(System.currentTimeMillis())
    val dataAsOfMs: StateFlow<Long> = _dataAsOfMs.asStateFlow()

    // ── The assembled source every card reads ───────────────────────────────

    /**
     * Assembled from the flows above.
     *
     * Two `combine` calls rather than one: the operator is arity-limited, and
     * nesting keeps each group's meaning visible instead of collapsing eleven
     * unrelated sources into one anonymous array.
     */
    private val cached = combine(
        scopedPortfolios,
        holdings,
        cashSources,
        cashMovements,
        tags,
    ) { portfolios, held, sources, movements, tagRows -> CachedRows(portfolios, held, sources, movements, tagRows) }

    private val fetched = combine(
        _budgets,
        _taxYears,
        _taxPositions,
        _valueSeries,
        _performanceSeries,
    ) { budgets, years, positions, values, performance ->
        FetchedRows(budgets, years, positions, values, performance)
    }

    val source: StateFlow<BtInsightSource> = combine(cached, fetched) { rows, extra ->
        BtInsightSource(
            portfolios = rows.portfolios,
            holdings = rows.holdings,
            cashSources = rows.cashSources,
            cashMovements = rows.movements,
            tagNames = rows.tags.associate { it.id to it.name },
            tagColors = rows.tags.mapNotNull { tag ->
                parseTagColor(tag.color)?.let { tag.id to it }
            }.toMap(),
            movementTags = rows.movements.associate { it.id to decodeTagIds(it.tagIds) },
            budgets = extra.budgets,
            valueSeries = extra.valueSeries,
            performanceSeries = extra.performanceSeries,
            taxYear = extra.taxYears,
            taxPositions = extra.taxPositions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtInsightSource(emptyList(), emptyList(), emptyList(), emptyList()))

    private data class CachedRows(
        val portfolios: List<PortfolioEntity>,
        val holdings: List<HoldingEntity>,
        val cashSources: List<CashSourceEntity>,
        val movements: List<CashMovementEntity>,
        val tags: List<CashTagEntity>,
    )

    private data class FetchedRows(
        val budgets: List<BtInsightBudget>,
        val taxYears: List<BtInsightTaxYear>,
        val taxPositions: List<BtInsightTaxPosition>,
        val valueSeries: Map<String, List<BtInsightPoint>>,
        val performanceSeries: Map<String, List<BtInsightPoint>>,
    )

    init {
        viewModelScope.launch {
            repo.refreshPortfolios()
            refreshDerived()
        }
    }

    // ── Frame mutation ──────────────────────────────────────────────────────

    fun setScope(ids: Set<String>) {
        _scopeIds.value = ids
        viewModelScope.launch { refreshDerived() }
    }

    fun setFramePeriod(period: BtInsightPeriod) {
        _framePeriod.value = period
        viewModelScope.launch { refreshDerived() }
    }

    // ── Layout mutation ─────────────────────────────────────────────────────

    fun showInsight(insight: BtInsight) = writePage(insightsPageShow(page.value, insight))

    fun hideInsight(insight: BtInsight) = writePage(insightsPageHide(page.value, insight))

    fun moveInsight(insight: BtInsight, delta: Int) =
        writePage(insightsPageMove(page.value, insight, delta))

    fun reorder(from: Int, to: Int) = writePage(insightsPageReorder(page.value, from, to))

    /**
     * `Standardansicht wiederherstellen`.
     *
     * Writes the page only. Every saved card override survives, which is exactly
     * what the confirmation dialog promises the user.
     */
    fun restoreDefaultPage() {
        prefs.setPage(null)
        viewModelScope.launch { refreshDerived() }
    }

    private fun writePage(next: BtInsightsPage) {
        prefs.setPage(insightsPageEncode(next))
        viewModelScope.launch { refreshDerived() }
    }

    // ── Card configuration ──────────────────────────────────────────────────

    /**
     * Save one card's override.
     *
     * Note what is absent: any call to [VizPrefs.setConfig]. Configuring an
     * insight card must never rewrite the app-wide family default, and the only
     * way to guarantee that is for this class never to hold a writable handle on
     * one — [vizPrefs] is read for defaults and nothing else.
     */
    fun setCardConfig(insight: BtInsight, config: BtInsightConfig) {
        prefs.setCard(insight.name, insightConfigEncode(config))
        viewModelScope.launch { refreshDerived() }
    }

    fun configFor(insight: BtInsight): BtInsightConfig =
        cards.value[insight] ?: BtInsightConfig.PRISTINE

    /** The family default a card inherits, or a pristine config when none was saved. */
    fun familyFor(insight: BtInsight): BtVizConfig {
        val family = insight.spec.family ?: return BtVizConfig()
        return familyConfigs.value[family.name] ?: BtVizConfig()
    }

    /**
     * The window a card resolves against: its own period, else the page frame's,
     * collapsed by the insight's timing.
     *
     * The collapse is not cosmetic — a stichtag card handed a twelve-month frame
     * would otherwise print that range as its own subject line, which is a claim
     * the data cannot support. [insightResolveWindow] owns that rule so the card,
     * the configurator and the report all apply it identically.
     */
    fun windowFor(insight: BtInsight, today: LocalDate = LocalDate.now()): BtInsightWindow {
        val period = configFor(insight).period ?: takeFramePeriodFor(insight, today)
        return insightResolveWindow(insight, period, today)
    }

    /**
     * The frame period, coerced to something this insight can honestly answer.
     *
     * A tax card in a "6 Monate" frame does not get a six-month tax year; it
     * falls back to the calendar year the frame ends in, and the report builder
     * separately refuses to include it unless the frame IS a calendar year.
     */
    private fun takeFramePeriodFor(insight: BtInsight, today: LocalDate): BtInsightPeriod {
        val frame = _framePeriod.value
        return when (insight.spec.timing) {
            BtInsightTiming.CALENDAR_YEAR -> BtInsightPeriod(
                BtInsightPeriodKind.CALENDAR_YEAR,
                year = if (frame.kind == BtInsightPeriodKind.CALENDAR_YEAR && frame.year > 0) {
                    frame.year
                } else {
                    today.year
                },
            )
            else -> frame
        }
    }

    // ── Fetching ────────────────────────────────────────────────────────────

    fun refresh() {
        viewModelScope.launch {
            val ids = scopedPortfolios.value.map { it.id }
            coroutineScope {
                ids.map { async { repo.refreshPortfolioDetail(it) } }.awaitAll()
                ids.map { async { repo.refreshCash(it) } }.awaitAll()
            }
            refreshDerived()
            _dataAsOfMs.value = System.currentTimeMillis()
        }
    }

    /**
     * Fetch only what the currently visible cards actually need.
     *
     * History, budgets and tax years are three network calls with three
     * different costs; issuing all of them on every page open would make the
     * default five insights pay for the seven that are not on screen.
     */
    private suspend fun refreshDerived() {
        val visible = page.value.visible
        val ids = scopedPortfolios.value.map { it.id }
        if (ids.isEmpty()) return
        _loading.value = true
        try {
            coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
                if (BtInsight.PORTFOLIO_DEVELOPMENT in visible) {
                    jobs += async { loadHistory(ids) }
                }
                if (BtInsight.BUDGETS_SPENDING in visible) {
                    jobs += async { loadBudgets(ids) }
                }
                if (visible.any { it in TAX_BACKED }) {
                    jobs += async { loadTax(ids) }
                }
                jobs.awaitAll()
            }
        } finally {
            _loading.value = false
        }
    }

    private suspend fun loadHistory(ids: List<String>) {
        val range = historyRangeFor(configFor(BtInsight.PORTFOLIO_DEVELOPMENT).period ?: _framePeriod.value)
        coroutineScope {
            ids.map { id -> async { repo.refreshHistory(id, range) } }.awaitAll()
        }
        val values = mutableMapOf<String, List<BtInsightPoint>>()
        val perf = mutableMapOf<String, List<BtInsightPoint>>()
        ids.forEach { id ->
            val history = repo.history(id, range).first() ?: return@forEach
            values[id] = history.points.map { BtInsightPoint(it.epochDay, it.valueEur) }
            perf[id] = history.performance.map { BtInsightPoint(it.epochDay, it.pct) }
        }
        _valueSeries.value = values
        _performanceSeries.value = perf
    }

    private suspend fun loadBudgets(ids: List<String>) {
        val month = YearMonth.from(LocalDate.ofEpochDay(windowFor(BtInsight.BUDGETS_SPENDING).asOfEpochDay))
        val rows = mutableListOf<BtInsightBudget>()
        ids.forEach { id ->
            when (val result = cashRepo.budgets(id, month.toString())) {
                is BtResult.Ok -> result.value.budgets.forEach { dto ->
                    rows += BtInsightBudget(
                        tagId = dto.tagId,
                        tagName = dto.tagName,
                        limitEur = dto.amount,
                        spentEur = dto.spent,
                        colorArgb = parseTagColor(dto.tagColor),
                    )
                }
                // Offline or refused: the card renders from cached spending and
                // simply shows no budget tracks. A failed budget call must not
                // blank an insight that can still answer most of its question.
                is BtResult.Err -> Unit
            }
        }
        _budgets.value = rows
    }

    private suspend fun loadTax(ids: List<String>) {
        val year = windowFor(BtInsight.TAX_SUMMARY).year
        val years = mutableListOf<BtInsightTaxYear>()
        val positions = mutableListOf<BtInsightTaxPosition>()
        ids.forEach { id ->
            when (val summaries = taxRepo.taxYears(id)) {
                is BtResult.Ok -> summaries.value.filter { it.year == year }.forEach { summary ->
                    years += BtInsightTaxYear(
                        portfolioId = id,
                        year = summary.year,
                        realizedPnlEur = summary.realizedPnlEur,
                        dividendsGrossEur = summary.dividendsGrossEur,
                        taxWithheldEur = summary.taxWithheldEur,
                        taxRefundedEur = summary.taxRefundedEur,
                        taxNetEur = summary.taxNetEur,
                    )
                }
                is BtResult.Err -> Unit
            }
            when (val report = taxRepo.taxYearReport(id, year)) {
                is BtResult.Ok -> report.value.positions.forEach { position ->
                    positions += BtInsightTaxPosition(
                        portfolioId = id,
                        symbol = position.symbol,
                        realizedPnlEur = position.realizedPnlEur,
                        dividendsGrossEur = position.dividendsGrossEur,
                        taxEur = position.taxEur,
                        sells = position.sells.map {
                            BtInsightTaxEvent(isoEpochDay(it.executedAt), it.realizedPnlEur)
                        },
                        dividends = position.dividends.map {
                            BtInsightTaxEvent(isoEpochDay(it.executedAt), it.grossAmountEur)
                        },
                    )
                }
                is BtResult.Err -> Unit
            }
        }
        _taxYears.value = years
        _taxPositions.value = positions
    }

    private companion object {
        /** The three insights whose numbers come from the tax-year endpoints. */
        val TAX_BACKED = setOf(
            BtInsight.TAX_SUMMARY,
            BtInsight.DIVIDENDS,
            BtInsight.REALIZED_FEES,
        )
    }
}

/**
 * Map a card period onto one of the FOUR ranges the server actually serves.
 *
 * `1D` and `1W` exist in the enum but not in this feature's vocabulary, and a
 * custom span has no server range at all — it takes `MAX` and is windowed on the
 * client, which is a display filter over a server series rather than a new
 * calculation.
 */
fun historyRangeFor(period: BtInsightPeriod): HistoryRange = when (period.kind) {
    BtInsightPeriodKind.ONE_MONTH -> HistoryRange.M1
    BtInsightPeriodKind.SIX_MONTHS -> HistoryRange.M6
    BtInsightPeriodKind.ONE_YEAR -> HistoryRange.Y1
    BtInsightPeriodKind.MAX, BtInsightPeriodKind.CUSTOM, BtInsightPeriodKind.CALENDAR_YEAR ->
        HistoryRange.MAX
}

/** `#RRGGBB` → ARGB int, or null when the server sent nothing usable. */
internal fun parseTagColor(raw: String?): Int? {
    val hex = raw?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    return hex.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
}

/** ISO timestamp → epoch day, tolerant of the several shapes the API returns. */
internal fun isoEpochDay(iso: String): Long = runCatching {
    LocalDate.parse(iso.take(10)).toEpochDay()
}.getOrDefault(0L)
