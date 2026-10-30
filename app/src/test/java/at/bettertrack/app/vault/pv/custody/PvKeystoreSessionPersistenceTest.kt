package at.bettertrack.app.vault.pv.custody

import android.content.SharedPreferences
import at.bettertrack.app.vault.FakeSharedPreferences
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.aesGcmDecrypt
import at.bettertrack.app.vault.aesGcmEncrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The Android session record (`PvKeystoreSessionPersistence`) around a JVM
 * stand-in for the Keystore seal, plus the prefs marker. The real
 * [AndroidKeystoreSessionCipher] needs the AndroidKeyStore provider, which the
 * JVM lacks, so its key shape is pinned by a source scan instead.
 */
class PvKeystoreSessionPersistenceTest {

    /** AES-256-GCM with a fixed in-memory key — a stand-in, never the production seal. */
    private class TestCipher : PvSessionCipher {
        private val key = ByteArray(32) { (it * 5 + 1).toByte() }
        private var counter = 0
        var canSeal = true

        override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray? {
            if (!canSeal) return null
            val iv = ByteArray(12) { (counter++ + it).toByte() }
            return iv + aesGcmEncrypt(key, iv, plaintext, aad)
        }

        override fun open(sealed: ByteArray, aad: ByteArray): ByteArray? = try {
            aesGcmDecrypt(key, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), aad)
        } catch (_: VaultCryptoError) {
            null
        }
    }

    private val accountA = "018f0000-0000-7000-8000-00000000a001"
    private val accountB = "018f0000-0000-7000-8000-00000000b002"
    private val kDev = ByteArray(32) { (it + 40).toByte() }
    private var now = 1_760_000_000_000L
    private val prefs = FakeSharedPreferences()
    private val cipher = TestCipher()
    private val store = PvKeystoreSessionPersistence(prefs, cipher) { now }
    private val expiresAt get() = now + PV_SESSION_PERSISTENCE_TTL_MS

    private fun raw(account: String = accountA) = prefs.getString(PvKeystoreSessionPersistence.key(account), null)

    @Test
    fun `round trips K_dev sealed, never in the clear`() {
        store.persist(accountA, kDev, expiresAt)
        val record = raw()!!
        assertFalse("K_dev must not be stored in the clear", record.contains(Base64.getEncoder().encodeToString(kDev)))
        assertTrue(record.contains("\"version\":1") && record.contains("\"accountId\":\"$accountA\""))
        val read = store.read(accountA, now)!!
        assertArrayEquals(kDev, read.deviceKey)
        assertEquals(expiresAt, read.expiresAt)
    }

    @Test
    fun `an expired record reports nothing and is deleted`() {
        val end = expiresAt
        store.persist(accountA, kDev, end)
        assertNotNull(store.read(accountA, end - 1))
        assertNull(store.read(accountA, end))
        assertNull(raw())
    }

    @Test
    fun `editing expiresAt in the file cannot extend the session - the AAD binds it`() {
        store.persist(accountA, kDev, expiresAt)
        val tampered = raw()!!.replace("\"expiresAt\":$expiresAt", "\"expiresAt\":${expiresAt + 1}")
        prefs.edit().putString(PvKeystoreSessionPersistence.key(accountA), tampered).commit()
        assertNull(store.read(accountA, now))
        assertNull(raw())
    }

    @Test
    fun `a record copied under another account opens nothing`() {
        store.persist(accountA, kDev, expiresAt)
        prefs.edit().putString(PvKeystoreSessionPersistence.key(accountB), raw()!!).commit()
        assertNull(store.read(accountB, now))
        assertNull(raw(accountB))
        assertNotNull("A's own record is untouched", store.read(accountA, now))
    }

    @Test
    fun `a wall clock moved back past the creating unlock refuses the record`() {
        store.persist(accountA, kDev, expiresAt)
        assertNull(store.read(accountA, now - 1))
        assertNull(raw())
    }

    @Test
    fun `malformed records report nothing and are deleted`() {
        for (bad in listOf("not json", "[]", """{"version":2}""", """{"version":"1","accountId":"$accountA"}""")) {
            prefs.edit().putString(PvKeystoreSessionPersistence.key(accountA), bad).commit()
            assertNull(bad, store.read(accountA, now))
            assertNull(bad, raw())
        }
    }

    @Test
    fun `a seal that is unavailable persists nothing, and clear is idempotent`() {
        cipher.canSeal = false
        store.persist(accountA, kDev, expiresAt)
        assertNull(raw())
        store.clear(accountA)
        store.clear(accountA)
        assertNull(store.read(accountA, now))
    }

    @Test
    fun `the prefs marker is per account and an unreadable store says so`() {
        val marker = PrefsPvDeviceLockMarker(prefs)
        assertEquals(PvDeviceLockState.CLEAR, marker.read(accountA))
        marker.remember(accountA)
        assertEquals(PvDeviceLockState.LOCKED, marker.read(accountA))
        assertEquals(PvDeviceLockState.CLEAR, marker.read(accountB))
        marker.forget(accountA)
        assertEquals(PvDeviceLockState.CLEAR, marker.read(accountA))

        val broken = object : SharedPreferences by FakeSharedPreferences() {
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw SecurityException("wedged")
        }
        assertEquals(PvDeviceLockState.UNREADABLE, PrefsPvDeviceLockMarker(broken).read(accountA))
    }

    /** The production seal cannot run on the JVM; its key shape is pinned on the source. */
    @Test
    fun `the Keystore seal is a non-extractable AES-256-GCM AndroidKeyStore key`() {
        val rel = "java/at/bettertrack/app/vault/pv/custody/PvKeystoreSessionPersistence.kt"
        val file = listOf(File("src/main/$rel"), File("app/src/main/$rel")).first { it.isFile }
        val code = file.readText()
        listOf(
            "\"AndroidKeyStore\"",
            "KeyGenParameterSpec.Builder",
            "KeyProperties.BLOCK_MODE_GCM",
            "KeyProperties.ENCRYPTION_PADDING_NONE",
            "KEY_BITS = 256",
            "setRandomizedEncryptionRequired(true)",
        ).forEach { assertTrue("missing $it", code.contains(it)) }
        // Nothing in the custody rail may read key bytes back out.
        val custodyDir = file.parentFile!!
        val offenders = custodyDir.listFiles()!!.filter { it.readText().contains(".encoded") || it.readText().contains("getEncoded(") }
        assertTrue("key bytes read back in $offenders", offenders.isEmpty())
    }
}
