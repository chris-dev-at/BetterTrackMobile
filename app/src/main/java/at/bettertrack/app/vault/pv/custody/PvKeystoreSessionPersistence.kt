package at.bettertrack.app.vault.pv.custody

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.base64ToBytes
import at.bettertrack.app.vault.bytesToBase64
import at.bettertrack.app.vault.utf8
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The seal around K_dev at rest. [seal] returns `iv ‖ ciphertext‖tag`, or null
 * when the key is unavailable; [open] returns the plaintext, or null on ANY
 * failure (wrong AAD, tampered bytes, a key the OS dropped).
 */
interface PvSessionCipher {
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray?
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray?
}

/**
 * The Android implementation of [PvSessionPersistence]: the web's IndexedDB
 * record (`createIndexedDbEndpointSessionPersistence`) with the non-extractable
 * `CryptoKey` replaced by K_dev sealed under a NON-EXTRACTABLE Android Keystore
 * AES-256-GCM key ([AndroidKeystoreSessionCipher]) — the OS hands the app a
 * handle that can encrypt and decrypt, never the key bytes.
 *
 * One record per account in the pv endpoint prefs, key `pv_session.<accountId>`,
 * a JSON object `{version, accountId, sealed, createdAt, expiresAt}` where
 * `sealed` = base64(iv12 ‖ AES-256-GCM(K_dev)). The AAD binds the accountId (so
 * a record copied to another account's key opens nothing) AND the version and
 * both instants (so editing `expiresAt` in the file cannot extend a session).
 *
 * [read] mirrors `isPersistedSessionRecord` + the expiry rule and deletes the
 * record whenever it reports nothing for a reason the record itself carries
 * (expired, malformed, wrong account, unopenable). One rule beyond the web's: a
 * `createdAt` later than now means the wall clock moved backwards past the
 * creating unlock, and the record is refused (fail closed: one password entry)
 * rather than allowed to outlive seven real days.
 */
class PvKeystoreSessionPersistence(
    private val prefs: SharedPreferences,
    private val cipher: PvSessionCipher,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : PvSessionPersistence {

    override fun persist(accountId: String, deviceKey: ByteArray, expiresAt: Long) {
        try {
            val createdAt = wallClock()
            val sealed = cipher.seal(deviceKey, aad(accountId, createdAt, expiresAt)) ?: return
            val record = JsonObject(
                linkedMapOf(
                    "version" to JsonPrimitive(RECORD_VERSION),
                    "accountId" to JsonPrimitive(accountId),
                    "sealed" to JsonPrimitive(bytesToBase64(sealed)),
                    "createdAt" to JsonPrimitive(createdAt),
                    "expiresAt" to JsonPrimitive(expiresAt),
                )
            )
            prefs.edit().putString(key(accountId), record.toString()).apply()
        } catch (_: RuntimeException) {
            // Persistence is a convenience; the session stands in memory.
        }
    }

    override fun read(accountId: String, now: Long): PvPersistedSession? {
        val raw = try {
            prefs.getString(key(accountId), null)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val record = parse(raw, accountId)
        if (record == null || record.expiresAt <= now || record.createdAt > now) {
            clear(accountId)
            return null
        }
        val opened = cipher.open(record.sealed, aad(accountId, record.createdAt, record.expiresAt))
        if (opened == null) {
            clear(accountId)
            return null
        }
        return PvPersistedSession(opened, record.expiresAt)
    }

    override fun clear(accountId: String) {
        try {
            prefs.edit().remove(key(accountId)).apply()
        } catch (_: RuntimeException) {
            // The marker keeps an undeletable record inert.
        }
    }

    private class Record(val sealed: ByteArray, val createdAt: Long, val expiresAt: Long)

    private fun parse(raw: String, accountId: String): Record? {
        val obj = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val version = (obj["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val owner = (obj["accountId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val sealedText = (obj["sealed"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val createdAt = (obj["createdAt"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val expiresAt = (obj["expiresAt"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (version != RECORD_VERSION.toLong() || owner != accountId) return null
        if (sealedText == null || createdAt == null || expiresAt == null) return null
        val sealed = try {
            base64ToBytes(sealedText, VaultCryptoErrorCode.ENVELOPE_INVALID)
        } catch (_: VaultCryptoError) {
            return null
        }
        return Record(sealed, createdAt, expiresAt)
    }

    companion object {
        internal const val RECORD_VERSION: Int = 1
        internal const val KEY_PREFIX = "pv_session."

        internal fun key(accountId: String) = "$KEY_PREFIX$accountId"

        /** `bt-pv-session-v1 ‖ 0 ‖ accountId ‖ 0 ‖ createdAt ‖ 0 ‖ expiresAt` (decimal), UTF-8. */
        internal fun aad(accountId: String, createdAt: Long, expiresAt: Long): ByteArray =
            utf8("bt-pv-session-v$RECORD_VERSION\u0000$accountId\u0000$createdAt\u0000$expiresAt")
    }
}

/**
 * K_dev's at-rest seal: an AES-256-GCM key generated INSIDE the Android
 * Keystore under [alias]. Keystore secret keys are non-extractable by
 * construction — no API returns their bytes — and the IV is the Keystore's own
 * (`setRandomizedEncryptionRequired`). No user-authentication binding: the
 * app's PIN lock is the gate the amendment names, and a biometric-bound key
 * would add a second unlock model §12 forbids.
 *
 * Not exercised on the JVM (no AndroidKeyStore provider there); the record logic
 * around it is, through [PvSessionCipher].
 */
class AndroidKeystoreSessionCipher(private val alias: String = DEFAULT_ALIAS) : PvSessionCipher {

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plaintext)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) null else iv + body
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: ProviderException) {
        null
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: ProviderException) {
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "bt_pv_session_v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
    }
}
