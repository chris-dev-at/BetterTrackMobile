package at.bettertrack.app.vault.pv.custody

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.v2.BIP39_ENGLISH
import at.bettertrack.app.vault.v2.VaultPassphraseCheck
import at.bettertrack.app.vault.v2.checkVaultPassphrase
import java.security.MessageDigest

/**
 * The 128 bits behind the 12 words — the value §12's keystore actually stores.
 *
 * ## Why entropy and not the word string
 *
 * A keystore entry holds the mnemonic **entropy**, not the sentence. The words
 * are a rendering of those bits (BIP-39 §"Generating the mnemonic"): 128 bits of
 * entropy plus the top 4 bits of `SHA-256(entropy)` as a checksum, sliced into
 * 12 × 11-bit indices into the English wordlist. Storing the bits keeps the
 * stored payload fixed-length, keeps a typo-tolerant re-render possible, and
 * means the checksum is recomputed — never trusted — every time the phrase is
 * shown again.
 *
 * ## Why the wordlist is imported, never copied
 *
 * [BIP39_ENGLISH] is the transcription of `@scure/bip39`'s English list that
 * `vault/v2` already pins by sha256
 * (`2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`,
 * asserted in `VaultV2ConformanceTest`). A second copy in this package would be
 * a second thing to keep in step with a digest, and the first divergence would
 * present itself as "the words from my other device are wrong".
 *
 * Validation likewise reuses `v2`'s [checkVaultPassphrase], so a phrase typed
 * into the §13 QR/manual-entry path and a phrase typed here normalise (NFKD,
 * lower-case, single-spaced) and checksum identically.
 *
 * ## No value from this file is ever logged
 *
 * Entropy, words and anything derived from them are secrets of the highest
 * order in this design — §16 makes the phrase the ONLY way into a vault. The
 * functions here therefore return values and throw code-only errors; they never
 * format a payload into a message. `PvCustodySourceDisciplineTest` enforces it.
 */

/** 12 words = 128 bits of entropy (§4). */
internal const val PV_ENTROPY_BYTES: Int = 16

/** 12 words, the only length this design issues (§4). */
internal const val PV_MNEMONIC_WORDS: Int = 12

/** Each word carries 11 bits (2048 = 2^11). */
private const val BITS_PER_WORD: Int = 11

/** 128 / 32 = 4 checksum bits for a 12-word phrase. */
private const val CHECKSUM_BITS: Int = (PV_ENTROPY_BYTES * 8) / 32

private val WORD_INDEX: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    BIP39_ENGLISH.withIndex().associate { (index, word) -> word to index }
}

/**
 * Renders 16 bytes of entropy as its 12 BIP-39 English words.
 *
 * @throws VaultCryptoError when [entropy] is not exactly [PV_ENTROPY_BYTES]
 *   long — a shorter or longer buffer is a programming error, and quietly
 *   padding it would mint a phrase that opens nothing.
 */
internal fun pvEntropyToWords(entropy: ByteArray): List<String> {
    if (entropy.size != PV_ENTROPY_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "Mnemonic entropy must be $PV_ENTROPY_BYTES bytes.",
        )
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(entropy)
    val totalBits = PV_ENTROPY_BYTES * 8 + CHECKSUM_BITS
    val bits = BooleanArray(totalBits)
    for (index in 0 until PV_ENTROPY_BYTES * 8) {
        bits[index] = (entropy[index / 8].toInt() shr (7 - index % 8)) and 1 == 1
    }
    for (index in 0 until CHECKSUM_BITS) {
        bits[PV_ENTROPY_BYTES * 8 + index] = (digest[index / 8].toInt() shr (7 - index % 8)) and 1 == 1
    }
    return (0 until totalBits / BITS_PER_WORD).map { word ->
        var value = 0
        for (bit in 0 until BITS_PER_WORD) {
            value = (value shl 1) or if (bits[word * BITS_PER_WORD + bit]) 1 else 0
        }
        BIP39_ENGLISH[value]
    }
}

/** The same rendering as one normalised, single-space-separated string. */
internal fun pvEntropyToPhrase(entropy: ByteArray): String = pvEntropyToWords(entropy).joinToString(" ")

/**
 * Recovers the 16 entropy bytes from a typed or scanned phrase.
 *
 * Returns `null` — never throws, never explains — when the phrase is not 12
 * checksum-valid wordlist words. The caller (manual entry, §13 scan) already
 * has [checkVaultPassphrase] for the *reason*, which is what a field needs to
 * highlight the offending word; this function is the conversion only.
 */
internal fun pvPhraseToEntropy(phrase: String): ByteArray? {
    val words = when (val checked = checkVaultPassphrase(phrase)) {
        is VaultPassphraseCheck.Valid -> checked.words
        is VaultPassphraseCheck.Invalid -> return null
    }
    if (words.size != PV_MNEMONIC_WORDS) return null

    val bits = BooleanArray(words.size * BITS_PER_WORD)
    words.forEachIndexed { wordIndex, word ->
        val value = WORD_INDEX[word] ?: return null
        for (bit in 0 until BITS_PER_WORD) {
            bits[wordIndex * BITS_PER_WORD + bit] = (value shr (BITS_PER_WORD - 1 - bit)) and 1 == 1
        }
    }
    return ByteArray(PV_ENTROPY_BYTES) { index ->
        var byte = 0
        for (bit in 0 until 8) {
            byte = (byte shl 1) or if (bits[index * 8 + bit]) 1 else 0
        }
        byte.toByte()
    }
}
