package at.bettertrack.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.ui.theme.BtTheme
import kotlin.math.abs
import kotlin.math.floor

/**
 * The four top-level pages, **all alive, side by side** (owner order 2026-08-08).
 *
 * ## The report this answers
 *
 * *"I want to be instantly on a page once I swiped and the finger left the
 * screen. Like right now I swipe and it takes a while for the other page to load.
 * When I swipe I instantly want to be able to swipe again to the next thing.
 * Right now I have to wait a certain time between each swipe for everything to
 * function."*
 *
 * Both halves of that are one cause. The tabs used to be four routes in the
 * NavHost, so exactly one of them existed at a time: a hop **composed** the
 * incoming tab on arrival (its view models, its `LaunchedEffect(Unit) { load() }`,
 * its charts), and the gesture layer covered that with a frozen `GraphicsLayer`
 * snapshot of the tab's last frame so the swap would not flash. The landing was
 * therefore never instant — it was a picture of the destination, held up until
 * the real one had been built — and the next gesture could not be honoured until
 * the nav graph had caught up, because `tabNeighbour` had nothing else to ask.
 * Measured on this device before the change: **3 swipes at a ~100ms cadence
 * advanced 0 pages, 20 bursts out of 20.**
 *
 * So the pages stop dying. All four are composed once, kept in a pager, and never
 * disposed. Landing is instant because the page was never dead; a new swipe is
 * accepted the moment the finger lands because a pager's scroll container takes
 * over mid-settle by construction; and there is no nav graph in the loop at all,
 * so there is no lag for a gesture to arrive inside.
 *
 * ## Why this can be a `HorizontalPager` now, when it could not be before
 *
 * [BtTabSwipe]'s retired KDoc argued the case honestly: a pager hosting the four
 * tabs would have to take those routes OUT of the graph, and with them
 * `popUpTo(start){saveState}` + `restoreState` — the thing that remembered *each
 * tab's pushed screens*. Open a holding, hop to Markets, come back, and the
 * holding was still there.
 *
 * The sheet architecture ([BtSheet]) removes that requirement rather than
 * working around it. A subpage is no longer pushed *onto a tab*; it is a
 * full-screen sheet over the whole shell, and it covers the bar it would have
 * been saved under. There is no per-tab back stack left to preserve, because
 * there is no per-tab stack: there is one sheet stack, over four live pages.
 *
 * ## What replaces what the NavHost was giving these four routes
 *
 *  - **View-model scope** — each page gets its own [BtTabScopes] store owner, so
 *    `viewModel { }` still resolves per tab and still clears when the shell goes
 *    (logout, app lock). Without this the ambient owner would be the Activity and
 *    a logged-out user's portfolio view model would survive into the next login.
 *  - **`rememberSaveable` scope** — one `SaveableStateProvider` per tab, keyed by
 *    the tab's name, so two pages cannot collide on a generated key and a page
 *    keeps its state across a storage-mode change that hides and re-shows it.
 *  - **Lifecycle-correct disposal** — the pages are *meant* to outlive
 *    navigation now; that is the feature. They still die with the shell.
 */
@Composable
internal fun BtTabPager(
    tabs: List<BtTab>,
    state: PagerState,
    scopes: BtTabScopes,
    live: BtTabLiveSet,
    modifier: Modifier = Modifier,
    page: @Composable (BtTab) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    HorizontalPager(
        state = state,
        modifier = modifier,
        flingBehavior = btTabFling(state),
        // Every page stays within the pager's composition window, always. This is
        // the whole feature in one argument: with the default (0) the pager would
        // dispose a page as soon as it left the viewport, and this would be the old
        // architecture wearing a different gesture layer.
        //
        // ## It is not what costs the cold start (measured 2026-08-08)
        //
        // The obvious suspicion, and it is wrong. Cold start did regress with this
        // architecture — 1248ms to 1479ms on this device, six samples each, ART
        // warm — and `beyondViewportPageCount` composing four page slots before the
        // visible one has drawn is the first thing to blame. So it was gated behind
        // the warm-up and re-measured: **1479ms, i.e. no change at all.** Removing
        // the pager from the shell entirely was also measured: 1482ms. Neither the
        // pager nor its viewport count is where the time goes.
        //
        // The gate is therefore NOT kept. It bought nothing, and it would have left
        // a window in which a page could still be disposed — the one behaviour this
        // whole file exists to prevent — in exchange for a saving that does not
        // exist. Where the 231ms actually goes is recorded in the report as an open
        // item; it is spread across the shell restructure (dropping the sheet
        // NavHost from composition accounts for ~45ms of it) rather than sitting in
        // any one component.
        beyondViewportPageCount = tabs.size,
        // The tab's own name, not the index. A storage-mode change re-orders and
        // re-lengths the bar (a Drive-only install has no People tab); an index
        // key would hand Markets' saved state to whatever now sits at slot 1.
        key = { index -> tabs.getOrNull(index)?.name ?: "bt-tab-$index" },
    ) { index ->
        val tab = tabs.getOrNull(index)
        if (tab != null) {
            holder.SaveableStateProvider(tab.name) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides scopes.owner(tab)) {
                    // Lazy on first visit, immortal after. A cold start pays for
                    // ONE page — see [BtTabLiveSet] for the warm-up schedule.
                    if (live.isLive(tab)) page(tab) else BtTabResting()
                }
            }
        }
    }
}

