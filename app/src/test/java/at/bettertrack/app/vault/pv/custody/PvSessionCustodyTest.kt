package at.bettertrack.app.vault.pv.custody

import at.bettertrack.app.vault.Argon2Derive
import at.bettertrack.app.vault.FakeSharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12 as amended 2026-09-03 — the device-scoped session (vault pass 2026-10-30
 * item 4): persistence across process death, the absolute seven-day TTL, the
 * device-locked marker written FIRST on every user-intended lock and cleared
 * only by a password, and every [PvDeviceCustody.resumeSession] refusal.
 *
 * "Process death" is a SECOND [PvDeviceCustody] over the same prefs, the same
 * [MemoryPvSessionPersistence] and the same marker — exactly what survives a
 * restart on a device. Argon2id is faked for the reason [PvDeviceCustodyTest]
 * gives.
 */
class PvSessionCustodyTest {

    private val fakeArgon2 = Argon2Derive { password, salt, _, _, _, hashLength ->
        val seed = password.fold(7) { acc, byte -> acc * 31 + byte } +
            salt.fold(11) { acc, byte -> acc * 17 + byte }
        ByteArray(hashLength) { index -> (seed + index * 13).toByte() }
    }

    private class TestClock(var now: Long = 1_000L) : PvElapsedClock {
        override fun elapsedMillis(): Long = now
    }

    /** One device: what outlives the process. */
    private inner class Device {
        val prefs = FakeSharedPreferences()
        val persistence = MemoryPvSessionPersistence()
        val marker = MemoryPvDeviceLockMarker()
        var wallNow = 1_760_000_000_000L
        val elapsed = TestClock()

        fun process(
            sessionPersistence: PvSessionPersistence = persistence,
            deviceLockMarker: PvDeviceLockMarker = marker,
        ) = PvDeviceCustody(
            keystore = PvEndpointKeystore(prefs),
            kdfDispatcher = UnconfinedTestDispatcher(),
            randomBytes = { length -> ByteArray(length) { (ivCounter++ * 7 + it).toByte() } },
            argon2 = fakeArgon2,
            clock = elapsed,
            sessionPersistence = sessionPersistence,
            deviceLockMarker = deviceLockMarker,
            wallClock = { wallNow },
        )
    }

    private var ivCounter = 0
    private val accountA = "018f0000-0000-7000-8000-00000000a001"
    private val accountB = "018f0000-0000-7000-8000-00000000b002"
    private val vault = "018f0000-0000-7000-8000-0000000000a1"
    private val password = "not-a-real-password-1"
    private fun entropy(seed: Int) = ByteArray(PV_ENTROPY_BYTES) { (seed + it * 3).toByte() }

    /** A live, persisted session for [accountA] holding one wrapped phrase. */
    private fun unlockedOn(device: Device): PvDeviceCustody = runBlocking {
        device.process().also {
            it.bindAccount(accountA)
            assertTrue(it.setDevicePassword(password))
            assertTrue(it.storeEntropy(vault, entropy(1), PvCustodyMode.WRAPPED))
        }
    }

    // ── Persistence and the absolute TTL ────────────────────────────────────

    @Test
    fun `a password session persists with a seven-day absolute TTL and clears the marker`() {
        val device = Device()
        device.marker.remember(accountA) // left by an earlier lock
        unlockedOn(device)
        assertEquals(1, device.persistence.size())
        assertEquals(device.wallNow + PV_SESSION_PERSISTENCE_TTL_MS, device.persistence.expiresAtOf(accountA))
        assertEquals(PvDeviceLockState.CLEAR, device.marker.read(accountA))
    }

    @Test
    fun `the session survives process death and resumes without the password`() {
        val device = Device()
        unlockedOn(device)

        val next = device.process()
        next.bindAccount(accountA)
        assertFalse(next.unlocked.value)
        assertEquals(PvResumeOutcome.RESUMED, next.resumeSession())
        assertTrue(next.unlocked.value)
        assertArrayEquals(entropy(1), next.entropyFor(vault))
        assertEquals(PvResumeOutcome.ALREADY_UNLOCKED, next.resumeSession())
    }

