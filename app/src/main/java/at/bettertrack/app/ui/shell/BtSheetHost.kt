package at.bettertrack.app.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The shell's answer to "what does back mean right now".
 *
 * The graph knows what is on the back stack. What it cannot know is that the top
 * entry has an *animation to run before it may be popped* — `popBackStack()`
 * deletes the destination on the spot, so a screen calling its own `onBack`
 * would make the sheet vanish rather than travel away. So the shell keeps the one
 * thing the graph cannot: the open sheet layer's dismissal. `back` resolves to
 * [dismissTop], the layer animates, and only then does it call [pop].
 *
 * Plain lists and no snapshot state: nothing composable reads this, it is written
 * and read from effects and callbacks on the main thread only.
 *
 * @param pop remove the top entry from the graph, at the end of the animation.
 * @param popAll clear the WHOLE sheet stack back to the floor — the stage-two
 *   half of the swipe-down. Defaults to [pop] so a host wired only for
 *   single-level behaviour degrades to it rather than doing nothing.
 */
@Stable
internal class BtSheetHostState(
    val pop: () -> Unit,
    popAll: (() -> Unit)? = null,
) {
    val popAll: () -> Unit = popAll ?: pop

    private val open = mutableListOf<() -> Unit>()

    fun push(dismiss: () -> Unit) {
        open += dismiss
    }

    fun remove(dismiss: () -> Unit) {
        open -= dismiss
    }

    /** Ask the topmost sheet layer to leave. A no-op when nothing is open. */
    fun dismissTop() {
        open.lastOrNull()?.invoke()
    }

    /** How many sheet layers are registered. */
    val depth: Int get() = open.size
}

/**
 * The shell's sheet host, for content composed inside the sheet layer to find.
 */
internal val LocalBtSheetHost = staticCompositionLocalOf<BtSheetHostState> {
    error("No BtSheetHostState — a sheet was composed outside BtSheetHost")
}
