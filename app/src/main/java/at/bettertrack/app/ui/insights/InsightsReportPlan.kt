package at.bettertrack.app.ui.insights

/**
 * The report **plan**: which insights go in, over which frame, and exactly how
 * many A4 pages that produces.
 *
 * Everything here is pure. The page count shown in the builder's sticky footer
 * and the page count printed in the finished PDF's footer come from the same
 * function ([reportPageCount]), because a report that promised seven pages and
 * delivered eight would undermine the one thing this feature sells: that the
 * export is a deliberate, predictable snapshot.
 */

/** The frame every section of one report shares: one period, one portfolio set. */
data class BtReportFrame(
    val period: BtInsightPeriod,
    /** Empty = every portfolio the page scope contains ("Alle Depots"). */
    val portfolioIds: Set<String>,
    /** True when [period] is exactly one calendar year — gates the tax section. */
    val isCalendarYear: Boolean,
)

/**
 * How many sections fit on one contents page.
 *
 * The study fixes this at eight, and derives its worked example from it: five
 * selected insights → 1 cover + 1 contents/summary + 5 sections + 1 provenance
 * = **8 pages**. [InsightsReportPlanTest] pins that example.
 */
const val BT_REPORT_SECTIONS_PER_CONTENTS: Int = 8

/**
 * The deterministic page formula:
 *
 * `1 cover + contents/summary pages + 1 page per insight + 1 provenance page`
 *
 * One insight owns one page. The study forbids the obvious "optimisation" of
 * pairing two small cards onto a sheet to save paper — a section that shares a
 * page stops being able to carry its own scope header, chart field and
 * provenance line, and the reader loses the guarantee that every page stands
 * alone.
 *
 * Returns 0 for an empty selection: a report with no sections is not a one-page
 * report, it is not a report, and the builder disables export rather than
 * producing a cover with nothing behind it.
 */
fun reportPageCount(selectedCount: Int): Int {
    if (selectedCount <= 0) return 0
    val contentsPages = reportContentsPageCount(selectedCount)
    return 1 + contentsPages + selectedCount + 1
}

/** Contents/summary pages needed for [selectedCount] sections. Always at least one. */
fun reportContentsPageCount(selectedCount: Int): Int {
    if (selectedCount <= 0) return 0
    val perPage = BT_REPORT_SECTIONS_PER_CONTENTS
    return (selectedCount + perPage - 1) / perPage
}

/** The 1-based page number the first section lands on. */
fun reportFirstSectionPage(selectedCount: Int): Int =
    1 + reportContentsPageCount(selectedCount) + 1

/** The 1-based page number [index] (0-based section index) lands on. */
fun reportSectionPage(selectedCount: Int, index: Int): Int =
    reportFirstSectionPage(selectedCount) + index

/** The 1-based provenance page number. */
fun reportProvenancePage(selectedCount: Int): Int = reportPageCount(selectedCount)

/**
 * The selection after a frame change, plus how many cards the change unchecked.
 *
 * The study requires this to be loud: "A frame change explicitly unchecks
 * incompatible cards and announces how many; it never exports an empty page
 * silently." The only incompatibility that exists today is a calendar-year
 * insight in a non-calendar-year frame.
 */
data class BtReportSelectionChange(
    val selected: List<BtInsight>,
    val removed: List<BtInsight>,
) {
    val removedCount: Int get() = removed.size
}

/** Drop every selected insight the new [frame] cannot honestly render. */
fun reportReconcileSelection(
    selected: List<BtInsight>,
    frame: BtReportFrame,
): BtReportSelectionChange {
    val kept = selected.filter { insightAcceptsCalendarYear(it, frame.isCalendarYear) }
    return BtReportSelectionChange(
        selected = kept,
        removed = selected.filterNot { it in kept },
    )
}

/**
 * `Empfohlene auswählen` — the five defaults, minus anything this frame cannot
 * render. Offered when the selection is empty, so the primary action is never a
 * dead end.
 */
fun reportRecommendedSelection(frame: BtReportFrame): List<BtInsight> =
    BT_INSIGHTS_DEFAULT.filter { insightAcceptsCalendarYear(it, frame.isCalendarYear) }

/**
 * Order a selection for the report.
 *
 * The page order the user arranged wins; anything not on the page falls back to
 * catalog rank. A report whose sections appeared in a different order from the
 * screen they were chosen on would read as a different document.
 */
fun reportOrderSelection(selected: Collection<BtInsight>, page: BtInsightsPage): List<BtInsight> {
    val pageRank = page.visible.withIndex().associate { (index, insight) -> insight to index }
    return selected.distinct().sortedWith(
        compareBy(
            { pageRank[it] ?: (page.visible.size + it.spec.rank) },
            { it.spec.rank },
        ),
    )
}

/**
 * One planned section: its insight, its 1-based number and the page it lands on.
 *
 * The PDF renderer walks this list; the review screen renders thumbnails from
 * the same list. Two readers of one plan, so the contents page and the page
 * footers cannot disagree.
 */
data class BtReportSection(
    val insight: BtInsight,
    /** 1-based section number, printed as `01`, `02`, … */
    val number: Int,
    /** 1-based PDF page this section occupies. */
    val page: Int,
)

/** Build the ordered section plan for [selected]. */
fun reportSections(selected: List<BtInsight>): List<BtReportSection> =
    selected.mapIndexed { index, insight ->
        BtReportSection(
            insight = insight,
            number = index + 1,
            page = reportSectionPage(selected.size, index),
        )
    }

/**
 * A rough byte estimate for the footer's `ca. {size}`.
 *
 * Deliberately coarse and deliberately labelled as an estimate: the real size
 * depends on how many vector marks each chart emits, and promising an exact
 * figure before rendering would be a number we cannot keep.
 *
 * ## Re-measured against a real report (device QA 2026-09-01, defect #23)
 *
 * The footer said `ca. 8 Seiten · 1,8 MB`; the file that came out was **259 kB**
 * — the estimate was ~7× the truth, which is not "coarse", it is wrong in a way
 * that would make a user on a metered connection cancel a report they could
 * easily afford. The old per-page constant was borrowed from the cash PDF, and
 * that borrowing was the bug: the cash export rasterises, this one is pure
 * vector ([at.bettertrack.app.ui.insights.InsightsPdfExport] draws into a
 * `PdfDocument` canvas), so its pages cost a fraction as much.
 *
 * The measurement: 5 insights ⇒ 8 pages ⇒ 259 kB, i.e. ~29.4 kB per page once
 * the base is taken out. [REPORT_PAGE_BYTES] rounds that up to 30 kB, which puts
 * the same report at 264 kB against an observed 259 kB.
 */
fun reportEstimateBytes(selectedCount: Int): Long {
    if (selectedCount <= 0) return 0L
    val pages = reportPageCount(selectedCount)
    return REPORT_BASE_BYTES + pages * REPORT_PAGE_BYTES
}

private const val REPORT_BASE_BYTES = 24_000L

/**
 * Per-page cost of a VECTOR page, measured on the owner's device 2026-09-01
 * (8 pages, 259 kB). Rounded up, so the estimate errs generous by ~2 %.
 */
private const val REPORT_PAGE_BYTES = 30_000L
