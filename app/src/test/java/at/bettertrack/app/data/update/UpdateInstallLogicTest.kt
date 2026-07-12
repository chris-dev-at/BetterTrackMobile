package at.bettertrack.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-logic tests for the in-app "Download & Install" flow (owner ask 2026-07-12):
 * determinate-vs-indeterminate progress math, the PackageInstaller status → outcome
 * mapping, the per-version cache layout, and the startup sweep that clears the whole
 * updates dir (successful-install leftover + stale partial downloads).
 */
class UpdateInstallLogicTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── Download progress ────────────────────────────────────────────────────

    @Test fun `percent maps bytes over a known total`() {
        assertEquals(0, DownloadProgress.percent(0, 200))
        assertEquals(50, DownloadProgress.percent(100, 200))
        assertEquals(100, DownloadProgress.percent(200, 200))
    }

    @Test fun `percent is null (indeterminate) when total is unknown`() {
        assertNull(DownloadProgress.percent(1_234, 0))
        assertNull(DownloadProgress.percent(1_234, -1))
    }

    @Test fun `percent never exceeds 100 even if more bytes arrive than declared`() {
        assertEquals(100, DownloadProgress.percent(250, 200))
    }

    // ── Install status mapping ───────────────────────────────────────────────

    @Test fun `status maps to pending, success, or failure`() {
        assertEquals(InstallOutcome.PENDING_USER_ACTION, UpdateInstallStatus.outcome(-1))
        assertEquals(InstallOutcome.SUCCESS, UpdateInstallStatus.outcome(0))
        // Everything else (FAILURE=1, ABORTED=3, BLOCKED=2, CONFLICT=5, …) → failure.
        assertEquals(InstallOutcome.FAILURE, UpdateInstallStatus.outcome(1))
        assertEquals(InstallOutcome.FAILURE, UpdateInstallStatus.outcome(3))
        assertEquals(InstallOutcome.FAILURE, UpdateInstallStatus.outcome(7))
        assertEquals(InstallOutcome.FAILURE, UpdateInstallStatus.outcome(Int.MIN_VALUE))
    }

    // ── Cache layout + startup sweep ─────────────────────────────────────────

    @Test fun `apk file is per-version under the updates dir`() {
        val cache = tmp.newFolder("cache")
        val apk = UpdateFiles.apkFile(cache, 58)
        assertEquals("updates", apk.parentFile!!.name)
        assertEquals("BetterTrackApp-58.apk", apk.name)
    }

    @Test fun `sweep on empty or missing dir is a no-op`() {
        val cache = tmp.newFolder("cache")
        assertEquals(0, UpdateFiles.sweep(cache))
        assertFalse(UpdateFiles.updatesDir(cache).exists())
    }

    @Test fun `sweep removes the whole updates dir including a leftover apk and partials`() {
        val cache = tmp.newFolder("cache")
        val dir = UpdateFiles.updatesDir(cache).apply { mkdirs() }
        File(dir, "BetterTrackApp-58.apk").writeText("full install leftover")
        File(dir, "BetterTrackApp-57.apk.part").writeText("stale partial")

        val removed = UpdateFiles.sweep(cache)

        assertEquals(2, removed)
        assertFalse(dir.exists())
    }

    @Test fun `sweep leaves the rest of the cache untouched`() {
        val cache = tmp.newFolder("cache")
        val keep = File(cache, "other.bin").apply { writeText("unrelated") }
        UpdateFiles.updatesDir(cache).apply { mkdirs() }
        File(UpdateFiles.updatesDir(cache), "BetterTrackApp-58.apk").writeText("x")

        UpdateFiles.sweep(cache)

        assertTrue(keep.exists())
    }
}
