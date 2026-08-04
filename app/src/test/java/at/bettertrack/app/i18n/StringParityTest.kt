package at.bettertrack.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EN↔DE string parity guard.
 *
 * Until now parity was verified by hand (a `grep -c "<string"` count compared
 * across the two files and written into the board notes). That catches a missing
 * key only if someone remembers to run it, and it cannot tell a real translation
 * from an English string pasted into the German file. This test makes the check
 * automatic and names the offending keys.
 */
class StringParityTest {

    private fun resFile(qualifier: String): File {
        val name = "src/main/res/values$qualifier/strings.xml"
        // Unit tests run with the module dir as CWD, but tolerate the repo root.
        val candidates = listOf(File(name), File("app/$name"))
        return candidates.firstOrNull { it.isFile }
            ?: error("strings.xml not found; tried ${candidates.map { it.absolutePath }}")
    }

    /** name -> body, for `<string>` entries only (plurals handled separately). */
    private fun strings(qualifier: String): Map<String, String> {
        val text = resFile(qualifier).readText()
        return Regex("""<string\s+name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[3] }
    }

    private fun pluralNames(qualifier: String): Set<String> =
        Regex("""<plurals\s+name="([^"]+)"""")
            .findAll(resFile(qualifier).readText())
            .map { it.groupValues[1] }
            .toSet()

    /** Keys whose value is legitimately identical in both languages. */
    private val identicalAllowed = setOf(
        // Proper nouns, symbols, format-only strings and loanwords that do not
        // translate. Anything added here is a deliberate decision, not an oversight.
        "bt_app_name",
        // "© BetterTrack" — a brand line, identical by design.
        "bt_about_copyright",
        // "Import · %1$s" — "Import" is the same word in German; inventing a
        // different one would make the badge worse, not more translated.
        "bt_source_import",
        // "Sync · %1$s" — same reasoning; "Sync" is the established loanword.
        "bt_source_sync",
    )

    @Test
    fun `german translation exists for every english string`() {
        val en = strings("")
        val de = strings("-de")
        val missing = (en.keys - de.keys).sorted()
        assertTrue("missing DE translations for: $missing", missing.isEmpty())
    }

    @Test
    fun `german file carries no key english does not have`() {
        val en = strings("")
        val de = strings("-de")
        val orphan = (de.keys - en.keys).sorted()
        assertTrue("orphaned DE keys (no EN counterpart): $orphan", orphan.isEmpty())
    }

    @Test
    fun `plurals are at parity too`() {
        assertEquals(pluralNames("").sorted(), pluralNames("-de").sorted())
    }

    @Test
    fun `format placeholders match between languages`() {
        val en = strings("")
        val de = strings("-de")
        val placeholder = Regex("""%(\d+\$)?[sdf]""")
        val mismatched = en.keys.intersect(de.keys).filter { key ->
            val a = placeholder.findAll(en.getValue(key)).map { it.value }.sorted().toList()
            val b = placeholder.findAll(de.getValue(key)).map { it.value }.sorted().toList()
            a != b
        }.sorted()
        assertTrue("placeholder mismatch (crash risk at format time): $mismatched", mismatched.isEmpty())
    }

    @Test
    fun `german strings are actually translated`() {
        val en = strings("")
        val de = strings("-de")
        // A DE value byte-identical to EN is usually a forgotten translation.
        // Short/symbolic values are excluded — "OK", "%1$s", "€" are the same in both.
        val untranslated = en.keys.intersect(de.keys).filter { key ->
            val v = en.getValue(key).trim()
            key !in identicalAllowed &&
                v.length > 12 &&
                v.any { it.isLetter() } &&
                v == de.getValue(key).trim()
        }.sorted()
        assertTrue("DE value identical to EN (untranslated?): $untranslated", untranslated.isEmpty())
    }
}
