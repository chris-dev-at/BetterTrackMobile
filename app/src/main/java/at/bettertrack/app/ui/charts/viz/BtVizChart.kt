package at.bettertrack.app.ui.charts.viz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The `Darstellung` renderers — seven alternative chart forms behind one entry
 * point, [BtVizChart].
 *
 * ## What this component is and is not
 *
 * It is **presentation only**. Every number it draws arrived already computed;
 * the forms rearrange space and print what they are handed. Reduction to a
 * top-N (and therefore the `Andere`/`Weitere` bucket) happens *before* the call,
 * in the surface that owns the vocabulary — this file never invents a label and
 * never sums money.
 *
 * ## Where the legend lives
 *
 * Two kinds of form appear here and they treat the legend oppositely:
 *
 *  - **Area and part-to-whole forms** (treemap, mosaic, stacked bar, ring,
 *    donut, waffle) draw a mark and rely on the caller's attached rows for exact
 *    values. [vizFormHasOwnRows] returns `false` for these.
 *  - **Row forms** (ranked bars, dot plot) *are* the rows — name, bar and amount
 *    already sit on one line. A separate legend under them would print every
 *    value twice, so [vizFormHasOwnRows] returns `true` and the caller hides its
 *    own list.
 *
 * Getting this wrong is the difference between the study's clean specimens and
 * a card that says everything twice, so it is a function rather than a habit.
 */

/** How this surface turns a value into text. Supplied by the caller so masking, currency and locale stay in one place. */
@Immutable
class BtVizFormat(
    /** Absolute money, already discreet-masked where the surface requires it. */
    val amount: (Double) -> String,
    /** A fraction of the whole (`0.42`) as a printed share (`"42 %"`). */
    val share: (Double) -> String,
)

/** True when [form] prints its own name/value rows and the caller must not add a legend. */
fun vizFormHasOwnRows(form: BtVizForm): Boolean =
    form == BtVizForm.RANKED_BARS || form == BtVizForm.DOT_PLOT

/**
 * The fill a datum takes.
 *
 * Precedence, highest first: the money direction for signed families, then a
 * colour the data itself carries ([VizDatum.colorArgb] — a user's tag colour),
 * then the fixed neutral roles, then the categorical ramp by stable rank.
 */
@Composable
fun vizFill(datum: VizDatum, signed: Boolean): Color {
    val bt = BtTheme.colors
    if (signed) return if (datum.value < 0.0) bt.loss else bt.gain
    datum.colorArgb?.let { if (datum.role == VizRole.Data) return Color(it) }
    return when (datum.role) {
        VizRole.Cash -> bt.chartCash
        VizRole.Other -> bt.chartRest
        VizRole.Data -> bt.chartSeries[datum.colorIndex.coerceAtLeast(0) % bt.chartSeries.size]
    }
}

/** The canvas height a fixed-geometry form gets. Row forms size to their content instead. */
fun vizCanvasHeight(form: BtVizForm, canvas: BtVizCanvas): Dp = when (canvas) {
    BtVizCanvas.APP_FULL -> when (form) {
        BtVizForm.STACKED_BAR -> 44.dp
        BtVizForm.RING, BtVizForm.DONUT -> 184.dp
        BtVizForm.WAFFLE -> 200.dp
        else -> 240.dp
    }
    BtVizCanvas.APP_COMPACT -> when (form) {
        BtVizForm.STACKED_BAR -> 32.dp
        BtVizForm.RING, BtVizForm.DONUT -> 120.dp
        else -> 120.dp
    }
    // Widget canvases are painted as bitmaps elsewhere; these are the in-app
    // exact-size previews the widget builder shows.
    BtVizCanvas.WIDGET_SMALL -> 190.dp
    BtVizCanvas.WIDGET_WIDE -> 190.dp
}

/**
 * Draw [items] as [form].
 *
 * @param form already resolved — pass [vizResolveForm]'s output, never `AUTO`.
 * @param signed true for the movers family, where colour repeats the sign and
 *   part-to-whole geometry is meaningless.
 * @param selectedKey the one selected mark, or null. Selection adds a gold
 *   keyline and never moves geometry (§ "Selection and interaction").
 * @param thumbnail draw a shape SWATCH rather than a chart. Used only by the
 *   `Darstellung` picker, where each option sits in a 96dp box: at that width a
 *   ranked-bar row has no room for its name column, and rendering one anyway
 *   produces two lines of clipped text instead of a recognisable silhouette.
 *   A swatch drops the text and keeps the geometry, which is the one thing the
 *   picker is actually asking the user to choose between.
 */
