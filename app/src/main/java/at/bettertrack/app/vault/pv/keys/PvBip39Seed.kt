package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.utf8
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.text.Normalizer

/**
 * **Mnemonic → BIP-39 seed**, the first link of the §4 derivation chain and the
 * last one that is fully pinned by a public standard.
 *
 * ```
 * mnemonic (12 words)
 *   → BIP39 seed   PBKDF2-HMAC-SHA512(mnemonic, "mnemonic", 2048) — 64 bytes
 *   → K_wrap       ← E3, blocked on the HKDF salt (see PvVaultKeyDerivation.kt)
 * ```
 *
 * ## Why this half needs nobody's permission
 *
 * §4 adopts BIP-39 unchanged and says why: *"the standard BIP39 PBKDF2 step
 * keeps us vector-compatible with every BIP39 tool"*. That compatibility is the
 * point — it means this function is not a BetterTrack invention waiting on a
 * platform ruling but a specification with 24 published English test vectors, so
 * it can be written, pinned and finished today while the vault-specific
 * derivation above it waits for its answer.
 *
 * The parameters are BIP-39's, not ours, and none of them is a choice:
 * HMAC-SHA512, [PV_BIP39_PBKDF2_ITERATIONS] iterations, a
 * [PV_BIP39_SEED_BYTES]-byte output, and a salt of the literal string
 * `"mnemonic"` followed by the (empty, for this app) passphrase — all NFKD.
 *
 * ## No Argon2id here, deliberately
 *
 * §4: *"KDF stretching defends low-entropy human secrets; a 128-bit random
 * mnemonic needs none."* Argon2id stays exactly where a human secret exists —
 * the §12 device password, which `PvDeviceCustody` already runs at the server's
 * own cost family. Adding cost here would buy nothing and break every vector.
 *
 * ## Why Bouncy Castle and not `SecretKeyFactory`
 *
 * `PBKDF2WithHmacSHA512` via JCE takes a `char[]` and each provider decides for
 * itself how those chars become bytes — historically Android's PBKDF2 providers
 * and the JDK's have disagreed for non-ASCII passwords, which for a UTF-8 NFKD
 * mnemonic is exactly the case that matters. Bouncy Castle is already on the
 * main classpath (Argon2id, HKDF), is pure Java, and takes the password as
 * BYTES — so the encoding is this file's decision and it is the same on the
 * phone as it is in the unit test that pins it.
 *
 * ## Passphrase parameter
 *
 * BIP-39's optional 25th-word passphrase. This app always passes the empty
 * string — §4 says *"the standard, empty passphrase"* and no surface offers one
 * — but the parameter exists because the published vectors use `"TREZOR"`, and
 * a function that cannot be run against its own conformance data is a function
 * nobody has actually checked. The default is `""`, so no call site can acquire
 * one by accident.
 */

/** BIP-39's fixed PBKDF2 iteration count. Not a tunable. */
internal const val PV_BIP39_PBKDF2_ITERATIONS: Int = 2048

/** BIP-39's fixed seed length: 512 bits. */
internal const val PV_BIP39_SEED_BYTES: Int = 64

/** BIP-39's fixed salt prefix; the passphrase (empty here) is appended to it. */
internal const val PV_BIP39_SALT_PREFIX: String = "mnemonic"

/**
 * PBKDF2-HMAC-SHA512(NFKD(mnemonic), NFKD("mnemonic" ‖ passphrase), 2048) → 64 bytes.
 *
 * @param mnemonic the phrase. Production passes the canonical form
 *   `vault/v2`'s `normalizeVaultPassphrase` produces (NFKD, lower-case,
 *   single-spaced); the NFKD below is applied again anyway because BIP-39
 *   specifies it on this input, and a normalisation that is only correct when
 *   the caller remembered it is not a normalisation.
 * @param passphrase BIP-39's optional passphrase. Always `""` in this app.
 * @throws VaultCryptoError when [mnemonic] is blank — a seed derived from
 *   nothing is a vault key derived from nothing.
 */
internal fun pvBip39Seed(mnemonic: String, passphrase: String = ""): ByteArray {
    if (mnemonic.isBlank()) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "A BIP-39 seed needs a non-empty mnemonic.",
        )
    }
    val password = utf8(nfkd(mnemonic))
    val salt = utf8(nfkd(PV_BIP39_SALT_PREFIX + passphrase))
    return try {
        val generator = PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(password, salt, PV_BIP39_PBKDF2_ITERATIONS)
        val derived = generator.generateDerivedParameters(PV_BIP39_SEED_BYTES * 8) as KeyParameter
        derived.key.also {
            if (it.size != PV_BIP39_SEED_BYTES) {
                throw VaultCryptoError(
                    VaultCryptoErrorCode.KDF_FAILED,
                    "PBKDF2 returned the wrong number of bytes.",
                )
            }
        }
    } catch (cause: RuntimeException) {
        // Presence only: the mnemonic must never reach a message or a log.
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "PBKDF2-HMAC-SHA512 derivation failed.",
            cause,
        )
    }
}

/**
 * BIP-39 §"From mnemonic to seed" normalises BOTH the mnemonic and the salt to
 * NFKD before they are encoded as UTF-8. For the English wordlist every byte is
 * already ASCII, so this is a no-op there — which is exactly why it is easy to
 * omit and then discover on the first non-ASCII input, long after the vectors
 * went green.
 */
private fun nfkd(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
