package at.bettertrack.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.ui.components.BT_HEADER_COLLAPSED_HEIGHT
import at.bettertrack.app.ui.components.BtHeaderSelector
import at.bettertrack.app.ui.components.BtHeaderWordmark
import at.bettertrack.app.ui.components.BtSettingsGear
import at.bettertrack.app.ui.components.btBarScrolledHairline
import at.bettertrack.app.ui.components.rememberBtPinnedHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The ONE shared top bar of the four top-level tabs, hoisted out of the pages
 * (owner report 2026-08-07).
 *
 * ## The report this answers
 *
 * *"Since the header is consistent in all the main pages it shouldn't scroll
 * [with the swipe] … it's just moving from showing BetterTrack and settings to
 * the same, sometimes with a portfolio selector — make that look better when
 * swiping."*
 *
 * He is describing an architectural fact, not a rendering glitch. Until this
 * change each tab drew **its own** 64dp pinned bar inside its own page, so a
 * swipe slid one copy of the brand strip off the screen while an identical copy
 * slid on — two pictures of the same fixed object, crossing. The wordmark and the
 * gear are in the same place before and after the gesture, so animating them at
 * all is motion that carries no information, and the eye reads it as the whole
 * app sliding rather than as the page changing.
 *
 * So the bar is **one instance, in the shell, above the swiped area**. It cannot
 * move during a gesture because it is not in the thing that moves. What actually
 * differs between tabs — the Portfolio selector pill, People's Messages action —
 * is the only thing that transitions, and it transitions in place.
 *
 * ## Why the variable part is DATA and not a composable slot
 *
 * The obvious hoist is for each tab to hand the shell a `@Composable` lambda for
 * its own bar content. It was impossible when this bar was written — mid-swipe
 * the incoming tab was a frozen bitmap, not a composition, so there was no slot
 * to invoke — and it stays wrong now that the pages are live, for a plainer
 * reason: a slot is a *page's* opinion about the chrome, and the whole point of
 * hoisting the bar was that four pages should not each own a version of it.
 *
 * A face is therefore a small immutable **description** ([BtTabHeaderFace]) the
 * shell holds for every tab at once and renders itself. Three of the four are
 * constants the shell simply knows; only Portfolio's changes with app state, and
 * it publishes that through [BtTabChrome]. The shell holding all four is also
 * what lets the bar cross-fade continuously across a drag — see
 * [BtHeaderSwapZone], which composes every tab's version of a slot and picks
 * between them with an alpha rather than with a swap.
 *
 * ## Why the wordmark and the gear cannot move
 *
 * `navigationIcon` is start-anchored and `actions` is end-anchored, and the gear
 * is the LAST child of the actions row — so the gear's right edge is the row's
 * right edge no matter what else the row carries, and the wordmark's left edge is
 * the bar's. The transition below only ever changes what sits *between* them, and
 * a zone whose width changes pushes its own contents around rather than theirs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BtTabHeader(
    /** Every tab's face, in bar order. */
    faces: List<BtTabHeaderFace>,
    /** The pager, read in the LAYER phase so a drag frame never recomposes. */
    pager: PagerState,
    scrollBehavior: TopAppBarScrollBehavior,
    onLongPressWordmark: () -> Unit,
    onOpenSwitcher: () -> Unit,
    onAction: (BtTabHeaderAction) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val bt = BtTheme.colors
    TopAppBar(
        // The bar's bottom edge, once content has gone under it. On the all-white
        // light table this line is the ONLY thing separating a static bar from a
        // scrolling page — see [btBarScrolledHairline].
        modifier = Modifier.btBarScrolledHairline(scrollBehavior, bt.groupBorder),
        title = {
            BtHeaderSwapZone(
                items = faces.map { it.selector },
                pager = pager,
            ) { selector ->
                BtHeaderSelector(
                    label = selector.label,
                    icon = selector.icon,
                    iconTint = selector.tint,
                    // The hoisted bar is the pinned strip and nothing else, so the
                    // pill sits at the compact end of its own ramp permanently —
                    // the same value the `pinned` branch used to force.
                    fraction = 1f,
                    labelColor = bt.textPrimary,
                    clickLabel = stringResource(R.string.bt_switcher_open_cd),
                    onClick = onOpenSwitcher,
                )
            }
        },
        navigationIcon = { BtHeaderWordmark(onLongPress = onLongPressWordmark) },
        actions = {
            BtHeaderSwapZone(
                items = faces.map { face -> face.action.takeIf { it != BtTabHeaderAction.None } },
                pager = pager,
            ) { action ->
                IconButton(onClick = { onAction(action) }) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = stringResource(action.labelRes),
                        tint = bt.textSecondary,
                    )
                }
            }
            // Last, always — the corner is the gear's address, and now it is one
            // address for the whole app rather than four that happen to agree.
            BtSettingsGear(onOpenSettings)
        },
        expandedHeight = BT_HEADER_COLLAPSED_HEIGHT,
        windowInsets = TopAppBarDefaults.windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = bt.bg,
            scrolledContainerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            actionIconContentColor = bt.textSecondary,
            navigationIconContentColor = bt.textSecondary,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The one bar's scroll behaviour, with **one `TopAppBarState` per tab**.
 *
 * The bar is shared but the scroll positions under it are not: the only thing a
 * pinned behaviour still does is accumulate `contentOffset`, and `contentOffset`
 * is what swaps `containerColor` for `scrolledContainerColor`. A single shared
 * state would carry Portfolio's "content is under me" tone onto Markets while
 * Markets is sitting at the top of its own list — the bar would claim a shadow
 * it is not casting.
 *
 * The map is a plain (non-observable) one on purpose: it is a cache keyed by tab,
 * and making it a snapshot map would invalidate this composition every time a tab
 * was seen for the first time, for a value that composition just computed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberBtTabHeaderBehavior(tab: BtTab?): TopAppBarScrollBehavior {
    val states = remember { mutableMapOf<BtTab, TopAppBarState>() }
    // A null tab is a pushed screen, where this bar is not drawn at all; keeping
    // the last tab's state rather than inventing one means coming back from a
    // holding detail restores the tone the tab had when it was left.
    val state = states.getOrPut(tab ?: BtTab.Portfolio) {
        TopAppBarState(
            initialHeightOffsetLimit = -Float.MAX_VALUE,
            initialHeightOffset = 0f,
            initialContentOffset = 0f,
        )
    }
    return rememberBtPinnedHeaderBehavior(state = state)
}