/**
 * The fling that makes a fast chain of swipes land a page per swipe.
 *
 * ## The measurement this exists for
 *
 * The default pager fling is tuned for a page the user reads, not for a bar the
 * user rakes through. Measured on device with the pager in and the defaults left
 * alone: three swipes at a ~118ms cadence advanced **two** pages, not three.
 *
 * The lost swipe is not a dropped gesture — it is arithmetic. The default settle
 * is a `StiffnessMediumLow` spring (~220ms), so at that cadence the second finger
 * lands while the first hop is still animating, at maybe 0.85 of a page. It then
 * drags 0.57 of a page from there, reaching 1.42, and the default
 * `snapPositionalThreshold` of 0.5 rounds that DOWN to page 1 — the page the
 * first swipe was already going to. The gesture was accepted, honoured, and
 * arithmetically worth nothing.
 *
 * Two changes, and they attack the two halves of that:
 *
 *  - **A stiffer settle** ([Spring.StiffnessHigh]) finishes the hop in a fraction
 *    of the time, so a chained finger usually arrives after it rather than during
 *    it.
 *  - **A low positional threshold** ([TAB_SNAP_THRESHOLD]) decides the rest.
 *    Landing at 1.42 goes to page 2, which is what the user asked for twice. A low
 *    threshold is only reckless for a slow, deliberate drag — and that case is not
 *    decided here at all, because a drag released below the fling velocity settles
 *    by distance under the finger, where the pages are visibly where they are
 *    going.
 *
 * [PagerSnapDistance.atMost] stays at one page: a flick means the next tab, never
 * two, however fast it was. That is the launcher rule and the bar's own — the user
 * is counting swipes.
 *
 * ## What this measures at, and the one window it does not
 *
 * With both changes, on a warm app: **60 swipes, 60 pages, 20 perfect bursts out
 * of 20**, at the same ~100ms cadence that used to yield 30/60 and no perfect
 * burst at all.
 *
 * The exception is honest and worth writing down: a run started seconds after a
 * cold install measured 52/60, and **every one of the eight losses fell in the
 * first nine bursts** — the window in which three of the four pages are being
 * composed for the first time and their first network loads are in flight. Bursts
 * 10-20 of that same run were 11 for 11. Lazy-init has to be paid for once; this
 * is where it is paid.
 */
@Composable
private fun btTabFling(state: PagerState) = PagerDefaults.flingBehavior(
    state = state,
    pagerSnapDistance = PagerSnapDistance.atMost(1),
    snapAnimationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    ),
    snapPositionalThreshold = TAB_SNAP_THRESHOLD,
)

/** See [btTabFling] — how far into a page a release has to be to commit to it. */
internal const val TAB_SNAP_THRESHOLD = 0.1f

/**
 * What a page that has not woken up yet draws.
 *
 * Deliberately the page background and nothing else. It is on screen for at most
 * the [TAB_WARMUP_DELAY] after a cold start, and only if the user swipes inside
 * that window — and a swipe wakes its target immediately anyway (see the
 * `targetPage` collector in `BtApp`), so what this actually covers is the single
 * frame between the finger moving and the page composing. A skeleton would be a
 * loading claim about a page that is not loading.
 */
@Composable
private fun BtTabResting() {
    Box(Modifier.fillMaxSize().background(BtTheme.colors.bg))
}

