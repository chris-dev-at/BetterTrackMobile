package at.bettertrack.app.vault.pv.envelope

import at.bettertrack.app.vault.RawDeflate
import at.bettertrack.app.vault.VAULT_JSON
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.aesGcmEncrypt
import at.bettertrack.app.vault.jsJsonStringify
import at.bettertrack.app.vault.pv.keys.pvBip39Seed
import at.bettertrack.app.vault.pv.keys.pvKeyFingerprint
import at.bettertrack.app.vault.pv.keys.pvUnwrapContentKey
import at.bettertrack.app.vault.pv.keys.pvVaultWrapKey
import at.bettertrack.app.vault.utf8
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The platform's complete E3 document envelope, replayed byte for byte.**
 *
 * `vault-vectors/pv-derivation.e3.fixture.json` carries more than the key chain:
 * the platform's E3 test also pins one WHOLE `BTVAULT1` v2 envelope — the header
 * document of the vector vault, 921 bytes, written under `K_c = 0x00..0x1f` with
 * the document IV `0x2c..0x37` that falls out of the same injected CSPRNG
 * stream. Reproducing it exercises every layer at once and is the only test in
 * this app that proves all of them agree with the web client simultaneously:
 *
 *  - `PvHeaderDoc`'s payload member order and `jsJsonStringify`;
 *  - [RawDeflate] — the fflate 0.8.3 port. `java.util.zip` would emit valid but
 *    DIFFERENT DEFLATE, and the ciphertext would diverge here and nowhere else;
 *  - the header member order and `serializePvDocHeader`;
 *  - AES-256-GCM with the exact wire header bytes as AAD;
 *  - the `BTVAULT1 ‖ uint32BE(len) ‖ header ‖ ct‖tag` framing.
 *
 * ## The property the platform singled out
 *
 * `a key-shuffled header must serialize to byte-identical wire AAD` is, in their
 * words, "the property most likely to drift silently between implementations".
 * It drifts silently because nothing fails at write time: a client whose header
 * came out in a different member order writes a perfectly well-formed envelope
 * that only the OTHER client cannot open, and the user meets it as "my vault
 * won't load on my phone" long after the write. It is pinned twice below —
 * once against the platform's own wire bytes, and once in the direction that
 * matters for reads: the AAD is whatever arrived, never a re-serialization.
 */
