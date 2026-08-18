package at.bettertrack.app.ui.insights

import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizLabels

/**
 * The frozen inputs the two exporters render from.
 *
 * Both the A4 PDF and the shared PNG run off the main thread, minutes after the
 * user pressed the button, so neither may reach back into Compose for a string,
 * a colour or a locale. Everything they need is resolved once — on the main
 * thread, with a `Context` in hand — into the structures below, and then the
 * renderers are pure functions of them.
 *
 * That is the same discipline the shipped cash export uses (`CashPdfCopy`), and
 * it buys the property that matters here: **the export is a snapshot.** The
 * study requires the report to "snapshot server-returned values, settings, scope
 * and timestamps before rendering", and a renderer that cannot observe live
 * state cannot violate it.
 */

/** The palette an exporter paints with, resolved to ARGB ints. */
data class BtInsightPaintTheme(
    val paper: Int,
    val panel: Int,
    val border: Int,
    val ink: Int,
    val muted: Int,
    val gold: Int,
    val gain: Int,
    val loss: Int,
    /** The categorical ramp, in stable-rank order. */
    val series: List<Int>,
    val rest: Int,
    val cash: Int,
    val inkOnFill: Int,
    val inkOnPale: Int,
)

/**
 * How an exporter turns a number into text.
 *
 * [amount] is always the REAL-value formatter (`btFormatMoneyExport`): a file
 * the user asked for is never masked. Whether amounts appear at all is
 * [showAmounts], which the image path sets to `false` under the privacy ruling
 * and the PDF path always leaves `true`.
 */
data class BtInsightPaintLabels(
    val amount: (Double) -> String,
    /** Takes a FRACTION (0.42) and returns a printed share ("42 %"). */
    val share: (Double) -> String,
    /** Signed money, for the movers-style rows. */
    val signedAmount: (Double) -> String,
    /** Signed percentage. */
    val signedPercent: (Double) -> String,
    /**
     * False when the surface may not print absolute euro values.
     *
     * Set only by the image path. When false the renderer prints shares in place
     * of amounts and draws no monetary axis — see [insightHideAmounts].
     */
    val showAmounts: Boolean = true,
    val labels: BtVizLabels = BtVizLabels.AUTO,
)

/** One section of a report, resolved and frozen. */
data class BtInsightReportSectionDoc(
    val section: BtReportSection,
    val snapshot: BtInsightSnapshot,
    /** The already-resolved form. Never [BtVizForm.AUTO]. */
    val form: BtVizForm,
    /** Localized insight name. */
    val name: String,
    /** Localized insight question. */
    val question: String,
    /** The one primary fact, pre-formatted. */
    val headline: String,
    /** True when the headline should be tinted by money direction. */
    val headlineValue: Double?,
    /** 2–3 exact facts as label/value pairs, pre-formatted. */
    val facts: List<Pair<String, String>>,
    /** The deterministic caption, pre-formatted. */
    val caption: String,
    /** `Stand {date} · serverseitig berechnet`. */
    val asOfLine: String,
    /** The row labels a legend needs, pre-formatted: label, amount, share. */
    val legend: List<Triple<String, String, String>>,
    /** Non-null when this section renders its designed empty state instead. */
    val emptyTitle: String? = null,
    val emptyBody: String? = null,
)

/**
 * A whole report, frozen.
 *
 * Everything the renderer prints on the cover, the summary, each section and the
 * provenance page is already here — including the timestamps, so re-rendering
 * the same document twice produces byte-comparable output.
 */
data class BtInsightsReportDoc(
    val sections: List<BtInsightReportSectionDoc>,
    val totalPages: Int,
    // Cover
    val coverKicker: String,
    val coverLine1: String,
    val coverLine2: String,
    val periodLabel: String,
    val scopeLabel: String,
    val createdAtLabel: String,
    val dataAsOfLabel: String,
    val privateLabel: String,
    val documentKindLabel: String,
    val createdAtCaption: String,
    val dataAsOfCaption: String,
    val scopeCaption: String,
    val documentKindCaption: String,
    val coverSubline: String,
    // Summary / contents
    val summaryLabel: String,
    val atAGlanceLabel: String,
    val keyFiguresTitle: String,
    val keyFiguresNote: String,
    val contentsLabel: String,
    /** Up to four headline facts, each label/value, only from CHECKED insights. */
    val keyFigures: List<Triple<String, String, Double?>>,
    // Provenance
    val provenanceTitle: String,
    val includedLabel: String,
    val notIncludedLabel: String,
    val included: List<String>,
    val notIncluded: List<String>,
    val notes: List<String>,
    val timezoneLabel: String,
    val timezoneValue: String,
    val currencyLabel: String,
    val currencyValue: String,
    val settingsLabel: String,
    val settingsLines: List<String>,
    // Running chrome
    val runningHeader: String,
    val footerText: String,
    /** `(page, pages) -> "Seite 3 von 8"`, pre-resolved so no Context is needed. */
    val pageOf: (Int, Int) -> String,
    val sectionWord: String,
    val theme: BtInsightPaintTheme,
    val labels: BtInsightPaintLabels,
)

/** A single-insight image, frozen. */
data class BtInsightImageDoc(
    val format: BtInsightImageFormat,
    val snapshot: BtInsightSnapshot,
    val form: BtVizForm,
    val brand: String,
    /** `AUFTEILUNG · 18. AUGUST 2026` — the small caps kicker. */
    val kicker: String,
    /** The one-sentence title. */
    val title: String,
    /** The big number, pre-formatted. May be `Betrag ausgeblendet`. */
    val headline: String,
    val headlineValue: Double?,
    /** `Alle Portfolios · 6 Anlageklassen`. */
    val scopeLine: String,
    val caption: String,
    /** The pill under the chart: `Beträge ausgeblendet`, or null when amounts show. */
    val privacyPill: String?,
    val footerLeft: String,
    val footerRight: String,
    /** Row labels for a legend-bearing form: label, value. */
    val legend: List<Pair<String, String>>,
    val theme: BtInsightPaintTheme,
    val labels: BtInsightPaintLabels,
)

/**
 * The A4 page box and margins, in PostScript points at 72 dpi.
 *
 * Identical to the shipped cash export's page size, deliberately: two exports
 * from one app that disagreed about what "A4" means would be a defect the user
 * discovers at the printer.
 */
object BtInsightsPdfPage {
    const val WIDTH: Int = 595
    const val HEIGHT: Int = 842

    /** 42 pt outer margins leave the study's 511 pt content column. */
    const val MARGIN: Float = 42f
    const val CONTENT_WIDTH: Float = WIDTH - 2 * MARGIN

    /** Running header band height. */
    const val HEADER: Float = 24f

    /** Footer reserve. */
    const val FOOTER: Float = 20f

    /** The chart field a section page gives its insight. */
    const val CHART_HEIGHT: Float = 300f

    /** Baseline grid. Every vertical step is a multiple of this. */
    const val BASELINE: Float = 4f
}
