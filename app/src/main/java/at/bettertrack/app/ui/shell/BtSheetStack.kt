package at.bettertrack.app.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.LocalOwnersProvider
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.rememberBtHaptics
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * One page of the sheet stack.
 *
 * @param key stable identity — a nav entry's id, so a push or pop MOVES the
 *   page's composition between the two planes instead of tearing it down.
 */
@Immutable
internal class BtSheetPage(
    val key: String,
    val content: @Composable () -> Unit,
)

/**
 * The live sheet stack, bottom-most first, as renderable pages.
 *
 * One [rememberSaveableStateHolder] for the whole layer, and it is the ONLY
 * place these entries are ever composed — the `NavHost` never sees them (they
 * belong to [BtSheetNavigator]), so there is no second owner provider to race
 * with over each entry's saved state.
 */
@Composable
internal fun rememberBtSheetPages(navigator: BtSheetNavigator): List<BtSheetPage> {
    val holder = rememberSaveableStateHolder()
    val empty = remember { MutableStateFlow(emptyList<NavBackStackEntry>()) }
    val stack by (if (navigator.attached) navigator.backStack else empty).collectAsState()
    return stack.map { entry ->
        BtSheetPage(entry.id) {
            entry.LocalOwnersProvider(holder) {
                (entry.destination as BtSheetNavigator.Destination).content(entry)
            }
        }
    }
}
/**
 * THE sheet layer: one persistent container that renders the top TWO pages of
 * the stack, live, and owns the whole of their motion.
 *
 * Composed once, for the life of the shell — the pages come and go inside it,
 * the container does not. That is what makes a depth change a *slide between two
 * composed planes* rather than a hand-over between two destinations, and it is
 * the whole of the owner's "visually connected, not separate popups".
 */
