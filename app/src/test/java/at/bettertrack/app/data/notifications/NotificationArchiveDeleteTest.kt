package at.bettertrack.app.data.notifications

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Notifications-v3 archive/delete feature (#437): view
 * filtering, the archive-implies-read mapping, the unread-ACTIVE badge recompute,
 * single- and bulk-op state transitions, and Task-C deep-link resolution for the
 * three platform share/activity types. No Android dependencies.
 */
class NotificationArchiveDeleteTest {

    private fun notif(
        id: String,
        read: Boolean = false,
        archived: Boolean = false,
        created: Long = 1_000L,
        type: String = "friend.request",
    ) = AppNotification(
        id = id,
        type = type,
        title = "t",
        body = "b",
        readAtMs = if (read) 500L else null,
        archivedAtMs = if (archived) 600L else null,
        createdAtMs = created,
    )

    private val now = 9_000L

    // ── View filtering ───────────────────────────────────────────────────────

    @Test fun `active view excludes archived rows`() {
        val list = listOf(notif("a"), notif("b", archived = true, read = true))
        assertEquals(listOf("a"), list.forView(NotifView.Active).map { it.id })
    }

    @Test fun `archived view keeps only archived rows`() {
        val list = listOf(notif("a"), notif("b", archived = true, read = true))
        assertEquals(listOf("b"), list.forView(NotifView.Archived).map { it.id })
    }

    @Test fun `all view keeps everything`() {
        val list = listOf(notif("a"), notif("b", archived = true, read = true))
        assertEquals(listOf("a", "b"), list.forView(NotifView.All).map { it.id })
    }

    // ── Archive implies read ─────────────────────────────────────────────────

    @Test fun `archiving an unread row marks it read AND archived`() {
        val out = notif("a", read = false).asArchived(now)
        assertTrue(out.isArchived)
        assertFalse(out.isUnread)
        assertEquals(now, out.archivedAtMs)
        assertEquals(now, out.readAtMs)
    }

    @Test fun `archiving keeps an already-read row's original read timestamp`() {
        val out = notif("a", read = true).asArchived(now)
        assertEquals(500L, out.readAtMs) // untouched
        assertEquals(now, out.archivedAtMs)
    }

    @Test fun `archiving an already-archived row is idempotent on the timestamp`() {
        val out = notif("a", read = true, archived = true).asArchived(now)
        assertEquals(600L, out.archivedAtMs) // kept, not re-stamped
    }

    @Test fun `unarchive clears archive but never touches read state`() {
        val out = notif("a", read = true, archived = true).asUnarchived()
        assertFalse(out.isArchived)
        assertEquals(500L, out.readAtMs)
    }

    // ── Badge recompute (unread + ACTIVE only) ───────────────────────────────

    @Test fun `active-unread badge ignores read and archived rows`() {
        val list = listOf(
            notif("u1", read = false),                         // counts
            notif("u2", read = false),                         // counts
            notif("r1", read = true),                          // read → no
            notif("ar", read = true, archived = true),         // archived → no
        )
        assertEquals(2, list.activeUnreadCount())
    }

    // ── Single-item transitions per view ─────────────────────────────────────

    @Test fun `archive in Active view removes the row from the list`() {
        val list = listOf(notif("a"), notif("b"))
        val out = list.archiveInView("a", NotifView.Active, now)
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test fun `archive in All view keeps the row but flips it to archived+read`() {
        val list = listOf(notif("a", read = false))
        val out = list.archiveInView("a", NotifView.All, now)
        assertEquals(1, out.size)
        assertTrue(out[0].isArchived)
        assertFalse(out[0].isUnread)
    }

    @Test fun `unarchive in Archived view removes the row`() {
        val list = listOf(notif("a", read = true, archived = true))
        val out = list.unarchiveInView("a", NotifView.Archived)
        assertTrue(out.isEmpty())
    }

    @Test fun `unarchive in All view flips the row back to active`() {
        val list = listOf(notif("a", read = true, archived = true))
        val out = list.unarchiveInView("a", NotifView.All)
        assertFalse(out[0].isArchived)
    }

    // ── Snackbar-Undo restore (the archived row left Active — re-insert it) ──

    @Test fun `undo restore re-inserts into Active at its newest-first position`() {
        val gone = notif("mid", read = true, archived = true, created = 2_000L)
        val list = listOf(notif("new", created = 3_000L), notif("old", created = 1_000L))
        val out = list.unarchiveInView("mid", NotifView.Active, restore = gone)
        assertEquals(listOf("new", "mid", "old"), out.map { it.id })
        assertFalse(out[1].isArchived)
    }

    @Test fun `undo restore appends when it is the oldest row`() {
        val gone = notif("old", read = true, archived = true, created = 100L)
        val list = listOf(notif("new", created = 3_000L))
        val out = list.unarchiveInView("old", NotifView.Active, restore = gone)
        assertEquals(listOf("new", "old"), out.map { it.id })
    }

    @Test fun `undo restore re-inserts into All when the row is absent`() {
        val gone = notif("x", read = true, archived = true, created = 2_000L)
        val out = emptyList<AppNotification>().unarchiveInView("x", NotifView.All, restore = gone)
        assertEquals(listOf("x"), out.map { it.id })
        assertFalse(out[0].isArchived)
    }

    @Test fun `restore is ignored when the row is still present`() {
        val present = notif("a", read = true, archived = true)
        val out = listOf(present).unarchiveInView("a", NotifView.All, restore = present)
        assertEquals(1, out.size)
        assertFalse(out[0].isArchived)
    }

    @Test fun `unarchive in Active without restore stays a no-op`() {
        val list = listOf(notif("a"))
        assertEquals(list, list.unarchiveInView("ghost", NotifView.Active))
    }

    @Test fun `delete removes the row from any view`() {
        val list = listOf(notif("a"), notif("b"))
        assertEquals(listOf("a"), list.deleteInView("b").map { it.id })
    }

    // ── Bulk transitions ─────────────────────────────────────────────────────

    @Test fun `archive-all-read in Active drops read rows, keeps unread`() {
        val list = listOf(
            notif("u", read = false),
            notif("r1", read = true),
            notif("r2", read = true),
        )
        val out = list.archiveAllReadInView(NotifView.Active, now)
        assertEquals(listOf("u"), out.map { it.id })
    }

    @Test fun `archive-all-read in All flips read-active rows to archived`() {
        val list = listOf(
            notif("u", read = false),
            notif("r", read = true),
            notif("ar", read = true, archived = true),
        )
        val out = list.archiveAllReadInView(NotifView.All, now)
        assertFalse(out.first { it.id == "u" }.isArchived)   // unread untouched
        assertTrue(out.first { it.id == "r" }.isArchived)    // read-active archived
        assertTrue(out.first { it.id == "ar" }.isArchived)   // already archived stays
    }

    @Test fun `delete-all-archived removes archived rows only`() {
        val list = listOf(notif("a"), notif("b", read = true, archived = true))
        assertEquals(listOf("a"), list.deleteArchivedInView().map { it.id })
    }

    @Test fun `delete-all empties the list`() {
        val list = listOf(notif("a"), notif("b", archived = true, read = true))
        assertTrue(list.deleteAllInView().isEmpty())
    }

    // ── Task C — new kinds + deep links ──────────────────────────────────────

    @Test fun `the three platform types map to their own kinds`() {
        assertEquals(NotifKind.FriendActivity, NotifKind.fromType("friend.activity"))
        assertEquals(NotifKind.WatchlistShared, NotifKind.fromType("watchlist.shared"))
        assertEquals(NotifKind.ConglomerateShared, NotifKind.fromType("conglomerate.shared"))
    }

    @Test fun `the new kinds are not user-configurable in the settings grid`() {
        // serverModeled=false keeps them out of serverMatrixForPatch / the grid.
        assertFalse(NotifKind.FriendActivity.serverModeled)
        assertFalse(NotifKind.WatchlistShared.serverModeled)
        assertFalse(NotifKind.ConglomerateShared.serverModeled)
    }

    @Test fun `friend activity with id+username deep-links to the friend overview`() {
        val payload = buildJsonObject {
            put("friendId", "u-1")
            put("friendUsername", "alice")
        }
        assertEquals(NotifDeepLink.FriendOverview("u-1", "alice"), resolveDeepLink("friend.activity", payload))
    }

    @Test fun `friend activity without a usable identity falls back to Social`() {
        val onlyId = buildJsonObject { put("friendId", "u-1") } // no username
        assertEquals(NotifDeepLink.Social, resolveDeepLink("friend.activity", onlyId))
        assertEquals(NotifDeepLink.Social, resolveDeepLink("friend.activity", null))
    }

    @Test fun `conglomerate shared opens the shared conglomerate when an id is present`() {
        val payload = buildJsonObject { put("conglomerateId", "cg-9") }
        assertEquals(NotifDeepLink.SharedConglomerate("cg-9"), resolveDeepLink("conglomerate.shared", payload))
        assertEquals(NotifDeepLink.Social, resolveDeepLink("conglomerate.shared", null))
    }

    @Test fun `watchlist shared routes to the Social shared tab`() {
        // The read view needs the owner name (not reliably in a payload) → Social.
        assertEquals(NotifDeepLink.Social, resolveDeepLink("watchlist.shared", buildJsonObject { put("watchlistId", "wl-1") }))
    }

    @Test fun `unknown deep-link payloads never crash`() {
        assertNull(resolveDeepLink("totally.unknown", null))
    }
}
