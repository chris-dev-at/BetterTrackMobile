package at.bettertrack.app.ui.insights

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import at.bettertrack.app.ui.charts.viz.VizRect
import java.io.File
import java.io.FileOutputStream

/**
 * The single-insight **image**: one opaque PNG, square or story.
 *
 * ## Why this is a renderer and not a screenshot
 *
 * Capturing the card would have been three lines. It would also have shipped the
 * app's chrome, the phone's density, the dark theme's near-black ground and —
 * fatally — whatever the screen was showing, including a balance the user did not
 * intend to publish. This path renders from a frozen [BtInsightImageDoc] whose
 * amounts were already removed by [insightHideAmounts], at a fixed 1080 px, into
 * a layout designed for a surface that is not a phone screen.
 *
 * ## Why every position comes from [insightImageLayout]
 *
 * The bands, safe areas and type sizes live in `InsightsImageSpec.kt`, which is
 * pure Kotlin and therefore unit-tested on the JVM: `InsightsImageSpecTest`
 * asserts that no band escapes its safe area and that no two bands overlap.
 * A poster is rendered once, off screen, into a file the user may publish, and
 * there is no layout inspector on the other side of that share sheet — so this
 * file measures nothing of its own and simply fills the rects it is handed.
 *
 * ## Opaque, and free of metadata
 *
 * The ground is painted first, edge to edge, with the paper colour forced opaque:
 * a transparent PNG composites against whatever the destination app decides,
 * which for a dark messenger means unreadable type. `Bitmap.compress(PNG)` writes
 * no EXIF, no XMP and no maker notes, and nothing here adds any — see
 * [BT_INSIGHT_IMAGE_FORBIDDEN_TOKENS] for the leaks a PNG *can* still carry,
 * because we would have had to write them.
 */

/** Render [doc] into a finished, opaque bitmap at its format's exact pixel size. */
fun renderInsightImage(doc: BtInsightImageDoc): Bitmap {
    val layout = insightImageLayout(doc.format)
    val theme = doc.theme
    val bitmap = Bitmap.createBitmap(
        doc.format.widthPx,
        doc.format.heightPx,
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)

    // The ground first, and opaque by construction: the alpha channel of the
    // theme's paper is dropped rather than trusted, because one translucent
    // token would turn the whole poster into a transparency the destination
    // composites however it likes.
    val ground = Color.rgb(Color.red(theme.paper), Color.green(theme.paper), Color.blue(theme.paper))
    canvas.drawRect(
        0f, 0f,
        doc.format.widthPx.toFloat(), doc.format.heightPx.toFloat(),
        Paint().apply { color = ground },
    )

    // 1080 px is three times a 360 dp column on both formats, so hairlines and
    // dots inside the chart land where the in-app card puts them.
    val density = doc.format.widthPx / NOMINAL_DP_WIDTH

    drawBrandRow(canvas, doc, layout)
    drawTitle(canvas, doc, layout)
    drawHeadline(canvas, doc, layout)
    drawScope(canvas, doc, layout)
    val chart = drawPrivacyPill(canvas, doc, layout, density)
    paintInsightChart(
        canvas = canvas,
        bounds = chart,
        snapshot = doc.snapshot,
        form = doc.form,
        theme = theme,
        labels = doc.labels,
        labelSizePx = layout.chartLabelSizePx,
        density = density,
    )
    drawCaption(canvas, doc, layout)
    drawFooter(canvas, doc, layout)
    return bitmap
}

/**
 * Write [bitmap] to [target] as a lossless PNG and return the file.
 *
 * Quality 100 is not a quality setting for PNG — the format is lossless and the
 * parameter is ignored — but it is the value that documents the intent, and it is
 * what the platform's own samples pass.
 */
fun writeInsightImage(target: File, bitmap: Bitmap): File {
    target.parentFile?.mkdirs()
    FileOutputStream(target).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return target
}