@Composable
internal fun BtSheetStack(
    host: BtSheetHostState,
    pages: List<BtSheetPage>,
    modifier: Modifier = Modifier,
    /** Told what this layer covers, so the shell can stop drawing it. */
    occlusion: BtOcclusion? = null,
) {
    val bt = BtTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()

    val depth = pages.size
    val topKey = pages.lastOrNull()?.key
    val stacked = depth >= 2
    val stackedNow by rememberUpdatedState(stacked)
    val reducedNow by rememberUpdatedState(reducedMotion)
    val hostNow by rememberUpdatedState(host)
    val hapticsNow by rememberUpdatedState(rememberBtHaptics())

    // 0f = settled at rest, 1f = below the bottom edge. Read by the scrim's alpha
    // and the surface's translation, so the two cannot drift apart.
    val travel = remember { BtSheetMotion(1f) }
    // 0f = the top page covers its parent, 1f = fully off to the right.
    val slide = remember { BtSheetMotion(0f) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    val flingVelocityPx = with(density) { SHEET_DISMISS_VELOCITY.toPx() }

    // What this layer hides while it is settled — its own top edge, from the same
    // two terms the Surface below is positioned by, plus the corner radius. A
    // derivedStateOf so the answer changes only when coverage FLIPS: a drag frame
    // writes `travel` a hundred times a second and must not re-record anything.
    val topEdgePx = WindowInsets.statusBars.getTop(density) +
        with(density) { SHEET_TOP_GAP.toPx() }
    val cornerPx = with(density) { SHEET_CORNER.toPx() }
    val exposed = remember(topEdgePx, cornerPx) {
        derivedStateOf { sheetExposedTopPx(travel.value, topEdgePx, cornerPx) }
    }
    DisposableEffect(occlusion, exposed) {
        occlusion?.probe = { exposed.value }
        onDispose { occlusion?.probe = null }
    }

    // What a change in the stack means, in motion. A gesture-driven pop has
    // ALREADY animated by the time it lands here, and it leaves `slide` at 1 —
    // where the parent plane is drawn at exactly its rest position — so snapping
    // to 0 with the parent now the top page is pixel-identical. That identity is
    // the seam the owner saw as "goes blank and something shifts".
    var lastDepth by remember { mutableIntStateOf(0) }
    LaunchedEffect(depth, topKey) {
        val previous = lastDepth
        lastDepth = depth
        leaving = false
        when {
            depth == 0 -> {
                travel.snap(1f)
                slide.snap(0f)
            }
            previous == 0 -> {
                slide.snap(0f)
                if (reducedMotion) travel.snap(0f) else travel.animateTo(0f, SheetEnter)
            }
            depth > previous -> {
                travel.snap(0f)
                slide.snap(1f)
                if (reducedMotion) slide.snap(0f) else slide.animateTo(0f, SheetSlide)
            }
            else -> {
                travel.snap(0f)
                slide.snap(0f)
            }
        }
    }
    // Back ONE page, on the axis this depth belongs to. At depth >= 2 that is
    // ALWAYS the connected slide — whether the gesture was a rightward swipe, a
    // notch release, a top-bar back or system back — with the sheet settling up
    // underneath it at the same time, so every route back looks like one thing.
    val backOne: () -> Unit = remember {
        {
            if (!leaving) {
                leaving = true
                scope.launch {
                    if (!reducedNow) {
                        if (stackedNow) {
                            val settleUp = launch { travel.animateTo(0f, SheetSettle) }
                            slide.animateTo(1f, SheetSlide)
                            settleUp.join()
                        } else {
                            travel.animateTo(1f, SheetExit)
                        }
                    }
                    hostNow.pop()
                }
            }
            Unit
        }
    }
    val closeAll: () -> Unit = remember {
        {
            if (!leaving) {
                leaving = true
                scope.launch {
                    if (!reducedNow) travel.animateTo(1f, SheetExit)
                    hostNow.popAll()
                }
            }
            Unit
        }
    }
    val settle: () -> Unit = remember {
        {
            scope.launch { travel.animateTo(0f, SheetSettle) }
            scope.launch { if (slide.value != 0f) slide.animateTo(0f, SheetSettle) }
            Unit
        }
    }
    DisposableEffect(host, backOne) {
        host.push(backOne)
        onDispose { host.remove(backOne) }
    }

    // System and predictive back drive the same travel the finger does, on
    // whichever axis this depth uses, so a back swipe previews the real motion.
    PredictiveBackHandler(enabled = depth > 0 && !leaving) { progress ->
        try {
            progress.collect { event ->
                val preview = (event.progress * SHEET_BACK_PREVIEW).coerceIn(0f, 1f)
                if (stackedNow) slide.snap(preview) else travel.snap(preview)
            }
            backOne()
        } catch (cancelled: CancellationException) {
            // The animation has to run on the composition's scope: this coroutine
            // is already dead.
            if (!leaving) settle()
            throw cancelled
        }
    }
    // The ONE place a downward drag becomes travel. Both entry points (the
    // content's nested scroll and the grabber strip) go through it, so the
    // detent haptic cannot fire from one and stay silent on the other, and so
    // the crossing is tested against the travel the sheet actually had.
    val pullBy: (Float) -> Float = remember {
        { dy ->
            val h = heightPx
            if (h <= 0f) {
                0f
            } else {
                val from = travel.value
                val next = sheetDragTravel(from, dy, h, stackedNow)
                // Synchronous: the finger's value lands on the finger's frame.
                travel.snap(next)
                if (sheetNotchCrossed(from, next, stackedNow)) hapticsNow.detent()
                (next - from) * h
            }
        }
    }

    // Pull-down from the top of the content. `onPostScroll` and not `onPreScroll`
    // is the "arms at scroll top only" rule: this only ever sees what the list
    // underneath refused, and a list that is not at its top refuses nothing.
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
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

            private fun drag(dy: Float): Float = pullBy(dy)

            fun release(velocityY: Float) {
                when (sheetRelease(travel.value, velocityY, flingVelocityPx, stackedNow)) {
                    SheetRelease.SETTLE -> settle()
                    SheetRelease.BACK_ONE -> backOne()
                    SheetRelease.CLOSE_ALL -> closeAll()
                }
            }
        }
    }

    // The "pull again" chip, driven by whichever refresh screen is on top.
    val hint = remember { BtSheetRefreshHint() }
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hint.token) {
        if (hint.token == 0) return@LaunchedEffect
        hintVisible = true
        delay(BT_SHEET_HINT_MS)
        hintVisible = false
    }

    if (depth == 0) return

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - travel.value) * SHEET_SCRIM_ALPHA
                    // A RenderNode with alpha < 1 assumes its content overlaps
                    // itself and rasterises into an offscreen buffer first — here
                    // that is a full 1080x2316 surface allocated and composited
                    // every frame of every drag, for one flat rectangle. Saying
                    // outright that nothing overlaps lets the alpha fold into the
                    // draw call instead. Measured on device: this and the
                    // synchronous drag together take issueDrawCommands and
                    // swapBuffers back to the main pager's numbers.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                // Same rule as the shell's, applied to the scrim itself: while the
                // sheet is settled, the only scrim anyone can see is the strip
                // above it. The rest was a full-screen alpha blend over pixels
                // that an opaque sheet covers on the very next draw call.
                .drawBehind {
                    val top = exposed.value
                    if (top < 0f) {
                        drawRect(bt.scrim)
                    } else {
                        drawRect(bt.scrim, size = size.copy(height = top))
                    }
                }
                // The strip above the sheet is the only part of this the user can
                // reach; tapping outside a sheet to close it is the idiom.
                .pointerInput(Unit) { detectTapGestures { backOne() } },
        )
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Pads AND consumes: children asking for the status bar get zero,
                // because this boundary has already paid it.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = SHEET_TOP_GAP)
                .onSizeChanged {
                    heightPx = it.height.toFloat()
                    widthPx = it.width.toFloat()
                }
                .graphicsLayer { translationY = travel.value * heightPx }
                .nestedScroll(pull),
            shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
            color = bt.bg,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Static chrome. It never rides the depth axis — the same
                // relationship the four main pages have with their top bar.
                BtSheetGrabber(
                    travel = travel,
                    stacked = stacked,
                    onDrag = { dy -> if (!leaving) pullBy(dy) },
                    onRelease = { v -> pull.release(v) },
                )
                Box(Modifier.fillMaxSize()) {
                    BtSheetPlanes(
                        pages = pages,
                        stacked = stacked,
                        slide = slide,
                        widthPx = { widthPx },
                        leaving = { leaving },
                        flingVelocityPx = flingVelocityPx,
                        hint = hint,
                        onDragBy = { dx ->
                            val w = widthPx
                            if (w > 0f && !leaving) {
                                // Synchronous, for the same reason the vertical
                                // drag is: see BtSheetMotion.
                                slide.snap((slide.value + dx / w).coerceIn(0f, 1f))
                            }
                        },
                        onCommitBack = backOne,
                        onSettle = settle,
                    )
                    BtSheetHintChip(
                        visible = hintVisible,
                        stacked = stacked,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}
/**
 * The two live planes.
 *
 * Both are composed, always — that is the entire fix for "goes blank". One call
 * site with `key(page.key)`, so a push or a pop MOVES a page's composition
 * between the planes instead of tearing it down and rebuilding it.
 *
 * The top plane rides the finger; the parent sits `-(1 - slide)` of a width to
 * its left, so at `slide = 1` it is exactly at rest and the pop that follows is
 * a snap onto identical pixels.
 */
@Composable
private fun BtSheetPlanes(
    pages: List<BtSheetPage>,
    stacked: Boolean,
    slide: BtSheetMotion,
    widthPx: () -> Float,
    leaving: () -> Boolean,
    flingVelocityPx: Float,
    hint: BtSheetRefreshHint,
    onDragBy: (Float) -> Unit,
    onCommitBack: () -> Unit,
    onSettle: () -> Unit,
) {
    val visible = pages.takeLast(2)
    CompositionLocalProvider(LocalBtSheetRefreshHint provides hint) {
        visible.forEachIndexed { index, page ->
            val fromTop = visible.lastIndex - index
            key(page.key) {
                val dragState = rememberDraggableState { dx -> onDragBy(dx) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = if (fromTop == 0) {
                                slide.value * widthPx()
                            } else {
                                -(1f - slide.value) * widthPx() * SHEET_DEPTH_PARALLAX
                            }
                        }
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Horizontal,
                            enabled = fromTop == 0 && stacked,
                            onDragStopped = { velocity ->
                                if (!leaving()) {
                                    val commits = sheetBackSwipeCommits(
                                        slide.value,
                                        velocity,
                                        flingVelocityPx,
                                    )
                                    if (commits) onCommitBack() else onSettle()
                                }
                            },
                        ),
                ) { page.content() }
            }
        }
    }
}

