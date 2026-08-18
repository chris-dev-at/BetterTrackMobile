package at.bettertrack.app.ui.insights

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import at.bettertrack.app.ui.charts.viz.VizDatum

/**
 * The **one** presentation-ready value object an insight produces, and the three
 * renderers that consume it: the in-app card, the A4 PDF section, and the shared
 * PNG.
 *
 * ## Why numbers, not strings
 *
 * An earlier shape carried pre-formatted text, copying the shipped cash export.
 * That is wrong here, for two reasons the study forces:
 *
 *  1. **The PDF must print real amounts while the screen is masked.** Discreet
 *     mode masks inside the formatter, so a snapshot of strings would freeze
 *     "•••• €" into a file the user explicitly asked for. Carrying `Double`s and
 *     letting each renderer pick its formatter keeps the shipped ledger ruling
 *     intact — screen uses the masking formatter, export uses
 *     `btFormatMoneyExport`.
 *  2. **The image's privacy transform has to REMOVE amounts, not blank them.**
 *     [insightHideAmounts] can only do that structurally if it can tell a euro
 *     value from a percentage, which a formatted string no longer says.
 *
 * So: this file carries typed numbers and string resource ids. Localisation and
 * money formatting happen in the renderer, once per surface.
 *
 * ## What it may contain
 *
 * Only values the server computed. Every `Double` below arrived from an API
 * response or a cached row of one; nothing in this package multiplies a price by
 * a quantity or nets two amounts into a third. The one arithmetic this feature
 * performs is *aggregating server-computed values into the total whose parts are
 * being drawn* — the same operation the shipped allocation card already does,
 * and the reason [total] is a field rather than a claim.
 */
data class BtInsightSnapshot(
    val insight: BtInsight,
    /** The resolved as-of date (`Stand {date}`), epoch day. */
    val asOfEpochDay: Long,
    /** Inclusive period bounds, epoch days. Equal for a stichtag insight. */
    val fromEpochDay: Long,
    val toEpochDay: Long,
    /** The categorical/signed series, unreduced. Empty for a pure time series. */
    val datums: List<VizDatum> = emptyList(),
    /** The time series, for [BtInsight.PORTFOLIO_DEVELOPMENT]. */
    val series: List<BtInsightPoint> = emptyList(),
    /** The comparison series, when the card asked for one and it exists. */
    val compareSeries: List<BtInsightPoint> = emptyList(),
    /** Paired tracks, for [BtInsight.VALUE_VS_BASIS]. */
    val paired: List<BtInsightPair> = emptyList(),
    /** The one primary fact the card, the section and the poster all lead with. */
    val headline: BtInsightValue? = null,
    /** 2–3 exact supporting facts. The section page prints all of them. */
    val facts: List<BtInsightFact> = emptyList(),
    /** The deterministic factual caption. Never causal, never predictive. */
    val caption: BtInsightCaption? = null,
    /** True when values carry direction and part-to-whole geometry is invalid. */
    val signed: Boolean = false,
    /** The denominator printed shares are fractions of. 0 when there is none. */
    val total: Double = 0.0,
    /** e.g. "16 von 19 Positionen" — cost-basis coverage. */
    val coverage: BtInsightCoverage? = null,
    /** Non-null means: render the designed empty state, not a chart. */
    val empty: BtInsightEmptyReason? = null,
) {
    val isEmpty: Boolean get() = empty != null

    /** True when the snapshot holds exactly one datum for a time-series insight. */
    val isSinglePoint: Boolean get() = series.size == 1
}

/** One point of a value or performance series. */
data class BtInsightPoint(val epochDay: Long, val value: Double)

/** One row of the paired market-value / cost-basis track. */
data class BtInsightPair(
    val key: String,
    val label: String,
    val valueEur: Double,
    val basisEur: Double,
    /** Server-computed difference; `null` when no cost basis is recorded. */
    val deltaEur: Double?,
    val colorIndex: Int = 0,
)

/**
 * A typed value.
 *
 * The type is what makes the privacy ruling implementable: [Money] is an
 * absolute euro amount and disappears from a shared image; [Percent], [Count]
 * and [Text] are proportions, shapes and names and stay.
 */
sealed interface BtInsightValue {
    /** An absolute euro amount. Removed by [insightHideAmounts]. */
    data class Money(val eur: Double, val signed: Boolean = false) : BtInsightValue

    /** A percentage. Survives image privacy — a share reveals no balance. */
    data class Percent(val pct: Double, val signed: Boolean = false) : BtInsightValue

    /** A plain count, rendered through an Android plural by the surface. */
    data class Count(val count: Int, @param:PluralsRes val pluralRes: Int) : BtInsightValue

    /** User data (a ticker, a tag, a source name). Never translated. */
    data class Text(val text: String) : BtInsightValue

    /**
     * A money-and-percentage pair shown as one fact ("+4.827,10 € · +14,30 %").
     * Under image privacy it degrades to its percentage rather than vanishing —
     * the shape of the answer survives, the balance does not.
     */
    data class MoneyPercent(
        val eur: Double,
        val pct: Double,
        val signed: Boolean = true,
    ) : BtInsightValue

    /** The placeholder a removed amount leaves behind (`Betrag ausgeblendet`). */
    data object Hidden : BtInsightValue
}

/** One labelled supporting fact. */
data class BtInsightFact(@param:StringRes val labelRes: Int, val value: BtInsightValue)

/**
 * A caption template plus its arguments.
 *
 * Captions are deterministic restatements of what the chart already shows — the
 * study forbids causal explanation, advice and prediction, so a caption may only
 * name a mark and repeat its value.
 */
