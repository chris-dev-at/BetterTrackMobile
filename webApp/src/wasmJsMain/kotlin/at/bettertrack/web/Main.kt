package at.bettertrack.web

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import at.bettertrack.app.domain.btInstallTimeZoneDatabase
import at.bettertrack.app.ui.theme.BtFonts

/**
 * The browser entry point — the web counterpart of :iosApp's `UIApplicationMain`
 * AppDelegate and of :app's `MainActivity`.
 *
 * `ComposeViewport` with no container id attaches the Compose canvas to
 * `<body>`; it is still `@ExperimentalComposeUiApi` at CMP 1.10.3, which is the
 * hard ceiling on this Mac (docs/KMP_PLAN.md §6.3) and NOT a version this phase
 * moved. Compose renders through Skiko's WebGL canvas, so the entire page is one
 * canvas: nothing in the DOM is addressable, which is the fact every "share this
 * in a browser" expectation has to be measured against (a11y, SEO, text
 * selection, browser find — R14).
 *
 * ## Three things must be true before the app draws, and all three are here
 *
 * Each is a runtime cliff rather than a compile error if it is missed, which is
 * why they sit together in the one function a future host cannot avoid writing:
 *
 *  1. the **IANA zone database** must be installed, or `Tax.viennaYearOf` takes
 *     the whole wasm module down rather than throwing (W0's finding; R19);
 *  2. the **UI locale** must be seeded from the browser, because there is no
 *     `res/values-de` on a canvas (W1);
 *  3. the **typeface** must be installed into [BtFonts] before anything reads
 *     `BtTypography`, or the money styles lose `tnum` and the ramp is drawn in
 *     Skia's fallback face.
 *
 * (3) is why `BtFonts.install` runs inside a `remember` in the FIRST composition
 * pass rather than in a coroutine before it: the family comes from
 * compose-resources, whose readers are composition-scoped on this stack (see
 * [rememberBtFontFamily] for the measurement that settled it), and `remember`
 * runs before the children it wraps. `BtTypography`'s first read happens inside
 * `BetterTrackTheme`, one level below — so the ordering is structural, not a
 * race.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // FIRST, before any domain call: kotlinx-datetime on Wasm is js-joda, which
    // has no IANA zone rules of its own. Without this, `Tax.viennaYearOf` takes
    // the whole module down with a null-pointer RuntimeError rather than
    // throwing. See :shared JsJodaTimeZoneDatabase.wasmJs.kt.
    btInstallTimeZoneDatabase()

    // The one place the browser's language is read (W1). Everything downstream
    // takes it as a value, so nothing else in the app consults `navigator`.
    val locale = seedWebLocale()
    setDocumentLanguage(locale.tag)

    ComposeViewport {
        val fontFamily = rememberBtFontFamily()
        // Before the subtree below composes — see this file's KDoc, point 3.
        remember(fontFamily) { BtFonts.install(fontFamily) }
        BetterTrackWebApp(locale = locale, brandGlyph = rememberBtBrandGlyph())
    }
}

/**
 * Keep `<html lang>` honest. It is the one language signal a single-canvas
 * Compose page can still give a screen reader or a translation prompt (R14),
 * and it costs one line.
 */
private fun setDocumentLanguage(tag: String) {
    js("document.documentElement.lang = tag")
}