/**
 * One slot of the shared bar, holding **every tab's version of it at once** and
 * cross-fading between them in step with the pager.
 *
 * ## Why all of them, rather than an outgoing and an incoming
 *
 * The retired version took two faces and a `forward` flag, and faded on
 * `abs(pageOffset) / pageWidth`. That was the shape the frozen-bitmap era forced:
 * only two tabs could be *known* at a time, because the incoming one was a
 * picture rather than a page and the shell had to be told which picture.
 *
 * With four live pages the shell knows all four faces always, so the slot simply
 * composes each non-null one — at most two per zone in practice, since only
 * Portfolio has a selector and only People has an action — and gives each an
 * alpha that is a function of its distance from the pager. Nothing is added or
 * removed from composition during a gesture, which is what makes the whole drag
 * free: a frame re-runs two `alpha`/`translationX` assignments in the LAYER
 * phase and nothing else.
 *
 * It also fixes a real artefact rather than only being tidier. The old fraction
 * was anchored on `currentPage`, which flips at the halfway mark, so a drag
 * carried past the middle and then pulled back faded the incoming face in, out
 * and in again while the finger moved steadily one way. Anchoring each item on
 * its OWN index cannot do that: item `i` is at full alpha when the pager is at
 * `i` and gone by `i ± 1`, whichever way the finger is going.
 *
 * The slide is deliberately small and *against* the page's travel: the page
 * carries a whole screen width, so a bar element that matched it would be a
 * second page turn happening in the chrome. [HEADER_SWAP_SLIDE] is a hint that
 * the content is being handed over, not a journey.
 */
@Composable
private fun <T : Any> BtHeaderSwapZone(
    items: List<T?>,
    pager: PagerState,
    content: @Composable (T) -> Unit,
) {
    val slidePx = with(LocalDensity.current) { HEADER_SWAP_SLIDE.toPx() }
    Box(contentAlignment = Alignment.CenterStart) {
        items.forEachIndexed { index, item ->
            if (item != null) {
                Box(
                    Modifier.graphicsLayer {
                        val pos = tabPagerPosition(
                            pager.currentPage,
                            pager.currentPageOffsetFraction,
                            items.size,
                        )
                        alpha = tabSelectionRamp(pos, index)
                        // Against the page: the pager moving RIGHT (pos rising)
                        // slides this element LEFT, by the same rule for every
                        // item, so there is no direction to get backwards.
                        translationX = -(pos - index).coerceIn(-1f, 1f) * slidePx
                    },
                ) { content(item) }
            }
        }
    }
}

/**
 * How far a swapping bar element slides while it fades. See [BtHeaderSwapZone] —
 * a hint of travel in the page's direction, not a page turn of its own.
 */
private val HEADER_SWAP_SLIDE: Dp = 12.dp

