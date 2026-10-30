package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two reference sheets every drag test resolves against: portrait,
 * edge-to-edge, sheet = window - status bar - the 8dp top gap.
 */
internal object SheetDragDevices {
    /** 1080x2400 @ 420dpi phone: 2400 - 63px (24dp) status bar - 21px gap. */
    const val PHONE_DENSITY = 2.625f
    const val PHONE_SHEET_PX = 2316f
    const val PHONE_SHEET_DP = PHONE_SHEET_PX / PHONE_DENSITY

    /**
     * 1600x2560 @ 340dpi tablet: 2560 - 51px (24dp) status bar - 17px gap. Measured
     * on R52T207VDBM 2026-10-30: window frame 2560px, grabber top at 68px.
     */
    const val TABLET_DENSITY = 2.125f
    const val TABLET_SHEET_PX = 2492f
    const val TABLET_SHEET_DP = TABLET_SHEET_PX / TABLET_DENSITY

    val phone = sheetDragModel(PHONE_SHEET_PX, PHONE_DENSITY)
    val tablet = sheetDragModel(TABLET_SHEET_PX, TABLET_DENSITY)
}

/**
 * The owner's order of 2026-10-29, pinned: in nested sheets a little pull goes
 * back ONE level and a harder pull closes the ENTIRE stack — the harder pull now
 * needs roughly HALF the drag it did, the one-level point stays where it was
 * (at most "very very little" earlier), and a resistance step between the two
 * stays felt.
 */
class BtSheetDragTuningTest {

    private class Device(val name: String, val heightPx: Float, val density: Float) {
        val model = sheetDragModel(heightPx, density)
        val heightDp get() = heightPx / density
        fun dp(fraction: Float) = fraction * heightDp
        fun px(dp: Float) = dp * density
    }

    private val devices = listOf(
        Device("phone", SheetDragDevices.PHONE_SHEET_PX, SheetDragDevices.PHONE_DENSITY),
        Device("tablet", SheetDragDevices.TABLET_SHEET_PX, SheetDragDevices.TABLET_DENSITY),
    )

    // The 2026-08-09 model this order replaces, as it shipped.
    private val oldDeadZone = 0.12f
    private val oldCloseAllFinger = 0.25f + (0.34f - 0.25f) / 0.50f // 0.43
    private val oldDepthOneClose = 0.50f
    private val oldFlingDp = 420f

    /** A real drag: [fingerPx] of finger in [stepPx] moves, haptic crossings counted. */
    private fun drag(d: Device, fingerPx: Float, stacked: Boolean, stepPx: Float = 8f): Pair<Float, Int> {
        var travel = 0f
        var moved = 0f
        var buzzes = 0
        while (moved < fingerPx) {
            val dy = minOf(stepPx, fingerPx - moved)
            val next = sheetDragTravel(travel, dy, d.heightPx, stacked, d.model)
            if (sheetNotchCrossed(travel, next, stacked, d.model)) buzzes++
            travel = next
            moved += dy
        }
        return travel to buzzes
    }

    // ── The order itself ────────────────────────────────────────────────────

    @Test
    fun `one-level fires at most a few percent earlier, and never later, on both devices`() {
        devices.forEach { d ->
            val ratio = d.model.backOne / oldDeadZone
            assertTrue("${d.name}: one-level moved to ${ratio * 100}% of its old place", ratio in 0.95f..1.0f)
        }
    }

    @Test
    fun `closing everything takes about half the old finger, on both devices`() {
        devices.forEach { d ->
            val ratio = sheetPullFor(d.model.closeAll, d.model) / oldCloseAllFinger
            assertTrue("${d.name}: close-all costs ${ratio * 100}% of the old pull", ratio in 0.40f..0.60f)
        }
    }

    @Test
    fun `a depth-1 sheet closes with the same pull, about half its old one`() {
        devices.forEach { d ->
            assertEquals(d.name, d.model.closeAllPull, sheetPullFor(d.model.closeAll, d.model), 1e-5f)
            val ratio = d.model.closeAllPull / oldDepthOneClose
            assertTrue("${d.name}: depth-1 close is ${ratio * 100}% of 0.50", ratio in 0.35f..0.55f)
        }
    }

    @Test
    fun `the close-all fling is half the old speed, the change-of-mind fling is not`() {
        devices.forEach { d ->
            assertEquals(d.name, d.px(oldFlingDp), d.model.flingPx, 1e-3f)
            assertEquals(d.name, d.model.flingPx / 2f, d.model.closeFlingPx, 1e-3f)
        }
    }

