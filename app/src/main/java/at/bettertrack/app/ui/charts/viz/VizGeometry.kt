package at.bettertrack.app.ui.charts.viz

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, platform-free geometry for the alternative chart forms (round-5 study,
 * `DESIGN_NOTES_CHARTS.md`).
 *
 * Nothing in this file touches Compose, Android, money formatting, or colour.
 * A chart form is **presentation only** — every value that arrives here was
 * already computed by the server; the functions below re-arrange space, never
 * money. That keeps the layouts unit-testable on the JVM and keeps the
 * "server is the only calculator" rule structurally true rather than merely
 * intended.
 */

/** Which fixed colour role a datum plays. Ordinary data takes the categorical ramp. */
enum class VizRole {
    /** Ordinary category — takes `BtColors.chartSeries[colorIndex]`. */
    Data,

    /** Cash. Always the quiet silver `chartCash`; never folded into a bucket. */
    Cash,

    /** A catch-all — either supplied by the server or produced by [reduceToTopN]. */
    Other,
}

/**
 * One datum in a visualization.
 *
 * @param key stable identity; drives colour stability across refreshes.
 * @param value non-negative for part-to-whole families, signed for movers.
 * @param colorIndex rank in the *unreduced* descending list. Held stable so a
 *   small rank change cannot make a holding look like a different category
 *   (study, "Legend and label system").
 * @param hiddenCount how many source rows this datum aggregates (bucket rows only).
 * @param colorArgb an identity colour the DATA carries — a user's spending-tag
 *   colour, for instance. When present it wins over the categorical ramp,
 *   because a tag the user painted green should not become the third blue just
 *   because it ranks third this month. Held as a plain ARGB `Int` so this file
 *   stays free of Compose.
 */
data class VizDatum(
    val key: String,
    val label: String,
    val value: Double,
    val role: VizRole = VizRole.Data,
    val colorIndex: Int = 0,
    val hiddenCount: Int = 0,
    val colorArgb: Int? = null,
)

/** An axis-aligned rectangle in an arbitrary (usually pixel) space. */
data class VizRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)

    companion object {
        fun of(width: Float, height: Float): VizRect = VizRect(0f, 0f, width, height)
    }
}

/** A laid-out area mark: the datum's [key] and the rectangle it occupies. */
data class VizTile(val key: String, val rect: VizRect)

// ---------------------------------------------------------------------------
// Bucketing / the long tail
// ---------------------------------------------------------------------------

/** Key used for the responsive bucket [reduceToTopN] synthesises. */
const val VIZ_BUCKET_KEY: String = "__viz_bucket__"

/**
 * Reduce a part-to-whole list to at most [limit] independently named items plus
 * one aggregate bucket, preserving the total exactly.
 *
 * Study rules honoured here:
 *  - Cash is **never** swept into the bucket; it stays its own named category.
 *  - The bucket is always last, whatever its value.
 *  - A bucket that has to coexist with a server-supplied `Andere` is a
 *    *different* meaning, so the caller labels it `Weitere` — [bucketLabel]
 *    receives the hidden count and whether a real catch-all is already present.
 *  - Everything else stays in descending value order.
 *
 * @param limit maximum independently named non-bucket rows. `<= 0` means "no cap".
 */
