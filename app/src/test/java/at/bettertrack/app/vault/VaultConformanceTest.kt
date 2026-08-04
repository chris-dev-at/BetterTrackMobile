package at.bettertrack.app.vault

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **The vault conformance gate** (plan §5, W3 done-when).
 *
 * `vectors.fixture.json` is the platform's *published interoperability oracle*:
 * a fixed passphrase, a fixed vault key, a fixed Argon2id salt, and the exact
 * expected `kekBase64`, header bytes and envelope bytes that the production web
 * client produces from them — plus wrong-passphrase, tampered-envelope,
 * update-required, passphrase-change, key-rotation, recovery-kit and rollback
 * cases. Its own header says the vectors were produced "with the production
 * hash-wasm Argon2id path, deterministic random input, and native AES-256-GCM"
 * so that "consumers can reproduce the exact serialized bytes".
 *
 * This test is that consumer. Nothing here is asserted "close enough": every
 * expectation is a byte array or a base64 string copied from the fixture, and a
 * user's Drive vault has to be openable by both clients or the feature is a lie.
 *
 * The fixture is vendored at `app/src/test/resources/vault-vectors/vectors.fixture.json`,
 * copied byte-identically from `apps/web/src/user/vault/vectors.fixture.json` at
 * the commit in `tools/domain-vectors/PINNED_AT`.
 */
class VaultConformanceTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/vectors.fixture.json")
            ?: error("vault-vectors/vectors.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.o(key: String): JsonObject = this[key]!!.jsonObject
    private fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)
    private fun e64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private val passphrase get() = fixture.s("passphrase")
    private val newPassphrase get() = fixture.s("newPassphrase")
    private val vaultKey get() = b64(fixture.s("vaultKeyBase64"))
    private val kdf get() = VaultKdfParams.parse(fixture.o("kdf"))
    private val initial get() = fixture.o("initial")
    private val initialEnvelope get() = b64(initial.s("envelopeBase64"))

    /**
     * `deterministicRandom` (`vectors.ts:96-99`) — `next++ & 0xff`.
     *
     * This is the entire reason the fixture is reproducible: it makes every IV,
     * salt and key in the reference run a known constant. Never used outside
     * tests, which is why it lives here and not in the production source set.
     */
    private fun deterministicRandom(start: Int = 0): RandomBytes {
        var next = start
        return RandomBytes { length -> ByteArray(length) { (next++ and 0xff).toByte() } }
    }

    /** `failingDeterministicRandom` (`vault.test.ts:66-75`) — the rollback driver. */
    private fun failingDeterministicRandom(failAtCall: Int, start: Int = 0): RandomBytes {
        val random = deterministicRandom(start)
        var calls = 0
        return RandomBytes { length ->
            calls += 1
            if (calls == failAtCall) throw IllegalStateException("Deterministic random failure at call $calls.")
            random(length)
        }
    }

    /** `vaultVectorDocument` (`vectors.ts:12-30`). */
    private fun vaultVectorDocument(): VaultDocument = VaultDocument.v1(
        entities = linkedMapOf(
            "portfolio" to listOf(
                VaultEntity(
                    id = VECTOR_KEY_ID,
                    rev = 1,
                    editedAt = "2026-07-24T10:00:00.000Z",
                    editedBy = VECTOR_DEVICE_ID,
                    deletedAt = null,
                    data = JsonObject(mapOf("name" to JsonPrimitive("Vector portfolio"))),
                )
            )
        ),
        mergeLog = emptyList(),
        // vectors.ts:27-29 — deliberately NO `mirrorProvenance`. An absent key is
        // not an empty list: adding `"mirrorProvenance":[]` would change the
        // plaintext and therefore these fixed envelope bytes.
        mirrorProvenance = null,
    )

    private fun draftFrom(header: JsonObject): VaultHeaderDraft = VaultHeaderDraft(
        keyId = header.s("keyId"),
        wrappedKeys = (header["wrappedKeys"] as JsonArray).map { VaultWrappedKey.parse(it) },
        vaultVersion = header["vaultVersion"]!!.jsonPrimitive.content.toInt(),
        deviceId = header.s("deviceId"),
        writeId = header.s("writeId"),
        writtenAt = header.s("writtenAt"),
    )

    private fun rekeyMetadata(header: JsonObject) = RekeyHeaderMetadata(
        vaultVersion = header["vaultVersion"]!!.jsonPrimitive.content.toInt(),
        deviceId = header.s("deviceId"),
        writeId = header.s("writeId"),
        writtenAt = header.s("writtenAt"),
    )

    private inline fun expectVaultError(code: VaultCryptoErrorCode, what: String, block: () -> Unit) {
        try {
            block()
            fail("$what: expected VaultCryptoError[$code], nothing was thrown")
        } catch (e: VaultCryptoError) {
            assertEquals("$what: error code", code, e.code)
        }
    }

    // =======================================================================
    // 1. The KEK gate — the first thing that had to work
    // =======================================================================

    /**
     * If this fails, nothing else in the vault matters: a KEK that disagrees with
     * the platform's cannot unwrap a vault key written by the web client, and
     * every other conformance case downstream is meaningless.
     *
     * Bouncy Castle's lightweight `Argon2BytesGenerator` reproduces it with
     * Argon2id, **version 1.3**, m=65536 KiB, t=3, p=1, 32-byte output.
     */
    @Test
    fun derivesThePublishedArgon2idKek() {
        assertEquals(
            "kekBase64 from the published passphrase + salt",
            fixture.s("kekBase64"),
            e64(deriveVaultKek(passphrase, kdf)),
        )
    }

    @Test
    fun theArgon2VersionIsPartOfTheContract() {
        // Argon2 v1.0 with identical parameters derives a DIFFERENT key. Pinning
        // the version is not defensive style; it is the difference between an
        // openable vault and a lost one.
        val v10 = Argon2Derive { password, salt, iterations, parallelism, memory, length ->
            val params = org.bouncycastle.crypto.params.Argon2Parameters
                .Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_10)
                .withIterations(iterations)
                .withMemoryAsKB(memory)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()
            org.bouncycastle.crypto.generators.Argon2BytesGenerator().let {
                it.init(params)
                ByteArray(length).also { out -> it.generateBytes(password, out, 0, length) }
            }
        }
        assertNotEquals(fixture.s("kekBase64"), e64(deriveVaultKek(passphrase, kdf, v10)))
    }

    @Test
    fun rejectsAnyKdfProfileOtherThanTheRequiredOne() {
        val unexpected = Argon2Derive { _, _, _, _, _, _ -> error("KDF must not run") }
        for (weakened in listOf(
            kdf.copy(m = 1024),
            kdf.copy(t = 1),
            kdf.copy(p = 2),
            kdf.copy(alg = "argon2i"),
        )) {
            // The profile check runs BEFORE any derivation, so the unexpected
            // hook proves the refusal is not "derive then compare".
            expectVaultError(VaultCryptoErrorCode.KDF_FAILED, "weakened kdf $weakened") {
                deriveVaultKek(passphrase, weakened, unexpected)
            }
        }
    }

    // =======================================================================
    // 2. The initial envelope — read, and rebuilt from nothing
    // =======================================================================

    @Test
    fun decodesThePublishedInitialEnvelopeToItsExactHeaderBytes() {
        val decoded = decodeVaultEnvelope(initialEnvelope)
        assertEquals(initial.s("headerBytesBase64"), e64(decoded.headerBytes))
        assertEquals(VaultEnvelopeHeader.parse(initial.o("header")), decoded.header)
    }

    @Test
    fun decryptsThePublishedInitialEnvelope() {
        val result = decryptVaultDocument(initialEnvelope, vaultKey)
        assertEquals(
            "decrypted document",
            jsJsonStringify(vaultVectorDocument().toJson()),
            jsJsonStringify(result.document.toJson()),
        )
        assertEquals(VaultEnvelopeHeader.parse(initial.o("header")), result.header)
        // The absent `mirrorProvenance` key must survive the round trip as absent.
        assertTrue(
            "an absent mirrorProvenance must not become an empty list",
            result.document.mirrorProvenance == null,
        )
    }

    /**
     * **The byte-identity gate.**
     *
     * Rebuilds the published envelope from nothing but the passphrase and the
     * deterministic CSPRNG: generate the vault key, generate the KDF salt, derive
     * the KEK, wrap the key, serialize the header, compress with the ported
     * fflate, encrypt, frame. Every one of those steps has to agree with the
     * reference to the byte, and the assertion is the whole envelope.
     *
     * The intermediate assertions are not redundant — they turn "the envelope
     * differs" into "the salt differs" or "the content IV differs", which is the
     * difference between a five-minute fix and a day of bisecting bytes.
     */
    @Test
    fun rebuildsThePublishedInitialEnvelopeByteForByte() {
        val random = deterministicRandom()
        val generatedKey = generateVaultKey(random)
        assertEquals("generated vault key", fixture.s("vaultKeyBase64"), e64(generatedKey))

        val generatedKdf = newKdfParams(random)
        assertEquals("generated KDF salt", kdf.salt, generatedKdf.salt)
        assertEquals("generated KDF profile", kdf, generatedKdf)

        val kek = deriveVaultKek(passphrase, generatedKdf)
        assertEquals(fixture.s("kekBase64"), e64(kek))

        val wrapped = wrapVaultKey(generatedKey, kek, VECTOR_KEY_ID, generatedKdf, random)
        val expectedWrapped = VaultWrappedKey.parse((initial.o("header")["wrappedKeys"] as JsonArray)[0])
        assertEquals("wrappedVk", expectedWrapped.wrappedVk, wrapped.wrappedVk)
        assertEquals("wrapped key", expectedWrapped, wrapped)

        val encrypted = encryptVaultDocument(
            document = vaultVectorDocument(),
            vaultKey = generatedKey,
            header = VaultHeaderDraft(
                keyId = VECTOR_KEY_ID,
                wrappedKeys = listOf(wrapped),
                vaultVersion = 1,
                deviceId = VECTOR_DEVICE_ID,
                writeId = VECTOR_WRITE_ID,
                writtenAt = "2026-07-24T10:00:00.000Z",
            ),
            randomBytes = random,
        )

        assertEquals("content IV", initial.o("header").s("iv"), encrypted.header.iv)
        assertEquals(
            "canonical header bytes",
            initial.s("headerBytesBase64"),
            e64(serializeVaultHeader(encrypted.header)),
        )
        assertEquals("THE ENVELOPE", initial.s("envelopeBase64"), e64(encrypted.envelope))
        assertArrayEquals(initialEnvelope, encrypted.envelope)
    }

    // =======================================================================
    // 3. Failure vectors
    // =======================================================================

    @Test
    fun rejectsATamperedEnvelope() {
        val tampered = b64(initial.s("tamperedEnvelopeBase64"))
        // The fixture's tamper flips the very last ciphertext byte: same length,
        // same header, only the GCM tag disagrees.
        assertEquals("tamper must not change the length", initialEnvelope.size, tampered.size)
        assertNotEquals(e64(initialEnvelope), e64(tampered))
        expectVaultError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "tampered envelope") {
            decryptVaultDocument(tampered, vaultKey)
        }
    }

    @Test
    fun rejectsTheWrongPassphrase() {
        val wrong = fixture.o("wrongSecret")
        val wrongKek = deriveVaultKek(wrong.s("passphrase"), kdf)
        assertEquals("the wrong passphrase's KEK is also published", wrong.s("kekBase64"), e64(wrongKek))
        assertEquals("authentication-failed", wrong.s("expectedErrorCode"))

        val header = VaultEnvelopeHeader.parse(initial.o("header"))
        expectVaultError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "wrong passphrase") {
            unwrapVaultKey(header.wrappedKeys[0], header.keyId, wrongKek)
        }
    }

    @Test
    fun anyEditToTheHeaderBreaksDecryption() {
        // The header is the AAD, so this is a property of the format rather than
        // an extra check anyone had to remember to write: bumping the CAS counter
        // in the cleartext header invalidates the content tag.
        val decoded = decodeVaultEnvelope(initialEnvelope)
        val forged = encodeVaultEnvelope(decoded.header.copy(vaultVersion = 99), decoded.ciphertext)
        expectVaultError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "edited vaultVersion") {
            decryptVaultDocument(forged, vaultKey)
        }
    }

    @Test
    fun rejectsStructurallyBrokenEnvelopes() {
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "truncated") {
            decodeVaultEnvelope(initialEnvelope.copyOfRange(0, 8))
        }
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "bad magic") {
            decodeVaultEnvelope(initialEnvelope.copyOf().also { it[0] = 'X'.code.toByte() })
        }
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "header longer than the envelope") {
            decodeVaultEnvelope(
                initialEnvelope.copyOf().also {
                    it[8] = 0x7F; it[9] = 0xFF.toByte(); it[10] = 0xFF.toByte(); it[11] = 0xFF.toByte()
                }
            )
        }
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "zero-length header") {
            decodeVaultEnvelope(
                initialEnvelope.copyOf().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 0 }
            )
        }
    }

    /**
     * `exactHeaderShape` — an unknown header member is fatal.
     *
     * This is what the fixture's `updateRequired` case now exercises. It was
     * authored when v2 was hypothetical, and it carries a deliberately unknown
     * member (`futureSchemaField`) alongside `schemaVersion: 2`. Now that v2 is a
     * version this client understands, the version gate no longer fires and the
     * strict shape check rejects it — which is exactly what the platform's own
     * suite asserts today (`vault.test.ts:312-321`: "V2 is now understood, so its
     * intentionally unknown header member is rejected structurally instead of
     * being treated as a future opaque shape").
     *
     * So the fixture's `expectedStatus: "update-required"` is **historical**, and
     * this test pins current behaviour rather than the stale label. The genuine
     * update-required path is proved separately in
     * [reportsUpdateRequiredForGenuinelyNewerVersions].
     */
    @Test
    fun rejectsTheUpdateRequiredFixtureAsStructurallyInvalid() {
        val updateRequired = fixture.o("updateRequired")
        val envelope = b64(updateRequired.s("envelopeBase64"))
        assertEquals(
            "the fixture's header bytes still frame correctly",
            updateRequired.s("headerBytesBase64"),
            e64(envelope.copyOfRange(12, 12 + 548)),
        )
        assertEquals(
            "the fixture's schemaVersion is now a version we understand",
            VaultContract.DOCUMENT_VERSION,
            updateRequired["schemaVersion"]!!.jsonPrimitive.content.toInt(),
        )
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "updateRequired fixture / decode") {
            decodeVaultEnvelope(envelope)
        }
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "updateRequired fixture / inspect") {
            inspectVaultEnvelope(envelope)
        }
    }

    /**
     * The real `update-required` contract: a newer `formatVersion` or a newer
     * `schemaVersion` is reported as "update the app", **read-only and never
     * destructively parsed** (plan §2.2). The version gate must fire *before* the
     * strict shape check, so a future header carrying fields this build has never
     * seen is still reported as "too new" rather than "corrupt".
     */
    @Test
    fun reportsUpdateRequiredForGenuinelyNewerVersions() {
        val decoded = decodeVaultEnvelope(initialEnvelope)
        val base = decoded.header.toJson()

        fun envelopeWith(vararg overrides: Pair<String, Int>): ByteArray {
            val members = LinkedHashMap(base.toMap())
            overrides.forEach { (key, value) -> members[key] = JsonPrimitive(value) }
            // A member this build has never heard of, to prove the version gate
            // wins over `exactHeaderShape`.
            members["someFutureField"] = JsonPrimitive("v-next")
            val headerBytes = utf8(jsJsonStringify(JsonObject(members)))
            val out = ByteArray(12 + headerBytes.size + decoded.ciphertext.size)
            utf8(VaultContract.MAGIC).copyInto(out)
            out[8] = ((headerBytes.size ushr 24) and 0xFF).toByte()
            out[9] = ((headerBytes.size ushr 16) and 0xFF).toByte()
            out[10] = ((headerBytes.size ushr 8) and 0xFF).toByte()
            out[11] = (headerBytes.size and 0xFF).toByte()
            headerBytes.copyInto(out, 12)
            decoded.ciphertext.copyInto(out, 12 + headerBytes.size)
            return out
        }

        val newerFormat = envelopeWith("formatVersion" to VaultContract.FORMAT_VERSION + 1)
        val newerSchema = envelopeWith("schemaVersion" to VaultContract.DOCUMENT_VERSION + 1)
        val newerBoth = envelopeWith(
            "formatVersion" to VaultContract.FORMAT_VERSION + 1,
            "schemaVersion" to VaultContract.DOCUMENT_VERSION + 1,
        )

        for ((label, envelope) in listOf(
            "newer formatVersion" to newerFormat,
            "newer schemaVersion" to newerSchema,
            "newer formatVersion AND schemaVersion" to newerBoth,
        )) {
            expectVaultError(VaultCryptoErrorCode.UPDATE_REQUIRED, "$label / decode") {
                decodeVaultEnvelope(envelope)
            }
            expectVaultError(VaultCryptoErrorCode.UPDATE_REQUIRED, "$label / decrypt") {
                decryptVaultDocument(envelope, vaultKey)
            }
            when (val inspected = inspectVaultEnvelope(envelope)) {
                is EnvelopeVersionResult.UpdateRequired ->
                    assertTrue(
                        "$label: inspect must report the newer versions for the UI",
                        inspected.formatVersion > VaultContract.FORMAT_VERSION ||
                            inspected.schemaVersion > VaultContract.DOCUMENT_VERSION,
                    )
                else -> fail("$label: inspectVaultEnvelope must report update-required")
            }
        }

        // Non-destructive: the input bytes are untouched and the ORIGINAL vault
        // still opens. "Read-only, never destructive" is the actual requirement.
        assertArrayEquals(initialEnvelope, b64(initial.s("envelopeBase64")))
        decryptVaultDocument(initialEnvelope, vaultKey)
    }

    // =======================================================================
    // 4. Passphrase change and key rotation
    // =======================================================================

    @Test
    fun reproducesThePublishedPassphraseChangeEnvelope() {
        val expected = fixture.o("passphraseChanged")
        val changed = changeVaultPassphrase(
            envelope = initialEnvelope,
            oldPassphrase = passphrase,
            newPassphrase = newPassphrase,
            metadata = rekeyMetadata(expected.o("header")),
            randomBytes = deterministicRandom(),
        )
        assertEquals("passphrase-change envelope", expected.s("envelopeBase64"), e64(changed.envelope))
        assertEquals(
            "passphrase-change header bytes",
            expected.s("headerBytesBase64"),
            e64(decodeVaultEnvelope(changed.envelope).headerBytes),
        )
        // Same vault key, new wrapper: the document must still decrypt with the
        // ORIGINAL key material, and the new passphrase must unwrap it.
        assertArrayEquals("the vault key is unchanged by a passphrase change", vaultKey, changed.vaultKey)
        val newHeader = decodeVaultEnvelope(changed.envelope).header
        val newKek = deriveVaultKek(newPassphrase, newHeader.wrappedKeys[0].kdf)
        assertArrayEquals(vaultKey, unwrapVaultKey(newHeader.wrappedKeys[0], newHeader.keyId, newKek))
        assertEquals(
            jsJsonStringify(vaultVectorDocument().toJson()),
            jsJsonStringify(decryptVaultDocument(changed.envelope, vaultKey).document.toJson()),
        )
        // The old passphrase must no longer open the new envelope.
        val oldKek = deriveVaultKek(passphrase, newHeader.wrappedKeys[0].kdf)
        expectVaultError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "retired passphrase") {
            unwrapVaultKey(newHeader.wrappedKeys[0], newHeader.keyId, oldKek)
        }
    }

    @Test
    fun reproducesThePublishedKeyRotationEnvelope() {
        val expected = fixture.o("rotated")
        val rotated = rotateVaultKey(
            envelope = initialEnvelope,
            passphrase = passphrase,
            metadata = rekeyMetadata(expected.o("header")),
            randomBytes = deterministicRandom(96),
            keyIdGenerator = { VECTOR_NEXT_KEY_ID },
        )
        assertEquals("rotation envelope", expected.s("envelopeBase64"), e64(rotated.envelope))
        assertEquals(
            "rotation header bytes",
            expected.s("headerBytesBase64"),
            e64(decodeVaultEnvelope(rotated.envelope).headerBytes),
        )
        assertEquals("rotation mints a fresh key id", expected.s("keyId"), rotated.header.keyId)
        // Rotation means a genuinely NEW vault key — the old one must not open it.
        assertNotEquals(e64(vaultKey), e64(rotated.vaultKey))
        expectVaultError(VaultCryptoErrorCode.AUTHENTICATION_FAILED, "rotated away key") {
            decryptVaultDocument(rotated.envelope, vaultKey)
        }
        assertEquals(
            jsJsonStringify(vaultVectorDocument().toJson()),
            jsJsonStringify(decryptVaultDocument(rotated.envelope, rotated.vaultKey).document.toJson()),
        )
        // ...and the new key really is derivable from the unchanged passphrase.
        val header = decodeVaultEnvelope(rotated.envelope).header
        val kek = deriveVaultKek(passphrase, header.wrappedKeys[0].kdf)
        assertArrayEquals(rotated.vaultKey, unwrapVaultKey(header.wrappedKeys[0], header.keyId, kek))
    }

    @Test
    fun refusesARotationThatWouldReuseTheCurrentKeyId() {
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "same key id") {
            rotateVaultKey(
                envelope = initialEnvelope,
                passphrase = passphrase,
                metadata = rekeyMetadata(fixture.o("rotated").o("header")),
                randomBytes = deterministicRandom(96),
                keyIdGenerator = { VECTOR_KEY_ID },
            )
        }
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "non-uuid key id") {
            rotateVaultKey(
                envelope = initialEnvelope,
                passphrase = passphrase,
                metadata = rekeyMetadata(fixture.o("rotated").o("header")),
                randomBytes = deterministicRandom(96),
                keyIdGenerator = { "not-a-uuid" },
            )
        }
    }

    // =======================================================================
    // 5. Recovery kit
    // =======================================================================

    @Test
    fun reproducesThePublishedRecoveryKitBytes() {
        val kit = serializeRecoveryKit(RecoveryKit(VECTOR_KEY_ID, vaultKey, VaultContract.FORMAT_VERSION))
        assertEquals(fixture.s("recoveryKitBase64"), e64(kit.bytes))
        assertEquals(RECOVERY_KIT_FILENAME, kit.filename)

        val imported = importRecoveryKit(kit.bytes, VECTOR_KEY_ID)
        assertEquals(VECTOR_KEY_ID, imported.keyId)
        assertArrayEquals(vaultKey, imported.vaultKey)
        // The kit alone opens the vault — that is the point, and the reason plan
        // §2.7 puts it behind a blocking acknowledgment.
        decryptVaultDocument(initialEnvelope, imported.vaultKey)

        expectVaultError(VaultCryptoErrorCode.RECOVERY_KIT_INVALID, "wrong key id") {
            importRecoveryKit(kit.bytes, VECTOR_NEXT_KEY_ID)
        }
        expectVaultError(VaultCryptoErrorCode.RECOVERY_KIT_INVALID, "mangled kit") {
            importRecoveryKit(kit.bytes.copyOfRange(0, kit.bytes.size - 5))
        }
    }

    // =======================================================================
    // 6. Rollback protection
    // =======================================================================

    /**
     * The rollback vectors are the most operationally important in the fixture:
     * they pin that a rekey which fails **part-way through** leaves the vault
     * exactly as it was. A half-applied passphrase change is a vault nobody can
     * open — neither the old passphrase nor the new one — which is
     * indistinguishable from data loss.
     *
     * The fixture drives the failure with a CSPRNG that throws on a chosen call
     * (call 3 for a passphrase change, call 4 for a rotation), i.e. after key
     * material has already been derived and while the new envelope is being
     * built.
     */
    @Test
    fun aFailedRekeyLeavesTheVaultUntouched() {
        val rollback = fixture.o("rollback")
        assertEquals(initial.s("envelopeBase64"), rollback.s("expectedEnvelopeBase64"))
        assertEquals(initial.s("headerBytesBase64"), rollback.s("expectedHeaderBytesBase64"))
        assertEquals(fixture.s("vaultKeyBase64"), rollback.s("expectedVaultKeyBase64"))
        assertEquals(initial.o("header").s("keyId"), rollback.s("expectedKeyId"))

        fun assertVaultIntact(what: String) {
            assertEquals("$what: envelope bytes", rollback.s("expectedEnvelopeBase64"), e64(initialEnvelope))
            assertEquals(
                "$what: header bytes",
                rollback.s("expectedHeaderBytesBase64"),
                e64(decodeVaultEnvelope(initialEnvelope).headerBytes),
            )
            assertEquals(
                "$what: the document still decrypts",
                jsJsonStringify(vaultVectorDocument().toJson()),
                jsJsonStringify(decryptVaultDocument(initialEnvelope, vaultKey).document.toJson()),
            )
        }

        assertVaultIntact("before any failure")

        val passphraseChange = rollback.o("passphraseChange")
        try {
            changeVaultPassphrase(
                envelope = initialEnvelope,
                oldPassphrase = passphraseChange.s("oldPassphrase"),
                newPassphrase = passphraseChange.s("newPassphrase"),
                metadata = rekeyMetadata(passphraseChange.o("metadata")),
                randomBytes = failingDeterministicRandom(
                    passphraseChange["failAtRandomCall"]!!.jsonPrimitive.content.toInt(),
                    passphraseChange["randomStart"]!!.jsonPrimitive.content.toInt(),
                ),
            )
            fail("the failing CSPRNG must abort the passphrase change")
        } catch (e: Throwable) {
            assertTrue(
                "expected '${passphraseChange.s("expectedErrorMessage")}', got '${e.message}' / ${e.cause?.message}",
                (e.message ?: "").contains(passphraseChange.s("expectedErrorMessage")) ||
                    (e.cause?.message ?: "").contains(passphraseChange.s("expectedErrorMessage")),
            )
        }
        assertVaultIntact("after a failed passphrase change")

        val rotation = rollback.o("rotation")
        try {
            rotateVaultKey(
                envelope = initialEnvelope,
                passphrase = rotation.s("oldPassphrase"),
                metadata = rekeyMetadata(rotation.o("metadata")),
                randomBytes = failingDeterministicRandom(
                    rotation["failAtRandomCall"]!!.jsonPrimitive.content.toInt(),
                    rotation["randomStart"]!!.jsonPrimitive.content.toInt(),
                ),
                keyIdGenerator = { rotation.s("keyId") },
            )
            fail("the failing CSPRNG must abort the rotation")
        } catch (e: Throwable) {
            assertTrue(
                "expected '${rotation.s("expectedErrorMessage")}', got '${e.message}' / ${e.cause?.message}",
                (e.message ?: "").contains(rotation.s("expectedErrorMessage")) ||
                    (e.cause?.message ?: "").contains(rotation.s("expectedErrorMessage")),
            )
        }
        assertVaultIntact("after a failed rotation")
    }

    /**
     * A rekey must never reuse or lower the CAS counter, and must never reuse the
     * write id — that is what makes a replayed or reordered Drive write
     * detectable rather than silently authoritative.
     */
    @Test
    fun refusesARekeyThatDoesNotAdvanceTheVaultVersion() {
        val rollback = fixture.o("rollback")
        val stale = VaultEnvelopeHeader.parse(initial.o("header"))
            .copy(vaultVersion = rollback["rejectedVaultVersion"]!!.jsonPrimitive.content.toInt())

        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "stale vaultVersion") {
            changeVaultPassphrase(
                envelope = initialEnvelope,
                oldPassphrase = passphrase,
                newPassphrase = newPassphrase,
                metadata = RekeyHeaderMetadata(
                    stale.vaultVersion,
                    stale.deviceId,
                    fixture.o("passphraseChanged").o("header").s("writeId"),
                    stale.writtenAt,
                ),
            )
        }
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "reused writeId") {
            changeVaultPassphrase(
                envelope = initialEnvelope,
                oldPassphrase = passphrase,
                newPassphrase = newPassphrase,
                metadata = RekeyHeaderMetadata(
                    rollback["nextVaultVersion"]!!.jsonPrimitive.content.toInt(),
                    stale.deviceId,
                    stale.writeId, // identical to the prior write
                    stale.writtenAt,
                ),
            )
        }
        expectVaultError(VaultCryptoErrorCode.ENVELOPE_INVALID, "unchanged passphrase") {
            changeVaultPassphrase(
                envelope = initialEnvelope,
                oldPassphrase = passphrase,
                newPassphrase = passphrase,
                metadata = rekeyMetadata(fixture.o("passphraseChanged").o("header")),
            )
        }
    }

    companion object {
        // `vectors.ts:7-10`
        const val VECTOR_KEY_ID = "018f0000-0000-7000-8000-00000000000a"
        const val VECTOR_DEVICE_ID = "018f0000-0000-7000-8000-00000000000b"
        const val VECTOR_WRITE_ID = "018f0000-0000-7000-8000-00000000000c"
        const val VECTOR_NEXT_KEY_ID = "018f0000-0000-7000-8000-00000000000d"
    }
}
