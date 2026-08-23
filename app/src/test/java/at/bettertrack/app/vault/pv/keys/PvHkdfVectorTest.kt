package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.v2.VAULT_HKDF_EMPTY_SALT
import at.bettertrack.app.vault.v2.hkdfSha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **RFC 5869's own HKDF test vectors, replayed against this app's primitive.**
 *
 * `rfc5869-hkdf.fixture.json` is Appendix A.1–A.3 of RFC 5869 — the three
 * SHA-256 cases (A.4–A.7 use SHA-1 and are out of scope) — transcribed verbatim
 * with provenance in the fixture's `_source` block, which is asserted below so
 * the file cannot quietly become "some numbers someone generated".
 *
 * ## Why this test exists next to the §4 chain
 *
 * The paranoid-vaults derivation (`PvVaultKeyDerivation.kt`) is HKDF twice.
 * These vectors move the *primitive* out of the app's own circle — they are
 * public data, owned by neither client, so they still say something the
 * platform's E3 fixture cannot: that the machinery underneath the chain is
 * RFC 5869 and not merely two implementations of the same mistake. What they
 * prove:
 *
 *  - Bouncy Castle's `HKDFBytesGenerator`, which is what the phone runs, computes
 *    RFC 5869 exactly — so it computes what WebCrypto's `deriveBits` computes,
 *    which is what the web client runs;
 *  - **A.3 is the load-bearing one.** It is the zero-length-salt case, and the
 *    empty salt is precisely the §4 convention ruled on 2026-08-20. An
 *    implementation that quietly treated "no salt" as anything other than
 *    HashLen zero bytes would fail A.3 and nothing else.
 *
 * What they do NOT prove is that the BetterTrack-specific `info` strings, output
 * lengths and truncations match the platform's. That is E3's fixture
 * (`vault-vectors/pv-derivation.e3.fixture.json`), which landed on 2026-08-23
 * and set [PV_E3_PINNED].
 *
 * The primitive is shared with the shipped v2 rail (`vault/v2/VaultHkdf.kt`), so
 * this covers that rail too; `VaultV2ConformanceTest` pins A.1 inline as well,
 * which is a deliberate duplicate — the v2 suite must stay standalone.
 */
class PvHkdfVectorTest {

    private data class Vector(
        val name: String,
        val ikm: ByteArray,
        val salt: ByteArray,
        val info: ByteArray,
        val length: Int,
        val okm: String,
    )

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/rfc5869-hkdf.fixture.json")
            ?: error("vault-vectors/rfc5869-hkdf.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val vectors: List<Vector> by lazy {
        fixture["vectors"]!!.jsonArray.map { element ->
            val row = element.jsonObject
            Vector(
                name = row["name"]!!.jsonPrimitive.content,
                ikm = unhex(row["ikm"]!!.jsonPrimitive.content),
                salt = unhex(row["salt"]!!.jsonPrimitive.content),
                info = unhex(row["info"]!!.jsonPrimitive.content),
                length = row["L"]!!.jsonPrimitive.content.toInt(),
                okm = row["okm"]!!.jsonPrimitive.content,
            )
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ── the fixture itself ──────────────────────────────────────────────────

    @Test
    fun `the fixture still says where it came from`() {
        val source = fixture["_source"]!!.jsonObject
        assertEquals(
            "https://www.rfc-editor.org/rfc/rfc5869#appendix-A",
            source["upstream"]!!.jsonPrimitive.content,
        )
        assertEquals("SHA-256", source["hash"]!!.jsonPrimitive.content)
        assertEquals("the three SHA-256 appendix cases", 3, vectors.size)
        assertEquals(listOf("A.1", "A.2", "A.3"), vectors.map { it.name })
    }

    // ── the conformance itself ──────────────────────────────────────────────

    @Test
    fun `every published vector derives its published output`() {
        val failures = vectors
            .filter { hex(hkdfSha256(it.ikm, it.info, it.length, it.salt)) != it.okm }
            .map { it.name }
        assertTrue("HKDF-SHA256 disagreed with RFC 5869 for: $failures", failures.isEmpty())
        // Prove the loop compared three things rather than iterating an empty list.
        assertEquals(3, vectors.count { hex(hkdfSha256(it.ikm, it.info, it.length, it.salt)) == it.okm })
    }

    @Test
    fun `the zero-length-salt vector is the one that pins the paranoid convention`() {
        // A.3 stated on its own, because it is the case §4 rides on and a future
        // edit that "simplified" the empty-salt handling would break only this.
        val a3 = vectors.first { it.name == "A.3" }
        assertEquals(0, a3.salt.size)
        assertEquals(0, a3.info.size)
        assertEquals(a3.okm, hex(hkdfSha256(a3.ikm, a3.info, a3.length, a3.salt)))
        assertEquals(
            "the shared empty-salt constant must derive A.3 too",
            a3.okm,
            hex(hkdfSha256(a3.ikm, a3.info, a3.length, VAULT_HKDF_EMPTY_SALT)),
        )
        assertEquals(
            "the default salt must BE the empty salt",
            a3.okm,
            hex(hkdfSha256(a3.ikm, a3.info, a3.length)),
        )
    }

    @Test
    fun `an empty salt means HashLen zero bytes, not no extract step`() {
        // RFC 5869 §2.2: "if not provided, it is set to a string of HashLen
        // zeros". This is the claim VaultHkdf's KDoc makes, and it is the claim
        // that makes an empty salt interoperable rather than merely convenient:
        // a client that passed 32 explicit zeros must land on the same key.
        val a3 = vectors.first { it.name == "A.3" }
        val explicitZeros = ByteArray(32)
        assertEquals(
            a3.okm,
            hex(hkdfSha256(a3.ikm, a3.info, a3.length, explicitZeros)),
        )
        // …and it is NOT the same as skipping extract or salting with anything
        // else, which is exactly the silent divergence §4 was afraid of.
        assertNotEquals(
            a3.okm,
            hex(hkdfSha256(a3.ikm, a3.info, a3.length, ByteArray(32) { 0x01 })),
        )
    }

    @Test
    fun `the shared empty-salt constant is empty and shared`() {
        assertEquals(0, VAULT_HKDF_EMPTY_SALT.size)
        assertTrue(
            "the §4 derivations must take the v2 rail's constant, not their own array",
            pvDerivationSalt() === VAULT_HKDF_EMPTY_SALT,
        )
    }
}
