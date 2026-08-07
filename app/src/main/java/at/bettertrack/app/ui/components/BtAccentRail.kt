package at.bettertrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A 3dp performance rail down a card's leading edge.
 *
 * §4.1: an asset is a bet, so its colour is its verdict — gain or loss by the
 * range the user is currently looking at. The rail is what makes a list of
 * holdings scannable without reading a single number, and it is the answer to
 * "everything on screen is grey except gold".
 *
 * **It is redundant encoding by construction, and must stay that way.** Every
 * surface that carries a rail already ships a signed number or an arrow beside
 * it, so the rail adds speed for colour-sighted users and takes nothing from
 * anyone else. Never let a rail become the only thing distinguishing two rows.
 *
 * Deliberately NOT applied to controls: a range chip stays gold, because a chip
 * is a control and gold is what the app's controls are. Letting the accent leak
 * into chrome is how "colour as signal" turns back into decoration.
 */
@Composable
fun BtAccentRail(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(color),
    )
}

/**
 * Wraps a card's content so [rail] runs flush down its leading edge, full height.
 *
 * The rail lives INSIDE the card rather than on its modifier: a card clips its
 * content to a rounded shape, so a rail drawn outside that clip would square off
 * the corner it overlaps. [IntrinsicSize.Min] is what lets the rail's
 * `fillMaxHeight` resolve against a row whose height comes from its own content.
 *
 * Pass `rail = null` for a row with no verdict to state (no price yet, zero
 * change) — the space is not reserved, because a rail that is sometimes invisible
 * would read as a rendering bug.
 */
@Composable
fun BtRailedRow(
    rail: Color?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (rail != null) BtAccentRail(rail)
        content()
    }
}

private val RAIL_WIDTH = 3.dp
