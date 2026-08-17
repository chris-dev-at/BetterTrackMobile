package at.bettertrack.app.widget

import at.bettertrack.app.data.db.PortfolioEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Quick Links widget's pure half: the catalog's own integrity, the config
 * codec's round trip, and the grid rules that decide what a given card can
 * actually show.
 *
 * Everything here runs on the JVM because everything here is a pure function —
 * the same reason every other seam in this package is testable. What is NOT
 * tested here is the drawing; that was verified on the device against the
 * round-3 study renders.
 */
class BtQuickLinksTest {

    private fun portfolio(id: String, name: String, order: Int = 0) = PortfolioEntity(
        id = id,
        name = name,
        visibility = "private",
        sortOrder = order,
        isDefault = false,
        defaultPayFromCash = false,
        archivedAt = null,
        baseCurrency = "EUR",
        totals = null,
        detailSyncedAtMs = null,
    )

    // ── The catalog ──────────────────────────────────────────────────────────

    @Test
    fun `every catalog key is unique and stable-looking`() {
        // The key is what a stored config carries. Two entries sharing one would
        // make a saved tile decode as the wrong destination after any reorder of
        // the enum — the exact failure `key` exists to prevent.
        val keys = BtQuickLink.entries.map { it.key }
        assertEquals("duplicate catalog keys: $keys", keys.size, keys.toSet().size)
        assertTrue("a catalog key must not be blank", keys.none { it.isBlank() })
    }

    @Test
    fun `every catalog entry has a pictogram unless it is the monogram`() {
        // A zero drawable id on a pictogram tile renders an empty square that
        // still takes a tap — a launcher icon with nothing on it.
        BtQuickLink.entries.forEach { link ->
            if (link.isMonogram) {
                assertEquals("the monogram tile must not carry a drawable", 0, link.icon)
            } else {
                assertTrue("${link.key} has no pictogram", link.icon != 0)
            }
        }
    }

    @Test
    fun `exactly one catalog entry is the monogram`() {
        assertEquals(1, BtQuickLink.entries.count { it.isMonogram })
        assertTrue(BtQuickLink.PORTFOLIO.isMonogram)
    }

    @Test
    fun `every catalog pictogram resource exists on disk`() {
        // The compiler pins the R reference, but not that the FILE the round-3
        // study specified was actually added — a stale id could point at some
        // other drawable and the grid would still build.
        val dir = listOf(File("src/main/res/drawable"), File("app/src/main/res/drawable"))
            .first { it.isDirectory }
        val missing = BtQuickLink.entries
            .filterNot { it.isMonogram }
            .map { it.key to "ic_bt_widget_${expectedIconName(it)}.xml" }
            .filterNot { (_, file) -> File(dir, file).isFile }
        assertTrue("catalog pictograms missing from res/drawable: $missing", missing.isEmpty())
    }

    private fun expectedIconName(link: BtQuickLink): String = when (link) {
        BtQuickLink.OVERVIEW -> "overview"
        BtQuickLink.MARKETS -> "markets"
        BtQuickLink.CHAT -> "chat"
        BtQuickLink.SOCIAL -> "social"
        BtQuickLink.WATCHLIST -> "watchlist"
        BtQuickLink.CASH -> "wallet"
        BtQuickLink.ADD_TRANSACTION -> "transaction_add"
        BtQuickLink.ADD_CASH -> "cash_add"
        BtQuickLink.PORTFOLIO -> error("the monogram tile has no drawable")
    }

    @Test
    fun `an unknown key decodes to nothing rather than to a default destination`() {
        // Forward compatibility with a config written by a newer build. Guessing
        // a destination here would send a tap somewhere the user never chose.
        assertNull(BtQuickLink.fromKey("a_link_a_later_build_added"))
        assertNull(BtQuickLink.fromKey(null))
        assertNull(BtQuickLink.fromKey(""))
    }

    // ── The codec ────────────────────────────────────────────────────────────

    @Test
    fun `the tile list round-trips, order included`() {
        val actions = listOf(
            BtQuickLinkAction(BtQuickLink.CASH),
            BtQuickLinkAction(BtQuickLink.PORTFOLIO, "pf-1", "Langfristig"),
            BtQuickLinkAction(BtQuickLink.CHAT),
        )
        assertEquals(actions, btQuickLinksDecode(btQuickLinksEncode(actions)))
    }

    @Test
    fun `a portfolio name survives the separators a user can actually type`() {
        // The record/field separators are ASCII control characters precisely so
        // a name full of punctuation cannot split a record in half.
        val hostile = BtQuickLinkAction(
            BtQuickLink.PORTFOLIO,
            portfolioId = "pf-1",
            portfolioName = "Depot | A,B: \"C\" ; D\ttab\nnewline",
        )
        val decoded = btQuickLinksDecode(btQuickLinksEncode(listOf(hostile)))
        assertEquals(listOf(hostile), decoded)
    }

