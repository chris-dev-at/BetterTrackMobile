package at.bettertrack.app.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Every subpage, as a full-screen sheet that comes up from the bottom and can be
 * pulled back down (owner order 2026-08-08, standing board directive).
 *
 * Owner verbatim: *"every subpage behaves like a popup that comes from the bottom
 * up and can be swiped down. Y'know like the portfolio selector looking but like
 * full page."*
 *
 * ## What this replaces
 *
 * The pushed-route idiom. A holding detail used to be a NavHost destination that
 * *replaced* the tab underneath it; now it is a sheet *over* the tab, which stays
 * composed, stays scrolled where it was, and keeps ticking. That is not only the
 * look the owner asked for — it is what frees the four tabs from the graph, and
 * so it is the same change as [BtTabPager], seen from the other end.
 *
 * ## The rules it holds itself to
 *
 *  - **Never a trap.** Three ways out, always: the screen's own top-bar back
 *    affordance, a pull down from the top of its scroll, and system/predictive
 *    back. The scrim strip above the sheet dismisses on tap as a fourth.
 *  - **Pull-down arms at scroll top only.** The drag is taken through nested
 *    scroll `onPostScroll` — i.e. only what the content did NOT consume — so a
 *    list mid-scroll scrolls, and the same finger continuing past the top pulls
 *    the sheet. The collapsing headers sit between the two and expand first,
 *    which is the correct order and costs nothing to arrange: it is just where
 *    they already are in the nested-scroll chain.
 *  - **The chrome is a grabber.** Title and close come from the screen's own top
 *    bar, which all 45 of them already carry, already localized, already in the
 *    app's two bar idioms. Drawing a second title strip above them would put
 *    every subpage's name on the screen twice.
 *  - **Insets are consumed at the boundary.** The sheet sits below the status
 *    bar and consumes it, so the 44 screens whose top bar silently pays
 *    `TopAppBarDefaults.windowInsets` do not pay it a second time inside a sheet
 *    that is already clear of the system bar.
 *
 * ## Why the sheet animates itself instead of using NavHost transitions
 *
 * A transition runs between two *committed* states, and a drag is neither. If the
 * exit were a `slideOutVertically` the sheet would snap back to rest and then
 * play a 300ms slide from there the moment the finger let go past the threshold —
 * the jump every hand-rolled bottom sheet gets wrong once. Owning the whole
 * travel means the release simply continues the number the finger was already
 * writing. The graph's job shrinks to *what is on the stack*, which is the part
 * it is good at.
 *
 * @param host the shell's sheet stack. The sheet registers its own dismissal with
 *   it on the way in and withdraws it on the way out, which is what lets a
 *   screen's plain `onBack: () -> Unit` reach whichever sheet is on top without
 *   any screen knowing that sheets exist.
 */
