package at.bettertrack.app.vault.bip39

import at.bettertrack.app.vault.utf8
import java.security.MessageDigest
import java.text.Normalizer

/**
 * The 12-word seed phrase: normalisation and the three local checks (word
 * count, wordlist membership, checksum).
 *
 * Ported literally from the platform's `apps/web/src/user/vault/v2/words.ts`
 * (`normalizeVaultPassphrase` / `checkVaultPassphrase`) while the Vaults v2 arc
 * was live; that arc was retired upstream (#1392) and this file left `vault/v2/`
 * with MB-17 (2026-10-30) because the per-portfolio rail kept calling it. Its
 * LIVE upstream counterpart is `apps/web/src/user/vault/bip39/mnemonic.ts`:
 * `normalizeMnemonic` is the same NFKD → trim → lower-case → single-space
 * pipeline, and `mnemonicToEntropy` performs the same three checks in the same
 * order (shape, then wordlist, then checksum) before handing the entropy to
 * `@scure/bip39`. The Kotlin names are kept so the pv call sites and their
 * fixtures (`PvBip39VectorTest`, `PvMnemonicEntropyTest`) did not have to move.
 *
 * 12 words = 128 bits of entropy + a 4-bit checksum (`BIP39_WORD_COUNT`,
 * `BIP39_ENTROPY_BITS`, `BIP39_CHECKSUM_BITS` in `mnemonic.ts`).
 *
 * The platform delegates checksum validation to `@scure/bip39`; this file
 * implements it directly (BIP-39 §"Generating the mnemonic"), because pulling a
 * mnemonic library onto Android for 40 lines of bit-packing would be a bigger
 * surface than the code it replaces.
 */
internal const val BIP39_WORD_COUNT: Int = 12

/** 128 bits of entropy for a 12-word phrase. */
private const val BIP39_ENTROPY_BITS: Int = 128

/** Each word carries 11 bits (2048 = 2^11). */
private const val BITS_PER_WORD: Int = 11

private val WORDLIST_INDEX: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    BIP39_ENGLISH.withIndex().associate { (index, word) -> word to index }
}

/**
 * Normalize user input into the canonical phrase the KDF sees: NFKD, lowercase,
 * single-space separated, no surrounding whitespace (`mnemonic.ts`
 * `normalizeMnemonic`; was `words.ts` `normalizeVaultPassphrase`).
 *
 * Manual entry, QR import and generation must all agree here, or the same words
 * would derive different keys. This is the only step in the file that changes
 * bytes the crypto sees, which is why the conformance vectors exercise it.
 *
 * JavaScript's `String.prototype.toLowerCase()` is locale-INDEPENDENT, so the
 * Kotlin side must pass `Locale.ROOT` (the platform default would map `I` to a
 * dotless `ı` under a Turkish locale and silently derive a different key).
 */
internal fun normalizeVaultPassphrase(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(java.util.Locale.ROOT)
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/** Why a phrase was rejected. Kept distinct because the UI says something different for each. */
internal sealed interface VaultPassphraseProblem {
    data class WordCount(val count: Int) : VaultPassphraseProblem
    data class UnknownWords(val words: List<String>, val positions: List<Int>) : VaultPassphraseProblem
    data object Checksum : VaultPassphraseProblem
}

internal sealed interface VaultPassphraseCheck {
    data class Valid(val passphrase: String, val words: List<String>) : VaultPassphraseCheck
    data class Invalid(val problem: VaultPassphraseProblem) : VaultPassphraseCheck
}

/**
 * Validate a typed or scanned phrase without throwing (`words.ts`
 * `checkVaultPassphrase`; `mnemonic.ts` raises `MnemonicWordCountError`,
 * `MnemonicWordError` and `MnemonicChecksumError` for the same three cases, in
 * the same order): wrong word count, a word outside the list (with its
 * positions, so a field can highlight it), or the right words in an order the
 * checksum rejects.
 */
internal fun checkVaultPassphrase(value: String): VaultPassphraseCheck {
    val passphrase = normalizeVaultPassphrase(value)
    val words = if (passphrase.isEmpty()) emptyList() else passphrase.split(" ")

    if (words.size != BIP39_WORD_COUNT) {
        return VaultPassphraseCheck.Invalid(VaultPassphraseProblem.WordCount(words.size))
    }

    val unknown = mutableListOf<String>()
    val positions = mutableListOf<Int>()
    words.forEachIndexed { index, word ->
        if (!WORDLIST_INDEX.containsKey(word)) {
            unknown += word
            positions += index
        }
    }
    if (unknown.isNotEmpty()) {
        return VaultPassphraseCheck.Invalid(VaultPassphraseProblem.UnknownWords(unknown, positions))
    }

    if (!validMnemonicChecksum(words)) {
        return VaultPassphraseCheck.Invalid(VaultPassphraseProblem.Checksum)
    }
    return VaultPassphraseCheck.Valid(passphrase, words)
}

/**
 * BIP-39 checksum: the 132 bits a 12-word phrase encodes are 128 bits of
 * entropy followed by the top 4 bits of `SHA-256(entropy)`.
 */
private fun validMnemonicChecksum(words: List<String>): Boolean {
    val totalBits = words.size * BITS_PER_WORD
    val checksumBits = totalBits - BIP39_ENTROPY_BITS
    if (checksumBits != BIP39_ENTROPY_BITS / 32) return false

    // Unpack the 11-bit indices into one big-endian bit string.
    val bits = BooleanArray(totalBits)
    words.forEachIndexed { wordIndex, word ->
        val value = WORDLIST_INDEX[word] ?: return false
        for (bit in 0 until BITS_PER_WORD) {
            bits[wordIndex * BITS_PER_WORD + bit] =
                (value shr (BITS_PER_WORD - 1 - bit)) and 1 == 1
        }
    }

    val entropy = ByteArray(BIP39_ENTROPY_BITS / 8)
    for (index in entropy.indices) {
        var byte = 0
        for (bit in 0 until 8) {
            byte = (byte shl 1) or if (bits[index * 8 + bit]) 1 else 0
        }
        entropy[index] = byte.toByte()
    }

    val digest = MessageDigest.getInstance("SHA-256").digest(entropy)
    for (bit in 0 until checksumBits) {
        val expected = (digest[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1
        if (bits[BIP39_ENTROPY_BITS + bit] != expected) return false
    }
    return true
}

/** The sha256 of the newline-joined wordlist — the canonical BIP-39 English digest. */
internal fun bip39EnglishDigestHex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(utf8(BIP39_ENGLISH.joinToString("\n") + "\n"))
        .joinToString("") { "%02x".format(it) }
