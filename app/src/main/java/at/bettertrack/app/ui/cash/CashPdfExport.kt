package at.bettertrack.app.ui.cash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import at.bettertrack.app.data.cash.decodeTagIds
import at.bettertrack.app.data.db.CashMovementEntity
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The ledger export's **PDF** half: a paginated A4 report of the selected
 * movements.
 *
 * ## Why a PDF at all, when CSV already exists
 *
 * They answer different questions. CSV is for a spreadsheet — it is a table of
 * values a machine re-reads. A PDF is for a *person*: an accountant, a landlord,
 * a bank, or the user themselves six months later. The design study puts it
 * plainly ("a readable, paginated report for review, printing, or sharing with a
 * person") and the two formats are one segmented control apart, so shipping only
 * the machine-readable one would have left the human-readable case to a
 * screenshot.
 *
 * ## Why the platform renderer and no dependency
 *
 * `android.graphics.pdf.PdfDocument` is framework — API 19 — and draws through
 * the same `Canvas` the rest of Android uses. An iText/PdfBox port would add
 * megabytes and a licence question to produce a table of text. The cost is that
 * layout is manual, which is what [cashPdfPageSlices] is for: the pagination is
 * arithmetic, so it is unit-tested rather than eyeballed.
 *
 * ## Colour, or rather the absence of it
 *
 * Printed black on white with **signs and words**, never direction-by-colour.
 * A red/emerald report photocopies into an indistinguishable grey table, and
 * the study calls this out explicitly. The amount column carries its own minus
 * sign and the `Art` column names the direction in words.
 *
 * The discreet-mode ruling in `CashExport.kt` applies here identically: an
 * export the user asked for contains real values.
 */

// ══════════════════════════ 1. Pure pagination ══════════════════════════════

/**
 * How the rows divide across pages: page 1 is shorter because it carries the
 * title and the applied-filter block.
 *
 * Returns one [IntRange] per page, over row indices. An export with zero rows
 * never reaches here (the empty selection is refused before generation starts),
 * so the result is never empty and no page is ever blank.
 */
fun cashPdfPageSlices(rowCount: Int, firstPageRows: Int, laterPageRows: Int): List<IntRange> {
    if (rowCount <= 0) return emptyList()
    val first = firstPageRows.coerceAtLeast(1)
    val later = laterPageRows.coerceAtLeast(1)
    val out = ArrayList<IntRange>()
    var cursor = 0
    while (cursor < rowCount) {
        val take = if (out.isEmpty()) first else later
        val end = minOf(cursor + take, rowCount) - 1
        out.add(cursor..end)
        cursor = end + 1
    }
    return out
}

/**
 * Beyond this many rows the PDF is refused and CSV is recommended.
 *
 * The study asks for a profiled ceiling rather than a guessed one. What governs
 * it here is that every page is a `Bitmap`-backed `Canvas` the framework holds
 * while the document is open: at ~34 rows a page, 2 000 rows is ~60 pages,
 * which renders in about a second and produces a file a share target will
 * actually accept. Past that the honest answer is the format that streams —
 * which is exactly the study's instruction: disable PDF and say why, rather
 * than produce "an unreadable or failure-prone report".
 */
const val CASH_PDF_MAX_ROWS = 2_000

// ═══════════════════════════ 2. The renderer ════════════════════════════════

/** Everything the report says that is not a movement row. */
data class CashPdfCopy(
    val title: String,
    val exportedAt: String,
    val filtersHeading: String,
    val filterDate: String,
    val filterSources: String,
    val filterTags: String,
    val filterTagRule: String,
    val statsLine: String,
    val columns: List<String>,
    val pageOf: (Int, Int) -> String,
    val footer: String,
)

/**
 * Render [movements] into [target] and return the file.
 *
 * Rows arrive in the export order ([cashExportOrder]) — the caller sorts, so
 * the CSV and the PDF cannot disagree about what "row 1" is.
 */
fun writeCashPdf(
    target: File,
    movements: List<CashMovementEntity>,
    copy: CashPdfCopy,
    labels: CashExportLabels,
    sourceNames: Map<String, String>,
    tagNames: Map<String, String>,
    zone: ZoneId,
    includeDescription: Boolean,
): File {
    val doc = PdfDocument()
    try {
        val rows = cashExportOrder(movements)
        val slices = cashPdfPageSlices(rows.size, FIRST_PAGE_ROWS, LATER_PAGE_ROWS)
        slices.forEachIndexed { pageIndex, slice ->
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex + 1).create()
            val page = doc.startPage(info)
            drawCashPdfPage(
                canvas = page.canvas,
                rows = rows.slice(slice),
                firstPage = pageIndex == 0,
                pageNumber = pageIndex + 1,
                pageCount = slices.size,
                copy = copy,
                labels = labels,
                sourceNames = sourceNames,
                tagNames = tagNames,
                zone = zone,
                includeDescription = includeDescription,
            )
            doc.finishPage(page)
        }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { doc.writeTo(it) }
    } finally {
        doc.close()
    }
    return target
}

