package at.bettertrack.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Bidi

/**
 * The untrusted-label treatment, proved rather than eyeballed.
 *
 * The two properties that decide whether [btSanitizeUntrustedLabel] is the right
 * shape pull in opposite directions, so both are asserted against the **real
 * Unicode bidi algorithm** (`java.text.Bidi`, the same UBA a text renderer runs)
 * rather than against a hand-reasoned guess about what the glyphs will do:
 *
 *  - a planted RIGHT-TO-LEFT OVERRIDE must not reorder the label OR the text
 *    around it, and
 *  - a legitimate Hebrew or Arabic name must still render as itself.
 *
 * Isolating alone passes the second and fails the first *inside* the label: a
 * fenced U+202E still reverses the label, and the label is the security answer.
 * Stripping alone passes the first but gives up the fencing that keeps an
 * all-RTL name from dragging the surrounding UI around with it. This file is
 * what stops a later "simplification" down to one of the two halves.
 *
 * Every control character below is written as a Unicode escape on purpose: a
 * test about invisible characters that pastes invisible characters into its own
 * source is a test nobody can review.
 */
class UntrustedLabelTest {

    /** U+2068 FSI / U+2069 PDI - the fence [btSanitizeUntrustedLabel] adds. */
    private val fsi = '\u2068'
    private val pdi = '\u2069'

    /** The sanitized text with the app's own isolate fence peeled off. */
    private fun visible(sanitized: String): String {
        assertTrue("not fenced at all: <$sanitized>", sanitized.length >= 2)
        assertEquals("must open with FSI", fsi, sanitized.first())
        assertEquals("must close with PDI", pdi, sanitized.last())
        return sanitized.substring(1, sanitized.length - 1)
    }

    /** The ruled control set, as a predicate - nothing in it may survive. */
    private fun isRuledControl(codePoint: Int): Boolean =
        codePoint <= 0x1F ||
            codePoint in 0x7F..0x9F ||
            codePoint == 0x2028 ||
            codePoint == 0x2029 ||
            codePoint in 0x202A..0x202E ||
            codePoint in 0x2066..0x2069

    // -- property 1: a planted override is inert, inside and outside ---------

    @Test
    fun `an RTL override cannot reorder the label it was planted in`() {
        // The classic spoof: the bytes say "safe" + U+202E + "tluav", the glyphs
        // say "safevault". Stripping is what defeats it - a fence would only stop
        // it escaping the label, and the label IS the answer the user acts on.
        val attack = "safe\u202Etluav"
        assertEquals("safetluav", visible(btSanitizeUntrustedLabel(attack)))
        assertFalse(btSanitizeUntrustedLabel(attack).contains('\u202E'))

        // Every member of the ruled bidi set, not just the famous one.
        listOf(
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069',
        ).forEach { control ->
            val sanitized = btSanitizeUntrustedLabel("Depot${control}kcatta")
            assertEquals(
                "U+" + control.code.toString(16) + " survived",
                "Depotkcatta",
                visible(sanitized),
            )
        }
    }

    @Test
    fun `an override in the label cannot reorder the text around it`() {
        // The UBA's verdict, not ours. Left-to-right base paragraph, hostile name
        // in the middle: with the raw hint the paragraph is no longer LTR, which
        // is the reordering leaking OUT of the label; after the treatment it is.
        val raw = "Adopt: safe\u202Etluav ?"
        val treated = "Adopt: ${btSanitizeUntrustedLabel("safe\u202Etluav")} ?"
        assertFalse(
            "the attack must actually work on raw text, or this test proves nothing",
            Bidi(raw, Bidi.DIRECTION_LEFT_TO_RIGHT).isLeftToRight,
        )
        assertTrue(
            "the sanitized label must leave the surrounding paragraph purely LTR",
            Bidi(treated, Bidi.DIRECTION_LEFT_TO_RIGHT).isLeftToRight,
        )
    }

    @Test
    fun `an unterminated isolate cannot swallow the rest of the line either`() {
        // U+2066..U+2068 with no PDI is the same primitive in its modern
        // spelling; both clients parse it into `n`, so it must die here too.
        val sanitized = btSanitizeUntrustedLabel("\u2067Depot")
        assertEquals("Depot", visible(sanitized))
        assertTrue(Bidi("Adopt: $sanitized ?", Bidi.DIRECTION_LEFT_TO_RIGHT).isLeftToRight)
    }

