package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.pvAccountBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **E3 derivation, pinned by the platform's own bytes.**
 *
 * The §4 chain above the BIP-39 seed is written, its last open parameter (the
 * HKDF salt) was ruled on 2026-08-20, and as of **2026-08-23 it is proven
 * byte-identical to the web client**: the platform shipped its E3 conformance
 * vectors and `vault-vectors/pv-derivation.e3.fixture.json` transcribes them
 * from `apps/web/src/user/vault/keys/keys.test.ts` (`origin/main` `970a5f1f`).
 *
 * Three kinds of test live here, and the third is no longer a placeholder:
 *
 *  1. **The implementation's own contracts** — the salt convention (asserted at
 *     the source level, so a literal cannot be slipped back in), the input
 *     shapes E0 answered, the contract literals.
 *  2. **The RFC 5869 vectors**, in `PvHkdfVectorTest` — the external oracle for
 *     the primitive itself, including the empty-salt case.
 *  3. **The platform's E3 vectors**, replayed here and no longer gated on
 *     [PV_E3_PINNED]: `K_wrap`, `K_c` from their injected CSPRNG, `wrappedKc`,
 *     the fingerprint and `accountBinding`. The complete document envelope they
 *     also pin is replayed in `PvE3EnvelopeVectorTest`; the slot wrap's own
 *     refusals stay in `PvKeySlotWrapTest`.
 *
 * The old tripwire — "PV_E3_PINNED must be false until the platform delivers" —
 * has done its job and is inverted below into the statement that is true now and
 * has to stay true: the flag is set BECAUSE the platform's fixture is present
 * and says so in writing, and the self-derived stand-in it replaced is gone.
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

    // ── the platform's fixture ──────────────────────────────────────────────

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream(E3_FIXTURE)
            ?: error("$E3_FIXTURE missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val chain: List<JsonObject> by lazy { fixture["chain"]!!.jsonArray.map { it.jsonObject } }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    // ── the tripwire, re-pointed ────────────────────────────────────────────

    @Test
    fun `E3 is pinned by the platform's bytes and the self-derived stand-in is gone`() {
        // The canary, inverted for the second time. Round one said "do not
        // derive". Round two said "derive, but do not claim the platform has
        // checked you". This is round three, and the statement it defends is:
        // the flag is true ONLY because an authored fixture is present, and the
        // self-derived file it replaced does not survive next to it. A fixture
        // that looks authoritative and is not is worse than no fixture at all —
        // it ends an investigation that should have continued — and two files
        // side by side is exactly how the wrong one gets picked up later.
        assertTrue(
            "PV_E3_PINNED is false while $E3_FIXTURE is in the tree. The flag means " +
                "the PLATFORM's E3 vectors are present and green. If they were withdrawn, " +
                "delete the fixture in the same change as clearing the flag.",
            PV_E3_PINNED,
        )
        assertNull(
            "vault-vectors/pv-derivation.selfderived.fixture.json is back. Its own marker " +
                "read 'self-derived pending platform E3 fixture - replace, never merge': it " +
                "must not sit beside the platform's authored vectors, where a later reader " +
                "cannot tell which bytes came from where.",
            javaClass.getResourceAsStream("/vault-vectors/pv-derivation.selfderived.fixture.json"),
        )
        val source = fixture["_source"]!!.jsonObject
        assertEquals(
            "the fixture must name the platform file it was transcribed from",
            "apps/web/src/user/vault/keys/keys.test.ts",
            source.str("file"),
        )
        assertTrue(
            "the fixture must carry the platform commit it was read at",
            source.str("commit").startsWith("970a5f1f"),
        )
        assertTrue(
            "the fixture must say, in writing, that these bytes are the platform's",
            source.str("provenance").contains("PLATFORM E3 conformance vectors"),
        )
        assertFalse(
            "a platform fixture must never describe itself as self-derived",
            source.str("provenance").contains("self-derived", ignoreCase = true),
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
    fun `an all-zero content key is the wiped sentinel and is refused everywhere`() {
        // The platform's own vector: `requireContentKey` rejects the buffer a
        // zeroized K_c leaves behind, so a caller that kept using a key after
        // the vault locked cannot encrypt real rows under a publicly known
        // constant. Refused before ANY CSPRNG byte is drawn — the platform
        // asserts that half too (`expect(randomBytes).not.toHaveBeenCalled()`).
        assertThrows(VaultCryptoError::class.java) { pvKeyFingerprint(ByteArray(PV_WRAP_KEY_BYTES)) }
        var draws = 0
        val counting = RandomBytes { length ->
            draws += 1
            ByteArray(length)
        }
        assertThrows(VaultCryptoError::class.java) {
            pvWrapContentKey(
                contentKey = ByteArray(PV_WRAP_KEY_BYTES),
                wrapKey = ByteArray(PV_WRAP_KEY_BYTES) { 0x5a.toByte() },
                vaultId = chain.first().str("vaultId"),
                keyId = chain.first().str("keyId"),
                randomBytes = counting,
            )
        }
        assertEquals("the IV must not be drawn before the key is validated", 0, draws)
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
        // And the parameters the fixture says it was authored under are the ones
        // this app implements — a fixture whose parameters drifted from the code
        // would keep passing while describing a different algorithm.
        val parameters = fixture["_source"]!!.jsonObject["parameters"]!!.jsonObject
        assertTrue(
            "the fixture's HKDF salt must be the empty/absent RFC 5869 default",
            parameters.str("hkdfSalt").contains("EMPTY"),
        )
        assertEquals(
            "the fixture's wrap info prefix must be the one this app derives with",
            PvVaultContract.WRAP_HKDF_INFO_PREFIX,
            parameters.str("wrapInfo").removeSuffix("\${vaultId}"),
        )
        assertEquals(
            "the fixture's fingerprint info must be the one this app derives with",
            PvVaultContract.KEY_FINGERPRINT_HKDF_INFO,
            parameters.str("fingerprintInfo"),
        )
        assertTrue(
            "the fixture must state the empty BIP-39 passphrase",
            parameters.str("bip39").contains("EMPTY passphrase"),
        )
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

    // ── the PLATFORM vectors: they run now ──────────────────────────────────

    @Test
    fun `the fixture is the platform's, and it still carries every value it pins`() {
        // Guards the fixture itself. Every assertion below reads one of these
        // keys; a fixture that lost a row would otherwise make the tests pass by
        // iterating nothing.
        assertEquals("the E3 chain lost rows", 1, chain.size)
        val row = chain.first()
        listOf("mnemonic", "seed", "vaultId", "keyId", "kWrap", "contentKey", "slotIv", "slotAad", "wrappedKc", "keyFingerprint")
            .forEach { key -> assertTrue("the fixture row lost '$key'", row.str(key).isNotEmpty()) }
    }

    @Test
    fun `the wrap key matches the platform vectors`() {
        chain.forEach { row ->
            assertEquals(
                "K_wrap for ${row.str("vaultId")}",
                row.str("kWrap"),
                hex(pvVaultWrapKey(unhex(row.str("seed")), row.str("vaultId"))),
            )
        }
    }

    @Test
    fun `the key fingerprint matches the platform vectors`() {
        chain.forEach { row ->
            val printed = pvKeyFingerprint(unhex(row.str("contentKey")))
            assertEquals("fingerprint for ${row.str("vaultId")}", row.str("keyFingerprint"), printed)
            assertEquals(PvVaultContract.KEY_FINGERPRINT_CHARS, printed.length)
        }
    }

    @Test
    fun `two vaults never derive the same wrap key from one phrase`() {
        // The platform's `domain-separates a reused phrase…` case, over ITS
        // phrase and ITS two vault ids rather than over invented ones.
        val row = chain.first()
        val sharedSeed = pvBip39Seed(row.str("mnemonic"))
        val otherVaultId = fixture["rotationSecondLeg"]!!.jsonObject.str("vaultId")
        val first = hex(pvVaultWrapKey(sharedSeed, row.str("vaultId")))
        val second = hex(pvVaultWrapKey(sharedSeed, otherVaultId))
        assertNotEquals("the fixture's two vault ids must differ", row.str("vaultId"), otherVaultId)
        assertEquals("the first leg must still be the platform's K_wrap", row.str("kWrap"), first)
        assertNotEquals(first, second)
    }

    @Test
    fun `the whole chain reproduces the platform's bytes, words to fingerprint`() {
        // mnemonic → seed → K_wrap → K_c → wrappedKc → fingerprint, in one
        // statement, through the composite that owns the CSPRNG draw order.
        val row = chain.first()
        assertEquals("the BIP-39 seed", row.str("seed"), hex(pvBip39Seed(row.str("mnemonic"))))
        val material = pvCreateVaultKeyMaterial(
            mnemonic = row.str("mnemonic"),
            vaultId = row.str("vaultId"),
            keyId = row.str("keyId"),
            randomBytes = incrementingBytes(0),
        )
        assertEquals("K_c from their injected CSPRNG", row.str("contentKey"), hex(material.contentKey))
        assertEquals("wrappedKc", row.str("wrappedKc"), material.keySlot.wrappedKc)
        assertEquals("key_fingerprint", row.str("keyFingerprint"), material.keyFingerprint)
        assertEquals(row.str("vaultId"), material.vaultId)
        assertEquals(row.str("keyId"), material.keyId)
        assertEquals(PvVaultContract.KEY_SLOT_SEED_V1, material.keySlot.slot)
        // K_c is a live secret: the material must not be printable by accident.
        assertFalse(material.toString().contains(row.str("contentKey")))
    }

    @Test
    fun `the CSPRNG draw order is K_c first, then the slot IV`() {
        // The half of the vector that is invisible in the numbers: their stub
        // hands out ONE stream, so a client that drew the IV first would produce
        // a different (equally valid) wrappedKc and the vector would quietly
        // stop proving anything. Asserted directly, on the request sizes.
        val row = chain.first()
        val draws = mutableListOf<Int>()
        val stream = incrementingBytes(0)
        pvCreateVaultKeyMaterial(
            mnemonic = row.str("mnemonic"),
            vaultId = row.str("vaultId"),
            keyId = row.str("keyId"),
            randomBytes = RandomBytes { length -> draws += length; stream(length) },
        )
        assertEquals(listOf(PV_WRAP_KEY_BYTES, 12), draws)
        assertEquals("the slot IV is the stream's bytes 0x20..0x2b", row.str("slotIv"), "202122232425262728292a2b")
    }

    @Test
    fun `the account binding is the platform's digest of the platform's account id`() {
        val binding = fixture["accountBinding"]!!.jsonObject
        assertEquals(binding.str("binding"), pvAccountBinding(binding.str("accountId")))
        assertEquals(43, binding.str("binding").length)
    }

    @Test
    fun `the rotation second leg derives a different key from a different phrase`() {
        // The platform names this phrase and pins no bytes for it, so nothing is
        // pinned here either — only the property that makes rotation mean
        // something: new words plus a new vault id must not land on the old key.
        val leg = fixture["rotationSecondLeg"]!!.jsonObject
        val row = chain.first()
        val second = hex(pvVaultWrapKey(pvBip39Seed(leg.str("mnemonic")), leg.str("vaultId")))
        assertNotEquals(row.str("kWrap"), second)
        assertEquals(PV_WRAP_KEY_BYTES * 2, second.length)
    }

    private companion object {
        const val E3_FIXTURE = "/vault-vectors/pv-derivation.e3.fixture.json"

        /**
         * The platform's `incrementingRandom(start)`: one stream of 0x00, 0x01,
         * 0x02 … shared by every draw, which is what makes their K_c
         * `0x00..0x1f` and their slot IV `0x20..0x2b`.
         */
        fun incrementingBytes(start: Int): RandomBytes {
            var next = start
            return RandomBytes { length -> ByteArray(length) { (next++ and 0xff).toByte() } }
        }
    }
}
