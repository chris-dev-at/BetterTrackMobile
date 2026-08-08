package at.bettertrack.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.Navigator
import androidx.navigation.NavigatorState
import androidx.navigation.get
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * The sheet stack, as a navigator of its own.
 *
 * ## Why the subpages left `ComposeNavigator`
 *
 * The 2026-08-08 architecture put every subpage in a full-screen sheet, and the
 * first build of it registered each one as an ordinary `composable<T>` that
 * wrapped itself in a `BtSheet`. That works for one sheet and breaks the moment
 * there are two, because a `NavHost` composes **one destination at a time**: the
 * entry underneath is dropped the instant its exit transition ends. Everything
 * the owner rejected on 2026-08-08 followed from that single fact —
 *
 *  - a rightward back-swipe revealed the sheet's own empty background rather than
 *    the page being returned to (*"goes blank"*), because there was nothing
 *    composed to reveal;
 *  - the page that came back arrived through a graph transition rather than by
 *    sliding under the finger (*"something shifts up and down"*, *"some separate
 *    popups"*), because a transition is the only tool a `NavHost` has for a
 *    destination that was not there a frame ago.
 *
 * No amount of tuning fixes that from inside a `NavHost`. The covered page has to
 * be **composed**, which means something other than the `NavHost` has to render
 * it — and that something needs the back stack, not a copy of it.
 *
 * ## What this is
 *
 * A [Navigator] owns a back stack of its own inside the one `NavController`, and
 * it is handed that stack as a `StateFlow` it may render however it likes. That
 * is exactly how `DialogNavigator` puts dialogs over the `NavHost`, and it is the
 * shape this needs: [backStack] is the live, ordered truth, and [BtSheetStack]
 * renders the top TWO entries of it into one persistent container.
 *
 * Nothing else moves. Routes are still typed and `kotlinx.serialization`-backed,
 * arguments still parse the same way, every entry still gets its own
 * `ViewModelStore` and `SavedStateRegistry`, the system-back dispatcher and
 * process-death restore are still the `NavController`'s, and deep links still
 * land through the same `navigate()` calls. The graph remains the source of
 * truth; what changed is who draws it.
 *
 * ## `FloatingWindow`
 *
 * A sheet does not replace what is under it — that is the whole premise — and
 * [FloatingWindow] is how a destination says so to the `NavController`. The
 * practical effect is the one this architecture needs: entries below a floating
 * destination stay STARTED rather than being stopped, so the parent page that is
 * visible under the finger during a back-swipe is not merely *drawn*, it is
 * **live**. It is also what keeps the `NavHost`'s own empty floor composed while
 * sheets are stacked over it.
 */
@Navigator.Name(BtSheetNavigator.NAME)
internal class BtSheetNavigator : Navigator<BtSheetNavigator.Destination>() {

    /**
     * Whether the `NavController` has handed this navigator its state yet.
     *
     * Snapshot-backed on purpose: [BtSheetStack] reads [backStack] and would
     * otherwise have no reason to recompose when the graph is created, leaving
     * the layer bound to nothing for the life of the process.
     */
    var attached by mutableStateOf(false)
        private set

    override fun onAttach(state: NavigatorState) {
        super.onAttach(state)
        attached = true
    }

    /**
     * The live sheet stack, bottom-most first.
     *
     * Only legible once [attached]; reading it before then throws, which is why
     * every caller goes through that flag rather than through a null check.
     */
    val backStack: StateFlow<List<NavBackStackEntry>>
        get() = state.backStack

    // `navigate` and `popBackStack` are deliberately NOT overridden. The base
    // implementations push and pop through `state`, which is precisely what is
    // wanted: this navigator adds no motion of its own, because the motion is
    // [BtSheetStack]'s and it runs BEFORE the pop (a sheet animates itself away
    // and only then asks the graph to forget it).
    override fun createDestination(): Destination = Destination(this) { }

    /**
     * One subpage.
     *
     * @param content the screen, exactly as `composable<T>` took it — same
     *   `NavBackStackEntry` argument, same `entry.toRoute<T>()` inside it. It is
     *   invoked by [BtSheetStack], not by the `NavHost`.
     */
    class Destination(
        navigator: Navigator<out Destination>,
        val content: @Composable (NavBackStackEntry) -> Unit,
    ) : NavDestination(navigator), FloatingWindow

    companion object {
        const val NAME: String = "bt-sheet"
    }
}

/** The typed-route builder for one [BtSheetNavigator.Destination]. */
internal class BtSheetDestinationBuilder(
    navigator: BtSheetNavigator,
    route: KClass<*>,
    typeMap: Map<KType, NavType<*>>,
    private val content: @Composable (NavBackStackEntry) -> Unit,
) : NavDestinationBuilder<BtSheetNavigator.Destination>(navigator, route, typeMap) {
    override fun instantiateDestination(): BtSheetNavigator.Destination =
        BtSheetNavigator.Destination(navigator, content)
}

/**
 * Register one subpage route, as a full-screen sheet.
 *
 * Drop-in for `composable<T>`, and unchanged from the call site's point of view
 * across this rewrite: same single type argument, same `(NavBackStackEntry) ->
 * Unit` content lambda, same trailing-lambda shape. All 47 registrations in
 * [at.bettertrack.app.ui.shell.BtApp]'s graph are byte-for-byte what they were —
 * only what happens to the lambda changed, and it changed on this side of the
 * call.
 */
internal inline fun <reified T : Any> NavGraphBuilder.btSheet(
    typeMap: Map<KType, NavType<*>> = emptyMap(),
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    destination(
        BtSheetDestinationBuilder(
            navigator = provider[BtSheetNavigator::class],
            route = T::class,
            typeMap = typeMap,
            content = content,
        ),
    )
}
