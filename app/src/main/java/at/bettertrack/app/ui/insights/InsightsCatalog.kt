package at.bettertrack.app.ui.insights

import at.bettertrack.app.ui.charts.viz.BtVizFamily
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizScope

/**
 * The Insights **catalog** — which questions BetterTrack can answer from data it
 * already holds, in the order a new user should meet them, and exactly which
 * controls each answer may expose.
 *
 * This file is the executable form of the round-6 study's ranked catalog and
 * per-insight configuration matrix (`DESIGN_NOTES_INSIGHTS.md`). Two of its
 * rules are load-bearing and therefore live here as *data* rather than as an
 * intention scattered across twelve screens:
 *
 *  1. **A control that makes no semantic sense is ABSENT, not disabled.** The
 *     study is explicit: "current allocation has no historical period unless the
 *     server supplies a historical snapshot; Tagesbewegungen cannot become an
 *     arbitrary range; and Steuerübersicht cannot pretend a non-calendar range
 *     is a tax year." A greyed-out control still advertises a capability the
 *     product does not have, so [BtInsightSpec] simply does not list it and the
 *     configurator has nothing to draw. [InsightsCatalogTest] guards this.
 *  2. **The product boundary.** Every insight below reads holdings, cost basis,
 *     market value, server-calculated P/L, portfolio history, cash movements and
 *     tags, budgets, transactions and fees, dividend records, or annual tax
 *     summaries. There is no market-wide universe, so there are no peer
 *     percentiles, no screeners, no benchmarks, no "opportunities" and no
 *     forecasts — and adding one later means adding a data source first, not
 *     adding an enum entry here.
 *
 * The five [defaultOn] insights cover trajectory, composition, immediate
 * movement, cash sustainability and everyday spending. The other seven are
 * opt-in through *Insight hinzufügen*; they are not lesser, they are simply not
 * what an empty account can answer on day one.
 */
enum class BtInsight {
    /** 1 · Wie haben sich Depotwert und Performance im Zeitraum entwickelt? */
    PORTFOLIO_DEVELOPMENT,

    /** 2 · Wie verteilt sich der aktuelle Depotwert auf Anlageklassen? */
    ASSET_CLASSES,

    /** 3 · Welche Positionen haben den heutigen Depotwert am stärksten bewegt? */
    DAILY_MOVERS,

    /** 4 · Wie entwickeln sich Zufluss, Abfluss und Netto über die Monate? */
    MONTHLY_CASHFLOW,

    /** 5 · Wofür wurde Geld ausgegeben, welche Budgets sind nahe am Limit? */
    BUDGETS_SPENDING,

    /** 6 · Wie stark ist das Depot auf einzelne Positionen konzentriert? */
    HOLDING_CONCENTRATION,

    /** 7 · Welche offenen Positionen tragen zum unrealisierten G/V bei? */
    UNREALIZED_PL,

    /** 8 · Wie stehen Marktwert und erfasste Kostenbasis zueinander? */
    VALUE_VS_BASIS,

    /** 9 · Was wurde im Zeitraum realisiert, welche Gebühren fielen an? */
    REALIZED_FEES,

    /** 10 · Wie viel wurde ausgeschüttet und von welchen Positionen? */
    DIVIDENDS,

    /** 11 · Welche steuerrelevanten Summen liegen für das Kalenderjahr vor? */
    TAX_SUMMARY,

    /** 12 · Wie viel Cash ist verfügbar und auf welche Quellen verteilt? */
    LIQUID_FUNDS,
    ;

    val spec: BtInsightSpec get() = insightSpec(this)
}

/** The three sections the catalog and the report checklist group cards under. */
enum class BtInsightGroup { PORTFOLIO, CASHFLOW, TAXES_INCOME }

/**
 * How an insight resolves *time* — the single most important honesty rail.
 *
 * A card does not get to choose this; it is a property of the question. Asking
 * "what is my allocation over the last six months" has no answer the server can
 * give, so [SNAPSHOT] insights expose an as-of date and never a range.
 */
