package at.bettertrack.app.ui.chat

import at.bettertrack.app.R
import at.bettertrack.app.data.repo.ShareChipKind
import at.bettertrack.app.ui.format.BtTimeAgo
import at.bettertrack.app.ui.format.btTimeAgo
import at.bettertrack.app.ui.format.btTimeAgoRes
import at.bettertrack.app.ui.format.btTimeAgoValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the chat surface (Step 15, §6.10).
 *
 * Both halves changed shape in the 2026-09-01 QA round (defect #8): the row's
 * preview sentence and its timestamp used to be assembled from hardcoded English
 * in a `Context`-free layer, which is exactly why a German phone read `You: …`
 * and `2w`. What is pinned here now is the STRUCTURE that made translation
 * possible — which resource each piece resolves to, and where the bucket
 * boundaries fall — rather than the English words themselves. The words are
 * `StringParityTest`'s business.
 */
class ChatLogicTest {

    // ── Conversation-row preview ─────────────────────────────────────────────
    //
    // `chatPreviewText` itself is @Composable (it reads string resources), so the
    // testable part is the kind→resource mapping plus the two rules the sentence
    // obeys: the chip outranks the body, and only a KIND is ever named.

    @Test
    fun `every chip kind previews as its own translated phrase`() {
        assertEquals(R.string.bt_chat_preview_kind_asset, chatPreviewKindRes(ShareChipKind.Asset))
        assertEquals(R.string.bt_chat_preview_kind_portfolio, chatPreviewKindRes(ShareChipKind.Portfolio))
        assertEquals(R.string.bt_chat_preview_kind_watchlist, chatPreviewKindRes(ShareChipKind.Watchlist))
        assertEquals(R.string.bt_chat_preview_kind_conglomerate, chatPreviewKindRes(ShareChipKind.Conglomerate))
        assertEquals(R.string.bt_chat_preview_kind_unknown, chatPreviewKindRes(ShareChipKind.Unknown))
    }

    @Test
    fun `an unmodelled wire kind previews as the neutral phrase`() {
        // A future `idea` chip must not fall through to nothing, and must not be
        // mistaken for one of the four kinds the app can open.
        assertEquals(
            R.string.bt_chat_preview_kind_unknown,
            chatPreviewKindRes(ShareChipKind.fromWire("idea")),
        )
    }

    @Test
    fun `no chip kind maps to the item's own name`() {
        // The mapping is total over the enum and keyed on the KIND only — there is
        // no branch that could ever reach a shared item's title, which is what
        // keeps the list from naming something that was not shared with the reader.
        ShareChipKind.entries.forEach { kind ->
            assertTrue("$kind must resolve to a phrase resource", chatPreviewKindRes(kind) != 0)
        }
    }

    // ── Relative time ────────────────────────────────────────────────────────
    //
    // `btTimeAgo` takes `nowMs`, which is the whole reason these boundaries can be
    // asserted at all — the function this replaced read the clock internally and
    // its tests had to aim at a moving target.

    private val now = 1_756_000_000_000L

    private fun ago(minutes: Long): Long = now - minutes * 60_000L

    @Test
    fun `under a minute is now`() {
        assertEquals(BtTimeAgo.Now, btTimeAgo(ago(0), now))
        assertEquals(BtTimeAgo.Now, btTimeAgo(now - 59_000L, now))
    }

    @Test
    fun `minutes, hours, days and weeks each get their own bucket`() {
        assertEquals(BtTimeAgo.Minutes(5), btTimeAgo(ago(5), now))
        assertEquals(BtTimeAgo.Hours(3), btTimeAgo(ago(3 * 60), now))
        assertEquals(BtTimeAgo.Days(2), btTimeAgo(ago(2 * 24 * 60), now))
        assertEquals(BtTimeAgo.Weeks(2), btTimeAgo(ago(2 * 7 * 24 * 60 + 60), now))
    }

    @Test
    fun `a timestamp from the future clamps to now rather than going negative`() {
        // Clock skew between phone and server is routine; "-3 Min." is not.
        assertEquals(BtTimeAgo.Now, btTimeAgo(now + 10 * 60_000L, now))
    }

    @Test
    fun `each bucket names a translated resource, and only Now takes no count`() {
        assertEquals(R.string.bt_time_ago_now, btTimeAgoRes(BtTimeAgo.Now))
        assertEquals(R.string.bt_time_ago_minutes, btTimeAgoRes(BtTimeAgo.Minutes(5)))
        assertEquals(R.string.bt_time_ago_hours, btTimeAgoRes(BtTimeAgo.Hours(3)))
        assertEquals(R.string.bt_time_ago_days, btTimeAgoRes(BtTimeAgo.Days(2)))
        assertEquals(R.string.bt_time_ago_weeks, btTimeAgoRes(BtTimeAgo.Weeks(7)))

        assertNull(btTimeAgoValue(BtTimeAgo.Now))
        assertEquals(5, btTimeAgoValue(BtTimeAgo.Minutes(5)))
        assertEquals(7, btTimeAgoValue(BtTimeAgo.Weeks(7)))
    }
}
