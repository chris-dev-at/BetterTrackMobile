package at.bettertrack.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.ui.shell.BtNavMotion
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The app's collapsing large-title header (R-arc mandate §1/§4).
 *
 * ## Why this exists as a component and not as a per-screen bar
 *
 * The mandate asks for the Android-2026 idiom — "large-title toolbars that
 * collapse, not dense fixed action rows" — and the app had none: all 39 of its
 * bars were plain [androidx.compose.material3.TopAppBar]. Building it once, here,
 * is what makes the R2 conversion of the other 38 a mechanical change rather than
 * 38 chances to invent a slightly different header. It also keeps the 3-element
 * rule enforceable in one place: **context/title, ONE action, overflow** — which
 * is why [action] is a single slot and not a `RowScope` the caller can fill with
 * four icons the way the old shell bar grew to six.
 *
 * ## What "collapsing" buys, concretely
 *
 * The expanded row is where a screen states what it is *about* — on Portfolio,
 * the portfolio's own name, at a size that makes it the page's subject rather
 * than a label above it. The moment the user starts reading content, that claim
 * has been made and the space is better spent on the content, so the bar shrinks
 * to a 64dp identity strip and stays out of the way. The user never loses the
 * title and never pays for it twice.
 *
 * ## The tap-to-act title
 *
 * [onTitleClick] turns the title into the screen's context *switcher* — the
 * single most valuable thing a large title can do, and the reason the portfolio
 * selector chip could leave the top bar without losing a capability. The gold
 * chevron is the affordance: it is the only gold in the header, so "this title is
 * a control" reads before any label does. [titleClickLabel] is the accessible
 * name for that act (e.g. "Switch portfolio"), announced as the click label so a
 * screen reader says what will happen rather than merely that something will.
 *
 * ## Colors: tonal elevation, not a divider
 *
 * Mandate §4 asks for tonal elevation instead of divider lines. `containerColor`
 * is the page background, so an expanded header is indistinguishable from the
 * page — no seam where nothing has scrolled yet — and `scrolledContainerColor`
 * lifts to the card surface once content has gone under it. The separation
 * appears exactly when there is something to separate.
 *
 * ## The subtitle, and why it fades instead of persisting
 *
 * R2 converts the pushed screens, and four of them (Transactions, Cash, Holding
 * detail, Standing orders) had a two-line bar title: the screen's name over the
 * portfolio it belongs to. That second line is *orienting* information — it
 * answers "whose transactions am I looking at" once, on arrival, and never
 * again. So it lives in the expanded region and fades out with the collapse
 * rather than being carried forever in a 64dp strip that the 3-element rule
 * wants kept to one idea. It is always composed (never conditionally removed) so
 * the collapse is a pure alpha animation with no reflow, and the expanded height
 * grows to [BT_HEADER_EXPANDED_HEIGHT_SUBTITLE] to give the extra line real room
 * instead of letting it clip against the title.
 *
 * @param title the screen's subject. One line, ellipsized: a portfolio name can
 *   be arbitrarily long and a wrapping header would change the bar's height on
 *   content the user chose, which is worse than a truncated name.
 * @param subtitle optional orienting second line — see above. Fades on collapse.
 * @param titleColor overrides the title's colour. Exists for exactly one case:
 *   "Where your data lives" turns its title red while the user is inside the
 *   delete-everything section, so the destructive context is stated by the
 *   screen's own subject rather than only by the button at the bottom of it.
 *   That is a real signal and the reason this parameter is not a styling hook —
 *   default to null everywhere else, because a header that can be any colour
 *   stops meaning anything when one of them turns red.
 * @param scrollBehavior from [rememberBtCollapsingHeaderBehavior]; its
 *   `nestedScrollConnection` must be hung on an ancestor of the screen's
 *   scrollable or the header will never collapse.
 * @param onTitleClick when non-null, the title row becomes clickable and grows a
 *   gold chevron.
 * @param titleClickLabel the accessible description of [onTitleClick].
 * @param navigationIcon the back affordance on pushed screens; empty on tabs.
 * @param action the ONE contextual action, or null. Not a slot list — see above.
 * @param overflow the ⋮ menu, or null. Renders after [action], as the last thing
 *   in the row, which is where every Android user's thumb expects to find it.
 * @param windowInsets defaults to the status-bar inset, which is correct
 *   everywhere in this app; pass `WindowInsets(0,0,0,0)` only when an ancestor
 *   has provably consumed it already (the debug gallery does, for instance).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtCollapsingHeader(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color? = null,
    onTitleClick: (() -> Unit)? = null,
    titleClickLabel: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    action: (@Composable () -> Unit)? = null,
    overflow: (@Composable () -> Unit)? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val bt = BtTheme.colors
    LargeTopAppBar(
        modifier = modifier,
        title = {
            // The two type sizes the mandate's idiom needs, interpolated rather
            // than switched: M3 renders this same lambda in BOTH the collapsed
            // row and the expanded one and cross-fades them, so a hard switch at
            // some threshold would land mid-fade and read as a jump. Reading
            // `collapsedFraction` here also scopes the per-frame recomposition to
            // this one row instead of the whole header.
            val fraction = scrollBehavior.state.collapsedFraction
            // Compose refuses to lerp TextUnits of different types ("Cannot
            // perform operation for Em and Sp" — a hard crash, found live on
            // device 2026-08-05): the brand ramp spaces letters in `em` while
            // M3's titleMedium keeps `sp`. Normalize every unit to sp (via the
            // style's own sp font size) before interpolating.
            val style = lerp(
                start = MaterialTheme.typography.headlineSmall.withSpUnits(),
                stop = MaterialTheme.typography.titleMedium.withSpUnits(),
                fraction = fraction,
            )
            val interaction = remember { MutableInteractionSource() }
            val titleRow = if (onTitleClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = titleClickLabel,
                    onClick = onTitleClick,
                )
            } else {
                Modifier
            }
            Column(modifier = titleRow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = style,
                        color = titleColor ?: bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onTitleClick != null) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            // The click label on the row already says what happens;
                            // a second announcement on the glyph would make a screen
                            // reader read the same act twice.
                            contentDescription = null,
                            tint = bt.gold,
                            modifier = Modifier.size(if (fraction > 0.5f) 20.dp else 24.dp),
                        )
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Fades out over the first half of the collapse, so it is
                        // gone before the row is tight enough for two lines to
                        // crowd each other — and it never blinks off at a
                        // threshold the way a conditional composition would.
                        modifier = Modifier.alpha((1f - fraction * 2f).coerceIn(0f, 1f)),
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = {
            action?.invoke()
            overflow?.invoke()
        },
        collapsedHeight = BT_HEADER_COLLAPSED_HEIGHT,
        expandedHeight = if (subtitle != null) {
            BT_HEADER_EXPANDED_HEIGHT_SUBTITLE
        } else {
            BT_HEADER_EXPANDED_HEIGHT
        },
        // This header consumes the status-bar inset itself, and it must: the app
        // shell zeroes its Scaffold's `contentWindowInsets` because its own bars
        // handle theirs, so a destination that sets `ownsItsHeader` is the ONLY
        // thing standing between the status bar and its own title. Leaving it to
        // the caller would make "the portfolio name is drawn under the clock" a
        // mistake each of R2's screens gets to rediscover.
        windowInsets = windowInsets,
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

/** Collapsed height — one standard app-bar row, so tab hops don't shift content. */
val BT_HEADER_COLLAPSED_HEIGHT = 64.dp

/**
 * Expanded height. 112dp = the collapsed row plus one 48dp title line: enough for
 * the large title to be the page's subject, short enough that the first content
 * row is still visible above the fold on a 360×800 screen — which is the whole
 * point of the §3 hierarchy work this header is part of.
 */
val BT_HEADER_EXPANDED_HEIGHT = 112.dp

/**
 * Expanded height when a `subtitle` is present: 112 + one 20dp `bodySmall` line.
 * Sized rather than left to wrap because M3 clips the title slot to
 * `expandedHeight` — a second line that does not fit does not push the bar
 * taller, it silently disappears, which is the worst of both outcomes.
 */
val BT_HEADER_EXPANDED_HEIGHT_SUBTITLE = 132.dp

/**
 * The scroll behaviour [BtCollapsingHeader] expects: exit-until-collapsed.
 *
 * Not `enterAlways`: a header that springs back on the first upward pixel makes
 * a long holdings list feel like it is fighting the finger. Exit-until-collapsed
 * gives the space back for the whole downward journey and returns the title only
 * when the user has actually returned to the top.
 *
 * @param canScroll gates collapsing on whether the body can actually scroll. Pass
 *   a real predicate on any screen whose body has non-scrolling branches (a
 *   centred empty/error state, a short form): without it, a fling that begins on
 *   a long branch and ends on a short one leaves a half-height bar with nothing
 *   on screen a finger could scroll to bring the title back. Screens whose body
 *   always scrolls can leave the default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberBtCollapsingHeaderBehavior(
    state: TopAppBarState = rememberTopAppBarState(),
    canScroll: () -> Boolean = { true },
): TopAppBarScrollBehavior =
    TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state, canScroll = canScroll)

/**
 * Return a collapsed header to fully expanded, animated (R3 §1).
 *
 * ## Why this exists
 *
 * Two screens change what their whole body IS while the header stays
 * ([at.bettertrack.app.ui.market.AssetPageScreen] between its loaded/empty/error
 * branches, and "Where your data lives" between its three sections). Both must
 * put the bar back — a collapse carried into a branch too short to scroll is
 * unrecoverable — and both did it by assigning `heightOffset = 0f`, which snaps
 * 64dp of bar back in a single frame while the user is looking straight at it.
 * The height change is legitimate; doing it instantly is the jank.
 *
 * `TopAppBarState` exposes no animator of its own, so this drives `heightOffset`
 * with the same duration and easing as the app's screen transitions
 * ([at.bettertrack.app.ui.shell.BtNavMotion]) — the branch swap and the bar
 * settle together instead of one arriving after the other.
 *
 * `contentOffset` is reset up-front rather than animated: it is not a rendered
 * dimension but the scroll accumulator the behaviour uses to decide when the bar
 * may expand again, and leaving it negative during the animation would let the
 * behaviour fight the values being written in.
 *
 * Under reduced motion it assigns directly — which is exactly the old behaviour,
 * and correct: "remove animations" asks for the end state, now.
 */
@OptIn(ExperimentalMaterial3Api::class)
suspend fun TopAppBarScrollBehavior.btExpandHeader(reducedMotion: Boolean = false) {
    val from = state.heightOffset
    state.contentOffset = 0f
    if (reducedMotion || from == 0f) {
        state.heightOffset = 0f
        return
    }
    animate(
        initialValue = from,
        targetValue = 0f,
        animationSpec = tween(
            durationMillis = BtNavMotion.DURATION_TOTAL_MS,
            easing = FastOutSlowInEasing,
        ),
    ) { value, _ -> state.heightOffset = value }
}

/**
 * Converts a style's em-based letterSpacing/lineHeight to sp using its own sp
 * font size, so two styles can always be lerped. A style whose units are
 * already sp (or unspecified) passes through untouched.
 */
private fun TextStyle.withSpUnits(): TextStyle {
    if (!fontSize.isSp) return this
    val ls = if (letterSpacing.isEm) (letterSpacing.value * fontSize.value).sp else letterSpacing
    val lh = if (lineHeight.isEm) (lineHeight.value * fontSize.value).sp else lineHeight
    return if (ls == letterSpacing && lh == lineHeight) this else copy(letterSpacing = ls, lineHeight = lh)
}