@Composable
fun BtVizChart(
    items: List<VizDatum>,
    form: BtVizForm,
    canvas: BtVizCanvas,
    format: BtVizFormat,
    emptyText: String,
    modifier: Modifier = Modifier,
    labels: BtVizLabels = BtVizLabels.AUTO,
    signed: Boolean = false,
    selectedKey: String? = null,
    onSelect: (String?) -> Unit = {},
    thumbnail: Boolean = false,
) {
    val drawable = remember(items, signed) {
        if (signed) items else items.filter { it.value > 0.0 }
    }
    if (drawable.isEmpty()) {
        VizEmpty(form = form, canvas = canvas, text = emptyText, modifier = modifier)
        return
    }
    val total = remember(drawable) { drawable.sumOf { if (signed) 0.0 else it.value } }

    when (form) {
        BtVizForm.TREEMAP, BtVizForm.MOSAIC -> VizAreaChart(
            items = drawable,
            total = total,
            squarified = form == BtVizForm.TREEMAP,
            canvas = canvas,
            format = format,
            labels = labels,
            selectedKey = selectedKey,
            onSelect = onSelect,
            modifier = modifier.fillMaxWidth().height(vizCanvasHeight(form, canvas)),
        )

        BtVizForm.STACKED_BAR -> VizStackedBar(
            items = drawable,
            total = total,
            format = format,
            compact = canvas != BtVizCanvas.APP_FULL,
            selectedKey = selectedKey,
            onSelect = onSelect,
            modifier = modifier.fillMaxWidth().height(vizCanvasHeight(form, canvas)),
        )

        BtVizForm.RING, BtVizForm.DONUT -> VizRing(
            items = drawable,
            total = total,
            segmented = form == BtVizForm.RING,
            format = format,
            selectedKey = selectedKey,
            modifier = modifier.fillMaxWidth().height(vizCanvasHeight(form, canvas)),
        )

        BtVizForm.WAFFLE -> VizWaffle(
            items = drawable,
            selectedKey = selectedKey,
            onSelect = onSelect,
            modifier = modifier.fillMaxWidth().height(vizCanvasHeight(form, canvas)),
        )

        BtVizForm.RANKED_BARS -> VizRankedBars(
            items = drawable,
            total = total,
            format = format,
            labels = labels,
            signed = signed,
            selectedKey = selectedKey,
            onSelect = onSelect,
            thumbnail = thumbnail,
            modifier = modifier.fillMaxWidth(),
        )

        BtVizForm.DOT_PLOT -> VizDotPlot(
            items = drawable,
            format = format,
            selectedKey = selectedKey,
            onSelect = onSelect,
            thumbnail = thumbnail,
            modifier = modifier.fillMaxWidth(),
        )

        BtVizForm.BUBBLES -> VizBubbles(
            items = drawable,
            total = total,
            format = format,
            labels = labels,
            selectedKey = selectedKey,
            onSelect = onSelect,
            modifier = modifier.fillMaxWidth().height(vizCanvasHeight(form, canvas)),
        )

        BtVizForm.AUTO -> Unit // Never reached: callers resolve first.
    }
}

// ---------------------------------------------------------------------------
// Area forms — treemap and mosaic
// ---------------------------------------------------------------------------

/** The gap between area tiles. Small enough to keep area honest, wide enough to separate two similar hues. */
private val VIZ_TILE_GAP = 3.dp

/**
 * Narrowest tile that still prints its amount — one type step down (defect #19).
 * A German `2.078,95 €` at `labelSmall` measures ~60dp, so 68dp leaves the padding
 * its 14dp and still resolves the figure without ellipsis at phone densities.
 */
private val VIZ_TILE_AMOUNT_MIN_W = 68.dp

/** At or above this the amount prints at full caption size. */
private val VIZ_TILE_AMOUNT_FULL_W = 92.dp

/**
 * The corner radius of a data mark.
 *
 * Deliberately NOT [BtShapes.cardSmall]. A card radius is 10dp, which is a
 * pleasant softening on a 300dp card and turns a 20dp treemap tile into a
 * lozenge — the thumbnail previews made that unmissable. A mark's radius has to
 * be small in absolute terms, because the mark itself may be tiny, and because
 * a rounded rectangle whose corners eat its area stops being an honest picture
 * of that area.
 */
private val VIZ_MARK_SHAPE = RoundedCornerShape(3.dp)

/**
 * Treemap / mosaic. One [Layout] places a real composable per tile, so labels
 * are ordinary [Text] — they scale with the user's font size, they are reachable
 * by TalkBack, and they never need a bitmap text-fit pass.
 */
@Composable
private fun VizAreaChart(
    items: List<VizDatum>,
    total: Double,
    squarified: Boolean,
    canvas: BtVizCanvas,
    format: BtVizFormat,
    labels: BtVizLabels,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            items.forEach { datum ->
                VizAreaTile(
                    datum = datum,
                    total = total,
                    format = format,
                    labels = labels,
                    compact = canvas != BtVizCanvas.APP_FULL,
                    selected = datum.key == selectedKey,
                    onClick = { onSelect(if (datum.key == selectedKey) null else datum.key) },
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width <= 0 || height <= 0) return@Layout layout(0, 0) {}

        val bounds = VizRect.of(width.toFloat(), height.toFloat())
        val tiles = if (squarified) {
            squarifiedTreemap(items, bounds)
        } else {
            orderedMosaic(items, bounds)
        }.associateBy { it.key }

        val placed = measurables.mapIndexed { index, measurable ->
            val rect = tiles[items[index].key]?.rect
            if (rect == null) {
                measurable.measure(Constraints.fixed(0, 0)) to null
            } else {
                val w = (rect.right.roundToInt() - rect.left.roundToInt()).coerceAtLeast(0)
                val h = (rect.bottom.roundToInt() - rect.top.roundToInt()).coerceAtLeast(0)
                measurable.measure(Constraints.fixed(w, h)) to rect
            }
        }
        layout(width, height) {
            placed.forEach { (placeable, rect) ->
                if (rect != null) placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
            }
        }
    }
}

