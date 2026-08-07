package at.bettertrack.app.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.dp
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The face each top-level tab last showed, kept so a swipe can put the
 * neighbouring page under the finger for real.
 *
 * ## Why pixels and not a second composition
 *
 * The obvious way to show the neighbour is to compose its tab root a second time
 * in an overlay. In this app that is a trap, and the reasons are specific:
 *
 *  - **A second ViewModel that never dies.** Nothing here uses `hiltViewModel`
 *    or a `SavedStateHandle`; every screen calls `viewModel { }`, which resolves
 *    `LocalViewModelStoreOwner`. Inside the NavHost that is the
 *    `NavBackStackEntry`; in an overlay it is the Activity. So the copy gets its
 *    own instance, in a store not cleared until the Activity dies — taking with
 *    it `WatchlistViewModel`'s two infinite `collect` loops and `AlertsViewModel`'s
 *    debounce collector.
 *  - **Real network load for a gesture the user may cancel.** `SocialScreen`'s
 *    `LaunchedEffect(Unit) { vm.load() }` is five requests and is not throttled;
 *    the Portfolio tree's `init { refresh() }` plus its resume effect is about
 *    ten, because the 60s throttle stamps its clock only after the first refresh
 *    completes. Peeking would double all of it, per drag.
 *  - **It would show the wrong page anyway.** The Workbench's Alerts/
 *    Conglomerates segment is `rememberSaveable`, People's segment is a plain
 *    `remember`, and the selected watchlist board lives in the ViewModel. A fresh
 *    copy starts at every default, so the "preview" would differ from the tab it
 *    previews in exactly the way the user would notice.
 *  - **Shared one-shot signals would double-fire.** `PortfolioTabEntry` and
 *    `WorkboardEntry` are `MutableStateFlow` + `consume()`; two collectors both
 *    observe `true`, and which of them acts is a frame-timing race.
 *
 * A picture of the page has none of those properties, and one a second
 * composition could not have had at any price: it is not an attempt to
 * reconstruct the tab's state, it **is** the tab as the user last saw it —
 * scroll position, selected segment, chosen watchlist board, live prices and
 * all.
 *
 * ## Why the picture has to be a real bitmap
 *
 * The cheap version of this — record the tab into a [GraphicsLayer] and replay
 * the layer later — does not work, and fails in a way worth writing down because
 * it *looks* like it works. `GraphicsLayer.record` captures drawing COMMANDS,
 * and the commands Compose emits for a subtree include references to that
 * subtree's own render nodes rather than copies of their contents. Replay the
 * layer after the tab has left the screen and those references resolve to
 * whatever is in those nodes now — so the "neighbour" renders as a live copy of
 * the page you are currently dragging. Verified on device: swiping Portfolio →
 * Markets peeked a second Portfolio.
 *
 * So the layer is only ever the SOURCE. [freeze] rasterises it into an
 * [ImageBitmap] — a real copy, immune to whatever the render nodes do next — at
 * the one moment the tab is guaranteed to be both complete and still alive: when
 * the user leaves it.
 *
 * ## Cost
 *
 * Recording is one extra render node in the draw path of the visible tab and no
 * work at all in composition, layout or measurement. The rasterisation is one
 * GPU readback per tab exit — an already-expensive frame — and never happens
 * during a drag. The bitmaps are page-sized and there are at most four.
 *
 * Layers are owned HERE, by the shell, not by the destination that records into
 * them: a `rememberGraphicsLayer` inside the tab would be released the moment
 * that tab left composition, which is exactly when its frame becomes worth
 * keeping.
 */
@Stable
internal class BtTabPeekLayers(private val context: GraphicsContext) {
    private val layers = mutableMapOf<BtTab, GraphicsLayer>()

    /**
     * Observable on purpose: a freeze lands from a coroutine, and the peek must
     * recompose from skeleton to picture when the first one for a tab arrives.
     */
    private val frozen = mutableStateMapOf<BtTab, ImageBitmap>()

    fun layerFor(tab: BtTab): GraphicsLayer = layers.getOrPut(tab) { context.createGraphicsLayer() }