fun reduceToTopN(
    items: List<VizDatum>,
    limit: Int,
    bucketLabel: (hiddenCount: Int, realOtherPresent: Boolean) -> String,
): List<VizDatum> {
    if (limit <= 0 || items.size <= limit) return items
    val realOtherPresent = items.any { it.role == VizRole.Other }

    // Cash and a server-supplied catch-all are pinned: they may not be hidden.
    val pinned = items.filter { it.role != VizRole.Data }
    val rankable = items.filter { it.role == VizRole.Data }

    // The cap counts every named row, pinned ones included.
    val roomForRankable = (limit - pinned.size).coerceAtLeast(0)
    if (rankable.size <= roomForRankable) return items

    val ordered = rankable.sortedWith(compareByDescending<VizDatum> { it.value }.thenBy { it.key })
    val kept = ordered.take(roomForRankable)
    val hidden = ordered.drop(roomForRankable)
    if (hidden.isEmpty()) return items

    val bucket = VizDatum(
        key = VIZ_BUCKET_KEY,
        label = bucketLabel(hidden.sumOf { it.hiddenCount.coerceAtLeast(1) }, realOtherPresent),
        value = hidden.sumOf { it.value },
        role = VizRole.Other,
        colorIndex = -1,
        hiddenCount = hidden.sumOf { it.hiddenCount.coerceAtLeast(1) },
    )

    val named = (kept + pinned.filter { it.key != VIZ_BUCKET_KEY })
        .sortedWith(compareByDescending<VizDatum> { it.value }.thenBy { it.key })
    return named + bucket
}

/**
 * Assign the stable colour index each datum keeps for the life of a scope:
 * its rank in the full, unreduced, descending list. Cash and catch-all rows get
 * `-1` because their colour is fixed by role, not by rank.
 */
fun withStableColorIndices(items: List<VizDatum>): List<VizDatum> {
    var next = 0
    val ranked = items
        .sortedWith(compareByDescending<VizDatum> { it.value }.thenBy { it.key })
        .associate { datum ->
            datum.key to if (datum.role == VizRole.Data) next++ else -1
        }
    return items.map { it.copy(colorIndex = ranked[it.key] ?: -1) }
}

// ---------------------------------------------------------------------------
// Squarified treemap  (Bruls, Huizing & van Wijk)
// ---------------------------------------------------------------------------

/**
 * Squarified treemap tiling of [bounds].
 *
 * Guarantees the tests pin down: every tile lies inside [bounds], tiles do not
 * overlap, and their areas sum to the area of [bounds] (up to float epsilon) —
 * i.e. the tiling is a true partition, so "area = value" is not a lie.
 *
 * Items with a non-positive value are dropped; input order is irrelevant
 * because the algorithm sorts descending (that is what makes tiles square).
 */
fun squarifiedTreemap(items: List<VizDatum>, bounds: VizRect): List<VizTile> {
    val positive = items.filter { it.value > 0.0 }
    if (positive.isEmpty() || bounds.width <= 0f || bounds.height <= 0f) return emptyList()

    val total = positive.sumOf { it.value }
    if (total <= 0.0) return emptyList()

    val scale = bounds.area.toDouble() / total
    val areas = positive
        .sortedWith(compareByDescending<VizDatum> { it.value }.thenBy { it.key })
        .map { it.key to it.value * scale }

    val out = ArrayList<VizTile>(areas.size)
    var free = bounds
    val row = ArrayList<Pair<String, Double>>()
    var i = 0
    while (i < areas.size) {
        val side = min(free.width, free.height).toDouble()
        val current = worstAspect(row, side)
        val widened = worstAspect(row + areas[i], side)
        if (row.isEmpty() || widened <= current) {
            row += areas[i]
            i++
        } else {
            free = layoutRow(row, free, lastRow = false, out = out)
            row.clear()
        }
    }
    if (row.isNotEmpty()) layoutRow(row, free, lastRow = true, out = out)
    return out
}

/** Worst (largest) aspect ratio in a candidate row laid along [side]. */
private fun worstAspect(row: List<Pair<String, Double>>, side: Double): Double {
    if (row.isEmpty() || side <= 0.0) return Double.MAX_VALUE
    var sum = 0.0
    var maxA = Double.MIN_VALUE
    var minA = Double.MAX_VALUE
    row.forEach { (_, a) ->
        sum += a
        if (a > maxA) maxA = a
        if (a < minA) minA = a
    }
    if (sum <= 0.0 || minA <= 0.0) return Double.MAX_VALUE
    val s2 = sum * sum
    val w2 = side * side
    return max(w2 * maxA / s2, s2 / (w2 * minA))
}

