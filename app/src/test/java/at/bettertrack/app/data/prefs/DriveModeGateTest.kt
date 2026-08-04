package at.bettertrack.app.data.prefs

import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.effective
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The W4 release-build guarantee: *"Flag off ⇒ release build unchanged"*
 * (S3/S4 plan §5 W4).
 *
 * W4 ships the entire Drive medium but W5 ships the wizard that lets a user
 * choose it. In between, DRIVE mode must be reachable on a debug build and
 * reachable **by nothing at all** in a release build — not by a stale prefs
 * file, not by a mis-set stored mode, not by a bug in a code path that has not
 * shipped yet.
 *
 * [gatedStorageMode] is the whole mechanism, and it is a filter on the stored
 * mode rather than a second boolean scattered through the code precisely so that
 * the guarantee is structural: the mode a release APK can act on *cannot* be
 * DRIVE, whatever is persisted. A flag every call site had to remember to check
 * would be a hope, not a property.
 *
 * (The pure rule is tested here; `DriveModeGate` itself only adds the
 * `BuildConfig.DEBUG` read and the prefs file, exactly as `DevOriginOverride`
 * does for the S1 origin override.)
 */
class DriveModeGateTest {

    @Test
    fun releaseBuildsCanNeverActOnADriveHoldingMode() {
        assertEquals(
            "a persisted DRIVE mode is inert without the gate",
            StorageMode.SERVER,
            gatedStorageMode(StorageMode.DRIVE, driveEnabled = false),
        )
        assertEquals(
            "so is BOTH — it also holds a vault",
            StorageMode.SERVER,
            gatedStorageMode(StorageMode.BOTH, driveEnabled = false),
        )
    }

    @Test
    fun theGateLetsDriveModeThroughWhenItIsEnabled() {
        assertEquals(StorageMode.DRIVE, gatedStorageMode(StorageMode.DRIVE, driveEnabled = true))
        assertEquals(StorageMode.BOTH, gatedStorageMode(StorageMode.BOTH, driveEnabled = true))
    }

    /**
     * `UNSET` is left alone rather than mapped to SERVER: it already *behaves* as
     * SERVER through [effective], and collapsing it here would destroy the one
     * distinction W5's wizard needs — "never asked" versus "chose the server".
     */
    @Test
    fun leavesTheServerAndUnsetModesExactlyAsTheyAre() {
        for (enabled in listOf(true, false)) {
            assertEquals(StorageMode.SERVER, gatedStorageMode(StorageMode.SERVER, enabled))
            assertEquals(StorageMode.UNSET, gatedStorageMode(StorageMode.UNSET, enabled))
        }
    }

    /** Whatever is stored, a gated release build resolves to today's behaviour. */
    @Test
    fun everyStoredModeResolvesToServerBehaviourWhenGated() {
        for (stored in StorageMode.entries) {
            assertEquals(
                "stored=$stored must behave as SERVER on a release build",
                StorageMode.SERVER,
                gatedStorageMode(stored, driveEnabled = false).effective,
            )
        }
    }
}