/**
 * The handle at the top of the sheet — the one piece of chrome the layer adds.
 *
 * Always draggable, whatever the content is doing: it is the escape hatch for
 * the screens that do not scroll at all, where a pull-from-scroll-top has
 * nothing to arm against.
 */
@Composable
private fun BtSheetGrabber(
    travel: BtSheetMotion,
    stacked: Boolean,
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
        // `travel` changes every frame of a pull, but the STAGE takes only three
        // values. Reading it through derivedStateOf collapses ~120 writes per
        // second into at most two recompositions for the whole gesture. The
        // shipped build read travel directly here and recomposed this strip —
        // retargeting the animateDpAsState below — on every single frame.
        val current by remember(travel, stacked) {
            derivedStateOf { sheetStageOf(travel.value, stacked) }
        }
        val width by animateDpAsState(
            targetValue = when (current) {
                SheetStage.IDLE -> SHEET_GRABBER_WIDTH
                SheetStage.BACK -> SHEET_GRABBER_WIDTH * 1.6f
                SheetStage.CLOSE_ALL -> SHEET_GRABBER_WIDTH * 2.2f
            },
            label = "grabberWidth",
        )
        val colour = if (current == SheetStage.CLOSE_ALL) {
            bt.goldEmphasis
        } else {
            bt.textMuted.copy(alpha = SHEET_GRABBER_ALPHA)
        }
        Box(
            Modifier
                .size(width = width, height = SHEET_GRABBER_HEIGHT)
                .clip(CircleShape)
                .background(colour),
        )
    }
}
/**
 * The quiet hint that the SECOND pull acts on the sheet, not on the data.
 *
 * An overlay, never a layout child: it must not move the content by a pixel.
 */
