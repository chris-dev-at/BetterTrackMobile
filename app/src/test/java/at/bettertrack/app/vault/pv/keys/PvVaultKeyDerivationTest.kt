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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **E3 derivation: what is pinned, what is blocked, and what will run the day
 * the salt is ruled.**
 *
 * The §4 chain above the BIP-39 seed cannot be finished yet. Three of its
 * parameters came back with the deployed E0 contract; the HKDF **salt** did not,
 * and it is on the mobile board as ask #83 Q4. `PvVaultKeyDerivation.kt`
 * therefore ships shapes and a single blocking point rather than a guess.
 *
 * This file has two halves and they are deliberately different in kind:
 *
 *  1. **Tests that run today** and assert the blocked state itself — that the
 *     stop line is where it is supposed to be, that it names the ask, and that
 *     every input contract E0 *did* answer is enforced before it. These pass
 *     now, and they are what would fail if someone quietly slipped a salt in.
 *  2. **The vector scaffolding**, which `assumeTrue`s on [PV_E3_PINNED] and on
 *     the presence of `vault-vectors/pv-derivation.fixture.json`. Those show up
 *     as SKIPPED in the report, with the board ask in the reason string, until
 *     the platform ships both — at which point they run without anyone
 *     remembering to come back and write them.
 *
 * The skip is not silence: `E3 is still blocked on the board ask` below runs on
 * every build and fails the moment the flag is flipped without the work.
 */
class PvVaultKeyDerivationTest {

    /** A syntactically real vault id; nothing about it is secret. */
    private val vaultId = "6b1f2f4c-2b2a-4a7f-9f3a-0d7c1f5a9e21"

    private fun seed(fill: Byte = 0x11): ByteArray = ByteArray(PV_BIP39_SEED_BYTES) { fill }

    private fun contentKey(fill: Byte = 0x22): ByteArray = ByteArray(PV_WRAP_KEY_BYTES) { fill }

    // ── half one: the blocked state, asserted out loud ──────────────────────

    @Test
    fun `E3 is still blocked on the board ask`() {
        // The canary. When the platform answers, this test is the one that says
        // "the flag moved" — flip it here together with pvDerivationSalt(), and
        // the scaffolding below stops skipping.
        assertFalse(
            "PV_E3_PINNED is set. Either the §4 HKDF salt was ruled — in which " +
                "case write it into pvDerivationSalt(), drop the fixture in as " +
                "vault-vectors/pv-derivation.fixture.json and update this test — " +
                "or it was flipped by accident, which would derive keys the web " +
                "client cannot read.",
            PV_E3_PINNED,
        )
    }

    @Test
    fun `the blocked derivations name the ask instead of saying not implemented`() {
        val fromSalt = assertThrows(NotImplementedError::class.java) { pvDerivationSalt() }
        val fromWrap = assertThrows(NotImplementedError::class.java) { pvVaultWrapKey(seed(), vaultId) }
        val fromPrint = assertThrows(NotImplementedError::class.java) { pvKeyFingerprint(contentKey()) }
        listOf(fromSalt, fromWrap, fromPrint).forEach { error ->
            val message = error.message.orEmpty()
            assertTrue("the block does not name the board ask: $message", message.contains("#83 Q4"))
            assertTrue("the block does not name the salt: $message", message.contains("salt"))
        }
        assertEquals(PV_E3_BOARD_ASK, fromWrap.message)
    }

    @Test
    fun `every derivation funnels through the one blocking point`() {
        // If a second `throw NotImplementedError` appeared inline in either
        // function, this file would still be green while the salt lived in two
        // places. The source check is what makes "one place to edit" true.
        val relative = "src/main/java/at/bettertrack/app/vault/pv/keys/PvVaultKeyDerivation.kt"
        val source = listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
        assertEquals(
            "exactly one function may throw the E3 block",
            1,
            Regex("""throw NotImplementedError""").findAll(source).count(),
        )
        assertEquals(
            "every derivation must take its salt from pvDerivationSalt()",
            2,
            Regex("""salt = pvDerivationSalt\(\)""").findAll(source).count(),
        )
    }

    // ── the answers E0 DID give, enforced before the stop line ──────────────

    @Test
    fun `the wrap key derives from the sixty-four byte seed, not the words or the bits`() {
        // E0 answered the IKM. A caller passing the 16-byte entropy — the thing
        // the keystore holds, and the easiest wrong argument to reach for — is
        // refused by shape before anything derives.
        assertThrows(VaultCryptoError::class.java) { pvVaultWrapKey(ByteArray(16), vaultId) }
        assertThrows(VaultCryptoError::class.java) { pvVaultWrapKey(ByteArray(32), vaultId) }
        assertThrows(VaultCryptoError::class.java) { pvVaultWrapKey(ByteArray(0), vaultId) }
        // …and a correctly-shaped seed gets past the shape check to the block,
        // which is how we know the refusals above were about the shape.
        assertThrows(NotImplementedError::class.java) { pvVaultWrapKey(seed(), vaultId) }
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
        assertThrows(NotImplementedError::class.java) { pvKeyFingerprint(contentKey()) }
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

    // ── half two: the scaffolding, skipped with its reason until E3 ships ───

    private val skipReason =
        "E3 derivation vectors are not consumable yet: the §4 HKDF salt is " +
            "unanswered (mobile board ask #83 Q4) and " +
            "vault-vectors/pv-derivation.fixture.json has not shipped. " +
            "Flip PV_E3_PINNED and drop the fixture in to run this."

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

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

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
