package at.bettertrack.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import at.bettertrack.app.ui.charts.viz.VizDatum
import at.bettertrack.app.ui.charts.viz.VizRect
import at.bettertrack.app.ui.charts.viz.orderedMosaic
import at.bettertrack.app.ui.charts.viz.squarifiedTreemap

/**
 * The chart widgets' RASTER half: [Bitmap]s painted with [Canvas]/[Paint].
 *
 * ## Why bitmaps at all
 *
 * Glance emits RemoteViews, and RemoteViews has no path, arc or polyline — text,
 * boxes and images are the entire vocabulary. So every diagram is painted here,
 * in the app's process, at the widget's own pixel size, and crosses to the
 * launcher as an image. The DATA behind each chart (fractions, scaling,
 * folding) comes exclusively from `BtWidgetChartData.kt`, which is where the
 * unit tests live; these functions add geometry and anti-aliasing and decide
 * nothing about money.
 *
 * Sizes are capped by [btWidgetBitmapSize] before any of these run — see
 * [BT_WIDGET_BITMAP_MAX_EDGE_PX] for the binder arithmetic. Colours arrive as
 * resolved ARGB ints ([BtGlanceChartPalette], already day/night-resolved),
 * because a bitmap has one set of pixels and cannot carry a theme pair.
 */

/**
 * A donut ring: [fractions] of the whole, painted clockwise from 12 o'clock in
 * [colors] order — the same geometry as the app's `BtDonutChart` (stroked ring,
 * small surface gaps, flat colours), minus the draw-in animation a RemoteViews
 * image cannot have.
 *
 * Gaps between slices double as the CVD-safe secondary boundary, exactly as in
 * the app; a slice too thin to survive its gap keeps a hairline of itself
 * rather than vanishing. Degenerate input (no positive fraction) paints the
 * neutral [trackColor] ring so the widget shows a shape, not a hole.
 */
fun btWidgetDonutBitmap(
    fractions: List<Float>,
    colors: List<Int>,
    sizePx: Int,
    trackColor: Int,
    strokeFraction: Float = 0.16f,
): Bitmap {
    val size = sizePx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val stroke = (size * strokeFraction).coerceAtLeast(2f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    val inset = stroke / 2f
    val rect = RectF(inset, inset, size - inset, size - inset)

    val total = fractions.filter { it > 0f }.sum()
    if (total <= 0f || fractions.size != colors.size) {
        paint.color = trackColor
        canvas.drawArc(rect, 0f, 360f, false, paint)
        return bitmap
    }

    // A 2px arc-length gap between slices, in degrees at this radius.
    val radius = (size - stroke) / 2f
    val gapDeg = (2f / (2f * Math.PI.toFloat() * radius)) * 360f

    var start = -90f
    fractions.forEachIndexed { i, fraction ->
        if (fraction <= 0f) return@forEachIndexed
        val sweepFull = fraction / total * 360f
        val gap = if (sweepFull > gapDeg * 2f) gapDeg else 0f
        paint.color = colors[i]
        canvas.drawArc(rect, start + gap / 2f, (sweepFull - gap).coerceAtLeast(0.5f), false, paint)
        start += sweepFull
    }
    return bitmap
}

/**
 * A value line with a soft area fill under it — the widget rendition of the
 * app's portfolio chart. [normalized] is the min/max-scaled series from
 * [btWidgetSparkNormalize] (0 = bottom of the drawing box, 1 = top).
 *
 * Fewer than two points paints a transparent bitmap; the widget shows its
 * empty state instead of a lone dot pretending to be a trend.
 */
fun btWidgetLineBitmap(
    normalized: List<Float>,
    widthPx: Int,
    heightPx: Int,
    lineColor: Int,
    density: Float,
    // The gradient's TOP alpha; it fades to fully transparent at the bottom.
    fillAlpha: Int = 64,
    /**
     * The study's direct endpoint: a dot on the last value, ringed in
     * [endpointRingColor] (the card surface) so it reads as sitting ON the
     * line. 0 (transparent ring) skips the dot — small charts opt out.
     */
    endpointRingColor: Int = 0,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (normalized.size < 2) return bitmap
    val canvas = Canvas(bitmap)

    val stroke = (2f * density).coerceAtLeast(2f)
    val dotRadius = if (endpointRingColor != 0) (2.6f * density).coerceAtLeast(3f) else 0f
    // Inset so the stroke's caps, the endpoint dot and extremes are not clipped.
    val padY = maxOf(stroke, dotRadius + 1f * density)
    val padEnd = if (dotRadius > 0f) dotRadius + 1f * density else 0f
    val usableH = (h - 2 * padY).coerceAtLeast(1f)
    val stepX = (w - padEnd) / (normalized.size - 1)
    fun x(i: Int) = i * stepX
    fun y(v: Float) = padY + (1f - v.coerceIn(0f, 1f)) * usableH

    // Midpoint-quadratic smoothing (device review round 3): the polyline's
    // corners read as a staircase at real resolution; curving THROUGH the
    // midpoints keeps every data point on the path while the eye gets the
    // study's calm line. Geometry only — the data is untouched.
    val line = Path().apply {
        moveTo(x(0), y(normalized[0]))
        if (normalized.size == 2) {
            lineTo(x(1), y(normalized[1]))
        } else {
            var prevX = x(0)
            var prevY = y(normalized[0])
            for (i in 1 until normalized.size) {
                val cx = x(i)
                val cy = y(normalized[i])
                quadTo(prevX, prevY, (prevX + cx) / 2f, (prevY + cy) / 2f)
                prevX = cx
                prevY = cy
            }
            lineTo(prevX, prevY)
        }
    }

    // The study's soft fade: the wash starts at the line and dissolves to
    // nothing well above the bottom edge — never a flat opaque block.
    val area = Path(line).apply {
        lineTo(x(normalized.size - 1), h.toFloat())
        lineTo(0f, h.toFloat())
        close()
    }
    val topAlpha = fillAlpha.coerceIn(0, 255)
    canvas.drawPath(
        area,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                0f, padY, 0f, h.toFloat(),
                (topAlpha shl 24) or (lineColor and 0x00FFFFFF),
                lineColor and 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP,
            )
        },
    )
    canvas.drawPath(
        line,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = lineColor
        },
    )
    if (dotRadius > 0f) {
        val cx = x(normalized.size - 1)
        val cy = y(normalized.last())
        canvas.drawCircle(
            cx, cy, dotRadius + 1.2f * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = endpointRingColor },
        )
        canvas.drawCircle(
            cx, cy, dotRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor },
        )
    }
    return bitmap
}

