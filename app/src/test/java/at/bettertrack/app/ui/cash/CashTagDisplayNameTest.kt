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
 * The seeded cash tags read German and write English (device QA 2026-09-01, #10).
 *
 * The ledger showed the chip **"Withdrawal"** beside German ones. It was not the
 * cash-KIND vocabulary failing — that map is complete and renders the row's title
 * ("Bezahlt"). The chip is a TAG, and the platform seeds nine of them with English
 * names, so the English arrived as server data and nothing app-side was allowed to
 * touch it.
 *
 * Two rules make the translation safe, and this pins both:
 *
 *  1. Every seeded identity has a display name, keyed on `systemKey` — the stable
 *     id — so the wire name and the auto-tag engine are untouched.
 *  2. A tag the user has RENAMED keeps their word. Overruling a deliberate rename
 *     with a translation would be a worse bug than the one being fixed.
 */
class CashTagDisplayNameTest {

    private fun tag(
        name: String,
        systemKey: String? = null,
        system: Boolean = systemKey != null,
    ) = CashTagEntity(id = "t-$name", name = name, color = "#64748b", system = system, systemKey = systemKey)

    @Test
    fun `every seeded identity has a display name`() {
        val missing = CashSystemTagKeys.ALL.filter { cashSystemTagNameRes(it) == null }
        assertTrue("seeded tags with no localized display name: $missing", missing.isEmpty())
    }

    @Test
    fun `the nine display names are distinct`() {
        val res = CashSystemTagKeys.ALL.mapNotNull { cashSystemTagNameRes(it) }
        assertEquals(CashSystemTagKeys.ALL.size, res.size)
        assertEquals(res.size, res.toSet().size)
    }

    @Test
    fun `the withdrawal tag from the report has one`() {
        assertNotNull(cashSystemTagNameRes(CashSystemTagKeys.WITHDRAWAL))
    }

    @Test
    fun `a user tag and an unknown identity have none`() {
        assertNull(cashSystemTagNameRes(null))
        // Deliberately not an enum: the platform may seed a tenth identity, and an
        // app that has never heard of it must fall back to the server's name
        // rather than invent one.
        assertNull(cashSystemTagNameRes("carbon_offset"))
    }

    // ── The rename rule, which decides WHEN the translation applies ──────────

    @Test
    fun `a seeded tag still carrying its wire name is translatable`() {
        assertTrue(cashSystemTagIsAtDefault(tag("Withdrawal", CashSystemTagKeys.WITHDRAWAL)))
        // Trim- and case-tolerant, exactly as the restore path is.
        assertTrue(cashSystemTagIsAtDefault(tag("withdrawal ", CashSystemTagKeys.WITHDRAWAL)))
    }

    @Test
    fun `a tag the user renamed keeps their word`() {
        // `cashTagDisplayName` returns `tag.name` for this case; the predicate is
        // the branch that decides it, and it is what makes the translation safe.
        assertFalse(cashSystemTagIsAtDefault(tag("Broker-Kosten", CashSystemTagKeys.FEES)))
        assertFalse(cashSystemTagIsAtDefault(tag("Abhebungen", CashSystemTagKeys.WITHDRAWAL)))
    }

    @Test
    fun `the write path stays English`() {
        // The restore action PATCHes the canonical name over the wire and the web
        // client shows the same row, so a German name written from this phone
        // would follow the account everywhere. Display is translated; storage is
        // not, and the two must not be confused.
        assertEquals("Withdrawal", cashSystemTagDefaultName(CashSystemTagKeys.WITHDRAWAL))
        assertEquals("Fees", cashSystemTagDefaultName(CashSystemTagKeys.FEES))
    }
}
