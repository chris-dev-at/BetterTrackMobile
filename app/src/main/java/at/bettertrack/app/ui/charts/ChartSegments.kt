package at.bettertrack.app.ui.charts

/**
 * Series segmentation for the area chart — **one connected run, always**.
 *
 * ## OWNER OVERRIDE, 2026-08-06
 *
 * S6 P0-3 shipped Δt gap-breaking here: the series was split wherever the step
 * between two observations exceeded `max(3 × median(Δt), 90 min)`, and the chart
 * stroked and filled each run on its own. The argument for it was honesty — a
 * value series is a sequence of OBSERVATIONS, not a continuous function, and
 * joining the last tick before a market close to the first one after it draws a
 * long diagonal ramp the reader can mistake for a real move.
 *
 * The owner looked at the result on the device and overruled it: *"I don't want
 * gaps in the graphs — a continuous line, not gapped."* That decision is
 * deliberate and informed, not a bug report — he has accepted the overnight
 * diagonal ramp as the lesser of the two complaints. A broken line costs him
 * something on every single chart he opens; the ramp costs him a moment's
 * misreading on the ranges that contain a market close, and the header's own
 * change figure states the true move next to it either way.
 *
 * So this function is now total and trivial: every point joins its neighbour.
 * It is kept as a function rather than inlined into the chart because the
 * always-connect contract is worth pinning in a test — see `ChartSegmentsTest` —
 * and because the shape of the call site should not have to change if the
 * decision is ever revisited.
 *
 * Everything else P0-3 brought stays: the x axis is still keyed on epoch millis
 * (not epoch days), so dense intraday series still land on distinct
 * coordinates instead of collapsing into a picket fence, and the axis labels
 * still pick their granularity from the data's own span.
 *
 * @return a single range spanning every index, or an empty list for an empty
 *   series. Never more than one range.
 */
internal fun chartSegments(times: List<Long>): List<IntRange> =
    if (times.isEmpty()) emptyList() else listOf(times.indices)

/**
 * Which indices the STROKE actually visits, so the line is never asked to render
 * detail finer than its own width.
 *
 * ## The bug this fixes (owner, 2026-08-17)
 *
 * *"they load and they look nice and slim and not too pointy but then once it
 * loads fully its looking really weird… like thinner maybe? or less spikey?"*
 *
 * Measured on his device, on the 1M range he was looking at:
 *
 * | | vertices | pitch | stroke (light) | stroke ÷ pitch |
 * |---|---|---|---|---|
 * | while loading (range morph) | 121 | 9.0 px | 8.2 px | **0.9×** |
 * | settled | 313 | 3.5 px | 8.2 px | **2.4×** |
 *
 * That table is the whole complaint. The settled line is drawn through vertices
 * 3.5 px apart with a stroke 8.2 px wide and round caps — so every vertex's cap
 * swallows both its neighbours, and any wiggle of a pixel or two piles up into a
 * lump. The morph frame he likes is not styled differently at all; it just
 * happens to resample onto 121 samples, which lands the pitch a hair WIDER than
 * the stroke. A line reads as slim exactly when it is not drawing on top of
 * itself.
 *
 * (Dark mode strokes 2 dp → 1.6×, which is why this arrived as a light-mode
 * complaint. His phone is in light mode.)
 *
 * ## Why min/max per column, and why this is not "faking smoothness"
 *
 * The chart's own header says interpolation is illegitimate here — a value
 * series is a sequence of observations and the app must not invent points
 * between them. Downsampling is the opposite operation and stays honest as long
 * as it never invents and never hides an extreme: for each pixel column this
 * keeps the HIGHEST and the LOWEST real observation in that column, in their
 * real order, and drops only the points in between — which are the ones the
 * rasteriser could not have distinguished anyway. Every peak and trough the eye
 * could see survives at its true height and its true x; what disappears is
 * sub-stroke zig-zag that was rendering noise, not information.
 *
 * The first and last observations are always kept so the curve still starts and
 * ends where the series does.
 *
 * The SCRUB is deliberately untouched: the crosshair still indexes the full
 * series, so the reader can still land on every real point and read its exact
 * value. This reduces what is STROKED, not what exists.
 *
 * @param values the series values, in x order.
 * @param columns how many pixel columns the stroke can distinguish
 *   (`plotWidthPx / strokeWidthPx`). Clamped to at least 2.
 * @return ascending indices to draw; the input's own indices when it is already
 *   coarser than [columns].
 */
internal fun chartRenderIndices(values: List<Double>, columns: Int): List<Int> {
    val n = values.size
    val budget = columns.coerceAtLeast(2)
    if (n <= budget) return values.indices.toList()

    val kept = LinkedHashSet<Int>()
    kept += 0
    for (bucket in 0 until budget) {
        val from = (bucket.toLong() * n / budget).toInt()
        val to = ((bucket + 1).toLong() * n / budget).toInt().coerceAtMost(n)
        if (from >= to) continue
        var lowAt = from
        var highAt = from
        for (i in from until to) {
            if (values[i] < values[lowAt]) lowAt = i
            if (values[i] > values[highAt]) highAt = i
        }
        // In their real order, so the stroke never doubles back on itself.
        if (lowAt <= highAt) {
            kept += lowAt
            kept += highAt
        } else {
            kept += highAt
            kept += lowAt
        }
    }
    kept += n - 1
    return kept.sorted()
}
