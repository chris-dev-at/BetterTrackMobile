package at.bettertrack.app.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.components.rememberReducedMotion
import kotlin.math.min

/**
 * One slice of the allocation donut. Colors come from [BtChartPalette] — a
 * validated categorical ramp (gold stays reserved for the brand, §3.3/§3.6).
 */
data class DonutSegment(
    val label: String,
    val value: Double,
    val color: Color,
)

/**
 * Flat allocation donut (spec §3.6): stroked ring, flat colors, 2dp surface
 * gaps between slices (the gap doubles as CVD-safe secondary encoding — the
 * legend with labels + percentages is the primary identity channel, so color
 * is never the only carrier). Draw-in sweep is skipped under reduced motion.
 */
@Composable
fun BtDonutChart(
    segments: List<DonutSegment>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 18.dp,
) {
    val reducedMotion = rememberReducedMotion()
    val sweep = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(segments, reducedMotion) {
        if (reducedMotion) {
            sweep.snapTo(1f)
        } else {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(durationMillis = 500, easing = FastOutSlowInEasing))
        }
    }

    Canvas(modifier = modifier) {
        val total = segments.sumOf { it.value }
        if (total <= 0.0 || segments.isEmpty()) return@Canvas

        val stroke = strokeWidth.toPx()
        val diameter = min(size.width, size.height) - stroke
        if (diameter <= 0f) return@Canvas
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        // A 2dp arc-length gap between slices, expressed in degrees at this
        // radius; skipped when a slice is too thin to survive it.
        val radius = diameter / 2f
        val gapDeg = ((2.dp.toPx() / (2f * Math.PI.toFloat() * radius)) * 360f)

        var startAngle = -90f
        segments.forEach { segment ->
            val fullSweep = (segment.value / total * 360.0).toFloat() * sweep.value
            val gap = if (fullSweep > gapDeg * 2f) gapDeg else 0f
            val sweepAngle = (fullSweep - gap).coerceAtLeast(0.5f)
            drawArc(
                color = segment.color,
                startAngle = startAngle + gap / 2f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            startAngle += fullSweep
        }
    }
}

/**
 * The chart-series palette — muted categorical ramp for allocation slices.
 * Validated with the dataviz six-checks against the card surface `#171717`
 * (dark band, chroma floor, contrast ≥3:1 all pass; worst CVD pair 8.6 sits in
 * the 8–12 floor band, which is legal because slices always ship secondary
 * encoding: 2dp gaps + a labeled legend with percentages). Gold is the brand
 * accent and NEVER a series color; emerald/red stay reserved for money deltas.
 * Assign slots IN ORDER by descending weight; never cycle past the last slot —
 * fold the tail into "Other" ([rest]) instead.
 */
object BtChartPalette {
    val series: List<Color> = listOf(
        Color(0xFF3987E5), // blue
        Color(0xFF1D9DBF), // cyan
        Color(0xFF6D5BD0), // violet
        Color(0xFFC25B8E), // magenta
        Color(0xFFB58840), // bronze
    )

    /** The fold bucket ("Other") — deliberately reads as neutral, not identity. */
    val rest: Color = Color(0xFF525252)

    /** Cash slice — semantically "uninvested", quiet silver, distinct from [rest]. */
    val cash: Color = Color(0xFF8A8A8A)
}
