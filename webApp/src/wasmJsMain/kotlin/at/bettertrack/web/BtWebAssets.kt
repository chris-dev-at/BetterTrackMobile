package at.bettertrack.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.painter.BitmapPainter
import at.bettertrack.web.resources.Res
import at.bettertrack.web.resources.roboto_variable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The two assets the browser build has to CARRY, because a Compose canvas has
 * no system anything (web port, Phase W1).
 *
 * Neither reaches the network. The typeface is a compose-resource served from
 * the bundle's own origin; the brand mark is inside the wasm binary itself. No
 * CDN, no Google Fonts URL, no third party — an authenticated dashboard must
 * not leak "this user opened BetterTrack" to a font host.
 *
 * ## Two facts about compose-resources on this stack, both measured in W1
 *
 * **1. Its readers only work from INSIDE composition.** The obvious shape —
 * read the bytes in `main()`, install them, then start Compose — fails in the
 * worst possible way: `Res.readBytes(...)` called from a plain
 * `CoroutineScope(Dispatchers.Main)` coroutine **suspends and never resumes**,
 * without ever issuing the HTTP request. Proven with stage markers written into
 * the page's bootstrap element: execution reached the coroutine and resumed
 * across a `delay(1)` (so the dispatcher is alive), then stopped dead at the
 * first `Res.readBytes`, and the dev server logged requests for `index.html`,
 * `webApp.js` and both `.wasm` files — none for `composeResources/…`. The
 * composable readers, which go through the composition-provided
 * `LocalResourceReader`, do work: [rememberBtFontFamily] is one.
 *
 * **2. Its DRAWABLE path does not render at all.**
 * `painterResource(Res.drawable.…)` fetches the PNG (the dev server logs the
 * 200) and hands back a painter that draws nothing — proven by putting a
 * `goldWash` background behind the `Image`: the square painted, the mark did
 * not. Hence [rememberBtBrandGlyph] decodes bytes that are already in the
 * binary instead.
 *
 * Both matter beyond this file: W2 moves 2984 string keys onto this library.
 * Recorded as R20/R21 in docs/KMP_PLAN.md §14.
 */

// ── The typeface ────────────────────────────────────────────────────────────

/**
 * Roboto, as one variable font.
 *
 * ## Why Roboto
 *
 * The design system's typography rule (`BtTypography`) is *"system font only
 * (Roboto / device default)"*. On Android that resolves to Roboto; the whole
 * ramp — the 44sp hero number, the negative tracking, the `tnum` money styles —
 * was drawn against it. A browser has no system font to resolve, so shipping
 * Roboto is what makes the web render the ramp the design was measured on
 * rather than whatever face the Skia build happens to fall back to.
 *
 * Source: Google Fonts `ofl/roboto/Roboto[wdth,wght].ttf`, SIL OFL 1.1
 * (`webApp/licenses/Roboto-OFL.txt`). It carries `tnum`, which every money
 * style in the app asks for and which is why a column of prices lines up.
 *
 * ## Why the VARIABLE font, and why one file is four faces
 *
 * The ramp uses four weights — Normal, Medium, SemiBold, Bold. Four static
 * Roboto TTFs are ~170 KB each; the variable font is **488,584 bytes for all of
 * them** (280,258 gzipped), and compose-resources hands each `Font(...)` its
 * own weight, which skiko applies to the `wght` axis. Synthetic bolding —
 * shipping one weight and letting Skia fake the rest — was not an option: the
 * app's hierarchy is built almost entirely out of weight (§3.4 "the brand look
 * comes from WEIGHT and tight LETTER-SPACING"), and a smeared 400 is not a 600.
 */
@Composable
fun rememberBtFontFamily(): FontFamily {
    val normal = Font(Res.font.roboto_variable, FontWeight.Normal, FontStyle.Normal)
    val medium = Font(Res.font.roboto_variable, FontWeight.Medium, FontStyle.Normal)
    val semiBold = Font(Res.font.roboto_variable, FontWeight.SemiBold, FontStyle.Normal)
    val bold = Font(Res.font.roboto_variable, FontWeight.Bold, FontStyle.Normal)
    return remember(normal, medium, semiBold, bold) {
        FontFamily(normal, medium, semiBold, bold)
    }
}

// ── The brand mark ──────────────────────────────────────────────────────────

/**
 * The 72dp BT glyph the login screen puts above the wordmark — byte-for-byte
 * the same `splash_bt_glyph.png` (864×864, 15,475 bytes) the Android app draws,
 * so the two builds cannot drift about the brand mark.
 *
 * Decoded from [BT_GLYPH_PNG_BASE64], i.e. from bytes that are already inside
 * the wasm binary, rather than read through compose-resources — that library's
 * drawable path does not render on this stack, and [BT_GLYPH_PNG_BASE64]'s KDoc
 * records the measurement. Nothing here touches the network, which is also the
 * strongest possible reading of the phase's "embedded, no external fetches".
 *
 * The same mark's dark-background sibling (`art/BT_AppIcon.png`) is the
 * favicon, inlined as a `data:` URI in `index.html` — see the note there.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun rememberBtBrandGlyph(): Painter = remember {
    BitmapPainter(Base64.decode(BT_GLYPH_PNG_BASE64).decodeToImageBitmap())
}
