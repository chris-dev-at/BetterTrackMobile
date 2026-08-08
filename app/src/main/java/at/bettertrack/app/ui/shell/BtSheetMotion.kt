package at.bettertrack.app.ui.shell

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * One axis of the sheet's motion: a value a finger writes directly and an
 * animation writes gradually, with the finger always winning.
 *
 * ## Why this exists instead of [androidx.compose.animation.core.Animatable]
 *
 * `Animatable.snapTo` is a **suspend** function guarded by a `MutatorMutex`, so
 * driving a drag with it means `scope.launch { travel.snapTo(next) }` on every
 * pointer event. That was the shipped build's drag path, and profiling the real
 * device (2026-08-09, 120Hz panel, 8.33ms budget) showed exactly what it costs:
 * the sheet's two-plane drag spent **4.32ms per frame** in the Choreographer's
 * animation callback — the slot where `AndroidUiDispatcher` runs Compose's queued
 * coroutines — against **0.00ms** for the main tab pager doing structurally the
 * same job. Total UI-thread time per frame: 5.68ms for the sheet, 1.61ms for the
 * pager. Recomposition and layout were never involved (0.10ms); it was the
 * coroutine machinery alone.
 *
 * Two things were wrong with it, and both are the same mistake:
 *
 *  1. **Cost.** Every touch move allocated a coroutine and contended a mutex,
 *     and because `MutatorMutex` cancels the previous holder, most of those
 *     coroutines existed only to cancel the one before them.
 *  2. **Latency.** `AndroidUiDispatcher` defers to the next frame's animation
 *     callback, so the value the finger asked for on frame N was not applied
 *     until frame N+1. A drag that is always one frame behind the thumb is the
 *     "laggy" the owner reported, and no amount of raster tuning can remove it.
 *
 * A drag is not an animation. It is a value the hardware already knows, and the
 * only correct thing to do with it is write it down. [snap] does that: one int
 * increment and one snapshot write, synchronously, on the frame the event
 * arrived. Animations still need a coroutine — they are genuinely time-driven —
 * so [animateTo] keeps one.
 */
@Stable
internal class BtSheetMotion(initial: Float) {

    /** Where this axis is now. 0f..1f, read from layer blocks every frame. */
    var value by mutableFloatStateOf(initial)
        private set

    /**
     * Which writer owns the value. Bumped by every [snap] and every [animateTo],
     * so a running animation can notice it has been superseded and stand down.
     * This is what [androidx.compose.animation.core.Animatable]'s `MutatorMutex`
     * did, minus the suspension — and the suspension was the whole problem.
     */
    private var epoch = 0

    /**
     * The finger. Synchronous by design: no dispatch, no coroutine, no frame of
     * delay, and it silently outranks any animation already in flight.
     */
    fun snap(target: Float) {
        epoch++
        value = target
    }

    /**
     * Everything not driven by a finger — settling back, sliding a depth, leaving.
     *
     * Yields the instant a [snap] or a later [animateTo] takes over, rather than
     * fighting it for the value. Returns normally when superseded: the caller is
     * a motion that no longer matters, not an error.
     */
    suspend fun animateTo(target: Float, spec: AnimationSpec<Float>) {
        val mine = ++epoch
        AnimationState(value).animateTo(target, spec) {
            if (epoch != mine) cancelAnimation() else this@BtSheetMotion.value = this.value
        }
    }
}