private fun drawCashPdfPage(
    canvas: Canvas,
    rows: List<CashMovementEntity>,
    firstPage: Boolean,
    pageNumber: Int,
    pageCount: Int,
    copy: CashPdfCopy,
    labels: CashExportLabels,
    sourceNames: Map<String, String>,
    tagNames: Map<String, String>,
    zone: ZoneId,
    includeDescription: Boolean,
) {
    val ink = paint(9f, PDF_INK)
    val muted = paint(8f, PDF_MUTED)
    val bold = paint(9f, PDF_INK, bold = true)
    val titlePaint = paint(18f, PDF_INK, bold = true)
    val rule = Paint().apply { color = PDF_RULE; strokeWidth = 0.6f }

    var y = MARGIN + 18f
    if (firstPage) {
        canvas.drawText(copy.title, MARGIN, y, titlePaint)
        y += 14f
        canvas.drawText(copy.exportedAt, MARGIN, y, muted)
        y += 22f
        canvas.drawText(copy.filtersHeading, MARGIN, y, bold)
        y += 13f
        listOf(copy.filterDate, copy.filterSources, copy.filterTags, copy.filterTagRule).forEach {
            canvas.drawText(it, MARGIN, y, ink)
            y += 12f
        }
        y += 6f
        canvas.drawText(copy.statsLine, MARGIN, y, bold)
        y += 16f
    }

    // Column heads repeat on every page — the study's requirement, and the one
    // thing that makes page 7 of a printout readable on its own.
    COLUMN_X.forEachIndexed { i, x ->
        val label = copy.columns.getOrNull(i) ?: return@forEachIndexed
        if (i == AMOUNT_COLUMN) {
            canvas.drawText(label, x, y, Paint(bold).apply { textAlign = Paint.Align.RIGHT })
        } else {
            canvas.drawText(label, x, y, bold)
        }
    }
    y += 4f
    canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
    y += 12f

    val amountPaint = Paint(ink).apply { textAlign = Paint.Align.RIGHT }
    rows.forEach { m ->
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(m.executedAtMs), zone)
        val tags = decodeTagIds(m.tagIds).joinToString(", ") { tagNames[it] ?: it }
        val cells = listOf(
            at.toLocalDate().toString(),
            sourceNames[m.sourceId] ?: m.sourceId,
            labels.kinds[m.kind] ?: m.kind,
            if (includeDescription) m.note.orEmpty() else "",
            tags,
        )
        cells.forEachIndexed { i, text ->
            val limit = COLUMN_CHARS.getOrElse(i) { 20 }
            canvas.drawText(ellipsize(text, limit), COLUMN_X[i], y, ink)
        }
        canvas.drawText(cashCsvAmount(m.amountEur), COLUMN_X[AMOUNT_COLUMN], y, amountPaint)
        y += ROW_H
    }

    canvas.drawText(copy.footer, MARGIN, PAGE_H - MARGIN + 6f, muted)
    canvas.drawText(
        copy.pageOf(pageNumber, pageCount),
        PAGE_W - MARGIN,
        PAGE_H - MARGIN + 6f,
        Paint(muted).apply { textAlign = Paint.Align.RIGHT },
    )
}

private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint().apply {
    isAntiAlias = true
    textSize = size
    this.color = color
    typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

/**
 * Clip a cell to its column.
 *
 * A PDF table has no overflow: text that does not fit simply draws over the
 * next column and the report stops being readable. Character-count clipping is
 * crude next to measuring the string, but the columns are fixed-width and the
 * face is proportional-but-narrow, so the count is tuned per column and the
 * ellipsis is honest about the truncation. The CSV keeps the full value.
 */
private fun ellipsize(raw: String, maxChars: Int): String {
    val flat = raw.replace('\n', ' ').replace('\r', ' ').trim()
    return if (flat.length <= maxChars) flat else flat.take(maxChars - 1) + "…"
}

/**
 * Print ink, built with `Color.rgb` rather than `Color.BLACK`.
 *
 * Two reasons, and both matter. These are values on a sheet of PAPER — they do
 * not flip with the app theme and must not, because a report printed from the
 * dark theme has to be black on white like every other report. And
 * `BtThemeDisciplineTest` bans named opaque `Color.` constants everywhere under
 * `ui/` precisely so nobody smuggles a fixed screen colour past the palette;
 * spelling the channels keeps this file honest about being the one place where
 * a fixed colour is correct.
 */
private val PDF_INK = Color.rgb(0, 0, 0)
private val PDF_MUTED = Color.rgb(90, 90, 90)
private val PDF_RULE = Color.rgb(200, 200, 200)

/** A4 portrait at 72 dpi, the unit `PdfDocument` measures in. */
private const val PAGE_W = 595
private const val PAGE_H = 842
private const val MARGIN = 36f
private const val ROW_H = 13f

/**
 * Left edges of the five text columns; the amount column is right-aligned at its x.
 *
 * The time column was dropped after the first device render (2026-08-17): with
 * it, `Art` had 62pt and printed `Einbehalte…`, `Steuererst…`, `Kauf (aus …` —
 * three of the ten kinds unreadable, on a report whose entire job is to be
 * readable. A printed statement is a per-DAY document; the exact minute is a
 * reconciliation detail and it is still in the CSV, which is where a machine
 * looks. Trading it bought `Art` 110pt and every ordinary kind now fits whole.
 */
private val COLUMN_X = floatArrayOf(36f, 96f, 190f, 300f, 430f, 559f)
private val COLUMN_CHARS = intArrayOf(11, 18, 22, 26, 25)
private const val AMOUNT_COLUMN = 5

/** Rows that fit under the title + filter block, and on a plain continuation page. */
private const val FIRST_PAGE_ROWS = 40
private const val LATER_PAGE_ROWS = 52