enum class BtInsightTiming {
    /** A flow or history over the whole period. 1 M / 6 M / 1 J / MAX / custom. */
    PERIOD,

    /** Resolves to the period's END date and says `Stand {date}`. No range control. */
    SNAPSHOT,

    /** The last available trading session at or before the end date. */
    SESSION,

    /** Monthly (or quarterly) buckets across the period. */
    MONTHS,

    /** The budget month containing the period's end date. */
    BUDGET_MONTH,

    /** A calendar year, and nothing else. A tax year is not a date range. */
    CALENDAR_YEAR,
}

/** What an insight may legitimately compare itself against. */
enum class BtInsightCompare {
    /** No honest comparison exists. Offered nowhere. */
    NONE,

    /** The previous equal-length period. */
    PREVIOUS_PERIOD,

    /** A previous snapshot — offered ONLY when the server actually supplies one. */
    PREVIOUS_SNAPSHOT,

    /** The prior calendar/tax year. */
    PREVIOUS_YEAR,
}

/** Row ordering an insight may offer. Empty list on the spec means "no control". */
enum class BtInsightSort { AMOUNT, PERCENT, NAME, VALUE, BASIS, DELTA, SIGNED }

/** How rows may be bucketed. Empty list on the spec means "no control". */
enum class BtInsightGrouping { HOLDING, MONTH, QUARTER, PAYER, CATEGORY, SOURCE, TAG }

/** Which series a time-series insight prints. */
enum class BtInsightSeries { VALUE, PERFORMANCE, BOTH }

/**
 * One insight's fixed capabilities: its rank, its section, how it resolves time,
 * and precisely which knobs the configurator may draw for it.
 *
 * Everything here is a *capability*, never a user choice — user choices live in
 * [BtInsightConfig]. A knob absent from this spec cannot be stored, cannot be
 * rendered and cannot be exported.
 */
data class BtInsightSpec(
    val insight: BtInsight,
    /** 1-based rank from the study's ranked catalog. Drives default page order. */
    val rank: Int,
    /** True for the five insights a new user sees without asking. */
    val defaultOn: Boolean,
    val group: BtInsightGroup,
    val timing: BtInsightTiming,
    /**
     * The `Darstellung` family this insight borrows its saved default from, or
     * `null` when the insight owns a form vocabulary the family system does not
     * model (a time series; the paired value/basis track).
     */
    val family: BtVizFamily?,
    /** True when values carry a direction and part-to-whole geometry is invalid. */
    val signed: Boolean,
    /**
     * The forms this insight may take, in picker order. Empty means the form is
     * fixed by the question and the configurator draws no `Darstellung` row.
     */
    val forms: List<BtVizForm>,
    /** Offered `Umfang` steps. Empty means the insight has a fixed row count. */
    val topN: List<BtVizScope>,
    /** True when Beträge / Anteile / Beides is a meaningful choice here. */
    val labels: Boolean,
    val compare: BtInsightCompare,
    /** True when a cash slice exists that could honestly be included or excluded. */
    val cashToggle: Boolean,
    val sorts: List<BtInsightSort>,
    val groupings: List<BtInsightGrouping>,
    /** True for the one insight that prints Wert / Performance / Beide. */
    val seriesChoice: Boolean = false,
    /** True when a Fokus (pre-selected mark) is meaningful. */
    val focus: Boolean = false,
    /** True when the card may hide or show attached budget tracks. */
    val budgetsToggle: Boolean = false,
    /** True when the card may hide or show the separate fee total. */
    val feesToggle: Boolean = false,
    /** True when transfers may be folded back into the flow. */
    val transfersToggle: Boolean = false,
)

/** The five insights a new user meets, in rank order. */
val BT_INSIGHTS_DEFAULT: List<BtInsight> =
    BtInsight.entries.filter { it.spec.defaultOn }.sortedBy { it.spec.rank }

/** All twelve, in the study's rank order. */
val BT_INSIGHTS_RANKED: List<BtInsight> = BtInsight.entries.sortedBy { it.spec.rank }

