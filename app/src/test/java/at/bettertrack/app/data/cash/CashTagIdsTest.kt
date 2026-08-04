package at.bettertrack.app.data.cash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cached-tag-set codec. Small surface, outsized blast radius: it runs on
 * every cash movement of every ledger render, and the empty case is the default
 * state of most rows.
 */
class CashTagIdsTest {

    @Test
    fun `empty list encodes to the empty string`() {
        assertEquals("", encodeTagIds(emptyList()))
    }

    @Test
    fun `THE classic bug - the empty string decodes to an EMPTY list, not one blank id`() {
        // Kotlin's own "".split(",") answers [""], which would paint a phantom
        // chip with a blank id on every untagged movement.
        assertEquals(listOf(""), "".split(","))
        assertTrue(decodeTagIds("").isEmpty())
    }

    @Test
    fun `a blank-but-not-empty column value also decodes to nothing`() {
        assertTrue(decodeTagIds("   ").isEmpty())
    }

    @Test
    fun `single id round trips`() {
        val ids = listOf("11111111-1111-1111-1111-111111111111")
        assertEquals("11111111-1111-1111-1111-111111111111", encodeTagIds(ids))
        assertEquals(ids, decodeTagIds(encodeTagIds(ids)))
    }

    @Test
    fun `many ids round trip in order`() {
        val ids = listOf(
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
            "33333333-3333-3333-3333-333333333333",
        )
        assertEquals(
            "11111111-1111-1111-1111-111111111111," +
                "22222222-2222-2222-2222-222222222222," +
                "33333333-3333-3333-3333-333333333333",
            encodeTagIds(ids),
        )
        assertEquals(ids, decodeTagIds(encodeTagIds(ids)))
    }

    @Test
    fun `blank entries are dropped on the way in, so they can never come back out`() {
        assertEquals("a,b", encodeTagIds(listOf("a", "", "  ", "b")))
        assertEquals(listOf("a", "b"), decodeTagIds("a,b"))
    }

    @Test
    fun `a malformed column with stray separators still decodes cleanly`() {
        // Defensive: nothing writes this shape, but a half-migrated row must not
        // turn into blank chips.
        assertEquals(listOf("a", "b"), decodeTagIds(",a,,b,"))
    }

    @Test
    fun `surrounding whitespace is trimmed on decode`() {
        assertEquals(listOf("a", "b"), decodeTagIds(" a , b "))
    }
}