/**
 * Place [row] along the shorter side of [free] and return the remaining free
 * rectangle. The final cell snaps to the strip edge so float drift can never
 * leave a hairline gap or a sliver of overhang.
 */
private fun layoutRow(
    row: List<Pair<String, Double>>,
    free: VizRect,
    lastRow: Boolean,
    out: MutableList<VizTile>,
): VizRect {
    val sum = row.sumOf { it.second }
    if (sum <= 0.0) return free

    return if (free.width < free.height) {
        // Horizontal strip across the top.
        val thickness = if (lastRow) free.height else (sum / free.width).toFloat().coerceAtMost(free.height)
        var x = free.left
        row.forEachIndexed { index, (key, area) ->
            val right = if (index == row.lastIndex) free.right else x + (area / sum).toFloat() * free.width
            out += VizTile(key, VizRect(x, free.top, right, free.top + thickness))
            x = right
        }
        VizRect(free.left, free.top + thickness, free.right, free.bottom)
    } else {
        // Vertical strip down the left.
        val thickness = if (lastRow) free.width else (sum / free.height).toFloat().coerceAtMost(free.width)
        var y = free.top
        row.forEachIndexed { index, (key, area) ->
            val bottom = if (index == row.lastIndex) free.bottom else y + (area / sum).toFloat() * free.height
            out += VizTile(key, VizRect(free.left, y, free.left + thickness, bottom))
            y = bottom
        }
        VizRect(free.left + thickness, free.top, free.right, free.bottom)
    }
}

// ---------------------------------------------------------------------------
// Ordered rectangle mosaic  ("Flächenraster" — ordered strip treemap)
// ---------------------------------------------------------------------------

/**
 * Ordered rectangle mosaic: horizontal strips filled left-to-right, stacked
 * top-to-bottom, in the **given order** — no sorting.
 *
 * This is the treemap's predictable cousin. It trades a little space efficiency
 * for a stable reading order, which is exactly why the study makes it the safe
 * area form for widgets: a small value change nudges a cell, it never teleports
 * one across the canvas.
 *
 * Same partition guarantee as [squarifiedTreemap]: tiles fill [bounds] exactly.
 */
fun orderedMosaic(items: List<VizDatum>, bounds: VizRect): List<VizTile> {
    val positive = items.filter { it.value > 0.0 }
    if (positive.isEmpty() || bounds.width <= 0f || bounds.height <= 0f) return emptyList()

    val total = positive.sumOf { it.value }
    if (total <= 0.0) return emptyList()

    val scale = bounds.area.toDouble() / total
    val areas = positive.map { it.key to it.value * scale }

    val out = ArrayList<VizTile>(areas.size)
    var free = bounds
    val strip = ArrayList<Pair<String, Double>>()
    var i = 0
    while (i < areas.size) {
        val width = free.width.toDouble()
        val current = stripAspect(strip, width)
        val widened = stripAspect(strip + areas[i], width)
        if (strip.isEmpty() || widened <= current) {
            strip += areas[i]
            i++
        } else {
            free = layoutStrip(strip, free, lastStrip = false, out = out)
            strip.clear()
        }
    }
    if (strip.isNotEmpty()) layoutStrip(strip, free, lastStrip = true, out = out)
    return out
}

/** Mean aspect-ratio badness of a candidate strip spanning [width]. */
private fun stripAspect(strip: List<Pair<String, Double>>, width: Double): Double {
    if (strip.isEmpty() || width <= 0.0) return Double.MAX_VALUE
    val sum = strip.sumOf { it.second }
    if (sum <= 0.0) return Double.MAX_VALUE
    val h = sum / width
    if (h <= 0.0) return Double.MAX_VALUE
    var acc = 0.0
    strip.forEach { (_, a) ->
        val w = a / h
        acc += if (w <= 0.0) Double.MAX_VALUE else max(w / h, h / w)
    }
    return acc / strip.size
}

