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
