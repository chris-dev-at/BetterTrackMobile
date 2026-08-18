package at.bettertrack.app.ui.charts.viz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `Darstellung` preference codec.
 *
 * The interesting assertions here are all about the DECODE side, because that is
 * the one that meets input it did not write: a downgrade, a sideload, a
 * half-finished migration. Every one of those must land on `Automatisch` rather
 * than throwing — an unreadable preference must never be able to lock a user out
 * of their own insights page.
 */
class VizConfigCodecTest {

    @Test
    fun `a pristine config encodes to nothing`() {
        // "Never chose" has to stay distinguishable from "chose the current
        // default", or a later change to what Automatisch resolves to would
        // silently overrule people who had actively picked the old shape.
        assertNull(vizConfigEncode(BtVizConfig()))
    }

    @Test
    fun `every field survives a round trip`() {
        val config = BtVizConfig(
            form = BtVizForm.TREEMAP,
            labels = BtVizLabels.AMOUNTS,
            scope = BtVizScope.TOP_8,
            showCash = false,
            focusKey = "sym:MSFT",
        )
        assertEquals(config, vizConfigDecode(vizConfigEncode(config)))
    }

    @Test
    fun `each field round trips on its own`() {
        BtVizForm.entries.forEach { form ->
            val c = BtVizConfig(form = form)
            assertEquals(c, vizConfigDecode(vizConfigEncode(c) ?: ""))
        }
        BtVizLabels.entries.forEach { labels ->
            val c = BtVizConfig(labels = labels)
            assertEquals(c, vizConfigDecode(vizConfigEncode(c) ?: ""))
        }
        BtVizScope.entries.forEach { scope ->
            val c = BtVizConfig(scope = scope)
            assertEquals(c, vizConfigDecode(vizConfigEncode(c) ?: ""))
        }
    }

    @Test
    fun `unreadable input decodes to the safe default instead of throwing`() {
        listOf(
            null,
            "",
            "   ",
            "garbage",
            "TREEMAP",
            "TREEMAP|AUTO",
            "TREEMAP|AUTO|AUTO|1|-|extra",
            "|||| ",
        ).forEach { raw ->
            assertEquals("input: $raw", BtVizConfig(), vizConfigDecode(raw))
        }
    }

    @Test
    fun `a form this build does not know degrades to Automatisch`() {
        // Written by a newer build that shipped an extra shape.
        val decoded = vizConfigDecode("PACKED_BUBBLES|SHARES|TOP_5|1|-")
        assertEquals(BtVizForm.AUTO, decoded.form)
        // The knobs it DOES understand still survive — one unknown token must
        // not throw away the rest of the user's configuration.
        assertEquals(BtVizLabels.SHARES, decoded.labels)
        assertEquals(BtVizScope.TOP_5, decoded.scope)
    }

    @Test
    fun `cash defaults to visible whenever the flag is not a clear no`() {
        // Hiding cash re-bases every printed percentage, so it is the reading
        // that must be explicit; anything ambiguous shows cash.
        assertEquals(true, vizConfigDecode("TREEMAP|AUTO|AUTO|1|-").showCash)
        assertEquals(true, vizConfigDecode("TREEMAP|AUTO|AUTO|?|-").showCash)
        assertEquals(false, vizConfigDecode("TREEMAP|AUTO|AUTO|0|-").showCash)
    }

    @Test
    fun `a focus key containing the separator is refused rather than corrupting the record`() {
        val encoded = vizConfigEncode(BtVizConfig(form = BtVizForm.RING, focusKey = "a|b"))
        assertEquals(BtVizForm.RING, vizConfigDecode(encoded).form)
        assertNull(vizConfigDecode(encoded).focusKey)
    }

    @Test
    fun `an empty focus key reads as no focus`() {
        assertNull(vizConfigDecode("RING|AUTO|AUTO|1|-").focusKey)
        assertNull(vizConfigDecode("RING|AUTO|AUTO|1|").focusKey)
    }
}
