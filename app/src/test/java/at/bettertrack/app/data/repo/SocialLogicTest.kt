package at.bettertrack.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Social v2 / Sharing-v3 data layer: the audience + kind
 * wire mapping (the unified `/social/audience` model), the "shared with me"
 * per-person grouping, and the my-shared counts. No Android dependencies.
 */
class SocialLogicTest {

    // ── ShareAudience wire mapping ───────────────────────────────────────────

    @Test fun `every audience round-trips through its wire value`() {
        ShareAudience.entries.forEach { a ->
            assertEquals(a, ShareAudience.fromWire(a.wire))
        }
    }

    @Test fun `audience wire strings match the contract`() {
        assertEquals("private", ShareAudience.Private.wire)
        assertEquals("specific_friends", ShareAudience.SpecificFriends.wire)
        assertEquals("all_friends", ShareAudience.AllFriends.wire)
        assertEquals("public_link", ShareAudience.PublicLink.wire)
    }

    @Test fun `unknown or null audience falls back to private`() {
        assertEquals(ShareAudience.Private, ShareAudience.fromWire("friends"))
        assertEquals(ShareAudience.Private, ShareAudience.fromWire(null))
        assertEquals(ShareAudience.Private, ShareAudience.fromWire("nonsense"))
    }

    @Test fun `every kind round-trips through its wire value`() {
        ShareableKind.entries.forEach { k -> assertEquals(k, ShareableKind.fromWire(k.wire)) }
        assertEquals("portfolio", ShareableKind.Portfolio.wire)
        assertEquals("conglomerate", ShareableKind.Conglomerate.wire)
        assertEquals("watchlist", ShareableKind.Watchlist.wire)
    }

    // ── SharedWithMe.groupByPerson ───────────────────────────────────────────

    private fun port(id: String, owner: String, name: String) =
        SharedPortfolioSummary(id, name, ownerId = owner, ownerName = owner, totalValueEur = 0.0, activityAlertsEnabled = false)

    private fun cong(id: String, owner: String) =
        SharedConglomerateSummary(id, "C$id", ownerId = owner, ownerName = owner, status = "active", positionCount = 1, activityAlertsEnabled = false)

    private fun watch(id: String, owner: String) =
        SharedWatchlistSummary(id, "W$id", ownerId = owner, ownerName = owner, itemCount = 3, activityAlertsEnabled = false)

    @Test fun `groups shared items by their owner`() {
        val shared = SharedWithMe(
            portfolios = listOf(port("p1", "alice", "Growth"), port("p2", "alice", "Value"), port("p3", "bob", "Bob PF")),
            conglomerates = listOf(cong("c1", "carl")),
            watchlists = listOf(watch("w1", "bob")),
        )
        val people = shared.groupByPerson()

        // Three distinct people.
        assertEquals(3, people.size)
        val alice = people.first { it.ownerId == "alice" }
        val bob = people.first { it.ownerId == "bob" }
        val carl = people.first { it.ownerId == "carl" }

        assertEquals(2, alice.portfolios.size)
        assertEquals(0, alice.conglomerates.size)
        assertEquals(2, alice.count)

        assertEquals(1, bob.portfolios.size)
        assertEquals(1, bob.watchlists.size)
        assertEquals(2, bob.count)

        assertEquals(1, carl.conglomerates.size)
        assertEquals(1, carl.count)
    }

    @Test fun `people are ordered by most-shared then name`() {
        val shared = SharedWithMe(
            portfolios = listOf(port("p1", "bob", "x"), port("p2", "alice", "y")),
            conglomerates = emptyList(),
            watchlists = listOf(watch("w1", "alice")),
        )
        // alice: 2 items, bob: 1 item → alice first.
        val order = shared.groupByPerson().map { it.ownerId }
        assertEquals(listOf("alice", "bob"), order)
    }

    @Test fun `ties on count break alphabetically by owner name`() {
        val shared = SharedWithMe(
            portfolios = listOf(port("p1", "bob", "x"), port("p2", "alice", "y")),
            conglomerates = emptyList(),
            watchlists = emptyList(),
        )
        // Both have 1 → alice before bob.
        assertEquals(listOf("alice", "bob"), shared.groupByPerson().map { it.ownerId })
    }

    @Test fun `empty shared yields no people`() {
        val shared = SharedWithMe(emptyList(), emptyList(), emptyList())
        assertTrue(shared.isEmpty)
        assertEquals(0, shared.count)
        assertTrue(shared.groupByPerson().isEmpty())
    }

    // ── MyShared counts ──────────────────────────────────────────────────────

    private fun mine(name: String, audience: ShareAudience) =
        MySharedItem(id = name, kind = ShareableKind.Portfolio, name = name, audience = audience, friendCount = 0, count = 0)

    @Test fun `sharedCount ignores private items`() {
        val my = MyShared(
            listOf(
                mine("a", ShareAudience.Private),
                mine("b", ShareAudience.AllFriends),
                mine("c", ShareAudience.SpecificFriends),
                mine("d", ShareAudience.PublicLink),
                mine("e", ShareAudience.Private),
            ),
        )
        assertEquals(3, my.sharedCount)
        assertEquals(5, my.items.size)
    }

    @Test fun `sharedWithMe count sums all three kinds`() {
        val shared = SharedWithMe(
            portfolios = listOf(port("p1", "a", "x")),
            conglomerates = listOf(cong("c1", "a")),
            watchlists = listOf(watch("w1", "a"), watch("w2", "a")),
        )
        assertEquals(4, shared.count)
        assertFalse(shared.isEmpty)
    }
}