/**
 * One area tile. The label tier is decided by the tile's own measured size:
 * a big region states name, share and amount; a medium one name and share; a
 * small one nothing at all.
 *
 * The study is explicit that a mark too small to carry its name has no
 * independent identity — it must not get a shrunken 6sp label as a consolation
 * prize, because that is a mark that *looks* identified and is not.
 */
@Composable
private fun VizAreaTile(
    datum: VizDatum,
    total: Double,
    format: BtVizFormat,
    labels: BtVizLabels,
    compact: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val fill = vizFill(datum, signed = false)
    val ink = bt.chartInk(fill)
    val share = if (total > 0.0) datum.value / total else 0.0
    val shareText = format.share(share)
    val amountText = format.amount(datum.value)
    val cd = "${datum.label} · $amountText · $shareText"

    BoxWithConstraints(Modifier.fillMaxSize().padding(VIZ_TILE_GAP / 2)) {
        val w = maxWidth
        val h = maxHeight
        val showName = w >= 54.dp && h >= 26.dp
        val showShare = showName && h >= 42.dp && labels != BtVizLabels.AMOUNTS
        // The amount gets its OWN line rather than joining the share on one.
        // Concatenating them fitted the widest tiles and ellipsised the rest
        // ("11,08 % · 2.26…"), and a truncated amount is worse than no amount:
        // the whole reason to print money inside a mark is that it is exact.
        //
        // ── The narrow tier (device QA 2026-09-01, defect #19) ──────────────
        //
        // The width floor used to be a cliff: at 91dp the "Krypto" tile printed
        // its name and its share and simply dropped its € — the only tile on the
        // card missing a value, which reads as missing DATA, not as a layout
        // decision. Between [VIZ_TILE_AMOUNT_MIN_W] and [VIZ_TILE_AMOUNT_FULL_W]
        // the amount is now drawn one type step down instead of being dropped:
        // smaller type is a legibility cost the reader can absorb, an absent
        // figure is information they cannot recover. Below the narrow floor there
        // is genuinely no room and the tap-through still has it.
        val showAmount = showName && !compact && h >= 72.dp && w >= VIZ_TILE_AMOUNT_MIN_W &&
            labels != BtVizLabels.SHARES
        val amountTight = w < VIZ_TILE_AMOUNT_FULL_W

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(VIZ_MARK_SHAPE)
                .background(fill)
                .then(
                    if (selected) Modifier.border(2.dp, bt.gold, VIZ_MARK_SHAPE) else Modifier,
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 6.dp)
                .clearAndSetSemantics { contentDescription = cd },
        ) {
            if (showName) {
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text(
                        text = datum.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showShare) {
                        Text(
                            text = shareText,
                            style = BtTheme.type.numberCaption,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showAmount) {
                        Text(
                            text = amountText,
                            style = if (amountTight) {
                                MaterialTheme.typography.labelSmall
                            } else {
                                BtTheme.type.numberCaption
                            },
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 100 % stacked bar
// ---------------------------------------------------------------------------

/**
 * The single 100 % stacked bar — one common length baseline, which is easier to
 * compare than an angle or an area. The study calls it "the baseline every
 * alternative must actually beat", and it is the compact default for exactly
 * that reason.
 *
 * Weights rather than measured widths: the segments are proportions of a known
 * total, so `weight` keeps them exact at any width without a layout pass.
 */
@Composable
private fun VizStackedBar(
    items: List<VizDatum>,
    total: Double,
    format: BtVizFormat,
    compact: Boolean,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    val bt = BtTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { datum ->
            val share = if (total > 0.0) (datum.value / total).toFloat() else 0f
            if (share <= 0f) return@forEach
            val fill = vizFill(datum, signed = false)
            val selected = datum.key == selectedKey
            val shareText = format.share(share.toDouble())
            val cd = "${datum.label} · ${format.amount(datum.value)} · $shareText"
            BoxWithConstraints(
                modifier = Modifier
                    .weight(share)
                    .fillMaxHeight()
                    .clip(VIZ_MARK_SHAPE)
                    .background(fill)
                    .then(
                        if (selected) Modifier.border(2.dp, bt.gold, VIZ_MARK_SHAPE) else Modifier,
                    )
                    .clickable { onSelect(if (selected) null else datum.key) }
                    .clearAndSetSemantics { contentDescription = cd },
                contentAlignment = Alignment.Center,
            ) {
                // Print inside the segment only where the text genuinely fits.
                // A clipped "4…" is worse than a segment that stays silent and
                // lets the attached row below name it.
                if (maxWidth >= (if (compact) 34.dp else 40.dp)) {
                    Text(
                        text = shareText,
                        style = BtTheme.type.numberCaption,
                        color = bt.chartInk(fill),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Segmented ring / donut
// ---------------------------------------------------------------------------

/**
 * Segmented ring and classic donut — the same arc geometry, differing only in
 * how much air sits between segments.
 *
 * The centre carries exactly ONE answer, per the study: the selected share when
 * something is selected, otherwise the total. Two numbers in a hole is a legend
 * with extra steps.
 */
@Composable
private fun VizRing(
    items: List<VizDatum>,
    total: Double,
    segmented: Boolean,
    format: BtVizFormat,
    selectedKey: String?,
    modifier: Modifier,
) {
    val bt = BtTheme.colors
    val fills = items.map { vizFill(it, signed = false) }
    val selected = items.indexOfFirst { it.key == selectedKey }
    val gapDp = if (segmented) 5.dp else 2.dp

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (total <= 0.0) return@Canvas
            val stroke = min(size.width, size.height) * 0.17f
            val diameter = min(size.width, size.height) - stroke
            if (diameter <= 0f) return@Canvas
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val radius = diameter / 2f
            val gapDeg = (gapDp.toPx() / (2f * Math.PI.toFloat() * radius)) * 360f

            var start = -90f
            items.forEachIndexed { index, datum ->
                val fullSweep = (datum.value / total * 360.0).toFloat()
                val gap = if (fullSweep > gapDeg * 2f) gapDeg else 0f
                drawArc(
                    color = fills[index],
                    startAngle = start + gap / 2f,
                    sweepAngle = (fullSweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                if (index == selected) {
                    // A gold outer keyline, not an exploded slice: moving
                    // geometry on selection suggests the value changed.
                    val out = stroke / 2f + 2.dp.toPx()
                    drawArc(
                        color = bt.gold,
                        startAngle = start + gap / 2f,
                        sweepAngle = (fullSweep - gap).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = Offset(topLeft.x - out, topLeft.y - out),
                        size = Size(diameter + out * 2f, diameter + out * 2f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                start += fullSweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val focus = items.getOrNull(selected)
            Text(
                text = if (focus != null && total > 0.0) {
                    format.share(focus.value / total)
                } else {
                    format.amount(total)
                },
                style = BtTheme.type.moneySmall,
                color = bt.textPrimary,
                maxLines = 1,
            )
            if (focus != null) {
                Text(
                    text = focus.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Waffle / dot grid
// ---------------------------------------------------------------------------

/** The waffle is always ten by ten: the whole point is that one cell is one percent. */
private const val VIZ_WAFFLE_SIDE = 10

/**
 * The 100-dot waffle. Cell counts come from [waffleCounts]' largest-remainder
 * allocation, so the grid totals exactly 100 and the form's promise — "1 Punkt
 * = 1 %" — is literally true rather than approximately true.
 *
 * Drawn on one [Canvas] rather than as 100 composables: the cells carry no text
 * and no individual semantics, so a hundred layout nodes would buy nothing.
 */
@Composable
private fun VizWaffle(
    items: List<VizDatum>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    val bt = BtTheme.colors
    val fills = items.map { vizFill(it, signed = false) }
    val cells = remember(items) { waffleCells(items) }
    val keyIndex = remember(items) { items.mapIndexed { i, d -> d.key to i }.toMap() }
    val density = LocalDensity.current

    Canvas(
        modifier
            .clickable { onSelect(null) }
            .clearAndSetSemantics { },
    ) {
        val side = min(size.width, size.height)
        val cell = side / VIZ_WAFFLE_SIDE
        val inset = cell * 0.14f
        val originX = (size.width - side) / 2f
        val originY = (size.height - side) / 2f
        val radius = with(density) { 2.dp.toPx() }

        for (i in 0 until VIZ_WAFFLE_SIDE * VIZ_WAFFLE_SIDE) {
            val row = i / VIZ_WAFFLE_SIDE
            val col = i % VIZ_WAFFLE_SIDE
            val key = cells.getOrNull(i)
            val fill = key?.let { fills.getOrNull(keyIndex[it] ?: -1) }
            val left = originX + col * cell
            val top = originY + row * cell
            val boxSize = Size(cell - inset, cell - inset)
            if (fill == null) {
                // An unfilled cell is an outline, never a grey fill: a grey
                // waffle would read as "100 % Andere".
                drawRoundRect(
                    color = bt.border,
                    topLeft = Offset(left, top),
                    size = boxSize,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                    style = Stroke(width = 1.dp.toPx()),
                )
            } else {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, top),
                    size = boxSize,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                )
                if (key == selectedKey) {
                    drawRoundRect(
                        color = bt.gold,
                        topLeft = Offset(left, top),
                        size = boxSize,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ranked bars
// ---------------------------------------------------------------------------

/** Width of the leading name column. Wide enough for a ticker or a tag at large font scales. */
private val VIZ_NAME_COLUMN = 84.dp

/** Width of the trailing value column. */
private val VIZ_VALUE_COLUMN = 96.dp

/** Height of one ranked-bar or dot-plot row. */
private val VIZ_ROW_HEIGHT = 30.dp

/**
 * Horizontal ranked bars — the study's most accurate form, and the default
 * wherever money or a long tail is the question.
 *
 * Name on the left, exact value on the right, bar in between as a *supporting*
 * measure. There is no legend because there is nothing left for one to say.
 */
@Composable
private fun VizRankedBars(
    items: List<VizDatum>,
    total: Double,
    format: BtVizFormat,
    labels: BtVizLabels,
    signed: Boolean,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    thumbnail: Boolean,
    modifier: Modifier,
) {
    val bt = BtTheme.colors
    val bars = remember(items) { rankedBars(items) }
    if (thumbnail) {
        VizBarSwatch(bars = bars, signed = signed, modifier = modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        bars.forEach { bar ->
            val datum = bar.datum
            val selected = datum.key == selectedKey
            val fill = vizFill(datum, signed)
            val valueText = when {
                signed -> format.amount(datum.value)
                labels == BtVizLabels.SHARES && total > 0.0 -> format.share(datum.value / total)
                else -> format.amount(datum.value)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VIZ_ROW_HEIGHT)
                    .clip(BtShapes.cardSmall)
                    .then(if (selected) Modifier.background(bt.goldWash) else Modifier)
                    .clickable { onSelect(if (selected) null else datum.key) }
                    .padding(horizontal = 6.dp)
                    .clearAndSetSemantics {
                        contentDescription = "${datum.label} · $valueText"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = datum.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(VIZ_NAME_COLUMN),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(BtShapes.pill)
                        .background(bt.surfaceLow),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(bar.fraction)
                            .fillMaxHeight()
                            .clip(BtShapes.pill)
                            .background(fill),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = valueText,
                    style = BtTheme.type.numberCaption,
                    color = if (signed) {
                        if (datum.value < 0.0) bt.loss else bt.gain
                    } else {
                        bt.textPrimary
                    },
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(VIZ_VALUE_COLUMN),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Signed dot plot
// ---------------------------------------------------------------------------

/**
 * The row-aligned diverging dot plot — the study's default for signed movers.
 *
 * One row per holding keeps identity attached, and a short stem back to a
 * shared, symmetric zero axis makes direction and distance explicit. That is
 * the whole advantage over a beeswarm: nothing here needs a tooltip to say
 * which dot is which.
 */
@Composable
private fun VizDotPlot(
    items: List<VizDatum>,
    format: BtVizFormat,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    thumbnail: Boolean,
    modifier: Modifier,
) {
    val bt = BtTheme.colors
    val rows = remember(items) { signedDotPlot(items) }
    if (thumbnail) {
        VizDotSwatch(rows = rows, modifier = modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            val datum = row.datum
            val selected = datum.key == selectedKey
            val positive = datum.value >= 0.0
            val fill = if (positive) bt.gain else bt.loss
            val valueText = format.amount(datum.value)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VIZ_ROW_HEIGHT)
                    .clip(BtShapes.cardSmall)
                    .then(if (selected) Modifier.background(bt.goldWash) else Modifier)
                    .clickable { onSelect(if (selected) null else datum.key) }
                    .padding(horizontal = 6.dp)
                    .clearAndSetSemantics {
                        contentDescription = "${datum.label} · $valueText"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = datum.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(VIZ_NAME_COLUMN),
                )
                Spacer(Modifier.width(8.dp))
                Canvas(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    val axisX = size.width / 2f
                    val midY = size.height / 2f
                    drawLine(
                        color = bt.chartAxis,
                        start = Offset(axisX, 0f),
                        end = Offset(axisX, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val dotX = row.axisFraction * size.width
                    drawLine(
                        color = fill,
                        start = Offset(axisX, midY),
                        end = Offset(dotX, midY),
                        strokeWidth = 2.dp.toPx(),
                    )
                    val r = 5.dp.toPx()
                    val cx = dotX.coerceIn(r, size.width - r)
                    drawCircle(color = fill, radius = r, center = Offset(cx, midY))
                    if (selected) {
                        drawCircle(
                            color = bt.gold,
                            radius = r + 3.dp.toPx(),
                            center = Offset(cx, midY),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = valueText,
                    style = BtTheme.type.numberCaption,
                    color = if (positive) bt.gain else bt.loss,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(VIZ_VALUE_COLUMN),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Packed bubbles
// ---------------------------------------------------------------------------

/**
 * Packed bubbles: area carries value, position carries nothing.
 *
 * Drawn on one [Canvas] with the labels painted through the text measurer,
 * because a bubble's label has to be centred on a CIRCLE and clipped to it —
 * neither of which a rectangular child composable does. Everything the study
 * demands of the form is enforced here:
 *
 *  - a label appears only when the circle can hold the name **and** its share,
 *    never shrunken to fit;
 *  - selection adds a gold ring and dims siblings, and **never repacks** —
 *    a layout that moved on tap would suggest the value changed;
 *  - the layout comes from [packedBubbles], which is deterministic, so the same
 *    portfolio is the same picture on every refresh.
 */
@Composable
private fun VizBubbles(
    items: List<VizDatum>,
    total: Double,
    format: BtVizFormat,
    labels: BtVizLabels,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    val bt = BtTheme.colors
    val measurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.labelLarge
    val valueStyle = BtTheme.type.numberCaption
    val fills = items.map { vizFill(it, signed = false) }
    val cd = items.joinToString(" · ") {
        "${it.label} ${format.share(if (total > 0.0) it.value / total else 0.0)}"
    }
    var placed by remember(items) { mutableStateOf(emptyList<VizCircle>()) }

    Canvas(
        modifier
            .pointerInput(placed, selectedKey) {
                detectTapGestures { offset ->
                    // Hit-test the SMALLEST circle containing the tap: a small
                    // bubble nested against a large one must still be reachable.
                    val hit = placed
                        .filter { c ->
                            val dx = offset.x - c.cx
                            val dy = offset.y - c.cy
                            dx * dx + dy * dy <= c.r * c.r
                        }
                        .minByOrNull { it.r }
                    onSelect(if (hit == null || hit.key == selectedKey) null else hit.key)
                }
            }
            .clearAndSetSemantics { contentDescription = cd },
    ) {
        val circles = packedBubbles(items, VizRect.of(size.width, size.height))
        placed = circles
        val byKey = items.withIndex().associate { (i, d) -> d.key to i }

        circles.forEach { circle ->
            val index = byKey[circle.key] ?: return@forEach
            val datum = items[index]
            val isSelected = circle.key == selectedKey
            // Dim siblings only while something is selected, and only enough to
            // recede — every label must keep accessible contrast.
            val alpha = if (selectedKey == null || isSelected) 1f else VIZ_DIM_ALPHA
            drawCircle(
                color = fills[index],
                radius = circle.r,
                center = Offset(circle.cx, circle.cy),
                alpha = alpha,
            )
            if (isSelected) {
                drawCircle(
                    color = bt.gold,
                    radius = circle.r + 1.dp.toPx(),
                    center = Offset(circle.cx, circle.cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            val ink = bt.chartInk(fills[index])
            val shareText = format.share(if (total > 0.0) datum.value / total else 0.0)
            val nameLayout = measurer.measure(datum.label, nameStyle.copy(color = ink))
            val valueLayout = measurer.measure(shareText, valueStyle.copy(color = ink))

            // A circle of radius r holds a box of about r * sqrt(2) across.
            val room = circle.r * 1.35f
            val stacked = labels != BtVizLabels.AMOUNTS &&
                nameLayout.size.height + valueLayout.size.height <= room &&
                maxOf(nameLayout.size.width, valueLayout.size.width) <= room
            val nameOnly = nameLayout.size.width <= room && nameLayout.size.height <= room

            if (stacked) {
                val h = nameLayout.size.height + valueLayout.size.height
                drawText(
                    nameLayout,
                    topLeft = Offset(
                        circle.cx - nameLayout.size.width / 2f,
                        circle.cy - h / 2f,
                    ),
                    alpha = alpha,
                )
                drawText(
                    valueLayout,
                    topLeft = Offset(
                        circle.cx - valueLayout.size.width / 2f,
                        circle.cy - h / 2f + nameLayout.size.height,
                    ),
                    alpha = alpha,
                )
            } else if (nameOnly) {
                drawText(
                    nameLayout,
                    topLeft = Offset(
                        circle.cx - nameLayout.size.width / 2f,
                        circle.cy - nameLayout.size.height / 2f,
                    ),
                    alpha = alpha,
                )
            }
            // Anything smaller stays unlabelled on purpose: the attached rows
            // below the card name it, and a 6sp label inside a 14dp circle
            // would look identified without being readable.
        }
    }
}

/** How far unselected geometry recedes. Enough to separate, not enough to hide. */
private const val VIZ_DIM_ALPHA = 0.42f

// ---------------------------------------------------------------------------
// Picker swatches
// ---------------------------------------------------------------------------

/** Ranked bars as a silhouette: the descending staircase, no text. */
@Composable
private fun VizBarSwatch(bars: List<VizRankedBar>, signed: Boolean, modifier: Modifier) {
    val bt = BtTheme.colors
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        bars.take(VIZ_SWATCH_ROWS).forEach { bar ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(BtShapes.pill)
                    .background(bt.surfaceLow),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(bar.fraction.coerceAtLeast(0.04f))
                        .fillMaxHeight()
                        .clip(BtShapes.pill)
                        .background(vizFill(bar.datum, signed)),
                )
            }
        }
    }
}

/** The dot plot as a silhouette: a centre axis with dots either side of it. */
@Composable
private fun VizDotSwatch(rows: List<VizDotRow>, modifier: Modifier) {
    val bt = BtTheme.colors
    val shown = rows.take(VIZ_SWATCH_ROWS)
    val gain = bt.gain
    val loss = bt.loss
    Canvas(modifier.fillMaxSize()) {
        val axisX = size.width / 2f
        drawLine(
            color = bt.chartAxis,
            start = Offset(axisX, 0f),
            end = Offset(axisX, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        if (shown.isEmpty()) return@Canvas
        val step = size.height / (shown.size + 1)
        shown.forEachIndexed { index, row ->
            val y = step * (index + 1)
            val fill = if (row.datum.value < 0.0) loss else gain
            val x = (row.axisFraction * size.width).coerceIn(3.dp.toPx(), size.width - 3.dp.toPx())
            drawLine(color = fill, start = Offset(axisX, y), end = Offset(x, y), strokeWidth = 1.5.dp.toPx())
            drawCircle(color = fill, radius = 3.dp.toPx(), center = Offset(x, y))
        }
    }
}

/** How many rows a swatch shows. Enough to read as a ranking, few enough to stay a silhouette. */
private const val VIZ_SWATCH_ROWS = 5

// ---------------------------------------------------------------------------
// Empty states
// ---------------------------------------------------------------------------

/**
 * The empty rendition of each form.
 *
 * Every one of these preserves its form's grammar while fabricating nothing:
 * ghost panes for the area forms, an unfilled track for the bar, a neutral ring
 * with an em dash, outlined dots for the waffle, a bare zero axis for the dot
 * plot. What none of them do is fill with grey — a fully grey waffle or a
 * complete grey segment reads as "100 % Other", which is a data claim.
 */
@Composable
private fun VizEmpty(form: BtVizForm, canvas: BtVizCanvas, text: String, modifier: Modifier) {
    val bt = BtTheme.colors
    val height = when (form) {
        BtVizForm.RANKED_BARS, BtVizForm.DOT_PLOT -> 96.dp
        else -> vizCanvasHeight(form, canvas)
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center,
        ) {
            when (form) {
                BtVizForm.RING, BtVizForm.DONUT -> Box(
                    Modifier
                        .size(height * 0.62f)
                        .clip(CircleShape)
                        .border(14.dp, bt.surfaceLow, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("—", style = BtTheme.type.moneySmall, color = bt.textMuted)
                }

                BtVizForm.STACKED_BAR -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (canvas == BtVizCanvas.APP_FULL) 26.dp else 18.dp)
                        .clip(BtShapes.pill)
                        .border(1.dp, bt.border, BtShapes.pill),
                )

                BtVizForm.WAFFLE -> Canvas(Modifier.fillMaxSize()) {
                    val side = min(size.width, size.height)
                    val cell = side / VIZ_WAFFLE_SIDE
                    val originX = (size.width - side) / 2f
                    val originY = (size.height - side) / 2f
                    for (i in 0 until VIZ_WAFFLE_SIDE * VIZ_WAFFLE_SIDE) {
                        drawRoundRect(
                            color = bt.border,
                            topLeft = Offset(
                                originX + (i % VIZ_WAFFLE_SIDE) * cell,
                                originY + (i / VIZ_WAFFLE_SIDE) * cell,
                            ),
                            size = Size(cell * 0.86f, cell * 0.86f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }

                BtVizForm.DOT_PLOT -> Canvas(Modifier.fillMaxSize()) {
                    drawLine(
                        color = bt.chartAxis,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // Nothing at all (device QA 2026-09-01, defect #20). This drew
                // three full-width rules above "Noch keine Daten", and three
                // rules over an empty plot are indistinguishable from three bars
                // of length zero — the picture said "the values are all nil"
                // where the sentence beneath it said "there are no values".
                // Every other empty form here draws a GHOST of its own geometry
                // (an outlined waffle, a dot-plot axis) that cannot be misread as
                // data; a ranked-bar chart has no such ghost, so it draws none.
                BtVizForm.RANKED_BARS -> Unit

                else -> VizGhostPanes(border = BorderStroke(1.dp, bt.border))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Equal ghost panes: an outline that says "a division would go here", with no proportions implied. */
@Composable
private fun VizGhostPanes(border: BorderStroke) {
    Column(
        Modifier
            .fillMaxSize()
            .clip(BtShapes.card)
            .border(border, BtShapes.card),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        repeat(2) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(2) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(border),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Heatmap  (owner ask 2026-08-18)
// ---------------------------------------------------------------------------

/**
 * One heat cell: an area sized by [weight], coloured by [changePct].
 *
 * @param changePct null means "no quote today". Drawn neutral — a missing move
 *   is not a flat one, and colouring it green would invent a fact.
 */
data class VizHeatCell(
    val key: String,
    val label: String,
    val weight: Double,
    val changePct: Double?,
)

/**
 * The signed heatmap.
 *
 * ## The one place area and direction share a picture
 *
 * Everywhere else in this app the rule is absolute: emerald and red mean money
 * direction and nothing else, and an area form means share-of-whole. A heatmap
 * deliberately runs both at once, and that is legitimate because the two
 * channels answer two INDEPENDENT questions off two independent encodings —
 * *how much of my book is this* (area) and *what did it do today* (hue). Neither
 * is asked to imply the other.
 *
 * It stays honest under three constraints, all enforced here:
 *  - **the printed signed percentage is on the tile**, so direction never rests
 *    on hue alone and the form survives colour-blindness;
 *  - **intensity is scaled to the day's own strongest move**, so a calm day
 *    still reads instead of collapsing to uniform grey;
 *  - **a cell with no quote is neutral**, never a pale green.
 */
@Composable
fun BtVizHeatmap(
    cells: List<VizHeatCell>,
    changeText: (Double) -> String,
    emptyText: String,
    modifier: Modifier = Modifier,
    squarified: Boolean = true,
    selectedKey: String? = null,
    /**
     * What a tile prints when the asset has NO quote — typically an em dash.
     *
     * Opt-in, and null by default, because "no move to report" is two different
     * facts on two different surfaces. On the watchlist heatmap a quote-less
     * custom asset ("Anthropic") printed a bare name while every neighbour
     * printed a percentage, which reads as a rendering failure rather than as
     * missing data (device QA 2026-09-01, defect #28). On the widget preview the
     * folded `Andere` bucket carries a null change ON PURPOSE — it is an
     * aggregate that HAS no single move — and stamping a dash on it would invent
     * an absence. The caller knows which it is; this composable cannot.
     */
    missingChangeText: String? = null,
    onSelect: (String?) -> Unit = {},
) {
    val bt = BtTheme.colors
    val drawable = remember(cells) { cells.filter { it.weight > 0.0 } }
    if (drawable.isEmpty()) {
        VizEmpty(
            form = BtVizForm.TREEMAP,
            canvas = BtVizCanvas.APP_FULL,
            text = emptyText,
            modifier = modifier,
        )
        return
    }
    val maxAbs = remember(drawable) {
        drawable.mapNotNull { it.changePct }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
    }
    val data = remember(drawable) {
        drawable.map { VizDatum(key = it.key, label = it.label, value = it.weight) }
    }

    Layout(
        modifier = modifier,
        content = {
            drawable.forEach { cell ->
                val fill = vizHeatFill(cell.changePct, maxAbs)
                VizHeatTile(
                    cell = cell,
                    fill = fill,
                    ink = bt.chartInk(fill),
                    changeText = changeText,
                    missingChangeText = missingChangeText,
                    selected = cell.key == selectedKey,
                    onClick = { onSelect(if (cell.key == selectedKey) null else cell.key) },
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width <= 0 || height <= 0) return@Layout layout(0, 0) {}
        val bounds = VizRect.of(width.toFloat(), height.toFloat())
        val tiles = if (squarified) {
            squarifiedTreemap(data, bounds)
        } else {
            orderedMosaic(data, bounds)
        }.associateBy { it.key }

        val placed = measurables.mapIndexed { index, measurable ->
            val rect = tiles[drawable[index].key]?.rect
            if (rect == null) {
                measurable.measure(Constraints.fixed(0, 0)) to null
            } else {
                measurable.measure(
                    Constraints.fixed(
                        (rect.right.roundToInt() - rect.left.roundToInt()).coerceAtLeast(0),
                        (rect.bottom.roundToInt() - rect.top.roundToInt()).coerceAtLeast(0),
                    ),
                ) to rect
            }
        }
        layout(width, height) {
            placed.forEach { (placeable, rect) ->
                if (rect != null) placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
            }
        }
    }
}

/**
 * The directional fill for a move, blended toward the card so magnitude reads as
 * saturation.
 *
 * Anchored to [VIZ_HEAT_REFERENCE], not to the day's own maximum. Scaling purely
 * to the biggest mover means a −0,18 % day paints full-strength red, which the
 * device made obvious: a portfolio that did nothing looked like one that
 * crashed. The day's maximum takes over only once it exceeds the reference.
 */
@Composable
private fun vizHeatFill(changePct: Double?, maxAbs: Double): Color {
    val bt = BtTheme.colors
    if (changePct == null || changePct == 0.0) return bt.chartRest
    val hue = if (changePct > 0.0) bt.gain else bt.loss
    val reference = kotlin.math.max(maxAbs, VIZ_HEAT_REFERENCE)
    val ratio = (kotlin.math.abs(changePct) / reference).coerceIn(0.0, 1.0).toFloat()
    return lerp(bt.surfaceHigh, hue, VIZ_HEAT_FLOOR + (1f - VIZ_HEAT_FLOOR) * ratio)
}

/** The palest a directional tile may get; below this, green stops reading as green. */
private const val VIZ_HEAT_FLOOR = 0.42f

/** The day-move that counts as "full strength" when nothing bigger happened. */
private const val VIZ_HEAT_REFERENCE = 3.0

@Composable
private fun VizHeatTile(
    cell: VizHeatCell,
    fill: Color,
    ink: Color,
    changeText: (Double) -> String,
    missingChangeText: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val change = cell.changePct?.let(changeText) ?: missingChangeText.orEmpty()
    val cd = listOf(cell.label, change).filter { it.isNotEmpty() }.joinToString(" · ")
    BoxWithConstraints(Modifier.fillMaxSize().padding(VIZ_TILE_GAP / 2)) {
        val showName = maxWidth >= 40.dp && maxHeight >= 22.dp
        val showChange = showName && maxHeight >= 38.dp && change.isNotEmpty()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(VIZ_MARK_SHAPE)
                .background(fill)
                .then(if (selected) Modifier.border(2.dp, bt.gold, VIZ_MARK_SHAPE) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 5.dp, vertical = 4.dp)
                .clearAndSetSemantics { contentDescription = cd },
            contentAlignment = Alignment.Center,
        ) {
            if (showName) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = cell.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showChange) {
                        Text(
                            text = change,
                            style = BtTheme.type.numberCaption,
                            color = ink,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
