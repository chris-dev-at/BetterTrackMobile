package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.pv.custody.PV_ENTROPY_BYTES
import at.bettertrack.app.vault.pv.custody.PV_MNEMONIC_WORDS
import at.bettertrack.app.vault.pv.custody.pvEntropyToWords
import java.security.SecureRandom

/**
 * **Minting a vault's 12 words** — the first act of the §21 creation ceremony
 * and the only place in the app that invents key material for a paranoid vault.
 *
 * ## One entropy↔words path, and it is not this file's
 *
 * The bit-packing that turns 128 bits into 12 BIP-39 words already exists, in
 * `vault/pv/custody/PvMnemonicEntropy.kt`, because §12's keystore stores the
 * ENTROPY and has to re-render the words on demand. Writing a second one here
 * would give the app two implementations of the same standard whose first
 * divergence presents itself to the user as *"the words from my other device
 * are wrong"* — with no way back, because §16 makes the phrase the only way in.
 *
 * So this file calls [pvEntropyToWords] rather than reimplementing it, and the
 * custody file stays where it is: the conversion belongs next to the store that
 * needs it on every read, and issuance is the occasional caller. (The wordlist
 * itself is `vault/v2`'s [at.bettertrack.app.vault.v2.BIP39_ENGLISH], pinned by
 * sha256 in `VaultV2ConformanceTest` — one list for mint, manual entry and the
 * §13 QR scan.)
 *
 * ## Where the randomness comes from
 *
 * [SecureRandom] with no seeding, no `setSeed`, no "mix in the clock". On
 * Android that is the platform CSPRNG; on the JVM the unit tests run on it is
 * the JDK's. Anything cleverer — hashing a timestamp in, XOR-ing two sources —
 * can only *reduce* the guarantee, and 128 bits of CSPRNG output is exactly what
 * §4 asks for ("generated client-side from CSPRNG entropy at vault creation").
 *
 * The generator is a parameter so a test can pin a byte pattern and assert the
 * rendering, never so production can pass something weaker.
 *
 * ## Nothing here is logged, formatted or persisted
 *
 * A [PvIssuedMnemonic] is a live secret: it opens the vault it was minted for,
 * and it will exist on paper and nowhere else until the user chooses custody.
 * The type therefore has no `toString`, no `equals`, no `hashCode` worth
 * printing — see the class KDoc — and `PvKeysSourceDisciplineTest` keeps this
 * package out of logcat the same way `PvCustodySourceDisciplineTest` does for
 * custody.
 */

/**
 * A freshly minted phrase: the 128 bits and their rendering.
 *
 * Deliberately a plain `class` and not a `data class`. A data class synthesises
 * `toString()`, and the single most likely way for a seed phrase to reach a log
 * is an innocent `Log.d(TAG, "issued $mnemonic")` or a crash report that
 * interpolates a state object holding one. [toString] here is overridden to a
 * constant so that even a careless interpolation prints nothing.
 *
 * [equals]/[hashCode] are likewise left as identity: comparing two phrases is
 * not an operation this app has any use for, and a synthesised `equals` over a
 * `ByteArray` compares references anyway (the classic data-class-with-array
 * trap), so the synthesised version would be misleading as well as unnecessary.
 */
class PvIssuedMnemonic internal constructor(
    /** The 16 bytes §12's keystore stores. Callers must not retain a second copy. */
    val entropy: ByteArray,
    /** The same bits as the 12 words the user writes down, in order. */
    val words: List<String>,
) {

    /** The words as the canonical single-space-separated sentence (§13's `m`). */
    val phrase: String get() = words.joinToString(" ")

    /** A constant. See the class KDoc: a phrase must not be printable by accident. */
    override fun toString(): String = "PvIssuedMnemonic(redacted)"
}

/**
 * Mints a new 12-word phrase from [random].
 *
 * @param random the CSPRNG to draw [PV_ENTROPY_BYTES] bytes from. Defaults to a
 *   fresh [SecureRandom]; a caller passing anything else is a test.
 */
internal fun pvIssueMnemonic(random: SecureRandom = SecureRandom()): PvIssuedMnemonic {
    val entropy = ByteArray(PV_ENTROPY_BYTES)
    random.nextBytes(entropy)
    return pvMnemonicFromEntropy(entropy)
}

/**
 * Renders an existing 128 bits as its phrase — the shape the §13 scan and a
 * future re-display path need, and the one the Trezor vectors exercise.
 *
 * @throws VaultCryptoError when [entropy] is not exactly [PV_ENTROPY_BYTES]
 *   long. Padding a short buffer would mint a phrase that opens nothing, and
 *   with no escrow (§16) "opens nothing" is permanent.
 */
internal fun pvMnemonicFromEntropy(entropy: ByteArray): PvIssuedMnemonic {
    if (entropy.size != PV_ENTROPY_BYTES) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "Mnemonic entropy must be $PV_ENTROPY_BYTES bytes.",
        )
    }
    val words = pvEntropyToWords(entropy)
    if (words.size != PV_MNEMONIC_WORDS) {
        // Unreachable while the shared renderer is correct — which is precisely
        // why it is asserted here rather than assumed: this is the last point
        // before a phrase is shown to a human as the only copy that will exist.
        throw VaultCryptoError(
            VaultCryptoErrorCode.KDF_FAILED,
            "A minted phrase must be $PV_MNEMONIC_WORDS words.",
        )
    }
    return PvIssuedMnemonic(entropy = entropy.copyOf(), words = words)
}
