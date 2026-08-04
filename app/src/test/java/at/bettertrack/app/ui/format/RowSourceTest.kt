package at.bettertrack.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `source` is regex-validated server-side, not an enum, so slugs the app has
 * never heard of are NORMAL traffic. These tests pin the structural parse and
 * the forward-compatible fallback.
 */
class RowSourceTest {

    @Test
    fun `manual is the default and gets no badge`() {
        assertEquals(RowSource.Manual, parseRowSource("manual"))
        assertEquals(RowSource.Manual, parseRowSource(null))
        assertEquals(RowSource.Manual, parseRowSource(""))
        assertEquals(RowSource.Manual, parseRowSource("  "))
        assertFalse(parseRowSource("manual").isBadgeWorthy())
    }

    @Test
    fun `standing order is recognised`() {
        assertEquals(RowSource.StandingOrder, parseRowSource("standing-order"))
        assertTrue(parseRowSource("standing-order").isBadgeWorthy())
    }

    @Test
    fun `import and sync slugs are extracted`() {
        assertEquals(RowSource.Import("trade_republic"), parseRowSource("import:trade_republic"))
        assertEquals(RowSource.Sync("mirrorchain"), parseRowSource("sync:mirrorchain"))
    }

    @Test
    fun `an unknown token survives verbatim`() {
        // A future platform value must render as itself, never crash or vanish.
        assertEquals(RowSource.Unknown("carrier-pigeon"), parseRowSource("carrier-pigeon"))
        assertTrue(parseRowSource("carrier-pigeon").isBadgeWorthy())
    }

    @Test
    fun `a prefix with no slug is not mistaken for a real source`() {
        assertEquals(RowSource.Unknown("import:"), parseRowSource("import:"))
        assertEquals(RowSource.Unknown("sync:"), parseRowSource("sync:"))
    }

    @Test
    fun `slugs are title-cased for display`() {
        assertEquals("Trade Republic", prettySourceSlug("trade_republic"))
        assertEquals("George", prettySourceSlug("george"))
        assertEquals("Mirrorchain", prettySourceSlug("mirrorchain"))
        assertEquals("Some Broker", prettySourceSlug("some-broker"))
    }

    @Test
    fun `surrounding whitespace does not defeat the parse`() {
        assertEquals(RowSource.StandingOrder, parseRowSource(" standing-order "))
    }
}
