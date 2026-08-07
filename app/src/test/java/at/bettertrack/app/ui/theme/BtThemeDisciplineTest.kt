package at.bettertrack.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the dual-theme system (B2 design spec §1.5 B1/B2, §7).
 *
 * ## Why this is a test and not a code review note
 *
 * The app now ships two colour tables, and the failure mode of a dual theme is
 * not a wrong token — it is a **colour that never asked the theme at all**. Two
 * shapes of that:
 *
 *  1. A hardcoded `Color(0x…)`. It renders identically in both modes, which is
 *     exactly wrong: a hue validated against a near-black card is not a hue that
 *     identifies anything on white.
 *  2. A brand hue alpha-composited at the call site — `bt.gold.copy(alpha = …)`.
 *     This is the subtler one and it was the single largest surface in the
 *     migration (75 sites across 34 files). Alpha silently assumes a substrate:
 *     14% gold reads as a warm tint on `#0A0D12` and as invisible cream on
 *     `#FFFFFF`, and the correction is not a constant — §1.4 moves gold washes
 *     *up* on white (14%→16%) and gain/loss washes *down* (14%→12%). No call
 *     site can be expected to know that, so no call site is allowed to try:
 *     `BtColors.wash` / `BtColors.edge` and the named `…Wash`/`…Edge` tokens are
 *     the only sanctioned forms.
 *
 * Both rules held at the moment they were written and both are the kind that
 * decay one defensible line at a time, which is why they are checked
 * mechanically — the same way [at.bettertrack.app.ui.shell.TopBarNavigationTest]
 * and [at.bettertrack.app.i18n.StringParityTest] check theirs, by reading the
 * sources.
 *
 * ## Scope
 *
 * `ui/theme/` is exempt by construction: it is where colour is *defined*. Every
 * other exemption is enumerated below with its reason, so adding one is a
 * visible edit rather than a quiet regex loosening.
 */
class BtThemeDisciplineTest {

    /**
     * The QR quiet zone. A QR code is not themed artwork — scanners require a
     * white quiet zone and dark modules, and inverting that in dark mode breaks
     * decoding on a meaningful share of readers. Called out explicitly in §1.5 B8.
     */
    private val literalExemptions = mapOf(
        "components/BtQrCode.kt" to "QR quiet zone must be white in both modes (§1.5 B8)",
    )

    /** Alpha-less named constants that carry no hue and therefore no substrate assumption. */
    private val hueFreeNames = setOf("Transparent", "Unspecified")

    private fun uiRoot(): File {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
    }

