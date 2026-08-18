package at.bettertrack.app.widget

import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.notifications.NotifDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-tile TARGETS for Quick Links (owner 2026-08-18).
 *
 * *"what if i want to have 3 buttons that each bring me to the overview of
 * another cash source. […] and for all the other stuff too like open portfolio
 * or add transaction. like where to?"*
 *
 * Before this round a catalog entry was a bare destination: three Cash tiles
 * were three identical tiles, and the editor would not even let a second one be
 * added. These tests pin the three properties that make aimed tiles work — the
 * codec carries the target, the target survives a downgrade/upgrade of the
 * stored blob, and two tiles of the same kind aimed differently are DIFFERENT
 * tiles.
 */
class BtQuickLinksTargetingTest {

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

    private fun source(
        id: String,
        portfolioId: String,
        name: String,
        isMain: Boolean = false,
        archivedAt: String? = null,
    ) = CashSourceEntity(
        id = id,
        portfolioId = portfolioId,
        name = name,
        kind = "bank",
        isMain = isMain,
        balanceEur = 0.0,
        archivedAt = archivedAt,
    )

    private val portfolios = listOf(portfolio("pf-1", "Haupt", 0), portfolio("pf-2", "Sparen", 1))
    private val sources = listOf(
        source("cs-1", "pf-1", "Girokonto", isMain = true),
        source("cs-2", "pf-1", "Bank 2"),
        source("cs-3", "pf-2", "Sparkonto", isMain = true),
    )

    // ── The codec ────────────────────────────────────────────────────────────

    @Test
    fun `a cash target round-trips with its source and its portfolio`() {
        val actions = listOf(
            BtQuickLinkAction(
                BtQuickLink.CASH,
                portfolioId = "pf-2",
                portfolioName = "Sparen",
                sourceId = "cs-3",
                sourceName = "Sparkonto",
            ),
            BtQuickLinkAction(BtQuickLink.ADD_CASH, portfolioId = "pf-1", sourceId = "cs-1", sourceName = "Girokonto"),
            BtQuickLinkAction(BtQuickLink.ADD_TRANSACTION, portfolioId = "pf-1", portfolioName = "Haupt"),
        )
        assertEquals(actions, btQuickLinksDecode(btQuickLinksEncode(actions)))
    }

    @Test
    fun `a record written before targets existed still decodes`() {
        // The pre-2026-08-18 format: exactly four fields, no source pair. The
        // fields were APPENDED for precisely this reason, so an app update must
        // not blank out a home screen the user already configured.
        val legacy = "cash"
        val decoded = btQuickLinksDecode(legacy)
        assertEquals(listOf(BtQuickLinkAction(BtQuickLink.CASH)), decoded)
        assertNull("an old record has no aim", decoded.single().targetName)
    }

    @Test
    fun `a source name survives the separators a user can actually type`() {
        val hostile = BtQuickLinkAction(
            BtQuickLink.CASH,
            portfolioId = "pf-1",
            portfolioName = "A, B | C",
            sourceId = "cs-9",
            sourceName = "Konto: \"privat\" | alt",
        )
        assertEquals(hostile, btQuickLinksDecode(btQuickLinksEncode(listOf(hostile))).single())
    }

    // ── What a tile is aimed at ──────────────────────────────────────────────

    @Test
    fun `targetName reads the field that matches the entry's targeting`() {
        // A cash entry names its SOURCE even though it also stores a portfolio;
        // reading the portfolio there would label three wallets of one depot
        // identically, which is the defect this round exists to fix.
        val cash = BtQuickLinkAction(
            BtQuickLink.CASH,
            portfolioId = "pf-1",
            portfolioName = "Haupt",
            sourceId = "cs-2",
            sourceName = "Bank 2",
        )
        assertEquals("Bank 2", cash.targetName)
        assertEquals("Haupt", BtQuickLinkAction(BtQuickLink.PORTFOLIO, "pf-1", "Haupt").targetName)
        assertNull(BtQuickLinkAction(BtQuickLink.SOCIAL).targetName)
        assertNull("an unaimed cash tile has no target", BtQuickLinkAction(BtQuickLink.CASH).targetName)
    }

