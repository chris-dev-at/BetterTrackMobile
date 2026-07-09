package at.bettertrack.app.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled in-app changelog (item 4) must be well-formed so the "New
 * features" screen never renders an empty/blank entry, and every release has at
 * least one highlight.
 */
class ChangelogTest {

    @Test
    fun `changelog is non-empty`() {
        assertTrue(BT_CHANGELOG.isNotEmpty())
    }

    @Test
    fun `every entry has a version label and at least one highlight`() {
        BT_CHANGELOG.forEach { entry ->
            assertTrue("blank version", entry.version.isNotBlank())
            assertTrue("no highlights for ${entry.version}", entry.highlights.isNotEmpty())
            assertTrue(
                "blank highlight in ${entry.version}",
                entry.highlights.all { it.isNotBlank() },
            )
        }
    }

    @Test
    fun `version labels are unique (stable list keys)`() {
        val versions = BT_CHANGELOG.map { it.version }
        assertTrue("duplicate version labels", versions.size == versions.toSet().size)
    }
}
