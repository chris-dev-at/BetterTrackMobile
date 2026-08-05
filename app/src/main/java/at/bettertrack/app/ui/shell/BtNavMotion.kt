package at.bettertrack.app.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import at.bettertrack.app.navigation.HomeTabRoute
import at.bettertrack.app.navigation.MarketsTabRoute
import at.bettertrack.app.navigation.PeopleTabRoute
import at.bettertrack.app.navigation.PortfolioTabRoute
import at.bettertrack.app.navigation.WorkbenchTabRoute

/**
 * The app's ONE screen-transition spec (R-arc R3, mandate §4 — "keep your
 * existing motion work", so this extends the portfolio rather than replacing it:
 * [at.bettertrack.app.ui.components.btPressScale] still owns press feedback and
 * the chart morphs still own their own data animations; this owns only the
 * hand-off between destinations).
 *
 * ## Why a spec object and not four lambdas inlined in the NavHost
 *
 * Before R3 the graph declared no transitions at all, so all 40-odd destinations
 * inherited navigation-compose's default 700ms cross-fade — the same motion for
 * a peer tab hop and for opening a holding, at a duration that reads as lag on a
 * money screen the user is checking in three seconds. Putting the decision here
 * makes "which motion does this navigation get" a *property of the pair of
 * routes* rather than something each `composable<T>` entry could answer
 * differently, which is exactly how 40 destinations drift into 40 idioms.
 *
 * ## The two idioms, and the rule that picks between them
 *
 * **Lateral — fade-through** ([lateralEnter] / [lateralExit]). The five tabs are
 * peers: there is no "right" or "left" between Home and People, so a directional
 * slide would assert an order the bottom bar does not have and the user would
 * see it contradict itself the moment they hop backwards. MD3's fade-through is
 * the canonical answer: the outgoing screen leaves on its own (90ms), the
 * incoming one arrives into the emptied space (210ms) with a slight scale-up so
 * the arrival has a direction *in depth* instead of in space.
 *
 * **Hierarchical — shared-axis X** ([forwardEnter] / [forwardExit] and their
 * [backEnter] / [backExit] mirrors). Pushing Portfolio → Holding detail *does*
 * have a direction, and it is the one Android's own back gesture already
 * teaches: forward moves the content left, back moves it right. The outgoing
 * screen travels a *shorter* distance than the incoming one (see
 * [SLIDE_PARALLAX_DIVISOR]), which is what makes the new screen read as arriving
 * on top of the old one rather than the two sliding as one filmstrip.
 *
 * Both idioms are 300ms end-to-end with the same 90/210 fade split, so the app
 * has one *rhythm* even where it has two shapes. Their easings are the standard
 * accelerate-on-exit / decelerate-on-entry pair: things leave the screen faster
 * than they arrive, which is what makes a transition feel responsive rather than
 * merely long.
 *
 * ## Portfolio→holding and Markets→asset: why fade-through, not shared element
 *
 * Mandate §4 asks for a shared-element transition on those two pairs and the R3
 * brief allows "or graceful fade-through". Two facts about *this* app's screens
 * made the shared element the wrong build, and both are structural rather than
 * aesthetic:
 *
 *  1. **The identity element is composed twice on the destination.** On both
 *     detail screens the asset's name/ticker lives in
 *     [at.bettertrack.app.ui.components.BtCollapsingHeader], i.e. in M3's
 *     `LargeTopAppBar` title slot — which composes its `title` lambda
 *     simultaneously in the collapsed row and the expanded row and cross-fades
 *     them. A shared key attached there would be claimed by two active
 *     composables at once, which is precisely the case Compose's shared-element
 *     machinery documents as unsupported.
 *  2. **The matched element does not exist when the transition starts.** Both
 *     detail screens open on a skeleton and resolve their asset from a DB/API
 *     flow a frame or more later. A shared element whose counterpart appears
 *     after the animation has begun does not morph — it fades in normally. The
 *     result would be a transition that morphs on a cache hit and cross-fades on
 *     a cold open: non-deterministic motion, which is worse than one consistent
 *     motion.
 *
 * So those two pairs take the hierarchical idiom like every other push, and the
 * "the row opened" reading is carried by the parallax plus the press-scale the
 * row already animates under the finger. Making the morph real needs the detail
 * screens to render their identity synchronously from route arguments — a route
 * -shape change — and that is recorded as an owner-return item rather than
 * half-built here.
 *
 * ## Reduced motion
 *
 * The caller checks [at.bettertrack.app.ui.components.rememberReducedMotion] and
 * passes [EnterTransition.None] / [ExitTransition.None] instead of calling these.
 * It is deliberately NOT checked inside these functions: they are pure and
 * non-composable so the NavHost's non-composable transition lambdas can call
 * them without capturing a composition.
 */
object BtNavMotion {

    /** Total travel of any one navigation. */
    const val DURATION_TOTAL_MS = 300

    /** The outgoing screen's fade — short, so the screen clears out of the way. */
    const val DURATION_EXIT_MS = 90

    /** The incoming screen's fade, delayed by [DURATION_EXIT_MS]. */
    const val DURATION_ENTER_MS = 210

