package at.bettertrack.app.vault.pv.custody

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.v2.BIP39_ENGLISH
import at.bettertrack.app.vault.v2.VaultPassphraseCheck
import at.bettertrack.app.vault.v2.checkVaultPassphrase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Entropy ↔ words, the conversion §12's keystore is built on.
 *
 * The keystore stores 128 bits, the user reads 12 words, and the §13 QR carries
 * the words. Two of those three are renderings of the first, so the only thing
 * that can go wrong is the rendering being lossy in one direction — which is
 * exactly what a round trip catches.
 *
 * Nothing here names a phrase. Test inputs are byte patterns and randoms, and
 * assertions are about lengths, membership and equality, never about a value
 * printed into a name or a message.
 */
class PvMnemonicEntropyTest {

    @Test
    fun `every rendered phrase is twelve words the shared wordlist knows`() {
        val entropy = ByteArray(PV_ENTROPY_BYTES) { (it * 7 + 3).toByte() }
        val words = pvEntropyToWords(entropy)
        assertEquals(PV_MNEMONIC_WORDS, words.size)
        assertTrue("a rendered word left the wordlist", words.all { it in BIP39_ENGLISH })
    }

    @Test
    fun `a rendered phrase passes the shared BIP39 checksum`() {
        // The checksum is what `vault/v2` validates on manual entry and on a
        // scan. If this file's bit packing disagreed with that validator by one
        // bit, a phrase this app itself minted would be rejected on re-entry.
        val entropy = ByteArray(PV_ENTROPY_BYTES) { (it * 31 + 11).toByte() }
        val checked = checkVaultPassphrase(pvEntropyToPhrase(entropy))
        assertTrue("self-minted phrase failed the shared checksum", checked is VaultPassphraseCheck.Valid)
    }

    @Test
    fun `entropy survives the round trip through words`() {
        val random = SecureRandom()
        repeat(64) {
            val entropy = ByteArray(PV_ENTROPY_BYTES).also(random::nextBytes)
            val recovered = pvPhraseToEntropy(pvEntropyToPhrase(entropy))
            assertNotNull(recovered)
            assertArrayEquals(entropy, recovered)
        }
    }

    @Test
    fun `the all-zero and all-ones edges round trip too`() {
        // The bit packing's two boundary inputs: index 0 and index 2047 in every
        // slot. An off-by-one in the shift would survive random data and die here.
        listOf(ByteArray(PV_ENTROPY_BYTES), ByteArray(PV_ENTROPY_BYTES) { 0xFF.toByte() }).forEach { entropy ->
            assertArrayEquals(entropy, pvPhraseToEntropy(pvEntropyToPhrase(entropy)))
        }
    }

    @Test
    fun `entry normalisation is the shared one`() {
        // Upper case, ragged whitespace and a trailing newline all reduce to the
        // same phrase, because the conversion delegates to v2's normaliser
        // rather than trimming by hand.
        val entropy = ByteArray(PV_ENTROPY_BYTES) { (it + 1).toByte() }
        val canonical = pvEntropyToPhrase(entropy)
        val ragged = "  " + canonical.uppercase().replace(" ", "   ") + "\n"
        assertArrayEquals(entropy, pvPhraseToEntropy(ragged))
    }

    @Test
    fun `a phrase that is not twelve valid checksummed words yields nothing`() {
        val entropy = ByteArray(PV_ENTROPY_BYTES) { (it * 5).toByte() }
        val words = pvEntropyToWords(entropy).toMutableList()

        assertNull("empty input", pvPhraseToEntropy(""))
        assertNull("too few words", pvPhraseToEntropy(words.take(11).joinToString(" ")))
        assertNull("too many words", pvPhraseToEntropy((words + words.first()).joinToString(" ")))

        // A word outside the list.
        val offList = words.toMutableList().also { it[4] = "bettertrack" }
        assertNull("off-wordlist word", pvPhraseToEntropy(offList.joinToString(" ")))

        // The right words in an order the checksum refuses. Swapping the first
        // two changes the entropy bits and therefore the expected checksum; the
        // odds of the swapped phrase still checksumming are 1 in 16, so the loop
        // walks positions until it finds a rejected arrangement rather than
        // asserting on a coin flip.
        val rejected = (0 until words.size - 1).any { index ->
            val shuffled = words.toMutableList()
            val held = shuffled[index]
            shuffled[index] = shuffled[index + 1]
            shuffled[index + 1] = held
            pvPhraseToEntropy(shuffled.joinToString(" ")) == null
        }
        assertTrue("no rearrangement was refused — the checksum is not being checked", rejected)
    }

    @Test
    fun `entropy of the wrong length is refused rather than padded`() {
        listOf(0, 8, 15, 17, 32).forEach { size ->
            val thrown = runCatching { pvEntropyToWords(ByteArray(size)) }.exceptionOrNull()
            assertTrue("size $size was accepted", thrown is VaultCryptoError)
        }
    }
}