    @Test
    fun `a resumed session never re-persists, so the TTL stays absolute from the creating unlock`() {
        val device = Device()
        unlockedOn(device)
        val createdExpiry = device.persistence.expiresAtOf(accountA)!!

        device.wallNow += 3L * 24 * 60 * 60 * 1000 // three days later
        val next = device.process().apply { bindAccount(accountA) }
        assertEquals(PvResumeOutcome.RESUMED, next.resumeSession())
        assertEquals("resuming restarted the clock", createdExpiry, device.persistence.expiresAtOf(accountA))

        // The resumed in-memory session ends at the record's instant, not 7 days from the resume.
        device.wallNow = createdExpiry - 1
        assertTrue(next.unlocked.value)
        device.wallNow = createdExpiry
        assertNull(next.entropyFor(vault))
        assertFalse(next.unlocked.value)
        assertEquals(PvLockReason.TTL, next.lastLockReason)
        assertEquals(0, device.persistence.size())
    }

    @Test
    fun `the TTL ends a long-lived password session on the next hot read, without a timer`() {
        val device = Device()
        val custody = unlockedOn(device)
        device.wallNow += PV_SESSION_PERSISTENCE_TTL_MS - 1
        assertEquals(PvCustodyState.Wrapped(vault, sessionUnlocked = true), custody.stateFor(vault))
        device.wallNow += 1
        assertEquals(PvCustodyState.Wrapped(vault, sessionUnlocked = false), custody.stateFor(vault))
        assertEquals(PvLockReason.TTL, custody.lastLockReason)
        // Not user-intended: no marker, so the next password is all it takes.
        assertEquals(PvDeviceLockState.CLEAR, device.marker.read(accountA))
    }

    @Test
    fun `an expired record is dropped and nothing resumes`() {
        val device = Device()
        unlockedOn(device)
        device.wallNow += PV_SESSION_PERSISTENCE_TTL_MS
        val next = device.process().apply { bindAccount(accountA) }
        assertEquals(PvResumeOutcome.NOTHING_PERSISTED, next.resumeSession())
        assertEquals(0, device.persistence.size())
        assertFalse(next.unlocked.value)
    }

    @Test
    fun `nothing persists without a bound account`() = runBlocking {
        val device = Device()
        val custody = device.process()
        assertTrue(custody.setDevicePassword(password))
        assertEquals(0, device.persistence.size())
        assertEquals(PvResumeOutcome.ALREADY_UNLOCKED, custody.resumeSession())
        custody.lock()
        assertEquals(PvResumeOutcome.NO_ACCOUNT, custody.resumeSession())
    }

    // ── The marker: first on every user-intended lock ───────────────────────

    /** Records the order of marker writes and record deletes, and the session state at each. */
    private class Journal {
        val events = mutableListOf<String>()
        var custody: PvDeviceCustody? = null
        private fun live() = if (custody?.unlocked?.value == true) "live" else "ended"

        fun marker(inner: PvDeviceLockMarker) = object : PvDeviceLockMarker by inner {
            override fun remember(accountId: String) {
                events += "marker ${live()}"
                inner.remember(accountId)
            }
        }

        fun persistence(inner: PvSessionPersistence) = object : PvSessionPersistence by inner {
            override fun clear(accountId: String) {
                events += "clear ${live()}"
                inner.clear(accountId)
            }
        }
    }

    @Test
    fun `every user-intended lock writes the marker FIRST, while the session still stands`() = runBlocking {
        for (reason in PvLockReason.entries.filter { it.userIntended && it != PvLockReason.ACCOUNT_SWITCH }) {
            val device = Device()
            val journal = Journal()
            val custody = device.process(journal.persistence(device.persistence), journal.marker(device.marker))
            journal.custody = custody
            custody.bindAccount(accountA)
            assertTrue(custody.setDevicePassword(password))

            custody.lock(reason)
            assertEquals("$reason", listOf("marker live", "clear ended"), journal.events)
            assertEquals(reason, custody.lastLockReason)
            assertEquals(PvDeviceLockState.LOCKED, device.marker.read(accountA))
            assertEquals(0, device.persistence.size())
        }
    }