    /**
     * The incoming screen enters from 1/12th of its own width — ~30dp on a
     * 360dp phone, which is the shared-axis distance MD3 specifies. Expressed as
     * a fraction of the measured width rather than a dp constant because these
     * lambdas run outside a `Density` scope.
     */
    private const val SLIDE_DIVISOR = 12

    /**
     * The outgoing screen travels a third as far as the incoming one. That
     * difference IS the parallax: equal distances would read as one sheet of
     * content sliding, where a shorter outgoing travel reads as the old screen
     * being *left behind* by the new one.
     */
    private const val SLIDE_PARALLAX_DIVISOR = 36

    /** Scale the incoming screen starts at in the lateral idiom. */
    private const val LATERAL_INITIAL_SCALE = 0.96f

    /**
     * Every top-level tab route, as the route *key* the graph reports (see
     * [routeKey]). Derived from the route objects themselves — not typed as
     * string literals — so renaming a route in
     * [at.bettertrack.app.navigation] can never silently drop a tab out of the
     * lateral set and give tab hops the push motion.
     */
    val TAB_ROUTE_KEYS: Set<String> = setOf(
        HomeTabRoute::class,
        PortfolioTabRoute::class,
        WorkbenchTabRoute::class,
        MarketsTabRoute::class,
        PeopleTabRoute::class,
    ).mapNotNull { it.qualifiedName }.toSet()

    /**
     * The stable identity of a destination's route, with its arguments stripped.
     *
     * `NavDestination.route` for a typed route is the destination class's
     * qualified name, followed by `/{arg}` for each required argument and
     * `?arg={arg}` for each optional one. Only the part before the first of
     * those separators identifies the *destination*, and that is all this spec
     * ever needs to know.
     */
    fun routeKey(route: String?): String? =
        route?.substringBefore('/')?.substringBefore('?')?.takeIf { it.isNotEmpty() }

    /**
     * True when a navigation runs between two of the five peer tabs — the only
     * case that gets the fade-through.
     *
     * Note it requires BOTH sides to be tabs. Leaving a tab for a pushed screen,
     * or returning to one, is hierarchical in both directions; only tab↔tab is
     * lateral.
     */
    fun isLateral(fromRoute: String?, toRoute: String?): Boolean {
        val from = routeKey(fromRoute) ?: return false
        val to = routeKey(toRoute) ?: return false
        return from in TAB_ROUTE_KEYS && to in TAB_ROUTE_KEYS
    }

    // ── Lateral (tab ↔ tab): MD3 fade-through ───────────────────────────────

    fun lateralEnter(): EnterTransition =
        fadeIn(
            animationSpec = tween(
                durationMillis = DURATION_ENTER_MS,
                delayMillis = DURATION_EXIT_MS,
                easing = LinearOutSlowInEasing,
            ),
        ) + scaleIn(
            initialScale = LATERAL_INITIAL_SCALE,
            animationSpec = tween(
                durationMillis = DURATION_ENTER_MS,
                delayMillis = DURATION_EXIT_MS,
                easing = LinearOutSlowInEasing,
            ),
        )

    fun lateralExit(): ExitTransition =
        fadeOut(animationSpec = tween(DURATION_EXIT_MS, easing = FastOutLinearInEasing))

    // ── Hierarchical (push / pop): MD3 shared-axis X ────────────────────────

    /** The pushed screen arriving. */
    fun forwardEnter(): EnterTransition =
        slideInHorizontally(
            animationSpec = tween(DURATION_TOTAL_MS, easing = FastOutSlowInEasing),
            initialOffsetX = { it / SLIDE_DIVISOR },
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = DURATION_ENTER_MS,
                delayMillis = DURATION_EXIT_MS,
                easing = LinearOutSlowInEasing,
            ),
        )

    /** The screen being pushed over. */
    fun forwardExit(): ExitTransition =
        slideOutHorizontally(
            animationSpec = tween(DURATION_TOTAL_MS, easing = FastOutSlowInEasing),
            targetOffsetX = { -it / SLIDE_PARALLAX_DIVISOR },
        ) + fadeOut(animationSpec = tween(DURATION_EXIT_MS, easing = FastOutLinearInEasing))

    /** The screen being returned to. */
    fun backEnter(): EnterTransition =
        slideInHorizontally(
            animationSpec = tween(DURATION_TOTAL_MS, easing = FastOutSlowInEasing),
            initialOffsetX = { -it / SLIDE_PARALLAX_DIVISOR },
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = DURATION_ENTER_MS,
                delayMillis = DURATION_EXIT_MS,
                easing = LinearOutSlowInEasing,
            ),
        )

    /** The screen being popped off. */
    fun backExit(): ExitTransition =
        slideOutHorizontally(
            animationSpec = tween(DURATION_TOTAL_MS, easing = FastOutSlowInEasing),
            targetOffsetX = { it / SLIDE_DIVISOR },
        ) + fadeOut(animationSpec = tween(DURATION_EXIT_MS, easing = FastOutLinearInEasing))
}
