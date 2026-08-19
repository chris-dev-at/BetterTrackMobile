package at.bettertrack.app.ui.insights

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import at.bettertrack.app.ui.charts.formatChartDate
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizLabels
import at.bettertrack.app.ui.charts.viz.VizDatum
import at.bettertrack.app.ui.charts.viz.VizRect
import at.bettertrack.app.ui.charts.viz.VizRole
import at.bettertrack.app.ui.charts.viz.orderedMosaic
import at.bettertrack.app.ui.charts.viz.packedBubbles
import at.bettertrack.app.ui.charts.viz.rankedBars
import at.bettertrack.app.ui.charts.viz.signedDotPlot
import at.bettertrack.app.ui.charts.viz.squarifiedTreemap
import at.bettertrack.app.ui.charts.viz.waffleCells
import at.bettertrack.app.ui.charts.viz.wholePercentShares
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The **one** chart painter both insight exports draw through.
 *
 * ## Why a second painter exists at all
 *
 * The in-app cards are Compose; a PDF page and a shared PNG are not. Neither
 * exporter can run a composition — one draws into `PdfDocument`'s canvas on a
 * background thread, the other into a `Bitmap` — so the marks have to be painted
 * with `android.graphics`. What must NOT be duplicated is the *geometry*: every
 * layout below comes from `ui.charts.viz.VizGeometry`, the same pure functions
 * the in-app charts and the widgets already use and the same ones
 * `VizGeometryTest` pins. A second, separately-written tiling would drift from
 * the card it is supposed to be an export of, and only one of the two would have
 * tests.
 *
 * The same reasoning makes this file shared between the two exporters rather
 * than written twice: a poster and a report page that disagreed about what the
 * user's allocation looks like would be a defect nobody discovers until both are
 * on a screen side by side.
 *
 * ## What it decides, and what it must not
 *
 * It decides space, colour roles and whether a label fits. It decides **nothing**
 * about money: every value arrives already computed, and every string arrives
 * through [BtInsightPaintLabels]. The one arithmetic performed here is turning a
 * value into a fraction of the drawn whole — the same aggregation the shipped
 * allocation card performs — and even that is routed through
 * [wholePercentShares] so a printed share column sums to exactly 100.
 */

/**
 * Paint [snapshot] as [form] into [bounds] on [canvas].
 *
 * @param labelSizePx a **floor**, not a target: chart text is never drawn
 *   smaller. A shrunk-to-fit label is unreadable after a messenger recompresses
 *   the PNG and invisible on paper, so a mark that cannot hold its name at this
 *   size stays unlabelled instead (the study's rule, and the same one
 *   `btWidgetTilesBitmap` follows).
 * @param density scales hairlines, dots and padding. Pass `1f` for a PDF page
 *   (the unit there is a PostScript point) and the bitmap's real density for a
 *   PNG.
 *
 * Dispatch order is deliberate and is not the caller's business: a snapshot that
 * carries a time series IS a time series whatever `form` says, and a paired
 * value/basis snapshot is always two tracks. Only when neither shape is present
 * does [form] get to choose.
 */
fun paintInsightChart(
    canvas: Canvas,
    bounds: RectF,
    snapshot: BtInsightSnapshot,
    form: BtVizForm,
    theme: BtInsightPaintTheme,
    labels: BtInsightPaintLabels,
    labelSizePx: Float,
    density: Float,
) {
    if (bounds.width() <= 1f || bounds.height() <= 1f) return
    // An empty snapshot has a designed empty state; the surface draws that
    // instead. Drawing an axis with nothing on it would be the failure the empty
    // state exists to avoid.
    if (snapshot.isEmpty) return

    val painter = InsightPainter(canvas, bounds, snapshot, theme, labels, labelSizePx, density)
    when {
        snapshot.series.isNotEmpty() -> painter.paintTimeSeries()
        snapshot.paired.isNotEmpty() -> painter.paintPairedTracks()
        snapshot.datums.isEmpty() -> Unit
        else -> when (form) {
            BtVizForm.TREEMAP -> painter.paintTiles(squarified = true)
            BtVizForm.MOSAIC -> painter.paintTiles(squarified = false)
            BtVizForm.STACKED_BAR -> painter.paintStackedBar()
            BtVizForm.RING -> painter.paintRing(gapped = true)
            BtVizForm.DONUT -> painter.paintRing(gapped = false)
            BtVizForm.WAFFLE -> painter.paintWaffle()
            BtVizForm.RANKED_BARS -> painter.paintRanked()
            BtVizForm.DOT_PLOT -> painter.paintDotPlot()
            BtVizForm.BUBBLES -> painter.paintBubbles()
            // AUTO is resolved before a doc is frozen (`BtInsightReportSectionDoc.form`
            // is documented as "never AUTO"), so reaching here means a caller bug.
            // Ranked bars are the honest fallback for every family — exact
            // comparison, no part-to-whole claim — and drawing them beats
            // returning an empty field the reader cannot distinguish from "no data".
            BtVizForm.AUTO -> painter.paintRanked()
        }
    }
}

// ---------------------------------------------------------------------------
// Shared text utilities — the two exporters use these too
// ---------------------------------------------------------------------------

