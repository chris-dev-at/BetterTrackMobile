package at.bettertrack.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MB-09 guard: an amount field's unit comes from the formatter, never a typed
 * "€". Four input suffixes were hard-coded; for the custom-asset value sheet
 * that was simply wrong — a custom asset has its own currency (`currency`, set
 * at create and immutable) and its value points are in it, so a USD custom
 * asset was valued "in €" on the sheet and in every row of its own detail page.
 */
class HardCodedCurrencyDisciplineTest {

    private val root: File by lazy {
        listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("main sources not found")
    }

    private val literalUnit = Regex("""Text\(\s*"[€$£¥]"""")

    @Test
    fun `no amount renders a hard-coded currency symbol`() {
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> literalUnit.containsMatchIn(line) }
                    .map { (i, line) -> "${f.relativeTo(root).path}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertEquals("hard-coded currency units:\n${hits.joinToString("\n")}", emptyList<String>(), hits)
    }

    @Test
    fun `the pattern sees a literal unit and not a resolved one`() {
        assertTrue(literalUnit.containsMatchIn("""suffix = { Text("€", color = bt.textMuted) }"""))
        assertTrue(literalUnit.containsMatchIn("""{ Text("$") }"""))
        assertTrue(!literalUnit.containsMatchIn("""suffix = { Text(currencySymbol(currency, locale)) }"""))
        assertTrue(!literalUnit.containsMatchIn("""Text("€%")"""))
    }

    @Test
    fun `the custom-asset detail values every figure in the asset's own currency`() {
        val screen = File(root, "at/bettertrack/app/ui/customassets/CustomAssetDetailScreen.kt").readText()
        val calls = Regex("""MoneyText\(([^)]*)\)""").findAll(screen).map { it.groupValues[1] }.toList()
        assertTrue("expected the hero and both point rows, found ${calls.size}", calls.size >= 3)
        val euroOnly = calls.filterNot { it.contains("currencyCode = valueCurrency") }
        assertEquals("MoneyText calls that fall back to EUR: $euroOnly", emptyList<String>(), euroOnly)
        assertTrue(
            "the value sheet's suffix is not the asset's currency",
            screen.contains("Text(currencySymbol(valueCurrency, locale)"),
        )
    }
}