    // ── The band: a back-one drag never closes everything ──────────────────

    @Test
    fun `the band between the two stages is 96dp of finger on every density`() {
        devices.forEach { d ->
            val bandPx = (d.model.closeAllPull - d.model.backOne) * d.heightPx
            assertEquals(d.name, d.px(96f), bandPx, 0.5f)
        }
    }

    @Test
    fun `no back-one drag that stops short of the band end can close the stack`() {
        devices.forEach { d ->
            // 1px inside each end: exactly ON the one-level boundary an 8px-step
            // drag accumulates a float hair short of it, which is SETTLE, not a bug.
            val fromPx = d.model.backOne * d.heightPx + 1f
            val toPx = d.model.closeAllPull * d.heightPx - 2f
            var finger = fromPx
            while (finger <= toPx) {
                val (travel, buzzes) = drag(d, finger, stacked = true)
                assertEquals("${d.name} finger=${finger}px buzzed", 0, buzzes)
                listOf(0f, d.model.closeFlingPx, d.model.flingPx * 0.99f, d.model.flingPx * 20f).forEach { v ->
                    assertEquals(
                        "${d.name} finger=${finger}px v=$v",
                        SheetRelease.BACK_ONE,
                        sheetRelease(travel, v, stacked = true, d.model),
                    )
                }
                finger += 3f
            }
        }
    }

    @Test
    fun `just past the band the stack closes, with exactly one detent`() {
        devices.forEach { d ->
            val (travel, buzzes) = drag(d, d.model.closeAllPull * d.heightPx + 2f, stacked = true)
            assertEquals(d.name, SheetRelease.CLOSE_ALL, sheetRelease(travel, 0f, true, d.model))
            assertEquals(d.name, 1, buzzes)
            assertEquals(d.name, SheetStage.CLOSE_ALL, sheetStageOf(travel, true, d.model))
        }
    }

    @Test
    fun `the same finger dp past the one-level point means the same thing on phone and tablet`() {
        devices.forEach { d ->
            val p1 = d.model.backOne * d.heightPx
            fun outcome(pastDp: Float) =
                sheetRelease(drag(d, p1 + d.px(pastDp), true).first, 0f, true, d.model)
            assertEquals(d.name, SheetRelease.BACK_ONE, outcome(1f))
            assertEquals(d.name, SheetRelease.BACK_ONE, outcome(50f))
            assertEquals(d.name, SheetRelease.BACK_ONE, outcome(95f))
            assertEquals(d.name, SheetRelease.CLOSE_ALL, outcome(97f))
        }
    }

    // ── Velocity ────────────────────────────────────────────────────────────

    @Test
    fun `velocity alone never closes a nested stack`() {
        devices.forEach { d ->
            listOf(d.model.backOne, d.model.resistOnset, d.model.closeAll - 0.001f).forEach { t ->
                assertEquals(
                    "${d.name} travel=$t",
                    SheetRelease.BACK_ONE,
                    sheetRelease(t, d.model.flingPx * 50f, stacked = true, d.model),
                )
            }
        }
    }

    @Test
    fun `a depth-1 flick closes from the dead zone at the close-fling speed, not below it`() {
        devices.forEach { d ->
            val m = d.model
            assertEquals(d.name, SheetRelease.BACK_ONE, sheetRelease(m.backOne, m.closeFlingPx, false, m))
            assertEquals(d.name, SheetRelease.SETTLE, sheetRelease(m.backOne, m.closeFlingPx - 1f, false, m))
            assertEquals(d.name, SheetRelease.SETTLE, sheetRelease(m.closeAllPull - 0.001f, 0f, false, m))
        }
    }

    @Test
    fun `the dead zone beats every velocity, at both depths, on both devices`() {
        devices.forEach { d ->
            listOf(true, false).forEach { stacked ->
                listOf(0f, d.model.closeFlingPx, d.model.flingPx * 30f, -d.model.flingPx * 30f).forEach { v ->
                    assertEquals(
                        "${d.name} stacked=$stacked v=$v",
                        SheetRelease.SETTLE,
                        sheetRelease(d.model.backOne - 0.0005f, v, stacked, d.model),
                    )
                }
            }
        }
    }

