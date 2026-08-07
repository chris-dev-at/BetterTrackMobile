package at.bettertrack.app.ui.shell

import androidx.compose.foundation.layout.Box
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
import kotlin.math.abs

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
 * its own bar content. That cannot work here, and the reason is the swipe: at the
 * moment the bar must show the *incoming* tab's content, the incoming tab is not
 * composed. It is a frozen bitmap ([BtTabPeekLayers]) precisely because composing
 * it a second time would double its view models and its network load. A slot
 * belonging to a page that does not exist yet cannot be invoked.
 *
 * A face is therefore a small immutable **description** ([BtTabHeaderFace]) that
 * the shell can hold for every tab at once, including tabs that are not on
 * screen, and render itself. Three of the four faces are constants the shell
 * simply knows; only Portfolio's changes with app state, and it publishes that
 * state through [BtTabChrome] the same way its face is published through the peek
 * layers — as the last thing that tab was, which is exactly what the swipe is
 * showing underneath.
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
    /** The face of the tab the nav graph is showing. */
    face: BtTabHeaderFace,
    /** The face the swipe is revealing, or null when nothing is in flight. */
    incoming: BtTabHeaderFace?,
    /** True when the revealed tab is the one to the RIGHT — the pager idiom. */
    forward: Boolean,
    swipe: BtTabSwipeState,
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
                outgoing = face.selector,
                incoming = incoming?.selector,
                forward = forward,
                swipe = swipe,
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
                outgoing = face.action.takeIf { it != BtTabHeaderAction.None },
                incoming = incoming?.action?.takeIf { it != BtTabHeaderAction.None },
                forward = forward,
                swipe = swipe,
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
 * One slot of the shared bar, crossfading its outgoing content for its incoming
 * one **in step with the finger**.
 *
 * Both copies are drawn in the same [Box], so neither is laid out relative to the
 * other and the zone's width is simply the wider of the two — it never reflows
 * mid-gesture, which is what would make the neighbouring slots twitch.
 *
 * The fraction is read inside [graphicsLayer]'s block form, i.e. in the LAYER
 * phase. That is the whole performance story of this component: a drag frame
 * re-runs two `alpha`/`translationX` assignments and nothing else — no
 * recomposition, no re-measure — which is the same contract the two page layers
 * hold themselves to ([BtTabSwipeState]).
 *
 * The slide is deliberately small and *against* the page's travel: the page
 * carries a whole screen width, so a bar element that matched it would be a
 * second page turn happening in the chrome. [HEADER_SWAP_SLIDE] is a hint that
 * the content is being handed over, not a journey.
 */
@Composable
private fun <T : Any> BtHeaderSwapZone(
    outgoing: T?,
    incoming: T?,
    forward: Boolean,
    swipe: BtTabSwipeState,
    content: @Composable (T) -> Unit,
) {
    val slidePx = with(LocalDensity.current) { HEADER_SWAP_SLIDE.toPx() }
    Box(contentAlignment = Alignment.CenterStart) {
        if (outgoing != null) {
            Box(
                Modifier.graphicsLayer {
                    val f = tabHeaderSwapFraction(
                        pageOffsetPx = swipe.pageOffsetPx,
                        pageWidthPx = swipe.pageWidthPx,
                        handedOff = swipe.handoff != null,
                    )
                    alpha = 1f - f
                    translationX = if (forward) -f * slidePx else f * slidePx
                },
            ) { content(outgoing) }
        }
        if (incoming != null) {
            Box(
                Modifier.graphicsLayer {
                    val f = tabHeaderSwapFraction(
                        pageOffsetPx = swipe.pageOffsetPx,
                        pageWidthPx = swipe.pageWidthPx,
                        handedOff = swipe.handoff != null,
                    )
                    alpha = f
                    translationX = if (forward) (1f - f) * slidePx else -(1f - f) * slidePx
                },
            ) { content(incoming) }
        }
    }
}

/**
 * How far through the hand-over the bar's variable content is, 0..1.
 *
 * The same displacement the pages ride, as a fraction of one page — so the
 * content has completed its crossfade exactly when the incoming page has arrived,
 * and a drag the user changes their mind about fades back with it.
 *
 * [handedOff] pins it at 1, and that pin is not decoration: a committed swipe
 * snaps the page offset back to zero the instant it tells the NavHost to swap
 * (see `btTabSwipe`), so a raw reading of the offset would say "no swipe in
 * progress" while the shell is still showing the OLD tab's face over the NEW
 * tab's page. That is the same handoff race the bottom bar's indicator latch
 * exists for, arriving in the top bar — and it is fixed the same way, by
 * believing the commit rather than the coordinate.
 */
internal fun tabHeaderSwapFraction(
    pageOffsetPx: Float,
    pageWidthPx: Float,
    handedOff: Boolean,
): Float = when {
    handedOff -> 1f
    pageWidthPx <= 0f -> 0f
    else -> (abs(pageOffsetPx) / pageWidthPx).coerceIn(0f, 1f)
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
     * Deliberately NOT cleared when the tab leaves composition. A swipe onto
     * Portfolio shows that tab's frozen last frame, so the bar showing its last
     * known pill is the same picture from the same instant — clearing it would
     * make the one moment the value is needed the one moment it is absent.
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
