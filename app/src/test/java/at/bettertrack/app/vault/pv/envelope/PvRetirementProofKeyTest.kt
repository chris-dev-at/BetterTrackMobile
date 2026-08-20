package at.bettertrack.app.vault.pv.envelope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`vaultRetirementProofPublicKeySchema`, both ways.**
 *
 * The platform's answer (2026-08-20): base64url over `/^[A-Za-z0-9_-]+$/`, the
 * exact length of the **44-byte canonical DER SPKI Ed25519** encoding (the
 * `MCowBQYDK2VwAyEA…` family), and it must BE such a key.
 *
 * This is the verifier the server keeps for the §7 signed purge. A malformed one
 * is accepted silently at creation and discovered at the single moment it is
 * needed — a user trying to prove possession of a vault in order to retire a
 * medium. So the validation belongs at the request boundary, and it belongs
 * strict.
 *
 * ## Where the test data comes from
 *
 * The valid key is a real Ed25519 public key generated with `openssl genpkey
 * -algorithm ed25519` and exported as DER SPKI; the near-miss is a real **X25519**
 * public key from the same tool. They differ in exactly one OID byte
 * (`1.3.101.112` vs `1.3.101.110`, `…K2Vw…` vs `…K2Vu…`) and are the same 44
 * bytes long, which is precisely why a length-only rule is not enough. Neither
 * key's private half exists anywhere and neither opens anything.
 */
class PvRetirementProofKeyTest {

    /** A real Ed25519 SPKI, DER, base64url — 59 characters. */
    private val validKey = "MCowBQYDK2VwAyEAkQ1iFgj4ykFF8aswkbTnlvq61hZ26Bc9i_R68XCbmcE"

    /** Same length, same family shape, WRONG CURVE: an X25519 SPKI. */
    private val x25519Key = "MCowBQYDK2VuAyEAu6Z3-rVcM3DfBrz2p3Z1JjuPeMPfZQVIwqGivh1RAV0"

    /** The all-zero key the E0 conformance fixture carries. */
    private val fixtureKey = "MCowBQYDK2VwAyEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    private fun problem(value: String): String? =
        PvVaultConfig.retirementProofPublicKeyProblem(value)

    // ── the constants themselves ────────────────────────────────────────────

    @Test
    fun `the DER prefix is the Ed25519 SubjectPublicKeyInfo header`() {
        // 30 2a 30 05 06 03 2b 65 70 03 21 00 — RFC 8410 §4. Written out here so
        // a future edit of the constant has to disagree with a second copy.
        assertEquals(12, PV_ED25519_SPKI_PREFIX.size)
        assertEquals(
            "302a300506032b6570032100",
            PV_ED25519_SPKI_PREFIX.joinToString("") { "%02x".format(it) },
        )
        assertEquals("12 prefix bytes + a 32-byte key", 44, PV_ED25519_SPKI_BYTES)
        // 44 bytes unpadded is 59 base64url characters, and the prefix lands on a
        // 3-byte boundary, so the family string is literal rather than derived.
        assertEquals(59, PV_ED25519_SPKI_B64URL_CHARS)
        assertEquals("MCowBQYDK2VwAyEA", PV_ED25519_SPKI_B64URL_PREFIX)
        assertTrue(validKey.startsWith(PV_ED25519_SPKI_B64URL_PREFIX))
        assertEquals(PV_ED25519_SPKI_B64URL_CHARS, validKey.length)
    }

    // ── accepted ────────────────────────────────────────────────────────────

    @Test
    fun `a real Ed25519 SPKI key is accepted`() {
        assertNull(problem(validKey))
        assertNull(problem(fixtureKey))
    }

    @Test
    fun `the create-vault body accepts it as part of a whole request`() {
        assertNull(
            PvVaultConfig.createVaultProblem(
                name = "Familie",
                media = listOf("server"),
                driveConnectionId = null,
                keyFingerprint = "AAAAAAAAAAAAAAAA",
                retirementProofPublicKey = validKey,
            ),
        )
    }

