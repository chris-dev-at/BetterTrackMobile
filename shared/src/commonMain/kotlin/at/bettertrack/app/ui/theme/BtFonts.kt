package at.bettertrack.app.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * The typeface the whole type ramp is drawn with — and the one thing about the
 * ramp that a browser cannot inherit (web port, Phase W1).
 *
 * ## Why this is not simply `FontFamily.Default`
 *
 * [BtTypography]'s KDoc states the design rule in one line: *"system font only
 * (Roboto / device default); the brand look comes from WEIGHT and tight
 * LETTER-SPACING, not a typeface."* On Android and on iOS that rule is free —
 * `FontFamily.Default` resolves to Roboto and to SF, both of which the OS
 * already has, and both of which carry the `tnum` feature the money styles ask
 * for. Compose Multiplatform on the web has no such thing to resolve against:
 * the page is one Skia canvas, so the only faces that exist are the ones the
 * bundle shipped. A canvas with no embedded font falls back to whatever the
 * Skia build happens to carry, which is neither Roboto nor a guarantee that
 * `tnum` exists — and `tnum` is not cosmetic here, it is what keeps a column of
 * money aligned.
 *
 * So the family is a **seam with an Android/iOS no-op**: on those two platforms
 * nothing ever calls [install] and this reads exactly the `FontFamily.Default`
 * the sources had before the move, which is why the Android render is unchanged
 * by construction. The browser host installs the embedded family before it
 * shows a frame.
 *
 * ## Why a settable holder rather than an `expect`/`actual`
 *
 * An `actual val` has to produce a family at class-initialisation time, and on
 * the web the font bytes arrive from a `suspend` read of the bundle's own
 * resources — there is no synchronous point early enough. Every alternative
 * (a `@Composable` family, a family threaded through `BetterTrackTheme`) would
 * have changed the shape of [BtTypography], which is a data class with 6
 * `TextStyle` defaults that 100+ call sites read; this changes one line of it.
 *
 * The cost is a startup ordering rule, and the honest thing is to name it:
 * **[install] must run before the first composition** or the web falls back to
 * Skia's default face. It is read through `by lazy` in [BtTypography], so the
 * first read pins it and it cannot flip mid-session; a host that forgets the
 * call gets the wrong face, not a crash. `:webApp`'s `main()` does it in the
 * same startup block as `btInstallTimeZoneDatabase()`, for the same reason
 * (docs/KMP_PLAN.md §14.4 R19).
 */
object BtFonts {

    /**
     * The family every [BtTypography] style and the wordmark are drawn in.
     * `FontFamily.Default` — i.e. the platform's own UI face — until a host
     * replaces it.
     */
    var appFontFamily: FontFamily = FontFamily.Default
        private set

    /**
     * Install the embedded family. Called by the browser host before the first
     * frame; never called on Android or iOS, which have a system font.
     */
    fun install(family: FontFamily) {
        appFontFamily = family
    }
}