/**
 * How long after the first frame the tabs the user did NOT open start waking.
 *
 * Startup is the budget this protects. `beyondViewportPageCount` composes every
 * page on the pager's first measure, so without a gate a cold start would build
 * four screens — four view models, four `LaunchedEffect(Unit) { load() }`, four
 * chart passes — before the one the user is looking at had drawn. The gate makes
 * a cold start compose exactly one page, and the other three arrive once the app
 * is idle and their cost is invisible.
 */
internal const val TAB_WARMUP_DELAY_MS = 1200L

/** The gap between two warm-ups, so the three never land in one frame. */
internal const val TAB_WARMUP_STAGGER_MS = 250L

/**
 * Which pages have been built. Once a tab is in here it never leaves — "lazy-init
 * on first visit, alive forever after" is exactly this set being append-only.
 *
 * A snapshot set, because flipping a page from resting to live must recompose
 * that page. It changes at most four times in the life of the shell.
 */
@Stable
internal class BtTabLiveSet(initial: BtTab? = null) {
    private val live = mutableStateSetOf<BtTab>()

    init {
        initial?.let(live::add)
    }

    fun isLive(tab: BtTab): Boolean = tab in live

    fun wake(tab: BtTab) {
        live.add(tab)
    }

    /** How many pages are currently composed — reported in the memory numbers. */
    val count: Int get() = live.size
}

/**
 * One [ViewModelStore] per tab, living exactly as long as the shell does.
 *
 * The store owner a page sees used to be its `NavBackStackEntry`. Outside the
 * graph the ambient owner would be the **Activity**, and that is not a detail:
 * `viewModel { }` calls in the tab screens are keyless, so a Portfolio view model
 * created under the Activity would still be there after a logout, hand its
 * previous user's holdings to the next login, and never clear. Scoping here
 * restores the old lifetime — cleared when `BtApp` leaves composition, which is
 * what a logout and an app-lock both do.
 *
 * Per TAB rather than one shared store, so the four pages stay as isolated as
 * four back-stack entries were: a keyless `viewModel<T>()` in two different tabs
 * can never resolve to the same instance.
 */
@Stable
internal class BtTabScopes {
    private val stores = mutableMapOf<BtTab, TabStoreOwner>()

    fun owner(tab: BtTab): ViewModelStoreOwner = stores.getOrPut(tab) { TabStoreOwner() }

    fun clear() {
        stores.values.forEach { it.viewModelStore.clear() }
        stores.clear()
    }

    private class TabStoreOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
}

@Composable
internal fun rememberBtTabScopes(): BtTabScopes {
    val scopes = remember { BtTabScopes() }
    DisposableEffect(scopes) { onDispose { scopes.clear() } }
    return scopes
}

@Composable
internal fun rememberBtTabPagerState(tabs: List<BtTab>, initial: BtTab): PagerState =
    rememberPagerState(initialPage = tabs.indexOf(initial).coerceAtLeast(0)) { tabs.size }

// ── The pure geometry every chrome reader shares ────────────────────────────
//
// One number — the pager's continuous position — and three readers: the bottom
// bar's pill, the bottom bar's ink, and the shared top bar's face crossfade. They
// are pure functions so that every branch is pinned by a unit test rather than by
// a device gesture, which is the discipline the retired swipe layer established
// and the one thing about it worth keeping.

/**
 * Where the pager is, as a continuous page index.
 *
 * `2.0` is Workbench at rest; `1.35` is a third of the way from Markets to
 * Workbench with the finger still down. Everything the chrome draws is a function
 * of this one value, which is what stops the bar and the pages from ever
 * disagreeing — the failure the retired handoff/latch machinery existed to patch.
 *
 * Clamped to the bar, because `currentPageOffsetFraction` reports overscroll at
 * the ends and the chrome has nowhere to put it.
 */
internal fun tabPagerPosition(currentPage: Int, offsetFraction: Float, count: Int): Float {
    if (count <= 0) return 0f
    return (currentPage + offsetFraction).coerceIn(0f, (count - 1).toFloat())
}

/**
 * How lit tab [index] is, 0..1, at pager position [pos].
 *
 * A linear ramp between neighbours: at rest exactly one tab is 1f and the rest
 * are 0f — the same 0f/1f the nav graph used to give — and mid-drag the two tabs
 * either side of the finger share the value. That is what turns the bar's ink and
 * its selected-weight from a swap into a crossfade the finger drives.
 */