    @Test
    fun `an empty or absent config decodes to an empty list`() {
        assertEquals(emptyList<BtQuickLinkAction>(), btQuickLinksDecode(null))
        assertEquals(emptyList<BtQuickLinkAction>(), btQuickLinksDecode(""))
    }

    @Test
    fun `a monogram tile with no portfolio is dropped, not rendered blank`() {
        // Half-written record, or a portfolio deleted between config and decode.
        // Rendering it would be a gold "•" that opens the Overview under the
        // pretence of being someone's depot.
        val raw = btQuickLinksEncode(
            listOf(
                BtQuickLinkAction(BtQuickLink.OVERVIEW),
                BtQuickLinkAction(BtQuickLink.PORTFOLIO, portfolioId = "", portfolioName = "Ghost"),
            ),
        )
        assertEquals(listOf(BtQuickLinkAction(BtQuickLink.OVERVIEW)), btQuickLinksDecode(raw))
    }

    @Test
    fun `the codec never stores or returns more than the maximum`() {
        val many = List(20) { BtQuickLinkAction(BtQuickLink.CASH) }
        assertEquals(BT_QUICK_LINKS_MAX, btQuickLinksDecode(btQuickLinksEncode(many)).size)
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    fun `the default set fills the largest grid and carries no monogram`() {
        assertEquals(BT_QUICK_LINKS_MAX, BT_QUICK_LINKS_DEFAULT.size)
        assertTrue(
            "a default monogram tile would have no portfolio behind it",
            BT_QUICK_LINKS_DEFAULT.none { it.link.isMonogram },
        )
        assertEquals(
            "the default set must not repeat a destination",
            BT_QUICK_LINKS_DEFAULT.size,
            BT_QUICK_LINKS_DEFAULT.map { it.link }.toSet().size,
        )
    }

    @Test
    fun `every prefix of the default set is a sensible widget`() {
        // The grid always takes the first N that fit, so the ORDER is the
        // priority list. The front door has to lead, or a 2x1 placement opens
        // with whatever happened to be first in the enum.
        assertEquals(BtQuickLink.OVERVIEW, BT_QUICK_LINKS_DEFAULT.first().link)
    }

    // ── Grid capacity ────────────────────────────────────────────────────────

    /**
     * Content widths from the launcher dp this session MEASURED on the owner's
     * device (`dumpsys`): 4 columns = 366dp, so 2 columns ≈ 183dp and 3 ≈ 274dp
     * — each minus this widget's 2 × 10dp insets. The mockup's own 92dp-row
     * assumption is exactly what produced the "squished" rejection, so the
     * numbers here come from the device and not from the study.
     */
    private val twoCols = 183f - 20f
    private val threeCols = 274f - 20f
    private val fourCols = 366f - 20f

    @Test
    fun `capacity follows the study at the four named sizes`() {
        assertEquals("2x1 shows three", 3, btQuickLinksPerRow(twoCols, rows = 1))
        assertEquals("4x1 shows six", 6, btQuickLinksPerRow(fourCols, rows = 1))
        assertEquals("2x2 shows two per row", 2, btQuickLinksPerRow(twoCols, rows = 2))
        assertEquals("4x2 shows four per row", 4, btQuickLinksPerRow(fourCols, rows = 2))
    }

    @Test
    fun `a three-column placement gets its own answer, not the two-column one`() {
        // The reason capacity is derived from a pitch instead of a size-class
        // table: this launcher hands out 3-column widgets and the four named
        // renditions do not describe them.
        assertTrue(
            "a 3-column strip must fit more icons than a 2-column one",
            btQuickLinksPerRow(threeCols, rows = 1) > btQuickLinksPerRow(twoCols, rows = 1),
        )
        assertTrue(
            "…and fewer than a 4-column one",
            btQuickLinksPerRow(threeCols, rows = 1) < btQuickLinksPerRow(fourCols, rows = 1),
        )
    }

    @Test
    fun `the gap the capacity rule budgets is the gap the widget draws`() {
        // If these drifted, the last tile of every full row would be clipped —
        // the count would be right and the layout wrong, which is the hardest
        // version of this bug to see in a screenshot.
        assertEquals(BT_QUICK_LINK_GAP_STRIP, btQuickLinkGap(1), 0.001f)
        assertEquals(BT_QUICK_LINK_GAP_GRID, btQuickLinkGap(2), 0.001f)
        assertTrue(
            "a tile must never be narrower than the 48dp tap target",
            BT_QUICK_LINK_PITCH_STRIP - BT_QUICK_LINK_GAP_STRIP >= 48f,
        )
    }

    @Test
    fun `capacity never collapses to zero or runs away`() {
        // A zero would render an empty card on a sliver-sized placement; an
        // unbounded count would turn a very wide widget into a keypad.
        assertEquals(1, btQuickLinksPerRow(0f, rows = 1))
        assertEquals(1, btQuickLinksPerRow(-40f, rows = 2))
        assertEquals(6, btQuickLinksPerRow(4000f, rows = 1))
        assertEquals(4, btQuickLinksPerRow(4000f, rows = 2))
    }

    @Test
    fun `the grid takes what fits and never more than the maximum`() {
        val eight = BT_QUICK_LINKS_DEFAULT
        assertEquals(listOf(3), btQuickLinksRows(eight, perRow = 3, rows = 1).map { it.size })
        assertEquals(listOf(4, 4), btQuickLinksRows(eight, perRow = 4, rows = 2).map { it.size })
        // Even a hypothetically huge card cannot draw a ninth tile, because
        // nothing may store one.
        assertEquals(
            BT_QUICK_LINKS_MAX,
            btQuickLinksRows(eight, perRow = 6, rows = 3).sumOf { it.size },
        )
    }

    @Test
    fun `a short last row is still a row`() {
        val five = BT_QUICK_LINKS_DEFAULT.take(5)
        assertEquals(listOf(4, 1), btQuickLinksRows(five, perRow = 4, rows = 2).map { it.size })
    }

    @Test
    fun `an empty configuration produces no rows rather than an empty row`() {
        assertTrue(btQuickLinksRows(emptyList(), perRow = 4, rows = 2).isEmpty())
        assertTrue(btQuickLinksRows(BT_QUICK_LINKS_DEFAULT, perRow = 0, rows = 2).isEmpty())
        assertTrue(btQuickLinksRows(BT_QUICK_LINKS_DEFAULT, perRow = 4, rows = 0).isEmpty())
    }

    // ── The monogram ─────────────────────────────────────────────────────────

    @Test
    fun `the monogram is the name's first letter, uppercased`() {
        assertEquals("L", btQuickLinkMonogram("Langfristig"))
        assertEquals("D", btQuickLinkMonogram("depot"))
    }

    @Test
    fun `the monogram skips leading punctuation and decoration`() {
        // "★ Langfristig" would otherwise get a star tile that says nothing,
        // and "(alt) Depot" a bracket.
        assertEquals("L", btQuickLinkMonogram("★ Langfristig"))
        assertEquals("A", btQuickLinkMonogram("(alt) Depot"))
        assertEquals("2", btQuickLinkMonogram("  2026 Trading"))
    }

    @Test
    fun `a manual override wins and is trimmed to one character`() {
        assertEquals("X", btQuickLinkMonogram("Langfristig", override = "x"))
        assertEquals("Q", btQuickLinkMonogram("Langfristig", override = "  qq "))
    }

    @Test
    fun `a name with nothing alphanumeric still gets a mark`() {
        // Never an empty tile: the house dot is the placeholder glyph.
        assertEquals("•", btQuickLinkMonogram("★★★"))
        assertEquals("•", btQuickLinkMonogram(""))
    }

    // ── Descriptions and the portfolio option list ───────────────────────────

    @Test
    fun `a monogram tile speaks its portfolio's full name`() {
        // These tiles render NO text, so the description is the only place the
        // destination exists for a screen reader.
        assertEquals(
            "Depot Langfristig",
            btQuickLinkDescription(
                BtQuickLinkAction(BtQuickLink.PORTFOLIO, "pf-1", "Langfristig"),
                "Depot",
            ),
        )
        assertEquals(
            "Cash",
            btQuickLinkDescription(BtQuickLinkAction(BtQuickLink.CASH), "Cash"),
        )
    }

    @Test
    fun `the portfolio options are the active ones, in switcher order`() {
        val options = btQuickLinkPortfolioActions(
            listOf(
                portfolio("b", "Beta", order = 2),
                portfolio("a", "Alpha", order = 1),
                portfolio("z", "Zombie", order = 0).copy(archivedAt = "2026-01-01T00:00:00Z"),
            ),
        )
        assertEquals(listOf("Alpha", "Beta"), options.map { it.portfolioName })
        assertTrue(options.all { it.link.isMonogram })
        assertFalse("an archived portfolio must not be offered", options.any { it.portfolioId == "z" })
    }

    // ── The pin stash ────────────────────────────────────────────────────────

    @Test
    fun `a pinned tile set round-trips through the stash payload`() {
        val config = BtQuickLinksConfig(
            actions = listOf(
                BtQuickLinkAction(BtQuickLink.PORTFOLIO, "pf-1", "Langfristig"),
                BtQuickLinkAction(BtQuickLink.CASH),
            ),
            captions = true,
        )
        assertEquals(config, btWidgetPinQuickLinks(btWidgetPinPayload(config)))
    }

    @Test
    fun `an empty stash is no configuration at all`() {
        // Falling through to the widget's own default set is strictly better
        // than claiming a blank grid was what the user built.
        assertNull(btWidgetPinQuickLinks(emptyMap()))
        assertNull(btWidgetPinQuickLinks(mapOf("links" to "", "captions" to "1")))
        assertNotNull(
            btWidgetPinQuickLinks(
                btWidgetPinPayload(BtQuickLinksConfig(listOf(BtQuickLinkAction(BtQuickLink.CASH)))),
            ),
        )
    }
}