// ---------------------------------------------------------------------------
// Bands
// ---------------------------------------------------------------------------

/**
 * A short gold rule, the brand beside it, and the kicker underneath.
 *
 * The kicker sits in the channel BETWEEN the brand row and the title rather than
 * inside either band: both are sized for one line of their own type, and
 * squeezing a second string into the brand row would have meant shrinking the
 * brand below the size that survives a messenger's recompression.
 */
private fun drawBrandRow(canvas: Canvas, doc: BtInsightImageDoc, layout: BtInsightImageLayout) {
    val theme = doc.theme
    val row = layout.brandRow.toRectF()
    val brandPaint = textPaint(layout.brandSizePx, theme.ink, bold = true, tracking = 0.02f)
    val baseline = row.top + layout.brandSizePx

    val ruleWidth = layout.brandSizePx * 1.4f
    val ruleHeight = (layout.brandSizePx * 0.14f).coerceAtLeast(3f)
    canvas.drawRect(
        row.left,
        baseline - layout.brandSizePx * 0.32f - ruleHeight / 2f,
        row.left + ruleWidth,
        baseline - layout.brandSizePx * 0.32f + ruleHeight / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.gold },
    )
    val brandX = row.left + ruleWidth + layout.brandSizePx * 0.6f
    insightEllipsize(doc.brand, brandPaint, row.right - brandX)?.let {
        canvas.drawText(it, brandX, baseline, brandPaint)
    }

    // Small-caps LOOK, not a second typeface: the kicker arrives upper-cased by
    // the surface (which owns the locale) and earns its air from tracking.
    val kickerSize = layout.brandSizePx * 0.7f
    val kickerPaint = textPaint(kickerSize, theme.muted, tracking = 0.16f)
    insightEllipsize(doc.kicker, kickerPaint, row.width())?.let {
        canvas.drawText(it, row.left, row.bottom + kickerSize * 0.95f, kickerPaint)
    }
}

/**
 * The title: at most two lines, ellipsised beyond that.
 *
 * A poster's title is a sentence, and a third line would push it into the
 * headline's band. Two lines plus an ellipsis is an honest truncation; five lines
 * of overflow drawn over the next element is not.
 */
private fun drawTitle(canvas: Canvas, doc: BtInsightImageDoc, layout: BtInsightImageLayout) {
    val rect = layout.title.toRectF()
    val paint = textPaint(layout.titleSizePx, doc.theme.ink, bold = true)
    var baseline = rect.top + layout.titleSizePx * 0.9f
    insightWrapLines(doc.title, paint, rect.width(), TITLE_MAX_LINES).forEach { line ->
        canvas.drawText(line, rect.left, baseline, paint)
        baseline += layout.titleLeadingPx
    }
}

/** The one big number, tinted by direction only when there is a direction. */
private fun drawHeadline(canvas: Canvas, doc: BtInsightImageDoc, layout: BtInsightImageLayout) {
    val rect = layout.headline.toRectF()
    val paint = textPaint(layout.headlineSizePx, insightTint(doc.headlineValue, doc.theme), bold = true)
    val text = insightEllipsize(doc.headline, paint, rect.width()) ?: return
    canvas.drawText(text, rect.left, insightBaselineIn(rect, paint), paint)
}

private fun drawScope(canvas: Canvas, doc: BtInsightImageDoc, layout: BtInsightImageLayout) {
    val rect = layout.scopeRow.toRectF()
    val paint = textPaint(layout.captionSizePx, doc.theme.muted)
    insightEllipsize(doc.scopeLine, paint, rect.width())?.let {
        canvas.drawText(it, rect.left, insightBaselineIn(rect, paint), paint)
    }
}