    @Test
    fun `a flick back up still cancels at the unchanged speed`() {
        devices.forEach { d ->
            val m = d.model
            assertEquals(d.name, SheetRelease.SETTLE, sheetRelease(m.closeAll + 0.1f, -m.flingPx, true, m))
            assertEquals(d.name, SheetRelease.CLOSE_ALL, sheetRelease(m.closeAll + 0.1f, -m.closeFlingPx, true, m))
        }
    }

    // ── The resistance curve ────────────────────────────────────────────────

    @Test
    fun `the curve is monotonic and has no jump at the onset or at close-all`() {
        devices.forEach { d ->
            val m = d.model
            var previous = sheetTravelFor(0f, m)
            var pull = 0.0005f
            while (pull <= 1f) {
                val now = sheetTravelFor(pull, m)
                assertTrue("${d.name} pull=$pull went backwards", now >= previous)
                assertTrue("${d.name} pull=$pull jumped", now - previous <= 0.0005f + 1e-6f)
                previous = now
                pull += 0.0005f
            }
            listOf(m.resistOnset, m.closeAllPull).forEach { edge ->
                assertEquals("${d.name} edge=$edge", sheetTravelFor(edge - 1e-5f, m), sheetTravelFor(edge + 1e-5f, m), 5e-5f)
            }
        }
    }

    @Test
    fun `the resistance is a felt step, half the finger inside it and all of it either side`() {
        devices.forEach { d ->
            val m = d.model
            val e = 0.002f
            fun slope(at: Float) = (sheetTravelFor(at + e, m) - sheetTravelFor(at, m)) / e
            assertEquals(d.name, 1f, slope((m.backOne + m.resistOnset) / 2f - e), 1e-3f)
            assertEquals(d.name, BtSheetDragTuning.RESISTANCE, slope((m.resistOnset + m.closeAllPull) / 2f - e), 1e-3f)
            assertEquals(d.name, 1f, slope(m.closeAllPull + 0.05f), 1e-3f)
            // The stretch is 56dp of finger, so the sheet ends it 28dp behind.
            assertEquals(d.name, 28f, d.dp(m.closeAllPull - m.closeAll), 0.1f)
        }
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    @Test
    fun `the numbers the 2026-10-29 ledger quotes`() {
        // phone 882dp sheet / tablet 1173dp sheet, in finger dp.
        val phone = devices[0]
        assertEquals(101.5f, phone.dp(phone.model.backOne), 0.5f)
        assertEquals(141.5f, phone.dp(phone.model.resistOnset), 0.5f)
        assertEquals(197.5f, phone.dp(phone.model.closeAllPull), 0.5f)
        val tablet = devices[1]
        assertEquals(134.9f, tablet.dp(tablet.model.backOne), 0.5f)
        assertEquals(174.9f, tablet.dp(tablet.model.resistOnset), 0.5f)
        assertEquals(230.9f, tablet.dp(tablet.model.closeAllPull), 0.5f)
    }

    @Test
    fun `a short landscape sheet keeps the stages ordered and cheaper than before`() {
        // 2400x1080 phone on its side: 1080 - 63 - 21 = 996px, 379dp.
        val m = sheetDragModel(996f, SheetDragDevices.PHONE_DENSITY)
        assertTrue(m.toString(), m.backOne < m.resistOnset)
        assertTrue(m.toString(), m.resistOnset < m.closeAll)
        assertTrue(m.toString(), m.closeAll < m.closeAllPull)
        assertTrue(m.toString(), m.closeAllPull <= BtSheetDragTuning.PULL_CEILING)
        assertTrue("close-all must not cost more than it did", m.closeAllPull < oldCloseAllFinger)
    }

    @Test
    fun `a tiny or unmeasured sheet resolves to finite, ordered thresholds`() {
        listOf(0f, -1f, 120f, 300f).forEach { h ->
            val m = sheetDragModel(h, SheetDragDevices.PHONE_DENSITY)
            listOf(m.backOne, m.resistOnset, m.closeAll, m.closeAllPull).forEach {
                assertFalse("h=$h $m", it.isNaN() || it.isInfinite())
            }
            assertTrue("h=$h $m", m.backOne <= m.resistOnset && m.resistOnset <= m.closeAll)
            assertTrue("h=$h $m", m.closeAll <= m.closeAllPull && m.closeAllPull <= BtSheetDragTuning.PULL_CEILING)
        }
    }
}
