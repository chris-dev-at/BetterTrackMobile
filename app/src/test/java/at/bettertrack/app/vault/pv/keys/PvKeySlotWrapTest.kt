package at.bettertrack.app.vault.pv.keys

import at.bettertrack.app.vault.VAULT_IV_BYTES
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.pv.envelope.PvKeySlot
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.pvBase64UrlDecode
import at.bettertrack.app.vault.pv.envelope.pvBase64UrlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The `keySlots[]` wrap: the layout, the AAD, and every way it must fail.**
 *
 * `wrappedKc = base64url(IV ‖ CT ‖ TAG)`, AES-256-GCM under `K_wrap`, with
 * `aad = "bettertrack-vault-key-slot-v1:" + vaultId + ":" + keyId` — the
 * platform's answer of 2026-08-20.
 *
 * The round trip is the least interesting test in this file. The ones that
 * matter are the refusals: a slot is a small blob that an attacker with write
 * access to a header can move, duplicate or roll back, and every one of those
 * moves has to end as an authentication failure rather than as a key.
 *
 * The pinned bytes come from `pv-derivation.selfderived.fixture.json`, whose
 * `wrappedKc` values were produced by an independent WebCrypto implementation
 * with a fixed IV. They are NOT the platform's numbers — see [PV_E3_PINNED].
 */
class PvKeySlotWrapTest {

    private val vaultId = "018f6a3e-1111-7000-8000-00000000000a"
    private val otherVaultId = "018f6a3e-1111-7000-8000-00000000000b"
    private val keyId = "018f6a3e-3333-7000-8000-00000000000a"
    private val otherKeyId = "018f6a3e-3333-7000-8000-00000000000b"