/**
 * What ONE tab puts in the shared bar's variable slots.
 *
 * Immutable and comparable, so the shell can hold four of them and recompose the
 * bar only when a tab's face actually changes — which for three of the four tabs
 * is never.
 */
@Immutable
internal data class BtTabHeaderFace(
    /** The Portfolio tab's switcher pill. Null on every other tab. */
    val selector: BtTabSelector? = null,
    /** This tab's ONE contextual action (R-arc mandate §1). */
    val action: BtTabHeaderAction = BtTabHeaderAction.None,
) {
    internal companion object {
        /** Brand and gear only — Markets and Workbench, and a cold Portfolio. */
        val Plain = BtTabHeaderFace()
    }
}

/**
 * The Portfolio selector pill's current state, as data.
 *
 * Public because the Portfolio tab builds one and hands it to [BtTabChrome]; the
 * rest of the shared-bar vocabulary stays internal to the shell.
 */
@Immutable
data class BtTabSelector(
    val label: String,
    val icon: ImageVector,
    /** The portfolio's own kind hue, or null for the brand gold (Overview). */
    val tint: Color?,
)

/**
 * The ONE contextual action a tab may put in the shared bar.
 *
 * An enum rather than a lambda because the shell must be able to render the
 * *incoming* tab's action during a swipe, when that tab is not composed and has
 * no lambda to give — see [BtTabHeader]'s KDoc. It is also what keeps the
 * 3-element rule enforceable now that one bar serves four tabs: a fifth action
 * is a new constant here, in front of a reviewer, rather than a slot quietly
 * filled at a call site.
 */
internal enum class BtTabHeaderAction {
    None,

    /** Overview's action: search is the affordance the whole app shares. */
    Search,

    /** People's action: the chat list. The unread COUNT stays on the tab dot. */
    Messages,
    ;

    val icon: ImageVector
        get() = when (this) {
            None -> Icons.Outlined.Search // never rendered; None is filtered out
            Search -> Icons.Outlined.Search
            Messages -> Icons.AutoMirrored.Outlined.Chat
        }

    val labelRes: Int
        get() = when (this) {
            None -> R.string.bt_search_cd
            Search -> R.string.bt_search_cd
            Messages -> R.string.bt_top_messages
        }
}

/**
 * The channel a top-level tab uses to reach the shell's shared bar.
 *
 * Two things travel over it, in opposite directions:
 *
 *  - **up**, the Portfolio tab's live selector state ([portfolioSelector]) — the
 *    only part of the bar the shell cannot know by itself, because it lives in a
 *    view model inside the nav graph;
 *  - **down**, the nested-scroll connection ([headerScroll]) every tab hangs on
 *    its own scroll container so the bar can still take its tonal lift when
 *    content goes under it. That connection used to be created per page, which is
 *    what made the bar per page.
 *
 * A composition local rather than the process-global object the tab-entry signals
 * use ([at.bettertrack.app.ui.portfolio.PortfolioTabEntry]): those carry one-shot
 * *events*, which survive a dead composition harmlessly, while this carries
 * *state* that must die with the shell that owns it. A global here would keep a
 * logged-out user's portfolio name alive for the next session's login screen.
 */
@Stable
class BtTabChrome internal constructor() {
    /** Hung by each tab on its own scroll container; drives the bar's tonal lift. */
    var headerScroll: NestedScrollConnection by mutableStateOf(NoNestedScroll)
        internal set

    /**
     * The Portfolio tab's pill, or null before that tab has ever composed.
     *
     * Deliberately NOT cleared when the tab leaves composition. The Portfolio
     * page no longer leaves composition at all (it is one of four permanently
     * live pages), so in practice this is now only about the window before that
     * page has first woken up — see [BtTabLiveSet]. Keeping the last known pill
     * through it means the bar has something true to show while a swipe is
     * arriving on a page that is still being built.
     */
    var portfolioSelector: BtTabSelector? by mutableStateOf(null)
        private set

    /** True while the switcher's pinned Overview entry is the selection. */
    var portfolioIsOverview: Boolean by mutableStateOf(false)
        private set

    /** Called by the Portfolio tab whenever what the bar should say changes. */
    fun publishPortfolio(selector: BtTabSelector, isOverview: Boolean) {
        portfolioSelector = selector
        portfolioIsOverview = isOverview
    }
}

/** The no-op connection a tab gets outside the shell (previews, the gallery). */
private val NoNestedScroll = object : NestedScrollConnection {}

/**
 * The shared bar's channel. `staticCompositionLocalOf` because the instance is
 * created once per shell and never swapped — reads of it must not be tracked.
 */
val LocalBtTabChrome = staticCompositionLocalOf { BtTabChrome() }
