package at.bettertrack.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast floors for both colour tables — a direct port of the platform's
 * own `apps/web/src/styles/origin.test.ts`, run against [BtDarkColors] and
 * [BtLightColors] instead of against `origin.css`.
 *
 * ## Why port it rather than trust the table
 *
 * The §1.4 values were derived from the web's light token block, and the web
 * measured them against the web's surface set. This app's dark ramp has five
 * steps where the web has fewer and a *lighter* top step, so a token that
 * cleared AA on the web can fail here — and one did: dark `textFaint` at the
 * spec's `#828C96` lands at 4.23:1 on `surfaceHighest`. That is precisely the
 * class of defect a table review cannot catch and a two-line test catches on
 * every build, for free, forever.
 *
 * ## What it checks
 *
 *  - Informational text ([BtColors.textMuted], [BtColors.textFaint]) clears
 *    **4.5:1 on all five opaque surfaces**, in both tables and under true black.
 *    These are the quietest text tokens in the app; if they pass, everything
 *    above them passes.
 *  - The accent inks ([BtColors.gain], [BtColors.loss]) clear 4.5:1 on `bg` and
 *    `surface` — the two substrates they actually appear on as text.
 *  - [BtColors.gold] as a FILL clears 4.5:1 against [BtColors.onGold], which is
 *    the pairing that makes gold buttons legible.
 *
 * Alpha tokens (`border`, the washes) are deliberately out of scope: they are
 * not text and WCAG's ratio is undefined for a colour without a substrate.
 *
 * ## What it deliberately does NOT check
 *
 * **Gold in the light table has no contrast floor.** That is an owner decision
 * of 2026-08-07, not an omission, and the tests that used to enforce a floor
 * there now pin the chosen VALUES instead so the decision still fails the build
 * when it drifts. The full reasoning sits above those tests — read it before
 * "fixing" a gold contrast number.
 */
class BtContrastTest {

    // ── The WCAG math, ported verbatim from origin.test.ts ───────────────────

