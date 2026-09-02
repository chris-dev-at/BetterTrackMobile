package at.bettertrack.app.ui.components

import androidx.compose.ui.graphics.Color
import at.bettertrack.app.ui.theme.BtColors
import at.bettertrack.app.ui.theme.BtDarkColors
import at.bettertrack.app.ui.theme.BtLightColors
import at.bettertrack.app.ui.theme.asTrueBlack
import at.bettertrack.app.ui.theme.materialSchemeFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The brand mark reads on every palette the app can be in.
 *
 * ## The order this file exists for
 *
 * Owner, 2026-09-02, verbatim:
 *
 * > *"the logo on the login screen the 'better' should not be same but opposite
 * > colour of the dark or white mode. like dark mode is black background and
 * > white font not other way around for the logo."*
 *
 * He was looking at the login screen with the app in DARK and the phone in
 * light: `Wordmark` drew "Better" in the dark table's near-white
 * [BtColors.textPrimary] — correct — on a **white page**, because the screen
 * painted no background of its own and what showed through was
 * `android:windowBackground`, a resource the SYSTEM's day/night configuration
 * picks. Two theme sources, one screen. The monogram above it had the same
 * failure by a different route: it was the splash raster, whose "B" is baked
 * `#FFFFFF` and cannot follow anything.
 *
 * So "opposite colour of the dark or white mode" decomposes into three
 * properties, and this file checks each of them mechanically:
 *
 *  1. **The ink is derived, never picked.** "Better" and the monogram's non-gold
 *     half both read [BtColors.textPrimary], which `materialSchemeFrom` maps
 *     onto M3's `onBackground`/`onSurface`. One token, every palette.
 *  2. **It lands on the opposite side of its own page.** Lighter than the ground
 *     in both dark tables, darker than it in light — the owner's sentence, as
 *     arithmetic.
 *  3. **The page under it is the app's.** Every root that can be on screen
 *     outside the shell paints [BtColors.bg] itself, so the ink and the ground
 *     can never come from two different theme sources again.
 *
 * "Track" is out of scope on purpose: it is the constant brand gold in both
 * tables by the owner order of 2026-08-07, WCAG exempts logotypes, and
 * `BtContrastTest` pins that decision as a value rather than a floor.
 */
class WordmarkInkTest {

    // ── WCAG 2.x, transcribed rather than imported ──────────────────────────
    // (A test that reads its subject's own arithmetic proves nothing.)