    @Test
    fun `every catalog entry that stores a target declares one, and vice versa`() {
        BtQuickLink.entries.forEach { link ->
            val aimed = btQuickLinkTargetChoices(link, portfolios, sources)
            if (link.targeting == BtQuickLinkTargeting.NONE) {
                assertTrue("${link.key} offers targets but declares none", aimed.isEmpty())
            } else {
                assertTrue("${link.key} declares targeting but offers nothing", aimed.isNotEmpty())
            }
        }
    }

    // ── The target choices the editor offers ─────────────────────────────────

    @Test
    fun `a cash entry is offered every active wallet across every portfolio`() {
        // The owner's literal case: a wallet in the savings portfolio has to be
        // reachable from a picker that used to list one portfolio's sources.
        val choices = btQuickLinkTargetChoices(BtQuickLink.CASH, portfolios, sources)
        assertEquals(
            listOf(null, "Girokonto", "Bank 2", "Sparkonto"),
            choices.map { it.targetName },
        )
        val savings = choices.single { it.sourceId == "cs-3" }
        assertEquals("the wallet carries its own portfolio", "pf-2", savings.portfolioId)
        assertEquals("Sparen", savings.portfolioName)
    }

    @Test
    fun `an archived wallet is never offered as a target`() {
        val withDead = sources + source("cs-x", "pf-1", "Altes Konto", archivedAt = "2026-01-01T00:00:00Z")
        val choices = btQuickLinkTargetChoices(BtQuickLink.CASH, portfolios, withDead)
        assertTrue(choices.none { it.sourceId == "cs-x" })
    }

    @Test
    fun `the untargeted reading leads, except for the monogram tile`() {
        // A monogram tile with no portfolio is the blank mark the decoder drops,
        // so offering it would be offering a tile that deletes itself on save.
        assertNull(btQuickLinkTargetChoices(BtQuickLink.CASH, portfolios, sources).first().targetName)
        assertNull(btQuickLinkTargetChoices(BtQuickLink.ADD_CASH, portfolios, sources).first().targetName)
        assertNull(btQuickLinkTargetChoices(BtQuickLink.ADD_TRANSACTION, portfolios, sources).first().targetName)
        assertTrue(
            "the portfolio tile must always name a portfolio",
            btQuickLinkTargetChoices(BtQuickLink.PORTFOLIO, portfolios, sources).all { it.targetName != null },
        )
    }

    @Test
    fun `an archived portfolio is never offered as a target`() {
        val withDead = portfolios + portfolio("pf-9", "Altes Depot", 9).copy(archivedAt = "2026-01-01T00:00:00Z")
        assertTrue(
            btQuickLinkTargetChoices(BtQuickLink.PORTFOLIO, withDead, sources).none { it.portfolioId == "pf-9" },
        )
    }

    // ── Two tiles of one kind, aimed differently ─────────────────────────────

    @Test
    fun `same-target compares ids, never the snapshotted names`() {
        // Two portfolios routinely both have a wallet called "Bank". Comparing
        // names would tick the wrong row in the picker and, worse, make the
        // editor refuse to add the second one as "already placed".
        val a = BtQuickLinkAction(BtQuickLink.CASH, "pf-1", "Haupt", sourceId = "cs-1", sourceName = "Bank")
        val b = BtQuickLinkAction(BtQuickLink.CASH, "pf-2", "Sparen", sourceId = "cs-3", sourceName = "Bank")
        assertFalse(btQuickLinkSameTarget(a, b))
        assertTrue(btQuickLinkSameTarget(a, a.copy(sourceName = "renamed since")))
    }