/**
 * The `Beträge ausgeblendet` pill, and the chart bounds that make room for it.
 *
 * The pill belongs immediately under the chart, but the layout has no band for
 * it — and inventing one would break the spec test that owns the geometry. So the
 * strip is taken from the BOTTOM of the chart field: the chart is painted into
 * what remains, the pill sits in the reclaimed strip, and nothing lands outside a
 * rect `insightImageLayout` published. Costing the chart ~40 px is the cheaper
 * side of that trade — the pill is a privacy statement, and a viewer who cannot
 * see it cannot know that the shapes are complete and only the euros are gone.
 *
 * @return the bounds the chart may use.
 */
private fun drawPrivacyPill(
    canvas: Canvas,
    doc: BtInsightImageDoc,
    layout: BtInsightImageLayout,
    density: Float,
): RectF {
    val field = layout.chartField.toRectF()
    val text = doc.privacyPill ?: return field

    val paint = textPaint(layout.captionSizePx * 0.86f, doc.theme.muted)
    val height = paint.textSize * 1.9f
    val padding = paint.textSize * 0.8f
    val dot = paint.textSize * 0.34f
    val pillWidth = padding * 2f + dot * 2f + paint.textSize * 0.5f + paint.measureText(text)

    val pill = RectF(
        field.left,
        field.bottom - height,
        (field.left + pillWidth).coerceAtMost(field.right),
        field.bottom,
    )
    canvas.drawRoundRect(
        pill, height / 2f, height / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (1.2f * density).coerceAtLeast(1f)
            color = doc.theme.border
        },
    )
    canvas.drawCircle(
        pill.left + padding + dot,
        pill.centerY(),
        dot,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = doc.theme.muted },
    )
    val textX = pill.left + padding + dot * 2f + paint.textSize * 0.5f
    insightEllipsize(text, paint, pill.right - textX - padding)?.let {
        canvas.drawText(it, textX, insightBaselineIn(pill, paint), paint)
    }

    return RectF(field.left, field.top, field.right, pill.top - paint.textSize * 0.6f)
}

private fun drawCaption(canvas: Canvas, doc: BtInsightImageDoc, layout: BtInsightImageLayout) {
    val rect = layout.caption.toRectF()
    val paint = textPaint(layout.captionSizePx, doc.theme.muted)
    val maxLines = (rect.height() / layout.captionLeadingPx).toInt().coerceAtLeast(1)
    var baseline = rect.top + layout.captionSizePx
    insightWrapLines(doc.caption, paint, rect.width(), maxLines).forEach { line ->
        canvas.drawText(line, rect.left, baseline, paint)
        baseline += layout.captionLeadingPx
    }
}

private fun drawFooter(canvas: Canvas, doc: BtInsightImageDoc, layout: BtInsightImageLayout) {
    val rect = layout.footer.toRectF()
    val leftPaint = textPaint(layout.footerSizePx, doc.theme.muted)
    val rightPaint = textPaint(layout.footerSizePx, doc.theme.muted, align = Paint.Align.RIGHT)
    val baseline = insightBaselineIn(rect, leftPaint)
    // The two halves share one band, so the left one yields when they collide —
    // a brand line that overran the date would read as a broken template.
    val rightWidth = rightPaint.measureText(doc.footerRight)
    insightEllipsize(doc.footerLeft, leftPaint, rect.width() - rightWidth - layout.footerSizePx)?.let {
        canvas.drawText(it, rect.left, baseline, leftPaint)
    }
    canvas.drawText(doc.footerRight, rect.right, baseline, rightPaint)
}

// ---------------------------------------------------------------------------
// Small shared bits
// ---------------------------------------------------------------------------

private fun textPaint(
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
    fontFeatureSettings = "tnum"
}

/** The layout speaks in the pure [VizRect]; the canvas speaks in [RectF]. */
private fun VizRect.toRectF(): RectF = RectF(left, top, right, bottom)

/** The dp column both formats are three times as wide as. */
private const val NOMINAL_DP_WIDTH = 360f

private const val TITLE_MAX_LINES = 2