/**
 * The per-insight capability matrix, transcribed from the study.
 *
 * Read the `forms` lists against `DESIGN_NOTES_INSIGHTS.md` § "Per-insight
 * configuration matrix": part-to-whole geometry never appears for a signed
 * insight, because a share of a whole cannot express a direction; and
 * [BtInsight.VALUE_VS_BASIS] gets no 100-% form at all, because two independent
 * quantities are not parts of one whole.
 */
@Suppress("LongMethod")
fun insightSpec(insight: BtInsight): BtInsightSpec = when (insight) {
    BtInsight.PORTFOLIO_DEVELOPMENT -> BtInsightSpec(
        insight = insight,
        rank = 1,
        defaultOn = true,
        group = BtInsightGroup.PORTFOLIO,
        timing = BtInsightTiming.PERIOD,
        // A time series has no `Darstellung` family: the study offers "existing
        // time series only", so there is no shape to choose between.
        family = null,
        signed = false,
        forms = emptyList(),
        topN = emptyList(),
        labels = false,
        compare = BtInsightCompare.PREVIOUS_PERIOD,
        cashToggle = false,
        sorts = emptyList(),
        groupings = emptyList(),
        seriesChoice = true,
    )

    BtInsight.ASSET_CLASSES -> BtInsightSpec(
        insight = insight,
        rank = 2,
        defaultOn = true,
        group = BtInsightGroup.PORTFOLIO,
        // Allocation is a fact about a moment. The report's end date is that
        // moment; there is no six-month allocation to draw.
        timing = BtInsightTiming.SNAPSHOT,
        family = BtVizFamily.ALLOCATION_CLASS,
        signed = false,
        forms = listOf(
            BtVizForm.AUTO,
            BtVizForm.TREEMAP,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.RING,
            BtVizForm.WAFFLE,
            BtVizForm.BUBBLES,
        ),
        // Six fixed classes need no Top-N; the study says so explicitly.
        topN = emptyList(),
        labels = true,
        compare = BtInsightCompare.PREVIOUS_SNAPSHOT,
        cashToggle = true,
        sorts = emptyList(),
        groupings = emptyList(),
        focus = true,
    )

    BtInsight.DAILY_MOVERS -> BtInsightSpec(
        insight = insight,
        rank = 3,
        defaultOn = true,
        group = BtInsightGroup.PORTFOLIO,
        // "Tagesbewegungen cannot become an arbitrary range."
        timing = BtInsightTiming.SESSION,
        family = BtVizFamily.MOVERS,
        signed = true,
        forms = listOf(BtVizForm.AUTO, BtVizForm.DOT_PLOT, BtVizForm.RANKED_BARS),
        topN = listOf(BtVizScope.AUTO, BtVizScope.TOP_5, BtVizScope.TOP_8, BtVizScope.ALL),
        labels = true,
        // One session against another session is not a comparison a reader can
        // act on, and the study offers none.
        compare = BtInsightCompare.NONE,
        cashToggle = false,
        sorts = listOf(BtInsightSort.AMOUNT, BtInsightSort.PERCENT),
        groupings = emptyList(),
        focus = true,
    )

    BtInsight.MONTHLY_CASHFLOW -> BtInsightSpec(
        insight = insight,
        rank = 4,
        defaultOn = true,
        group = BtInsightGroup.CASHFLOW,
        timing = BtInsightTiming.MONTHS,
        family = BtVizFamily.MOVERS,
        signed = true,
        forms = listOf(BtVizForm.AUTO, BtVizForm.DOT_PLOT, BtVizForm.RANKED_BARS),
        topN = emptyList(),
        labels = true,
        compare = BtInsightCompare.PREVIOUS_PERIOD,
        cashToggle = false,
        sorts = emptyList(),
        groupings = listOf(BtInsightGrouping.MONTH, BtInsightGrouping.QUARTER),
        transfersToggle = true,
    )

    BtInsight.BUDGETS_SPENDING -> BtInsightSpec(
        insight = insight,
        rank = 5,
        defaultOn = true,
        group = BtInsightGroup.CASHFLOW,
        timing = BtInsightTiming.BUDGET_MONTH,
        family = BtVizFamily.SPENDING,
        signed = false,
        forms = listOf(
            BtVizForm.AUTO,
            BtVizForm.RANKED_BARS,
            BtVizForm.TREEMAP,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.WAFFLE,
            BtVizForm.BUBBLES,
        ),
        topN = listOf(
            BtVizScope.AUTO,
            BtVizScope.TOP_3,
            BtVizScope.TOP_5,
            BtVizScope.TOP_8,
            BtVizScope.ALL,
        ),
        labels = true,
        compare = BtInsightCompare.PREVIOUS_PERIOD,
        cashToggle = false,
        sorts = emptyList(),
        groupings = listOf(BtInsightGrouping.TAG),
        focus = true,
        budgetsToggle = true,
        transfersToggle = true,
    )

    BtInsight.HOLDING_CONCENTRATION -> BtInsightSpec(
        insight = insight,
        rank = 6,
        defaultOn = false,
        group = BtInsightGroup.PORTFOLIO,
        timing = BtInsightTiming.SNAPSHOT,
        family = BtVizFamily.ALLOCATION_POSITION,
        signed = false,
        forms = listOf(
            BtVizForm.AUTO,
            BtVizForm.RANKED_BARS,
            BtVizForm.TREEMAP,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.BUBBLES,
        ),
        topN = listOf(
            BtVizScope.AUTO,
            BtVizScope.TOP_3,
            BtVizScope.TOP_5,
            BtVizScope.TOP_8,
            BtVizScope.ALL,
        ),
        labels = true,
        compare = BtInsightCompare.PREVIOUS_SNAPSHOT,
        cashToggle = true,
        sorts = emptyList(),
        groupings = emptyList(),
        focus = true,
    )

    BtInsight.UNREALIZED_PL -> BtInsightSpec(
        insight = insight,
        rank = 7,
        defaultOn = false,
        group = BtInsightGroup.PORTFOLIO,
        timing = BtInsightTiming.SNAPSHOT,
        family = BtVizFamily.MOVERS,
        signed = true,
        // "Part-to-whole forms are invalid" — a loss has no share of a total.
        forms = listOf(BtVizForm.AUTO, BtVizForm.DOT_PLOT, BtVizForm.RANKED_BARS),
        topN = listOf(BtVizScope.AUTO, BtVizScope.TOP_5, BtVizScope.TOP_8, BtVizScope.ALL),
        labels = true,
        compare = BtInsightCompare.NONE,
        cashToggle = false,
        sorts = listOf(BtInsightSort.AMOUNT, BtInsightSort.SIGNED, BtInsightSort.NAME),
        groupings = emptyList(),
        focus = true,
    )

    BtInsight.VALUE_VS_BASIS -> BtInsightSpec(
        insight = insight,
        rank = 8,
        defaultOn = false,
        group = BtInsightGroup.PORTFOLIO,
        timing = BtInsightTiming.SNAPSHOT,
        // Paired tracks are this insight's own form; no family owns them.
        family = null,
        signed = false,
        // "A 100-% form would falsely imply parts of one whole" — so the paired
        // ranked bar is the ONLY form, and the configurator draws no picker.
        forms = emptyList(),
        topN = listOf(BtVizScope.AUTO, BtVizScope.TOP_5, BtVizScope.TOP_8, BtVizScope.ALL),
        labels = true,
        compare = BtInsightCompare.NONE,
        cashToggle = false,
        sorts = listOf(BtInsightSort.VALUE, BtInsightSort.BASIS, BtInsightSort.DELTA),
        groupings = listOf(BtInsightGrouping.HOLDING),
        focus = true,
    )

    BtInsight.REALIZED_FEES -> BtInsightSpec(
        insight = insight,
        rank = 9,
        defaultOn = false,
        group = BtInsightGroup.TAXES_INCOME,
        timing = BtInsightTiming.PERIOD,
        family = BtVizFamily.MOVERS,
        signed = true,
        forms = listOf(BtVizForm.AUTO, BtVizForm.DOT_PLOT, BtVizForm.RANKED_BARS),
        topN = listOf(BtVizScope.AUTO, BtVizScope.TOP_5, BtVizScope.TOP_8, BtVizScope.ALL),
        labels = true,
        compare = BtInsightCompare.PREVIOUS_PERIOD,
        cashToggle = false,
        sorts = emptyList(),
        groupings = listOf(BtInsightGrouping.HOLDING, BtInsightGrouping.MONTH),
        focus = true,
        feesToggle = true,
    )

    BtInsight.DIVIDENDS -> BtInsightSpec(
        insight = insight,
        rank = 10,
        defaultOn = false,
        group = BtInsightGroup.TAXES_INCOME,
        timing = BtInsightTiming.PERIOD,
        family = BtVizFamily.ALLOCATION_POSITION,
        signed = false,
        forms = listOf(
            BtVizForm.AUTO,
            BtVizForm.RANKED_BARS,
            BtVizForm.TREEMAP,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.WAFFLE,
            BtVizForm.BUBBLES,
        ),
        topN = listOf(
            BtVizScope.AUTO,
            BtVizScope.TOP_3,
            BtVizScope.TOP_5,
            BtVizScope.TOP_8,
            BtVizScope.ALL,
        ),
        labels = true,
        compare = BtInsightCompare.PREVIOUS_PERIOD,
        cashToggle = false,
        sorts = emptyList(),
        groupings = listOf(BtInsightGrouping.PAYER, BtInsightGrouping.MONTH),
        focus = true,
    )

    BtInsight.TAX_SUMMARY -> BtInsightSpec(
        insight = insight,
        rank = 11,
        defaultOn = false,
        group = BtInsightGroup.TAXES_INCOME,
        // "Steuerübersicht cannot pretend a non-calendar range is a tax year."
        timing = BtInsightTiming.CALENDAR_YEAR,
        family = BtVizFamily.MOVERS,
        signed = true,
        forms = listOf(BtVizForm.AUTO, BtVizForm.DOT_PLOT, BtVizForm.RANKED_BARS),
        topN = emptyList(),
        // Tax components are euro facts; printing them as shares of each other
        // would invent a denominator the tax authority never used.
        labels = false,
        compare = BtInsightCompare.PREVIOUS_YEAR,
        cashToggle = false,
        sorts = emptyList(),
        groupings = listOf(BtInsightGrouping.CATEGORY),
    )

    BtInsight.LIQUID_FUNDS -> BtInsightSpec(
        insight = insight,
        rank = 12,
        defaultOn = false,
        group = BtInsightGroup.CASHFLOW,
        timing = BtInsightTiming.SNAPSHOT,
        family = BtVizFamily.ALLOCATION_CLASS,
        signed = false,
        forms = listOf(
            BtVizForm.AUTO,
            BtVizForm.RING,
            BtVizForm.RANKED_BARS,
            BtVizForm.STACKED_BAR,
            BtVizForm.MOSAIC,
        ),
        topN = listOf(BtVizScope.AUTO, BtVizScope.TOP_5, BtVizScope.TOP_8, BtVizScope.ALL),
        labels = true,
        compare = BtInsightCompare.PREVIOUS_SNAPSHOT,
        // Broker cash is a real, separable source — including it is a genuine
        // question, not a decorative switch.
        cashToggle = true,
        sorts = emptyList(),
        groupings = listOf(BtInsightGrouping.SOURCE),
        focus = true,
    )
}

/**
 * True when this insight can be rendered for [year] as a calendar-year fact.
 *
 * Only [BtInsightTiming.CALENDAR_YEAR] insights care; everything else answers
 * "yes" because a range is a range. The report builder uses this to *uncheck*
 * incompatible cards out loud rather than exporting a page that quietly claims a
 * tax year it never had.
 */
fun insightAcceptsCalendarYear(insight: BtInsight, isCalendarYear: Boolean): Boolean =
    insight.spec.timing != BtInsightTiming.CALENDAR_YEAR || isCalendarYear