    @Test
    fun `three cash tiles aimed at three wallets are three distinct tiles`() {
        val three = btQuickLinkTargetChoices(BtQuickLink.CASH, portfolios, sources)
            .filter { it.sourceId.isNotBlank() }
        assertEquals(3, three.size)
        // Distinct as configuration…
        assertEquals(3, three.distinct().size)
        // …and distinct after a save/load cycle, which is what the home screen
        // actually renders from.
        assertEquals(three, btQuickLinksDecode(btQuickLinksEncode(three)))
        // …and distinct to the eye, with captions off: different marks.
        assertEquals(3, three.mapNotNull { btQuickLinkTileMonogram(it) }.distinct().size)
    }

    // ── How an aimed tile is drawn and spoken ────────────────────────────────

    @Test
    fun `an aimed cash tile paints its wallet's initial instead of the wallet glyph`() {
        val aimed = BtQuickLinkAction(BtQuickLink.CASH, "pf-2", "Sparen", sourceId = "cs-3", sourceName = "Sparkonto")
        assertEquals("S", btQuickLinkTileMonogram(aimed))
        assertNull("an unaimed cash tile keeps its pictogram", btQuickLinkTileMonogram(BtQuickLinkAction(BtQuickLink.CASH)))
    }

    @Test
    fun `the add actions keep their verb glyph even when aimed`() {
        // The verb is the point: a bare initial would be indistinguishable from
        // the PORTFOLIO tile, which is a different destination entirely.
        assertNull(
            btQuickLinkTileMonogram(
                BtQuickLinkAction(BtQuickLink.ADD_TRANSACTION, "pf-1", "Haupt"),
            ),
        )
        assertNull(
            btQuickLinkTileMonogram(
                BtQuickLinkAction(BtQuickLink.ADD_CASH, "pf-1", sourceId = "cs-1", sourceName = "Girokonto"),
            ),
        )
    }

    @Test
    fun `a caption names the target when there is one, the destination otherwise`() {
        val aimed = BtQuickLinkAction(BtQuickLink.CASH, "pf-1", "Haupt", sourceId = "cs-2", sourceName = "Bank 2")
        assertEquals("Bank 2", btQuickLinkCaption(aimed, "Cash"))
        assertEquals("Cash", btQuickLinkCaption(BtQuickLinkAction(BtQuickLink.CASH), "Cash"))
    }

    @Test
    fun `a screen reader hears the destination and the target together`() {
        val aimed = BtQuickLinkAction(BtQuickLink.CASH, "pf-1", "Haupt", sourceId = "cs-2", sourceName = "Bank 2")
        assertEquals("Cash Bank 2", btQuickLinkDescription(aimed, "Cash"))
        assertEquals("Cash", btQuickLinkDescription(BtQuickLinkAction(BtQuickLink.CASH), "Cash"))
    }

    @Test
    fun `an aimed tile still fits the stored maximum`() {
        val many = btQuickLinkTargetChoices(BtQuickLink.CASH, portfolios, sources) +
            btQuickLinkTargetChoices(BtQuickLink.PORTFOLIO, portfolios, sources) +
            btQuickLinkTargetChoices(BtQuickLink.ADD_CASH, portfolios, sources)
        assertTrue(many.size > BT_QUICK_LINKS_MAX)
        assertEquals(BT_QUICK_LINKS_MAX, btQuickLinksDecode(btQuickLinksEncode(many)).size)
    }

    // ── The aim survives the tap ─────────────────────────────────────────────

    @Test
    fun `an aimed tile's target reaches the deep link`() {
        // The tile stores a target; this is the other end of that wire. If the
        // resolver dropped it, three configured Cash tiles would open the same
        // unscoped screen and the whole feature would be invisible.
        assertEquals(
            NotifDeepLink.Cash(portfolioId = "pf-2", sourceId = "cs-3"),
            btWidgetDeepLink(BT_WIDGET_TARGET_CASH, null, portfolioId = "pf-2", sourceId = "cs-3"),
        )
        assertEquals(
            NotifDeepLink.AddTransaction("pf-1"),
            btWidgetDeepLink(BT_WIDGET_TARGET_ADD_TRANSACTION, null, portfolioId = "pf-1"),
        )
        assertEquals(
            NotifDeepLink.AddCashEntry(portfolioId = "pf-1", sourceId = "cs-1"),
            btWidgetDeepLink(BT_WIDGET_TARGET_ADD_CASH, null, portfolioId = "pf-1", sourceId = "cs-1"),
        )
    }

