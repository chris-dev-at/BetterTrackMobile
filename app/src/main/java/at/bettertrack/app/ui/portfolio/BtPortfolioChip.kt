package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.repo.BT_KIND_GROUP_TINT_SLOT
import at.bettertrack.app.data.repo.BtPortfolioKind
import at.bettertrack.app.ui.theme.BtIcons
import at.bettertrack.app.ui.theme.BtTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape

/**
 * A portfolio's identity mark: its icon glyph on its own hue.
 *
 * This is the app's single biggest "use more colour" win, and it is pure web
 * parity — `PortfolioIconChip.tsx` is exactly this control, down to the numbers:
 * a 26dp rounded chip (30dp `lg` for the one that states the current scope), hue
 * at 14% fill, hue at 26% border, glyph at full strength.
 *
 * Before this, portfolio identity was carried by the name alone and every chip in
 * the app was gold — 243 uses of `gold` against nothing else, which is why a list
 * of portfolios read as a list of identical rows.
 *
 * **Colour is never the only carrier here.** Each kind has its own glyph, so the
 * hue is reinforcement rather than the identity channel — which is what makes the
 * six hues legitimate despite failing a chart-series CVD check that does not
 * model glyphs (see [at.bettertrack.app.ui.theme.BtColors.kindTints]).
 */
@Composable
fun BtPortfolioChip(
    kind: BtPortfolioKind,
    modifier: Modifier = Modifier,
    group: Boolean = false,
    size: Dp = BtPortfolioChipSize,
) {
    val bt = BtTheme.colors
    val hue = portfolioKindTint(kind)
    Box(modifier.size(size)) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = RoundedCornerShape(size * CHIP_CORNER_RATIO),
            color = bt.wash(hue, 0.14f),
            border = BorderStroke(1.dp, bt.edge(hue, 0.26f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = portfolioKindIcon(kind),
                    // The chip always sits beside the portfolio's name, and the
                    // kind is a property of that name, not a separate control.
                    contentDescription = null,
                    tint = hue,
                    modifier = Modifier.size(size * GLYPH_RATIO),
                )
            }
        }
        if (group) {
            // The shared marker — a corner dot, NOT a glyph or hue override.
            //
            // Forcing a group portfolio onto the group glyph is exactly what the
            // web tried and retired: it made the Icon setting a silent no-op for
            // the portfolios people most want to tell apart. "What this book is
            // for" and "others are in it" are two facts, so they get two channels
            // instead of competing for one.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(size * MARKER_RATIO)
                    .background(bt.kindTints[BT_KIND_GROUP_TINT_SLOT], CircleShape)
                    .border(1.dp, bt.surface, CircleShape),
            )
        }
    }
}

/** The switcher-row chip. `origin.css` `.bt-pf-chip`: 26px. */
val BtPortfolioChipSize: Dp = 26.dp

/** The trigger chip — one step up, because it states the current scope. `--lg`: 30px. */
val BtPortfolioChipSizeLarge: Dp = 30.dp

/**
 * Glyph for a kind — **always** the kind's own, never overridden by `group`.
 * Mirrors the web's `portfolioIconName`, which takes the portfolio only to keep
 * its signature API-shaped and then ignores it for exactly this reason.
 */
fun portfolioKindIcon(kind: BtPortfolioKind): ImageVector = when (kind) {
    BtPortfolioKind.Private -> BtIcons.UserLock
    BtPortfolioKind.Family -> BtIcons.Family
    BtPortfolioKind.Business -> BtIcons.Briefcase
    BtPortfolioKind.Savings -> BtIcons.PiggyBank
    BtPortfolioKind.Property -> BtIcons.Building
}

/**
 * Hue for a kind, from the theme's [at.bettertrack.app.ui.theme.BtColors.kindTints].
 * Also never overridden by `group` — see [portfolioKindIcon].
 */
@Composable
fun portfolioKindTint(kind: BtPortfolioKind): Color = BtTheme.colors.kindTints[kind.ordinal]

/** Translated label — the picker rows and any `contentDescription` that needs one. */
@Composable
fun portfolioKindLabel(kind: BtPortfolioKind): String = stringResource(
    when (kind) {
        BtPortfolioKind.Private -> R.string.bt_pf_kind_private
        BtPortfolioKind.Family -> R.string.bt_pf_kind_family
        BtPortfolioKind.Business -> R.string.bt_pf_kind_business
        BtPortfolioKind.Savings -> R.string.bt_pf_kind_savings
        BtPortfolioKind.Property -> R.string.bt_pf_kind_property
    },
)

/** `origin.css`: 7px radius on a 26px chip, 8px on 30px — a constant ratio. */
private const val CHIP_CORNER_RATIO = 7f / 26f

/** The web draws a 16px glyph inside the 26px chip. */
private const val GLYPH_RATIO = 16f / 26f

/** The shared-marker dot, sized off the chip so it scales with `--lg`. */
private const val MARKER_RATIO = 9f / 26f
