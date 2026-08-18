package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.api.dto.CashSystemTagKeys
import at.bettertrack.app.data.db.CashTagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner ask 2026-08-17 (item 10): a renamed built-in tag must be explainable and
 * restorable. Both halves hang off `systemKey`, never off the visible name — so
 * these tests pin the mapping's TOTALITY over [CashSystemTagKeys.ALL] and the
 * "already default ⇒ nothing to restore" rule.
 */
class CashSystemTagDefaultsTest {

    private fun tag(
        id: String,
        name: String,
        system: Boolean = true,
        systemKey: String? = null,
        color: String = "#64748b",
    ) = CashTagEntity(id = id, name = name, color = color, system = system, systemKey = systemKey)

    @Test
    fun `every seeded system key has a default name`() {
        CashSystemTagKeys.ALL.forEach { key ->
            assertNotNull("no default name for $key", cashSystemTagDefaultName(key))
            assertTrue("blank default name for $key", cashSystemTagDefaultName(key)!!.isNotBlank())
        }
        assertEquals(9, CashSystemTagKeys.ALL.size)
    }

    @Test
    fun `every seeded system key has a description`() {
        CashSystemTagKeys.ALL.forEach { key ->
            assertNotNull("no description for $key", cashSystemTagDescriptionRes(key))
        }
    }

    @Test
    fun `a user tag and an unknown key have neither`() {
        assertNull(cashSystemTagDefaultName(null))
        assertNull(cashSystemTagDefaultName("some_tenth_key_this_build_never_heard_of"))
        assertNull(cashSystemTagDescriptionRes(null))
        assertNull(cashSystemTagDescriptionRes("some_tenth_key_this_build_never_heard_of"))
    }

    @Test
    fun `default names are distinct so a restore cannot collide with itself`() {
        val names = CashSystemTagKeys.ALL.map { cashSystemTagDefaultName(it)!!.lowercase() }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `at-default is trim and case tolerant`() {
        assertTrue(cashSystemTagIsAtDefault(tag("1", "Fees", systemKey = CashSystemTagKeys.FEES)))
        assertTrue(cashSystemTagIsAtDefault(tag("1", "  fees ", systemKey = CashSystemTagKeys.FEES)))
        assertTrue(cashSystemTagIsAtDefault(tag("1", "FEES", systemKey = CashSystemTagKeys.FEES)))
        assertFalse(cashSystemTagIsAtDefault(tag("1", "Gebühren", systemKey = CashSystemTagKeys.FEES)))
    }

    @Test
    fun `an unknown key reports as at-default so no invented restore is offered`() {
        assertTrue(cashSystemTagIsAtDefault(tag("1", "Whatever", systemKey = "tenth_key")))
        assertTrue(cashSystemTagIsAtDefault(tag("1", "Groceries", system = false, systemKey = null)))
    }

    @Test
    fun `restore-all excludes already-default rows, user tags and unknown keys`() {
        val tags = listOf(
            tag("u1", "Groceries", system = false, systemKey = null),
            tag("s1", "Fees", systemKey = CashSystemTagKeys.FEES),
            tag("s2", "Gebühren aufs Depot", systemKey = CashSystemTagKeys.TAX),
            tag("s3", "Steuer", systemKey = CashSystemTagKeys.TAX),
            tag("s4", "Weird", systemKey = "tenth_key"),
            tag("s5", "  transfer  ", systemKey = CashSystemTagKeys.TRANSFER),
        )
        val restore = cashSystemTagsToRestore(tags)
        assertEquals(listOf("s2", "s3"), restore.map { it.first.id })
        assertEquals(listOf("Tax", "Tax"), restore.map { it.second })
    }

    @Test
    fun `restore-all is empty on an untouched catalog`() {
        val untouched = CashSystemTagKeys.ALL.mapIndexed { i, key ->
            tag("s$i", cashSystemTagDefaultName(key)!!, systemKey = key)
        }
        assertTrue(cashSystemTagsToRestore(untouched).isEmpty())
    }

    @Test
    fun `restore-all pairs each renamed tag with its own default`() {
        val renamed = CashSystemTagKeys.ALL.mapIndexed { i, key -> tag("s$i", "renamed $i", systemKey = key) }
        val restore = cashSystemTagsToRestore(renamed)
        assertEquals(CashSystemTagKeys.ALL.size, restore.size)
        restore.forEach { (t, name) -> assertEquals(cashSystemTagDefaultName(t.systemKey), name) }
    }
}