    private val contentKey = ByteArray(PV_WRAP_KEY_BYTES) { (it + 1).toByte() }
    private val wrapKey = ByteArray(PV_WRAP_KEY_BYTES) { (0xa0 + it).toByte() }
    private val otherWrapKey = ByteArray(PV_WRAP_KEY_BYTES) { (0x50 + it).toByte() }
    private val iv = ByteArray(VAULT_IV_BYTES) { it.toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun slot(): PvKeySlot = pvWrapContentKey(contentKey, wrapKey, vaultId, keyId, iv = iv)

    private fun codeOf(block: () -> Unit): VaultCryptoErrorCode =
        assertThrows(VaultCryptoError::class.java) { block() }.code

    // ── the shape the contract will store ───────────────────────────────────

    @Test
    fun `a wrapped slot is a contract-valid key slot`() {
        val produced = slot()
        assertEquals(keyId, produced.keyId)
        assertEquals(PvVaultContract.KEY_SLOT_SEED_V1, produced.slot)
        // The E0 slot schema is the thing that will actually receive this, so
        // the strongest statement available is that its own parser accepts it.
        assertEquals(produced, PvKeySlot.parse(produced.toJson()))
    }

    @Test
    fun `the layout is IV then ciphertext then tag, base64url`() {
        val payload = pvBase64UrlDecode(slot().wrappedKc, "wrappedKc")
        assertEquals(
            "12-byte IV + 32-byte K_c + 16-byte GCM tag",
            VAULT_IV_BYTES + PV_WRAP_KEY_BYTES + 16,
            payload.size,
        )
        assertArrayEquals(
            "the IV must lead, in the clear",
            iv,
            payload.copyOfRange(0, VAULT_IV_BYTES),
        )
        assertTrue(
            "wrappedKc must be unpadded base64url",
            Regex("^[A-Za-z0-9_-]+$").matches(slot().wrappedKc),
        )
    }

    @Test
    fun `the round trip returns the content key and nothing near it`() {
        val opened = pvUnwrapContentKey(slot(), wrapKey, vaultId)
        assertArrayEquals(contentKey, opened)
        assertEquals(PV_WRAP_KEY_BYTES, opened.size)
    }

    @Test
    fun `production wraps draw a fresh nonce every time`() {
        // Two wraps of the SAME key under the SAME K_wrap must not produce the
        // same bytes. Reusing an IV across two different plaintexts under one
        // key breaks GCM outright, and equal ciphertexts are how that starts.
        val first = pvWrapContentKey(contentKey, wrapKey, vaultId, keyId)
        val second = pvWrapContentKey(contentKey, wrapKey, vaultId, keyId)
        assertNotEquals(first.wrappedKc, second.wrappedKc)
        assertArrayEquals(contentKey, pvUnwrapContentKey(first, wrapKey, vaultId))
        assertArrayEquals(contentKey, pvUnwrapContentKey(second, wrapKey, vaultId))
    }

    // ── the refusals, which are the point ───────────────────────────────────

    @Test
    fun `a slot lifted into another vault does not open`() {
        // §8 anti-swap, one level below the doc envelope. Without the vaultId in
        // the AAD, the holder of vault A's phrase could read a slot pasted into
        // vault B's header.
        assertEquals(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            codeOf { pvUnwrapContentKey(slot(), wrapKey, otherVaultId) },
        )
    }

    @Test
    fun `a slot re-labelled with another key id does not open`() {
        // The rotation rollback: swap the retired slot's bytes under the new
        // slot's id and a client that only authenticated the vault would accept
        // the old K_c back.
        val relabelled = slot().copy(keyId = otherKeyId)
        assertEquals(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            codeOf { pvUnwrapContentKey(relabelled, wrapKey, vaultId) },
        )
    }

    @Test
    fun `the wrong phrase derives the wrong wrap key and the slot stays shut`() {
        assertEquals(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            codeOf { pvUnwrapContentKey(slot(), otherWrapKey, vaultId) },
        )
    }

    @Test
    fun `one flipped ciphertext bit fails closed`() {
        val payload = pvBase64UrlDecode(slot().wrappedKc, "wrappedKc")
        payload[VAULT_IV_BYTES] = (payload[VAULT_IV_BYTES].toInt() xor 0x01).toByte()
        val tampered = slot().copy(wrappedKc = pvBase64UrlEncode(payload))
        assertEquals(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            codeOf { pvUnwrapContentKey(tampered, wrapKey, vaultId) },
        )
    }

    @Test
    fun `a moved IV fails closed too`() {
        // The IV is in the clear, which invites the assumption that it is free
        // to edit. It is not: GCM's tag covers it.
        val payload = pvBase64UrlDecode(slot().wrappedKc, "wrappedKc")
        payload[0] = (payload[0].toInt() xor 0xff).toByte()
        val tampered = slot().copy(wrappedKc = pvBase64UrlEncode(payload))
        assertEquals(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            codeOf { pvUnwrapContentKey(tampered, wrapKey, vaultId) },
        )
    }

    @Test
    fun `a short or long payload is structurally refused before any crypto`() {
        val payload = pvBase64UrlDecode(slot().wrappedKc, "wrappedKc")
        val short = slot().copy(wrappedKc = pvBase64UrlEncode(payload.copyOfRange(0, payload.size - 1)))
        val long = slot().copy(wrappedKc = pvBase64UrlEncode(payload + 0x00))
        assertEquals(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            codeOf { pvUnwrapContentKey(short, wrapKey, vaultId) },
        )
        assertEquals(
            VaultCryptoErrorCode.AUTHENTICATION_FAILED,
            codeOf { pvUnwrapContentKey(long, wrapKey, vaultId) },
        )
    }

    @Test
    fun `non-canonical base64url keeps its own honest code`() {
        // Not a secret-dependent failure, so it does not pretend to be one —
        // the same split the v1 rail's unwrapVaultKey makes.
        val bad = slot().copy(wrappedKc = "not base64url!!")
        assertEquals(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            codeOf { pvUnwrapContentKey(bad, wrapKey, vaultId) },
        )
    }

    @Test
    fun `a slot kind this build does not know asks for an update, not a password`() {
        // A newer format is not a wrong phrase, and telling the user it is would
        // send them looking for a piece of paper that is perfectly fine.
        val future = slot().copy(slot = "seed-v2")
        assertEquals(
            VaultCryptoErrorCode.UPDATE_REQUIRED,
            codeOf { pvUnwrapContentKey(future, wrapKey, vaultId) },
        )
    }

    @Test
    fun `both ids must be uuids, because the E0 slot schema types them so`() {
        assertEquals(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            codeOf { pvWrapContentKey(contentKey, wrapKey, "not-a-uuid", keyId, iv = iv) },
        )
        assertEquals(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            codeOf { pvWrapContentKey(contentKey, wrapKey, vaultId, "slot-1", iv = iv) },
        )
    }

    @Test
    fun `both keys must be AES-256 and the IV must be 96 bits`() {
        assertEquals(
            VaultCryptoErrorCode.KDF_FAILED,
            codeOf { pvWrapContentKey(ByteArray(16), wrapKey, vaultId, keyId, iv = iv) },
        )
        assertEquals(
            VaultCryptoErrorCode.KDF_FAILED,
            codeOf { pvWrapContentKey(contentKey, ByteArray(16), vaultId, keyId, iv = iv) },
        )
        assertEquals(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            codeOf { pvWrapContentKey(contentKey, wrapKey, vaultId, keyId, iv = ByteArray(16)) },
        )
    }

    // ── the AAD, spelled out ────────────────────────────────────────────────

    @Test
    fun `the AAD is the ruled string and carries both ids`() {
        assertEquals("bettertrack-vault-key-slot-v1:", PV_KEY_SLOT_AAD_PREFIX)
        assertEquals(
            "bettertrack-vault-key-slot-v1:$vaultId:$keyId",
            String(pvKeySlotAad(vaultId, keyId), Charsets.UTF_8),
        )
    }

    // ── the cross-checked bytes ─────────────────────────────────────────────

    private val selfChain: List<JsonObject> by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/pv-derivation.selfderived.fixture.json")
            ?: error("vault-vectors/pv-derivation.selfderived.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() })
            .jsonObject["chain"]!!
            .jsonArray
            .map { it.jsonObject }
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun `every fixture row wraps to its cross-checked bytes and unwraps back`() {
        selfChain.forEach { row ->
            val wrapKeyBytes = unhex(row.str("kWrap"))
            val kc = unhex(row.str("contentKey"))
            val produced = pvWrapContentKey(
                contentKey = kc,
                wrapKey = wrapKeyBytes,
                vaultId = row.str("vaultId"),
                keyId = row.str("keyId"),
                iv = unhex(row.str("slotIv")),
            )
            assertEquals("wrappedKc for ${row.str("vaultId")}", row.str("wrappedKc"), produced.wrappedKc)
            assertEquals(
                "the fixture's own AAD",
                row.str("slotAad"),
                String(pvKeySlotAad(row.str("vaultId"), row.str("keyId")), Charsets.UTF_8),
            )
            assertEquals(
                hex(kc),
                hex(pvUnwrapContentKey(produced, wrapKeyBytes, row.str("vaultId"))),
            )
        }
        assertEquals(3, selfChain.size)
    }

    @Test
    fun `the fixture's phrase opens the fixture's slot end to end`() {
        // The whole §4 chain in one statement: words in, K_c out.
        val row = selfChain.first()
        val seed = pvBip39Seed(row.str("mnemonic"))
        val derivedWrapKey = pvVaultWrapKey(seed, row.str("vaultId"))
        val stored = PvKeySlot(
            keyId = row.str("keyId"),
            slot = PvVaultContract.KEY_SLOT_SEED_V1,
            wrappedKc = row.str("wrappedKc"),
        )
        val kc = pvUnwrapContentKey(stored, derivedWrapKey, row.str("vaultId"))
        assertEquals(row.str("contentKey"), hex(kc))
        assertEquals(row.str("keyFingerprint"), pvKeyFingerprint(kc))
    }
}
