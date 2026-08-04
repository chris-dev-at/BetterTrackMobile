package at.bettertrack.app.ui.charts

/**
 * Gap segmentation for the area chart (S6 P0-3).
 *
 * A value series is a sequence of OBSERVATIONS, not a continuous function. When
 * the market is closed there is no data — and joining the last tick before the
 * close to the first tick after it draws a long straight ramp that the user
 * reads as a real move. On 1D that ramp was the last third of the plot climbing
 * off the chart while the header said +0.79 %.
 *
 * The honest rendering is a GAP: the line and its gradient fill stop at the last
 * observation and restart at the next one.
 *
 * ## The threshold rule
 *
 * The break threshold is derived per render from the series itself — no
 * hardcoded per-range table, because the same range comes back at different
 * densities (a 1D series can be 1-minute or 15-minute ticks):
 *
 * ```
 * threshold = max(GAP_FACTOR × median(Δt), GAP_FLOOR_MS)
 * break where Δt > threshold
 * ```
 *
 * * **median**, not mean — a single overnight gap must not drag the threshold up
 *   to the point where it hides itself.
 * * **× 3** is loose enough that ordinary jitter and a couple of missing ticks
 *   stay connected, and tight enough that a market close (≈17 h against a
 *   minutes-scale median) always breaks.
 * * **the 90-minute floor** stops a very dense series from shattering into
 *   confetti: with 1-minute ticks, 3 × median is 3 minutes, and a five-minute
 *   hole in the feed is not a story worth telling. It only ever RAISES a small
 *   threshold; a daily series (median 24 h) keeps its 72 h threshold, which
 *   deliberately leaves the normal Fri→Mon weekend (exactly 3 × median)
 *   connected while a genuinely longer market holiday breaks.
 */
internal const val CHART_GAP_FACTOR = 3.0

/** Lower bound for the break threshold — see [CHART_GAP_FACTOR]. */
internal const val CHART_GAP_FLOOR_MS = 90L * 60_000L

/**
 * Median spacing between adjacent timestamps in [times] (assumed ascending).
 * Non-positive steps (duplicate or out-of-order timestamps) are ignored — they
 * carry no spacing information and would only bias the median toward zero.
 *
 * @return the median step in ms, or 0 when there is no usable step at all.
 */
internal fun medianSpacingMs(times: List<Long>): Long {
    if (times.size < 2) return 0L
    val steps = ArrayList<Long>(times.size - 1)
    for (i in 1 until times.size) {
        val d = times[i] - times[i - 1]
        if (d > 0L) steps.add(d)
    }
    if (steps.isEmpty()) return 0L
    steps.sort()
    val mid = steps.size / 2
    return if (steps.size % 2 == 1) {
        steps[mid]
    } else {
        // Even count: the two central steps averaged (rounded down; ms precision
        // is far finer than anything the threshold decides).
        (steps[mid - 1] + steps[mid]) / 2
    }
}

/** The Δt above which adjacent points are NOT joined. See [CHART_GAP_FACTOR]. */
internal fun chartGapThresholdMs(times: List<Long>): Long {
    val median = medianSpacingMs(times)
    if (median <= 0L) return Long.MAX_VALUE // no usable spacing ⇒ never break
    val scaled = (median.toDouble() * CHART_GAP_FACTOR)
        .coerceAtMost(Long.MAX_VALUE.toDouble())
        .toLong()
    return maxOf(scaled, CHART_GAP_FLOOR_MS)
}

/**
 * Splits [times] into the index ranges that may be drawn as connected strokes.
 *
 * Every index appears in exactly one range and the ranges are in ascending
 * order, so a caller can draw each one independently (line + its own closed
 * fill) and the union is the whole series. A single-index range is a lone
 * observation with a gap on both sides — the caller decides how to mark it
 * (the chart draws a dot).
 *
 * An empty input yields an empty list; a one-point input yields `[0..0]`.
 */
internal fun chartSegments(times: List<Long>): List<IntRange> {
    if (times.isEmpty()) return emptyList()
    if (times.size == 1) return listOf(0..0)
    val threshold = chartGapThresholdMs(times)
    val segments = ArrayList<IntRange>()
    var start = 0
    for (i in 1 until times.size) {
        if (times[i] - times[i - 1] > threshold) {
            segments.add(start..(i - 1))
            start = i
        }
    }
    segments.add(start..(times.size - 1))

    // Anti-shatter guard. A break is only meaningful if it is EXCEPTIONAL. If a
    // series mixes densities (say a MAX range served weekly early and daily
    // late), the median comes from the dense half and every sparse step reads as
    // a gap — the sparse half would dissolve into loose dots. When most segments
    // are isolated points, the "gaps" ARE the sampling rate, so the series is
    // drawn connected as before.
    if (segments.size > 2 && segments.count { it.first == it.last } * 2 > segments.size) {
        return listOf(times.indices)
    }
    return segments
}
