package at.bettertrack.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [assetTwin] is what lets one caller name a window and be served either by the
 * portfolio endpoint's per-asset overlay (one request) or by a fan-out of
 * `GET /assets/{id}/history` reads, without branching. That only holds while the
 * mapping is total and names the SAME span on both endpoints — so it is asserted
 * against the wire values rather than trusted to a `when`.
 */
class HistoryRangeTwinTest {

    @Test
    fun `every portfolio range has an asset range with the identical wire value`() {
        HistoryRange.entries.forEach { range ->
            assertEquals(
                "${range.name} maps onto a different window",
                range.wire,
                range.assetTwin.wire,
            )
        }
    }

    @Test
    fun `asset history enumerates every portfolio range and one more`() {
        // The superset relationship is the reason the mapping can be total. 3M is
        // the one asset range portfolio history does not serve; nothing may map
        // onto it, because no portfolio call could answer it.
        val portfolioWires = HistoryRange.entries.map { it.wire }.toSet()
        val assetWires = AssetRange.entries.map { it.wire }.toSet()

        assertEquals(emptySet<String>(), portfolioWires - assetWires)
        assertEquals(setOf("3M", "5Y"), assetWires - portfolioWires)
        assertNotNull("3M must stay unreachable from a portfolio range", AssetRange.M3)
    }
}
