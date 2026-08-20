package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.pv.custody.PV_ENTROPY_BYTES
import at.bettertrack.app.vault.pv.custody.pvPhraseToEntropy
import at.bettertrack.app.vault.v2.normalizeVaultPassphrase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The published BIP-39 test vectors, replayed against this app's PBKDF2.**
 *
 * `bip39-trezor.fixture.json` is the `english` set of
 * `https://github.com/trezor/python-mnemonic/blob/master/vectors.json` — the
 * reference vectors that ship with the BIP-39 reference implementation, fetched
 * 2026-08-20 and vendored verbatim (entropy, mnemonic, seed; the trailing
 * `xprv` of each row is BIP-32 and out of this arc's scope). Provenance lives
 * in the fixture's `_source` block and is asserted below, so the file cannot
 * quietly become "some numbers someone generated".
 *
 * ## Why an external vector set is the right oracle here
 *
 * §4 adopts BIP-39 *because* it is boring and ubiquitous: *"the standard BIP39
 * PBKDF2 step keeps us vector-compatible with every BIP39 tool"*. A
 * self-generated fixture would prove only that this file agrees with itself.
 * These 24 rows prove it agrees with the specification — which is the property
 * the design actually leans on, since the same phrase must reach the same seed
 * in a browser, on a phone, and in any recovery tool a user reaches for after
 * BetterTrack is gone.
 *
 * ## The passphrase in the fixture
 *
 * Every published vector uses BIP-39's optional passphrase `"TREZOR"`. This app
 * always uses the empty one (§4: "the standard, empty passphrase"), so the
 * vectors pin the *machinery* — iterations, digest, salt construction, output
 * length, encoding — while `the app's own call never carries a passphrase`
 * below pins the production parameter. Both halves are needed; neither alone is.
 *
 * ## Nothing here is a real phrase
 *
 * These mnemonics are specification data, printed in the BIP itself and in every
 * BIP-39 library's test suite. No test name below quotes a word from them.
 */
class PvBip39VectorTest {

    private data class Vector(val entropy: String, val mnemonic: String, val seed: String)

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/bip39-trezor.fixture.json")
            ?: error("vault-vectors/bip39-trezor.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val fixturePassphrase: String by lazy {
        fixture["passphrase"]!!.jsonPrimitive.content
    }

    private val vectors: List<Vector> by lazy {
        fixture["vectors"]!!.jsonArray.map { element ->
            val row = element.jsonObject
            Vector(
                entropy = row["entropy"]!!.jsonPrimitive.content,
                mnemonic = row["mnemonic"]!!.jsonPrimitive.content,
                seed = row["seed"]!!.jsonPrimitive.content,
            )
        }
    }

    /** The 12-word rows — the only length this app issues (§4). */
    private val shortVectors: List<Vector> get() = vectors.filter { it.mnemonic.split(" ").size == 12 }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ── the fixture itself ──────────────────────────────────────────────────

    @Test
    fun `the fixture still says where it came from`() {
        // A vendored vector file with no provenance is indistinguishable from a
        // file someone regenerated to make a failing test pass.
        val source = fixture["_source"]!!.jsonObject
        assertEquals(
            "https://github.com/trezor/python-mnemonic/blob/master/vectors.json",
            source["upstream"]!!.jsonPrimitive.content,
        )
        assertEquals("english", source["set"]!!.jsonPrimitive.content)
        assertEquals("TREZOR", source["passphrase"]!!.jsonPrimitive.content)
        assertEquals("mnemonic", source["saltPrefix"]!!.jsonPrimitive.content)
        assertEquals(PV_BIP39_PBKDF2_ITERATIONS, source["iterations"]!!.jsonPrimitive.content.toInt())
        assertEquals(PV_BIP39_SEED_BYTES, source["seedBytes"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the whole english set is vendored, not a convenient subset`() {
        assertEquals("the upstream english set has 24 rows", 24, vectors.size)
        val byLength = vectors.groupingBy { it.mnemonic.split(" ").size }.eachCount()
        assertEquals("8 rows each of 12, 18 and 24 words", mapOf(12 to 8, 18 to 8, 24 to 8), byLength)
        assertTrue("a row lost its seed", vectors.all { it.seed.length == PV_BIP39_SEED_BYTES * 2 })
    }

    // ── the conformance itself ──────────────────────────────────────────────

    @Test
    fun `every vector derives its published seed`() {
        val failures = vectors
            .filter { hex(pvBip39Seed(it.mnemonic, fixturePassphrase)) != it.seed }
            .map { it.entropy }
        assertTrue(
            "PBKDF2-HMAC-SHA512 disagreed with the published seed for the vectors " +
                "with entropy: $failures",
            failures.isEmpty(),
        )
        // Belt and braces: prove the loop above actually ran 24 comparisons
        // rather than iterating an empty list into a green test.
        assertEquals(24, vectors.count { hex(pvBip39Seed(it.mnemonic, fixturePassphrase)) == it.seed })
    }

    @Test
    fun `every twelve-word vector renders from its published entropy`() {
        // The other direction of the same standard: the app mints words FROM
        // bits, so the bits in the vector must produce the vector's sentence.
        shortVectors.forEach { vector ->
            val entropy = unhex(vector.entropy)
            assertEquals(PV_ENTROPY_BYTES, entropy.size)
            assertEquals(vector.mnemonic, pvMnemonicFromEntropy(entropy).phrase)
        }
        assertEquals(8, shortVectors.size)
    }

    @Test
    fun `every twelve-word vector round trips back to its entropy`() {
        shortVectors.forEach { vector ->
            val recovered = pvPhraseToEntropy(vector.mnemonic)
            assertNotNull("the shared parser rejected a published vector", recovered)
            assertEquals(vector.entropy, hex(recovered!!))
        }
    }

    @Test
    fun `the published sentences are already in the app's canonical form`() {
        // The seed is derived from the phrase the user's other device produced,
        // and `vault/v2`'s normalisation (NFKD, lower-case, single-spaced) is
        // what every entry path applies. If it changed a canonical BIP-39
        // sentence, manual entry and the §13 scan would derive a different seed
        // from the same correct words.
        vectors.forEach { vector ->
            assertEquals(vector.mnemonic, normalizeVaultPassphrase(vector.mnemonic))
        }
    }

    @Test
    fun `normalisation makes a messy transcription derive the vector's seed anyway`() {
        val vector = shortVectors.first()
        val messy = "  " + vector.mnemonic.uppercase().replace(" ", "   ") + "\n"
        assertEquals(
            vector.seed,
            hex(pvBip39Seed(normalizeVaultPassphrase(messy), fixturePassphrase)),
        )
    }

    // ── the production parameter ────────────────────────────────────────────

    @Test
    fun `the app's own call never carries a passphrase`() {
        // §4 pins the empty passphrase. If the default ever gained a value, the
        // vectors above would still pass (they pass one explicitly) while every
        // real vault silently changed key — so the default is pinned here.
        val vector = shortVectors.first()
        assertEquals(
            hex(pvBip39Seed(vector.mnemonic, "")),
            hex(pvBip39Seed(vector.mnemonic)),
        )
        assertNotEquals(
            "an empty passphrase must not derive the same seed as a set one",
            hex(pvBip39Seed(vector.mnemonic, "")),
            vector.seed,
        )
    }

    @Test
    fun `the seed is deterministic and full length`() {
        val vector = shortVectors.last()
        val once = pvBip39Seed(vector.mnemonic)
        val twice = pvBip39Seed(vector.mnemonic)
        assertEquals(PV_BIP39_SEED_BYTES, once.size)
        assertEquals(hex(once), hex(twice))
    }

    @Test
    fun `two different phrases never share a seed`() {
        val seeds = shortVectors.map { hex(pvBip39Seed(it.mnemonic)) }.toSet()
        assertEquals(shortVectors.size, seeds.size)
    }

    @Test
    fun `an empty mnemonic is refused rather than derived from`() {
        assertThrows(VaultCryptoError::class.java) { pvBip39Seed("   ") }
    }
}