    // -- property 2: a legitimate RTL name is untouched and still RTL --------

    @Test
    fun `a legitimate Hebrew name survives the treatment character for character`() {
        val hebrew = "כספת הבית"
        assertEquals(
            "stripping must not cost a single letter of a real RTL name",
            hebrew,
            visible(btSanitizeUntrustedLabel(hebrew)),
        )
    }

    @Test
    fun `a legitimate Arabic name survives the treatment character for character`() {
        val arabic = "خزنة المنزل"
        assertEquals(arabic, visible(btSanitizeUntrustedLabel(arabic)))
    }

    @Test
    fun `an RTL name still renders right-to-left, and only within its own fence`() {
        val sanitized = btSanitizeUntrustedLabel("כספת הבית")

        // The FSI's whole job: the base direction is resolved from the first
        // strong character, so the name's own run stays RTL rather than being
        // flattened to LTR and rendered backwards.
        assertFalse(
            "an RTL name must not be flattened to LTR",
            Bidi(sanitized, Bidi.DIRECTION_LEFT_TO_RIGHT).isLeftToRight,
        )

        // And the fence: the LTR text around it keeps level 0, so a legitimate
        // RTL name does not drag the surrounding UI with it either.
        val line = "Adopt: $sanitized ?"
        val bidi = Bidi(line, Bidi.DIRECTION_LEFT_TO_RIGHT)
        assertEquals("the leading label must stay LTR", 0, bidi.getLevelAt(0))
        assertEquals("the trailing punctuation must stay LTR", 0, bidi.getLevelAt(line.length - 1))
    }

    // -- the rest of the ruled treatment -------------------------------------

    @Test
    fun `C0 and C1 controls are stripped and line breaks become a single space`() {
        // NUL is not whitespace, so neither isBlank() nor trim() in the parser
        // catches it; it has to die here.
        assertEquals("pwn", visible(btSanitizeUntrustedLabel("\u0000pwn")))
        assertEquals("Phone vault", visible(btSanitizeUntrustedLabel("Phone\nvault")))
        assertEquals("Phone vault", visible(btSanitizeUntrustedLabel("Phone\r\n\tvault")))
        assertEquals("ab", visible(btSanitizeUntrustedLabel("a\u0001b")))
        assertEquals("ab", visible(btSanitizeUntrustedLabel("a\u007Fb"))) // DEL
        assertEquals("ab", visible(btSanitizeUntrustedLabel("a\u009Bb"))) // C1 CSI
        // The two Unicode line separators read as whitespace, not as removals.
        assertEquals("a b", visible(btSanitizeUntrustedLabel("a\u2028b")))
        assertEquals("a b", visible(btSanitizeUntrustedLabel("a\u2029b")))
    }

    @Test
    fun `whitespace runs collapse and the ends are trimmed, so the label is one line`() {
        assertEquals(
            "Phone vault",
            visible(btSanitizeUntrustedLabel("  Phone \t\n  vault  ")),
        )
        // Non-breaking and exotic spaces are just spaces for a single-line label.
        assertEquals("a b", visible(btSanitizeUntrustedLabel("a\u00A0 \u3000b")))
        assertFalse(btSanitizeUntrustedLabel("a\nb").contains('\n'))
    }

    @Test
    fun `nothing from the ruled set can survive any input`() {
        val hostile = buildString {
            append("Depot")
            (0x00..0x1F).forEach { appendCodePoint(it) }
            (0x7F..0x9F).forEach { appendCodePoint(it) }
            listOf(0x2028, 0x2029, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E)
                .forEach { appendCodePoint(it) }
            (0x2066..0x2069).forEach { appendCodePoint(it) }
            append("vault")
        }
        val inner = visible(btSanitizeUntrustedLabel(hostile))
        val survivors = inner.codePoints().toArray().filter { isRuledControl(it) }
        assertTrue("these must not survive: $survivors", survivors.isEmpty())
        assertEquals("Depot vault", inner)
    }

