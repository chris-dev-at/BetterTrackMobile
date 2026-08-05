package at.bettertrack.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §4.5 availability matrix.
 *
 * This table is the single source of truth for "absent, not greyed", and it is
 * read by the bottom bar, the top bar and Settings. A regression here does not
 * look like a crash — it looks like a Drive-only user finding a Social tab that
 * leads to a permanent spinner, or an existing SERVER user quietly losing their
 * alerts. So every cell of the plan's table is pinned individually.
 */
class StorageSurfacesTest {

    // ── SERVER: nothing changes, ever ───────────────────────────────────────

    @Test
    fun `a server install renders every surface except the vault section`() {
        for (surface in BtSurface.entries) {
            val expected =
                if (surface == BtSurface.VAULT_SETTINGS) SurfaceAvailability.ABSENT else SurfaceAvailability.FULL
            assertEquals(surface.name, expected, surfaceAvailability(StorageMode.SERVER, surface))
        }
    }

    @Test
    fun `unset behaves exactly as server`() {
        for (surface in BtSurface.entries) {
            assertEquals(
                surface.name,
                surfaceAvailability(StorageMode.SERVER, surface),
                surfaceAvailability(StorageMode.UNSET, surface),
            )
        }
    }

    // ── BOTH: server plus a vault, never less ───────────────────────────────

    @Test
    fun `both renders everything server does and adds the vault section`() {
        for (surface in BtSurface.entries) {
            assertEquals(surface.name, SurfaceAvailability.FULL, surfaceAvailability(StorageMode.BOTH, surface))
        }
    }

    @Test
    fun `both never reduces a surface below what server offers`() {
        // The mirror is a backup, not a downgrade (plan §1.5): the server stays
        // authoritative, so nothing about the UI may become poorer.
        for (surface in BtSurface.entries) {
            val server = surfaceAvailability(StorageMode.SERVER, surface)
            val both = surfaceAvailability(StorageMode.BOTH, surface)
            assertTrue(
                "$surface degraded from $server to $both by adding a Drive backup",
                both.ordinal <= server.ordinal,
            )
        }
    }

    // ── DRIVE: the plan's table, cell by cell ───────────────────────────────

    @Test
    fun `drive keeps the money surfaces at full strength`() {
        assertEquals(SurfaceAvailability.FULL, surfaceAvailability(StorageMode.DRIVE, BtSurface.PORTFOLIO))
        assertEquals(SurfaceAvailability.FULL, surfaceAvailability(StorageMode.DRIVE, BtSurface.HISTORY))
        assertEquals(SurfaceAvailability.FULL, surfaceAvailability(StorageMode.DRIVE, BtSurface.APP_LOCK))
        assertEquals(SurfaceAvailability.FULL, surfaceAvailability(StorageMode.DRIVE, BtSurface.VAULT_SETTINGS))
    }

    @Test
    fun `drive degrades market and watchlists rather than removing them`() {
        // Degraded, not absent: they genuinely work, just without live prices
        // (W6) and with device-local membership (board #40.3). Removing them
        // would be as dishonest as pretending they are complete.
        assertEquals(SurfaceAvailability.DEGRADED, surfaceAvailability(StorageMode.DRIVE, BtSurface.MARKET))
        assertEquals(SurfaceAvailability.DEGRADED, surfaceAvailability(StorageMode.DRIVE, BtSurface.WATCHLISTS))
        assertTrue(StorageMode.DRIVE.shows(BtSurface.MARKET))
        assertTrue(StorageMode.DRIVE.shows(BtSurface.WATCHLISTS))
    }

    @Test
    fun `drive removes every account-backed surface outright`() {
        val absent = listOf(
            BtSurface.CONGLOMERATES,
            BtSurface.SOCIAL,
            BtSurface.ALERTS_NOTIFICATIONS,
            BtSurface.TAX_MODES,
            BtSurface.ACCOUNT_SETTINGS,
        )
        for (surface in absent) {
            assertEquals(surface.name, SurfaceAvailability.ABSENT, surfaceAvailability(StorageMode.DRIVE, surface))
            assertFalse(surface.name, StorageMode.DRIVE.shows(surface))
        }
    }