    /** Every `ui/` source outside `ui/theme/`, keyed by its path relative to `ui/`. */
    private fun sourcesOutsideTheme(): List<Pair<String, File>> {
        val root = uiRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it }
            .filterNot { (rel, _) -> rel.startsWith("theme" + File.separator) }
            .toList()
    }

    /** Strip `//` line comments and KDoc/block-comment bodies so prose never trips a rule. */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlock = false
        file.readLines().forEachIndexed { idx, raw ->
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) return@forEachIndexed
                line = line.substring(end + 2)
                inBlock = false
            }
            while (true) {
                val start = line.indexOf("/*")
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    line = line.substring(0, start)
                    inBlock = true
                    break
                }
                line = line.substring(0, start) + line.substring(end + 2)
            }
            val slash = line.indexOf("//")
            if (slash >= 0) line = line.substring(0, slash)
            if (line.isNotBlank()) out += (idx + 1) to line
        }
        return out
    }

    // ── B2: no hardcoded colour literals ────────────────────────────────────

    @Test
    fun `no hardcoded Color literal outside the theme package`() {
        val literal = Regex("""\bColor\(0[xX]""")
        val offenders = sourcesOutsideTheme().flatMap { (rel, file) ->
            if (rel in literalExemptions) emptyList()
            else codeLines(file).filter { (_, line) -> literal.containsMatchIn(line) }
                .map { (ln, line) -> "$rel:$ln  ${line.trim()}" }
        }
        assertTrue(
            "A colour literal cannot flip with the theme. Add the value to BtDarkColors/BtLightColors " +
                "and read it from BtTheme.colors instead:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no named opaque Color constant outside the theme package`() {
        // Color.White / Color.Black / Color.Red … are literals with better PR.
        val named = Regex("""\bColor\.([A-Z][A-Za-z]*)""")
        val offenders = sourcesOutsideTheme().flatMap { (rel, file) ->
            if (rel in literalExemptions) emptyList()
            else codeLines(file).flatMap { (ln, line) ->
                named.findAll(line)
                    .map { it.groupValues[1] }
                    .filterNot { it in hueFreeNames }
                    .map { "$rel:$ln  Color.$it" }
                    .toList()
            }
        }
        assertTrue(
            "Use a theme token, not a named Compose colour:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the QR exemption is still the only one and still needed`() {
        // An exemption that stops being used is an exemption that starts hiding
        // the next one. Assert both directions.
        assertEquals(setOf("components/BtQrCode.kt"), literalExemptions.keys)
        val qr = File(uiRoot(), "components/BtQrCode.kt")
        assertTrue("BtQrCode.kt has moved — re-point or retire the exemption", qr.isFile)
        assertTrue(
            "BtQrCode no longer paints a white quiet zone; drop the exemption",
            qr.readText().contains("Color.White"),
        )
    }

    // ── B1: no brand hue alpha-composited at a call site ────────────────────

    @Test
    fun `no brand token is alpha-composited outside the theme package`() {
        // Matches `bt.gold.copy(alpha`, `BtTheme.colors.gain.copy(alpha`,
        // `colors.lossSoft.copy(alpha`, and the bare-local form `gold.copy(alpha`.
        val brand = "gold|goldInk|goldEmphasis|goldSoft|goldWash|goldWashStrong|goldEdge|" +
            "onGold|gain|gainSoft|gainWash|loss|lossSoft|lossWash"
        val wash = Regex("""\b($brand)\s*\.\s*copy\s*\(\s*alpha""")
        val offenders = sourcesOutsideTheme().flatMap { (rel, file) ->
            codeLines(file).filter { (_, line) -> wash.containsMatchIn(line) }
                .map { (ln, line) -> "$rel:$ln  ${line.trim()}" }
        }
        assertTrue(
            "A brand wash must go through the theme, which knows the per-mode correction: " +
                "use the named token (goldWash / goldWashStrong / goldEdge / gainWash / lossWash) " +
                "or BtTheme.colors.wash(hue, alpha) / .edge(hue, alpha):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the guard would actually catch a violation`() {
        // A regex guard that silently stops matching is worse than no guard, so
        // prove it fires on each shape it claims to cover.
        val wash = Regex(
            """\b(gold|goldInk|goldEmphasis|goldSoft|goldWash|goldWashStrong|goldEdge|""" +
                """onGold|gain|gainSoft|gainWash|loss|lossSoft|lossWash)\s*\.\s*copy\s*\(\s*alpha""",
        )
        listOf(
            "color = bt.gold.copy(alpha = 0.14f),",
            "border = BorderStroke(1.dp, BtTheme.colors.loss.copy(alpha = 0.4f)),",
            "val c = colors.gainSoft.copy(alpha = 0.5f)",
            "val c = gold.copy( alpha = 0.5f )",
        ).forEach { assertTrue("guard missed: $it", wash.containsMatchIn(it)) }

        listOf(
            "color = bt.goldWash,",
            "color = bt.wash(bt.gold, 0.07f),",
            "color = bt.edge(accent, 0.55f),",
            "val c = someUnrelatedGoldfish.copy(x = 1)",
        ).forEach { assertTrue("guard false-positive: $it", !wash.containsMatchIn(it)) }
    }

    // ── The rollout gate ────────────────────────────────────────────────────

    @Test
    fun `light mode is public`() {
        // The tripwire, kept and inverted rather than deleted. It was `false`
        // through B2-A so that the flip could not drift in as a side effect of
        // another edit; B2-B made it deliberately, together with the component
        // sweep and the Settings → Appearance picker, and this line moved in the
        // same commit. It still earns its keep in the new position: it is what
        // fails if someone quietly turns the feature back off.
        assertEquals(true, BtThemeFeatures.LIGHT_MODE_PUBLIC)
    }

    @Test
    fun `while the gate is closed every mode resolves to dark`() {
        listOf(true, false).forEach { systemInDark ->
            at.bettertrack.app.data.prefs.BtThemeMode.entries.forEach { mode ->
                assertTrue(
                    "mode=$mode systemInDark=$systemInDark leaked light mode",
                    resolveDarkTheme(mode, systemInDark, lightAllowed = false),
                )
            }
        }
    }

    @Test
    fun `once the gate opens the choice is honoured and System follows the device`() {
        assertEquals(true, resolveDarkTheme(at.bettertrack.app.data.prefs.BtThemeMode.Dark, false, true))
        assertEquals(false, resolveDarkTheme(at.bettertrack.app.data.prefs.BtThemeMode.Light, true, true))
        assertEquals(true, resolveDarkTheme(at.bettertrack.app.data.prefs.BtThemeMode.System, true, true))
        assertEquals(false, resolveDarkTheme(at.bettertrack.app.data.prefs.BtThemeMode.System, false, true))
    }

    @Test
    fun `only the gallery may opt into light mode`() {
        // `allowLight = true` is the escape hatch that makes the 32-shot component
        // matrix possible before light is public. Exactly one call site may pass it.
        val offenders = sourcesOutsideTheme()
            .flatMap { (rel, file) ->
                codeLines(file).filter { (_, line) -> line.contains("allowLight") }
                    .map { (ln, _) -> "$rel:$ln" }
            }
            .filterNot { it.startsWith("gallery" + File.separator + "GalleryScreen.kt") }
        assertTrue(
            "allowLight = true is the debug gallery's escape hatch only:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ── The sanctioned wash/edge forms ──────────────────────────────────────

    @Test
    fun `dark washes are byte-identical to the raw alpha they replaced`() {
        // The migration's contract: rewriting 75 call sites must not have moved a
        // single dark pixel. In dark, wash() and edge() are the identity.
        listOf(0.06f, 0.07f, 0.11f, 0.14f, 0.22f, 0.4f, 0.55f, 0.6f).forEach { a ->
            assertEquals(BtDarkColors.gold.copy(alpha = a), BtDarkColors.wash(BtDarkColors.gold, a))
            assertEquals(BtDarkColors.gold.copy(alpha = a), BtDarkColors.edge(BtDarkColors.gold, a))
            assertEquals(BtDarkColors.loss.copy(alpha = a), BtDarkColors.wash(BtDarkColors.loss, a))
        }
    }

    @Test
    fun `the named wash tokens agree with the helper at their own strength`() {
        assertEquals(BtDarkColors.wash(BtDarkColors.gold, 0.14f), BtDarkColors.goldWash)
        assertEquals(BtDarkColors.wash(BtDarkColors.gold, 0.22f), BtDarkColors.goldWashStrong)
        assertEquals(BtDarkColors.edge(BtDarkColors.gold, 0.30f), BtDarkColors.goldEdge)
        assertEquals(BtDarkColors.wash(BtDarkColors.gain, 0.14f), BtDarkColors.gainWash)
        assertEquals(BtDarkColors.wash(BtDarkColors.loss, 0.14f), BtDarkColors.lossWash)
    }

    @Test
    fun `light corrects gold washes up and accent washes down`() {
        // §1.4: gold 14%→16%, 22%→26% (a pale tint needs more of itself on white);
        // gain/loss 14%→12% (the light inks are already dark and saturated).
        val goldUp = BtLightColors.wash(BtLightColors.gold, 0.14f).alpha
        assertTrue("gold wash must strengthen on white, got $goldUp", goldUp > 0.14f)
        val lossDown = BtLightColors.wash(BtLightColors.loss, 0.14f).alpha
        assertTrue("accent wash must weaken on white, got $lossDown", lossDown < 0.14f)
    }

    @Test
    fun `a light gold edge is drawn in the ink hue, not in gold`() {
        // A pale gold hairline on white is invisible; this is the one hue swap
        // edge() performs and the reason it exists as a separate helper.
        assertEquals(BtLightColors.goldInk.copy(alpha = 0.3f), BtLightColors.edge(BtLightColors.gold, 0.3f))
        // Non-gold hues pass through untouched — gain/loss inks are already dark.
        assertEquals(BtLightColors.loss.copy(alpha = 0.4f), BtLightColors.edge(BtLightColors.loss, 0.4f))
    }

    @Test
    fun `wash never produces an out-of-range alpha`() {
        listOf(0f, 0.5f, 0.9f, 1f).forEach { a ->
            listOf(BtLightColors, BtDarkColors).forEach { bt ->
                assertTrue(bt.wash(bt.gold, a).alpha in 0f..1f)
                assertTrue(bt.edge(bt.gold, a).alpha in 0f..1f)
            }
        }
    }

    // ── True black ──────────────────────────────────────────────────────────

    @Test
    fun `true black overrides exactly two neutrals and nothing else`() {
        // It is a sub-toggle, not a third table: anything else drifting would make
        // "AMOLED" a second dark theme nobody signed up to maintain.
        val tb = BtDarkColors.asTrueBlack()
        assertTrue("bg must be pure black", tb.bg.red == 0f && tb.bg.green == 0f && tb.bg.blue == 0f)
        assertTrue("surfaceLow must darken", tb.surfaceLow != BtDarkColors.surfaceLow)
        assertEquals(BtDarkColors.surface, tb.surface)
        assertEquals(BtDarkColors.surfaceHigh, tb.surfaceHigh)
        assertEquals(BtDarkColors.surfaceHighest, tb.surfaceHighest)
        assertEquals(BtDarkColors.gold, tb.gold)
        assertEquals(BtDarkColors.textPrimary, tb.textPrimary)
        assertEquals(BtDarkColors.border, tb.border)
    }
}