@Composable
private fun BtSheetHintChip(visible: Boolean, stacked: Boolean, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(SHEET_HINT_FADE_MS)),
        exit = fadeOut(tween(SHEET_HINT_FADE_MS)),
    ) {
        Surface(
            color = bt.surface,
            contentColor = bt.textMuted,
            shape = CircleShape,
            modifier = Modifier.padding(top = SHEET_HINT_GAP),
        ) {
            Text(
                text = stringResource(
                    if (stacked) R.string.bt_sheet_pull_again_back else R.string.bt_sheet_pull_again_close,
                ),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * A single standalone sheet, for the one surface that has no graph to live in
 * (the pre-login settings sheet, composed outside the NavHost). Same container,
 * same chrome, same gestures — a stack of exactly one.
 */
@Composable
internal fun BtSheet(host: BtSheetHostState, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(content)
    val pages = remember { listOf(BtSheetPage("bt-sheet-solo") { current() }) }
    BtSheetStack(host = host, pages = pages)
}
// ── Chrome metrics and motion ───────────────────────────────────────────────

/** The scrim behind a settled sheet. */
private const val SHEET_SCRIM_ALPHA = 0.32f

/** How far below the status bar the sheet's top edge rests. */
private val SHEET_TOP_GAP: Dp = 8.dp
private val SHEET_CORNER: Dp = 28.dp

/**
 * The always-draggable strip at the top. 32dp rather than the pill's own 4: it
 * is the ONE dismiss gesture that works on every sheet without exception, so it
 * has to be a real touch target — including on the five screens whose content
 * takes the pull for a refresh.
 */
private val SHEET_GRABBER_ZONE: Dp = 32.dp
private val SHEET_GRABBER_WIDTH: Dp = 32.dp
private val SHEET_GRABBER_HEIGHT: Dp = 4.dp
private const val SHEET_GRABBER_ALPHA = 0.4f

private val SHEET_HINT_GAP: Dp = 6.dp
private const val SHEET_HINT_FADE_MS = 140

/** The sheet's arrival: crisp, it is answering a tap that already happened. */
private val SheetEnter: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** The sheet's departure — a tween, so a dismissal always takes the same time. */
private val SheetExit: AnimationSpec<Float> = tween(durationMillis = 220)

/**
 * The depth axis. A lateral hand-over between two pages of the same stack is a
 * smaller event than a sheet leaving the screen, and reads wrong at the same
 * duration. Tunable.
 */
private val SheetSlide: AnimationSpec<Float> = tween(durationMillis = 260)

/** Springing back after an abandoned pull. */
private val SheetSettle: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
