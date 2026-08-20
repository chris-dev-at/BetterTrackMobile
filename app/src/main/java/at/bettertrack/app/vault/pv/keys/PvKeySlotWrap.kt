package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.VAULT_IV_BYTES
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.aesGcmDecrypt
import at.bettertrack.app.vault.aesGcmEncrypt
import at.bettertrack.app.vault.asVaultCryptoError
import at.bettertrack.app.vault.concatBytes
import at.bettertrack.app.vault.pv.envelope.PvKeySlot
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.pvBase64UrlDecode
import at.bettertrack.app.vault.pv.envelope.pvBase64UrlEncode
import at.bettertrack.app.vault.pv.envelope.pvIsUuid
import at.bettertrack.app.vault.secureRandomBytes
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.zeroBytes

/**
 * **`keySlots[]` — the wrap and unwrap of the random content key `K_c` (§4).**
 *
 * ```
 * aad        = "bettertrack-vault-key-slot-v1:" + vaultId + ":" + keyId
 * wrappedKc  = base64url( iv(12) ‖ ciphertext ‖ tag(16) )   — AES-256-GCM under K_wrap
 * ```
 *
 * The slot indirection is the reason the design can rotate a phrase at all: the
 * content key is RANDOM and the words only ever wrap it, so §4 rotation and any
 * far-future sharing re-issue a slot rather than re-encrypting under new words.
 * A slot's `slot` discriminator is [PvVaultContract.KEY_SLOT_SEED_V1] and there
 * is exactly one kind — a second one arrives behind a format-version bump, never
 * by loosening that literal.
 *
 * ## The AAD, and why it is not decoration
 *
 * The wrap is bound to `vaultId` AND `keyId`. Without the vault id, a slot
 * lifted out of vault A's header and pasted into vault B's would still unwrap
 * for anyone holding A's phrase — the §8 anti-swap property the doc envelope
 * gets from its own header-as-AAD would have a hole one level below it. Without
 * the key id, two slots of the SAME vault would be interchangeable, so a
 * rotation could be rolled back by swapping the new slot's bytes for the old
 * slot's under the new slot's id. Both halves ride in the AAD, so both attacks
 * fail as authentication failures before a key byte exists.
 *
 * ## The layout
 *
 * `IV ‖ CT ‖ TAG`, base64url — the platform's answer of 2026-08-20. The IV
 * travels INSIDE `wrappedKc` rather than as a sibling field (v1's `wrappedVk`
 * did the same, `vault/VaultCrypto.kt`'s `wrapVaultKey`); the tag is GCM's
 * trailing 16 bytes, which is where `javax.crypto` puts it and where WebCrypto
 * reads it from, so the two clients concatenate identically.
 *
 * ## Constant hoisting
 *
 * [PV_KEY_SLOT_AAD_PREFIX] is declared here rather than in [PvVaultContract]
 * because the extracted E0 contract does not carry it yet; the hoist into the
 * shared contract object happens with epic E7, together with the QR grammar's
 * own literals. Recorded so the next author moves it deliberately instead of
 * discovering a duplicate.
 */

/** `bettertrack-vault-key-slot-v1:${vaultId}:${keyId}` — the slot wrap's AAD domain. */
internal const val PV_KEY_SLOT_AAD_PREFIX: String = "bettertrack-vault-key-slot-v1:"

/** The GCM tag AES-GCM appends to every ciphertext. */
private const val PV_GCM_TAG_BYTES: Int = 16

/** The AAD a slot of [keyId] in [vaultId] authenticates under. */
internal fun pvKeySlotAad(vaultId: String, keyId: String): ByteArray =
    utf8("$PV_KEY_SLOT_AAD_PREFIX$vaultId:$keyId")

/**
 * Wrap `K_c` under `K_wrap` into one [PvKeySlot].
 *
 * [iv] exists for the deterministic-vector callers only (the discipline the v2
 * rail established). Production omits it and draws a fresh random nonce:
 * reusing one IV across two different plaintexts under one key breaks GCM
 * outright, so the parameter is never a convenience.
 *
 * @throws VaultCryptoError on any wrong-shaped input — the ids are uuids because
 *   the E0 slot schema types them so, and a slot whose `keyId` fails that schema
 *   is a slot the platform will refuse after it has already been written.
 */
