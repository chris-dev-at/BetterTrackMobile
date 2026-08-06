package at.bettertrack.app.ui.format

import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.market.formatPrice
import at.bettertrack.app.ui.portfolio.formatQuantity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Guards the per-thread, per-locale `NumberFormat` reuse introduced by the
 * 2026-08-06 perf pass (see `btNumberFormat` in `BtNumberFormat.kt`).
 *
 * The formatters used to be constructed fresh on every call, which was slow but
 * trivially correct. Caching them buys back ~4 ICU `DecimalFormat` constructions
 * per holdings row, and introduces exactly two ways to be wrong: leaking a
 * formatter between LOCALES, or leaking one between the three digit
 * CONFIGURATIONS. `NumberFormat` is also documented as not thread-safe, and the
 * same pass moved decoding onto background dispatchers, so a third question is
 * whether concurrent callers can corrupt each other.
 *
 * These tests answer all three by asserting output, not internals — which is
 * what actually has to hold.
 */
class BtNumberFormatCacheTest {

    private val de = Locale.GERMAN
    private val en = Locale.ENGLISH

    @Test
    fun `interleaving locales does not leak separators between them`() {
        // Alternate repeatedly: a cache keyed wrongly would serve the first
        // locale's formatter to the second from the second call onwards.
        repeat(5) {
            assertEquals("1.234,56 €", formatEur(1234.56, de))
            assertEquals("1,234.56 €", formatEur(1234.56, en))
        }
    }

    @Test
    fun `interleaving digit configurations does not leak fraction digits`() {
        // Money/percent are fixed at 2 decimals, quantities trim up to 8, and a
        // sub-cent price runs to 6 significant. One shared instance would show
        // up here as a quantity rendered "12,00" or a price truncated to "0,00".
        repeat(5) {
            assertEquals("1.234,56 €", formatEur(1234.56, de))
            assertEquals("12", formatQuantity(12.0, de))
            assertEquals("0,000012 €", formatPrice(0.000012, "EUR", de))
            assertEquals("+2,50 %", formatPercent(2.5, de, showSign = true))
            assertEquals("1,23456789", formatQuantity(1.23456789, de))
        }
    }

    @Test
    fun `concurrent formatting on many threads stays correct`() {
        // The cache is a ThreadLocal precisely so this holds. A shared unguarded
        // NumberFormat corrupts its internal buffer under concurrency and this
        // test fails intermittently — which is the failure mode worth pinning.
        val pool = Executors.newFixedThreadPool(8)
        try {
            val work = (0 until 400).map { i ->
                Callable {
                    val locale = if (i % 2 == 0) de else en
                    val expected = if (i % 2 == 0) "1.234,56 €" else "1,234.56 €"
                    assertEquals(expected, formatEur(1234.56, locale))
                    assertEquals("1,23456789", formatQuantity(1.23456789, de))
                }
            }
            pool.invokeAll(work).forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }
}
