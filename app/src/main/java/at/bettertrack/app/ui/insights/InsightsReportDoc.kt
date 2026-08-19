package at.bettertrack.app.ui.insights

import android.content.res.Resources
import at.bettertrack.app.R
import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.format.btFormatMoneyExport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Freeze a report: resolve every string, every number and every timestamp, then
 * hand the renderer something it cannot change its mind about.
 *
 * The study requires the export to "snapshot server-returned values, settings,
 * scope and timestamps before rendering". This function is that snapshot. After
 * it returns, nothing the user does — switching a card's form, pulling to
 * refresh, crossing midnight — can alter the document being written.
 *
 * ## The frame injection, and its limit
 *
 * Each section is built with [insightForReport], which replaces the card's
 * period and scope with the report's and leaves everything else alone. So a card
 * the user set to `Treemap · Top 5 · Fokus Aktien` exports as a treemap with the
 * same Top-5 and the same focus, over the report's period. The one thing the
 * report is allowed to override is *when* and *what*; never *how*.
 *
 * ## Real amounts
 *
 * Every value below goes through `btFormatMoneyExport`. See
 * [BT_INSIGHTS_PDF_CARRIES_REAL_VALUES] — a personal report the user explicitly
 * asked for is not masked, consistently with the shipped ledger export.
 */
