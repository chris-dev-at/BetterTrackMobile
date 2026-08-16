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

    /** name -> (quantity -> body), for `<plurals>` entries. */
    private fun plurals(qualifier: String): Map<String, Map<String, String>> {
        val text = resFile(qualifier).readText()
        return Regex("""<plurals\s+name="([^"]+)"\s*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { block ->
                block.groupValues[1] to
                    Regex("""<item\s+quantity="([^"]+)"\s*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(block.groupValues[2])
                        .associate { it.groupValues[1] to it.groupValues[2] }
            }
    }

    private val placeholder = Regex("""%(\d+\$)?[sdf]""")

    private fun placeholdersIn(body: String): List<String> =
        placeholder.findAll(body).map { it.value }.sorted().toList()

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
        // "Bayer AG · XETRA" — a widget-preview SAMPLE made of proper nouns
        // (a company and an exchange); there is nothing to translate.
        "bt_widget_preview_asset_subline",
        // "Rocket Lab USA" — the same reasoning: a company name in the widget
        // previews' sample rows (the study's own data set).
        "bt_widget_preview_mover1_name",
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
        val mismatched = en.keys.intersect(de.keys).filter { key ->
            placeholdersIn(en.getValue(key)) != placeholdersIn(de.getValue(key))
        }.sorted()
        assertTrue("placeholder mismatch (crash risk at format time): $mismatched", mismatched.isEmpty())
    }

    /**
     * The `<string>` placeholder test above stops at `</string>`, so converting a
     * counted key from `<string>` to `<plurals>` used to *drop* its crash
     * coverage: `getQuantityString(id, n, a, b)` formats whichever item CLDR
     * picks, and an item that quietly lost a `%2$d` — or a German `one` form
     * translated from an older English wording — crashes only on the phone that
     * happens to hit that quantity. Every item of a plural therefore has to carry
     * exactly the same placeholders as every other item, in both languages.
     */
    @Test
    fun `format placeholders match across plural items and languages`() {
        val en = plurals("")
        val de = plurals("-de")
        val mismatched = en.keys.intersect(de.keys).filter { name ->
            val perItem = (en.getValue(name) + de.getValue(name).mapKeys { "de:" + it.key })
                .mapValues { placeholdersIn(it.value) }
            perItem.values.distinct().size > 1
        }.sorted()
        assertTrue("plural items disagree on placeholders (crash risk at format time): $mismatched", mismatched.isEmpty())
    }

    /**
     * A plural is only doing its job if the language's own quantity classes are
     * all present. English and German share the same two (CLDR `one` + `other`);
     * a missing `one` silently falls back to `other` and reprints the very bug
     * the plural was added to fix ("1 members"), while a stray extra class is
     * dead weight that no BetterTrack locale will ever select.
     */
    @Test
    fun `every plural carries the quantity classes its language needs`() {
        val required = setOf("one", "other")
        val wrong = mutableListOf<String>()
        for ((qualifier, label) in listOf("" to "EN", "-de" to "DE")) {
            plurals(qualifier).forEach { (name, items) ->
                if (items.keys != required) wrong += "$label:$name has ${items.keys.sorted()}"
            }
        }
        assertTrue("plurals with the wrong quantity classes (want $required): ${wrong.sorted()}", wrong.isEmpty())
    }

    /**
     * Keys whose `%d` sits next to a plural noun on purpose. Only a count that
     * can never be 1 belongs here — anything else is a plural waiting to happen.
     */
    private val hardCodedPluralAllowed = setOf(
        // "Use at least %1$d characters." — the argument is the compile-time
        // minimum length (a constant well above 1), never a live count.
        "bt_storage_pass_too_short",
    )

    /**
     * The guard for the whole class of bug: a counted noun frozen into a
     * `<string>`, which prints "1 transactions" the first time the count reaches
     * one. Deliberately narrow so it stays quiet — it only fires when a `%d`
     * (never `%s`) is followed within three words by a lower-case word of four
     * letters or more ending in a single "s". That leaves the abbreviation-based
     * counters alone by construction ("Active %1$d min ago", "%1$d d ago",
     * "Syncing %1$d%%"), because an abbreviation is not a noun that inflects.
     */
    @Test
    fun `no string hard-codes a plural noun beside a count`() {
        val count = Regex("""%(\d+\$)?d""")
        val word = Regex("""[A-Za-z']+""")
        val offenders = strings("").filter { (key, body) ->
            key !in hardCodedPluralAllowed && count.findAll(body).any { hit ->
                word.findAll(body.substring(hit.range.last + 1)).take(3).any { w ->
                    val t = w.value
                    t.length >= 4 && t == t.lowercase() && t.endsWith("s") && !t.endsWith("ss")
                }
            }
        }.keys.sorted()
        assertTrue(
            "counted noun hard-coded in a <string> (use <plurals> + pluralStringResource): $offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * The S6 P0-4 error catalogue is the largest single block of copy in the app
     * and the one most likely to be extended in a hurry (a new server code, a
     * new queue refusal). Asserting the whole `bt_err_*` family here — on top of
     * the generic parity tests above — means a half-added code fails the build
     * with the key named, rather than shipping an English sentence to a German
     * phone. `BtErrorCopyTest` checks the other half: that the Kotlin catalogue
     * and these strings agree.
     */
    @Test
    fun `every error-code string exists in both languages`() {
        val en = strings("").keys.filter { it.startsWith("bt_err_") }.toSet()
        val de = strings("-de").keys.filter { it.startsWith("bt_err_") }.toSet()
        assertTrue("error catalogue looks empty (${en.size} keys)", en.size > 150)
        assertTrue("error codes missing from DE: ${(en - de).sorted()}", (en - de).isEmpty())
        assertTrue("error codes only in DE: ${(de - en).sorted()}", (de - en).isEmpty())
    }

    /**
     * The chart-window family (`bt_range_*`), which the generic tests above
     * cannot police on their own.
     *
     * Every value here is two or three characters, so the "actually translated"
     * test below — which only fires above 12 characters — waves them all through,
     * including a German `5Y` pasted straight from English. That is exactly the
     * bug this family had: `AssetRange` and `BacktestRange` carried hardcoded
     * English labels on the enums until 2026-08-08, so the asset page said `1Y`
     * one tap away from the hero saying `1J`. The labels are resources now, and
     * this is the assertion that the German ones are actually German.
     */
    @Test
    fun `every chart window says its year in the reader's language`() {
        val en = strings("")
        val de = strings("-de")
        val enWindows = en.keys.filter { it.startsWith("bt_range_") }.toSet()
        val deWindows = de.keys.filter { it.startsWith("bt_range_") }.toSet()
        assertEquals(enWindows, deWindows)
        // 1D 1W 1M 3M 6M 1Y 3Y 5Y Max — every window any of the three charts
        // serves. A new one arriving without a translation fails here.
        assertEquals(9, enWindows.size)

        // English abbreviates the year Y(ear); German J(ahr).
        mapOf("bt_range_1y" to "1Y", "bt_range_3y" to "3Y", "bt_range_5y" to "5Y").forEach { (k, v) ->
            assertEquals(v, en.getValue(k).trim())
        }
        mapOf("bt_range_1y" to "1J", "bt_range_3y" to "3J", "bt_range_5y" to "5J").forEach { (k, v) ->
            assertEquals(v, de.getValue(k).trim())
        }
        // The general form of the same rule, so a tenth window cannot slip in
        // with an English Y on the German side.
        val anglicised = deWindows.filter { de.getValue(it).trim().endsWith("Y") }.sorted()
        assertTrue("German windows still printing an English year: $anglicised", anglicised.isEmpty())
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
