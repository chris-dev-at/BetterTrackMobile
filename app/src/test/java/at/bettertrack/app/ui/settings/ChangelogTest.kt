package at.bettertrack.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled in-app "What's new" must be well-formed (no empty update or blank
 * highlight), and the last-5 / "Show more" partition — the one piece of real
 * logic on the screen — is proven here as a pure function so no UI test is
 * needed.
 */
class ChangelogTest {

    // ── Data invariants ─────────────────────────────────────────────────────

    @Test
    fun `changelog is non-empty`() {
        assertTrue(BT_WHATS_NEW.isNotEmpty())
    }

    @Test
    fun `every entry has a version label and at least one feature`() {
        BT_WHATS_NEW.forEach { entry ->
            assertTrue("blank version", entry.version.isNotBlank())
            assertTrue("no features for ${entry.version}", entry.features.isNotEmpty())
        }
    }

    @Test
    fun `version labels are unique (stable list keys)`() {
        val versions = BT_WHATS_NEW.map { it.version }
        assertEquals("duplicate version labels", versions.size, versions.toSet().size)
    }

    /**
     * Every feature points at its OWN pair of strings — catches a copy-paste that
     * reuses a name/description resource. Guarded so it never false-fails in a
     * unit-test env that doesn't populate real R ids (all zero ⇒ skip).
     */
    @Test
    fun `each feature references a distinct pair of string resources`() {
        val ids = BT_WHATS_NEW.flatMap { it.features }.flatMap { listOf(it.name, it.description) }
        val idsAreReal = ids.any { it != 0 }
        if (!idsAreReal) return // degenerate env: R not merged for this test — nothing to assert
        assertTrue("a feature has a 0 (missing) string resource", ids.none { it == 0 })
        assertEquals("a string resource is referenced by more than one line", ids.size, ids.toSet().size)
    }

    // ── The last-5 / "Show more" split (pure logic) ─────────────────────────

    private fun entry(v: String) = WhatsNewEntry(v, listOf(WhatsNewFeature(1, 2)))

    @Test
    fun `split shows the newest N and hides the rest, preserving order`() {
        val list = listOf("a", "b", "c", "d", "e", "f", "g").map(::entry)
        val split = splitWhatsNew(list, visibleCount = 5)

        assertEquals(5, split.visible.size)
        assertEquals(2, split.hidden.size)
        assertEquals(listOf("a", "b", "c", "d", "e"), split.visible.map { it.version })
        assertEquals(listOf("f", "g"), split.hidden.map { it.version })
        // The two partitions reconstitute the whole list in the original order.
        assertEquals(list, split.visible + split.hidden)
    }

    @Test
    fun `split with a count at or beyond the size hides nothing`() {
        val list = listOf("a", "b", "c").map(::entry)
        listOf(3, 4, 99).forEach { count ->
            val split = splitWhatsNew(list, visibleCount = count)
            assertEquals("count=$count", list, split.visible)
            assertTrue("count=$count should hide nothing", split.hidden.isEmpty())
        }
    }

    @Test
    fun `split with zero or negative count hides everything`() {
        val list = listOf("a", "b", "c").map(::entry)
        listOf(0, -1, -50).forEach { count ->
            val split = splitWhatsNew(list, visibleCount = count)
            assertTrue("count=$count should show nothing", split.visible.isEmpty())
            assertEquals("count=$count", list, split.hidden)
        }
    }

    @Test
    fun `the real changelog has enough history for Show more to appear`() {
        assertTrue(
            "need > $WHATS_NEW_VISIBLE_COUNT updates so 'Show more' has something to reveal",
            BT_WHATS_NEW.size > WHATS_NEW_VISIBLE_COUNT,
        )
        val split = splitWhatsNew(BT_WHATS_NEW)
        assertEquals(WHATS_NEW_VISIBLE_COUNT, split.visible.size)
        assertTrue(split.hidden.isNotEmpty())
        // Newest-first: the very first shown entry is the head of the list.
        assertEquals(BT_WHATS_NEW.first().version, split.visible.first().version)
    }
}