@Composable
internal fun BtSheet(
    host: BtSheetHostState,
    content: @Composable () -> Unit,
) {
    val bt = BtTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val onPoppedState = rememberUpdatedState(host.pop)

    // 0f = settled at its rest position, 1f = fully below the bottom edge. One
    // number, read by the scrim's alpha and the surface's translation, so the two
    // cannot drift apart.
    val travel = remember { Animatable(1f) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    val flingVelocityPx = with(density) { SHEET_DISMISS_VELOCITY.toPx() }

    LaunchedEffect(Unit) {
        if (reducedMotion) travel.snapTo(0f) else travel.animateTo(0f, SheetEnter)
    }

    val dismiss: () -> Unit = remember {
        {
            if (!leaving) {
                leaving = true
                scope.launch {
                    if (!reducedMotion) travel.animateTo(1f, SheetExit)
                    onPoppedState.value()
                }
            }
        }
    }
    val settle: () -> Unit = remember {
        { scope.launch { travel.animateTo(0f, SheetSettle) }; Unit }
    }
    // Join the stack. This is the whole of how 45 screens dismiss a sheet without
    // a single one of them taking a new parameter: they still call the same
    // `onBack: () -> Unit` they always did, the shell still builds it in one
    // place, and what it now resolves to is "whichever sheet is on top".
    DisposableEffect(dismiss) {
        host.push(dismiss)
        onDispose { host.remove(dismiss) }
    }

    // System back and the predictive-back gesture drive the same travel the finger
    // does, so a back swipe previews the dismiss and an abandoned one springs back.
    // A three-button back arrives as a single 0 -> complete, which lands on the
    // ordinary exit animation — the same code path, no branch.
    PredictiveBackHandler(enabled = !leaving) { progress ->
        try {
            progress.collect { event ->
                travel.snapTo((event.progress * SHEET_BACK_PREVIEW).coerceIn(0f, 1f))
            }
            leaving = true
            if (!reducedMotion) travel.animateTo(1f, SheetExit)
            onPoppedState.value()
        } catch (cancelled: CancellationException) {
            // The gesture was abandoned. The animation has to run on the
            // composition's scope, not this one: this coroutine is already dead.
            if (!leaving) settle()
            throw cancelled
        }
    }

    // Pull-down from the top of the content. `onPostScroll` and not `onPreScroll`
    // is the whole "arms at scroll top only" rule: this connection only ever sees
    // what the list underneath refused, and a list that is not at its top refuses
    // nothing.
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Going back UP while the sheet is displaced belongs to the sheet,
                // before the content gets it — otherwise the list would start
                // scrolling with the sheet still hanging half a screen down.
                if (source != NestedScrollSource.UserInput || leaving) return Offset.Zero
                if (available.y >= 0f || travel.value <= 0f) return Offset.Zero
                return Offset(0f, drag(available.y))
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || leaving) return Offset.Zero
                if (available.y <= 0f) return Offset.Zero
                return Offset(0f, drag(available.y))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (leaving || travel.value <= 0f) return Velocity.Zero
                release(available.y)
                return available
            }

            private fun drag(dy: Float): Float {
                val h = heightPx
                if (h <= 0f) return 0f
                val next = sheetDragTravel(travel.value, dy, h)
                val used = (next - travel.value) * h
                scope.launch { travel.snapTo(next) }
                return used
            }

            private fun release(velocityY: Float) {
                if (sheetDismissOnRelease(travel.value, velocityY, flingVelocityPx)) {
                    dismiss()
                } else {
                    settle()
                }
            }

            fun releaseFromGrabber(velocityY: Float) = release(velocityY)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (1f - travel.value) * SHEET_SCRIM_ALPHA }
                .background(bt.scrim)
                // The strip above the sheet is the only part of this the user can
                // reach, and tapping outside a sheet to close it is the idiom.
                // Everywhere else the surface is on top and takes the pointer.
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
        )
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Pads AND consumes: children asking for the status bar — which is
                // every M3 top bar by default — get zero, because this boundary has
                // already paid it.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = SHEET_TOP_GAP)
                .onSizeChanged { heightPx = it.height.toFloat() }
                .graphicsLayer { translationY = travel.value * heightPx }
                .nestedScroll(pull),
            shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
            color = bt.bg,
        ) {
            Column(Modifier.fillMaxSize()) {
                BtSheetGrabber(
                    onDrag = { dy ->
                        val h = heightPx
                        if (h > 0f && !leaving) {
                            scope.launch { travel.snapTo(sheetDragTravel(travel.value, dy, h)) }
                        }
                    },
                    onRelease = { v -> pull.releaseFromGrabber(v) },
                )
                content()
            }
        }
    }
}

/**
 * The handle at the top of every sheet — the one piece of chrome the sheet adds
 * to the screen it hosts.
 *
 * Always draggable, whatever the content is doing: it is the escape hatch for the
 * two screens that do not scroll at all (the app-lock setup and the language
 * list), where a pull-from-scroll-top has nothing to arm against.
 */
@Composable
private fun BtSheetGrabber(
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
) {
    val bt = BtTheme.colors
    val label = stringResource(R.string.bt_sheet_grabber_cd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SHEET_GRABBER_ZONE)
            .draggable(
                state = rememberDraggableState(onDrag),
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> onRelease(velocity) },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = SHEET_GRABBER_WIDTH, height = SHEET_GRABBER_HEIGHT)
                .clip(CircleShape)
                .background(bt.textMuted.copy(alpha = SHEET_GRABBER_ALPHA)),
        )
    }
}

// ── Pure rules, so the sheet's behaviour is pinned without a device ─────────

/**
 * Where a drag of [dy] px puts the sheet, as a fraction of its own height.
 *
 * Clamped at both ends: 0f because a sheet cannot be pulled up past its rest
 * position (there is nothing above it but the scrim strip), and 1f because one
 * height of travel is the whole dismissal.
 */
internal fun sheetDragTravel(current: Float, dy: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return current
    return (current + dy / heightPx).coerceIn(0f, 1f)
}

/**
 * Whether letting go here dismisses the sheet, or springs it back.
 *
 * Distance OR velocity, and the velocity only counts when it is still travelling
 * DOWN — so a sheet dragged halfway open and flicked back up settles, which is
 * the same rule the retired tab swipe used and the one users have in their hands
 * from every other sheet on the phone.
 */
internal fun sheetDismissOnRelease(
    travel: Float,
    velocityY: Float,
    velocityThresholdPx: Float,
): Boolean {
    if (velocityY <= -velocityThresholdPx) return false
    return travel >= SHEET_DISMISS_FRACTION || velocityY >= velocityThresholdPx
}

/** How far down a release must have carried the sheet to commit to leaving. */
internal const val SHEET_DISMISS_FRACTION = 0.28f

/** A flick this fast dismisses whatever distance it covered. */
private val SHEET_DISMISS_VELOCITY: Dp = 420.dp

