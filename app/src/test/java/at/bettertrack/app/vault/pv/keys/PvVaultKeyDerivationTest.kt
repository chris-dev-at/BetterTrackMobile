package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **E3 derivation: what is implemented, what proves it, and what is still owed.**
 *
 * The §4 chain above the BIP-39 seed is now WRITTEN. Its last open parameter —
 * the HKDF salt — was ruled on 2026-08-20 as RFC 5869's empty salt for both
 * `K_wrap` and `key_fingerprint`, and `PvVaultKeyDerivation.kt` no longer
 * refuses to derive.
 *
 * What is NOT finished is the proof of *cross-client* agreement, and this file
 * is deliberately honest about the difference. Three kinds of test live here:
 *
 *  1. **Tests that run today and pin the implementation.** The salt convention
 *     (asserted at the source level, so a literal cannot be slipped back in),
 *     the input contracts E0 answered, the contract literals, and the
 *     self-derived chain fixture — which cross-checks Kotlin/BouncyCastle
 *     against an independent WebCrypto run of the same specification.
 *  2. **The RFC 5869 vectors**, in `PvHkdfVectorTest` — the external oracle for
 *     the primitive itself, including the empty-salt case.
 *  3. **The platform's own E3 vectors**, which have NOT shipped. Those three
 *     tests still `assumeTrue` on [PV_E3_PINNED] and on the presence of
 *     `vault-vectors/pv-derivation.fixture.json`, and show as SKIPPED with the
 *     reason in the report until the platform delivers.
 *
 * The skip is not silence: `the chain is implemented, and E3 is honest about
 * what is still unproven` below runs on every build.
 */
class PvVaultKeyDerivationTest {

    /** A syntactically real vault id; nothing about it is secret. */
    private val vaultId = "6b1f2f4c-2b2a-4a7f-9f3a-0d7c1f5a9e21"

    private fun seed(fill: Byte = 0x11): ByteArray = ByteArray(PV_BIP39_SEED_BYTES) { fill }

    private fun contentKey(fill: Byte = 0x22): ByteArray = ByteArray(PV_WRAP_KEY_BYTES) { fill }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun derivationSource(): String {
        val relative = "src/main/java/at/bettertrack/app/vault/pv/keys/PvVaultKeyDerivation.kt"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }

    // ── half one: the implemented state, asserted out loud ──────────────────

    @Test
    fun `the chain is implemented, and E3 is honest about what is still unproven`() {
        // The canary, inverted from what it used to be. It no longer says "do
        // not derive"; it says "derive, and do not claim the platform has
        // checked you". Both halves have to keep being true.
        pvVaultWrapKey(seed(), vaultId)
        pvKeyFingerprint(contentKey())
        assertFalse(
            "PV_E3_PINNED is set. That flag means the PLATFORM's E3 vectors " +
                "(vault-vectors/pv-derivation.fixture.json) are in the tree and " +
                "green — not that the chain compiles. If they landed, drop the " +
                "fixture in, delete pv-derivation.selfderived.fixture.json and " +
                "flip this. If they did not, cross-client byte-identity is still " +
                "unproven and the flag must stay false.",
            PV_E3_PINNED,
        )
    }

    @Test
    fun `the salt convention is stated once, by name, and never as a literal`() {
        // The source check that replaced the old "exactly one throw" tripwire.
        // The failure it guards is the one that cannot be caught by running the
        // code: a second empty array, or worse a non-empty one, written straight
        // into an HKDF call. Both would derive perfectly valid keys that open
        // nothing another client wrote.
        val source = derivationSource()
        assertEquals(
            "every derivation must take its salt from pvDerivationSalt()",
            2,
            Regex("""salt = pvDerivationSalt\(\)""").findAll(source).count(),
        )
        val body = Regex("""fun pvDerivationSalt\(\)[^\n]*""").find(source)?.value
            ?: error("pvDerivationSalt() is gone — the one place the §4 salt is stated")
        assertTrue(
            "pvDerivationSalt() must return the shared empty-salt primitive " +
                "(vault/v2/VaultHkdf.kt), not a second spelling of 'empty': $body",
            body.contains("VAULT_HKDF_EMPTY_SALT"),
        )
        assertFalse(
            "a salt must never be constructed in this file: $body",
            body.contains("ByteArray(") || body.contains("byteArrayOf("),
        )
        assertFalse(
            "the derivation file must not build any byte array by hand",
            Regex("""byteArrayOf\(""").containsMatchIn(source),
        )
    }