/**
 * The Monthly-flow chart (round-2 restyle): per month ONE column around a true
 * zero line — inflow rising above it in the gain hue, outflow hanging below in
 * the loss hue — with localized short month labels underneath and the current
 * month's label in [highlightColor]. Heights come normalised from
 * [btWidgetCashflowBars] (one shared scale across both directions). A non-zero
 * bar keeps a minimum visible height: €3 of inflow is a short bar, not a
 * missing one.
 */
fun btWidgetFlowBarsBitmap(
    bars: List<BtWidgetBarPair>,
    labels: List<String?>,
    widthPx: Int,
    heightPx: Int,
    inflowColor: Int,
    outflowColor: Int,
    labelColor: Int,
    highlightColor: Int,
    baselineColor: Int,
    density: Float,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (bars.isEmpty()) return bitmap
    val canvas = Canvas(bitmap)

    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = 9f * density
        textAlign = Paint.Align.CENTER
    }
    val labelZone = labelPaint.textSize * 1.6f
    val plotBottom = (h - labelZone).coerceAtLeast(4f)
    // The zero line splits the plot by the window's shape: both halves share
    // one SCALE (the fractions are already normalised), so the halves' heights
    // just split the room evenly — a symmetric window reads symmetric.
    val zeroY = plotBottom / 2f
    val halfH = (zeroY - 2f * density).coerceAtLeast(1f)
    val minBar = (2f * density).coerceAtLeast(2f)

    val slotW = w.toFloat() / bars.size
    val barW = (slotW * 0.34f).coerceAtLeast(3f)
    val radius = (1.5f * density).coerceAtLeast(1f)
    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val gap = (1f * density).coerceAtLeast(1f)

    bars.forEachIndexed { i, bar ->
        val cx = i * slotW + slotW / 2f
        val left = cx - barW / 2f
        if (bar.inflowFrac > 0f) {
            val height = (bar.inflowFrac * halfH).coerceAtLeast(minBar)
            barPaint.color = inflowColor
            canvas.drawRoundRect(
                RectF(left, zeroY - gap - height, left + barW, zeroY - gap),
                radius, radius, barPaint,
            )
        }
        if (bar.outflowFrac > 0f) {
            val height = (bar.outflowFrac * halfH).coerceAtLeast(minBar)
            barPaint.color = outflowColor
            canvas.drawRoundRect(
                RectF(left, zeroY + gap, left + barW, zeroY + gap + height),
                radius, radius, barPaint,
            )
        }
        labels.getOrNull(i)?.let { label ->
            labelPaint.color = if (i == bars.size - 1) highlightColor else labelColor
            canvas.drawText(label, cx, h - labelPaint.textSize * 0.4f, labelPaint)
        }
    }

    // The zero line last, a 1px hairline the eye reads as the axis.
    canvas.drawRect(
        0f, zeroY - 0.5f * density, w.toFloat(), zeroY + 0.5f * density,
        Paint().apply { color = baselineColor },
    )
    return bitmap
}


// ── Tile forms: treemap, ordered mosaic, and the signed heatmap ──────────────

/**
 * One tile to paint. [fill] is a resolved ARGB int; [label] and [value] are
 * already localised strings, because a bitmap cannot resolve a resource and this
 * file decides nothing about money or language.
 */
data class BtWidgetTile(
    val label: String,
    val weight: Double,
    val fill: Int,
    val value: String,
    /** Ink for text drawn ON [fill]. Resolved by the caller, per tile. */
    val ink: Int,
)