    /** sRGB relative luminance (WCAG 2.x). */
    private fun relativeLuminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return channel(c.red) * 0.2126 + channel(c.green) * 0.7152 + channel(c.blue) * 0.0722
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val f = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (maxOf(f, b) + 0.05) / (minOf(f, b) + 0.05)
    }

    private fun BtColors.opaqueSurfaces(): List<Pair<String, Color>> = listOf(
        "bg" to bg,
        "surfaceLow" to surfaceLow,
        "surface" to surface,
        "surfaceHigh" to surfaceHigh,
        "surfaceHighest" to surfaceHighest,
    )

    private val tables = listOf(
        "dark" to BtDarkColors,
        "light" to BtLightColors,
        "dark+trueBlack" to BtDarkColors.asTrueBlack(),
    )

    private fun assertAA(label: String, fg: Color, bgName: String, bgColor: Color) {
        val ratio = contrastRatio(fg, bgColor)
        assertTrue(
            "$label on $bgName is %.2f:1, below the 4.5:1 AA floor".format(ratio),
            ratio >= AA,
        )
    }

    // ── Informational text on every opaque surface ───────────────────────────

    @Test
    fun `textMuted keeps AA on every opaque surface in both tables`() {
        tables.forEach { (name, bt) ->
            bt.opaqueSurfaces().forEach { (sName, s) ->
                assertAA("$name textMuted", bt.textMuted, sName, s)
            }
        }
    }

    @Test
    fun `textFaint keeps AA on every opaque surface in both tables`() {
        tables.forEach { (name, bt) ->
            bt.opaqueSurfaces().forEach { (sName, s) ->
                assertAA("$name textFaint", bt.textFaint, sName, s)
            }
        }
    }

    @Test
    fun `the louder text tokens clear the quiet ones by construction`() {
        // textPrimary > textSecondary > textMuted > textFaint, measured on the
        // page. A ramp that crosses over is a ramp that stopped ranking anything.
        tables.forEach { (name, bt) ->
            val order = listOf(
                "textPrimary" to bt.textPrimary,
                "textSecondary" to bt.textSecondary,
                "textMuted" to bt.textMuted,
                "textFaint" to bt.textFaint,
            ).map { (n, c) -> n to contrastRatio(c, bt.bg) }
            order.zipWithNext().forEach { (louder, quieter) ->
                assertTrue(
                    "$name: ${louder.first} (%.2f) must out-contrast ${quieter.first} (%.2f)"
                        .format(louder.second, quieter.second),
                    louder.second > quieter.second,
                )
            }
        }
    }

    // ── Accent inks where they are actually used as text ─────────────────────

    @Test
    fun `gold ink clears AA on page and card in dark`() {
        // Light is exempt by owner order — see `the light gold is the owner's
        // chosen value`, which pins the value instead of a floor. Dark is not
        // exempt and never needed to be: gold on near-black passes comfortably.
        assertAA("dark goldInk", BtDarkColors.goldInk, "bg", BtDarkColors.bg)
        assertAA("dark goldInk", BtDarkColors.goldInk, "surface", BtDarkColors.surface)
    }

    @Test
    fun `gain and loss clear AA on page and card in both tables`() {
        listOf("dark" to BtDarkColors, "light" to BtLightColors).forEach { (name, bt) ->
            assertAA("$name gain", bt.gain, "bg", bt.bg)
            assertAA("$name gain", bt.gain, "surface", bt.surface)
            assertAA("$name loss", bt.loss, "bg", bt.bg)
            assertAA("$name loss", bt.loss, "surface", bt.surface)
        }
    }

    @Test
    fun `the soft gain and loss inks are legible on the badge fills they sit in`() {
        // BtBadge is `wash fill + soft ink`. "Soft" means lighter, and lighter
        // fails on white — which is why the light table sets gainSoft == gain.
        // This is the test that would have caught skipping that.
        listOf("dark" to BtDarkColors, "light" to BtLightColors).forEach { (name, bt) ->
            assertAA("$name gainSoft", bt.gainSoft, "surface", bt.surface)
            assertAA("$name lossSoft", bt.lossSoft, "surface", bt.surface)
        }
        // `goldEmphasis` collapses onto `goldInk`, so in light it inherits that
        // token's owner-ordered exemption and only dark is checked here.
        assertAA("dark goldEmphasis", BtDarkColors.goldEmphasis, "surface", BtDarkColors.surface)
    }

    // ── Gold as a fill ──────────────────────────────────────────────────────

    @Test
    fun `ink on a gold fill clears AA in both tables`() {
        listOf("dark" to BtDarkColors, "light" to BtLightColors).forEach { (name, bt) ->
            assertAA("$name onGold", bt.onGold, "gold", bt.gold)
        }
    }

    // ── Gold as a graphical mark: the owner-ordered exemption ────────────────

    /*
     * OWNER ORDER, 2026-08-07 — Christian, final word after two rejected
     * compromises:
     *
     *   "still the graphs are this muddy gold rusty color, not a nice yellow.
     *    And the highlights not either. Go make it ALL nice bright yellow, not
     *    rusty gold."
     *
     * Everything below replaces a CONTRAST FLOOR with a PINNED VALUE, and that
     * substitution is the whole design of these tests now. The reasoning:
     *
     *  - Yellow is the hue WCAG punishes hardest. On white, the brand `#F6B82E`
     *    is 1.78:1; the lightest point on its own RGB ray clearing even the 3:1
     *    graphical floor is `#A77D1F`, which the owner sees — correctly — as
     *    rust. There is no value that is both compliant and recognisably the
     *    logo, so the floor and the brand are irreconcilable and the owner chose
     *    the brand. A test cannot re-litigate that.
     *  - But a decision that is merely *absent* from the tests is a decision
     *    that drifts back in six months. So each exempted token asserts its
     *    EXACT VALUE: the build still fails on any change, it just fails for
     *    "you changed what the owner picked" instead of "you missed a floor".
     *  - The readability the floors bought is bought back by GEOMETRY instead —
     *    `chartLineWidth` (3dp light / 2dp dark), a heavier `chartAreaTopAlpha`,
     *    and the 2× alpha gain in `edge()`. Those are asserted below too,
     *    because they are now the only thing holding the light charts up.
     */

    @Test
    fun `the light gold is the owner's chosen value, not a derived floor`() {
        // Pinned, not derived. If you are changing this line, you are changing
        // the owner's decision — get his word, not a contrast calculator's.
        assertEquals(Color(0xFFD49E28), BtLightColors.goldInk)
        assertEquals(BtGold_ForTest, BtLightColors.gold)

        // The ONE property that is still derived rather than dictated, and the
        // reason two previous inks were rejected: it must sit on the brand
        // gold's RGB ray. `#8F5F00` and `#D99A00` both zero the blue channel,
        // and a yellow with no blue in it at that lightness is what the eye
        // calls rust — hue alone does not catch it (`#8F5F00` is within 1.5° of
        // the brand gold), so the invariant is the CHANNEL RATIO.
        val deviation = rayDeviation(BtLightColors.goldInk, BtLightColors.gold)
        assertTrue(
            "light goldInk is %.3f off the brand gold's RGB ray — darken along the ray, never re-pick the hue"
                .format(deviation),
            deviation <= RAY_TOLERANCE,
        )

        // And it must stay BRIGHT. This is the guard that actually protects the
        // owner's intent: the failure mode here has always been someone nudging
        // the ink darker one defensible step at a time until it is rust again.
        // `#D99A00` — the ceiling the owner named himself — is 2.45:1 on white.
        val onWhite = contrastRatio(BtLightColors.goldInk, BtLightColors.bg)
        assertTrue(
            "light goldInk is %.2f:1 on white — darker than the #D99A00-class ceiling the owner set"
                .format(onWhite),
            onWhite <= OWNER_INK_CEILING,
        )
    }

    @Test
    fun `every gold graphical mark is the brand value in both tables`() {
        // The retired `goldGraphic` (`#A77D1F`) used to sit here holding chart
        // lines, selection rings and gold hairlines to 3:1. It is gone: a
        // graphical gold mark is now `gold` itself in BOTH tables, which is what
        // "make it ALL nice bright yellow" means mechanically.
        assertEquals("light gold must equal dark gold", BtDarkColors.gold, BtLightColors.gold)
        // The hairline helper keeps the hue in light instead of swapping it.
        assertEquals(
            "edge() must no longer darken gold — it gains alpha instead",
            BtLightColors.gold.red,
            BtLightColors.edge(BtLightColors.gold, 0.3f).red,
        )
        // The named wash/edge tokens are all struck from the brand value.
        listOf(
            "goldWash" to BtLightColors.goldWash,
            "goldWashStrong" to BtLightColors.goldWashStrong,
            "goldEdge" to BtLightColors.goldEdge,
        ).forEach { (label, c) ->
            assertEquals(
                "light $label must carry the brand hue",
                Triple(BtLightColors.gold.red, BtLightColors.gold.green, BtLightColors.gold.blue),
                Triple(c.red, c.green, c.blue),
            )
        }
    }

    @Test
    fun `the geometry compensations that replace the contrast floors are present`() {
        // These are load-bearing: they are the ONLY thing making a 1.78:1 curve
        // read on white now that the darkening is gone. Weakening either without
        // a matching owner decision re-opens the defect the floors used to cover.
        assertTrue(
            "light chart lines must be drawn heavier than dark's to read on white",
            BtLightColors.chartLineWidth > BtDarkColors.chartLineWidth,
        )
        assertEquals(3.dp, BtLightColors.chartLineWidth)
        assertEquals(2.dp, BtDarkColors.chartLineWidth)
        assertTrue(
            "the light area gradient must carry more mass than it did at 0.18",
            BtLightColors.chartAreaTopAlpha >= 0.26f,
        )
        // `edge()` doubles gold's alpha in light, which reproduces the retired
        // `goldGraphic` hairline's luminance exactly (0.690 vs 0.694 over white).
        // Dark is untouched. The delta clears one 8-bit quantization step
        // (1/255 = 0.0039), which is how Compose stores an sRGB alpha channel.
        assertEquals(0.60f, BtLightColors.edge(BtLightColors.gold, 0.30f).alpha, ALPHA_STEP)
        assertEquals(0.30f, BtDarkColors.edge(BtDarkColors.gold, 0.30f).alpha, ALPHA_STEP)
    }

    /**
     * How far [c] sits off the RGB ray through [reference], as the largest
     * absolute difference between their red-normalised channel ratios. Zero
     * means "the same colour, scaled" — i.e. a pure darkening.
     */
    private fun rayDeviation(c: Color, reference: Color): Double {
        fun ratios(x: Color): Triple<Double, Double, Double> {
            val m = maxOf(x.red, x.green, x.blue).toDouble()
            return Triple(x.red / m, x.green / m, x.blue / m)
        }
        val (ar, ag, ab) = ratios(c)
        val (br, bg, bb) = ratios(reference)
        return maxOf(Math.abs(ar - br), Math.abs(ag - bg), Math.abs(ab - bb))
    }

    @Test
    fun `gold is a constant across the tables and can never be text on white`() {
        assertEquals(BtDarkColors.gold, BtLightColors.gold)
        // The defect this whole split exists to prevent: gold at #F6B82E is
        // 1.78:1 on white. If someone ever "simplifies" goldInk back to gold in
        // the light table, this fails immediately.
        val goldOnWhite = contrastRatio(BtLightColors.gold, Color(0xFFFFFFFF))
        assertTrue(
            "gold is %.2f:1 on white — it is a FILL, never text; use goldInk".format(goldOnWhite),
            goldOnWhite < AA,
        )
        assertTrue(BtLightColors.goldInk != BtLightColors.gold)
    }

    // ── Chart axis labels ───────────────────────────────────────────────────

    @Test
    fun `chart axis labels clear the graphical-object floor on the page`() {
        // Axis labels are small text over the plot area, which is `bg` (charts
        // are drawn edge to edge inside their card's padding). 3:1 is the
        // non-text/large-text floor; these clear it comfortably in both tables.
        listOf("dark" to BtDarkColors, "light" to BtLightColors).forEach { (name, bt) ->
            val ratio = contrastRatio(bt.chartAxis, bt.bg)
            assertTrue(
                "$name chartAxis on bg is %.2f:1, below 3:1".format(ratio),
                ratio >= GRAPHICAL,
            )
        }
    }

    // ── The skeleton pairing ────────────────────────────────────────────────

    @Test
    fun `the skeleton shimmer is lighter than its own base in both tables`() {
        // A sweep darker than the block it crosses reads as a smear, not as
        // light moving over a surface. Both modes take the SAME two ramp ends
        // and light takes them in the opposite order, because since the
        // white-page flip light's ramp runs the opposite way: dark's bright end
        // is `surfaceHighest`, light's is `surfaceLow`. The gallery matrix
        // caught the naive port; this keeps it caught.
        listOf("dark" to BtDarkColors, "light" to BtLightColors).forEach { (name, bt) ->
            val base = if (bt.isLight) bt.surfaceHighest else bt.surfaceLow
            val highlight = if (bt.isLight) bt.surfaceLow else bt.surfaceHighest
            assertTrue(
                "$name skeleton highlight must be lighter than its base",
                relativeLuminance(highlight) > relativeLuminance(base),
            )
            assertTrue(
                "$name skeleton base must be distinguishable from the page",
                Math.abs(relativeLuminance(base) - relativeLuminance(bt.bg)) > 0.0,
            )
            assertTrue(
                "$name skeleton base must be distinguishable from a card",
                Math.abs(relativeLuminance(base) - relativeLuminance(bt.surface)) > 0.0,
            )
        }
    }

    private companion object {
        const val AA = 4.5
        /** WCAG 1.4.11: the floor for a graphical object, as opposed to text. */
        const val GRAPHICAL = 3.0
        /** Max red-normalised channel drift still counted as "the brand gold, darkened". */
        const val RAY_TOLERANCE = 0.02
        /**
         * The owner's own darkness ceiling for gold-as-text, as a contrast ratio
         * on white: `#D99A00` — the value he named — measures 2.45:1, and a
         * SMALLER ratio means a BRIGHTER ink. Slack of 0.05 so a rounding step
         * along the ray does not fail the build.
         */
        const val OWNER_INK_CEILING = 2.50
        /** The brand gold. Duplicated here on purpose: a test that reads the value it checks proves nothing. */
        val BtGold_ForTest = Color(0xFFF6B82E)
        /** One 8-bit alpha quantization step, with room to spare — Compose stores sRGB alpha in 8 bits. */
        const val ALPHA_STEP = 0.01f
    }
}