    @Test
    fun `an account switch is the previous account's lock - marker first, its record gone`() = runBlocking {
        val device = Device()
        val journal = Journal()
        val custody = device.process(journal.persistence(device.persistence), journal.marker(device.marker))
        journal.custody = custody
        custody.bindAccount(accountA)
        assertTrue(custody.setDevicePassword(password))

        custody.bindAccount(accountB)
        assertEquals(listOf("marker live", "clear ended"), journal.events)
        assertEquals(PvLockReason.ACCOUNT_SWITCH, custody.lastLockReason)
        assertEquals(accountB, custody.boundAccountId())
        assertEquals("B never resumes A's session", PvResumeOutcome.NOTHING_PERSISTED, custody.resumeSession())

        custody.bindAccount(accountA)
        assertEquals("signing back in as A needs the password", PvResumeOutcome.DEVICE_LOCKED, custody.resumeSession())
    }

    @Test
    fun `the first bind is not a change and binding the same account twice locks nothing`() = runBlocking {
        val device = Device()
        val custody = unlockedOn(device)
        custody.bindAccount(" $accountA ")
        assertTrue(custody.unlocked.value)
        assertNull(custody.lastLockReason)
    }

    @Test
    fun `the TTL lock writes no marker but still drops the record`() = runBlocking {
        val device = Device()
        val journal = Journal()
        val custody = device.process(journal.persistence(device.persistence), journal.marker(device.marker))
        journal.custody = custody
        custody.bindAccount(accountA)
        assertTrue(custody.setDevicePassword(password))

        custody.lock(PvLockReason.TTL)
        assertEquals(listOf("clear ended"), journal.events)
        assertEquals(PvDeviceLockState.CLEAR, device.marker.read(accountA))
    }

    @Test
    fun `a set marker keeps a record inert even when deleting it failed`() {
        val device = Device()
        val undeletable = object : PvSessionPersistence by device.persistence {
            override fun clear(accountId: String) = Unit
        }
        val custody = runBlocking {
            device.process(sessionPersistence = undeletable).apply {
                bindAccount(accountA)
                assertTrue(setDevicePassword(password))
            }
        }
        custody.lock(PvLockReason.SIGN_OUT)
        assertEquals("the delete failed", 1, device.persistence.size())

        val next = device.process().apply { bindAccount(accountA) }
        assertEquals(PvResumeOutcome.DEVICE_LOCKED, next.resumeSession())
        assertFalse(next.unlocked.value)
    }

    @Test
    fun `a marker that cannot be written still ends the session`() = runBlocking {
        val device = Device()
        val throwing = object : PvDeviceLockMarker by device.marker {
            override fun remember(accountId: String) = throw IllegalStateException("store wedged")
        }
        val custody = device.process(deviceLockMarker = throwing)
        custody.bindAccount(accountA)
        assertTrue(custody.setDevicePassword(password))
        custody.lock()
        assertFalse(custody.unlocked.value)
        assertEquals(0, device.persistence.size())
    }

    @Test
    fun `an unreadable marker refuses the resume`() {
        val device = Device()
        unlockedOn(device)
        device.marker.unreadable = true
        val next = device.process().apply { bindAccount(accountA) }
        assertEquals(PvResumeOutcome.MARKER_UNREADABLE, next.resumeSession())
        assertFalse(next.unlocked.value)
    }

