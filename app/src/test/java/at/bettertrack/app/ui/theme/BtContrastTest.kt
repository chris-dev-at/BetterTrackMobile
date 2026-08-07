package at.bettertrack.app.ui.theme

import androidx.compose.ui.graphics.Color
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
 *  - The accent inks ([BtColors.goldInk], [BtColors.gain], [BtColors.loss])
 *    clear 4.5:1 on `bg` and `surface` — the two substrates they actually
 *    appear on as text.
 *  - [BtColors.gold] as a FILL clears 4.5:1 against [BtColors.onGold], which is
 *    the pairing that makes gold buttons legible.
 *
 * Alpha tokens (`border`, the washes) are deliberately out of scope: they are
 * not text and WCAG's ratio is undefined for a colour without a substrate.
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
    fun `gold ink clears AA on page and card in both tables`() {
        listOf("dark" to BtDarkColors, "light" to BtLightColors).forEach { (name, bt) ->
            assertAA("$name goldInk", bt.goldInk, "bg", bt.bg)
            assertAA("$name goldInk", bt.goldInk, "surface", bt.surface)
        }
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
            assertAA("$name goldEmphasis", bt.goldEmphasis, "surface", bt.surface)
        }
    }

    // ── Gold as a fill ──────────────────────────────────────────────────────

    @Test
    fun `ink on a gold fill clears AA in both tables`() {
        listOf("dark" to BtDarkColors, "light" to BtLightColors).forEach { (name, bt) ->
            assertAA("$name onGold", bt.onGold, "gold", bt.gold)
        }
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
                ratio >= 3.0,
            )
        }
    }

    // ── The skeleton pairing ────────────────────────────────────────────────

    @Test
    fun `the skeleton shimmer is lighter than its own base in both tables`() {
        // A sweep darker than the block it crosses reads as a smear, not as
        // light moving over a surface. Dark takes `surfaceLow → surfaceHighest`;
        // light has to invert to `surfaceHighest → surface`, because in light the
        // page sits in the MIDDLE of the compressed ramp. The gallery matrix
        // caught the naive port; this keeps it caught.
        listOf("dark" to BtDarkColors, "light" to BtLightColors).forEach { (name, bt) ->
            val base = if (bt.isLight) bt.surfaceHighest else bt.surfaceLow
            val highlight = if (bt.isLight) bt.surface else bt.surfaceHighest
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
    }
}