    private fun relativeLuminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return channel(c.red) * 0.2126 + channel(c.green) * 0.7152 + channel(c.blue) * 0.0722
    }

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val f = relativeLuminance(fg)
        val b = relativeLuminance(bg)
        return (maxOf(f, b) + 0.05) / (minOf(f, b) + 0.05)
    }

    /** Every palette the app can actually render in — the theme switch's full range. */
    private val palettes = listOf(
        "light" to BtLightColors,
        "dark" to BtDarkColors,
        "dark + true black (Reines Schwarz)" to BtDarkColors.asTrueBlack(),
    )

    // ── 1. The ink is the on-background token ───────────────────────────────

    @Test
    fun `the wordmark ink is M3's on-background role in every palette`() {
        palettes.forEach { (name, bt) ->
            val scheme = materialSchemeFrom(bt, dark = !bt.isLight)
            assertEquals(
                "$name: textPrimary must BE onBackground — the wordmark reads the token, " +
                    "so the token has to be the ink M3 would put on this page",
                bt.textPrimary,
                scheme.onBackground,
            )
            assertEquals(
                "$name: textPrimary must BE onSurface too — the wordmark also sits in the " +
                    "collapsing header and on the About card",
                bt.textPrimary,
                scheme.onSurface,
            )
        }
    }

    // ── 2. Opposite side of the page, with room to spare ────────────────────

    @Test
    fun `Better is the opposite polarity of its own page in every palette`() {
        palettes.forEach { (name, bt) ->
            val ink = relativeLuminance(bt.textPrimary)
            val page = relativeLuminance(bt.bg)
            if (bt.isLight) {
                assertTrue(
                    "$name: the page is light, so the wordmark ink must be DARKER than it " +
                        "(ink %.4f vs page %.4f)".format(ink, page),
                    ink < page,
                )
            } else {
                assertTrue(
                    "$name: the page is dark, so the wordmark ink must be LIGHTER than it " +
                        "(ink %.4f vs page %.4f)".format(ink, page),
                    ink > page,
                )
            }
        }
    }

    @Test
    fun `Better clears AA against the page in every palette`() {
        // Measured 2026-09-02: light 17.81:1, dark 17.96:1, true black 19.38:1 —
        // four times the 4.5:1 body floor and six times the 3:1 large-text one
        // the wordmark would actually be judged by at 40sp. The floor asserted is
        // the strict one on purpose: this ink has no reason to ever be near it,
        // so a value that lands between 3 and 4.5 is a regression, not a choice.
        palettes.forEach { (name, bt) ->
            val ratio = contrastRatio(bt.textPrimary, bt.bg)
            assertTrue(
                "$name: \"Better\" is %.2f:1 on the page — below the %.1f:1 AA floor"
                    .format(ratio, AA),
                ratio >= AA,
            )
            assertTrue(
                "$name: \"Better\" is %.2f:1 on the page — below the %.1f:1 large-text floor"
                    .format(ratio, LARGE_TEXT),
                ratio >= LARGE_TEXT,
            )
        }
    }

    @Test
    fun `Better clears AA on every opaque surface the mark is drawn on`() {
        // The wordmark is not only a login-screen object: it leads the collapsing
        // header on every tab (over `bg`), and it is the About screen's masthead.
        // Whatever a future screen puts behind it, it is one of these six.
        palettes.forEach { (name, bt) ->
            bt.opaqueSurfaces().forEach { (sName, s) ->
                val ratio = contrastRatio(bt.textPrimary, s)
                assertTrue(
                    "$name: \"Better\" is %.2f:1 on $sName — below the %.1f:1 AA floor"
                        .format(ratio, AA),
                    ratio >= AA,
                )
            }
        }
    }

    @Test
    fun `the App edition suffix stays legible on every palette too`() {
        // It keeps its muted tone; this is the check that it is allowed to.
        // (light 6.31:1, dark 6.33:1, true black 6.83:1 — all comfortably AA, so
        // there is no case for promoting it to on-surface-variant.)
        palettes.forEach { (name, bt) ->
            val ratio = contrastRatio(bt.textMuted, bt.bg)
            assertTrue(
                ("$name: the \"App\" suffix is %.2f:1 on the page — below the %.1f:1 AA " +
                    "floor; promote it to textSecondary rather than leaving it there")
                    .format(ratio, AA),
                ratio >= AA,
            )
        }
    }

    private fun BtColors.opaqueSurfaces(): List<Pair<String, Color>> = listOf(
        "bg" to bg,
        "surfaceLow" to surfaceLow,
        "surfaceQuiet" to surfaceQuiet,
        "surface" to surface,
        "surfaceHigh" to surfaceHigh,
        "surfaceHighest" to surfaceHighest,
    )

    // ── 3. The construction, pinned in the source ───────────────────────────

    private fun uiFile(relative: String): File {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
        return File(root, relative).also {
            assertTrue("expected $relative under ${root.absolutePath}", it.isFile)
        }
    }

    private fun uiSources(): List<Pair<String, File>> {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        val root = roots.first { it.isDirectory }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it }
            .toList()
    }

    @Test
    fun `the wordmark paints Better with the theme ink and Track with the brand gold`() {
        val src = uiFile("components/Wordmark.kt").readText()
        assertTrue(
            "Wordmark must colour \"Better\" with bt.textPrimary — the on-background ink, " +
                "not a per-theme value",
            src.contains("""SpanStyle(color = bt.textPrimary)) { append("Better")"""),
        )
        assertTrue(
            "Wordmark must colour \"Track\" with bt.gold — the constant brand value " +
                "(owner order 2026-08-07)",
            src.contains("""SpanStyle(color = bt.gold)) { append("Track")"""),
        )
    }

    @Test
    fun `the monogram tints two masks rather than drawing baked colour`() {
        val src = uiFile("components/Wordmark.kt").readText()
        assertTrue(
            "BtBrandmark must tint the silhouette mask with bt.textPrimary",
            src.contains("R.drawable.bt_brandmark_ink") &&
                src.contains("ColorFilter.tint(bt.textPrimary)"),
        )
        assertTrue(
            "BtBrandmark must tint the T mask with bt.gold",
            src.contains("R.drawable.bt_brandmark_gold") &&
                src.contains("ColorFilter.tint(bt.gold)"),
        )
    }

    @Test
    fun `no in-app screen draws the baked splash raster`() {
        // `splash_bt_glyph` has a WHITE "B" baked into it. It is correct for the
        // window's splash frame — drawn before Compose exists, over the pinned
        // dark `bt_splash_bg` — and wrong for everything that can be themed. The
        // login screen drew it, which is half of the 2026-09-02 defect.
        // The Kotlin reference form, not the bare name: [BtBrandmark]'s KDoc
        // names the raster it replaces, and prose must never trip a rule.
        val offenders = uiSources()
            .filter { (_, f) -> f.readText().contains("R.drawable.splash_bt_glyph") }
            .map { it.first }
        assertTrue(
            "these screens draw the baked splash artwork; use BtBrandmark, which tints " +
                "the same shapes from the theme: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every root outside the shell paints the app's own page`() {
        // The other half of the defect: a screen that paints nothing shows
        // `android:windowBackground`, which follows the SYSTEM configuration
        // while its content follows the USER's in-app choice. On a light phone
        // with the app forced dark that is a white page under near-white ink.
        //
        // These four are the whole set of composables that can own the window
        // without the shell's Scaffold under them (BtRoot's own two backstops
        // paint `bg` inline; the wizard's every step goes through WizardScaffold).
        listOf(
            "auth/LoginScreen.kt",
            "auth/PasswordChangeRequiredScreen.kt",
            "applock/AppLockScreen.kt",
            "storage/VaultUnlockGate.kt",
        ).forEach { rel ->
            assertTrue(
                "$rel can be the whole window, so it must paint bt.bg itself — otherwise " +
                    "the system's windowBackground shows through under the app's own inks",
                uiFile(rel).readText().contains(".background(bt.bg)"),
            )
        }
    }

    private companion object {
        const val AA = 4.5
        /** WCAG 1.4.3's floor for large text — what a 40sp wordmark is judged by. */
        const val LARGE_TEXT = 3.0
    }
}