    @Test
    fun `drive keeps the pending-sync screen because the queue is a local journal`() {
        // Plan §1.2: in Drive mode the outbound queue stops being a network queue
        // and becomes a local-apply journal — a domain refusal still has to land
        // somewhere the user can see and fix it.
        assertEquals(SurfaceAvailability.FULL, surfaceAvailability(StorageMode.DRIVE, BtSurface.PENDING_SYNC))
    }

    // ── Totality ────────────────────────────────────────────────────────────

    @Test
    fun `every surface has an answer in every mode`() {
        for (mode in StorageMode.entries) {
            for (surface in BtSurface.entries) {
                assertNotNull("$mode/$surface", surfaceAvailability(mode, surface))
            }
        }
    }

    // ── The bottom bar ──────────────────────────────────────────────────────

    @Test
    fun `the server bar is the mandate's five tabs in bar order`() {
        // R-arc mandate §2, verbatim: Home · Portfolio · Workbench · Markets ·
        // People. CONGLOMERATES is the Workbench tab's surface — the constant
        // keeps the storage plan's name (decision O-2) while the label changed.
        assertEquals(
            listOf(
                BtSurface.HOME,
                BtSurface.PORTFOLIO,
                BtSurface.CONGLOMERATES,
                BtSurface.MARKET,
                BtSurface.SOCIAL,
            ),
            visibleTabSurfaces(StorageMode.SERVER),
        )
        assertEquals(visibleTabSurfaces(StorageMode.SERVER), visibleTabSurfaces(StorageMode.BOTH))
        assertEquals(visibleTabSurfaces(StorageMode.SERVER), visibleTabSurfaces(StorageMode.UNSET))
    }

    @Test
    fun `the drive bar is home, portfolio and markets`() {
        // Drive-only gains a real front door in R1. Workbench and People stay
        // absent because a Drive install has no BetterTrack account — those are
        // features that CANNOT exist for it, not features it is missing.
        assertEquals(
            listOf(BtSurface.HOME, BtSurface.PORTFOLIO, BtSurface.MARKET),
            visibleTabSurfaces(StorageMode.DRIVE),
        )
    }

    @Test
    fun `every mode keeps HOME first`() {
        // It is the NavHost's start destination in every mode; a bar whose first
        // entry was not the start destination would open on a tab the user did
        // not pick. This is strictly stronger than the pre-R1 version of this
        // guard, which pinned PORTFOLIO: Portfolio was FULL everywhere by
        // happenstance, HOME is FULL by construction (see below).
        for (mode in StorageMode.entries) {
            assertEquals(mode.name, BtSurface.HOME, visibleTabSurfaces(mode).first())
        }
    }

    @Test
    fun `home is full in every mode because it is an index, not a feature`() {
        // Home shows whatever this install has. There is no mode in which it can
        // be absent or degraded, and nothing gated behind it — so if this ever
        // fails, the bar has acquired a first entry that some install cannot use.
        for (mode in StorageMode.entries) {
            assertEquals(mode.name, SurfaceAvailability.FULL, surfaceAvailability(mode, BtSurface.HOME))
            assertTrue(mode.name, mode.shows(BtSurface.HOME))
        }
    }

    @Test
    fun `every bar is a subsequence of the full bar`() {
        // Gating removes tabs; it never reorders them. A Drive user and a server
        // user must be able to describe the bar to each other.
        val full = visibleTabSurfaces(StorageMode.SERVER)
        for (mode in StorageMode.entries) {
            val bar = visibleTabSurfaces(mode)
            assertEquals(mode.name, bar, full.filter { it in bar })
        }
    }
}
