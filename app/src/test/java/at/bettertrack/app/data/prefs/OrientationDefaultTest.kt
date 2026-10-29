package at.bettertrack.app.data.prefs

import at.bettertrack.app.vault.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orientation lock's default is per DEVICE class (T1 large-screen
 * foundation, 2026-10-29): locked on phones, unlocked on tablets — and an
 * explicit choice always wins.
 */
class OrientationDefaultTest {

    private val prefs = FakeSharedPreferences()

    // ── pure rule ─────────────────────────────────────────────────────────────

    @Test
    fun `sw600 is the tablet line`() {
        assertEquals(BtDeviceClass.Phone, btDeviceClassFor(320))
        assertEquals(BtDeviceClass.Phone, btDeviceClassFor(411))
        assertEquals(BtDeviceClass.Phone, btDeviceClassFor(599))
        assertEquals(BtDeviceClass.Tablet, btDeviceClassFor(600))
        // The owner's Galaxy Tab S8 (1600px short edge at 340dpi).
        assertEquals(BtDeviceClass.Tablet, btDeviceClassFor(753))
    }

    @Test
    fun `phones default to locked, tablets to unlocked`() {
        assertTrue(defaultOrientationLocked(BtDeviceClass.Phone))
        assertFalse(defaultOrientationLocked(BtDeviceClass.Tablet))
    }

    @Test
    fun `an absent choice falls back to the device default`() {
        assertTrue(resolveOrientationLocked(stored = null, deviceClass = BtDeviceClass.Phone))
        assertFalse(resolveOrientationLocked(stored = null, deviceClass = BtDeviceClass.Tablet))
    }

    @Test
    fun `a stored choice wins on every device class`() {
        BtDeviceClass.entries.forEach { device ->
            assertTrue("$device, stored true", resolveOrientationLocked(stored = true, deviceClass = device))
            assertFalse("$device, stored false", resolveOrientationLocked(stored = false, deviceClass = device))
        }
    }

    // ── the store ─────────────────────────────────────────────────────────────

    @Test
    fun `a phone that never chose is locked`() {
        assertTrue(DevicePrefs(prefs, deviceClass = BtDeviceClass.Phone).orientationLockedNow())
    }

    @Test
    fun `a tablet that never chose is unlocked`() {
        assertFalse(DevicePrefs(prefs, deviceClass = BtDeviceClass.Tablet).orientationLockedNow())
    }

    @Test
    fun `a tablet whose user locked it stays locked`() {
        prefs.edit().putBoolean(KEY, true).apply()

        assertTrue(DevicePrefs(prefs, deviceClass = BtDeviceClass.Tablet).orientationLockedNow())
    }

    @Test
    fun `a phone whose user unlocked it stays unlocked`() {
        prefs.edit().putBoolean(KEY, false).apply()

        assertFalse(DevicePrefs(prefs, deviceClass = BtDeviceClass.Phone).orientationLockedNow())
    }

    @Test
    fun `reading never writes the default back`() {
        // If the computed default were persisted, the same install on its next
        // device class would inherit this one's default as if it were a choice.
        DevicePrefs(prefs, deviceClass = BtDeviceClass.Tablet).orientationLockedNow()

        assertFalse("the default was persisted as a choice", prefs.contains(KEY))
    }

    @Test
    fun `the flow agrees with the synchronous read`() {
        val devicePrefs = DevicePrefs(prefs, deviceClass = BtDeviceClass.Tablet)

        assertEquals(devicePrefs.orientationLockedNow(), devicePrefs.orientationLocked.value)
    }

    @Test
    fun `a choice made on a tablet survives the next launch`() {
        DevicePrefs(prefs, deviceClass = BtDeviceClass.Tablet).setOrientationLocked(true)

        assertTrue(prefs.contains(KEY))
        assertTrue(
            "the explicit lock was lost to the tablet default on a cold start",
            DevicePrefs(prefs, deviceClass = BtDeviceClass.Tablet).orientationLockedNow(),
        )
    }

    @Test
    fun `the test constructor's default is the phone app`() {
        // Every pre-T1 test constructs DevicePrefs(prefs); it must keep seeing
        // the behaviour it was written against.
        assertTrue(DevicePrefs(prefs).orientationLockedNow())
    }

    private companion object {
        /** The literal key — a rename must fail here, not migrate silently. */
        const val KEY = "orientation_locked"
    }
}
