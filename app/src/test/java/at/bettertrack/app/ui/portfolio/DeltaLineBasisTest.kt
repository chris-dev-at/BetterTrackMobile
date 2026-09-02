package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.ui.format.BtDiscreetMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The hero delta line: ONE shape, and what its two numbers actually mean.
 *
 * ## The shape (owner order, device QA 2026-09-01 #14)
 *
 * `+/-xx € (xx %) · <Zeitspanne in Worten>`, for every window. The line used to
 * fork — 1D bracketed its percent, every longer window wrote `€ · % · Wort` —
 * because outside 1D the two figures have different bases and a bracket reads as
 * "the same number, expressed differently". The owner saw both shapes on the
 * device and ruled the bracketed one everywhere: two punctuations for one line
 * read as a bug, and no reader decodes a separator.
 *
 * ## What the two numbers are (defect #13 — the pair is KEPT, by owner order)
 *
 * The € is an ABSOLUTE change in net worth: a deposit moves it by its full
 * amount. The % is the server's TIME-WEIGHTED performance: the same deposit
 * moves it by nothing. So `+3 004,07 € (+0,85 %)` is two correct answers to two
 * different questions, not an arithmetic error — and both stay, because the €
 * answers "how much more is in here" and the % answers "how well did it do".
 * The reasoning lives in `DeltaLine.kt`'s KDoc; this pins the rendering.
 */
class DeltaLineBasisTest {

    @After
    fun tearDown() = BtDiscreetMode.resetForTest()

    private val de = Locale.GERMANY
    private val en = Locale.ENGLISH

    // ── One shape, every span ────────────────────────────────────────────────

    @Test
    fun `every span writes the percent in brackets after the money`() {
        // The window word is what varies across spans; the SHAPE never does.
        listOf(
            "heute",
            "letzte Woche",
            "letzter Monat",
            "letzte sechs Monate",
            "letztes Jahr",
            "seit Beginn",
        ).forEach { span ->
            assertEquals(
                "-176,76 € (-1,47 %) · $span",
                btDeltaLineText(-176.76, -1.47, span, de),
            )
        }
    }

    @Test
    fun `the owner's 1D example renders exactly as he wrote it`() {
        assertEquals(
            "-176,76 € (-1,47 %) · heute",
            btDeltaLineText(-176.76, -1.47, "heute", de),
        )
    }

    @Test
    fun `the spans that used to use a middot now bracket too`() {
        // Device 2026-09-01: `-199,63 € · -0,99 % · letzte Woche` was the old
        // shape for every span but 1D. It is the same figures, re-punctuated.
        assertEquals(
            "-199,63 € (-0,99 %) · letzte Woche",
            btDeltaLineText(-199.63, -0.99, "letzte Woche", de),
        )
        assertEquals(
            "+2.830,77 € (+3,15 %) · letzter Monat",
            btDeltaLineText(2_830.77, 3.15, "letzter Monat", de),
        )
        assertEquals(
            "+16.265,34 € (+70,77 %) · seit Beginn",
            btDeltaLineText(16_265.34, 70.77, "seit Beginn", de),
        )
    }

    @Test
    fun `the different-basis pair is rendered, not hidden`() {
        // Defect #13: `+3 004,07 € (+0,85 %)` is the case that motivated the old
        // fork. The owner ordered both figures kept, so it must still render.
        assertEquals(
            "+3.004,07 € (+0,85 %) · letzter Monat",
            btDeltaLineText(3_004.07, 0.85, "letzter Monat", de),
        )
    }

    @Test
    fun `a window with no server percentage degrades to money and span`() {
        // No empty bracket, no invented zero.
        assertEquals("+120,00 € · letztes Jahr", btDeltaLineText(120.0, null, "letztes Jahr", de))
    }

    @Test
    fun `english keeps its own number and percent conventions`() {
        assertEquals("+1,204.20 € (+0.95%) · today", btDeltaLineText(1_204.20, 0.95, "today", en))
    }

    @Test
    fun `the money half is masked in discreet mode and the percent is not`() {
        BtDiscreetMode.setEnabled(true)
        val line = btDeltaLineText(-176.76, -1.47, "heute", de)
        assertEquals("•••• € (-1,47 %) · heute", line)
    }

    // ── The EUR half is a difference of two server points, nothing more ──────

    @Test
    fun `the range delta is last minus first`() {
        val points = listOf(
            HistoryPoint(1_000L, 17_526.61),
            HistoryPoint(2_000L, 19_000.00),
            HistoryPoint(3_000L, 20_530.68),
        )
        assertEquals(20_530.68 - 17_526.61, rangeDeltaEur(points)!!, 1e-9)
    }

    @Test
    fun `a series that cannot carry a difference stays silent rather than claiming zero`() {
        assertNull(rangeDeltaEur(emptyList()))
        assertNull(rangeDeltaEur(listOf(HistoryPoint(1_000L, 100.0))))
    }

    @Test
    fun `a fallen portfolio reports a negative delta`() {
        val points = listOf(HistoryPoint(1L, 500.0), HistoryPoint(2L, 420.0))
        assertEquals(-80.0, rangeDeltaEur(points)!!, 1e-9)
    }
}
