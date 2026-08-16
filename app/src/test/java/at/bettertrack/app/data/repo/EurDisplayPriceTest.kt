package at.bettertrack.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [eurDisplayPrice] — the EUR-identity read behind every €-price display.
 *
 * Found on-device (widget QA 2026-08-16): the server converts non-EUR quotes
 * and omits `eurPrice` for quotes already in euros, so BMW.DE and BTC-EUR
 * rendered "—" beside a live day-percent in the watchlist rows. The rule under
 * test: the server's figure always wins; a EUR-denominated quote is its own
 * €-price; anything else without a server conversion stays unpriced — the
 * client NEVER converts.
 */
class EurDisplayPriceTest {

    @Test
    fun `server-converted price wins when present`() {
        // A USD quote with the server's conversion: the conversion is the answer.
        assertEquals(444.48, eurDisplayPrice(444.48, 514.39, "USD")!!, 0.0)
        // Even for a EUR quote, a server figure (if ever sent) is authoritative.
        assertEquals(88.10, eurDisplayPrice(88.10, 88.10, "EUR")!!, 0.0)
    }

    @Test
    fun `a euro quote is its own euro price`() {
        // BMW.DE on XETRA: no server conversion, native quote in EUR.
        assertEquals(88.10, eurDisplayPrice(null, 88.10, "EUR")!!, 0.0)
        // Case-insensitive: the wire says "eur" somewhere, someday.
        assertEquals(63_240.0, eurDisplayPrice(null, 63_240.0, "eur")!!, 0.0)
    }

    @Test
    fun `a non-euro quote without a conversion stays unpriced`() {
        // The client must not invent an FX rate — "—" is the honest rendering.
        assertNull(eurDisplayPrice(null, 514.39, "USD"))
        assertNull(eurDisplayPrice(null, 0.25, "AUD"))
    }

    @Test
    fun `no quote at all is no price`() {
        assertNull(eurDisplayPrice(null, null, "EUR"))
        assertNull(eurDisplayPrice(null, null, "USD"))
    }
}