internal fun pvWrapContentKey(
    contentKey: ByteArray,
    wrapKey: ByteArray,
    vaultId: String,
    keyId: String,
    randomBytes: RandomBytes = secureRandomBytes,
    iv: ByteArray? = null,
): PvKeySlot {
    pvRequireContentKey(contentKey)
    pvRequireWrapKey(wrapKey)
    pvRequireSlotIds(vaultId, keyId)
    val nonce = iv ?: randomBytes(VAULT_IV_BYTES)
    if (nonce.size != VAULT_IV_BYTES) {
        throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, "A key slot IV must be 96 bits.")
    }
    var sealed: ByteArray? = null
    return try {
        sealed = aesGcmEncrypt(wrapKey, nonce, contentKey, pvKeySlotAad(vaultId, keyId))
        PvKeySlot(
            keyId = keyId,
            slot = PvVaultContract.KEY_SLOT_SEED_V1,
            wrappedKc = pvBase64UrlEncode(concatBytes(nonce, sealed)),
        )
    } catch (cause: Throwable) {
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not wrap the vault content key.",
            cause,
        )
    } finally {
        sealed?.let { zeroBytes(it) }
        if (iv == null) zeroBytes(nonce)
    }
}

/**
 * Unwrap one [PvKeySlot] back to `K_c`.
 *
 * Fail-closed at every step, and every KEY-dependent failure is one opaque
 * outcome: a wrong phrase, a slot lifted from another vault, a rolled-back
 * rotation and a flipped ciphertext bit are all `authentication-failed`,
 * because distinguishing them for the caller distinguishes them for an attacker
 * too. Failures that depend on no secret at all keep their own honest code —
 * non-canonical base64url is `envelope-invalid`, an unknown slot kind is
 * `update-required` — exactly as the v1 rail's `unwrapVaultKey` does it.
 *
 * @param slot the header slot to open.
 * @param wrapKey `K_wrap`, from [pvVaultWrapKey].
 * @param vaultId the vault the slot is being read INSIDE — passed separately
 *   rather than read from the slot, which carries no vault id: the caller's
 *   belief about which vault this is has to be the thing that gets
 *   authenticated, or the anti-swap check would be the attacker's own claim.
 */
internal fun pvUnwrapContentKey(slot: PvKeySlot, wrapKey: ByteArray, vaultId: String): ByteArray {
    pvRequireWrapKey(wrapKey)
    pvRequireSlotIds(vaultId, slot.keyId)
    if (slot.slot != PvVaultContract.KEY_SLOT_SEED_V1) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.UPDATE_REQUIRED,
            "This vault uses a key slot kind this app version does not know.",
        )
    }
    var payload: ByteArray? = null
    try {
        payload = pvBase64UrlDecode(slot.wrappedKc, "Vault key slot 'wrappedKc'")
        if (payload.size != VAULT_IV_BYTES + PV_WRAP_KEY_BYTES + PV_GCM_TAG_BYTES) {
            // Exact, not a minimum: K_c is a fixed-size AES-256 key, so the one
            // correct length is known and anything else is malformed rather than
            // "possibly a longer key".
            throw VaultCryptoError(
                VaultCryptoErrorCode.AUTHENTICATION_FAILED,
                "Vault key slot is structurally invalid.",
            )
        }
        val contentKey = aesGcmDecrypt(
            wrapKey,
            payload.copyOfRange(0, VAULT_IV_BYTES),
            payload.copyOfRange(VAULT_IV_BYTES, payload.size),
            pvKeySlotAad(vaultId, slot.keyId),
        )
        pvRequireContentKey(contentKey)
        return contentKey
    } catch (cause: Throwable) {
        // `asVaultCryptoError` returns an existing VaultCryptoError unchanged, so
        // the typed answers above keep their code and only an untyped provider
        // failure is relabelled — the same rule `decryptPvDoc` states.
        throw asVaultCryptoError(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            "Could not authenticate the vault key slot.",
            cause,
        )
    } finally {
        payload?.let { zeroBytes(it) }
    }
}

/** `K_wrap` is AES-256, exactly like `K_c`; named separately so failures say which. */
private fun pvRequireWrapKey(wrapKey: ByteArray) {
    if (wrapKey.size != PV_WRAP_KEY_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "The vault wrap key must be $PV_WRAP_KEY_BYTES bytes.",
        )
    }
}

/** Both ids ride in the AAD, and the E0 slot schema types both as uuids. */
private fun pvRequireSlotIds(vaultId: String, keyId: String) {
    if (!pvIsUuid(vaultId)) {
        throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, "A key slot needs the vault's uuid.")
    }
    if (!pvIsUuid(keyId)) {
        throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, "A key slot's keyId must be a uuid.")
    }
}
