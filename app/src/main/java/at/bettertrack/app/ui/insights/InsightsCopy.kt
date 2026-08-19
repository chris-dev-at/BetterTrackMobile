package at.bettertrack.app.ui.insights

import android.content.res.Resources
import androidx.annotation.StringRes
import at.bettertrack.app.R
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.format.btFormatMoneyExport
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Every name, question, empty state and number the Insights feature prints,
 * resolved in one place.
 *
 * Two surfaces read it and they cannot be allowed to disagree: the Compose card
 * (which has a `Context`) and the two exporters (which run off the main thread
 * and get a plain [Resources]). Routing both through this file is what makes the
 * PDF say exactly what the card said — the alternative, formatting in each
 * renderer, is how an export quietly starts rounding differently from the screen
 * it was exported from.
 *
 * ## The one deliberate divergence
 *
 * [BtInsightValueFormatter.export] picks `btFormatMoneyExport` instead of
 * `formatEur`. That is the shipped ledger ruling: discreet mode masks the
 * SCREEN, and a file the user explicitly asked for is not a screen. Everything
 * else — rounding, the decimal comma, the symbol position, the sign — is
 * byte-identical between the two paths by construction, because both call the
 * same core formatter.
 */

@StringRes
fun insightNameRes(insight: BtInsight): Int = when (insight) {
    BtInsight.PORTFOLIO_DEVELOPMENT -> R.string.bt_insight_name_development
    BtInsight.ASSET_CLASSES -> R.string.bt_insight_name_asset_classes
    BtInsight.DAILY_MOVERS -> R.string.bt_insight_name_movers
    BtInsight.MONTHLY_CASHFLOW -> R.string.bt_insight_name_cashflow
    BtInsight.BUDGETS_SPENDING -> R.string.bt_insight_name_spending
    BtInsight.HOLDING_CONCENTRATION -> R.string.bt_insight_name_concentration
    BtInsight.UNREALIZED_PL -> R.string.bt_insight_name_unrealized
    BtInsight.VALUE_VS_BASIS -> R.string.bt_insight_name_value_basis
    BtInsight.REALIZED_FEES -> R.string.bt_insight_name_realized
    BtInsight.DIVIDENDS -> R.string.bt_insight_name_dividends
    BtInsight.TAX_SUMMARY -> R.string.bt_insight_name_tax
    BtInsight.LIQUID_FUNDS -> R.string.bt_insight_name_liquid
}

@StringRes
fun insightQuestionRes(insight: BtInsight): Int = when (insight) {
    BtInsight.PORTFOLIO_DEVELOPMENT -> R.string.bt_insight_q_development
    BtInsight.ASSET_CLASSES -> R.string.bt_insight_q_asset_classes
    BtInsight.DAILY_MOVERS -> R.string.bt_insight_q_movers
    BtInsight.MONTHLY_CASHFLOW -> R.string.bt_insight_q_cashflow
    BtInsight.BUDGETS_SPENDING -> R.string.bt_insight_q_spending
    BtInsight.HOLDING_CONCENTRATION -> R.string.bt_insight_q_concentration
    BtInsight.UNREALIZED_PL -> R.string.bt_insight_q_unrealized
    BtInsight.VALUE_VS_BASIS -> R.string.bt_insight_q_value_basis
    BtInsight.REALIZED_FEES -> R.string.bt_insight_q_realized
    BtInsight.DIVIDENDS -> R.string.bt_insight_q_dividends
    BtInsight.TAX_SUMMARY -> R.string.bt_insight_q_tax
    BtInsight.LIQUID_FUNDS -> R.string.bt_insight_q_liquid
}

@StringRes
fun insightGroupRes(group: BtInsightGroup): Int = when (group) {
    BtInsightGroup.PORTFOLIO -> R.string.bt_insight_group_portfolio
    BtInsightGroup.CASHFLOW -> R.string.bt_insight_group_cashflow
    BtInsightGroup.TAXES_INCOME -> R.string.bt_insight_group_taxes
}

