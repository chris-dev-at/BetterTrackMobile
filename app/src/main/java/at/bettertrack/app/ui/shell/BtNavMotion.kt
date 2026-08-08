package at.bettertrack.app.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/**
 * The app's ONE graph-level transition spec.
 *
 * ## What this file used to be, and why almost all of it is gone
 *
 * R3 gave the graph two idioms and a rule that picked between them by the PAIR of
 * routes: a **lateral** shallow slide for tab↔tab, and a **hierarchical**
 * shared-axis X for push/pop. Both are retired by the 2026-08-08 architecture
 * change, and neither was replaced by a different animation — the navigations
 * they animated stopped existing.
 *
 *  - **Lateral** animated a hop between two tab destinations. There are no tab
 *    destinations: the four pages live in [BtTabPager] and a hop is a *scroll*,
 *    driven by the finger or by a tween, with the pages simply where they are.
 *    The gesture is the motion; a transition on top of it would be a second page
 *    turn over a picture that is already correct.
 *  - **Hierarchical** animated a subpage being pushed over a tab. Subpages are
 *    not pushed over anything now — every one is a [BtSheet], which owns its
 *    whole travel because a *drag* and a *transition* cannot share the job (see
 *    that file). So the graph contributes `None` on the way in and `None` on the
 *    way back out.
 *
 * ## What is left, and why the graph still has to own it
 *
 * Exactly one case a sheet cannot see: **a sheet opening over another sheet.**
 *
 * The lower sheet is the outgoing destination, and left at `ExitTransition.None`
 * it is dropped from composition the moment the upper one is pushed — so a live
 * tab page would flash through the gap for a frame while the new sheet was still
 * sliding up, and the lower sheet would stop ticking. An exit transition is the
 * only thing that keeps a destination composed for a known duration, which makes
 * this the graph's job by construction rather than by preference.
 *
 * Since it has to exist, it may as well say something true: the lower sheet sinks
 * back a little rather than merely waiting. That is the stack idiom the owner's
 * reference app uses, and it is an honest picture — there really is something
 * behind the new sheet, and it really is still there. The DIMMING is not done
 * here; see [stackRecede] for the device check that settled that.
 *
 * ## Reduced motion
 *
 * Not checked here. These are pure, non-composable functions so the NavHost's
 * non-composable transition lambdas can call them without capturing a
 * composition; the caller checks
 * [at.bettertrack.app.ui.components.rememberReducedMotion]. The sheet's own
 * travel does check it, which is where a reduced-motion user notices the
 * difference — this pair only ever runs one level deep.
 */
object BtNavMotion {

    /**
     * How long a sheet-over-sheet hand-over lasts.
     *
     * Deliberately the same 300ms the retired idioms used end to end: the app's
     * *rhythm* is the thing worth keeping from R3 even though its shapes are
     * gone, and this is now the only place the graph spends time.
     */
    const val DURATION_TOTAL_MS = 300

    /**
     * How far back the covered sheet sits.
     *
     * Small on purpose. Only the top ~8dp strip of the lower sheet is ever
     * visible under the upper one, so this is read almost entirely at that edge;
     * anything deeper would show as a hard inset appearing at the corners rather
     * than as depth.
     */
    private const val STACK_SCALE = 0.94f

    /**
     * ## Scale only — never alpha (device check 2026-08-08)
     *
     * The first build of this pair also faded the covered sheet to 60%. On device
     * that is unmistakably wrong, and the screenshot is unambiguous: a sheet's
     * surface is OPAQUE, and fading the whole destination makes it translucent —
     * so the live tab page underneath ghosts straight through the receding sheet,
     * and for ~300ms the user is looking at two pages at once.
     *
     * The dimming was never this transition's to do. The arriving sheet draws its
     * own scrim behind itself ([BtSheet]), which darkens everything below it
     * including this one, at exactly the rate the sheet arrives. Fading here was
     * the same job done twice, and the second copy is the one that could see
     * through the furniture.
     */
    fun stackRecede(): ExitTransition =
        scaleOut(
            targetScale = STACK_SCALE,
            animationSpec = tween(DURATION_TOTAL_MS, easing = FastOutLinearInEasing),
        )

    /** The sheet underneath, coming back as the one over it leaves. */
    fun stackReturn(): EnterTransition =
        scaleIn(
            initialScale = STACK_SCALE,
            animationSpec = tween(DURATION_TOTAL_MS, easing = LinearOutSlowInEasing),
        )
}
