package at.bettertrack.app.ui.social

import at.bettertrack.app.data.repo.MyShared
import at.bettertrack.app.data.repo.MySharedItem
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.ShareableKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the People tab's **My shares** segment badge counts.
 *
 * ## Why this file exists
 *
 * The badge was the integer literal `0`, passed straight into the segment, for as
 * long as the segment has existed (found by the 2026-08-09 reachability audit).
 * That is the worst shape a bug can take: it never threw, never logged, and read
 * as a deliberate "nothing here" to anyone glancing at the tab — while the very
 * next screen listed the things the user was in fact sharing with the world.
 *
 * A hard-coded number cannot be caught by a compiler or by the two neighbouring
 * badges being right, so the wiring is a named function and these are its guard.
 * The assertions below all fail against a constant.
 */
class MySharesBadgeTest {

    private fun item(id: String, audience: ShareAudience) = MySharedItem(
        id = id,
        kind = ShareableKind.Portfolio,
        name = "P$id",
        audience = audience,
        friendCount = 0,
        count = 0,
    )

    @Test
    fun `an unloaded list badges nothing`() {
        // Null is "not loaded yet", not "you share nothing". The segment renders a
        // badge only above zero, so this shows none rather than flashing a wrong
        // number and correcting it a moment later.
        assertEquals(0, mySharesBadgeCount(null))
    }

    @Test
    fun `an empty list badges nothing`() {
        assertEquals(0, mySharesBadgeCount(MyShared(items = emptyList())))
    }

    @Test
    fun `private items are shareABLE, not shared`() {
        // The My-shares list is everything the user COULD share, private items
        // included. Badging `items.size` would report exposure that does not
        // exist — the opposite failure to the hard-coded zero, and just as wrong.
        val allPrivate = MyShared(
            items = listOf(
                item("a", ShareAudience.Private),
                item("b", ShareAudience.Private),
            ),
        )
        assertEquals(0, mySharesBadgeCount(allPrivate))
    }

    @Test
    fun `every non-private rung counts, and only the non-private ones`() {
        // One item on each rung of the ladder, plus a private one that must not
        // be counted. Written over `ShareAudience.entries` so a rung added later
        // is included by construction rather than by someone remembering to.
        val shared = ShareAudience.entries.filter { it != ShareAudience.Private }
        val items = shared.mapIndexed { i, audience -> item("s$i", audience) } +
            item("private", ShareAudience.Private)
        assertEquals(shared.size, mySharesBadgeCount(MyShared(items = items)))
    }

    @Test
    fun `the badge is the repository's own count`() {
        // The badge must not compute a second opinion: it reports exactly what
        // `MyShared.sharedCount` says, which is what the My-shares list itself is
        // built from. Two definitions of "shared" is how the tab and the screen
        // start disagreeing.
        val mixed = MyShared(
            items = listOf(
                item("a", ShareAudience.AllFriends),
                item("b", ShareAudience.Private),
                item("c", ShareAudience.PublicLink),
            ),
        )
        assertEquals(mixed.sharedCount, mySharesBadgeCount(mixed))
        assertEquals(2, mySharesBadgeCount(mixed))
    }
}