    /**
     * Rasterise the tab's last recorded frame into a bitmap that no longer
     * depends on the tab being composed.
     *
     * Called as the user LEAVES the tab, while its content is still on the way
     * out and its render nodes are therefore still alive. Later is too late —
     * see the class KDoc — and earlier would freeze a page the user then kept
     * using.
     */
    suspend fun freeze(tab: BtTab) {
        val layer = layers.remove(tab) ?: return
        if (!layer.isReleased) frozen[tab] = layer.toImageBitmap()
        // The recording layer has done its one job. Holding it would keep a
        // full-screen render target alive for a tab that is not on screen, and
        // for all four tabs that is tens of megabytes of GPU memory earning
        // nothing — the bitmap is the artefact worth keeping. `layerFor`
        // rebuilds it for free the next time the tab actually draws.
        context.releaseGraphicsLayer(layer)
    }

    /**
     * The tab's face, or `null` if it has not been on screen this process — the
     * cold case, which peeks as [BtTabColdPeek] instead.
     */
    fun snapshotOf(tab: BtTab): ImageBitmap? = frozen[tab]

    fun release() {
        layers.values.forEach(context::releaseGraphicsLayer)
        layers.clear()
        frozen.clear()
    }
}

@Composable
internal fun rememberBtTabPeekLayers(): BtTabPeekLayers {
    val context = LocalGraphicsContext.current
    val layers = remember(context) { BtTabPeekLayers(context) }
    DisposableEffect(layers) { onDispose { layers.release() } }
    return layers
}

/**
 * Record this tab's own pixels as it draws them, so leaving it can freeze them.
 *
 * Applied per DESTINATION rather than once around the NavHost, and that is not
 * tidiness: around the NavHost the recording would also capture whatever
 * `AnimatedContent` is doing, so a tab frozen on its way out would be frozen
 * mid-transition — half itself, half the page replacing it.
 */
internal fun Modifier.btTabPeekCapture(
    layers: BtTabPeekLayers,
    tab: BtTab,
): Modifier = drawWithContent {
    val layer = layers.layerFor(tab)
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * The incoming page: the neighbouring tab's face, riding the same displacement
 * as the outgoing one exactly one page away.
 *
 * Opaque on purpose. It is a real page arriving, not a scrim over the old one —
 * the owner's reference is the phone home screen, where there is no cross-fade
 * and no scaling, just two pages that are hard-adjacent and move as one strip.
 * The opaque background is also what lets the handoff hide the NavHost swap.
 *
 * The layer swallows touches (Initial pass, so nothing beneath sees them). A
 * page in flight is not a page you can tap, and without this a tap during the
 * settle would fall through to the outgoing page's controls — which are off
 * screen and belong to a tab the user is in the act of leaving.
 */
@Composable
internal fun BtTabPeek(
    tab: BtTab,
    forward: Boolean,
    layers: BtTabPeekLayers,
    state: BtTabSwipeState,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val snapshot = layers.snapshotOf(tab)
    Box(
        modifier
            .fillMaxSize()
            .btTabPageOffset {
                peekPageOffsetPx(
                    pageOffsetPx = state.pageOffsetPx,
                    pageWidthPx = state.pageWidthPx,
                    forward = forward,
                    handedOff = state.handoff != null,
                )
            }
            .background(bt.bg)
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDownIgnoringConsumed().consume() }
            },
    ) {
        if (snapshot != null) {
            Canvas(Modifier.fillMaxSize()) { drawImage(snapshot) }
        } else {
            BtTabColdPeek()
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitFirstDownIgnoringConsumed() =
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

/**
 * The peek for a tab that has never been on screen this process, and so has no
 * face to show.
 *
 * The app's own loading idiom ([BtSkeleton]), because that is honestly what the
 * user is about to see: a cold tab root arrives loading. It is stateless — no
 * ViewModel, no request — which is the whole reason the cold case is allowed to
 * exist rather than forcing a composition of the real tab.
 */
@Composable
private fun BtTabColdPeek() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BtSkeleton(Modifier.fillMaxWidth(0.45f).height(28.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(112.dp), shape = RoundedCornerShape(16.dp))
        repeat(6) {
            BtSkeleton(Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp))
        }
    }
}
