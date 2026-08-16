package at.bettertrack.app.ui.charts

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import at.bettertrack.app.ui.components.BtHaptics
import at.bettertrack.app.ui.components.rememberBtHaptics
import kotlin.math.abs

/**
 * The two things every scrubbable chart in the app does while a finger is on it,
 * kept in one place so all of them do it identically (owner order 2026-08-10).
 *
 *  1. **A haptic tick per point crossed** — [BtScrubTicker] / [nextScrubTick].
 *  2. **Dimming everything to the right of the crosshair** — [drawScrubFuture].
 *
 * Both are deliberately *display* concerns: nothing here touches a value, and
 * neither depends on what the series means.
 */

// ═══════════════════════════ 1. The detent tick ═════════════════════════════

/**
 * The last tick that actually fired: which point it was on, where the finger was,
 * and when. Not "the last crossing" — a suppressed crossing leaves this alone on
 * purpose, so the throttle always measures from the last thing the user FELT.
 */
internal data class ScrubTick(val index: Int, val x: Float, val atMs: Long)

/**
 * The floor between two ticks.
 *
 * **Owner correction 2026-08-17: the ticks are too slow — he wants a faster,
 * denser bezel.** So this went 55ms → 30ms, capping the cadence at ~33
 * ticks/second instead of ~18.
 *
 * The 55ms it replaces was reasoned from the effect's own length: `EFFECT_TICK`
 * measures ~61ms on the test phone's LRA, and the assumption was that a tick may
 * not start before the previous one has finished, or the row of detents would
 * smear into one continuous buzz. That assumption is what turned out to be too
 * conservative. A re-triggered LRA does not sum with itself — the new
 * `vibrate()` REPLACES what is playing, so a tick that arrives at 30ms truncates
 * its predecessor's decay rather than piling onto it. What the finger feels is
 * the attack, and the attack is the front few milliseconds; cutting the tail is
 * how a bezel gets crisper, not muddier. It only smears if ticks arrive faster
 * than the motor can restate an attack, which is far below 30ms.
 *
 * 30ms is also comfortably above the frame budget, so the cap is a property of
 * the haptic rather than an accident of how often Compose delivers pointer
 * events.
 *
 * The other floor ([SCRUB_TICK_MIN_STEP_PX]) is deliberately unchanged: this
 * value governs how fast a *moving* finger may ring the motor, and nothing about
 * wanting that faster means a still hand should start buzzing.
 */
internal const val SCRUB_TICK_MIN_INTERVAL_MS = 30L

/**
 * The floor on how far the finger must travel between two ticks, in pixels.
 *
 * The interval alone does not stop a finger resting exactly on a cell boundary:
 * a 1px wobble flips the snapped index back and forth and would ring the motor
 * ~18 times a second while the hand is, to its owner, holding still. A real
 * bezel does not do that, so neither does this.
 */
internal const val SCRUB_TICK_MIN_STEP_PX = 5f

/**
 * Should this crossing ring, and what does that make the new state?
 *
 * Returns the [ScrubTick] to record when the motor should fire, or `null` to stay
 * silent and keep [prev] — see [ScrubTick] for why the suppressed case must not
 * advance the state.
 *
 * `prev == null` means the drag just began, and the first touch always ticks: it
 * is the "you are now scrubbing" confirmation, and it is what makes the control
 * feel like it engaged rather than like it might have.
 *
 * ## Why the index, and not the pixel, is the unit
 *
 * The owner asked for a tick per *point* — the bezel's detents are the data, not
 * the distance. Both scrubbable charts snap their readout to a real point over a
 * uniform index grid, so "the index changed" is exactly "the crosshair moved onto
 * a different point". A dense range can put several hundred of those under a
 * 400px canvas, which is what the two floors above are for: past that density the
 * felt cadence is capped, but every tick still coincides with a genuine point
 * crossing rather than being a metronome running under the finger.
 *
 * Pure, so the cadence is a tested fact — see `ChartScrubTest`.
 */
internal fun nextScrubTick(
    prev: ScrubTick?,
    index: Int,
    x: Float,
    nowMs: Long,
    minIntervalMs: Long = SCRUB_TICK_MIN_INTERVAL_MS,
    minStepPx: Float = SCRUB_TICK_MIN_STEP_PX,
): ScrubTick? {
    val tick = ScrubTick(index, x, nowMs)
    if (prev == null) return tick
    if (index == prev.index) return null
    if (nowMs - prev.atMs < minIntervalMs) return null
    if (abs(x - prev.x) < minStepPx) return null
    return tick
}

/**
 * The scrub's haptic channel, held by a chart for the life of its composition.
 *
 * Stateful because the throttle is: [crossed] is called from a pointer handler on
 * the frame the snapped index changes, and everything about whether it rings is
 * in [nextScrubTick].
 */
@Stable
class BtScrubTicker internal constructor(
    private val haptics: BtHaptics,
    private val clock: () -> Long,
) {
    private var last: ScrubTick? = null

    /** The crosshair moved onto point [index], with the finger at [x] pixels. */
    fun crossed(index: Int, x: Float) {
        val next = nextScrubTick(last, index, x, clock()) ?: return
        last = next
        haptics.scrubTick()
    }

    /**
     * The finger left the chart (or the series changed under it).
     *
     * Clearing is what makes the *next* drag's first touch tick again — see
     * [nextScrubTick]'s null case.
     */
    fun end() {
        last = null
    }
}

/**
 * The ticker for the chart being composed.
 *
 * `SystemClock.uptimeMillis` rather than wall time: it is monotonic, it is the
 * same clock Compose's own input events are stamped with, and a user changing
 * time zones mid-scrub is not a case worth a bug.
 */
@Composable
fun rememberBtScrubTicker(): BtScrubTicker {
    val haptics = rememberBtHaptics()
    return remember(haptics) { BtScrubTicker(haptics) { SystemClock.uptimeMillis() } }
}

// ═══════════════════════ 2. Dimming the unread future ═══════════════════════

/**
 * Grey out the part of the plot to the RIGHT of the crosshair at [x].
 *
 * **Owner order 2026-08-10:** *"grey out or give opacity to the stuff to the
 * right of the point you are looking at."* Reading a chart with a crosshair on it
 * is reading a moment, and the stretch after that moment is information the
 * reader has explicitly stepped back from — dimming it says so, and it makes the
 * value under the finger the brightest thing on the canvas.
 *
 * It is a scrim in the chart's own container colour rather than a repaint of the
 * curve at lower opacity, for two reasons that both matter:
 *
 *  - it is O(1) — one rect — where a repaint is a second rasterisation of the
 *    path and the gradient on every point crossed;
 *  - it lives on the CROSSHAIR canvas, so the series layer keeps reading no
 *    scrub state at all. That separation is the reason scrubbing this app's
 *    charts is cheap (see [BtAreaChart]'s header), and it would have been the
 *    first casualty of the other approach.
 *
 * Confined to the plot rect: the x-label strip underneath is the axis, not the
 * future, and greying half of it would just look like a rendering fault.
 */
internal fun DrawScope.drawScrubFuture(x: Float, plotHeight: Float, scrim: Color) {
    val width = size.width - x
    if (width <= 0f || plotHeight <= 0f) return
    drawRect(color = scrim, topLeft = Offset(x, 0f), size = Size(width, plotHeight))
}