@StringRes
fun insightEmptyTitleRes(reason: BtInsightEmptyReason): Int = when (reason) {
    BtInsightEmptyReason.NO_HISTORY -> R.string.bt_insight_empty_history_title
    BtInsightEmptyReason.NO_ALLOCATION -> R.string.bt_insight_empty_allocation_title
    BtInsightEmptyReason.NO_MOVEMENTS_TODAY -> R.string.bt_insight_empty_movers_title
    BtInsightEmptyReason.NO_CASHFLOW -> R.string.bt_insight_empty_cashflow_title
    BtInsightEmptyReason.NO_SPENDING -> R.string.bt_insight_empty_spending_title
    BtInsightEmptyReason.NO_HOLDINGS -> R.string.bt_insight_empty_holdings_title
    BtInsightEmptyReason.NO_COST_BASIS -> R.string.bt_insight_empty_basis_title
    BtInsightEmptyReason.NOTHING_REALIZED -> R.string.bt_insight_empty_realized_title
    BtInsightEmptyReason.NO_DIVIDENDS -> R.string.bt_insight_empty_dividends_title
    BtInsightEmptyReason.NO_TAX_DATA -> R.string.bt_insight_empty_tax_title
    BtInsightEmptyReason.NO_LIQUID_FUNDS -> R.string.bt_insight_empty_liquid_title
    BtInsightEmptyReason.PRICE_HISTORY_LOADING -> R.string.bt_insight_empty_prices_loading_title
    BtInsightEmptyReason.NO_PRICE_HISTORY -> R.string.bt_insight_empty_prices_title
}

@StringRes
fun insightEmptyBodyRes(reason: BtInsightEmptyReason): Int = when (reason) {
    BtInsightEmptyReason.NO_HISTORY -> R.string.bt_insight_empty_history_body
    BtInsightEmptyReason.NO_ALLOCATION -> R.string.bt_insight_empty_allocation_body
    BtInsightEmptyReason.NO_MOVEMENTS_TODAY -> R.string.bt_insight_empty_movers_body
    BtInsightEmptyReason.NO_CASHFLOW -> R.string.bt_insight_empty_cashflow_body
    BtInsightEmptyReason.NO_SPENDING -> R.string.bt_insight_empty_spending_body
    BtInsightEmptyReason.NO_HOLDINGS -> R.string.bt_insight_empty_holdings_body
    BtInsightEmptyReason.NO_COST_BASIS -> R.string.bt_insight_empty_basis_body
    BtInsightEmptyReason.NOTHING_REALIZED -> R.string.bt_insight_empty_realized_body
    BtInsightEmptyReason.NO_DIVIDENDS -> R.string.bt_insight_empty_dividends_body
    BtInsightEmptyReason.NO_TAX_DATA -> R.string.bt_insight_empty_tax_body
    BtInsightEmptyReason.NO_LIQUID_FUNDS -> R.string.bt_insight_empty_liquid_body
    BtInsightEmptyReason.PRICE_HISTORY_LOADING -> R.string.bt_insight_empty_prices_loading_body
    BtInsightEmptyReason.NO_PRICE_HISTORY -> R.string.bt_insight_empty_prices_body
}

@StringRes
fun insightPeriodRes(kind: BtInsightPeriodKind): Int = when (kind) {
    BtInsightPeriodKind.ONE_MONTH -> R.string.bt_insight_period_1m
    BtInsightPeriodKind.SIX_MONTHS -> R.string.bt_insight_period_6m
    BtInsightPeriodKind.ONE_YEAR -> R.string.bt_insight_period_1y
    BtInsightPeriodKind.MAX -> R.string.bt_insight_period_max
    BtInsightPeriodKind.CUSTOM -> R.string.bt_insight_period_custom
    BtInsightPeriodKind.CALENDAR_YEAR -> R.string.bt_insight_period_calendar_year
}

/**
 * The picker label for a movement span.
 *
 * Deliberately NOT reusing [insightPeriodRes]: `1 M` there names a *frame* over a
 * portfolio series, while `1 Monat` here names a different server fact entirely.
 * Sharing the strings would invite sharing the control, which is the one thing
 * [BtInsightMoveRange] exists to prevent.
 */
@StringRes
fun insightMoveRangeRes(range: BtInsightMoveRange): Int = when (range) {
    BtInsightMoveRange.DAY -> R.string.bt_insight_move_day
    BtInsightMoveRange.WEEK -> R.string.bt_insight_move_week
    BtInsightMoveRange.MONTH -> R.string.bt_insight_move_month
    BtInsightMoveRange.YEAR -> R.string.bt_insight_move_year
    BtInsightMoveRange.SINCE_BUY -> R.string.bt_insight_move_since_buy
}

/**
 * The sentence printed under a movements chart saying what its numbers are.
 *
 * `null` for [BtInsightMoveRange.DAY], which needs no disclaimer: it prints
 * server-computed euro contributions and has done since the card shipped. The
 * other four each make a claim a reader could misread, so each says what it is.
 */