@Suppress("LongParameterList", "LongMethod")
fun buildInsightsReportDoc(
    sections: List<BtReportSection>,
    cards: Map<BtInsight, BtInsightConfig>,
    familyConfigs: Map<String, BtVizConfig>,
    source: BtInsightSource,
    framePeriod: BtInsightPeriod,
    scopeIds: Set<String>,
    frameWindow: BtInsightWindow,
    scopeLabel: String,
    palette: BtInsightPaintTheme,
    resources: Resources,
    locale: Locale,
    brand: String,
    createdAtMs: Long,
    dataAsOfMs: Long,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): BtInsightsReportDoc {
    val formatter = BtInsightValueFormatter(resources, locale, export = true)
    val zone = ZoneId.systemDefault()
    val stamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    val periodLabel = insightFormatRange(frameWindow.fromEpochDay, frameWindow.toEpochDay, locale)
    val totalPages = reportPageCount(sections.size)

    val sectionDocs = sections.map { section ->
        val insight = section.insight
        val card = insightForReport(
            insight = insight,
            card = cards[insight] ?: BtInsightConfig.PRISTINE,
            reportPeriod = framePeriod,
            reportPortfolioIds = scopeIds,
        )
        val family = insight.spec.family?.let { familyConfigs[it.name] } ?: BtVizConfig()
        // Per section, not one shared window: the report injects ONE period, but
        // a stichtag section still resolves it to the frame's end date and says
        // so. The study: "stichtag sections use its end and label it."
        val sectionWindow = insightResolveWindow(insight, framePeriod, today)
        val snapshot = buildInsightSnapshot(
            insight = insight,
            config = card,
            source = source,
            window = sectionWindow,
        )
        // A4 always uses the FULL rendition. The study is explicit, and a page
        // with 511 pt of width has no reason to draw a compact reduction.
        val resolved = insightResolvedForm(insight, card, family, BtVizCanvas.APP_FULL)
            .takeIf { it != BtVizForm.AUTO }
            ?: if (snapshot.signed) BtVizForm.DOT_PLOT else BtVizForm.RANKED_BARS
        val total = snapshot.total

        BtInsightReportSectionDoc(
            section = section,
            snapshot = snapshot,
            form = resolved,
            name = resources.getString(insightNameRes(insight)),
            question = resources.getString(insightQuestionRes(insight)),
            headline = snapshot.headline?.let(formatter::format).orEmpty(),
            headlineValue = formatter.direction(snapshot.headline),
            facts = snapshot.facts.map { fact ->
                resources.getString(fact.labelRes) to formatter.format(fact.value)
            },
            caption = snapshot.caption?.let(formatter::caption).orEmpty(),
            asOfLine = resources.getString(
                R.string.bt_insight_as_of_server,
                insightFormatDate(snapshot.asOfEpochDay, locale),
            ),
            legend = snapshot.datums.map { datum ->
                Triple(
                    datum.label,
                    // The PDF legend must say the same thing the card said. A
                    // percent set printed through the money formatter would put
                    // a € on a price movement in a file the user keeps.
                    if (snapshot.datumUnit == BtInsightUnit.PERCENT) {
                        formatter.percent(datum.value, signed = true)
                    } else {
                        formatter.money(datum.value, snapshot.signed)
                    },
                    if (total != 0.0 && !snapshot.signed) formatter.share(datum.value / total) else "",
                )
            },
            emptyTitle = snapshot.empty?.let { resources.getString(insightEmptyTitleRes(it)) },
            emptyBody = snapshot.empty?.let { resources.getString(insightEmptyBodyRes(it)) },
        )
    }

    // Only CHECKED insights may contribute a headline fact. The study forbids
    // the summary from leaking a metric whose section was not selected.
    val keyFigures = sectionDocs
        .filter { it.snapshot.headline != null && !it.snapshot.isEmpty }
        .take(MAX_KEY_FIGURES)
        .map { Triple(it.name, it.headline, it.headlineValue) }

    return BtInsightsReportDoc(
        sections = sectionDocs,
        totalPages = totalPages,
        coverKicker = resources.getString(R.string.bt_insight_pdf_kicker),
        coverLine1 = resources.getString(R.string.bt_insight_pdf_cover_line1),
        coverLine2 = resources.getString(R.string.bt_insight_pdf_cover_line2),
        periodLabel = periodLabel,
        scopeLabel = resources.getString(R.string.bt_insight_pdf_selection),
        createdAtLabel = resources.getString(R.string.bt_insight_pdf_created_at),
        dataAsOfLabel = resources.getString(R.string.bt_insight_pdf_data_as_of),
        privateLabel = resources.getString(R.string.bt_insight_pdf_private),
        documentKindLabel = resources.getString(R.string.bt_insight_pdf_document_kind),
        createdAtCaption = stamp.format(Instant.ofEpochMilli(createdAtMs).atZone(zone)),
        dataAsOfCaption = stamp.format(Instant.ofEpochMilli(dataAsOfMs).atZone(zone)),
        scopeCaption = scopeLabel,
        documentKindCaption = resources.getString(R.string.bt_insight_pdf_document_kind),
        coverSubline = buildString {
            append(scopeLabel)
            append(" · ")
            append(
                resources.getQuantityString(
                    R.plurals.bt_insight_selected,
                    sections.size,
                    sections.size,
                ),
            )
        },
        summaryLabel = resources.getString(R.string.bt_insight_pdf_summary),
        atAGlanceLabel = resources.getString(R.string.bt_insight_pdf_at_a_glance),
        keyFiguresTitle = resources.getString(R.string.bt_insight_pdf_key_figures),
        keyFiguresNote = resources.getString(R.string.bt_insight_pdf_key_figures_note),
        contentsLabel = resources.getString(R.string.bt_insight_pdf_contents),
        keyFigures = keyFigures,
        provenanceTitle = resources.getString(R.string.bt_insight_pdf_provenance),
        includedLabel = resources.getString(R.string.bt_insight_pdf_included),
        notIncludedLabel = resources.getString(R.string.bt_insight_pdf_not_included),
        included = sectionDocs.map { "${twoDigits(it.section.number)}  ${it.name}" },
        notIncluded = listOf(
            resources.getString(R.string.bt_insight_pdf_excl_accounts),
            resources.getString(R.string.bt_insight_pdf_excl_liabilities),
            resources.getString(R.string.bt_insight_pdf_excl_pending),
            resources.getString(R.string.bt_insight_pdf_excl_universe),
        ),
        notes = listOf(
            resources.getString(R.string.bt_insight_pdf_note_server),
            resources.getString(R.string.bt_insight_pdf_note_cutoff),
            resources.getString(R.string.bt_insight_pdf_note_advice),
        ),
        timezoneLabel = resources.getString(R.string.bt_insight_pdf_timezone),
        timezoneValue = zone.id,
        currencyLabel = resources.getString(R.string.bt_insight_pdf_display_currency),
        currencyValue = CURRENCY,
        settingsLabel = resources.getString(R.string.bt_insight_pdf_settings),
        settingsLines = sectionDocs.map { doc ->
            // An insight with no form vocabulary owns its rendition, so naming
            // the fallback the resolver returned would print "Rangbalken" next
            // to a line chart. Caught on the owner's device, 2026-08-18.
            val form = when {
                doc.snapshot.series.isNotEmpty() ->
                    resources.getString(R.string.bt_insight_form_timeseries)
                doc.snapshot.paired.isNotEmpty() ->
                    resources.getString(R.string.bt_insight_form_paired)
                else -> resources.getString(insightFormRes(doc.form))
            }
            "${doc.name} · $form"
        },
        // Composed here rather than stored as one string: "BetterTrack Insights"
        // is a brand line plus two data values, and a resource that concatenated
        // them would be byte-identical in both languages and fail parity.
        runningHeader = "$brand ${resources.getString(R.string.bt_insight_studio_title)} · " +
            "$scopeLabel · $periodLabel",
        footerText = resources.getString(R.string.bt_insight_pdf_footer),
        pageOf = { page, pages -> resources.getString(R.string.bt_insight_pdf_page_of, page, pages) },
        sectionWord = resources.getString(R.string.bt_insight_pdf_section),
        theme = palette,
        labels = BtInsightPaintLabels(
            amount = { btFormatMoneyExport(it, CURRENCY, locale, false) },
            // Whole percents: the painter feeds this a largest-remainder
            // column that already sums to 100.
            share = { insightFormatWholeShare(it, locale) },
            signedAmount = { btFormatMoneyExport(it, CURRENCY, locale, true) },
            signedPercent = { formatter.percent(it, signed = true) },
            // The report always prints real amounts.
            showAmounts = BT_INSIGHTS_PDF_CARRIES_REAL_VALUES,
        ),
    )
}

private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()

private const val MAX_KEY_FIGURES = 4
private const val CURRENCY = "EUR"
