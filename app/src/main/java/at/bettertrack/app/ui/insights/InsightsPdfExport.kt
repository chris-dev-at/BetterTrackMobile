package at.bettertrack.app.ui.insights

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * The insights report's **PDF** half: a light-first, A4 document that stands on
 * its own away from the app.
 *
 * ## Why the platform renderer, again
 *
 * `android.graphics.pdf.PdfDocument` is framework and draws through the same
 * `Canvas` everything else does, exactly as the shipped ledger export
 * ([at.bettertrack.app.ui.cash.writeCashPdf]) already proves. An iText/PdfBox
 * port would add megabytes and a licence question to produce type and
 * rectangles. The cost is manual layout, which is why the page **count** is
 * arithmetic ([reportPageCount]) and unit-tested rather than eyeballed: the page
 * total printed in the footer and the one promised in the builder's sticky
 * footer come from one formula.
 *
 * ## Light-first, and why that is not a theme bug
 *
 * The app is dark by default; this document is not. A report is paper — it is
 * printed, forwarded, and read next to bank statements — and a near-black A4
 * page is a page nobody prints twice. The palette therefore arrives resolved in
 * [BtInsightsReportDoc.theme] (paper `#FDFCF8`, panels white, gold `#F6B82E`)
 * and this file declares no colour of its own.
 *
 * ## Real text, never a raster
 *
 * Every string is drawn with [Canvas.drawText], so the finished PDF is
 * searchable, selectable and accessible, and prints at the printer's resolution
 * rather than the phone's. Rasterising a page would have made the layout easier
 * and the artefact worse.
 *
 * The discreet-mode ruling is the shipped one: a file the user explicitly asked
 * for carries their real numbers — see [BT_INSIGHTS_PDF_CARRIES_REAL_VALUES].
 */

// ── Type scale (size / leading, in points) ──────────────────────────────────
// Display sizes take tight leading on purpose: at 44 pt the cap height is ~31 pt,
// so 42 pt of leading still leaves a clean 11 pt channel between the two cover
// lines while keeping the statement reading as one block rather than two.
private const val COVER_SIZE = 44f
private const val COVER_LEAD = 42f
private const val TITLE_SIZE = 24f
private const val TITLE_LEAD = 25f
private const val METRIC_SIZE = 27f
private const val METRIC_LEAD = 30f
private const val SUB_SIZE = 12f
private const val SUB_LEAD = 16f
private const val BODY_SIZE = 9f
private const val BODY_LEAD = 13.5f
private const val CHART_LABEL_SIZE = 8f
private const val FOOT_SIZE = 8f
private const val FOOT_LEAD = 10f

/** The kicker/eyebrow tracking. Small caps by letter-spacing, not by a second face. */
private const val KICKER_TRACKING = 0.12f

/**
 * Write [doc] to [target] as an A4 PDF and return the file.
 *
 * @param onPage called with `(current, total)` **before** each page is drawn, so
 *   a caller can drive a determinate progress bar. A report of ten insights is
 *   twelve pages of chart painting; an indeterminate spinner over that would
 *   leave the user unable to tell slow from stuck.
 *
 * Page order comes from the plan, not from this function's own counting: the
 * cover, [reportContentsPageCount] contents pages, one page per section in
 * `section.page` order, and the provenance page last. The printed denominator is
 * [BtInsightsReportDoc.totalPages] — the frozen document is the authority on how
 * long it is, and a renderer that recounted could disagree with the contents
 * list it just printed.
 */
fun writeInsightsPdf(
    target: File,
    doc: BtInsightsReportDoc,
    onPage: (Int, Int) -> Unit = { _, _ -> },
): File {
    val pdf = PdfDocument()
    try {
        val contentsPages = reportContentsPageCount(doc.sections.size)
        val ordered = doc.sections.sortedBy { it.section.page }

        val pages = ArrayList<(ReportCanvas, Int) -> Unit>(doc.totalPages)
        pages += { page, _ -> page.drawCover() }
        repeat(contentsPages) { index ->
            pages += { page, number -> page.drawContents(index, number) }
        }
        ordered.forEach { section ->
            pages += { page, number -> page.drawSection(section, number) }
        }
        pages += { page, number -> page.drawProvenance(number) }

        pages.forEachIndexed { index, draw ->
            val number = index + 1
            onPage(number, doc.totalPages)
            val info = PdfDocument.PageInfo
                .Builder(BtInsightsPdfPage.WIDTH, BtInsightsPdfPage.HEIGHT, number)
                .create()
            val page = pdf.startPage(info)
            draw(ReportCanvas(page.canvas, doc), number)
            pdf.finishPage(page)
        }

        target.parentFile?.mkdirs()
        FileOutputStream(target).use { pdf.writeTo(it) }
    } finally {
        pdf.close()
    }
    return target
}