data class BtInsightCaption(
    @param:StringRes val templateRes: Int,
    val name: String? = null,
    val value: BtInsightValue? = null,
)

/** "{covered} von {total} Positionen". */
data class BtInsightCoverage(val covered: Int, val total: Int)

/**
 * The designed empty states, one per insight family.
 *
 * These are answers, not failures — "Absence is not 0,00 €". Each maps to the
 * exact title/body pair from the study's copy table, resolved in
 * [insightEmptyTitleRes] / [insightEmptyBodyRes].
 */
enum class BtInsightEmptyReason {
    NO_HISTORY,
    NO_ALLOCATION,
    NO_MOVEMENTS_TODAY,
    NO_CASHFLOW,
    NO_SPENDING,
    NO_HOLDINGS,
    NO_COST_BASIS,
    NOTHING_REALIZED,
    NO_DIVIDENDS,
    NO_TAX_DATA,
    NO_LIQUID_FUNDS,
}

// ---------------------------------------------------------------------------
// The image privacy transform
// ---------------------------------------------------------------------------

/**
 * Strip every absolute euro amount from [snapshot] for a shared image.
 *
 * ## PRIVACY RULING — shared images (study § "Privacy ruling", implemented here)
 *
 * `Beträge ausblenden` defaults **ON every time image sharing starts**, and an
 * "off" choice is **never remembered as the next default**. The reason is not
 * timidity: at render time the destination is unknown, and publication is easy
 * to make irreversible. A setting that remembered "off" would turn one
 * deliberate decision into a standing one, and the next share would leak a
 * balance the user never re-considered. The default is re-asserted in
 * [at.bettertrack.app.ui.insights.InsightImageSheet]; nothing persists it.
 *
 * The PDF is deliberately the opposite — see [BT_INSIGHTS_PDF_CARRIES_REAL_VALUES].
 *
 * What this function does, exactly as the study specifies:
 *
 *  - removes every absolute euro amount and every monetary axis
 *    ([BtInsightValue.Money] → [BtInsightValue.Hidden]);
 *  - preserves percentages, geometry, categorical colours, signed direction,
 *    tickers and the period — the insight still reads as an insight;
 *  - promotes an available meaningful percentage to the headline
 *    ([BtInsightValue.MoneyPercent] → [BtInsightValue.Percent]), otherwise shows
 *    `Betrag ausgeblendet`.
 *
 * The datum *values* are intentionally left intact: they are what the geometry
 * is drawn from, and a treemap whose tiles were zeroed would not be a private
 * chart, it would be a blank one. The renderer is what must not print them, and
 * [BT_INSIGHT_IMAGE_LABELS_ARE_SHARES] is the flag that says so.
 */
fun insightHideAmounts(snapshot: BtInsightSnapshot): BtInsightSnapshot = snapshot.copy(
    headline = snapshot.headline?.let(::hideValue)?.let { hidden ->
        // "Promote an available meaningful percentage to the headline, otherwise
        // show Betrag ausgeblendet." For a part-to-whole insight the largest
        // mark's share IS that percentage, and it is the number the study's own
        // specimen leads with — a poster whose biggest line reads "Betrag
        // ausgeblendet" has removed the answer along with the balance.
        if (hidden == BtInsightValue.Hidden) snapshot.largestShare() ?: hidden else hidden
    },
    facts = snapshot.facts
        .map { it.copy(value = hideValue(it.value)) }
        // A fact that becomes nothing but a placeholder is noise on a poster:
        // three rows of "Betrag ausgeblendet" say less than none.
        .filterNot { it.value == BtInsightValue.Hidden },
    caption = snapshot.caption?.let { caption ->
        val value = caption.value?.let(::hideValue)
        if (value == BtInsightValue.Hidden) null else caption.copy(value = value)
    },
    coverage = snapshot.coverage,
)

/**
 * The largest drawn mark's share of the whole, or null when this insight has no
 * whole to be a share of (a signed set has no denominator).
 */
private fun BtInsightSnapshot.largestShare(): BtInsightValue.Percent? {
    if (signed || total == 0.0 || !total.isFinite()) return null
    val largest = datums.maxByOrNull { it.value } ?: return null
    if (largest.value <= 0.0) return null
    return BtInsightValue.Percent(largest.value / total * 100.0)
}

private fun hideValue(value: BtInsightValue): BtInsightValue = when (value) {
    is BtInsightValue.Money -> BtInsightValue.Hidden
    // The percentage is the meaningful half — promote it and drop the balance.
    is BtInsightValue.MoneyPercent -> BtInsightValue.Percent(value.pct, value.signed)
    is BtInsightValue.Percent, is BtInsightValue.Count, is BtInsightValue.Text,
    BtInsightValue.Hidden,
    -> value
}

/**
 * When amounts are hidden, chart labels may only print shares.
 *
 * A ranked bar that keeps its euro column would defeat the transform on the one
 * surface that most needs it, so the image renderer reads this constant rather
 * than deciding per form.
 */
const val BT_INSIGHT_IMAGE_LABELS_ARE_SHARES: Boolean = true

/**
 * ## PRIVACY RULING — the PDF report carries REAL amounts
 *
 * Consistent with the shipped ledger-export ruling (`CashExport.kt`, owner
 * 2026-08-17): a file the user explicitly asked for, for their own records, is
 * not the place to hide their own numbers. Masking it would protect nobody and
 * would produce a personal financial report that cannot be used as one.
 *
 * The two rulings differ because the *destinations* differ, not because the data
 * does: a PDF is an intentional personal export, while a social image has an
 * unknown audience. Both are guarded by `InsightsPrivacyRulingTest`.
 */
const val BT_INSIGHTS_PDF_CARRIES_REAL_VALUES: Boolean = true
