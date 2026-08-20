package at.bettertrack.app.vault.pv.custody

import at.bettertrack.app.vault.Argon2Derive
import at.bettertrack.app.vault.FakeSharedPreferences
import at.bettertrack.app.vault.VAULT_ARGON2_PARAMS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The §12 custody state machine: wrap, unlock, refuse, throttle, reset.
 *
 * ## Why Argon2id is faked here
 *
 * `VaultConformanceTest` already proves the real derivation is byte-identical
 * to the platform's published `kekBase64` at m = 64 MiB, t = 3, p = 1, with the
 * real Bouncy Castle generator. Re-paying 64 MiB of hashing per case here would
 * add seconds of CI to re-prove that. What this file tests is everything
 * *around* the derivation, so the KDF is injected as a cheap deterministic
 * function — and the profile itself is asserted separately below, because a
 * silently "tuned" profile is exactly the regression a fake KDF would hide.
 *
 * ## Nothing here names a secret
 *
 * Entropy is a byte pattern, passwords are obviously-fake literals, and no
 * assertion message formats a payload. That is the same rule the production
 * code follows and `PvCustodySourceDisciplineTest` enforces.
 */
class PvDeviceCustodyTest {

    /** Deterministic per (password, salt), 32 bytes — a stand-in, never a KDF. */
    private val fakeArgon2 = Argon2Derive { password, salt, _, _, _, hashLength ->
        val seed = password.fold(7) { acc, byte -> acc * 31 + byte } +
            salt.fold(11) { acc, byte -> acc * 17 + byte }
        ByteArray(hashLength) { index -> (seed + index * 13).toByte() }
    }

    /** A test clock the ladder can be driven against without waiting. */
    private class TestClock(var now: Long = 1_000L) : PvElapsedClock {
        override fun elapsedMillis(): Long = now
    }

    private var ivCounter = 0

    private fun custody(
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        clock: PvElapsedClock = TestClock(),
    ) = PvDeviceCustody(
        keystore = PvEndpointKeystore(prefs),
        kdfDispatcher = UnconfinedTestDispatcher(),
        // Distinct per call so two seals of the same plaintext differ, the way
        // a real CSPRNG's would — a fixed IV would let a broken wrap look fine.
        randomBytes = { length -> ByteArray(length) { (ivCounter++ * 7 + it).toByte() } },
        argon2 = fakeArgon2,
        clock = clock,
    )

    private val vaultA = "018f0000-0000-7000-8000-0000000000a1"
    private val vaultB = "018f0000-0000-7000-8000-0000000000b2"

    private fun entropy(seed: Int) = ByteArray(PV_ENTROPY_BYTES) { (seed + it * 3).toByte() }

    private val password = "not-a-real-password-1"
    private val otherPassword = "not-a-real-password-2"

    // ── Wrapped custody ─────────────────────────────────────────────────────

    @Test
    fun `a fresh endpoint has no password, no entries and a locked session`() {
        val custody = custody()
        assertFalse(custody.hasDevicePassword)
        assertFalse(custody.unlocked.value)
        assertEquals(emptyList<String>(), custody.storedVaultIds())
        assertEquals(PvCustodyState.Absent(vaultA), custody.stateFor(vaultA))
    }

    @Test
    fun `wrapped custody round trips through a locked session`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val original = entropy(5)

        val first = custody(prefs)
        assertTrue(first.setDevicePassword(password))
        assertTrue("choosing the password opens the session", first.unlocked.value)
        assertTrue(first.storeEntropy(vaultA, original, PvCustodyMode.WRAPPED))

        // Locking is the whole point: the payload survives, the ability to read
        // it does not.
        first.lock()
        assertFalse(first.unlocked.value)
        assertNull("a locked session must not open a wrapped entry", first.entropyFor(vaultA))
        assertEquals(PvCustodyState.Wrapped(vaultA, sessionUnlocked = false), first.stateFor(vaultA))