    @Test
    fun `a label made only of stripped characters comes back empty, not blank`() {
        // The caller's cue to fall back to its own trusted placeholder. A blank
        // string would paint an empty line where a vault name belongs.
        assertEquals("", btSanitizeUntrustedLabel("\u0000\u202E\u2069"))
        assertEquals("", btSanitizeUntrustedLabel("   \n\t "))
        assertEquals("", btSanitizeUntrustedLabel(""))
        assertEquals("", btSanitizeUntrustedLabel(null))
    }

    // -- the parts that must NOT be neutralized ------------------------------

    @Test
    fun `emoji survive, including the ZWJ sequences that need a format character`() {
        assertEquals("Depot 😀", visible(btSanitizeUntrustedLabel("Depot 😀")))
        // A family emoji is three people glued with U+200D ZERO WIDTH JOINER.
        // Stripping "format characters" wholesale would shatter it into three
        // separate humans, so U+200D is deliberately outside the ruled set.
        val family = "👨" + "\u200D" + "👩" + "\u200D" + "👧"
        assertEquals(family, visible(btSanitizeUntrustedLabel(family)))
        // U+200C ZWNJ is required by legitimate Persian orthography.
        val persian = "می" + "\u200C" + "رود"
        assertEquals(persian, visible(btSanitizeUntrustedLabel(persian)))
    }

    @Test
    fun `ordinary names pass through unchanged apart from the fence`() {
        listOf("Phone vault", "Familie & Co", "Öl & Gas – Depot", "Café", "金庫")
            .forEach { assertEquals(it, visible(btSanitizeUntrustedLabel(it))) }
    }

    // -- the cap and the ellipsis --------------------------------------------

    @Test
    fun `an over-long label is ellipsized inside its budget`() {
        val cut = visible(btSanitizeUntrustedLabel("x".repeat(100), maxCodePoints = 10))
        assertEquals("xxxxxxxxx…", cut)
        assertEquals("the ellipsis counts against the budget", 10, cut.codePointCount(0, cut.length))
    }

    @Test
    fun `a label exactly at the budget is not ellipsized`() {
        val exact = "x".repeat(10)
        assertEquals(exact, visible(btSanitizeUntrustedLabel(exact, maxCodePoints = 10)))
    }

    @Test
    fun `truncation counts code points, so an emoji is never sliced in half`() {
        // 10 emoji = 10 code points = 20 UTF-16 code units. Cutting on code units
        // would leave an unpaired surrogate, which renders as a replacement box.
        val cut = visible(btSanitizeUntrustedLabel("😀".repeat(10), maxCodePoints = 4))
        assertEquals("😀😀😀…", cut)
        // A UTF-8 round trip is the decisive check: an unpaired surrogate cannot
        // survive one, it comes back as U+FFFD.
        assertEquals(cut, String(cut.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `the default budget is the same 64 the QR name hint is capped at`() {
        assertEquals(64, BT_UNTRUSTED_LABEL_MAX_CODE_POINTS)
        // A conforming hint therefore never reaches the ellipsis path - including
        // the 64 astral characters ruling 3 now makes legal.
        val atCap = "é".repeat(64)
        assertEquals(atCap, visible(btSanitizeUntrustedLabel(atCap)))
        val astralAtCap = "😀".repeat(64)
        assertEquals(astralAtCap, visible(btSanitizeUntrustedLabel(astralAtCap)))
    }

    @Test
    fun `a nonsensical budget yields nothing rather than a crash`() {
        assertEquals("", btSanitizeUntrustedLabel("Depot", maxCodePoints = 0))
        assertEquals("", btSanitizeUntrustedLabel("Depot", maxCodePoints = -1))
    }

    @Test
    fun `the treatment is idempotent, so a label cannot accumulate fences`() {
        // Re-running it strips the previous fence (U+2066..U+2069 is in the ruled
        // set) and adds exactly one back, so passing through two render sites
        // cannot pile up invisible characters.
        val once = btSanitizeUntrustedLabel("Phone vault")
        assertEquals(once, btSanitizeUntrustedLabel(once))
    }
}
