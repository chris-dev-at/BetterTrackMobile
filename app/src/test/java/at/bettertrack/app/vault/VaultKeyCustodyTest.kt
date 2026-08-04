package at.bettertrack.app.vault

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Key custody (S3/S4 plan §2.7): generate, wrap, persist, unlock, lock, recover.
 *
 * ## Why Argon2id is faked here and nowhere else
 *
 * The KDF's *conformance* — that this client derives byte-identically to the web
 * PWA at m=64 MiB, t=3, p=1 — is proven against the platform's published
 * `kekBase64` vector in `VaultConformanceTest`, with the real Bouncy Castle
 * generator. Re-paying 64 MiB of hashing in every custody test would add seconds
 * to CI to re-prove something already pinned. What *this* file tests is the
 * custody state machine around it, so the derivation is injected as a cheap
 * deterministic function and the state machine is exercised properly.
 *
 * The parameters themselves are asserted below — they are not negotiable, and a
 * silently "tuned" profile would produce vaults the web cannot open.
 */
class VaultKeyCustodyTest {

    /** A cheap stand-in: deterministic per (password, salt), 32 bytes. */
    private val fakeArgon2 = Argon2Derive { password, salt, _, _, _, hashLength ->
        val seed = password.fold(7) { acc, byte -> acc * 31 + byte } +
            salt.fold(11) { acc, byte -> acc * 17 + byte }
        ByteArray(hashLength) { index -> (seed + index * 13).toByte() }
    }

    private var idCounter = 0

    private fun custody(prefs: FakeSharedPreferences = FakeSharedPreferences()) = VaultKeyCustody(
        prefs = prefs,
        kdfDispatcher = UnconfinedTestDispatcher(),
        randomBytes = { length -> ByteArray(length) { (it + 1).toByte() } },
        argon2 = fakeArgon2,
        newId = { "018f0000-0000-7000-8000-0000000003%02d".format(idCounter++) },
    )

    // ── Creation ────────────────────────────────────────────────────────────

    @Test
    fun createsAVaultKeyAndLeavesTheVaultUnlocked() = runBlocking {
        val custody = custody()
        assertTrue("a fresh device is locked", custody.locked.value)
        assertFalse(custody.hasVault)

        val material = custody.create("correct horse battery staple")

        assertFalse("the wizard's next step needs the key", custody.locked.value)
        assertTrue(custody.hasVault)
        assertEquals(VAULT_KEY_BYTES, material.vaultKey.size)
        assertEquals(material.keyId, custody.keyId)
        assertNotNull(custody.unlockedKey())
    }

    /**
     * Every write must carry a wrapper for its own key id, or it produces an
     * envelope nobody — including its author — can ever open again
     * (`assertEncryptableWrappedKeys`).
     */
    @Test
    fun persistsAWrapperForTheActiveKeyWithTheContractedProfile() = runBlocking {
        val custody = custody()
        custody.create("a passphrase")

        val wrapped = custody.wrappedKey() ?: throw AssertionError("expected a wrapped key")
        assertEquals(custody.keyId, wrapped.keyId)
        assertEquals("argon2id", wrapped.kdf.alg)
        assertEquals("m is not negotiable", 65536, wrapped.kdf.m)
        assertEquals("t is not negotiable", 3, wrapped.kdf.t)
        assertEquals("p is not negotiable", 1, wrapped.kdf.p)
        assertEquals(
            "a 16-byte salt, base64",
            VAULT_SALT_BYTES,
            java.util.Base64.getDecoder().decode(wrapped.kdf.salt).size,
        )
        assertTrue(wrapped.wrappedVk.isNotEmpty())
    }

    /** The whole point of "Lock vault": the key stops existing in memory. */
    @Test
    fun lockDropsTheKeyButKeepsTheVault() = runBlocking {
        val custody = custody()
        custody.create("a passphrase")

        custody.lock()

        assertTrue(custody.locked.value)
        assertNull("no key while locked", custody.unlockedKey())
        assertTrue("the vault itself is untouched", custody.hasVault)
        assertNotNull("and its wrapper is still on disk", custody.wrappedKey())
    }

    // ── Unlock ──────────────────────────────────────────────────────────────

