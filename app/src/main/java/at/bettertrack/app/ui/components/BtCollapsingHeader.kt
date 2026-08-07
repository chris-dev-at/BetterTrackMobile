package at.bettertrack.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp as lerpTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.ui.shell.BtNavMotion
import at.bettertrack.app.ui.theme.BtTheme
import kotlin.math.roundToInt

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
 * ## The tap-to-act title reads as a BUTTON (owner change 2026-08-06)
 *
 * [onTitleClick] turns the title into the screen's context *switcher* — the
 * single most valuable thing a large title can do, and the reason the portfolio
 * selector chip could leave the top bar without losing a capability.
 *
 * It first shipped as plain text plus a gold chevron, on the theory that the
 * chevron alone was affordance enough. The owner's verdict on the device was that
 * it is not: *"the portfolio selector should look more like a button with the
 * icon, like the real webapp"*. He is right, and the web app agrees with him —
 * its `.bt-portfolio-trigger` is a real `<button>`: a surface-filled, bordered
 * control carrying a tinted icon chip, the portfolio's name, and a muted
 * chevron. A title that is a control should LOOK like a control before it is
 * touched, not after.
 *
 * So a clickable title renders as [BtHeaderSelector]: a rounded, bordered,
 * surface-filled pill of `[icon chip] name ⌄`. The pill IS the affordance now,
 * which frees the chevron to go muted and leaves exactly one gold in the header —
 * the icon chip — instead of the two a gold chevron on a gold-bordered pill would
 * have made. Everything about it interpolates with the collapse, so it shrinks
 * into the 64dp strip as one object rather than swapping for a different control.
 *
 * [titleClickLabel] is the accessible name for that act (e.g. "Switch
 * portfolio"), announced as the click label so a screen reader says what will
 * happen rather than merely that something will.
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
 *
 *   **Null renders no title at all**, and three screens use it: Workbench,
 *   Markets and People (owner order 2026-08-07). Their name is already on screen,
 *   56dp below, as the selected label in the bottom navigation bar — printing it
 *   again in the strip directly above would be the same word twice in one
 *   viewport, and the *second* time is the one that has to justify itself. What
 *   the slot is worth more as is empty: the leading [BtHeaderWordmark] then reads
 *   as the row's subject on all four tabs instead of being a prefix to a label on
 *   three of them, and the strip means one thing app-wide — brand, then actions.
 *   Portfolio keeps a title because its title is not a label but the selector
 *   *control*, which is a capability rather than a repetition.
 *
 *   Null is not offered to pushed screens by convention rather than by type: a
 *   sub-page has no bottom-bar label naming it, so its title is the only thing
 *   that says where the back arrow leads back from.
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
 * @param titleIcon the glyph for the selector pill's leading chip. Only read when
 *   [onTitleClick] is set; a pill with no icon simply omits the chip.
 * @param onTitleClick when non-null, the title renders as the [BtHeaderSelector]
 *   button described above.
 * @param titleClickLabel the accessible description of [onTitleClick].
 * @param navigationIcon the leading slot of the always-visible top row: the back
 *   affordance on pushed screens, and [BtHeaderWordmark] on all four top-level
 *   tabs (owner order 2026-08-07 — see that component for why the brand is a
 *   component rather than four copies, and why it stops at the tab roots).
 * @param action the ONE contextual action, or null. Not a slot list — see above.
 * @param overflow the ⋮ menu, or null. Renders after [action].
 *
 *   **As of the 2026-08-06 navigation restoration there is no ⋮ left on any top
 *   bar in this app** — see [BtSettingsGear]. The slot survives for one reason:
 *   deleting it would make "add an overflow" a one-line change again, and the
 *   rule that has to stay enforceable is that a ⋮ must justify itself against an
 *   in-content second path. A parameter with no callers is a question a reviewer
 *   gets to ask; an absent parameter is a decision nobody remembers making.
 * @param settings the Settings gear, or null. **Renders LAST, after [overflow]**,
 *   and that ordering is the whole point — see [BtSettingsGear].
 * @param pinned draw as a fixed single-row bar that never expands or collapses,
 *   with the title slot locked to its compact form. Pair it with
 *   [rememberBtPinnedHeaderBehavior].
 *
 *   **Used by all four top-level tabs, and by nothing else** (owner order
 *   2026-08-07, extending the Portfolio directive of 2026-08-06 to its peers).
 *   The line is exactly the tab/sub-page line: a root tab's bar carries the app's
 *   identity and its two fixed controls, none of which are things you read once
 *   and are done with, so none of them should shrink away — and a tab is a place
 *   you re-enter constantly, where a bar that looks different depending on where
 *   the last visit left the scroll is a bar you have to re-read every time.
 *   Pushed screens keep the collapse: their title genuinely IS read once, on
 *   arrival, and their content deserves the 48dp back afterwards.
 * @param windowInsets defaults to the status-bar inset, which is correct
 *   everywhere in this app; pass `WindowInsets(0,0,0,0)` only when an ancestor
 *   has provably consumed it already (the debug gallery does, for instance).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtCollapsingHeader(
    title: String?,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color? = null,
    titleIcon: ImageVector? = null,
    onTitleClick: (() -> Unit)? = null,
    titleClickLabel: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    action: (@Composable () -> Unit)? = null,
    overflow: (@Composable () -> Unit)? = null,
    settings: (@Composable () -> Unit)? = null,
    pinned: Boolean = false,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val bt = BtTheme.colors
    val titleSlot: @Composable () -> Unit = {
            // ── Collapse fraction, QUANTIZED (perf pass 2026-08-06) ──────────
            //
            // M3 renders this same lambda in BOTH the collapsed row and the
            // expanded one and cross-fades them, so the sizes have to be
            // interpolated rather than switched at a threshold — a hard switch
            // would land mid-fade and read as a jump.
            //
            // Reading `collapsedFraction` raw, though, made this row recompose on
            // EVERY frame of the collapse and allocate a whole `TextStyle` (with
            // its ParagraphStyle/SpanStyle/platform-style tree) per frame, purely
            // so a font could grow by a fraction of a point. The fraction is
            // snapped to [COLLAPSE_STEPS] steps inside a `derivedStateOf`, so the
            // row recomposes only when a step boundary is crossed — at most 32
            // times across a whole collapse instead of once per frame, and zero
            // times while the header sits at either end (which is where it sits
            // for nearly all of a long scroll). One step is ~0.25sp of type and
            // ~0.3dp of height: below the threshold of visibility, and the motion
            // stays continuous because the pill's own layout animates the change.
            val step by remember(scrollBehavior) {
                derivedStateOf {
                    (scrollBehavior.state.collapsedFraction * COLLAPSE_STEPS).roundToInt()
                }
            }
            // A [pinned] bar never interpolates — it sits at the compact end of
            // every ramp above, permanently. Short-circuiting the fraction here
            // rather than at each `lerp` also means `step` is never READ in
            // pinned mode, so the derived state takes no subscription on the
            // scroll position and the title stops recomposing on scroll entirely.
            val fraction = if (pinned) 1f else step / COLLAPSE_STEPS.toFloat()

            if (title == null) {
                // Nothing — see the `title` KDoc. Composing an empty `Text("")`
                // instead would measure a full line box in the middle of the bar
                // and push the wordmark's neighbours around for a glyph nobody
                // can see; an absent slot measures 0×0 and the row is genuinely
                // just brand + actions.
                Unit
            } else if (onTitleClick != null) {
                // A title that acts is drawn as the control it is. No subtitle
                // branch here on purpose: the two screens that own a selector
                // (Overview and a portfolio) have nothing to orient the user
                // with beyond the name already inside the pill.
                BtHeaderSelector(
                    label = title,
                    icon = titleIcon,
                    fraction = fraction,
                    labelColor = titleColor ?: bt.textPrimary,
                    clickLabel = titleClickLabel,
                    onClick = onTitleClick,
                )
            } else {
                // Compose refuses to lerp TextUnits of different types ("Cannot
                // perform operation for Em and Sp" — a hard crash, found live on
                // device 2026-08-05): the brand ramp spaces letters in `em` while
                // M3's titleMedium keeps `sp`. Normalize every unit to sp (via
                // the style's own sp font size) before interpolating.
                val style = lerpTextStyle(
                    start = MaterialTheme.typography.headlineSmall.withSpUnits(),
                    stop = MaterialTheme.typography.titleMedium.withSpUnits(),
                    fraction = fraction,
                )
                Column {
                    Text(
                        text = title,
                        style = style,
                        color = titleColor ?: bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Fades out over the first half of the collapse, so
                            // it is gone before the row is tight enough for two
                            // lines to crowd each other — and it never blinks off
                            // at a threshold the way a conditional composition
                            // would.
                            modifier = Modifier.alpha((1f - fraction * 2f).coerceIn(0f, 1f)),
                        )
                    }
                }
            }
    }
    val actionsSlot: @Composable RowScope.() -> Unit = {
        action?.invoke()
        overflow?.invoke()
        // Last, always — the corner is the gear's address. See [BtSettingsGear].
        settings?.invoke()
    }
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = bt.bg,
        scrolledContainerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        actionIconContentColor = bt.textSecondary,
        navigationIconContentColor = bt.textSecondary,
    )

    // ── The pinned branch (owner directive 2026-08-06) ───────────────────────
    //
    // *"The selector for portfolio can always be on top and doesn't need to drop
    // down when scrolled all the way up."*
    //
    // On the Portfolio tab the large-title state was costing more than it bought.
    // The expanded bar's subject is a PILL the user taps — not a page title they
    // read — and a control that changes size depending on scroll position is a
    // control you have to re-find every time. The tab also opens at the top, so
    // the expanded state was the state the user met first and lost immediately.
    //
    // So this branch renders the single-row [TopAppBar] at exactly
    // [BT_HEADER_COLLAPSED_HEIGHT] with the title slot forced to `fraction = 1f`:
    // the same compact pill, in the same place, at every scroll position. The
    // content simply travels underneath it.
    //
    // The owner extended this to the other three tabs on 2026-08-07 ("do the same
    // as with the portfolio page where it just gets put up top, that works
    // great"), and the reasoning generalises without needing a pill: on Workbench,
    // Markets and People the expanded row was holding a word the bottom bar was
    // already saying, so the large-title state was spending 48dp to repeat itself
    // — and it did so only until the first scroll, which made the tab's own
    // height depend on when you last looked at it.
    //
    // What it deliberately KEEPS is the tonal scrolled container colour — that is
    // the one thing the collapse was doing that still earns its place, because it
    // is what tells the eye the bar is floating over content rather than sitting
    // on the background. That is also why this pairs with
    // [rememberBtPinnedHeaderBehavior] and not a bare `null`: a pinned behaviour
    // still tracks `contentOffset`, which is what drives that colour swap.
    if (pinned) {
        TopAppBar(
            modifier = modifier,
            title = titleSlot,
            navigationIcon = navigationIcon,
            actions = actionsSlot,
            expandedHeight = BT_HEADER_COLLAPSED_HEIGHT,
            windowInsets = windowInsets,
            colors = barColors,
            scrollBehavior = scrollBehavior,
        )
        return
    }

    LargeTopAppBar(
        modifier = modifier,
        title = titleSlot,
        navigationIcon = navigationIcon,
        actions = actionsSlot,
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
        colors = barColors,
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The BetterTrack wordmark as a top bar's leading slot — the brand strip that
 * opens **every** top-level tab (owner order 2026-08-07).
 *
 * ## The report this answers
 *
 * *"Have the BetterTrack logo on the top of the main pages — like on EVERY main
 * page, not a sub page (not asset view etc.), main stuff like social and so on —
 * and do the same as with the portfolio page where it just gets put up top, that
 * works great."*
 *
 * The wordmark's return on 2026-08-06 landed on Portfolio alone, so the app said
 * its own name on the tab it opens on and then fell silent the moment the user
 * moved sideways. Brand that appears on one of four peers reads as a property of
 * that page rather than of the app — which is the opposite of what a wordmark is
 * for.
 *
 * ## Why a component, not four call sites
 *
 * Identical reasoning to [BtSettingsGear], and it has already been proven on this
 * exact surface: there are exactly four top-level tabs, the mark must be the same
 * size, in the same corner, with the same padding on all of them, and one
 * composable is how "the same" stops depending on four authors agreeing. Written
 * out four times it would drift by a `sp` within two milestones — the shell's old
 * top bar grew to six elements the same way.
 *
 * ## What it deliberately is NOT
 *
 * Not a button, and not on sub-pages. The leading slot of a pushed screen belongs
 * to its back arrow — that is the one affordance a user must never have to hunt
 * for — so the brand stops at the tab roots, exactly as the owner drew the line.
 *
 * @param onLongPress the debug-only door to the component gallery, or null. It is
 *   gated on [BuildConfig.DEBUG] here rather than at the call site so the four
 *   tabs cannot disagree about whether a shipping build has a hidden gesture on
 *   its logo. `indication = null` and no click label: it must stay invisible and
 *   silent to TalkBack — this is a developer door on a brand mark, not an action
 *   the wordmark advertises. `onClick` is deliberately a no-op, so a normal tap
 *   on the logo does nothing on every tab.
 */
@Composable
fun BtHeaderWordmark(onLongPress: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    Wordmark(
        fontSize = 19.sp,
        modifier = Modifier
            .padding(start = 16.dp, end = 4.dp)
            .then(
                if (BuildConfig.DEBUG && onLongPress != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onLongClick = onLongPress,
                        onClick = {},
                    )
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * The Settings gear — the app's one fixed landmark (owner directive 2026-08-06).
 *
 * ## The report this answers
 *
 * The R-arc's austerity rule sent Settings behind Overview's ⋮, on the reasoning
 * that it is a rarely-used surface and a bar icon is expensive. The owner's
 * verdict on the device: *"the settings menu is absolutely inaccessible, so
 * niche"* — and *"the nav on the old version was 10 times better"*.
 *
 * Both halves of that are true at once, and the resolution is not to undo the
 * new style but to notice what the old nav actually had: a **fixed** thing in a
 * **fixed** place. Frequency was never the point. A landmark is not something you
 * use often; it is something you can steer by, and its value is that it is in the
 * same corner on every screen you might be lost on. Settings behind a menu whose
 * contents changed per page had no address at all — the user had to remember
 * which page's ⋮ was the one with Settings in it.
 *
 * ## Why it is drawn as a component and not written four times
 *
 * There are exactly four top-level tabs and the gear must be identical on all of
 * them — same glyph, same tint, same accessible name, same trailing position. One
 * composable is how "identical" stops depending on four authors agreeing.
 *
 * ## Why it renders LAST in the actions row
 *
 * `settings` is invoked after `action` and after `overflow`, so the gear owns the
 * final slot before the screen edge no matter what else a bar carries. If it were
 * placed before the overflow, a bar that grew a ⋮ would shift the gear inward and
 * the landmark would move — which is precisely the failure being fixed. It costs
 * the Android convention that ⋮ sits last; that convention is worth less here
 * than an anchor that never moves, and after this change no top bar in the app
 * carries a ⋮ for it to conflict with anyway.
 *
 * Deliberately carries **no badge**. The gear means one thing — "the app's
 * settings are here" — and a dot on it would make it mean "something happened",
 * which is the ⋮'s old job and the reason the ⋮ was ambiguous.
 */
@Composable
fun BtSettingsGear(onClick: () -> Unit) {
    val bt = BtTheme.colors
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = stringResource(R.string.bt_top_settings),
            tint = bt.textSecondary,
        )
    }
}

/**
 * The number of steps the collapse fraction is snapped to before anything is
 * sized from it. See the comment in [BtCollapsingHeader]'s title slot: this is a
 * recomposition budget, not a visual choice — 32 steps over a 48dp collapse and
 * an 8sp type change is finer than the eye or the pixel grid can resolve.
 */
private const val COLLAPSE_STEPS = 32

/**
 * The clickable title, drawn as a button (owner change 2026-08-06).
 *
 * ## Shape of the thing
 *
 * `[icon chip] label ⌄` inside a bordered, surface-filled rounded rect — the
 * Android reading of the web app's `.bt-portfolio-trigger`, which is a 1px
 * border on `--bt-surface` at radius 6 with a 30px tinted icon chip at radius 8,
 * a 13.5px/620 name, and a muted 14px chevron. The proportions carry over; the
 * sizes do not, because this pill has to be the page's *subject* when expanded,
 * where the web's sits in a permanent 48px topbar and never grows.
 *
 * ## Why every dimension interpolates
 *
 * M3 draws this lambda twice — once in the collapsed row, once in the expanded
 * one — and cross-fades. If the pill were one fixed size, the two copies would
 * be identical and the collapse would read as a fade between two things in
 * different places rather than one thing moving. Interpolating height, radius,
 * chip, glyph, type and padding against the same [fraction] makes it a single
 * object that shrinks: 44dp → 34dp tall, 20sp → 15sp label, 30dp → 24dp chip.
 *
 * ## The gold is on the chip, not the chevron
 *
 * The design system allows exactly one accent, and the pre-button title spent it
 * on a gold chevron because the chevron was the only affordance it had. A pill
 * announces itself, so the chevron drops to muted (as on the web) and the gold
 * moves to the icon chip — a translucent-gold tile, which is the §5 treatment for
 * a small control that states the current selection.
 *
 * ## Touch target
 *
 * The click modifier sits OUTSIDE the 2dp visual inset, so the tap area is the
 * full 48dp the design system asks for while the drawn pill stays 44dp and clears
 * the 48dp of vertical room M3 gives the expanded title.
 */
@Composable
private fun BtHeaderSelector(
    label: String,
    icon: ImageVector?,
    fraction: Float,
    labelColor: Color,
    clickLabel: String?,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }

    val height = lerp(44.dp, 34.dp, fraction)
    val radius = lerp(14.dp, 11.dp, fraction)
    val chip = lerp(30.dp, 24.dp, fraction)
    val chipRadius = lerp(9.dp, 7.dp, fraction)
    val glyph = lerp(18.dp, 15.dp, fraction)
    val chevron = lerp(20.dp, 17.dp, fraction)
    val labelSize = lerp(20.sp, 15.sp, fraction)
    val startPad = lerp(6.dp, 5.dp, fraction)
    val endPad = lerp(12.dp, 10.dp, fraction)
    val gap = lerp(10.dp, 8.dp, fraction)

    // The two shapes are the only per-step ALLOCATIONS in the pill, and a new
    // shape instance also invalidates the background/border draw caches that
    // hang off it. Keyed on the rounded dp, so the 32 collapse steps produce at
    // most a handful of distinct instances instead of two per step.
    val shape = remember(radius) { RoundedCornerShape(radius) }
    val chipShape = remember(chipRadius) { RoundedCornerShape(chipRadius) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // Click first: the tap area is the padded box, i.e. 48dp expanded.
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = clickLabel,
                onClick = onClick,
            )
            .btPressScale(interaction)
            .padding(vertical = 2.dp)
            .height(height)
            // ── `background(colour, shape)`, NOT `clip(shape) + background()` ──
            //
            // (Perf claw-back 2026-08-06.) The two forms paint identically here,
            // and they cost very differently. `Modifier.clip` is a graphics
            // LAYER: it allocates a RenderNode and a clip path so that anything
            // drawn by the children can be cut to the rounded rect. The
            // shape-taking `background` overload has no children to cut — it
            // simply rasterises the rounded rect it was handed.
            //
            // Nothing in this pill needs the clip. The label is `maxLines = 1`
            // + `Ellipsis` and the chevron is a fixed-size glyph inside the
            // padding, so no child can reach the rounded corners to be clipped
            // BY them; the clip was only ever paying for a guarantee the layout
            // already made. And M3 composes this whole lambda TWICE — once for
            // the collapsed row, once for the expanded one — so a layer here is
            // two RenderNodes on every frame of every scroll, one of which is
            // sitting at alpha 0 whenever the header is at either end of its
            // travel (i.e. nearly always).
            .background(bt.surface, shape)
            .border(1.dp, bt.borderStrong, shape)
            .padding(start = startPad, end = endPad),
    ) {
        if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(chip)
                    // Same reasoning as the pill above: the glyph is centred and
                    // strictly smaller than the chip, so the clip could never
                    // cut anything — it only cost a second RenderNode per copy.
                    .background(bt.goldWash, chipShape)
                    .border(1.dp, bt.edge(bt.gold, 0.26f), chipShape),
            ) {
                Icon(
                    imageVector = icon,
                    // The pill's click label already names the act; a second
                    // description on a garnish glyph would make a screen reader
                    // read the same control twice.
                    contentDescription = null,
                    tint = bt.gold,
                    modifier = Modifier.size(glyph),
                )
            }
            Spacer(Modifier.width(gap))
        }
        Text(
            text = label,
            fontSize = labelSize,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // fill = false so the pill hugs a short name instead of stretching
            // to the whole bar; weight so a long one ellipsizes rather than
            // pushing the chevron out of the header.
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(chevron),
        )
    }
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
 * The scroll behaviour [BtCollapsingHeader] expects when `pinned = true`.
 *
 * A pinned behaviour never writes `heightOffset`, so the bar cannot shrink, grow
 * or scroll away — which is the entire point on the four top-level tabs, where
 * the wordmark, the gear and (on Portfolio) the selector pill must stay at the
 * same coordinates at every scroll position (owner directive 2026-08-06,
 * extended to all four tabs 2026-08-07).
 *
 * It is NOT the same as passing no behaviour at all, and the difference is the
 * reason this function exists rather than a comment saying "use pinned". A
 * pinned behaviour still accumulates `contentOffset`, and `contentOffset` is what
 * `TopAppBar` reads to cross-fade `containerColor` → `scrolledContainerColor`.
 * Drop the behaviour and the bar keeps its position but loses the tonal lift, so
 * content scrolls *through* a bar that looks like background — the exact seam
 * this app spent its header work removing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberBtPinnedHeaderBehavior(
    state: TopAppBarState = rememberTopAppBarState(),
    canScroll: () -> Boolean = { true },
): TopAppBarScrollBehavior =
    TopAppBarDefaults.pinnedScrollBehavior(state, canScroll = canScroll)

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