    @Test
    fun `an unaimed tile resolves exactly as it did before targets existed`() {
        // The regression guard for every home screen already out there.
        assertEquals(NotifDeepLink.Cash(null, null), btWidgetDeepLink(BT_WIDGET_TARGET_CASH, null))
        assertEquals(NotifDeepLink.AddTransaction(null), btWidgetDeepLink(BT_WIDGET_TARGET_ADD_TRANSACTION, null))
        assertEquals(NotifDeepLink.AddCashEntry(), btWidgetDeepLink(BT_WIDGET_TARGET_ADD_CASH, null))
        // Blank is treated as absent, not as an id of "".
        assertEquals(
            NotifDeepLink.Cash(null, null),
            btWidgetDeepLink(BT_WIDGET_TARGET_CASH, null, portfolioId = "  ", sourceId = "  "),
        )
    }

    @Test
    fun `two cash tiles on different wallets are two distinct PendingIntents`() {
        // PendingIntent equality ignores extras, so the SOURCE has to appear in
        // the action string. Without it the launcher collapses both tiles onto
        // whichever was registered first and one of the owner's three buttons
        // silently opens the other's wallet.
        val a = btWidgetIntentAction(BT_WIDGET_TARGET_CASH, "pf-1", "cs-1")
        val b = btWidgetIntentAction(BT_WIDGET_TARGET_CASH, "pf-1", "cs-2")
        val unaimed = btWidgetIntentAction(BT_WIDGET_TARGET_CASH, null, null)
        assertEquals(3, setOf(a, b, unaimed).size)
    }

    @Test
    fun `two wallets sharing a name are told apart by their portfolio`() {
        // Device pass 2026-08-18: this account has FIVE cash sources and four
        // of them are called "Main", one per portfolio. The editor listed three
        // identical "Cash · Main" rows until the label was qualified.
        val a = BtQuickLinkAction(BtQuickLink.CASH, "pf-1", "Haupt", sourceId = "cs-1", sourceName = "Main")
        val b = BtQuickLinkAction(BtQuickLink.CASH, "pf-2", "Sparen", sourceId = "cs-9", sourceName = "Main")
        assertEquals("Main · Haupt", btQuickLinkTargetLabel(a))
        assertEquals("Main · Sparen", btQuickLinkTargetLabel(b))
        assertTrue(btQuickLinkTargetLabel(a) != btQuickLinkTargetLabel(b))
        // The widget CAPTION stays short — one tile is on screen at a time.
        assertEquals("Main", btQuickLinkCaption(a, "Cash"))
    }

    @Test
    fun `a target label falls back cleanly when the portfolio name is unknown`() {
        val orphan = BtQuickLinkAction(BtQuickLink.CASH, "pf-x", "", sourceId = "cs-x", sourceName = "Bank")
        assertEquals("Bank", btQuickLinkTargetLabel(orphan))
        assertNull(btQuickLinkTargetLabel(BtQuickLinkAction(BtQuickLink.CASH)))
        assertEquals("Haupt", btQuickLinkTargetLabel(BtQuickLinkAction(BtQuickLink.PORTFOLIO, "pf-1", "Haupt")))
        assertNull(btQuickLinkTargetLabel(BtQuickLinkAction(BtQuickLink.SOCIAL)))
    }

    @Test
    fun `the default tile set carries no targets and therefore needs no data`() {
        // The defaults are what a freshly pinned instance renders before any
        // portfolio or wallet has been read, so none of them may depend on one.
        assertTrue(BT_QUICK_LINKS_DEFAULT.all { it.targetName == null })
        assertNotNull(BT_QUICK_LINKS_DEFAULT.firstOrNull())
    }
}
