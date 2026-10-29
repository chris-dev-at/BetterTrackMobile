package at.bettertrack.app.i18n

import at.bettertrack.app.R
import at.bettertrack.app.ui.cash.rulesAppliedArgs
import at.bettertrack.app.ui.social.sharedWithFriendsToast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.IllegalFormatException
import java.util.Locale

/**
 * Every counted toast, formatted with the arg list its caller ACTUALLY passes,
 * against the real EN and DE plural patterns — so a `%2$d` handed a String fails
 * here instead of crashing on a phone.
 *
 * The crash this pins (device verify 2026-10-29, `BtSnackbar.kt` getQuantityString):
 * the snackbar PREPENDED the quantity to the args while the Compose path passed
 * them verbatim, so `“%1$s” shared with %2$d friends` received
 * `[1, "Tech Radar", 1]` and `%2$d` got a String. One convention now — the
 * platform's: the quantity only selects the form, the args are complete and
 * verbatim. `java.util.Formatter` raises the same `IllegalFormatConversionException`
 * Android's `getQuantityString` does, which is what makes this a faithful check.
 */
class QuantityToastFormatTest {

    private fun resFile(qualifier: String): File {
        val name = "src/main/res/values$qualifier/strings.xml"
        return listOf(File(name), File("app/$name")).firstOrNull { it.isFile }
            ?: error("strings.xml not found for values$qualifier")
    }

    /** `plural name -> quantity -> pattern`, XML/Android escapes undone. */
    private fun plurals(qualifier: String): Map<String, Map<String, String>> {
        val text = resFile(qualifier).readText()
        return Regex("""<plurals\s+name="([^"]+)"\s*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { m ->
                m.groupValues[1] to Regex("""<item\s+quantity="([^"]+)"\s*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(m.groupValues[2])
                    .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            }
    }

    private fun unescape(s: String): String = s
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private data class Case(val what: String, val pluralName: String, val id: Int, val expectedId: Int, val args: List<Any>)

    /** Every counted toast in the app, built by the very factory its call site uses. */
    private fun cases(count: Int): List<Case> {
        val shared = sharedWithFriendsToast("Tech Radar", count)
        return listOf(
            Case("Social: shared with N friends", "bt_social_toast_shared_specific", shared.id, R.plurals.bt_social_toast_shared_specific, shared.args),
            Case("Cash rules: N movements tagged", "bt_rules_apply_done", R.plurals.bt_rules_apply_done, R.plurals.bt_rules_apply_done, rulesAppliedArgs(count)),
        )
    }

    private val languages = listOf("" to Locale.ENGLISH, "-de" to Locale.GERMANY)

    @Test
    fun `every counted toast formats with its real args in EN and DE`() {
        listOf(1, 3).forEach { count ->
            cases(count).forEach { c ->
                assertEquals("${c.what}: the factory names the wrong plural", c.expectedId, c.id)
                languages.forEach { (qualifier, locale) ->
                    val items = plurals(qualifier)[c.pluralName] ?: error("${c.pluralName} missing in values$qualifier")
                    assertTrue("${c.pluralName} has no items in values$qualifier", items.isNotEmpty())
                    items.forEach { (quantity, pattern) ->
                        val out = try {
                            String.format(locale, pattern, *c.args.toTypedArray())
                        } catch (e: IllegalFormatException) {
                            fail("${c.what} [$qualifier/$quantity] `$pattern` with ${c.args}: $e")
                            return@forEach
                        }
                        assertTrue("${c.what} [$qualifier/$quantity] lost the count: $out", count.toString() in out)
                        assertTrue("${c.what} [$qualifier/$quantity] left a placeholder: $out", "%" !in out)
                    }
                }
            }
        }
        val shared = String.format(Locale.GERMANY, plurals("-de").getValue("bt_social_toast_shared_specific").getValue("one"), *sharedWithFriendsToast("Tech Radar", 1).args.toTypedArray())
        assertEquals("„Tech Radar“ mit 1 Freund geteilt", shared)
    }

    @Test
    fun `the retired prepend convention is exactly the device crash`() {
        // Proves the check above can go red: the old snackbar arg list for the
        // shared toast — quantity prepended — dies on `%2$d`.
        val pattern = plurals("").getValue("bt_social_toast_shared_specific").getValue("one")
        val prepended = listOf<Any>(1) + sharedWithFriendsToast("Tech Radar", 1).args
        try {
            String.format(Locale.ENGLISH, pattern, *prepended.toTypedArray())
            fail("the prepended list must not format")
        } catch (e: java.util.IllegalFormatConversionException) {
            assertEquals('d', e.conversion)
        }
    }

    @Test
    fun `no counted toast is built outside the audited factories`() {
        // A new call site must go through a factory listed in [cases], or this
        // check has nothing to format it with.
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val allowed = mapOf(
            "SocialToast.Quantity(" to setOf("SocialToast.kt"),
            "showQuantity(" to setOf("BtSnackbar.kt", "SocialToast.kt", "CashRulesScreen.kt"),
        )
        val stray = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val text = f.readText()
            allowed.filter { (needle, files) -> needle in text && f.name !in files }.map { "${f.name}: ${it.key}" }
        }.toList()
        assertEquals("unaudited counted-toast call sites", emptyList<String>(), stray)
        // CashRulesScreen's one call must hand over its explicit args.
        val rules = root.walkTopDown().first { it.name == "CashRulesScreen.kt" }.readText()
        assertTrue(
            "CashRulesScreen must pass rulesAppliedArgs to showQuantity",
            "showQuantity(R.plurals.bt_rules_apply_done, tagged, *rulesAppliedArgs(tagged).toTypedArray())" in rules,
        )
    }
}