@StringRes
fun insightMoveNoteRes(range: BtInsightMoveRange): Int? = when (range) {
    BtInsightMoveRange.DAY -> null
    BtInsightMoveRange.WEEK,
    BtInsightMoveRange.MONTH,
    BtInsightMoveRange.YEAR,
    -> R.string.bt_insight_movers_price_note
    BtInsightMoveRange.SINCE_BUY -> R.string.bt_insight_movers_since_buy_note
}

@StringRes
fun insightSortRes(sort: BtInsightSort): Int = when (sort) {
    BtInsightSort.AMOUNT -> R.string.bt_insight_sort_amount
    BtInsightSort.PERCENT -> R.string.bt_insight_sort_percent
    BtInsightSort.NAME -> R.string.bt_insight_sort_name
    BtInsightSort.VALUE -> R.string.bt_insight_sort_value
    BtInsightSort.BASIS -> R.string.bt_insight_sort_basis
    BtInsightSort.DELTA -> R.string.bt_insight_sort_delta
    BtInsightSort.SIGNED -> R.string.bt_insight_sort_signed
}

@StringRes
fun insightGroupingRes(grouping: BtInsightGrouping): Int = when (grouping) {
    BtInsightGrouping.HOLDING -> R.string.bt_insight_grouping_holding
    BtInsightGrouping.MONTH -> R.string.bt_insight_grouping_month
    BtInsightGrouping.QUARTER -> R.string.bt_insight_grouping_quarter
    BtInsightGrouping.PAYER -> R.string.bt_insight_grouping_payer
    BtInsightGrouping.CATEGORY -> R.string.bt_insight_grouping_category
    BtInsightGrouping.SOURCE -> R.string.bt_insight_grouping_source
    BtInsightGrouping.TAG -> R.string.bt_insight_grouping_tag
}

@StringRes
fun insightSeriesRes(series: BtInsightSeries): Int = when (series) {
    BtInsightSeries.VALUE -> R.string.bt_insight_series_value
    BtInsightSeries.PERFORMANCE -> R.string.bt_insight_series_performance
    BtInsightSeries.BOTH -> R.string.bt_insight_series_both
}

@StringRes
fun insightCompareRes(compare: BtInsightCompare): Int = when (compare) {
    BtInsightCompare.NONE -> R.string.bt_insight_compare_none
    BtInsightCompare.PREVIOUS_PERIOD -> R.string.bt_insight_compare_previous
    BtInsightCompare.PREVIOUS_SNAPSHOT -> R.string.bt_insight_compare_snapshot
    BtInsightCompare.PREVIOUS_YEAR -> R.string.bt_insight_compare_year
}

/**
 * The `Darstellung` label for a resolved form, reusing the shipped chart study's
 * own vocabulary rather than inventing a second set of names for the same
 * shapes.
 */
@StringRes
fun insightFormRes(form: BtVizForm): Int = when (form) {
    BtVizForm.AUTO -> R.string.bt_viz_auto
    BtVizForm.TREEMAP -> R.string.bt_viz_form_treemap
    BtVizForm.MOSAIC -> R.string.bt_viz_form_mosaic
    BtVizForm.STACKED_BAR -> R.string.bt_viz_form_stacked_bar
    BtVizForm.RANKED_BARS -> R.string.bt_viz_form_ranked_bars
    BtVizForm.RING -> R.string.bt_viz_form_ring
    BtVizForm.WAFFLE -> R.string.bt_viz_form_waffle
    BtVizForm.DOT_PLOT -> R.string.bt_viz_form_dot_plot
    BtVizForm.BUBBLES -> R.string.bt_viz_form_bubbles
    BtVizForm.DONUT -> R.string.bt_viz_form_donut
}

// ---------------------------------------------------------------------------
// Value formatting
// ---------------------------------------------------------------------------

/**
 * Turns a [BtInsightValue] into text.
 *
 * @param export true for a file the user asked for (real amounts, per the
 *   shipped ledger ruling); false for the screen, where discreet mode masks
 *   inside the shared money formatter.
 */
