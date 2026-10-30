package at.bettertrack.app.vault.bip39

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wordlist pin, carried over verbatim from `VaultV2ConformanceTest`
 * (`bip39WordlistIsCanonical`) when the frozen v2 replay was dropped with
 * MB-17 (2026-10-30). The retired suite was the only place the digest was
 * asserted; the per-portfolio rail mints, checks and derives from this one list,
 * so the pin outlives the arc that first needed it.
 */
class Bip39EnglishTest {

    /** The wordlist is the canonical BIP-39 English list, byte for byte. */
    @Test
    fun bip39WordlistIsCanonical() {
        assertEquals(2048, BIP39_ENGLISH.size)
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            bip39EnglishDigestHex(),
        )
        assertEquals("abandon", BIP39_ENGLISH.first())
        assertEquals("zoo", BIP39_ENGLISH.last())
    }

    /** The checks keep their three-way shape: count, membership, checksum. */
    @Test
    fun theThreeLocalChecksAreDistinct() {
        val tooShort = checkVaultPassphrase("abandon abandon") as VaultPassphraseCheck.Invalid
        assertEquals(VaultPassphraseProblem.WordCount(2), tooShort.problem)

        val unknown = checkVaultPassphrase("abandon ".repeat(11) + "notaword") as VaultPassphraseCheck.Invalid
        assertEquals(VaultPassphraseProblem.UnknownWords(listOf("notaword"), listOf(11)), unknown.problem)
    }

    /**
     * No digest is typed here: with eleven words fixed, the twelfth carries the
     * last 7 entropy bits plus the 4 checksum bits, so EXACTLY 2^7 = 128 of the
     * 2048 candidates complete a valid phrase and every other candidate fails
     * on the checksum alone (count and membership already hold).
     */
    @Test
    fun exactly128LastWordsCompleteAPhraseAndTheRestFailTheChecksum() {
        val prefix = "abandon ".repeat(11)
        var valid = 0
        for (word in BIP39_ENGLISH) {
            when (val checked = checkVaultPassphrase(prefix + word)) {
                is VaultPassphraseCheck.Valid -> valid += 1
                is VaultPassphraseCheck.Invalid ->
                    assertEquals("'$word' must fail on the checksum only", VaultPassphraseProblem.Checksum, checked.problem)
            }
        }
        assertEquals(128, valid)
        // The reference vector's completion is among them (BIP-39 test vector 1).
        assertTrue(checkVaultPassphrase(prefix + "about") is VaultPassphraseCheck.Valid)
    }
}