/**
 * Paint [tiles] as an area chart — squarified when [squarified], otherwise the
 * ordered mosaic that keeps a stable reading order.
 *
 * The tiling itself comes from the app's own pure geometry
 * (`ui.charts.viz.squarifiedTreemap` / `orderedMosaic`), the same functions the
 * in-app charts use and the same ones the unit tests pin. That shared origin is
 * the point: a widget that laid its tiles out by a second, separately-written
 * algorithm would drift from the card it is supposed to mirror, and only one of
 * the two would have tests.
 *
 * Labels obey the study's rule rather than shrinking to fit: a tile that cannot
 * hold its name at the standard size is left unlabelled. In a widget there is no
 * tooltip to rescue it, so a 5px name would be decoration pretending to be
 * information.
 */
fun btWidgetTilesBitmap(
    tiles: List<BtWidgetTile>,
    widthPx: Int,
    heightPx: Int,
    squarified: Boolean,
    borderColor: Int,
    density: Float,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (tiles.isEmpty()) return bitmap

    val data = tiles.mapIndexed { index, tile ->
        VizDatum(key = index.toString(), label = tile.label, value = tile.weight)
    }
    val bounds = VizRect.of(w.toFloat(), h.toFloat())
    val laid = if (squarified) squarifiedTreemap(data, bounds) else orderedMosaic(data, bounds)

    val gap = 1.5f * density
    val radius = 2f * density
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = borderColor
    }
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        isFakeBoldText = true
    }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * density }

    laid.forEach { placed ->
        val tile = tiles[placed.key.toInt()]
        val rect = RectF(
            placed.rect.left + gap / 2f,
            placed.rect.top + gap / 2f,
            placed.rect.right - gap / 2f,
            placed.rect.bottom - gap / 2f,
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return@forEach
        fillPaint.color = tile.fill
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, edgePaint)

        namePaint.color = tile.ink
        valuePaint.color = tile.ink
        val pad = 4f * density
        val nameWidth = namePaint.measureText(tile.label)
        val fitsName = rect.width() >= nameWidth + pad * 2f &&
            rect.height() >= namePaint.textSize + pad * 2f
        if (!fitsName) return@forEach

        val valueWidth = valuePaint.measureText(tile.value)
        val fitsValue = tile.value.isNotEmpty() &&
            rect.width() >= valueWidth + pad * 2f &&
            rect.height() >= namePaint.textSize + valuePaint.textSize + pad * 2.5f

        if (fitsValue) {
            canvas.drawText(tile.label, rect.left + pad, rect.top + pad + namePaint.textSize, namePaint)
            canvas.drawText(
                tile.value,
                rect.left + pad,
                rect.top + pad + namePaint.textSize + valuePaint.textSize * 1.15f,
                valuePaint,
            )
        } else {
            canvas.drawText(tile.label, rect.left + pad, rect.top + pad + namePaint.textSize, namePaint)
        }
    }
    return bitmap
}

/**
 * Blend [hue] toward [ground] so a small move reads as a pale tile and a big one
 * as a saturated one.
 *
 * @param intensity 0 = barely tinted, 1 = the full directional hue.
 *
 * This is the heatmap's second channel, and it is deliberately the WEAKER one:
 * direction is carried by hue AND by the printed signed number on the tile, so a
 * reader who cannot separate the two hues still gets the answer. Intensity alone
 * never has to be decoded.
 */
fun btWidgetBlendToward(hue: Int, ground: Int, intensity: Float): Int {
    val t = intensity.coerceIn(0f, 1f)
    fun mix(shift: Int): Int {
        val a = (hue shr shift) and 0xFF
        val b = (ground shr shift) and 0xFF
        return (b + (a - b) * t).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/**
 * A single 100 % stacked bar: the one part-to-whole form that survives every
 * widget size, which is why it is the fallback when a tile form cannot fit.
 *
 * Shares are printed inside a segment only where they genuinely fit; the rest
 * stay silent and are named by the legend rows beside the image.
 */
fun btWidgetStackedBarBitmap(
    tiles: List<BtWidgetTile>,
    widthPx: Int,
    heightPx: Int,
    density: Float,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val total = tiles.sumOf { it.weight }
    if (total <= 0.0) return bitmap

    val gap = 1.5f * density
    val radius = 3f * density
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * density }

    var x = 0f
    tiles.forEachIndexed { index, tile ->
        val span = (tile.weight / total).toFloat() * w
        val right = if (index == tiles.lastIndex) w.toFloat() else x + span
        val rect = RectF(x + gap / 2f, 0f, right - gap / 2f, h.toFloat())
        if (rect.width() > 0f) {
            fill.color = tile.fill
            canvas.drawRoundRect(rect, radius, radius, fill)
            text.color = tile.ink
            val label = tile.value
            val tw = text.measureText(label)
            if (label.isNotEmpty() && rect.width() >= tw + 6f * density) {
                canvas.drawText(
                    label,
                    rect.centerX() - tw / 2f,
                    rect.centerY() + text.textSize / 3f,
                    text,
                )
            }
        }
        x = right
    }
    return bitmap
}
