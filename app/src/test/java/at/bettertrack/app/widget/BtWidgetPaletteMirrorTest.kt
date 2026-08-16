package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * [BtGlanceColor] is a hand-copied mirror of `BtColors`. This reads the original
 * and asserts the copy still matches it.
 *
 * ## Why the mirror exists, and why it has to be checked
 *
 * A widget is `RemoteViews` in the launcher's process. It cannot read
 * `BtTheme.colors`, which is delivered through a `CompositionLocal` inside the
 * app's Compose tree, so its colours have to cross as literals. Glance's
 * resource-backed `ColorProvider(resId)` — the one mechanism that would have
 * avoided the copy — is `@RestrictTo(LIBRARY_GROUP)` and fails lint.
 *
 * So the duplication is forced, and forced duplication is the kind that rots
 * quietly: the app's palette is retuned, every screen follows, and the widget
 * keeps painting last season's brand on the home screen where it is MORE visible
 * than any screen in the app. Nobody notices, because nobody has the widget and
 * the app open at the same time.
 *
 * This parses `BtColors.kt` rather than importing it on purpose. The Compose
 * values are `androidx.compose.ui.graphics.Color`, and several are built by
 * `.copy(alpha = …)` off a private ink — reading the SOURCE is what lets the
 * check name the exact declaration a future edit would touch, in the same
 * source-scanning style as
 * [at.bettertrack.app.ui.theme.BtThemeDisciplineTest].
 */
class BtWidgetPaletteMirrorTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun themeSource(): String =
        projectFile("src/main/java/at/bettertrack/app/ui/theme/BtColors.kt").readText()

    /** `private val BtGold = Color(0xFFF6B82E)` → "BtGold" to "#FFF6B82E". */
    private fun namedInks(): Map<String, String> =
        Regex("""private val ([A-Za-z][A-Za-z0-9_]*)\s*=\s*Color\(0[xX]([0-9A-Fa-f]{8})\)""")
            .findAll(themeSource())
            .associate { it.groupValues[1] to "#${it.groupValues[2].uppercase(Locale.ROOT)}" }

    /** The body of `val BtDarkColors = BtColors( … )` / its light twin. */
    private fun table(name: String): String {
        val start = themeSource().indexOf("val $name = BtColors(")
        assertTrue("$name not found in BtColors.kt", start >= 0)
        val end = themeSource().indexOf("\n)", start)
        assertTrue("$name is not terminated", end > start)
        return themeSource().substring(start, end)
    }

    /**
     * One token's value out of a table, resolving a named ink to its literal.
     * `null` when the token is not a plain literal or ink (an alpha composite).
     */
    private fun token(tableName: String, token: String): String? {
        val m = Regex("""^\s*$token\s*=\s*(?:Color\(0[xX]([0-9A-Fa-f]{8})\)|([A-Za-z][A-Za-z0-9_]*))\s*,""", RegexOption.MULTILINE)
            .find(table(tableName)) ?: return null
        m.groupValues[1].takeIf { it.isNotEmpty() }?.let { return "#${it.uppercase(Locale.ROOT)}" }
        return namedInks()[m.groupValues[2]]
    }

    private fun hex(argb: Long): String = String.format(Locale.ROOT, "#%08X", argb)

    /** Widget token → the `BtColors` property it mirrors. */
    private val mirrored = mapOf(
        BtGlanceColor.TextPrimary to "textPrimary",
        BtGlanceColor.TextSecondary to "textSecondary",
        BtGlanceColor.TextMuted to "textMuted",
        // The readable gold, not the brand fill — see BtGlanceColor.Gold.
        BtGlanceColor.Gold to "goldInk",
        BtGlanceColor.Gain to "gain",
        BtGlanceColor.Loss to "loss",
        // Round 2: the active-range-chip ink. onGold is copyable verbatim.
        BtGlanceColor.OnGold to "onGold",
    )

    /**
     * Tokens allowed to render identically in both modes, each with its reason.
     * NOT a loosening lever: an entry here must name a substrate that itself
     * does not flip. (The wash tokens are absent on purpose — they flatten over
     * [BtGlanceColor.Surface], which flips, so they must flip too.)
     */
    private val flatExempt = mapOf(
        // Ink on the brand-gold chip fill. Gold at fill strength is the one
        // brand constant (see res/values/colors.xml `bt_gold`), so the ink
        // contrasting with it is constant by construction.
        BtGlanceColor.OnGold to "sits on brand gold, which does not flip",
    )

    @Test
    fun `the scan can read the theme it is checking against`() {
        // A silently-unparseable BtColors.kt would make every assertion below
        // vacuous, which is the one way this file could fail at its job.
        assertTrue("no named inks parsed from BtColors.kt", namedInks().size >= 6)
        assertTrue("dark table looks empty", table("BtDarkColors").length > 500)
        assertTrue("light table looks empty", table("BtLightColors").length > 500)
        assertEquals("#FFF6B82E", namedInks()["BtGold"])
    }

    @Test
    fun `every mirrored token still matches BtColors`() {
        val offenders = mirrored.flatMap { (widget, themeToken) ->
            buildList {
                val light = token("BtLightColors", themeToken)
                val dark = token("BtDarkColors", themeToken)
                when {
                    light == null -> add("$themeToken: could not read the light value")
                    light != hex(widget.day) ->
                        add("$themeToken light: theme $light vs widget ${hex(widget.day)}")
                }
                when {
                    dark == null -> add("$themeToken: could not read the dark value")
                    dark != hex(widget.night) ->
                        add("$themeToken dark: theme $dark vs widget ${hex(widget.night)}")
                }
            }
        }
        assertTrue(
            "The widget palette has drifted from BtColors (see this class's KDoc). " +
                "Update BtGlanceColor to match:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the card's NIGHT side mirrors the app, the DAY side pins the study ruling`() {
        // Owner ruling 2026-08-16 (device review): the light widget card takes
        // the Codex study's warm off-white and its border, NOT the app's pure
        // white — a white card on a launcher read as unstyled. The night side
        // stays the brand mirror. These literals are the ruling; changing them
        // is a design decision, not a cleanup.
        assertEquals("#FFFBFBF9", hex(BtGlanceColor.Surface.day))
        assertEquals("#FFD9D9D4", hex(BtGlanceColor.Border.day))
        assertEquals("#FFEFEFEB", hex(BtGlanceColor.Chip.day))
        // Night: surface is BtColors.surface; border is the app's flattened
        // bt_border, exactly as published for XML consumers.
        assertEquals(token("BtDarkColors", "surface"), hex(BtGlanceColor.Surface.night))
        fun btBorder(qualifier: String): String? =
            Regex("""<color name="bt_border">(#[0-9A-Fa-f]{8})</color>""")
                .find(projectFile("src/main/res/values$qualifier/colors.xml").readText())
                ?.groupValues?.get(1)?.uppercase(Locale.ROOT)
        assertEquals(hex(BtGlanceColor.Border.night), btBorder("-night"))
    }

    @Test
    fun `every token really is theme-aware`() {
        // A token whose two sides are equal is a colour that never asked the
        // theme — the exact defect BtThemeDisciplineTest polices in the app, and
        // it matters more here: a widget sits on the user's wallpaper with no app
        // chrome to hide a mismatch. Gold is the sanctioned exception in the app's
        // palette, and even it differs (goldInk steps down on white).
        val flat = BtGlanceColor.entries
            .filter { it.day == it.night }
            .filterNot { it in flatExempt }
        assertTrue("widget colours that do not flip with the theme: $flat", flat.isEmpty())
    }

    @Test
    fun `the delta-pill washes really are the brand hue flattened over the card`() {
        // The washes cannot be alpha colours (RemoteViews composites over the
        // wallpaper, not the card), so BtGlanceColor carries them PRE-flattened.
        // This re-derives each from its hue + Surface and pins the arithmetic,
        // so a Surface or hue retune cannot leave a stale wash behind.
        fun flatten(surface: Long, hue: Long, alpha: Double): Long {
            fun ch(v: Long, shift: Int) = (v shr shift) and 0xFF
            fun mix(shift: Int) =
                Math.round(ch(surface, shift) * (1 - alpha) + ch(hue, shift) * alpha)
            return 0xFF000000L or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }

        val cases = listOf(
            Triple(BtGlanceColor.GainWash, BtGlanceColor.Gain, "GainWash"),
            Triple(BtGlanceColor.LossWash, BtGlanceColor.Loss, "LossWash"),
            Triple(BtGlanceColor.GoldWash, BtGlanceColor.Gold, "GoldWash"),
        )
        val offenders = cases.flatMap { (wash, hue, name) ->
            buildList {
                // Day pills sit on white at 12 %; night steps up to 14 % exactly
                // as the app's own wash tokens strengthen on dark.
                val day = flatten(BtGlanceColor.Surface.day, hue.day, 0.12)
                val night = flatten(BtGlanceColor.Surface.night, hue.night, 0.14)
                if (wash.day != day) add("$name day: expected ${hex(day)} got ${hex(wash.day)}")
                if (wash.night != night) {
                    add("$name night: expected ${hex(night)} got ${hex(wash.night)}")
                }
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `every token is fully opaque`() {
        // A RemoteViews colour has no substrate to composite against; a
        // translucent token would render over the wallpaper, not over the card.
        val translucent = BtGlanceColor.entries.filterNot { entry ->
            (entry.day ushr 24) == 0xFFL && (entry.night ushr 24) == 0xFFL
        }
        assertTrue("widget colours with alpha: $translucent", translucent.isEmpty())
    }
}