private fun layoutStrip(
    strip: List<Pair<String, Double>>,
    free: VizRect,
    lastStrip: Boolean,
    out: MutableList<VizTile>,
): VizRect {
    val sum = strip.sumOf { it.second }
    if (sum <= 0.0) return free
    val thickness = if (lastStrip) free.height else (sum / free.width).toFloat().coerceAtMost(free.height)
    var x = free.left
    strip.forEachIndexed { index, (key, area) ->
        val right = if (index == strip.lastIndex) free.right else x + (area / sum).toFloat() * free.width
        out += VizTile(key, VizRect(x, free.top, right, free.top + thickness))
        x = right
    }
    return VizRect(free.left, free.top + thickness, free.right, free.bottom)
}

// ---------------------------------------------------------------------------
// Waffle / dot grid
// ---------------------------------------------------------------------------

/**
 * Largest-remainder allocation of [cells] cells across [items].
 *
 * The grid always totals exactly [cells] — that is the whole promise of the
 * form ("1 Punkt = 1 %"). Printed percentages stay derived from the underlying
 * values elsewhere; they are never back-calculated from these counts.
 *
 * @return counts in the same order as [items].
 */
fun waffleCounts(items: List<VizDatum>, cells: Int = 100): List<Int> {
    if (items.isEmpty() || cells <= 0) return List(items.size) { 0 }
    val total = items.sumOf { max(0.0, it.value) }
    if (total <= 0.0) return List(items.size) { 0 }

    val exact = items.map { max(0.0, it.value) / total * cells }
    val floors = exact.map { it.toInt() }
    var remaining = cells - floors.sum()
    val counts = floors.toMutableList()

    // Hand out the remainder by descending fractional part; ties resolve by
    // descending value and then key, so the grid is deterministic.
    val order = items.indices.sortedWith(
        compareByDescending<Int> { exact[it] - floors[it] }
            .thenByDescending { items[it].value }
            .thenBy { items[it].key },
    )
    var cursor = 0
    while (remaining > 0 && order.isNotEmpty()) {
        counts[order[cursor % order.size]]++
        cursor++
        remaining--
    }
    return counts
}

/**
 * Row-major cell assignment for the waffle: the key that owns each of the
 * [cells] cells, categories contiguous and in the given order.
 */
fun waffleCells(items: List<VizDatum>, cells: Int = 100): List<String> {
    val counts = waffleCounts(items, cells)
    val out = ArrayList<String>(cells)
    items.forEachIndexed { index, datum ->
        repeat(counts[index]) { out += datum.key }
    }
    return out
}

// ---------------------------------------------------------------------------
// Ranked bars
// ---------------------------------------------------------------------------

/** One ranked row: the datum plus its bar fill fraction on the shared scale. */
data class VizRankedBar(val datum: VizDatum, val fraction: Float)

/**
 * Ranked horizontal bars: descending by value on a **common** scale, so bar
 * length is comparable across rows. The scale is the largest bar, not the
 * total — this form answers "how much, and how does it compare", not "what
 * fraction of the whole".
 *
 * The bucket row, if present, stays last regardless of its magnitude.
 */
fun rankedBars(items: List<VizDatum>): List<VizRankedBar> {
    if (items.isEmpty()) return emptyList()
    val bucket = items.filter { it.key == VIZ_BUCKET_KEY }
    val rest = items.filter { it.key != VIZ_BUCKET_KEY }
        .sortedWith(compareByDescending<VizDatum> { abs(it.value) }.thenBy { it.key })
    val ordered = rest + bucket

    val maxValue = ordered.maxOfOrNull { abs(it.value) } ?: 0.0
    if (maxValue <= 0.0) return ordered.map { VizRankedBar(it, 0f) }
    return ordered.map { VizRankedBar(it, (abs(it.value) / maxValue).toFloat().coerceIn(0f, 1f)) }
}

// ---------------------------------------------------------------------------
// Signed dot plot  ("Punktdiagramm" — row-aligned diverging lollipop)
// ---------------------------------------------------------------------------