    @Test
    fun `only a successful password unlock clears the marker`() = runBlocking {
        val device = Device()
        unlockedOn(device).lock(PvLockReason.EXPLICIT)

        val next = device.process().apply { bindAccount(accountA) }
        assertTrue(next.unlock("not-the-password-at-all") is PvUnlockResult.Wrong)
        assertEquals(PvDeviceLockState.LOCKED, device.marker.read(accountA))
        assertEquals(PvResumeOutcome.DEVICE_LOCKED, next.resumeSession())

        assertEquals(PvUnlockResult.Success, next.unlock(password))
        assertEquals(PvDeviceLockState.CLEAR, device.marker.read(accountA))
        val third = device.process().apply { bindAccount(accountA) }
        assertEquals(PvResumeOutcome.RESUMED, third.resumeSession())
    }

    // ── The other resume refusals ───────────────────────────────────────────

    @Test
    fun `a candidate that fails the wrap check is never installed and is dropped`() {
        val device = Device()
        unlockedOn(device)
        device.persistence.persist(accountA, ByteArray(32) { 0x5a }, device.wallNow + 1_000)
        val next = device.process().apply { bindAccount(accountA) }
        assertEquals(PvResumeOutcome.WRAP_CHECK_FAILED, next.resumeSession())
        assertFalse(next.unlocked.value)
        assertNull(next.entropyFor(vault))
        assertEquals(0, device.persistence.size())
    }

    @Test
    fun `a resume inside a password lockout window is refused`() = runBlocking {
        val device = Device()
        unlockedOn(device)
        val next = device.process().apply { bindAccount(accountA) }
        repeat(PV_LOCKOUT_FREE_ATTEMPTS) { next.unlock("not-the-password-at-all") }
        assertTrue(next.currentLockoutRemaining() > 0)
        assertEquals(PvResumeOutcome.LOCKED_OUT, next.resumeSession())
    }

    @Test
    fun `a reset endpoint resumes nothing and the reset itself is a user-intended lock`() = runBlocking {
        val device = Device()
        val custody = unlockedOn(device)
        custody.resetKeystore()
        assertEquals(PvLockReason.EXPLICIT, custody.lastLockReason)
        assertEquals(PvDeviceLockState.LOCKED, device.marker.read(accountA))
        assertEquals(0, device.persistence.size())

        // A record left by a sibling store is residue on a reset endpoint.
        device.marker.forget(accountA)
        device.persistence.persist(accountA, ByteArray(32) { 1 }, device.wallNow + 1_000)
        assertEquals(PvResumeOutcome.NO_KEYSTORE, custody.resumeSession())
        assertEquals(0, device.persistence.size())

        // The ceremony's first password is a proven password: the marker goes.
        device.marker.remember(accountA)
        assertTrue(custody.setDevicePassword(password))
        assertEquals(PvDeviceLockState.CLEAR, device.marker.read(accountA))
    }

    @Test
    fun `a password change re-persists under the new key`() = runBlocking {
        val device = Device()
        val custody = unlockedOn(device)
        assertTrue(custody.changeDevicePassword(password, "not-a-real-password-2"))
        val next = device.process().apply { bindAccount(accountA) }
        assertEquals(PvResumeOutcome.RESUMED, next.resumeSession())
        assertArrayEquals(entropy(1), next.entropyFor(vault))
    }

    // ── The one timer ───────────────────────────────────────────────────────

    @Test
    fun `the cold-start PIN gate is not an idle lock, the next locked edge is`() = runTest {
        val device = Device()
        unlockedOn(device)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val next = device.process()
        next.bindAccount(accountA)
        val appLocked = MutableStateFlow(true) // a configured PIN: every cold start begins locked
        val job = next.bindToAppLock(appLocked, backgroundScope + dispatcher)

        assertEquals("the gate wrote no marker", PvDeviceLockState.CLEAR, device.marker.read(accountA))
        appLocked.value = false // PIN entered
        assertEquals(PvResumeOutcome.RESUMED, next.resumeSession())

        appLocked.value = true // idle timeout while the session stands
        assertFalse(next.unlocked.value)
        assertEquals(PvLockReason.APP_LOCK_IDLE, next.lastLockReason)
        assertEquals(PvDeviceLockState.LOCKED, device.marker.read(accountA))
        assertEquals(0, device.persistence.size())
        job.cancel()
    }
}
