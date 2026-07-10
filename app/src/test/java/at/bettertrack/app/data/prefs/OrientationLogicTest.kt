package at.bettertrack.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Orientation-lock preference logic (owner ask 2026-07-10). ON (default) pins the
 * app to portrait; OFF lets it follow the device sensor.
 */
class OrientationLogicTest {

    @Test
    fun `locked maps to portrait`() {
        assertEquals(ScreenOrientationMode.LOCKED_PORTRAIT, orientationModeFor(true))
    }

    @Test
    fun `unlocked follows the sensor`() {
        assertEquals(ScreenOrientationMode.FOLLOW_SENSOR, orientationModeFor(false))
    }
}