    @Test
    fun unlocksWithTheRightPassphraseAndRecoversTheSameKey() = runBlocking {
        val prefs = FakeSharedPreferences()
        val first = custody(prefs)
        val created = first.create("open sesame")
        first.lock()

        // A fresh instance over the same storage: the cold-start path.
        val second = custody(prefs)
        assertTrue("cold start is locked", second.locked.value)
        assertTrue(second.unlock("open sesame"))
        assertFalse(second.locked.value)
        assertTrue(
            "the recovered key is the one the vault was created with",
            second.unlockedKey()!!.contentEquals(created.vaultKey),
        )
    }

    /**
     * A wrong passphrase is an ordinary user event, not an exception — and it
     * must leave the vault locked rather than half-open.
     */
    @Test
    fun refusesAWrongPassphraseWithoutThrowing() = runBlocking {
        val prefs = FakeSharedPreferences()
        custody(prefs).create("the real one").also { }
        val custody = custody(prefs)

        assertFalse(custody.unlock("not the real one"))
        assertTrue("still locked", custody.locked.value)
        assertNull(custody.unlockedKey())

        assertTrue("and the right one still works afterwards", custody.unlock("the real one"))
    }

    @Test
    fun cannotUnlockADeviceThatHasNoVault() = runBlocking {
        assertFalse(custody().unlock("anything"))
    }

    // ── Recovery kit ────────────────────────────────────────────────────────

    /**
     * The kit is the designed way back in when the passphrase is gone. Its bytes
     * are a published vector (pinned in `VaultConformanceTest`), so a kit written
     * on the phone imports into the web PWA and vice versa.
     */
    @Test
    fun producesARecoveryKitThatOpensTheVaultWithoutThePassphrase() = runBlocking {
        val prefs = FakeSharedPreferences()
        val custody = custody(prefs)
        val created = custody.create("forgettable")

        val kit = custody.recoveryKit() ?: throw AssertionError("expected a recovery kit")
        assertEquals(RECOVERY_KIT_FILENAME, kit.filename)

        val fresh = custody(prefs)
        assertTrue("cold start", fresh.locked.value)
        assertTrue("the kit alone unlocks it", fresh.unlockWithRecoveryKit(kit.bytes))
        assertTrue(fresh.unlockedKey()!!.contentEquals(created.vaultKey))
    }

    @Test
    fun refusesARecoveryKitForADifferentVault() = runBlocking {
        val otherKit = custody().let { other ->
            other.create("other vault")
            other.recoveryKit()!!
        }

        val custody = custody()
        custody.create("this vault")
        custody.lock()

        assertFalse("a kit for another key id must not open this vault", custody.unlockWithRecoveryKit(otherKit.bytes))
        assertTrue(custody.locked.value)
    }

    @Test
    fun refusesAMalformedRecoveryKit() = runBlocking {
        val custody = custody()
        custody.create("a passphrase")
        custody.lock()

        assertFalse(custody.unlockWithRecoveryKit("BetterTrack recovery kit\nbut not really".toByteArray()))
        assertTrue(custody.locked.value)
    }

    @Test
    fun noKitIsProducedWhileLocked() = runBlocking {
        val custody = custody()
        custody.create("a passphrase")
        custody.lock()
        assertNull("a locked vault cannot export its own key", custody.recoveryKit())
    }

    // ── Forget ──────────────────────────────────────────────────────────────

    @Test
    fun forgetRemovesEveryTraceOfTheKey() = runBlocking {
        val prefs = FakeSharedPreferences()
        val custody = custody(prefs)
        custody.create("a passphrase")

        custody.forget()

        assertTrue(custody.locked.value)
        assertFalse(custody.hasVault)
        assertNull(custody.wrappedKey())
        assertNull(custody.keyId)
        assertFalse("nothing is left behind for a later instance", custody(prefs).hasVault)
    }

    /**
     * `unlockedKey()` hands out a COPY: callers zero what they are given, and a
     * shared array would leave custody's own copy zeroed behind their back.
     */
    @Test
    fun handsOutACopyOfTheKeyNotTheKeyItself() = runBlocking {
        val custody = custody()
        custody.create("a passphrase")

        val borrowed = custody.unlockedKey()!!
        zeroBytes(borrowed)

        val again = custody.unlockedKey()!!
        assertFalse("custody still holds real key material", again.all { it == 0.toByte() })
    }
}
