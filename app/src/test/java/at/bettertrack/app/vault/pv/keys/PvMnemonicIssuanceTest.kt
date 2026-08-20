package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.pv.custody.PV_ENTROPY_BYTES
import at.bettertrack.app.vault.pv.custody.PV_MNEMONIC_WORDS
import at.bettertrack.app.vault.pv.custody.pvPhraseToEntropy
import at.bettertrack.app.vault.v2.BIP39_ENGLISH
import at.bettertrack.app.vault.v2.VaultPassphraseCheck
import at.bettertrack.app.vault.v2.checkVaultPassphrase
import at.bettertrack.app.vault.v2.normalizeVaultPassphrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Issuance — the moment a vault's only credential comes into existence.
 *
 * The property that matters is a round trip: whatever is minted must be
 * readable back by the SAME parsers that read a typed phrase and a scanned one,
 * because those are the paths a user's second device will arrive through. A
 * mint that produced words no entry path accepts would be discovered by the
 * user, on the day they need it most, with no escrow to fall back on (§16).
 *
 * Nothing below names a word from any phrase; inputs are byte patterns and
 * randoms, assertions are about membership, length and equality.
 */
class PvMnemonicIssuanceTest {

    @Test
    fun `a minted phrase is twelve words the shared wordlist knows`() {
        val issued = pvIssueMnemonic()
        assertEquals(PV_MNEMONIC_WORDS, issued.words.size)
        assertTrue("a minted word left the wordlist", issued.words.all { it in BIP39_ENGLISH })
        assertEquals(PV_ENTROPY_BYTES, issued.entropy.size)
    }

    @Test
    fun `a minted phrase passes the checksum every entry path applies`() {
        repeat(64) {
            val issued = pvIssueMnemonic()
            val checked = checkVaultPassphrase(issued.phrase)
            assertTrue(
                "the shared validator rejected a phrase this app just minted",
                checked is VaultPassphraseCheck.Valid,
            )
        }
    }

    @Test
    fun `a minted phrase is already canonical`() {
        // The sentence is handed to the §13 QR payload and to PBKDF2 as-is; if
        // it were not already normalised, the two sides would disagree the
        // moment one of them normalised and the other did not.
        val issued = pvIssueMnemonic()
        assertEquals(issued.phrase, normalizeVaultPassphrase(issued.phrase))
    }

    @Test
    fun `every mint round trips back to the bits it came from`() {
        repeat(64) {
            val issued = pvIssueMnemonic()
            val recovered = pvPhraseToEntropy(issued.phrase)
            assertNotNull("the shared parser could not read a freshly minted phrase", recovered)
            assertTrue(
                "entropy changed across the render/parse round trip",
                issued.entropy.contentEquals(recovered!!),
            )
        }
    }

    @Test
    fun `the rendering is a pure function of the bits`() {
        val entropy = ByteArray(PV_ENTROPY_BYTES) { (it * 11 + 5).toByte() }
        assertEquals(
            pvMnemonicFromEntropy(entropy).phrase,
            pvMnemonicFromEntropy(entropy.copyOf()).phrase,
        )
    }

    @Test
    fun `the caller cannot mutate a minted phrase's bits from underneath it`() {
        // pvMnemonicFromEntropy copies, so a caller that zeroes its own buffer
        // (the right thing to do with key material) does not silently blank the
        // entropy the ceremony is about to hand to custody.
        val entropy = ByteArray(PV_ENTROPY_BYTES) { 0x5a }
        val issued = pvMnemonicFromEntropy(entropy)
        entropy.fill(0)
        assertTrue("the issued entropy aliased the caller's buffer", issued.entropy.any { it != 0.toByte() })
    }

    @Test
    fun `the generator is where the randomness comes from`() {
        // Injectable so a fixed stream is reproducible — and so this test can
        // prove the function draws exactly the 16 bytes it is given rather than
        // deriving them from anything else.
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                for (index in bytes.indices) bytes[index] = (index + 1).toByte()
            }
        }
        val first = pvIssueMnemonic(fixed)
        val second = pvIssueMnemonic(fixed)
        assertEquals(first.phrase, second.phrase)
        assertEquals(
            pvMnemonicFromEntropy(ByteArray(PV_ENTROPY_BYTES) { (it + 1).toByte() }).phrase,
            first.phrase,
        )
    }

    @Test
    fun `two mints from the real generator do not collide`() {
        val phrases = (1..32).map { pvIssueMnemonic().phrase }.toSet()
        assertEquals("SecureRandom repeated itself, or the mint ignores it", 32, phrases.size)
    }

    @Test
    fun `a wrong-sized buffer is refused rather than padded`() {
        assertThrows(VaultCryptoError::class.java) { pvMnemonicFromEntropy(ByteArray(15)) }
        assertThrows(VaultCryptoError::class.java) { pvMnemonicFromEntropy(ByteArray(32)) }
        assertThrows(VaultCryptoError::class.java) { pvMnemonicFromEntropy(ByteArray(0)) }
    }

    @Test
    fun `a minted phrase never prints itself`() {
        // The single most likely route from "in memory" to "in a bug report" is
        // an interpolated state object. PvIssuedMnemonic is not a data class for
        // exactly this reason.
        val issued = pvIssueMnemonic()
        val printed = "$issued"
        assertEquals("PvIssuedMnemonic(redacted)", printed)
        // A constant, so the assertion is exact rather than a substring scan —
        // several wordlist entries are substrings of ordinary English ("act" of
        // "redacted"), and a probabilistic guard is not a guard.
        assertTrue("the redaction is not a constant", printed.none { it.isDigit() })
    }
}