// ---------------------------------------------------------------------------
// One page, one canvas
// ---------------------------------------------------------------------------

/**
 * A single A4 page and the small vocabulary every page shares.
 *
 * Held as a class for the same reason the painter is: page geometry, the
 * palette and the type scale are constant across four very different layouts,
 * and re-deriving them per page is how a footer ends up 2 pt off on page 6.
 */
private class ReportCanvas(
    private val canvas: Canvas,
    private val doc: BtInsightsReportDoc,
) {
    private val theme = doc.theme
    private val left = BtInsightsPdfPage.MARGIN
    private val right = BtInsightsPdfPage.WIDTH - BtInsightsPdfPage.MARGIN
    private val width = BtInsightsPdfPage.CONTENT_WIDTH
    private val bottomLimit = BtInsightsPdfPage.HEIGHT - BtInsightsPdfPage.MARGIN -
        BtInsightsPdfPage.FOOTER

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f
    }

    private fun paint(
        size: Float,
        color: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
        tracking: Float = 0f,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = align
        letterSpacing = tracking
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        // Tabular figures everywhere, because almost every number on this page
        // sits in a column with another one.
        fontFeatureSettings = "tnum"
    }

    private fun paper() {
        fill.color = theme.paper
        canvas.drawRect(
            0f, 0f,
            BtInsightsPdfPage.WIDTH.toFloat(), BtInsightsPdfPage.HEIGHT.toFloat(),
            fill,
        )
    }

    /** A bordered white panel — the report's only container. */
    private fun panel(rect: RectF) {
        fill.color = theme.panel
        canvas.drawRoundRect(rect, 3f, 3f, fill)
        hairline.color = theme.border
        canvas.drawRoundRect(rect, 3f, 3f, hairline)
    }

    private fun rule(x: Float, y: Float, length: Float, thickness: Float, color: Int) {
        fill.color = color
        canvas.drawRect(x, y, x + length, y + thickness, fill)
    }

    private fun text(value: String, x: Float, y: Float, p: Paint) {
        if (value.isEmpty()) return
        canvas.drawText(value, x, y, p)
    }

    /**
     * Draw [value] as wrapped lines starting at baseline [y] and return the
     * baseline the next block may use.
     */
    private fun paragraph(
        value: String,
        p: Paint,
        x: Float,
        y: Float,
        maxWidth: Float,
        leading: Float,
        maxLines: Int = 40,
    ): Float {
        var baseline = y
        insightWrapLines(value, p, maxWidth, maxLines).forEach { line ->
            canvas.drawText(line, x, baseline, p)
            baseline += leading
        }
        return baseline
    }

    /** A label/value pair, the report's one repeating micro-layout. */
    private fun pair(label: String, value: String, x: Float, y: Float, maxWidth: Float, tint: Int) {
        text(label, x, y, paint(FOOT_SIZE, theme.muted, tracking = KICKER_TRACKING))
        val valuePaint = paint(BODY_SIZE, tint)
        val fitted = insightEllipsize(value, valuePaint, maxWidth) ?: return
        text(fitted, x, y + BODY_LEAD, valuePaint)
    }

    /**
     * Running header and footer.
     *
     * The cover opts out of the header deliberately: a cover that repeats the
     * document's name at the top and again in the middle reads as a template,
     * not as a title page.
     */
    private fun chrome(page: Int, headerRight: String?, header: Boolean = true) {
        if (header) {
            val headerBaseline = BtInsightsPdfPage.MARGIN + FOOT_SIZE
            text(doc.runningHeader, left, headerBaseline, paint(FOOT_SIZE, theme.muted))
            headerRight?.let {
                text(it, right, headerBaseline, paint(FOOT_SIZE, theme.muted, align = Paint.Align.RIGHT))
            }
            rule(left, BtInsightsPdfPage.MARGIN + BtInsightsPdfPage.HEADER - 8f, width, 0.6f, theme.border)
        }
        val footBaseline = BtInsightsPdfPage.HEIGHT - BtInsightsPdfPage.MARGIN + FOOT_LEAD
        text(doc.footerText, left, footBaseline, paint(FOOT_SIZE, theme.muted))
        text(
            doc.pageOf(page, doc.totalPages),
            right,
            footBaseline,
            paint(FOOT_SIZE, theme.muted, align = Paint.Align.RIGHT),
        )
    }

    // ── Page 1 · Cover ──────────────────────────────────────────────────────

    /**
     * The cover states what this is, for whom, as of when — and claims nothing
     * about performance.
     *
     * The study is explicit that a report cover may not carry a decorative
     * result ("+14,3 %" under the brand): a headline number chosen by the
     * exporter, on the page a reader trusts most, is advice by layout. So the
     * cover carries facts about the DOCUMENT, and the figures start on page 2.
     */
    fun drawCover() {
        paper()

        var y = 150f
        rule(left, y, 40f, 3f, theme.gold)
        y += 3f + 26f
        text(doc.footerText, left, y, paint(SUB_SIZE, theme.ink, bold = true, tracking = 0.04f))

        y += 42f
        text(doc.coverKicker, left, y, paint(FOOT_SIZE, theme.muted, tracking = KICKER_TRACKING))

        y += COVER_SIZE + 14f
        val coverPaint = paint(COVER_SIZE, theme.ink, bold = true)
        text(doc.coverLine1, left, y, coverPaint)
        y += COVER_LEAD
        text(doc.coverLine2, left, y, coverPaint)

        y += 40f
        text(doc.periodLabel, left, y, paint(SUB_SIZE, theme.ink))
        y += SUB_LEAD + 4f
        paragraph(doc.coverSubline, paint(BODY_SIZE, theme.muted), left, y, width * 0.78f, BODY_LEAD, 3)

        // The document block is anchored to the bottom, not to the flow above:
        // a cover statement that runs to two lines must not push the provenance
        // pairs into the footer.
        val privateBaseline = bottomLimit - 4f
        val gridBottom = privateBaseline - 26f
        val rowHeight = 34f
        val columnWidth = width / 2f
        val gridTop = gridBottom - rowHeight * 2f
        listOf(
            doc.createdAtLabel to doc.createdAtCaption,
            doc.dataAsOfLabel to doc.dataAsOfCaption,
            doc.scopeLabel to doc.scopeCaption,
            doc.documentKindLabel to doc.documentKindCaption,
        ).forEachIndexed { index, (label, value) ->
            val column = index % 2
            val row = index / 2
            pair(
                label = label,
                value = value,
                x = left + column * columnWidth,
                y = gridTop + row * rowHeight + FOOT_SIZE,
                maxWidth = columnWidth - 16f,
                tint = theme.ink,
            )
        }
        rule(left, privateBaseline - 16f, width, 0.6f, theme.border)
        text(doc.privateLabel, left, privateBaseline, paint(FOOT_SIZE, theme.muted))

        chrome(page = 1, headerRight = null, header = false)
    }

    // ── Page 2… · Summary and contents ──────────────────────────────────────

    /**
     * `Auf einen Blick` plus the numbered contents.
     *
     * The key figures appear on the FIRST contents page only, and only for
     * insights the user actually checked — a summary that quoted a figure from a
     * section the reader will not find later would be the report contradicting
     * its own table of contents.
     */
    fun drawContents(contentsIndex: Int, page: Int) {
        paper()
        chrome(page, headerRight = doc.summaryLabel)

        var y = BtInsightsPdfPage.MARGIN + BtInsightsPdfPage.HEADER + SUB_SIZE + 12f
        text(doc.summaryLabel, left, y, paint(SUB_SIZE, theme.ink, bold = true))
        text(doc.periodLabel, right, y, paint(BODY_SIZE, theme.muted, align = Paint.Align.RIGHT))
        y += 30f

        if (contentsIndex == 0) {
            text(doc.atAGlanceLabel, left, y, paint(FOOT_SIZE, theme.muted, tracking = KICKER_TRACKING))
            y += 22f
            text(doc.keyFiguresTitle, left, y + TITLE_SIZE * 0.2f, paint(TITLE_SIZE, theme.ink, bold = true))
            y += TITLE_LEAD + 14f

            val figures = doc.keyFigures.take(4)
            if (figures.isNotEmpty()) {
                val panelWidth = (width - 14f) / 2f
                val panelHeight = 64f
                figures.forEachIndexed { index, (label, value, direction) ->
                    val column = index % 2
                    val row = index / 2
                    val rect = RectF(
                        left + column * (panelWidth + 14f),
                        y + row * (panelHeight + 14f),
                        left + column * (panelWidth + 14f) + panelWidth,
                        y + row * (panelHeight + 14f) + panelHeight,
                    )
                    panel(rect)
                    text(
                        label,
                        rect.left + 12f,
                        rect.top + 18f,
                        paint(FOOT_SIZE, theme.muted, tracking = KICKER_TRACKING),
                    )
                    val metric = paint(METRIC_SIZE, insightTint(direction, theme), bold = true)
                    val fitted = insightEllipsize(value, metric, rect.width() - 24f)
                    if (fitted != null) {
                        text(fitted, rect.left + 12f, rect.top + 18f + METRIC_LEAD + 6f, metric)
                    }
                }
                val rows = (figures.size + 1) / 2
                y += rows * (panelHeight + 14f) + 4f
            }
            y = paragraph(doc.keyFiguresNote, paint(FOOT_SIZE, theme.muted), left, y, width, FOOT_LEAD, 2)
            y += 22f
        }

        text(doc.contentsLabel, left, y, paint(SUB_SIZE, theme.ink, bold = true))
        y += 22f

        val from = contentsIndex * BT_REPORT_SECTIONS_PER_CONTENTS
        val slice = doc.sections.drop(from).take(BT_REPORT_SECTIONS_PER_CONTENTS)
        val numberPaint = paint(BODY_SIZE, theme.muted, bold = true)
        val namePaint = paint(BODY_SIZE, theme.ink)
        val pagePaint = paint(BODY_SIZE, theme.muted, align = Paint.Align.RIGHT)
        slice.forEach { section ->
            if (y > bottomLimit - BODY_LEAD) return@forEach
            text(sectionNumber(section.section.number), left, y, numberPaint)
            val name = insightEllipsize(section.name, namePaint, width - 30f - 40f)
            if (name != null) text(name, left + 30f, y, namePaint)
            text(section.section.page.toString(), right, y, pagePaint)
            rule(left, y + 6f, width, 0.5f, theme.border)
            y += 24f
        }
    }

    // ── One page per section ────────────────────────────────────────────────

    /**
     * A section page: question, one primary fact, the chart, the exact facts,
     * the caption, and the as-of line.
     *
     * Every page stands alone — that is why the as-of line repeats here rather
     * than living once on the provenance page. A reader handed page 5 on its own
     * must still know what the numbers are as of.
     */
    fun drawSection(section: BtInsightReportSectionDoc, page: Int) {
        paper()
        chrome(page, headerRight = "${doc.sectionWord} ${sectionNumber(section.section.number)}")

        var y = BtInsightsPdfPage.MARGIN + BtInsightsPdfPage.HEADER + TITLE_SIZE + 8f
        text(section.name, left, y, paint(TITLE_SIZE, theme.ink, bold = true))
        y += 22f
        y = paragraph(section.question, paint(SUB_SIZE, theme.muted), left, y, width, SUB_LEAD, 2)

        y += 18f
        val headlinePaint = paint(TITLE_SIZE, insightTint(section.headlineValue, theme), bold = true)
        val headline = insightEllipsize(section.headline, headlinePaint, width) ?: section.headline
        text(headline, left, y, headlinePaint)
        y += 18f

        val field = RectF(left, y, left + width, y + BtInsightsPdfPage.CHART_HEIGHT)
        if (section.emptyTitle != null) {
            drawEmptyState(field, section.emptyTitle, section.emptyBody)
        } else {
            paintInsightChart(
                canvas = canvas,
                bounds = field,
                snapshot = section.snapshot,
                form = section.form,
                theme = theme,
                labels = doc.labels,
                labelSizePx = CHART_LABEL_SIZE,
                // One PostScript point is the unit here, so density is 1 by
                // definition — hairlines stay hairlines at any printer dpi.
                density = 1f,
            )
        }
        y = field.bottom + 22f

        val facts = section.facts.take(3)
        if (facts.isNotEmpty()) {
            val gap = 12f
            val panelWidth = (width - gap * (facts.size - 1)) / facts.size
            val panelHeight = 50f
            facts.forEachIndexed { index, (label, value) ->
                val rect = RectF(
                    left + index * (panelWidth + gap),
                    y,
                    left + index * (panelWidth + gap) + panelWidth,
                    y + panelHeight,
                )
                panel(rect)
                text(
                    label,
                    rect.left + 10f,
                    rect.top + 17f,
                    paint(FOOT_SIZE, theme.muted, tracking = KICKER_TRACKING),
                )
                val valuePaint = paint(SUB_SIZE, theme.ink, bold = true)
                insightEllipsize(value, valuePaint, rect.width() - 20f)?.let {
                    text(it, rect.left + 10f, rect.top + 17f + SUB_LEAD + 4f, valuePaint)
                }
            }
            y += panelHeight + 20f
        }

        paragraph(section.caption, paint(BODY_SIZE, theme.ink), left, y, width, BODY_LEAD, 3)
        text(section.asOfLine, left, bottomLimit - 2f, paint(FOOT_SIZE, theme.muted))
    }

    /**
     * The designed empty state, in the chart's place.
     *
     * An empty section still gets its whole page: absence is an answer
     * ("Absence is not 0,00 €"), and a report that silently dropped the question
     * would leave the reader unsure whether it was asked.
     */
    private fun drawEmptyState(field: RectF, title: String, body: String?) {
        panel(field)
        val titlePaint = paint(SUB_SIZE, theme.ink, bold = true)
        val bodyPaint = paint(BODY_SIZE, theme.muted)
        val maxWidth = field.width() - 96f
        val lines = if (body == null) emptyList() else insightWrapLines(body, bodyPaint, maxWidth, 4)
        val blockHeight = SUB_LEAD + lines.size * BODY_LEAD
        var baseline = field.centerY() - blockHeight / 2f + SUB_SIZE
        text(title, field.left + 48f, baseline, titlePaint)
        baseline += SUB_LEAD + 4f
        lines.forEach {
            canvas.drawText(it, field.left + 48f, baseline, bodyPaint)
            baseline += BODY_LEAD
        }
    }

    // ── Last page · Provenance ──────────────────────────────────────────────

    /**
     * Where the numbers came from, what was in scope, and what this document is
     * not.
     *
     * This page is the reason the report can be shown to a third party: it names
     * the calculation authority, the cut-off, the timezone and the currency, and
     * it states plainly that nothing here is advice.
     */
    fun drawProvenance(page: Int) {
        paper()
        chrome(page, headerRight = doc.provenanceTitle)

        var y = BtInsightsPdfPage.MARGIN + BtInsightsPdfPage.HEADER + TITLE_SIZE + 8f
        text(doc.provenanceTitle, left, y, paint(TITLE_SIZE, theme.ink, bold = true))
        y += 34f

        val columnWidth = width / 2f
        listOf(
            doc.createdAtLabel to doc.createdAtCaption,
            doc.dataAsOfLabel to doc.dataAsOfCaption,
            doc.timezoneLabel to doc.timezoneValue,
            doc.currencyLabel to doc.currencyValue,
        ).forEachIndexed { index, (label, value) ->
            val column = index % 2
            val row = index / 2
            pair(
                label = label,
                value = value,
                x = left + column * columnWidth,
                y = y + row * 36f,
                maxWidth = columnWidth - 16f,
                tint = theme.ink,
            )
        }
        y += 36f * 2f + 10f

        y = drawList(doc.settingsLabel, doc.settingsLines, left, y, width)
        y += 14f

        // Included / not included sit side by side: they are one comparison, and
        // stacking them puts a page-scroll between the two halves of it.
        val includedEnd = drawList(doc.includedLabel, doc.included, left, y, columnWidth - 12f)
        val notIncludedEnd = drawList(
            doc.notIncludedLabel,
            doc.notIncluded,
            left + columnWidth,
            y,
            columnWidth - 12f,
        )
        y = maxOf(includedEnd, notIncludedEnd) + 18f

        rule(left, y - 10f, width, 0.6f, theme.border)
        val notePaint = paint(FOOT_SIZE, theme.muted)
        doc.notes.forEach { note ->
            if (y > bottomLimit - FOOT_LEAD) return@forEach
            y = paragraph(note, notePaint, left, y, width, FOOT_LEAD, 3) + 6f
        }
    }

    private fun drawList(
        label: String,
        entries: List<String>,
        x: Float,
        y: Float,
        maxWidth: Float,
    ): Float {
        var baseline = y
        text(label, x, baseline, paint(FOOT_SIZE, theme.muted, tracking = KICKER_TRACKING))
        baseline += BODY_LEAD + 2f
        val entryPaint = paint(BODY_SIZE, theme.ink)
        entries.forEach { entry ->
            if (baseline > bottomLimit - BODY_LEAD) return@forEach
            baseline = paragraph("·  $entry", entryPaint, x, baseline, maxWidth, BODY_LEAD, 2)
        }
        return baseline
    }

    /** `01`, `02`, … — a section number is an identifier, so it keeps its width. */
    private fun sectionNumber(number: Int): String = String.format(Locale.ROOT, "%02d", number)
}