    // ── the answers E0 gave, enforced before anything derives ───────────────

    @Test
    fun `the wrap key derives from the sixty-four byte seed, not the words or the bits`() {
        // E0 answered the IKM. A caller passing the 16-byte entropy — the thing
        // the keystore holds, and the easiest wrong argument to reach for — is
        // refused by shape before anything derives.
        assertThrows(VaultCryptoError::class.java) { pvVaultWrapKey(ByteArray(16), vaultId) }
        assertThrows(VaultCryptoError::class.java) { pvVaultWrapKey(ByteArray(32), vaultId) }
        assertThrows(VaultCryptoError::class.java) { pvVaultWrapKey(ByteArray(0), vaultId) }
        // …and a correctly-shaped seed derives an AES-256 key, which is how we
        // know the refusals above were about the shape and not about everything.
        assertEquals(PV_WRAP_KEY_BYTES, pvVaultWrapKey(seed(), vaultId).size)
    }

    @Test
    fun `a blank vault id would erase the domain separation and is refused`() {
        assertThrows(VaultCryptoError::class.java) { pvVaultWrapKey(seed(), "") }
        assertThrows(VaultCryptoError::class.java) { pvVaultWrapKey(seed(), "   ") }
    }

    @Test
    fun `the fingerprint takes a two-hundred-fifty-six bit content key`() {
        assertThrows(VaultCryptoError::class.java) { pvKeyFingerprint(ByteArray(16)) }
        assertThrows(VaultCryptoError::class.java) { pvKeyFingerprint(ByteArray(64)) }
        assertEquals(PvVaultContract.KEY_FINGERPRINT_CHARS, pvKeyFingerprint(contentKey()).length)
    }

    @Test
    fun `the contract literals this chain derives against have not drifted`() {
        // The info strings and the truncation ARE answered, and they are the
        // half of the derivation that a later edit could break silently.
        assertEquals("bettertrack-vault-wrap-v1:", PvVaultContract.WRAP_HKDF_INFO_PREFIX)
        assertEquals("bettertrack-vault-fingerprint-v1", PvVaultContract.KEY_FINGERPRINT_HKDF_INFO)
        assertEquals("16 base64url CHARACTERS, not 16 bytes", 16, PvVaultContract.KEY_FINGERPRINT_CHARS)
        assertEquals(32, PV_WRAP_KEY_BYTES)
        // Any HKDF output of 12 bytes or more yields the same first 16 base64url
        // characters, so this length is free to be generous but not to be short.
        assertTrue("the fingerprint HKDF output is too short to fill 16 chars", PV_FINGERPRINT_HKDF_BYTES >= 12)
    }

    @Test
    fun `the fingerprint is base64url characters and carries no padding`() {
        val printed = pvKeyFingerprint(contentKey())
        assertTrue("'$printed' is not base64url", Regex("^[A-Za-z0-9_-]{16}$").matches(printed))
        // Prefix-stability: a longer HKDF output must not change the 16 chars,
        // which is what makes PV_FINGERPRINT_HKDF_BYTES a free parameter.
        assertEquals(printed, pvKeyFingerprint(contentKey()))
    }

    @Test
    fun `two vault ids separate one phrase into two unrelated wrap keys`() {
        // The property §4 leans on. It needs no vectors, so it is not gated:
        // if the info string ever stopped carrying the vault id, this is the
        // test that notices, and it notices on every build.
        val shared = seed()
        assertNotEquals(
            hex(pvVaultWrapKey(shared, vaultId)),
            hex(pvVaultWrapKey(shared, "0f0e0d0c-0b0a-4908-8706-050403020100")),
        )
    }

    // ── half two: the SELF-DERIVED chain fixture (runs; proves what it can) ──