/**
 * How much of the sheet's height a completed predictive-back preview shows.
 *
 * Not 1f: the preview is a promise that letting go will close this, and a sheet
 * dragged entirely off screen before the gesture has committed leaves the user
 * looking at a page they have not chosen yet.
 */
private const val SHEET_BACK_PREVIEW = 0.42f

/** The scrim behind a settled sheet. */
private const val SHEET_SCRIM_ALPHA = 0.32f

/** How far below the status bar the sheet's top edge rests. */
private val SHEET_TOP_GAP: Dp = 8.dp

private val SHEET_CORNER: Dp = 28.dp
/**
 * The always-draggable strip at the top of every sheet.
 *
 * 32dp rather than the grabber's own 4: it is the ONE dismiss gesture that works
 * on every sheet without exception, so it has to be a real touch target. It has
 * to be unconditional because five sheets — holding detail, transactions, cash,
 * standing orders and market intel — put a pull-to-refresh on their content, and
 * a pull-to-refresh and a pull-to-dismiss are the same finger asking for two
 * different things. Refresh wins inside the content there (it is nested INSIDE
 * this sheet's connection, so it consumes the drag first); this strip is what
 * keeps the sheet's own gesture available anyway.
 */
private val SHEET_GRABBER_ZONE: Dp = 32.dp
private val SHEET_GRABBER_WIDTH: Dp = 32.dp
private val SHEET_GRABBER_HEIGHT: Dp = 4.dp
private const val SHEET_GRABBER_ALPHA = 0.4f

/**
 * The sheet's arrival. Crisp and slightly quick — it is answering a tap that has
 * already happened, so anything slower reads as the tap not having landed.
 */
private val SheetEnter: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** The sheet's departure — a tween, so a dismissal always takes the same time. */
private val SheetExit: AnimationSpec<Float> = tween(durationMillis = 220)

/** Springing back after an abandoned pull. */
private val SheetSettle: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

/**
 * The live sheet stack — the shell's answer to "what does back mean right now".
 *
 * ## Why the shell needs a stack of its own next to the nav graph's
 *
 * The graph already knows what is on the back stack. What it does not know is
 * that each of those entries has an *animation to run before it may be popped*,
 * and `popBackStack()` does not ask: it deletes the destination from composition
 * on the spot, so a screen calling its own `onBack` would make the sheet vanish
 * rather than travel off the bottom.
 *
 * So the shell keeps the one thing the graph cannot hold: each open sheet's
 * dismissal. `back` resolves to [dismissTop], the top sheet animates itself away,
 * and only then does it call [pop] — the single `popBackStack` in the app that is
 * not routed through a sheet.
 *
 * ## Why the LAST registration is the top sheet
 *
 * Composition order is stack order: a pushed sheet composes after the one it
 * covers, so it registers after it. The subtlety is the overlap — during a
 * sheet-over-sheet push both are composed at once (that is what
 * [BtNavMotion.stackRecede] is for), and during that window the newer one is
 * still last, which is the correct answer to a back press.
 *
 * Plain lists and no snapshot state: nothing composable reads this, it is written
 * and read only from effects and callbacks on the main thread, and making it
 * observable would recompose the shell twice for every sheet.
 *
 * @param pop remove the top entry from the nav graph. Called by a sheet, at the
 *   end of its own exit animation.
 */
@Stable
internal class BtSheetHostState(val pop: () -> Unit) {
    private val open = mutableListOf<() -> Unit>()

    fun push(dismiss: () -> Unit) {
        open += dismiss
    }

    fun remove(dismiss: () -> Unit) {
        open -= dismiss
    }

    /** Ask the topmost sheet to leave. A no-op when nothing is open. */
    fun dismissTop() {
        open.lastOrNull()?.invoke()
    }

    /** How many sheets are open. One level of stacking is the supported depth. */
    val depth: Int get() = open.size
}

/**
 * The shell's sheet stack, for the [btSheet] registrations to find.
 *
 * A composition local rather than a parameter because [btSheet] has to keep the
 * exact call shape `composable<T>` had — one type argument and one content lambda
 * — or the 45 registrations it replaced would each have grown two arguments for
 * a fact that is the same at every one of them.
 */
internal val LocalBtSheetHost = staticCompositionLocalOf<BtSheetHostState> {
    error("No BtSheetHostState — a btSheet<> route was composed outside BtSheetHost")
}

/**
 * Register one subpage route, as a full-screen sheet.
 *
 * Drop-in for `composable<T>`: same type argument, same `(NavBackStackEntry) ->
 * Unit` content lambda, same `entry.toRoute<T>()` inside it. That is deliberate
 * and is why the 45-route migration is a rename — the alternative was 45 hand
 * edits to nested brace structures, in the one file where a mistake is a broken
 * route rather than a compile error.
 */
internal inline fun <reified T : Any> NavGraphBuilder.btSheet(
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) = composable<T> { entry ->
    BtSheet(LocalBtSheetHost.current) { content(entry) }
}
