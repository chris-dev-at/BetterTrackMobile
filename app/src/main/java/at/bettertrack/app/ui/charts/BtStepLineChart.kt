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
 * The BetterTrack step-line chart (spec §3.6): custom-asset value points are
 * discrete observations, not a continuous price — so the line holds each value
 * flat until the next point (a step), with a soft gradient fill beneath. Same
 * visual family as [BtAreaChart] (gold 2dp line, recessive fill), different
 * interpolation. Values are the user's recorded points, drawn verbatim.
 */
@Composable
fun BtStepLineChart(
    points: List<StepPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = BtTheme.colors.gold,
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

        // Build the step path: horizontal to the next x at the current y, then
        // vertical to the next value.
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
            line.lineTo(px, prevY)   // hold previous value (the step)
            line.lineTo(px, py)      // jump to new value
            fill.lineTo(px, prevY)
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