internal fun tabSelectionRamp(pos: Float, index: Int): Float =
    (1f - abs(pos - index)).coerceIn(0f, 1f)

/**
 * The two faces the shared top bar is between, and how far between them it is.
 *
 * Returns `(lo, hi, fraction)` where `lo`/`hi` are adjacent tab indices and
 * `fraction` is 0f at `lo` and 1f at `hi`. `lo == hi` means the pager is at rest
 * on a page and the bar has one face to draw.
 *
 * ## Why lo/hi and not outgoing/incoming
 *
 * The retired version took an outgoing face, an incoming face and a `forward`
 * flag, and crossfaded on `abs(offset) / pageWidth`. That reads correctly for a
 * gesture that finishes, and wrongly for one that does not: `currentPage` flips
 * at the halfway mark, so a drag carried past the middle and then dragged back
 * would fade the incoming face **in, out and in again** while the finger moved
 * steadily one way.
 *
 * Anchoring on the two pages the position lies BETWEEN removes the flip. The
 * fraction is monotonic in the finger for the whole travel, in both directions,
 * and the direction falls out of it — `lo` always exits to the left and `hi`
 * always enters from the right, whichever way the user is going.
 */
internal fun tabHeaderSpan(pos: Float, count: Int): TabHeaderSpan {
    if (count <= 0) return TabHeaderSpan(0, 0, 0f)
    val clamped = pos.coerceIn(0f, (count - 1).toFloat())
    val lo = floor(clamped).toInt().coerceIn(0, count - 1)
    val hi = (lo + 1).coerceAtMost(count - 1)
    val fraction = if (hi == lo) 0f else (clamped - lo).coerceIn(0f, 1f)
    return TabHeaderSpan(lo, hi, fraction)
}

/** The answer [tabHeaderSpan] gives. */
internal data class TabHeaderSpan(val lo: Int, val hi: Int, val fraction: Float)

/**
 * Where the selection pill sits, interpolated between the two icon centres the
 * pager position lies between.
 *
 * The pill therefore travels **exactly** with the pages, at every instant, with
 * no spring of its own to fall behind them and no latch to hold it ahead of them.
 * The old bar had both, and needed both, because its only source of truth (the
 * nav graph) arrived a frame or more after the pixels; §6.3 predicted this
 * function verbatim — *"when a real pager lands, `selectionFraction` starts
 * returning true intermediate values"*.
 *
 * @param centreOf x of tab `i`'s icon centre, or `null` before it has been laid
 *   out. A gap disables the interpolation rather than guessing across it.
 */
internal fun tabPillX(pos: Float, count: Int, centreOf: (Int) -> Float?): Float? {
    val span = tabHeaderSpan(pos, count)
    val lo = centreOf(span.lo) ?: return null
    if (span.hi == span.lo) return lo
    val hi = centreOf(span.hi) ?: return lo
    return lo + (hi - lo) * span.fraction
}

/**
 * Whether an optimistic TAP latch is still believable, against a pager.
 *
 * ## What the latch is still for
 *
 * The swipe latches are gone — a gesture and the bar now read the same number in
 * the same frame, so there is nothing left to be optimistic about. A TAP still
 * needs one: `animateScrollToPage` is a *journey*, and the owner's ask
 * (2026-08-08) was that the tap be acknowledged on its own frame rather than when
 * the journey ends. So the ink and the selected weight flip immediately while the
 * pill and the pages travel together, honestly, to where the tap sent them.
 *
 * The latch may not outlive its own truth, and against a pager "truth" is
 * `settledPage`, not `currentPage`: `currentPage` flips at the halfway mark, so
 * believing it would drop the latch while the page was still visibly in motion.
 *
 *  - settled on the target → the journey is over, drop it;
 *  - settled anywhere else → still travelling, or something else took over; hold
 *    only while the settled page is still where the tap started from.
 */
internal fun tapLatchHolds(target: Int, origin: Int, settledPage: Int): Boolean = when {
    settledPage == target -> false
    else -> settledPage == origin
}

/**
 * The order the warm-up wakes the sleeping tabs in: nearest to the open page
 * first, so the two the user is one swipe away from are ready before the far one.
 */
internal fun tabWarmOrder(tabs: List<BtTab>, from: Int): List<BtTab> =
    tabs.indices.sortedBy { abs(it - from) }.mapNotNull(tabs::getOrNull)