    // ── refused ─────────────────────────────────────────────────────────────

    @Test
    fun `an X25519 key of exactly the right length is refused`() {
        // The case a length-only rule would wave through. One OID byte apart,
        // and it can never verify an Ed25519 purge signature.
        assertEquals(PV_ED25519_SPKI_B64URL_CHARS, x25519Key.length)
        assertNotEquals(null, problem(x25519Key))
        assertTrue(problem(x25519Key)!!.contains("DER SPKI Ed25519"))
    }

    @Test
    fun `the raw thirty-two byte key without its DER wrapper is refused`() {
        // The most likely real mistake: exporting `raw` instead of `spki`.
        val raw = validKey.drop(PV_ED25519_SPKI_B64URL_PREFIX.length)
        assertEquals(43, raw.length)
        assertNotEquals(null, problem(raw))
    }

    @Test
    fun `sixty-nine characters of perfectly good base64url are still refused`() {
        assertNotEquals(null, problem("A".repeat(PV_ED25519_SPKI_B64URL_CHARS + 10)))
        assertNotEquals(null, problem("A".repeat(PV_ED25519_SPKI_B64URL_CHARS - 1)))
    }

    @Test
    fun `right length, right alphabet, wrong bytes is refused`() {
        // 59 characters of `A` decodes to 44 zero bytes — the exact length, and
        // no DER header at all.
        val allZeroBytes = "A".repeat(PV_ED25519_SPKI_B64URL_CHARS)
        assertEquals(PV_ED25519_SPKI_B64URL_CHARS, allZeroBytes.length)
        assertNotEquals(null, problem(allZeroBytes))
    }

    @Test
    fun `padded or standard-alphabet base64 is refused`() {
        assertNotEquals("padding is not part of base64url", null, problem(validKey.dropLast(1) + "="))
        // `/` and `+` are the STANDARD alphabet's two characters; a client that
        // encoded with `btoa` instead of the url-safe variant lands here.
        assertNotEquals(null, problem(validKey.replace('_', '/')))
        assertNotEquals(null, problem(validKey.replaceFirst("k", "+")))
        assertNotEquals(null, problem(""))
        assertNotEquals(null, problem("   "))
    }

    @Test
    fun `a non-canonical spelling of the same key is refused`() {
        // 44 bytes in 59 base64url characters leaves 2 padding bits in the final
        // group. Setting them makes a SECOND string that decodes to the same
        // key, and a field with two spellings is a field two clients can
        // disagree about while both being "right".
        val nonCanonical = validKey.dropLast(1) + "F"
        assertEquals(validKey.length, nonCanonical.length)
        assertNotEquals(validKey, nonCanonical)
        assertNull("the canonical spelling must still pass", problem(validKey))
        assertEquals("must be canonical base64url", problem(nonCanonical))
    }

    @Test
    fun `the create-vault body reports which field was wrong`() {
        val reported = PvVaultConfig.createVaultProblem(
            name = "Familie",
            media = listOf("server"),
            driveConnectionId = null,
            keyFingerprint = "AAAAAAAAAAAAAAAA",
            retirementProofPublicKey = x25519Key,
        )
        assertTrue("got: $reported", reported!!.startsWith("retirementProofPublicKey "))
    }

    // ── the doc payload keeps the looser rule, deliberately ─────────────────

    @Test
    fun `the encrypted doc still accepts a key this validator would refuse`() {
        // Stated as a test because it looks like an inconsistency and is not.
        // Refusing to SEND a malformed key costs a retry; refusing to OPEN the
        // common doc costs the vault, and §16 says there is nothing behind it.
        val proof = PvRetirementProof(publicKey = x25519Key, privateKey = "AAAA")
        assertEquals(proof, PvRetirementProof.parse(proof.toJson()))
        assertNotEquals(null, problem(x25519Key))
    }
}
