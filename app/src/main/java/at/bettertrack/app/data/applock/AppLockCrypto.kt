package at.bettertrack.app.data.applock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac

/**
 * Keystore-backed PIN hashing (spec §5 — "hashed, Keystore-backed"). The PIN is
 * NEVER stored, in plaintext or otherwise. Instead we HMAC-SHA256 the salted PIN
 * under a **non-exportable AndroidKeyStore key**: the hardware key never leaves
 * the TEE/StrongBox, so even a full dump of the (already encrypted) preferences
 * cannot be brute-forced off-device — every guess needs the on-device key.
 *
 * A per-PIN random salt defends against precomputation and makes two identical
 * PINs hash differently. Verification is a constant-time compare (in the store).
 */
object AppLockCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "bt_applock_hmac_v1"
    private const val HMAC_ALGO = "HmacSHA256"
    private const val SALT_BYTES = 16

    private val secureRandom = SecureRandom()

    /** A fresh Base64 salt to pair with a newly-set PIN. */
    fun newSalt(): String {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    /**
     * HMAC-SHA256 over (salt ‖ pin) keyed by the Keystore HMAC key; Base64 out.
     * The salt is decoded and mixed in first so the digest is PIN- and
     * salt-specific while the secret stays the hardware key.
     */
    fun hashPin(pin: String, saltB64: String): String {
        val mac = Mac.getInstance(HMAC_ALGO).apply { init(getOrCreateKey()) }
        mac.update(Base64.decode(saltB64, Base64.NO_WRAP))
        mac.update(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(mac.doFinal(), Base64.NO_WRAP)
    }

    /**
     * The app-lock HMAC key, created once and reused. PURPOSE_SIGN with no
     * user-authentication requirement: verifying the PIN must work BEFORE any
     * unlock (that would be circular), and the security comes from the key being
     * hardware-bound and non-exportable, not from gating its use.
     */
    private fun getOrCreateKey(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }
}