/**
 * One dot-plot row: [axisFraction] is 0.5 at zero, 0 at −max and 1 at +max.
 * The axis is symmetric by construction, so a gain and a loss of equal size sit
 * equally far from the centre and the eye can trust the comparison.
 */
data class VizDotRow(val datum: VizDatum, val axisFraction: Float)

/**
 * Row-aligned diverging dot plot on a shared, symmetric zero axis.
 *
 * Rows are ordered from the largest gain to the largest loss, so the sign
 * change is a single crossing rather than a scatter. Every row keeps its
 * identity and its printed signed amount; colour merely repeats the sign.
 */
fun signedDotPlot(items: List<VizDatum>): List<VizDotRow> {
    if (items.isEmpty()) return emptyList()
    val ordered = items.sortedWith(compareByDescending<VizDatum> { it.value }.thenBy { it.key })
    val maxAbs = ordered.maxOfOrNull { abs(it.value) } ?: 0.0
    if (maxAbs <= 0.0) return ordered.map { VizDotRow(it, 0.5f) }
    return ordered.map {
        VizDotRow(it, (0.5 + it.value / maxAbs / 2.0).toFloat().coerceIn(0f, 1f))
    }
}

/**
 * The two extrema a 2×2 widget can honestly show: the largest gain and the
 * largest loss. Returns fewer than two entries when the data has only one sign.
 */
fun signedExtrema(items: List<VizDatum>): List<VizDatum> {
    val gain = items.filter { it.value > 0.0 }.maxByOrNull { it.value }
    val loss = items.filter { it.value < 0.0 }.minByOrNull { it.value }
    return listOfNotNull(gain, loss)
}

// ---------------------------------------------------------------------------
// Displayed shares
// ---------------------------------------------------------------------------

/**
 * Whole-percent shares allocated by largest remainder, so the printed column
 * sums to exactly 100 — no "42 + 28 + 16 + 8 + 4 + 2 = 99" embarrassment.
 *
 * Monetary totals are never derived from these; they come from the unrounded
 * source values the server supplied.
 */
fun wholePercentShares(values: List<Double>): List<Int> {
    if (values.isEmpty()) return emptyList()
    val total = values.sumOf { max(0.0, it) }
    if (total <= 0.0) return List(values.size) { 0 }

    val exact = values.map { max(0.0, it) / total * 100.0 }
    val counts = exact.map { it.toInt() }.toMutableList()
    var remaining = 100 - counts.sum()
    val order = values.indices.sortedWith(
        compareByDescending<Int> { exact[it] - exact[it].toInt() }
            .thenByDescending { values[it] },
    )
    var cursor = 0
    while (remaining > 0 && order.isNotEmpty()) {
        counts[order[cursor % order.size]]++
        cursor++
        remaining--
    }
    return counts
}

// ---------------------------------------------------------------------------
// Packed bubbles
// ---------------------------------------------------------------------------

/** A laid-out bubble: centre and radius in the same space as [VizRect]. */
data class VizCircle(val key: String, val cx: Float, val cy: Float, val r: Float)

/**
 * Deterministic circle packing.
 *
 * ## Why determinism is the whole requirement
 *
 * The study allows bubbles only "once deterministic packing and label tests are
 * solid", and that is not a performance note — a packing that shuffles between
 * frames makes the same portfolio look like a different portfolio on every
 * refresh, and destroys the one thing the form is good at ("what dominates?").
 * So this algorithm has no randomness and no iteration count that depends on
 * timing: identical input yields byte-identical output, which
 * [VizGeometryTest] asserts directly.
 *
 * ## The algorithm
 *
 * Radius is proportional to √value, so **area** carries value — the same claim
 * the treemap makes, and the reason a bubble chart is not just decoration.
 * Circles are placed largest-first: the first at the origin, each subsequent one
 * at the first collision-free point found along an outward spiral. Placing
 * biggest-first is what keeps the result compact; scanning a fixed spiral is
 * what keeps it reproducible.
 *
 * Finally the whole cluster is scaled and centred into [bounds], so the packing
 * fills the canvas without any circle escaping it.
 */
