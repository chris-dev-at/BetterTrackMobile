package at.bettertrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.ui.theme.BtTheme

/**
 * A calm initials avatar (spec §5 — social/chat surfaces are emotional; give
 * them personal identity without breaking the palette). The tint is a gentle,
 * deterministic pick from the CVD-validated donut ramp (already checked against
 * `#171717`), so people are distinguishable at a glance while gold stays the sole
 * brand accent. Pass [gold] = true for the signed-in user / self chip.
 */
@Composable
fun BtAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    gold: Boolean = false,
) {
    val bt = BtTheme.colors
    val initials = initialsOf(name)
    val tint = if (gold) bt.gold else avatarTint(name)
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = tint.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.40f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initials,
                color = if (gold) bt.goldEmphasis else tint,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                fontSize = (size.value * 0.36f).sp,
            )
        }
    }
}

private fun initialsOf(name: String): String {
    val cleaned = name.trim().trimStart('@')
    if (cleaned.isEmpty()) return "?"
    // Split on separators common in usernames/handles.
    val parts = cleaned.split(' ', '.', '_', '-').filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        else -> cleaned.take(2).uppercase()
    }
}

/** Deterministic muted tint from the validated ramp (blue/teal/violet/rose/gold-brown). */
private fun avatarTint(name: String): Color {
    val ramp = listOf(
        Color(0xFF3987E5),
        Color(0xFF1D9DBF),
        Color(0xFF6D5BD0),
        Color(0xFFC25B8E),
        Color(0xFFB58840),
    )
    val idx = (name.trim().lowercase().hashCode().let { if (it == Int.MIN_VALUE) 0 else it } and 0x7fffffff) % ramp.size
    return ramp[idx]
}