/**
 * Break [text] into at most [maxLines] lines that each fit [maxWidth].
 *
 * Greedy by word, with the last line ellipsised when the text runs longer. A
 * word that cannot fit on a line of its own is broken by character rather than
 * allowed to bleed past the column: a PDF has no overflow and a bitmap has no
 * scroll, so anything that does not fit simply prints over its neighbour.
 */
internal fun insightWrapLines(
    text: String,
    paint: Paint,
    maxWidth: Float,
    maxLines: Int,
): List<String> {
    val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
    if (flat.isEmpty() || maxWidth <= 0f || maxLines <= 0) return emptyList()

    val out = ArrayList<String>(maxLines)
    var rest = flat
    while (rest.isNotEmpty() && out.size < maxLines) {
        if (paint.measureText(rest) <= maxWidth) {
            out += rest
            rest = ""
            break
        }
        if (out.size == maxLines - 1) {
            out += insightEllipsize(rest, paint, maxWidth) ?: break
            rest = ""
            break
        }
        var cut = rest.lastIndexOf(' ', startIndex = rest.length - 1)
        while (cut > 0 && paint.measureText(rest.substring(0, cut)) > maxWidth) {
            cut = rest.lastIndexOf(' ', startIndex = cut - 1)
        }
        if (cut <= 0) {
            // One unbreakable word wider than the column.
            var chars = rest.length
            while (chars > 1 && paint.measureText(rest.substring(0, chars)) > maxWidth) chars--
            out += rest.substring(0, chars)
            rest = rest.substring(chars).trimStart()
        } else {
            out += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
    }
    return out
}

/**
 * [text] shortened with an ellipsis until it fits [maxWidth], or `null` when
 * even a single character plus the ellipsis does not.
 *
 * Used for NAMES only. A clipped number is not a shorter number, it is a wrong
 * one, so amounts are dropped rather than ellipsised — see [InsightPainter].
 */
internal fun insightEllipsize(text: String, paint: Paint, maxWidth: Float): String? {
    if (text.isEmpty() || maxWidth <= 0f) return null
    if (paint.measureText(text) <= maxWidth) return text
    var end = text.length
    while (end > 1) {
        end--
        val candidate = text.substring(0, end).trimEnd() + "…"
        if (paint.measureText(candidate) <= maxWidth) return candidate
    }
    return null
}

/**
 * The baseline that centres [paint]'s text box vertically inside [rect].
 *
 * Centring by hand is the classic source of type that sits a pixel low on one
 * surface and a pixel high on the other; deriving it from the font metrics keeps
 * every band in both exporters optically identical.
 */
internal fun insightBaselineIn(rect: RectF, paint: Paint): Float {
    val metrics = paint.fontMetrics
    return rect.top + (rect.height() - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
}

/**
 * The ink a money direction earns: gain, loss, or plain [BtInsightPaintTheme.ink]
 * when there is no direction to carry.
 *
 * Zero is deliberately NOT green: "unchanged" is not a gain, and tinting it as
 * one would be the chart telling a story the number does not.
 */
internal fun insightTint(value: Double?, theme: BtInsightPaintTheme): Int = when {
    value == null || value == 0.0 -> theme.ink
    value > 0.0 -> theme.gain
    else -> theme.loss
}

/**
 * Relative luminance (WCAG) of an opaque ARGB colour, 0 (black) … 1 (white).
 *
 * This is what picks ink for text ON a filled mark. Hardcoding white would be
 * wrong on the pale end of every categorical ramp and on a user's own tag
 * colour, which the app lets them choose freely.
 */
internal fun insightLuminance(argb: Int): Double {
    fun channel(raw: Int): Double {
        val v = raw / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(Color.red(argb)) +
        0.7152 * channel(Color.green(argb)) +
        0.0722 * channel(Color.blue(argb))
}

// ---------------------------------------------------------------------------
// The painter
// ---------------------------------------------------------------------------

/**
 * One chart, one canvas, one set of resolved paints.
 *
 * Held as a class rather than a pile of functions with fourteen parameters each:
 * every form needs the same theme, the same label floor and the same "does this
 * fit" test, and threading them by hand is how two forms end up disagreeing
 * about their padding.
 */
private class InsightPainter(
    private val canvas: Canvas,
    private val bounds: RectF,
    private val snapshot: BtInsightSnapshot,
    private val theme: BtInsightPaintTheme,
    private val labels: BtInsightPaintLabels,
    labelSizePx: Float,
    private val density: Float,
) {
    /** The readable floor. No text on this canvas is ever smaller. */
    private val label = labelSizePx.coerceAtLeast(1f)
    private val pad = label * 0.5f
    private val hair = (0.8f * density).coerceAtLeast(0.4f)
    private val corner = 2f * density

    private val datums: List<VizDatum> = snapshot.datums

    /**
     * Whole-percent shares of the DRAWN set, largest-remainder.
     *
     * A part-to-whole form claims its marks are the whole, so its printed column
     * has to sum to 100 — hence [wholePercentShares] rather than a per-mark
     * division that rounds to 99. Absolute amounts are never derived from these;
     * they come from the values the server supplied.
     */
    private val partShares: List<Double> =
        wholePercentShares(datums.map { it.value }).map { it / 100.0 }

    private val shareByKey: Map<String, Double> =
        datums.mapIndexed { index, datum -> datum.key to (partShares.getOrNull(index) ?: 0.0) }
            .toMap()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hair
    }

    private fun textPaint(
        size: Float = label,
        color: Int = theme.ink,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size.coerceAtLeast(label)
        this.color = color
        textAlign = align
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        // Tabular figures: a column of amounts that shimmies as digits change is
        // unreadable as a column.
        fontFeatureSettings = "tnum"
    }

    // ── Colour ──────────────────────────────────────────────────────────────

    /**
     * The fill a datum earns.
     *
     * Order matters. Signed data is direction-only — a mover that took its tag's
     * colour would say "category" where the chart means "down". Cash and the
     * catch-all keep their fixed roles (they are not ranks). Only then does a
     * colour the DATA carries win over the categorical ramp, which is the whole
     * point of `VizDatum.colorArgb`: a tag the user painted green stays green
     * when it slips from third place to fourth.
     */
    private fun fillFor(datum: VizDatum): Int = when {
        snapshot.signed -> if (datum.value < 0.0) theme.loss else theme.gain
        datum.role == VizRole.Cash -> theme.cash
        datum.role == VizRole.Other -> theme.rest
        datum.colorArgb != null -> datum.colorArgb
        theme.series.isEmpty() -> theme.gold
        else -> {
            val size = theme.series.size
            theme.series[((datum.colorIndex % size) + size) % size]
        }
    }

    /** Ink for text drawn ON [fill], chosen by the fill's luminance. */
    private fun inkOn(fill: Int): Int =
        if (insightLuminance(fill) > 0.55) theme.inkOnPale else theme.inkOnFill

    // ── Text for a mark ─────────────────────────────────────────────────────

    /**
     * The value string a mark may print, or `null` when there is none to print.
     *
     * ## PRIVACY — this is where [BtInsightPaintLabels.showAmounts] bites
     *
     * When amounts are hidden the renderer prints a **share**, never a masked
     * euro string: the shared-image ruling removes balances structurally rather
     * than blanking them. Signed data has no honest share (a percentage of a
     * mixed-sign total is not a fact), so a hidden signed mark prints no number
     * at all and carries its meaning in the sign glyph and the geometry — which
     * is exactly what the transform promises to preserve.
     */
    private fun markText(datum: VizDatum): String? = when {
        // A percentage is not a balance, so it prints on the poster too: the
        // privacy transform removes euro amounts, and [BtInsightValue.Percent]
        // is explicitly one of the types it keeps. Routing these through
        // [BtInsightPaintLabels.amount] would stamp a € on a price movement.
        snapshot.datumUnit == BtInsightUnit.PERCENT -> labels.signedPercent(datum.value)
        snapshot.signed && labels.showAmounts -> labels.signedAmount(datum.value)
        snapshot.signed -> null
        !labels.showAmounts || labels.labels == BtVizLabels.SHARES -> shareText(datum)
        else -> labels.amount(datum.value)
    }

    private fun shareText(datum: VizDatum): String? {
        val fraction = shareByKey[datum.key]
            ?: snapshot.total.takeIf { it > 0.0 }?.let { abs(datum.value) / it }
            ?: return null
        return labels.share(fraction)
    }

    /**
     * The sign, drawn as a glyph.
     *
     * Colour alone may not carry direction — roughly one reader in twelve cannot
     * separate the two hues, and a photocopied report has no hues at all. The
     * signed formatters already prefix their own sign; this is what a mark uses
     * when its amount is hidden and the sign would otherwise live only in the ink.
     */
    private fun signGlyph(value: Double): String = if (value < 0.0) "−" else "+"

    // ── 1 · Time series ─────────────────────────────────────────────────────

    /**
     * A value curve: soft polyline, a wash under it that fades to nothing, an
     * endpoint dot, and the two endpoint facts at the baseline corners.
     *
     * A **single point draws no line.** One observation is an as-of fact, and
     * joining it to nothing — or worse, drawing a flat line across the field —
     * would fabricate a trend the data does not contain.
     */
    fun paintTimeSeries() {
        val cornerBand = label * 1.7f
        val plot = RectF(
            bounds.left,
            bounds.top + label * 0.9f,
            bounds.right,
            bounds.bottom - cornerBand,
        )
        if (plot.height() <= label || plot.width() <= label) return

        val all = snapshot.series + snapshot.compareSeries
        var lo = all.minOf { it.value }
        var hi = all.maxOf { it.value }
        if (hi - lo < 1e-9) {
            // A flat window still deserves a mid-height line rather than one
            // pinned to an edge by a divide-by-zero.
            hi += 1.0
            lo -= 1.0
        }
        val span = hi - lo
        fun yOf(value: Double): Float =
            plot.bottom - ((value - lo) / span).toFloat() * plot.height()

        val dotRadius = (2.8f * density).coerceAtLeast(1.6f)
        val padEnd = dotRadius + 2f * density

        if (snapshot.isSinglePoint) {
            paintSinglePoint(plot, yOf(snapshot.series.first().value), dotRadius)
            return
        }

        fun xOf(index: Int, count: Int): Float =
            plot.left + (plot.width() - padEnd) * index / (count - 1).coerceAtLeast(1)

        // The comparison curve first, so it sits behind: thin, muted, never gold.
        // It shares the main curve's axis — both series are the same unit, and a
        // second scale would let two portfolios look equal at different sizes.
        if (snapshot.compareSeries.size >= 2) {
            val comparePath = smoothPath(snapshot.compareSeries.size) { index ->
                xOf(index, snapshot.compareSeries.size) to yOf(snapshot.compareSeries[index].value)
            }
            canvas.drawPath(
                comparePath,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (1.1f * density).coerceAtLeast(0.6f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = theme.muted
                },
            )
        }

        val count = snapshot.series.size
        val line = smoothPath(count) { index -> xOf(index, count) to yOf(snapshot.series[index].value) }

        // The wash: gold at the line, fully transparent at the baseline. Built by
        // arithmetic on the theme's own gold so no colour is declared here.
        val area = Path(line).apply {
            lineTo(xOf(count - 1, count), plot.bottom)
            lineTo(plot.left, plot.bottom)
            close()
        }
        canvas.drawPath(
            area,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, plot.top, 0f, plot.bottom,
                    (SERIES_WASH_ALPHA shl 24) or (theme.gold and RGB_MASK),
                    theme.gold and RGB_MASK,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawPath(
            line,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (1.8f * density).coerceAtLeast(0.9f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = theme.gold
            },
        )

        val endX = xOf(count - 1, count)
        val endY = yOf(snapshot.series.last().value)
        fillPaint.color = theme.paper
        canvas.drawCircle(endX, endY, dotRadius + 1.2f * density, fillPaint)
        fillPaint.color = theme.gold
        canvas.drawCircle(endX, endY, dotRadius, fillPaint)

        paintSeriesCorners()
    }

    private fun paintSinglePoint(plot: RectF, y: Float, dotRadius: Float) {
        val point = snapshot.series.first()
        val cx = plot.centerX()
        fillPaint.color = theme.paper
        canvas.drawCircle(cx, y, dotRadius + 1.4f * density, fillPaint)
        fillPaint.color = theme.gold
        canvas.drawCircle(cx, y, dotRadius, fillPaint)

        val paint = textPaint(label, theme.ink, align = Paint.Align.CENTER)
        val date = formatChartDate(point.epochDay, 0L, Locale.getDefault())
        val value = if (labels.showAmounts) labels.amount(point.value) else null
        val text = listOfNotNull(date, value).joinToString("  ")
        if (paint.measureText(text) <= bounds.width()) {
            canvas.drawText(text, cx, y + dotRadius + label * 1.6f, paint)
        }
    }

    /**
     * The first and last fact of the window, at the corners of the baseline.
     *
     * With amounts hidden these degrade to **dates only**. This is the one place
     * `labels.share` is not the substitute: a point on a value curve is not a
     * share of anything, and inventing a denominator to satisfy the rule would
     * print a number that means nothing.
     */
    private fun paintSeriesCorners() {
        val first = snapshot.series.first()
        val last = snapshot.series.last()
        val spanDays = last.epochDay - first.epochDay
        val baseline = bounds.bottom - label * 0.35f
        val half = bounds.width() / 2f - pad

        val leftPaint = textPaint(label, theme.muted)
        cornerText(first, spanDays, leftPaint, half)?.let {
            canvas.drawText(it, bounds.left, baseline, leftPaint)
        }
        val rightPaint = textPaint(label, theme.ink, align = Paint.Align.RIGHT)
        cornerText(last, spanDays, rightPaint, half)?.let {
            canvas.drawText(it, bounds.right, baseline, rightPaint)
        }
    }

    private fun cornerText(
        point: BtInsightPoint,
        spanDays: Long,
        paint: Paint,
        maxWidth: Float,
    ): String? {
        val date = formatChartDate(point.epochDay, spanDays, Locale.getDefault())
        if (!labels.showAmounts) return date.takeIf { paint.measureText(it) <= maxWidth }
        val amount = labels.amount(point.value)
        val both = "$date  $amount"
        return when {
            paint.measureText(both) <= maxWidth -> both
            paint.measureText(amount) <= maxWidth -> amount
            paint.measureText(date) <= maxWidth -> date
            else -> null
        }
    }

    /**
     * Midpoint-quadratic smoothing: the curve passes THROUGH every midpoint and
     * bends around every data point, so the polyline loses its staircase without
     * the data moving. Geometry only — the same treatment the widget line uses.
     */
    private fun smoothPath(count: Int, at: (Int) -> Pair<Float, Float>): Path = Path().apply {
        val (x0, y0) = at(0)
        moveTo(x0, y0)
        if (count == 2) {
            val (x1, y1) = at(1)
            lineTo(x1, y1)
            return@apply
        }
        var prevX = x0
        var prevY = y0
        for (i in 1 until count) {
            val (cx, cy) = at(i)
            quadTo(prevX, prevY, (prevX + cx) / 2f, (prevY + cy) / 2f)
            prevX = cx
            prevY = cy
        }
        lineTo(prevX, prevY)
    }

    // ── 2 · Paired tracks (market value vs cost basis) ───────────────────────

    /**
     * Two tracks per row on ONE linear baseline: market value above in its
     * series colour, cost basis below in [BtInsightPaintTheme.muted].
     *
     * Never a part-to-whole form, and that is the point of the insight — value
     * and basis are two measurements of the same holding, not two slices of one
     * pie, and a 100 % bar would claim they add up to something. One shared
     * scale (the largest of either track, across every row) is what makes "the
     * top bar is longer" mean "it is worth more than it cost".
     */
    fun paintPairedTracks() {
        val rows = snapshot.paired
        val scale = rows.maxOf { max(abs(it.valueEur), abs(it.basisEur)) }
        if (scale <= 0.0) return

        val namePaint = textPaint(label, theme.ink)
        val valuePaint = textPaint(label, theme.ink, align = Paint.Align.RIGHT)
        val basisPaint = textPaint(label, theme.muted, align = Paint.Align.RIGHT)

        // The value column is measured, not guessed: an amount that does not fit
        // its column is the one thing a financial export may not do.
        val valueColumn = rows.maxOf { row ->
            max(
                valuePaint.measureText(pairedText(row.valueEur) ?: ""),
                basisPaint.measureText(pairedText(row.basisEur) ?: ""),
            )
        } + pad

        val minRow = label * 2.9f
        val visible = min(rows.size, max(1, (bounds.height() / minRow).toInt()))
        val rowHeight = min(bounds.height() / visible, label * 4.4f)
        val barHeight = (rowHeight - label * 1.5f) / 2f - hair
        if (barHeight <= hair) return

        val trackLeft = bounds.left
        val trackRight = bounds.right - valueColumn
        val trackWidth = trackRight - trackLeft
        if (trackWidth <= label) return

        var y = bounds.top
        rows.take(visible).forEach { row ->
            val name = insightEllipsize(row.label, namePaint, trackWidth - pad)
            if (name != null) canvas.drawText(name, trackLeft, y + label, namePaint)

            val top = y + label * 1.4f
            listOf(
                Triple(row.valueEur, seriesColor(row.colorIndex), valuePaint),
                Triple(row.basisEur, theme.muted, basisPaint),
            ).forEachIndexed { index, (amount, color, paint) ->
                val barTop = top + index * (barHeight + hair * 2f)
                val width = (abs(amount) / scale).toFloat() * trackWidth
                fillPaint.color = color
                canvas.drawRoundRect(
                    RectF(trackLeft, barTop, trackLeft + max(width, hair * 2f), barTop + barHeight),
                    corner, corner, fillPaint,
                )
                val bar = RectF(trackLeft, barTop, trackRight, barTop + barHeight)
                pairedText(amount)?.let { text ->
                    if (paint.measureText(text) <= valueColumn) {
                        canvas.drawText(text, bounds.right, insightBaselineIn(bar, paint), paint)
                    }
                }
            }
            y += rowHeight
        }
    }

    private fun pairedText(amount: Double): String? = when {
        labels.showAmounts && labels.labels != BtVizLabels.SHARES -> labels.amount(amount)
        snapshot.total > 0.0 -> labels.share(abs(amount) / snapshot.total)
        else -> null
    }

    private fun seriesColor(colorIndex: Int): Int {
        if (theme.series.isEmpty()) return theme.gold
        val size = theme.series.size
        return theme.series[((colorIndex % size) + size) % size]
    }

    // ── 3 · Area forms: treemap and ordered mosaic ───────────────────────────

    /**
     * Area tiles, either squarified (best concentration overview) or the ordered
     * mosaic (stable reading order). Both tilings are a true partition of
     * [bounds], so "area = value" is a promise the geometry keeps.
     */
    fun paintTiles(squarified: Boolean) {
        val box = VizRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val tiles = if (squarified) squarifiedTreemap(datums, box) else orderedMosaic(datums, box)
        if (tiles.isEmpty()) return
        val byKey = datums.associateBy { it.key }
        val gap = 1.4f * density

        tiles.forEach { tile ->
            val datum = byKey[tile.key] ?: return@forEach
            val rect = RectF(
                tile.rect.left + gap / 2f,
                tile.rect.top + gap / 2f,
                tile.rect.right - gap / 2f,
                tile.rect.bottom - gap / 2f,
            )
            if (rect.width() <= 0f || rect.height() <= 0f) return@forEach

            val fill = fillFor(datum)
            fillPaint.color = fill
            canvas.drawRoundRect(rect, corner, corner, fillPaint)
            strokePaint.color = theme.border
            canvas.drawRoundRect(rect, corner, corner, strokePaint)

            val ink = inkOn(fill)
            val namePaint = textPaint(label, ink, bold = true)
            val valuePaint = textPaint(label * 0.92f, ink)
            val inner = rect.width() - pad * 2f
            val name = insightEllipsize(datum.label, namePaint, inner)
            // A tile too small for its name stays anonymous. In an export there
            // is no tooltip to rescue a 5 pt label, so it would be decoration
            // pretending to be information.
            if (name == null || rect.height() < namePaint.textSize + pad * 2f) return@forEach

            val value = markText(datum)
            val roomForBoth = rect.height() >=
                namePaint.textSize + valuePaint.textSize * 1.35f + pad * 2f
            if (value != null && roomForBoth && valuePaint.measureText(value) <= inner) {
                canvas.drawText(name, rect.left + pad, rect.top + pad + namePaint.textSize, namePaint)
                canvas.drawText(
                    value,
                    rect.left + pad,
                    rect.top + pad + namePaint.textSize + valuePaint.textSize * 1.2f,
                    valuePaint,
                )
            } else {
                canvas.drawText(name, rect.left + pad, rect.top + pad + namePaint.textSize, namePaint)
            }
        }
    }

    // ── 4 · One 100 % bar ───────────────────────────────────────────────────

    /**
     * A single 100 % stacked bar plus a legend beneath it.
     *
     * The bar alone names nothing — segments carry a share where one fits and
     * stay silent otherwise — so the legend is not optional chrome here, it is
     * the half of the form that says which colour is which.
     */
    fun paintStackedBar() {
        val total = datums.sumOf { max(0.0, it.value) }
        if (total <= 0.0) return

        val barHeight = min(bounds.height() * 0.42f, label * 3.6f)
        val bar = RectF(bounds.left, bounds.top, bounds.right, bounds.top + barHeight)
        val gap = 1.4f * density
        var x = bar.left
        datums.forEachIndexed { index, datum ->
            val span = (max(0.0, datum.value) / total).toFloat() * bar.width()
            val right = if (index == datums.lastIndex) bar.right else x + span
            val rect = RectF(x + gap / 2f, bar.top, right - gap / 2f, bar.bottom)
            if (rect.width() > 0f) {
                val fill = fillFor(datum)
                fillPaint.color = fill
                canvas.drawRoundRect(rect, corner, corner, fillPaint)
                val text = shareText(datum)
                val paint = textPaint(label, inkOn(fill), align = Paint.Align.CENTER)
                if (text != null && paint.measureText(text) + pad * 2f <= rect.width()) {
                    canvas.drawText(text, rect.centerX(), insightBaselineIn(rect, paint), paint)
                }
            }
            x = right
        }

        val legend = RectF(bounds.left, bar.bottom + label, bounds.right, bounds.bottom)
        if (legend.height() > label) paintLegend(legend, columns = 2)
    }

    // ── 5 · Ring / donut ────────────────────────────────────────────────────

    /**
     * A segmented ring. `RING` keeps a gap between segments, `DONUT` does not.
     *
     * The gap is not decoration: it is the CVD-safe secondary boundary, so two
     * adjacent hues a reader cannot separate are still two shapes. A segment too
     * thin to survive its own gap keeps a hairline of itself rather than
     * vanishing — a 1 % slice that disappears is a chart that lies about its
     * denominator.
     */
    fun paintRing(gapped: Boolean) {
        val total = datums.sumOf { max(0.0, it.value) }
        if (total <= 0.0) return

        val sideBySide = bounds.width() > bounds.height() * 1.25f
        val ringBox: RectF
        val legendBox: RectF
        if (sideBySide) {
            val side = min(bounds.height(), bounds.width() * 0.52f)
            ringBox = RectF(bounds.left, bounds.centerY() - side / 2f, bounds.left + side, bounds.centerY() + side / 2f)
            legendBox = RectF(ringBox.right + label * 1.5f, bounds.top, bounds.right, bounds.bottom)
        } else {
            val side = min(bounds.width(), bounds.height() * 0.62f)
            ringBox = RectF(bounds.centerX() - side / 2f, bounds.top, bounds.centerX() + side / 2f, bounds.top + side)
            legendBox = RectF(bounds.left, ringBox.bottom + label, bounds.right, bounds.bottom)
        }

        val stroke = min(ringBox.width(), ringBox.height()) * 0.30f
        val inset = stroke / 2f
        val arc = RectF(
            ringBox.left + inset,
            ringBox.top + inset,
            ringBox.left + min(ringBox.width(), ringBox.height()) - inset,
            ringBox.top + min(ringBox.width(), ringBox.height()) - inset,
        )
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val radius = (min(ringBox.width(), ringBox.height()) - stroke) / 2f
        val gapDeg = if (gapped && radius > 0f) {
            (2f * density / (2f * Math.PI.toFloat() * radius)) * 360f
        } else {
            0f
        }

        var start = -90f
        datums.forEach { datum ->
            val value = max(0.0, datum.value)
            if (value <= 0.0) return@forEach
            val sweep = (value / total).toFloat() * 360f
            val gap = if (sweep > gapDeg * 2f) gapDeg else 0f
            ringPaint.color = fillFor(datum)
            canvas.drawArc(arc, start + gap / 2f, (sweep - gap).coerceAtLeast(0.4f), false, ringPaint)
            start += sweep
        }

        if (legendBox.height() > label && legendBox.width() > label * 4f) {
            paintLegend(legendBox, columns = if (sideBySide) 1 else 2)
        }
    }

    // ── 6 · Waffle ──────────────────────────────────────────────────────────

    /**
     * The 10 × 10 grid — one cell is one percent, allocated by
     * [waffleCells]'s largest-remainder split so the grid totals exactly 100.
     * Counting is the point of the form, so the cells stay square and the
     * categories stay contiguous.
     */
    fun paintWaffle() {
        val cells = waffleCells(datums, WAFFLE_CELLS)
        if (cells.isEmpty()) return
        val byKey = datums.associateBy { it.key }

        val sideBySide = bounds.width() > bounds.height() * 1.25f
        val gridSide = if (sideBySide) {
            min(bounds.height(), bounds.width() * 0.5f)
        } else {
            min(bounds.width(), bounds.height() * 0.66f)
        }
        val grid = RectF(bounds.left, bounds.top, bounds.left + gridSide, bounds.top + gridSide)
        val cell = gridSide / WAFFLE_SIDE
        val dot = cell - (cell * 0.22f)

        cells.forEachIndexed { index, key ->
            val datum = byKey[key] ?: return@forEachIndexed
            val row = index / WAFFLE_SIDE
            val column = index % WAFFLE_SIDE
            val left = grid.left + column * cell
            val top = grid.top + row * cell
            fillPaint.color = fillFor(datum)
            canvas.drawRoundRect(
                RectF(left, top, left + dot, top + dot),
                corner * 0.6f, corner * 0.6f, fillPaint,
            )
        }

        val legendBox = if (sideBySide) {
            RectF(grid.right + label * 1.5f, bounds.top, bounds.right, bounds.bottom)
        } else {
            RectF(bounds.left, grid.bottom + label, bounds.right, bounds.bottom)
        }
        if (legendBox.height() > label && legendBox.width() > label * 4f) {
            paintLegend(legendBox, columns = if (sideBySide) 1 else 2)
        }
    }

    // ── 7 · Ranked bars ─────────────────────────────────────────────────────

    /**
     * Horizontal bars on a **common** scale (the largest bar, not the total), so
     * length is comparable row to row. This is the form that survives a long
     * tail, which is why it is also the defensive fallback for `AUTO`.
     */
    fun paintRanked() {
        val rows = rankedBars(datums)
        if (rows.isEmpty()) return

        val namePaint = textPaint(label, theme.ink)
        val valuePaint = textPaint(label, theme.ink, align = Paint.Align.RIGHT)
        val valueColumn = rows.mapNotNull { markText(it.datum) }
            .maxOfOrNull { valuePaint.measureText(it) }
            ?.plus(pad) ?: (label * 0.9f)
        val nameColumn = min(bounds.width() * 0.34f, bounds.width() - valueColumn - label * 3f)
        if (nameColumn <= label) return

        val minRow = label * 1.9f
        val visible = min(rows.size, max(1, (bounds.height() / minRow).toInt()))
        val rowHeight = min(bounds.height() / visible, label * 3.2f)
        val barHeight = min(rowHeight * 0.5f, label * 1.3f)
        val trackLeft = bounds.left + nameColumn + pad
        val trackRight = bounds.right - valueColumn
        if (trackRight - trackLeft <= label) return

        var y = bounds.top
        rows.take(visible).forEach { row ->
            val rect = RectF(bounds.left, y, bounds.right, y + rowHeight)
            val bar = RectF(trackLeft, rect.centerY() - barHeight / 2f, trackRight, rect.centerY() + barHeight / 2f)

            insightEllipsize(row.datum.label, namePaint, nameColumn)?.let {
                canvas.drawText(it, bounds.left, insightBaselineIn(bar, namePaint), namePaint)
            }

            fillPaint.color = theme.border
            canvas.drawRoundRect(bar, corner, corner, fillPaint)
            fillPaint.color = fillFor(row.datum)
            canvas.drawRoundRect(
                RectF(bar.left, bar.top, bar.left + max(bar.width() * row.fraction, hair * 2f), bar.bottom),
                corner, corner, fillPaint,
            )

            // A hidden signed amount still has to say which way it went, so the
            // bare sign glyph stands in — colour may not carry direction alone.
            val text = markText(row.datum) ?: if (snapshot.signed) signGlyph(row.datum.value) else null
            if (text != null && valuePaint.measureText(text) <= valueColumn) {
                valuePaint.color = if (snapshot.signed) fillFor(row.datum) else theme.ink
                canvas.drawText(text, bounds.right, insightBaselineIn(bar, valuePaint), valuePaint)
            }
            y += rowHeight
        }
    }

    // ── 8 · Signed dot plot ─────────────────────────────────────────────────

    /**
     * Row-aligned lollipops on one symmetric zero axis: a gain and a loss of
     * equal size sit equally far from the centre, so the comparison the eye
     * makes is the comparison the numbers support.
     */
    fun paintDotPlot() {
        val rows = signedDotPlot(datums)
        if (rows.isEmpty()) return

        val namePaint = textPaint(label, theme.ink)
        val valuePaint = textPaint(label, theme.ink, align = Paint.Align.RIGHT)
        val valueColumn = rows.map { markText(it.datum) ?: signGlyph(it.datum.value) }
            .maxOfOrNull { valuePaint.measureText(it) }
            ?.plus(pad) ?: (label * 0.9f)
        val nameColumn = min(bounds.width() * 0.30f, bounds.width() - valueColumn - label * 3f)
        if (nameColumn <= label) return

        val trackLeft = bounds.left + nameColumn + pad
        val trackRight = bounds.right - valueColumn
        val trackWidth = trackRight - trackLeft
        if (trackWidth <= label) return
        val axisX = trackLeft + trackWidth / 2f

        val minRow = label * 1.8f
        val visible = min(rows.size, max(1, (bounds.height() / minRow).toInt()))
        val rowHeight = min(bounds.height() / visible, label * 3f)
        val plotBottom = bounds.top + rowHeight * visible

        strokePaint.color = theme.border
        canvas.drawLine(axisX, bounds.top, axisX, plotBottom, strokePaint)

        val dotRadius = min(label * 0.42f, rowHeight * 0.3f)
        var y = bounds.top
        rows.take(visible).forEach { row ->
            val rect = RectF(bounds.left, y, bounds.right, y + rowHeight)
            val centerY = rect.centerY()
            val dotX = trackLeft + row.axisFraction * trackWidth
            val color = fillFor(row.datum)

            insightEllipsize(row.datum.label, namePaint, nameColumn)?.let {
                canvas.drawText(it, bounds.left, insightBaselineIn(rect, namePaint), namePaint)
            }

            strokePaint.color = color
            strokePaint.strokeWidth = max(hair * 2f, 1.4f * density)
            canvas.drawLine(axisX, centerY, dotX, centerY, strokePaint)
            strokePaint.strokeWidth = hair
            fillPaint.color = color
            canvas.drawCircle(dotX, centerY, dotRadius, fillPaint)

            val text = markText(row.datum) ?: signGlyph(row.datum.value)
            if (valuePaint.measureText(text) <= valueColumn) {
                valuePaint.color = color
                canvas.drawText(text, bounds.right, insightBaselineIn(rect, valuePaint), valuePaint)
            }
            y += rowHeight
        }
    }

    // ── 9 · Packed bubbles ──────────────────────────────────────────────────

    /**
     * Deterministic circle packing — radius by √value, so **area** carries the
     * number. A bubble that cannot hold its ticker stays unlabelled and is named
     * by the legend the surface prints; shrinking the type to fit would trade the
     * one thing the form is good at (what dominates) for an illegible one.
     */
    fun paintBubbles() {
        val circles = packedBubbles(datums, VizRect(bounds.left, bounds.top, bounds.right, bounds.bottom))
        if (circles.isEmpty()) return
        val byKey = datums.associateBy { it.key }

        circles.forEach { circle ->
            val datum = byKey[circle.key] ?: return@forEach
            val fill = fillFor(datum)
            fillPaint.color = fill
            canvas.drawCircle(circle.cx, circle.cy, circle.r, fillPaint)

            val ink = inkOn(fill)
            val namePaint = textPaint(label, ink, bold = true, align = Paint.Align.CENTER)
            val valuePaint = textPaint(label * 0.92f, ink, align = Paint.Align.CENTER)
            val inner = circle.r * 1.5f
            val name = insightEllipsize(datum.label, namePaint, inner) ?: return@forEach
            if (circle.r * 2f < namePaint.textSize * 2.2f) return@forEach

            val value = markText(datum)
            val roomForBoth = circle.r * 2f >= (namePaint.textSize + valuePaint.textSize) * 2.2f
            if (value != null && roomForBoth && valuePaint.measureText(value) <= inner) {
                canvas.drawText(name, circle.cx, circle.cy - valuePaint.textSize * 0.15f, namePaint)
                canvas.drawText(value, circle.cx, circle.cy + valuePaint.textSize, valuePaint)
            } else {
                canvas.drawText(name, circle.cx, circle.cy + namePaint.textSize * 0.35f, namePaint)
            }
        }
    }

    // ── Legend ──────────────────────────────────────────────────────────────

    /**
     * Swatch · name · value rows, in [columns] columns.
     *
     * Only the rows that fit are drawn. The caller has already bucketed the long
     * tail (`vizEffectiveLimit`), so this bound normally does not bind; when it
     * does, a truncated legend is still better than one that runs off the page.
     */
    private fun paintLegend(rect: RectF, columns: Int) {
        if (datums.isEmpty()) return
        val rowHeight = label * 1.6f
        val perColumn = max(1, (rect.height() / rowHeight).toInt())
        val columnCount = max(1, min(columns, (datums.size + perColumn - 1) / perColumn))
        val columnWidth = rect.width() / columnCount
        val swatch = label * 0.7f

        val namePaint = textPaint(label, theme.ink)
        val valuePaint = textPaint(label, theme.muted, align = Paint.Align.RIGHT)

        datums.take(perColumn * columnCount).forEachIndexed { index, datum ->
            val column = index / perColumn
            val row = index % perColumn
            val left = rect.left + column * columnWidth
            val top = rect.top + row * rowHeight
            val line = RectF(left, top, left + columnWidth - label * 0.6f, top + rowHeight)

            fillPaint.color = fillFor(datum)
            canvas.drawRoundRect(
                RectF(left, line.centerY() - swatch / 2f, left + swatch, line.centerY() + swatch / 2f),
                corner * 0.8f, corner * 0.8f, fillPaint,
            )

            val value = markText(datum)
            val valueWidth = if (value == null) 0f else valuePaint.measureText(value) + pad
            val nameWidth = line.width() - swatch - pad - valueWidth
            insightEllipsize(datum.label, namePaint, nameWidth)?.let {
                canvas.drawText(it, left + swatch + pad, insightBaselineIn(line, namePaint), namePaint)
            }
            if (value != null) {
                canvas.drawText(value, line.right, insightBaselineIn(line, valuePaint), valuePaint)
            }
        }
    }
}

/** Alpha of the series wash where it meets the line; it fades to 0 at the baseline. */
private const val SERIES_WASH_ALPHA = 0x3D

/** Keeps the RGB channels of a theme colour while its alpha is replaced. */
private const val RGB_MASK = 0x00FFFFFF

private const val WAFFLE_CELLS = 100
private const val WAFFLE_SIDE = 10