fun packedBubbles(items: List<VizDatum>, bounds: VizRect): List<VizCircle> {
    val positive = items.filter { it.value > 0.0 }
    if (positive.isEmpty() || bounds.width <= 0f || bounds.height <= 0f) return emptyList()

    val ordered = positive.sortedWith(compareByDescending<VizDatum> { it.value }.thenBy { it.key })
    val maxValue = ordered.first().value
    // Unit space: the largest bubble has radius 1. Scale comes later.
    val radii = ordered.map { (kotlin.math.sqrt(it.value / maxValue)).toFloat() }

    val placed = ArrayList<VizCircle>(ordered.size)
    ordered.forEachIndexed { index, datum ->
        val r = radii[index]
        if (placed.isEmpty()) {
            placed += VizCircle(datum.key, 0f, 0f, r)
            return@forEachIndexed
        }
        placed += packBubble(datum.key, r, placed)
    }

    // Fit: scale the cluster so its bounding box fills the canvas, then centre.
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    placed.forEach {
        minX = min(minX, it.cx - it.r)
        minY = min(minY, it.cy - it.r)
        maxX = max(maxX, it.cx + it.r)
        maxY = max(maxY, it.cy + it.r)
    }
    val spanX = (maxX - minX).coerceAtLeast(0.0001f)
    val spanY = (maxY - minY).coerceAtLeast(0.0001f)
    val scale = min(bounds.width / spanX, bounds.height / spanY)
    val offsetX = bounds.left + (bounds.width - spanX * scale) / 2f - minX * scale
    val offsetY = bounds.top + (bounds.height - spanY * scale) / 2f - minY * scale

    return placed.map {
        VizCircle(
            key = it.key,
            cx = it.cx * scale + offsetX,
            cy = it.cy * scale + offsetY,
            r = it.r * scale,
        )
    }
}

/**
 * The first collision-free spot for a circle of radius [r], found by walking an
 * outward spiral from the cluster's centre.
 *
 * The step sizes are constants rather than tuned per input, which is what makes
 * two runs over the same data land in exactly the same place.
 */
private fun packBubble(key: String, r: Float, placed: List<VizCircle>): VizCircle {
    var ring = BUBBLE_RING_STEP
    while (ring < BUBBLE_MAX_RING) {
        // More candidate angles on a bigger ring, so angular resolution stays
        // roughly constant as the cluster grows.
        val steps = (BUBBLE_BASE_ANGLES * ring / BUBBLE_RING_STEP).toInt().coerceIn(12, 240)
        for (i in 0 until steps) {
            val angle = 2.0 * Math.PI * i / steps
            val cx = (ring * kotlin.math.cos(angle)).toFloat()
            val cy = (ring * kotlin.math.sin(angle)).toFloat()
            if (placed.none { other ->
                    val dx = other.cx - cx
                    val dy = other.cy - cy
                    dx * dx + dy * dy < (other.r + r + BUBBLE_GAP) * (other.r + r + BUBBLE_GAP)
                }
            ) {
                return VizCircle(key, cx, cy, r)
            }
        }
        ring += BUBBLE_RING_STEP
    }
    // Unreachable for any realistic set; parking it outside the cluster beats
    // dropping a datum silently.
    return VizCircle(key, BUBBLE_MAX_RING, 0f, r)
}

/** Radial resolution of the spiral search, in units of the largest radius. */
private const val BUBBLE_RING_STEP = 0.06f

/** Angular resolution at the innermost ring; grows with the ring. */
private const val BUBBLE_BASE_ANGLES = 24f

/** Breathing room between bubbles so two similar hues stay two shapes. */
private const val BUBBLE_GAP = 0.035f

/** How far out the search may go before giving up. */
private const val BUBBLE_MAX_RING = 40f
