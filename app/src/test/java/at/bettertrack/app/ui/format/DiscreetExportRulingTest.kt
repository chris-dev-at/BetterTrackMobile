package at.bettertrack.app.ui.format

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two rules about money formatting that are easy to break by accident and
 * expensive to notice, so they are pinned here.
 *
 * ## 1. The discreet-mode export ruling (owner, 2026-08-17)
 *
 * Discreet mode masks absolute money **on screen**, because the threat it
 * defends against is the person standing behind you. An export is a file the
 * user explicitly asked for, on their own device, for their own accounting —
 * masking it would protect nobody, would hand them a worthless artefact, and
 * would do it silently.
 *
 * So [btFormatMoneyCore] grew a `masked` parameter, and [btFormatMoneyExport]
 * is the one named door through it. The danger is obvious: that parameter is
 * also a way for any SCREEN to opt out of masking, which would quietly gut the
 * feature. This test is the fence — `masked = false` may appear in exactly one
 * place in the whole app.
 *
 * ## 2. One thousands separator per language (device review 2026-08-17)
 *
 * CLDR gives `de-AT` a narrow no-break space (U+202F) as its group separator
 * where `de-DE` uses a full stop. On the owner's de-AT phone that produced
 * `5 712,08 €` in the Cash screen and `3.112,08 €` on a launcher widget — the
 * same app, two conventions. [btFormatLocale] normalizes it at the one
 * formatter factory, so the fix cannot be "fixed" for one surface again.
 */
class DiscreetExportRulingTest {

    // ── 1. The one door ─────────────────────────────────────────────────────

    private fun mainSources(): List<Pair<String, File>> {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app"),
            File("app/src/main/java/at/bettertrack/app"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("app sources not found; tried ${roots.map { it.absolutePath }}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it }
            .toList()
    }

    @Test
    fun `masking is bypassed in exactly one place, and it is the export door`() {
        val bypass = Regex("""masked\s*=\s*false""")
        val offenders = mainSources()
            .filter { (_, file) -> bypass.containsMatchIn(file.readText()) }
            .map { (rel, _) -> rel }
            .sorted()
        assertEquals(
            "Discreet mode may only be bypassed by btFormatMoneyExport, for a file the user " +
                "asked to export. A SCREEN that opts out has broken the feature. Offenders: $offenders",
            listOf("ui${File.separator}format${File.separator}BtNumberFormat.kt"),
            offenders,
        )
    }

    @Test
    fun `the export formatter renders real values while masking is on`() {
        BtDiscreetMode.setEnabled(true)
        try {
            val masked = btFormatMoneyCore(1234.5, "EUR", Locale.GERMAN, showSign = false)
            val real = btFormatMoneyExport(1234.5, "EUR", Locale.GERMAN, showSign = false)
            assertTrue("the screen path must still mask: $masked", !masked.contains("1"))
            assertEquals("1.234,50 €", real)
        } finally {
            BtDiscreetMode.setEnabled(false)
        }
    }

    @Test
    fun `the export formatter is otherwise identical to the screen formatter`() {
        // It differs in exactly one thing. A second difference would mean the
        // file and the app disagree about a number, which is worse than masking.
        listOf(0.0, -2.125, 1_234_567.891, -0.004).forEach { value ->
            assertEquals(
                btFormatMoneyCore(value, "EUR", Locale.GERMAN, showSign = true),
                btFormatMoneyExport(value, "EUR", Locale.GERMAN, showSign = true),
            )
        }
    }

    // ── 2. One separator per language ───────────────────────────────────────

    @Test
    fun `every German variant formats with the same thousands separator`() {
        val atLocale = Locale.forLanguageTag("de-AT")
        val chLocale = Locale.forLanguageTag("de-CH")
        assertEquals(
            "5.712,08 €",
            btFormatMoneyCore(5712.08, "EUR", Locale.GERMAN, showSign = false, masked = false),
        )
        assertEquals(
            "5.712,08 €",
            btFormatMoneyCore(5712.08, "EUR", atLocale, showSign = false, masked = false),
        )
        // de-CH is the case that proves the normalization is scoped to the
        // NUMBER: its grouping joins the German convention, while the currency
        // SYMBOL stays whatever CLDR says a Swiss reader expects for a foreign
        // currency ("EUR", not "€"). Flattening that too would be this fix
        // overreaching from "one separator per language" into "one country".
        val ch = btFormatMoneyCore(5712.08, "EUR", chLocale, showSign = false, masked = false)
        assertTrue("de-CH grouping did not normalize: $ch", ch.startsWith("5.712,08 "))
    }

    @Test
    fun `the de-AT narrow no-break space is gone from every number shape`() {
        val at = Locale.forLanguageTag("de-AT")
        val narrow = '\u202F'
        val nbsp = '\u00A0'
        val rendered = listOf(
            btFormatMoneyCore(21052.0, "EUR", at, showSign = false, masked = false),
            btFormatPercentCore(1234.5, at, signed = true),
            btFormatQuantityCore(21052.125, at),
            btFormatCompactNumberCore(4161610000.0, at),
        )
        rendered.forEach { text ->
            assertTrue("narrow space survived in: $text", !text.contains(narrow))
            assertTrue("nbsp group separator survived in: $text", !text.contains("21${nbsp}052"))
        }
        assertTrue("expected dot grouping, got ${rendered[0]}", rendered[0].contains("21.052"))
    }

    @Test
    fun `normalization only touches German, and only the locale`() {
        assertEquals(Locale.GERMAN, btFormatLocale(Locale.forLanguageTag("de-AT")))
        assertEquals(Locale.GERMAN, btFormatLocale(Locale.GERMAN))
        // English (and everything else) is handed back untouched — the split was
        // a German-CLDR problem, not a reason to flatten every locale.
        assertEquals(Locale.US, btFormatLocale(Locale.US))
        assertEquals(Locale.UK, btFormatLocale(Locale.UK))
        assertEquals("1,234.50 €", btFormatMoneyCore(1234.5, "EUR", Locale.US, false, masked = false))
    }
}
