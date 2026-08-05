package at.bettertrack.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shapes — R-arc §4 ("consistent 12–16dp radii").
 *
 * The original spec §3.5 pinned 6–8dp. The R-arc mandate supersedes it in as
 * many words ("this supersedes conflicting earlier polish guidance") and names
 * the radius band explicitly, so the constants move rather than the call sites:
 * all 67 `BtShapes.*` uses inherit the new language for free, and R1's Home and
 * Portfolio pick it up in the same breath as R2's screens. That is the whole
 * reason radii live here and not in the screens.
 *
 * Three steps, all inside the mandated band, because one radius for everything
 * flattens the hierarchy the rest of R2 is building:
 *  - [card] 12dp — a list row. The dense end of the band: rows repeat, and a
 *    repeated 16dp corner reads as a pile of lozenges rather than a list.
 *  - [group] 16dp — a *container* of rows (settings groups, the Needs-you
 *    block). The generous end, so a group is legible as one object at a glance
 *    and its rows as parts of it.
 *  - [control] 12dp — inputs and buttons, matched to [card] so a search field
 *    and the row under it share a silhouette.
 *
 * Flat design and borders-instead-of-elevation are unchanged. Full-round (pill)
 * stays reserved for chips/badges and small state buttons.
 */
object BtShapes {
    val card = RoundedCornerShape(12.dp)
    val cardSmall = RoundedCornerShape(10.dp)
    val control = RoundedCornerShape(12.dp)

    /** Tonal grouping container — see [BtShapes] KDoc. R-arc §4. */
    val group = RoundedCornerShape(16.dp)
    val pill = CircleShape
}

val BtMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
