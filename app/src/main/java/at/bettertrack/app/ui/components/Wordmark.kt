package at.bettertrack.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The BetterTrack wordmark (spec §3.2): always one word, two colors —
 * "Better" in the primary ink + "Track" in gold, bold, tight letter-spacing.
 * Optional edition suffix ("App") after a normal space at ~0.78em, medium
 * weight, muted. All sizing is em-relative so the same construction scales from
 * top bar to login screen. Never recolor or restyle.
 *
 * ## "Track" is drawn in the CONSTANT brand gold in BOTH themes (owner order
 * 2026-08-07: "the yellow of the logo shouldn't change to a darker rusty gold —
 * it should stay the BetterTrack yellow; don't change up the logo"). WCAG
 * exempts logotypes from contrast minimums, so brand wins here — and ONLY here;
 *
 * The two are the same value in dark, so this is byte-identical there and the
 * mark is unchanged. They diverge in light, and they have to: `#F6B82E` is
 * **1.78:1 against white**, so the brand gold as *text* on the light page is
 * not merely low-contrast, it is barely present — half the wordmark would fade
 * out. `goldInk` (`#8F5F00`, 5.52:1 on white) is the same hue read as ink.
 *
 * This is not a recolour of the brand. §1.4 splits `gold` (fills, the mark, the
 * brand object) from `goldInk` (gold *as text on a surface*) precisely so that
 * the identity survives a white background; the wordmark is text, so it takes
 * the ink form. The web has the same defect today and has not fixed it — the
 * split is written up as platform ask §8 item 3.
 *
 * ## "Better" is the ON-BACKGROUND ink and nothing else (owner order 2026-09-02)
 *
 * > *"the logo on the login screen the 'Better' should not be same but opposite
 * > colour of the dark or white mode. like dark mode is black background and
 * > white font not other way around for the logo."*
 *
 * [at.bettertrack.app.ui.theme.BtColors.textPrimary] IS that ink:
 * `materialSchemeFrom` maps it onto `onBackground`/`onSurface`, so it is
 * near-white on both dark tables (including "Reines Schwarz") and near-black on
 * the light one, and it follows the **app's** theme preference — which is a user
 * choice that may deliberately disagree with the system's. Never a per-theme hex,
 * never `Color.White`.
 *
 * `WordmarkInkTest` pins that, and pins the ratios against the page: 17.81:1
 * light, 17.96:1 dark, 19.38:1 true black.
 */
@Composable
fun Wordmark(
    fontSize: TextUnit = 22.sp,
    modifier: Modifier = Modifier,
    edition: String? = null,
) {
    val bt = BtTheme.colors
    // Remembered because the wordmark now sits in the app's collapsing header
    // (owner report 2026-08-06), which recomposes as the bar collapses — and
    // rebuilding an AnnotatedString means re-running the builder and allocating
    // a fresh span list every time. Nothing in it varies per frame.
    val text = remember(bt, fontSize, edition) {
        buildAnnotatedString {
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
        }
    }
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Default,
        letterSpacing = (-0.025).em,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/**
 * The compact **B/T monogram** — the wordmark's rule applied to the glyph.
 *
 * The artwork (`@drawable/splash_bt_glyph`) is a WHITE "B" beside a gold "T",
 * drawn for the dark splash canvas, and `res/values/colors.xml` has carried the
 * consequence as a written-down defect since B2-B: *"On the light page colour the
 * B disappears entirely … following the configuration here would ship an
 * invisible logo. The honest fix is a light-ground variant of the artwork."*
 * The login screen drew that raster directly, so it shipped exactly that — the
 * owner's 2026-09-02 report, and the visible half of it.
 *
 * The fix is not a second baked variant (which would need a third for true black
 * and would still be a hex per theme). The same raster is split once, offline,
 * into two **alpha masks** that carry no colour of their own:
 *
 *  - `bt_brandmark_ink` — the FULL silhouette (B ∪ T), tinted
 *    [at.bettertrack.app.ui.theme.BtColors.textPrimary], i.e. the identical
 *    on-background ink [Wordmark] gives "Better";
 *  - `bt_brandmark_gold` — the "T" alone, tinted the constant brand
 *    [at.bettertrack.app.ui.theme.BtColors.gold] on top.
 *
 * Painting the union underneath and the T over it reproduces the letterforms
 * pixel-for-pixel, seam included: where the two glyphs meet, the T's antialiased
 * edge now blends into the ink instead of into a baked white. Geometry is
 * untouched — the masks keep the source's 864² framing (at half the resolution,
 * which is still 1.5× the largest size it is drawn at), so the mark occupies the
 * same box it always did.
 *
 * The SPLASH keeps the baked raster and its pinned dark canvas: that frame is
 * drawn by the window before Compose exists and cannot read a theme at all.
 */
@Composable
fun BtBrandmark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val bt = BtTheme.colors
    Box(modifier = modifier.size(size)) {
        Image(
            painter = painterResource(R.drawable.bt_brandmark_ink),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(bt.textPrimary),
        )
        Image(
            painter = painterResource(R.drawable.bt_brandmark_gold),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(bt.gold),
        )
    }
}