class BtInsightValueFormatter(
    private val res: Resources,
    private val locale: Locale,
    private val export: Boolean,
    /**
     * Print percentages as WHOLE numbers.
     *
     * Set only by the shared-image path, so a caption reading "50 %" sits beside
     * a chart label reading "50 %". With the app's two-decimal percent the two
     * disagreed on the same poster — caught on device, 2026-08-18.
     */
    private val wholePercent: Boolean = false,
) {
    /** Absolute money. Masked on screen, real in a file. */
    fun money(value: Double, signed: Boolean = false): String =
        if (export) {
            btFormatMoneyExport(value, CURRENCY, locale, signed)
        } else {
            formatEur(value, locale, signed)
        }

    /** A percentage that already arrived as 0–100, not as a fraction. */
    fun percent(value: Double, signed: Boolean = false): String = when {
        wholePercent && !signed -> insightFormatWholeShare(value / 100.0, locale)
        else -> formatPercent(value, locale, showSign = signed)
    }

    /** A FRACTION (0.42) printed as a share ("42 %"). */
    fun share(fraction: Double): String =
        if (wholePercent) {
            insightFormatWholeShare(fraction, locale)
        } else {
            formatPercent(fraction * 100.0, locale, showSign = false)
        }

    fun format(value: BtInsightValue): String = when (value) {
        is BtInsightValue.Money -> money(value.eur, value.signed)
        is BtInsightValue.Percent -> percent(value.pct, value.signed)
        is BtInsightValue.Count -> res.getQuantityString(value.pluralRes, value.count, value.count)
        is BtInsightValue.Text -> value.text
        is BtInsightValue.MoneyPercent ->
            money(value.eur, value.signed) + " · " + percent(value.pct, value.signed)
        // The one string this formatter invents, and only because the value it
        // replaces was deliberately removed.
        BtInsightValue.Hidden -> res.getString(R.string.bt_insight_amount_hidden)
    }

    /** The signed magnitude a renderer tints by, or null when direction is meaningless. */
    fun direction(value: BtInsightValue?): Double? = when (value) {
        is BtInsightValue.Money -> if (value.signed) value.eur else null
        is BtInsightValue.Percent -> if (value.signed) value.pct else null
        is BtInsightValue.MoneyPercent -> value.eur
        else -> null
    }

    /** A caption, with its arguments substituted in the caller's language. */
    fun caption(caption: BtInsightCaption): String {
        val name = caption.name
        val value = caption.value?.let(::format)
        return when {
            name != null && value != null -> res.getString(caption.templateRes, name, value)
            value != null -> res.getString(caption.templateRes, value)
            name != null -> res.getString(caption.templateRes, name)
            else -> res.getString(caption.templateRes)
        }
    }

    /** "16 von 19 Positionen". */
    fun coverage(coverage: BtInsightCoverage): String = res.getQuantityString(
        R.plurals.bt_insight_coverage,
        coverage.total,
        coverage.covered,
        coverage.total,
    )

    private companion object {
        /** The app's display currency. Server totals are already EUR. */
        const val CURRENCY = "EUR"
    }
}

/**
 * A fraction printed as a WHOLE percent — `0.4988` → `50 %`.
 *
 * Used only by the two exporters' chart labels, and only because the painter
 * feeds them `wholePercentShares`: a largest-remainder column that sums to
 * exactly 100. Printing those through the app's two-decimal percent formatter
 * produced `50,00 %`, which claims a precision the whole-percent value does not
 * have — caught on the owner's device 2026-08-18, where the poster read
 * `50,00 %` next to a card reading `50,20 %`.
 *
 * The on-screen card keeps its decimals: it prints the exact euro amount beside
 * the share, so the extra digit is doing real work there. A social graphic has
 * neither the density budget nor the euro column, and the study's own specimens
 * print `42 %`.
 */
fun insightFormatWholeShare(fraction: Double, locale: Locale): String {
    val whole = Math.round(fraction * 100.0)
    val number = java.text.NumberFormat.getIntegerInstance(locale).format(whole)
    // Same spacing rule the app's percent formatter uses: German sets a space
    // before the sign, English does not.
    val space = if (locale.language == "en") "" else " "
    return "$number$space%"
}

// ---------------------------------------------------------------------------
// Dates
// ---------------------------------------------------------------------------

/** `18. Aug. 2026` — the medium form the app already uses for a stichtag. */
fun insightFormatDate(epochDay: Long, locale: Locale): String =
    LocalDate.ofEpochDay(epochDay)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** `2026-08-18` — the sortable form a file name uses. */
fun insightIsoDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

/**
 * `1. Sep. 2025 – 18. Aug. 2026`, or one date when the window is a stichtag.
 *
 * An en dash, not a hyphen: this is a range, and the app's ledger export already
 * spells ranges this way.
 */
fun insightFormatRange(fromEpochDay: Long, toEpochDay: Long, locale: Locale): String =
    if (fromEpochDay == toEpochDay) {
        insightFormatDate(toEpochDay, locale)
    } else {
        insightFormatDate(fromEpochDay, locale) + " – " + insightFormatDate(toEpochDay, locale)
    }
