package at.bettertrack.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shapes (spec §3.5): 6–8dp corner radius for cards/controls, flat design,
 * borders instead of elevation. Full-round (pill) ONLY for chips/badges and
 * small state buttons.
 */
object BtShapes {
    val card = RoundedCornerShape(8.dp)
    val cardSmall = RoundedCornerShape(6.dp)
    val control = RoundedCornerShape(8.dp)
    val pill = CircleShape
}

val BtMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)