        // A second process over the same storage — nothing about the session
        // survived, but everything about the entry did.
        val next = custody(prefs)
        assertTrue(next.hasDevicePassword)
        assertFalse(next.unlocked.value)
        assertEquals(PvUnlockResult.Success, next.unlock(password))
        assertArrayEquals(original, next.entropyFor(vaultA))
        assertEquals(PvCustodyState.Wrapped(vaultA, sessionUnlocked = true), next.stateFor(vaultA))
    }

    @Test
    fun `one password opens every wrapped phrase on the endpoint`() = runBlocking {
        // §12's "one device password per endpoint (never per vault)" made
        // observable: a single unlock reads both vaults.
        val custody = custody()
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)
        custody.storeEntropy(vaultB, entropy(9), PvCustodyMode.WRAPPED)
        custody.lock()

        assertEquals(PvUnlockResult.Success, custody.unlock(password))
        assertArrayEquals(entropy(1), custody.entropyFor(vaultA))
        assertArrayEquals(entropy(9), custody.entropyFor(vaultB))
    }

    @Test
    fun `a wrong password is refused locally and never yields the payload`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val custody = custody(prefs)
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(3), PvCustodyMode.WRAPPED)
        custody.lock()

        val result = custody.unlock(otherPassword)
        assertTrue("a wrong password must be a Wrong, not a throw", result is PvUnlockResult.Wrong)
        assertEquals(1, (result as PvUnlockResult.Wrong).failureCount)
        assertEquals(0L, result.lockoutMillis)
        assertFalse(custody.unlocked.value)
        assertNull(custody.entropyFor(vaultA))

        // And the entry is untouched — a refused attempt is not a mutation.
        assertEquals(PvCustodyMode.WRAPPED, PvEndpointKeystore(prefs).entry(vaultA)?.custody)
        assertEquals(PvUnlockResult.Success, custody.unlock(password))
    }

    @Test
    fun `a wrapped payload is bound to its own vault id`() = runBlocking {
        // The vault id rides as GCM additional data, so a payload moved between
        // entries fails the tag instead of silently attaching the wrong phrase.
        val prefs = FakeSharedPreferences()
        val custody = custody(prefs)
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(4), PvCustodyMode.WRAPPED)

        val keystore = PvEndpointKeystore(prefs)
        val stolen = keystore.entry(vaultA)!!.payload
        keystore.putEntry(PvKeystoreEntry(vaultB, PvCustodyMode.WRAPPED, stolen))

        assertNotNull(custody.entropyFor(vaultA))
        assertNull("a payload replanted under another vault id must not open", custody.entropyFor(vaultB))
    }

    @Test
    fun `wrapped storage without a session is refused rather than silently plain`() {
        val custody = custody()
        assertFalse(
            "sealing needs K_dev; falling back to plain would be a downgrade nobody chose",
            custody.storeEntropy(vaultA, entropy(2), PvCustodyMode.WRAPPED),
        )
        assertEquals(PvCustodyState.Absent(vaultA), custody.stateFor(vaultA))
    }

    // ── Plain custody ───────────────────────────────────────────────────────

    @Test
    fun `plain custody round trips with no password at all`() {
        // §21/§2's warned option: no device password on the endpoint, no
        // session, and the vault still opens.
        val prefs = FakeSharedPreferences()
        val custody = custody(prefs)
        val original = entropy(7)

        assertTrue(custody.storeEntropy(vaultA, original, PvCustodyMode.PLAIN))
        assertFalse("plain custody must not require a device password", custody.hasDevicePassword)
        assertEquals(PvCustodyState.Plain(vaultA), custody.stateFor(vaultA))
        assertArrayEquals(original, custody.entropyFor(vaultA))

        // Across a process boundary, still no prompt.
        val next = custody(prefs)
        assertFalse(next.unlocked.value)
        assertArrayEquals(original, next.entropyFor(vaultA))
    }

    @Test
    fun `a plain entry stores the entropy itself and a wrapped one does not`() {
        // The entry format, asserted at the storage layer: plain is base64 of
        // the 16 bytes; wrapped is iv12 ‖ ciphertext ‖ tag and shares no prefix
        // with the plaintext.
        val prefs = FakeSharedPreferences()
        val custody = custody(prefs)
        val original = entropy(8)
        custody.storeEntropy(vaultA, original, PvCustodyMode.PLAIN)

        val keystore = PvEndpointKeystore(prefs)
        val plain = keystore.entry(vaultA)!!
        assertEquals("plain", plain.custody.wire)
        assertArrayEquals(original, Base64.getDecoder().decode(plain.payload))

        runBlocking {
            custody.setDevicePassword(password)
            custody.storeEntropy(vaultB, original, PvCustodyMode.WRAPPED)
        }
        val wrapped = keystore.entry(vaultB)!!
        assertEquals("wrapped", wrapped.custody.wire)
        val bytes = Base64.getDecoder().decode(wrapped.payload)
        // 12-byte IV + 16 bytes of entropy + a 16-byte GCM tag.
        assertEquals(44, bytes.size)
        assertFalse(
            "a wrapped payload must not contain the entropy in the clear",
            bytes.toList().windowed(original.size).any { it == original.toList() },
        )
    }

    @Test
    fun `custody can be flipped both ways while the session is open`() = runBlocking {
        val custody = custody()
        val original = entropy(6)
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, original, PvCustodyMode.WRAPPED)

        assertTrue(custody.setCustodyMode(vaultA, PvCustodyMode.PLAIN))
        assertEquals(PvCustodyState.Plain(vaultA), custody.stateFor(vaultA))
        assertArrayEquals(original, custody.entropyFor(vaultA))

        assertTrue(custody.setCustodyMode(vaultA, PvCustodyMode.WRAPPED))
        assertEquals(PvCustodyState.Wrapped(vaultA, sessionUnlocked = true), custody.stateFor(vaultA))
        assertArrayEquals(original, custody.entropyFor(vaultA))

        custody.lock()
        assertFalse(
            "re-wrapping must prompt for the password, not happen behind a locked session",
            custody.setCustodyMode(vaultA, PvCustodyMode.PLAIN),
        )
    }

    // ── The lockout ladder, on the real state machine ───────────────────────

    @Test
    fun `five wrong attempts open a thirty second window that then expires`() = runBlocking {
        val clock = TestClock()
        val custody = custody(clock = clock)
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)
        custody.lock()

        repeat(4) { attempt ->
            val wrong = custody.unlock(otherPassword) as PvUnlockResult.Wrong
            assertEquals(attempt + 1, wrong.failureCount)
            assertEquals("attempt ${attempt + 1} must still be free", 0L, wrong.lockoutMillis)
        }

        val fifth = custody.unlock(otherPassword) as PvUnlockResult.Wrong
        assertEquals(5, fifth.failureCount)
        assertEquals(PV_LOCKOUT_BASE_MS, fifth.lockoutMillis)

        // Inside the window even the RIGHT password is refused without deriving.
        val blocked = custody.unlock(password)
        assertTrue(blocked is PvUnlockResult.LockedOut)
        assertEquals(PV_LOCKOUT_BASE_MS, (blocked as PvUnlockResult.LockedOut).remainingMillis)
        assertFalse(custody.unlocked.value)

        clock.now += PV_LOCKOUT_BASE_MS - 1
        assertTrue(custody.unlock(password) is PvUnlockResult.LockedOut)

        clock.now += 1
        assertEquals(PvUnlockResult.Success, custody.unlock(password))
        assertEquals("a success clears the ladder", 0, custody.failureCount)
        assertEquals(0L, custody.currentLockoutRemaining())
    }

    @Test
    fun `the window doubles per further miss and stops at the cap`() = runBlocking {
        val clock = TestClock()
        val custody = custody(clock = clock)
        custody.setDevicePassword(password)
        custody.lock()

        val observed = mutableListOf<Long>()
        repeat(10) {
            clock.now += PV_LOCKOUT_CAP_MS + 1_000L // step past whatever window is open
            observed += (custody.unlock(otherPassword) as PvUnlockResult.Wrong).lockoutMillis
        }
        assertEquals(
            listOf(0L, 0L, 0L, 0L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L),
            observed,
        )
    }

    @Test
    fun `the ladder survives a process restart but not a clock restart`() = runBlocking {
        val prefs = FakeSharedPreferences()
        // Hours of uptime, so the stored deadline is far past what any rung of
        // the ladder could still legitimately mean after a reboot.
        val clock = TestClock(now = 10_000_000L)
        val first = custody(prefs, clock)
        first.setDevicePassword(password)
        first.lock()
        repeat(5) { first.unlock(otherPassword) }
        assertEquals(PV_LOCKOUT_BASE_MS, first.currentLockoutRemaining())

        // A force-kill must not reset a backoff, so the counters are persisted.
        val restarted = custody(prefs, clock)
        assertEquals(5, restarted.failureCount)
        assertEquals(PV_LOCKOUT_BASE_MS, restarted.currentLockoutRemaining())

        // A reboot restarts elapsed-realtime near zero, which would otherwise
        // leave a deadline further out than the ladder's own ceiling. That is a
        // restarted clock, not a lockout.
        val rebooted = custody(prefs, TestClock(now = 0L))
        assertEquals(0L, rebooted.currentLockoutRemaining())
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    @Test
    fun `the keystore reset deletes this endpoint's entries and nothing else exists to delete`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val custody = custody(prefs)
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)
        custody.storeEntropy(vaultB, entropy(2), PvCustodyMode.PLAIN)
        custody.lock()
        repeat(3) { custody.unlock(otherPassword) }

        custody.resetKeystore()

        assertFalse(custody.hasDevicePassword)
        assertFalse(custody.unlocked.value)
        assertEquals(emptyList<String>(), custody.storedVaultIds())
        assertEquals(PvCustodyState.Absent(vaultA), custody.stateFor(vaultA))
        assertEquals(
            "plain entries go too — 'the phrases on this endpoint' means all of them",
            PvCustodyState.Absent(vaultB),
            custody.stateFor(vaultB),
        )
        assertEquals(0, custody.failureCount)
        assertEquals(0L, custody.currentLockoutRemaining())
        assertTrue("the reset must leave no key material behind", prefs.all.isEmpty())

        // And the endpoint is usable again immediately — the reset produces a
        // state with a next action, not a dead device.
        assertEquals(PvUnlockResult.NoKeystore, custody.unlock(password))
        assertTrue(custody.setDevicePassword(otherPassword))
    }

    @Test
    fun `forgetting one vault leaves the others alone`() = runBlocking {
        val custody = custody()
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)
        custody.storeEntropy(vaultB, entropy(2), PvCustodyMode.WRAPPED)

        custody.forget(vaultA)

        assertEquals(PvCustodyState.Absent(vaultA), custody.stateFor(vaultA))
        assertArrayEquals(entropy(2), custody.entropyFor(vaultB))
        assertTrue("the endpoint password survives forgetting one phrase", custody.hasDevicePassword)
    }

    // ── The device password itself ──────────────────────────────────────────

    @Test
    fun `a device password is never silently replaced`() = runBlocking {
        val custody = custody()
        assertTrue(custody.setDevicePassword(password))
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)

        assertFalse(
            "replacing the password blind would orphan every wrapped entry",
            custody.setDevicePassword(otherPassword),
        )
        assertArrayEquals(entropy(1), custody.entropyFor(vaultA))
    }

    @Test
    fun `changing the password re-wraps every entry and refuses a wrong current one`() = runBlocking {
        val custody = custody()
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)
        custody.storeEntropy(vaultB, entropy(2), PvCustodyMode.PLAIN)

        assertFalse(custody.changeDevicePassword("wrong-current-pw", otherPassword))
        custody.lock()
        assertEquals(
            "a refused change must leave the old password working",
            PvUnlockResult.Success,
            custody.unlock(password),
        )

        assertTrue(custody.changeDevicePassword(password, otherPassword))
        custody.lock()
        assertTrue(custody.unlock(password) is PvUnlockResult.Wrong)
        assertEquals(PvUnlockResult.Success, custody.unlock(otherPassword))
        assertArrayEquals(entropy(1), custody.entropyFor(vaultA))
        assertArrayEquals("a plain entry is untouched by a re-wrap", entropy(2), custody.entropyFor(vaultB))
    }

    @Test
    fun `a too-short password is refused at both ends`() = runBlocking {
        val custody = custody()
        val short = "a".repeat(PV_DEVICE_PASSWORD_MIN_LENGTH - 1)
        assertFalse(custody.setDevicePassword(short))
        assertFalse(custody.hasDevicePassword)

        assertTrue(custody.setDevicePassword("a".repeat(PV_DEVICE_PASSWORD_MIN_LENGTH)))
        assertFalse(custody.changeDevicePassword("a".repeat(PV_DEVICE_PASSWORD_MIN_LENGTH), short))
    }

    @Test
    fun `each endpoint gets its own salt, at the app's own Argon2 profile`() = runBlocking {
        // Two endpoints that shared a salt would let one precomputed KDF table
        // attack both, so the salt is per install and freshly random.
        val prefsA = FakeSharedPreferences()
        val prefsB = FakeSharedPreferences()
        custody(prefsA).setDevicePassword(password)
        custody(prefsB).setDevicePassword(password)

        val saltA = PvEndpointKeystore(prefsA).endpointSalt
        val saltB = PvEndpointKeystore(prefsB).endpointSalt
        assertNotNull(saltA)
        assertNotNull(saltB)
        assertNotEquals("two installs must not share a salt", saltA, saltB)
        assertEquals(16, Base64.getDecoder().decode(saltA).size)

        // The profile is imported from the app's one definition, not retyped —
        // this pins that nobody "tuned" it on the way past (§12 names it by
        // value: m = 64 MiB, t = 3, p = 1).
        assertEquals(65536, VAULT_ARGON2_PARAMS.m)
        assertEquals(3, VAULT_ARGON2_PARAMS.t)
        assertEquals(1, VAULT_ARGON2_PARAMS.p)
    }

    @Test
    fun `two seals of the same entropy differ`() = runBlocking {
        // A fixed IV would make the two payloads equal and the wrap useless
        // against anyone who can read the keystore twice.
        val prefs = FakeSharedPreferences()
        val custody = custody(prefs)
        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)
        val first = PvEndpointKeystore(prefs).entry(vaultA)!!.payload
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)
        val second = PvEndpointKeystore(prefs).entry(vaultA)!!.payload
        assertNotEquals(first, second)
    }

    // ── The app-lock hook ───────────────────────────────────────────────────

    @Test
    fun `the existing app lock ends the session, and no second timer exists`() = runTest {
        // §12: "one timer, one mental model". The binding takes the app lock's
        // own flow, exactly as AppGraph.linkVaultLockToAppLock does for the v1
        // vault, so there is nothing here for a second timeout to live in.
        // Everything on ONE unconfined test dispatcher: the collector then runs
        // the instant the flow emits, so the assertions below are about the
        // binding rather than about who advanced which scheduler.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val appLocked = MutableStateFlow(false)
        val custody = PvDeviceCustody(
            keystore = PvEndpointKeystore(FakeSharedPreferences()),
            kdfDispatcher = dispatcher,
            randomBytes = { length -> ByteArray(length) { (it + 1).toByte() } },
            argon2 = fakeArgon2,
            clock = TestClock(),
        )
        // `backgroundScope + dispatcher`, not a free-standing CoroutineScope:
        // the job then belongs to a scope `runTest` tears down itself, so a
        // failing assertion below cannot strand a collector whose later
        // resumption would surface as an uncaught exception in whichever test
        // happens to run next.
        val job = custody.bindToAppLock(appLocked, backgroundScope + dispatcher)

        custody.setDevicePassword(password)
        custody.storeEntropy(vaultA, entropy(1), PvCustodyMode.WRAPPED)
        assertTrue(custody.unlocked.value)

        appLocked.value = true
        assertFalse("the app lock must end the vault session", custody.unlocked.value)
        assertNull(custody.entropyFor(vaultA))

        // Unlocking the app does NOT re-open the vault session: the phrase needs
        // its own password again.
        appLocked.value = false
        assertFalse(custody.unlocked.value)
        job.cancel()
    }
}