    /**
     * The full chain, cross-checked against Node WebCrypto. Not the platform's
     * data — see the file's own `_provenance` and [PV_E3_PINNED].
     */
    private val selfFixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/pv-derivation.selfderived.fixture.json")
            ?: error("vault-vectors/pv-derivation.selfderived.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val selfChain: List<JsonObject> by lazy {
        selfFixture["chain"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun `the self-derived fixture admits in writing that it is not the platform's`() {
        // The single most important assertion about this file. A fixture that
        // looks authoritative and is not is worse than no fixture at all: it
        // ends an investigation that should have continued.
        assertEquals(
            "self-derived pending platform E3 fixture - replace, never merge",
            selfFixture.str("_provenance"),
        )
        val source = selfFixture["_source"]!!.jsonObject
        assertTrue(
            "the fixture must name the independent generator it was cross-checked against",
            source.str("generator").contains("WebCrypto"),
        )
        assertTrue(
            "the fixture must say the platform E3 vectors have not shipped",
            source.str("why").contains("has NOT shipped"),
        )
        assertEquals("the chain fixture lost rows", 3, selfChain.size)
    }

    @Test
    fun `the whole chain reproduces the cross-checked bytes`() {
        // mnemonic → seed → K_wrap → fingerprint, every link, on every row.
        selfChain.forEach { row ->
            val derivedSeed = pvBip39Seed(row.str("mnemonic"))
            assertEquals("seed for ${row.str("entropy")}", row.str("seed"), hex(derivedSeed))
            val wrapKey = pvVaultWrapKey(derivedSeed, row.str("vaultId"))
            assertEquals("K_wrap for ${row.str("vaultId")}", row.str("kWrap"), hex(wrapKey))
            assertEquals(
                "fingerprint for ${row.str("vaultId")}",
                row.str("keyFingerprint"),
                pvKeyFingerprint(unhex(row.str("contentKey"))),
            )
        }
    }

    @Test
    fun `the fixture's own separation rows still separate`() {
        val separation = selfFixture["separation"]!!.jsonObject
        val sharedSeed = pvBip39Seed(separation.str("mnemonic"))
        assertEquals(separation.str("kWrapA"), hex(pvVaultWrapKey(sharedSeed, separation.str("vaultIdA"))))
        assertEquals(separation.str("kWrapB"), hex(pvVaultWrapKey(sharedSeed, separation.str("vaultIdB"))))
        assertNotEquals(separation.str("kWrapA"), separation.str("kWrapB"))
    }

    // ── half three: the PLATFORM vectors, skipped with their reason ─────────

    private val skipReason =
        "The platform's E3 conformance fixture has not shipped: " +
            "vault-vectors/pv-derivation.fixture.json is absent and PV_E3_PINNED " +
            "is false. The chain IS implemented and is covered by the RFC 5869 " +
            "vectors plus a self-derived cross-check; what these three tests add " +
            "is byte-identity with the platform, which nothing can assert yet. " +
            "Drop the fixture in and flip PV_E3_PINNED to run them."

    /** Null until the platform ships the E3 vectors next to the E0 ones. */
    private val derivationFixture: JsonObject? by lazy {
        javaClass.getResourceAsStream("/vault-vectors/pv-derivation.fixture.json")?.let { stream ->
            Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        }
    }

    private fun requireE3Vectors(): JsonObject {
        assumeTrue(skipReason, PV_E3_PINNED)
        val fixture = derivationFixture
        assumeTrue(skipReason, fixture != null)
        return fixture!!
    }

    @Test
    fun `the wrap key matches the platform vectors`() {
        val fixture = requireE3Vectors()
        fixture["wrapKeys"]!!.jsonArray.forEach { element ->
            val row = element.jsonObject
            assertEquals(
                row["kWrap"]!!.jsonPrimitive.content,
                hex(
                    pvVaultWrapKey(
                        unhex(row["seed"]!!.jsonPrimitive.content),
                        row["vaultId"]!!.jsonPrimitive.content,
                    ),
                ),
            )
        }
    }

    @Test
    fun `the key fingerprint matches the platform vectors`() {
        val fixture = requireE3Vectors()
        fixture["fingerprints"]!!.jsonArray.forEach { element ->
            val row = element.jsonObject
            val printed = pvKeyFingerprint(unhex(row["contentKey"]!!.jsonPrimitive.content))
            assertEquals(row["keyFingerprint"]!!.jsonPrimitive.content, printed)
            assertEquals(PvVaultContract.KEY_FINGERPRINT_CHARS, printed.length)
        }
    }

    @Test
    fun `two vaults never derive the same wrap key from one phrase`() {
        requireE3Vectors()
        val shared = seed()
        assertFalse(
            hex(pvVaultWrapKey(shared, vaultId)) ==
                hex(pvVaultWrapKey(shared, "0f0e0d0c-0b0a-4908-8706-050403020100")),
        )
    }
}
