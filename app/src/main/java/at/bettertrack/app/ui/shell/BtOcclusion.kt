package at.bettertrack.app.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How much of the window a settled sheet leaves uncovered, in px from the top —
 * or [NOT_COVERED] when the pages behind it are visible and must be drawn.
 *
 * A sheet at rest is opaque and full-bleed from its top edge down, so everything
 * below that edge is dead pixels: rasterised, then painted over. This is the rule
 * that says so, kept pure and away from Compose so it can be tested on the JVM.
 *
 * The uncovered strip is the sheet's top edge PLUS its corner radius, because the
 * 28dp rounded corners leave the backdrop showing in two small curves beside the
 * sheet's first 28dp. Clipping to that line is exact, not approximate: nothing
 * below it can ever be seen.
 *
 * Any travel at all — a dismiss pull, a depth-1 close drag, a predictive-back
 * preview — moves the sheet down and immediately exposes the pages again, so the
 * test is `travel == 0f` and nothing softer. `travel` is written synchronously by
 * the finger (see [BtSheetMotion]), so the pages come back on the *same frame*
 * the sheet starts to move, with no crossfade to schedule and no frame in which
 * the wrong thing is on screen.
 */
internal fun sheetExposedTopPx(travel: Float, sheetTopPx: Float, cornerPx: Float): Float =
    if (travel != 0f) NOT_COVERED else sheetTopPx + cornerPx

/** "Draw everything" — no sheet, or a sheet that is not at its resting height. */
internal const val NOT_COVERED = -1f

/**
 * The channel between the sheet layer, which knows what it covers, and the shell,
 * which owns the pages being covered.
 *
 * ## Why this exists
 *
 * Measured on the test phone (2026-08-09, 120Hz panel, 8.33ms budget): a two-plane
 * sheet drag spent **10.98ms** per frame on the RenderThread and GPU against the
 * tab pager's **3.91ms**, and every frame still presented — so the symptom was
 * never dropped frames, it was ~7ms of pipeline *latency*, which is what the owner
 * felt as "laggy". The cause is overdraw: each frame rasterised the whole tab
 * pager, the bottom bar and the header, then a full-screen scrim, then an opaque
 * full-screen sheet over the lot.
 *
 * So the shell stops drawing what cannot be seen. [probe] is installed by the
 * sheet layer for as long as one exists and answers [sheetExposedTopPx]; the shell
 * clips its draw to that strip.
 *
 * ## Draw phase only
 *
 * [exposedTopPx] must be called from inside a draw lambda and nowhere else. That
 * is not a style rule: it is what keeps the mechanism cheap. The value behind
 * [probe] is a `derivedStateOf`, so a snapshot read of it from the draw phase
 * re-records the shell's display list **only when coverage flips**, not on every
 * frame of a drag that moves the sheet. Read it in composition instead and every
 * pixel of travel would recompose the four tab pages.
 *
 * Composition, layout, semantics and every state holder underneath are untouched:
 * this skips *raster*, so nothing resets, no ViewModel is disposed, predictive
 * back and process-death restore behave exactly as they did, and the pages are
 * still there — being drawn again is a clip away.
 */
@Stable
internal class BtOcclusion {

    /** Installed by the live sheet layer; `null` when no sheet layer is mounted. */
    var probe: (() -> Float)? by mutableStateOf(null)

    /** DRAW PHASE ONLY — see the class KDoc. */
    fun exposedTopPx(): Float = probe?.invoke() ?: NOT_COVERED
}
