package at.bettertrack.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtTheme

/** One value point for the step-line chart (custom assets, §3.6). */
data class StepPoint(val epochDay: Long, val value: Double)

/**
 * How the line connects the recorded value points:
 *  - [Step] holds each value flat until the next point (a staircase) — the right
 *    read for discrete observations that don't move between entries.
 *  - [Linear] draws a straight line from point to point (the same simple-line
 *    form the gold hero [BtAreaChart] uses) — the "smoothed" read for assets
 *    whose value is assumed to drift continuously between entries.
 * Only the interpolation changes; the points, dots, gridlines, fill and scale
 * are identical in both modes.
 */
enum class BtLineInterpolation { Step, Linear }

/**
 * The BetterTrack value-line chart (spec §3.6): custom-asset value points are
 * discrete observations, not a continuous price — so by default the line holds
 * each value flat until the next point (a [BtLineInterpolation.Step]), with a
 * soft gradient fill beneath. Assets flagged as smoothed request
 * [BtLineInterpolation.Linear] instead, connecting the same points with straight
 * lines. Same visual family as [BtAreaChart] (gold 2dp line, recessive fill);
 * values are the user's recorded points, drawn verbatim.
 */
@Composable
fun BtStepLineChart(
    points: List<StepPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = BtTheme.colors.gold,
    interpolation: BtLineInterpolation = BtLineInterpolation.Step,
) {
    val fillTop = lineColor.copy(alpha = 0.22f)
    val gridColor = BtTheme.colors.border
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val padTop = 8f
        val padBottom = 8f
        val w = size.width
        val h = size.height
        val innerH = (h - padTop - padBottom).coerceAtLeast(1f)

        val minDay = points.first().epochDay
        val maxDay = points.last().epochDay
        val daySpan = (maxDay - minDay).coerceAtLeast(1L).toFloat()

        var minV = points.minOf { it.value }
        var maxV = points.maxOf { it.value }
        if (minV == maxV) { minV -= 1.0; maxV += 1.0 }
        // All-positive series read cleaner clamped toward zero baseline.
        if (minV > 0.0 && minV < maxV * 0.5) minV = 0.0
        val vSpan = (maxV - minV).coerceAtLeast(1e-9)

        fun x(day: Long) = ((day - minDay) / daySpan) * w
        fun y(v: Double) = padTop + (innerH - ((v - minV) / vSpan * innerH)).toFloat()

        // Three recessive gridlines.
        repeat(3) { i ->
            val gy = padTop + innerH * (i + 1) / 4f
            drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        }

        // Build the line + fill path. Step interpolation holds the previous value
        // (horizontal) then jumps (vertical) at each new point; linear draws a
        // straight segment directly to the new point. The fill mirrors the line
        // and closes down to the baseline.
        val line = Path()
        val fill = Path()
        val firstX = x(points.first().epochDay)
        val firstY = y(points.first().value)
        line.moveTo(firstX, firstY)
        fill.moveTo(firstX, h - padBottom)
        fill.lineTo(firstX, firstY)
        var prevY = firstY
        for (i in 1 until points.size) {
            val px = x(points[i].epochDay)
            val py = y(points[i].value)
            if (interpolation == BtLineInterpolation.Step) {
                line.lineTo(px, prevY)   // hold previous value (the step)
                fill.lineTo(px, prevY)
            }
            line.lineTo(px, py)          // move to the new value
            fill.lineTo(px, py)
            prevY = py
        }
        val lastX = x(points.last().epochDay)
        fill.lineTo(lastX, h - padBottom)
        fill.close()

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(listOf(fillTop, Color.Transparent)),
        )
        drawPath(
            path = line,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Dot each recorded observation.
        points.forEach { p ->
            drawCircle(lineColor, radius = 2.6.dp.toPx(), center = Offset(x(p.epochDay), y(p.value)))
        }
    }
}