class PvE3EnvelopeVectorTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream(E3_FIXTURE) ?: error("$E3_FIXTURE missing")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.num(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()

    private val row: JsonObject by lazy { fixture["chain"]!!.jsonArray.first().jsonObject }
    private val envelopeSpec: JsonObject by lazy { fixture["envelope"]!!.jsonObject }

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val contentKey: ByteArray get() = unhex(row.str("contentKey"))
    private val keySlot: PvKeySlot
        get() = PvKeySlot(row.str("keyId"), PvVaultContract.KEY_SLOT_SEED_V1, row.str("wrappedKc"))

    private val headerJson: String get() = envelopeSpec.str("headerJson")
    private val documentJson: String get() = envelopeSpec.str("documentJson")
    private val expectedEnvelope: String get() = envelopeSpec.str("envelope")

    private fun envelopeBytes(): ByteArray = pvBase64UrlDecode(expectedEnvelope, "the E3 envelope")

    private fun write(): PvDocWrite = PvDocWrite(
        vaultId = row.str("vaultId"),
        docId = envelopeSpec.str("docId"),
        accountBinding = fixture["accountBinding"]!!.jsonObject.str("binding"),
        keyId = row.str("keyId"),
        keySlots = listOf(keySlot),
        docVersion = envelopeSpec.num("docVersion"),
        deviceId = envelopeSpec.str("deviceId"),
        writeId = envelopeSpec.str("writeId"),
        writtenAt = envelopeSpec.str("writtenAt"),
    )

    private fun headerTree(): JsonObject = VAULT_JSON.parseToJsonElement(headerJson).jsonObject

    // ── the write side ──────────────────────────────────────────────────────

    @Test
    fun `their header document parses, and re-serializes to their exact plaintext`() {
        // Before any crypto: the payload this app would compress must be the
        // payload they compressed, to the byte. A member-order difference here
        // is invisible until the tag fails on the other client.
        val document = PvHeaderDoc.parse(VAULT_JSON.parseToJsonElement(documentJson))
        assertEquals(documentJson, jsJsonStringify(document.toJson()))
        assertEquals("TEST VECTOR vault", document.name)
        assertEquals(listOf(keySlot), document.keySlots)
        assertNull(document.driveConnection)
    }

    @Test
    fun `encrypting their document under their key reproduces their envelope exactly`() {
        val document = PvHeaderDoc.parse(VAULT_JSON.parseToJsonElement(documentJson))
        val sealed = encryptPvDoc(
            document = document,
            contentKey = contentKey,
            write = write(),
            iv = unhex(envelopeSpec.str("ivHex")),
        )
        assertEquals(
            "the serialized wire header must be the platform's, character for character",
            headerJson,
            String(serializePvDocHeader(sealed.header), Charsets.UTF_8),
        )
        assertEquals(
            "the whole BTVAULT1 envelope must be the platform's, byte for byte",
            expectedEnvelope,
            pvBase64UrlEncode(sealed.envelope),
        )
        // And the same statement in raw bytes, so a base64url bug cannot make
        // two different byte strings compare equal.
        assertEquals(envelopeBytes().toList(), sealed.envelope.toList())
    }

    @Test
    fun `their compressed plaintext is fflate's, not java util zip's`() {
        // Isolates the compressor from the crypto: if this fails and the
        // envelope test above also fails, the cause is RawDeflate and not AES.
        val framing = decodePvEnvelopeFraming(envelopeBytes())
        assertEquals(headerJson, String(framing.headerBytes, Charsets.UTF_8))
        val compressed = RawDeflate.deflate(utf8(documentJson))
        assertEquals(
            "their ciphertext is the deflate stream plus a 16-byte tag",
            compressed.size + 16,
            framing.ciphertext.size,
        )
        val ciphertext = aesGcmEncrypt(
            contentKey,
            unhex(envelopeSpec.str("ivHex")),
            compressed,
            framing.headerBytes,
        )
        assertEquals(framing.ciphertext.toList(), ciphertext.toList())
    }

    // ── the read side, from the words ───────────────────────────────────────

    @Test
    fun `their phrase opens their envelope, words to document`() {
        // The whole product statement in one test: a user types the twelve words
        // the web client used, and this app reads the document the web wrote.
        val wrapKey = pvVaultWrapKey(pvBip39Seed(row.str("mnemonic")), row.str("vaultId"))
        val kc = pvUnwrapContentKey(keySlot, wrapKey, row.str("vaultId"))
        assertEquals(row.str("contentKey"), kc.joinToString("") { "%02x".format(it) })
        assertEquals(row.str("keyFingerprint"), pvKeyFingerprint(kc))

        val opened = decryptPvDoc(envelopeBytes(), kc)
        assertEquals(documentJson, jsJsonStringify(opened.document.toJson()))
        assertEquals(PvVaultContract.KIND_HEADER, opened.header.docKind)
        assertEquals(row.str("vaultId"), opened.header.vaultId)
        assertEquals(envelopeSpec.str("docId"), opened.header.docId)
        assertEquals(envelopeSpec.str("writtenAt"), opened.header.writtenAt)
        assertEquals(fixture["accountBinding"]!!.jsonObject.str("binding"), opened.header.accountBinding)
    }

    // ── the canonicalization property, pinned against their bytes ───────────

    @Test
    fun `a fully key-shuffled header canonicalizes to the platform's exact wire bytes`() {
        // The named drift risk. Not "some canonical form" — THEIR canonical
        // form, reached from a header whose members arrive in the wrong order
        // at every level, including inside each key slot.
        val original = headerTree()
        val shuffled = reverseKeys(
            JsonObject(
                original.toMutableMap().apply {
                    this["keySlots"] = JsonArray(original["keySlots"]!!.jsonArray.map { reverseKeys(it.jsonObject) })
                },
            ),
        )
        assertNotEquals("the shuffle must actually reorder", original.keys.toList(), shuffled.keys.toList())
        assertEquals(
            "a shuffled header must serialize to the platform's wire AAD",
            headerJson,
            String(pvCanonicalHeaderBytes(shuffled), Charsets.UTF_8),
        )
        // …and the same through the typed path, so both entry points agree.
        assertEquals(
            headerJson,
            String(serializePvDocHeader(PvDocEnvelopeHeader.parse(shuffled)), Charsets.UTF_8),
        )
    }

    @Test
    fun `the AAD is the exact wire header bytes, never a re-serialization`() {
        // The platform's `authenticates exact noncanonical wire header bytes
        // instead of reserializing`. A conforming producer may emit its members
        // in another order; a reader that re-serialized before authenticating
        // would reject that producer's perfectly valid write. The bytes on the
        // wire are the contract, not our idea of them.
        val noncanonical = utf8(jsJsonStringify(reverseKeys(headerTree())))
        assertNotEquals(
            "the reversed header must NOT be the canonical serialization",
            String(serializePvDocHeader(PvDocEnvelopeHeader.parse(headerTree())), Charsets.UTF_8),
            String(noncanonical, Charsets.UTF_8),
        )
        val plaintext = utf8(documentJson)
        val ciphertext = aesGcmEncrypt(
            contentKey,
            unhex(envelopeSpec.str("ivHex")),
            RawDeflate.deflate(plaintext),
            noncanonical,
        )
        val envelope = framePvEnvelopeBytes(noncanonical, ciphertext)
        val opened = decryptPvDoc(envelope, contentKey)
        assertEquals(documentJson, jsJsonStringify(opened.document.toJson()))
    }

    // ── fail-closed, over their envelope ────────────────────────────────────

    @Test
    fun `every binding tamper and the format rollback fail closed on their envelope`() {
        val framing = decodePvEnvelopeFraming(envelopeBytes())
        val tampers = fixture["tampers"]!!.jsonArray.map { it.jsonObject }
        assertEquals("the platform's tamper set lost a case", 6, tampers.size)
        tampers.forEach { tamper ->
            val field = tamper.str("field")
            val raw = tamper["value"]!!.jsonPrimitive
            val replacement: JsonElement =
                if (raw.isString) JsonPrimitive(raw.content) else JsonPrimitive(raw.content.toInt())
            val mutated = JsonObject(headerTree().toMutableMap().apply { this[field] = replacement })
            assertNotEquals("the tamper on '$field' changed nothing", headerTree()[field], replacement)
            val envelope = framePvEnvelopeBytes(utf8(jsJsonStringify(mutated)), framing.ciphertext)
            val error = assertThrows(
                "tampering with '$field' must fail closed",
                VaultCryptoError::class.java,
            ) { decryptPvDoc(envelope, contentKey) }
            assertTrue(
                "'$field' failed with ${error.code}, which is neither a refusal to " +
                    "authenticate nor a refusal to parse",
                error.code == VaultCryptoErrorCode.AUTHENTICATION_FAILED ||
                    error.code == VaultCryptoErrorCode.ENVELOPE_INVALID,
            )
        }
    }

    @Test
    fun `their envelope does not open under another account's binding`() {
        // The §8 anti-swap guarantee stated as the thing a user would notice:
        // a document lifted into another account's storage is unreadable there,
        // and the refusal comes from the GCM tag rather than from a check.
        val framing = decodePvEnvelopeFraming(envelopeBytes())
        val other = pvAccountBinding(fixture.str("otherAccountId"))
        assertNotEquals(fixture["accountBinding"]!!.jsonObject.str("binding"), other)
        val mutated = JsonObject(
            headerTree().toMutableMap().apply { this["accountBinding"] = JsonPrimitive(other) },
        )
        val envelope = framePvEnvelopeBytes(utf8(jsJsonStringify(mutated)), framing.ciphertext)
        val error = assertThrows(VaultCryptoError::class.java) { decryptPvDoc(envelope, contentKey) }
        assertEquals(VaultCryptoErrorCode.AUTHENTICATION_FAILED, error.code)
    }

    private fun reverseKeys(value: JsonObject): JsonObject =
        JsonObject(value.entries.reversed().associateTo(LinkedHashMap()) { it.key to it.value })

    private companion object {
        const val E3_FIXTURE = "/vault-vectors/pv-derivation.e3.fixture.json"
    }
}
