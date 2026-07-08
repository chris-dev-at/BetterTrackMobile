package at.bettertrack.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The BetterTrack wordmark (spec §3.2): always one word, two colors —
 * "Better" in white + "Track" in gold, bold, tight letter-spacing. Optional
 * edition suffix ("App") after a normal space at ~0.78em, medium weight, muted.
 * All sizing is em-relative so the same construction scales from top bar to
 * login screen. Never recolor or restyle.
 */
@Composable
fun Wordmark(
    fontSize: TextUnit = 22.sp,
    modifier: Modifier = Modifier,
    edition: String? = null,
) {
    val bt = BtTheme.colors
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = bt.textPrimary)) { append("Better") }
            withStyle(SpanStyle(color = bt.gold)) { append("Track") }
            if (edition != null) {
                append(" ")
                withStyle(
                    SpanStyle(
                        color = bt.textMuted,
                        fontSize = fontSize * 0.78f,
                        fontWeight = FontWeight.Medium,
                    ),
                ) { append(edition) }
            }
        },
        modifier = modifier,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Default,
        letterSpacing = (-0.025).em,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}
