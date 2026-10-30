package at.bettertrack.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MB-08 guard: a currency code becomes a symbol in exactly one place,
 * [btMoneySymbol]. A second resolver is how the alert sheet came to print
 * "usd" where every other screen printed "$" — each copy drifts on its own
 * (case folding, fallback). Scans `app/src/main` for `.getSymbol(` calls.
 */
class CurrencySymbolDisciplineTest {

    private val root: File by lazy {
        listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("main sources not found")
    }

    @Test
    fun `only the format package resolves a currency symbol`() {
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) ->
                        val code = line.trim()
                        !code.startsWith("*") && !code.startsWith("//") && code.contains(".getSymbol(")
                    }
                    .map { (i, _) -> "${f.relativeTo(root).path}:${i + 1}" }
            }
            .toList()
        // Precision: the canonical resolver itself must still be found.
        assertTrue("the scan no longer sees btMoneySymbol's own call", hits.any { it.startsWith("at/bettertrack/app/ui/format/BtNumberFormat.kt:") })
        assertEquals(
            "currency symbols resolved outside ui/format/BtNumberFormat.kt",
            emptyList<String>(),
            hits.filterNot { it.startsWith("at/bettertrack/app/ui/format/BtNumberFormat.kt:") },
        )
    }

    @Test
    fun `the components alias delegates to the canonical resolver`() {
        val money = File(root, "at/bettertrack/app/ui/components/MoneyText.kt").readText()
        val body = money.substringAfter("fun currencySymbol(", missingDelimiterValue = "")
            .substringBefore("\nfun ")
        assertTrue("MoneyText.kt no longer declares currencySymbol", body.isNotEmpty())
        assertTrue("currencySymbol no longer delegates to btMoneySymbol: $body", body.contains("btMoneySymbol(code, locale)"))
    }
}
