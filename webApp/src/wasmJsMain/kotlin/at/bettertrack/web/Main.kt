package at.bettertrack.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import at.bettertrack.app.domain.btInstallTimeZoneDatabase

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
 * selection, browser find — see §14 W1).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // FIRST, before any domain call: kotlinx-datetime on Wasm is js-joda, which
    // has no IANA zone rules of its own. Without this, `Tax.viennaYearOf` takes
    // the whole module down with a null-pointer RuntimeError rather than
    // throwing. See :shared JsJodaTimeZoneDatabase.wasmJs.kt.
    btInstallTimeZoneDatabase()

    ComposeViewport {
        BetterTrackWebApp()
    }
}
