package at.bettertrack.app.vault.pv.envelope

import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.aesGcmDecrypt
import at.bettertrack.app.vault.aesGcmEncrypt
import at.bettertrack.app.vault.pv.docs.PvDocBuckets
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The E0 envelope-v2 conformance vectors, replayed against the app's port.**
 *
 * `pv-envelope.fixture.json` transcribes every literal of the platform's
 * `packages/contracts/src/vaults.vectors.test.ts` (main `14f27679`, epic E0
 * #1410) — the ONE conformance source for this format. Each `@Test` below is a
 * 1:1 translation of one `it()` there, in the same order, with the same intent.
 *
 * Unlike the v1/v2 vector suites there is no byte oracle to replay: the platform
 * suite generates its AES key and IV at run time and asserts PROPERTIES —
 * round trip, the four §8 anti-swap fields, a single-bit AAD flip, the
 * canonicalization pin, fail-closed versioning, the doc buckets, the doc-set
 * payloads and the transport bodies. So this file asserts the same properties
 * with real AES-256-GCM, and the one thing that IS byte-determined by the
 * contract — `accountBinding = base64url(sha256(prefix + accountId))` — is
 * pinned in the fixture and recomputed here.
 *
 * The crypto is real on purpose. The §8 guarantee (a doc copied between vaults,
 * accounts or Drive folders fails decryption) is proven at the format level, not
 * assumed: every anti-swap case below mutates the SERIALIZED HEADER and then
 * asks AES-GCM, not a schema, to refuse it.
 */
class PvEnvelopeConformanceTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/pv-envelope.fixture.json")
            ?: error("vault-vectors/pv-envelope.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun family(name: String): JsonObject = fixture[name]!!.jsonObject
    private fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.o(key: String): JsonObject = this[key]!!.jsonObject
    private fun JsonObject.a(key: String): JsonArray = this[key]!!.jsonArray

    private val ids: JsonObject by lazy { family("ids") }
    private val headerLiterals: JsonObject by lazy { family("header") }

    private val random = SecureRandom()
    private fun randomBytes(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }

    // ── The fixture's header, exactly as `makeHeader()` builds it ────────────

    private fun makeHeader(iv: ByteArray = randomBytes(12)): PvDocEnvelopeHeader =
        PvDocEnvelopeHeader(
            formatVersion = headerLiterals.i("formatVersion"),
            cipher = headerLiterals.s("cipher"),
            iv = pvBase64UrlEncode(iv),
            keyId = ids.s("keyId"),
            keySlots = listOf(
                PvKeySlot(
                    keyId = ids.s("keyId"),
                    slot = headerLiterals.s("slot"),
                    wrappedKc = headerLiterals.s("wrappedKc"),
                ),
            ),
            vaultId = ids.s("vaultId"),
            docId = ids.s("docId"),
            docKind = headerLiterals.s("docKind"),
            accountBinding = pvAccountBinding(ids.s("accountId")),
            docVersion = headerLiterals.i("docVersion"),
            schemaVersion = headerLiterals.i("schemaVersion"),
            deviceId = ids.s("deviceId"),
            writeId = ids.s("writeId"),
            writtenAt = headerLiterals.s("writtenAt"),
        )

    private class Sealed(
        val header: PvDocEnvelopeHeader,
        val envelope: ByteArray,
        val key: ByteArray,
        val iv: ByteArray,
        val plaintext: ByteArray,
    )

    /** `seal()` — encrypt → encode, with the exact serialized header as the GCM AAD. */
    private fun seal(): Sealed {
        val key = randomBytes(32)
        val iv = randomBytes(12)
        val header = makeHeader(iv)
        val headerBytes = serializePvDocHeader(header)
        val plaintext = family("roundTrip").s("plaintext").toByteArray(Charsets.UTF_8)
        val ciphertext = aesGcmEncrypt(key, iv, plaintext, headerBytes)
        return Sealed(header, encodePvDocEnvelope(header, ciphertext), key, iv, plaintext)
    }

    private fun supported(envelope: ByteArray): PvDecodedEnvelope =
        (inspectPvDocEnvelope(envelope) as? PvDocEnvelopeInspection.Supported)?.envelope
            ?: error("fixture must be supported")

    /** Byte-exact JSON text surgery on the wire header, re-framed as an envelope. */
    private fun mutateHeaderText(envelope: ByteArray, from: String, to: String): ByteArray {
        val decoded = supported(envelope)
        val text = String(decoded.headerBytes, Charsets.UTF_8)
        assertTrue("fixture header does not contain $from", text.contains(from))
        // JS `String.prototype.replace(string, string)` replaces the FIRST match.
        val mutated = text.replaceFirst(from, to)
        return framePvEnvelope(
            at.bettertrack.app.vault.VAULT_JSON.parseToJsonElement(mutated),
            decoded.ciphertext,
        )
    }

    private fun failsToDecrypt(sealed: Sealed, ciphertext: ByteArray, aad: ByteArray) {
        val error = runCatching { aesGcmDecrypt(sealed.key, sealed.iv, ciphertext, aad) }.exceptionOrNull()
        assertTrue(
            "the GCM tag must refuse this AAD, got $error",
            error is VaultCryptoError && error.code == VaultCryptoErrorCode.AUTHENTICATION_FAILED,
        )
    }

    // =======================================================================
    // Family A — round trip and the AAD anti-swap guarantee (§5, §8)
    // =======================================================================

    @Test
    fun `encrypt encode inspect decrypt round-trips`() {
        val sealed = seal()
        val decoded = supported(sealed.envelope)
        assertEquals("the inspected header is the sealed header", sealed.header, decoded.header)
        // Decrypt authenticates the EXACT wire header bytes as AAD.
        val plaintext = aesGcmDecrypt(sealed.key, sealed.iv, decoded.ciphertext, decoded.headerBytes)
        assertEquals(family("roundTrip").s("plaintext"), String(plaintext, Charsets.UTF_8))
    }

    /**
     * The §8 anti-swap fields, one test each: mutating the serialized header
     * makes DECRYPTION fail closed — even when the mutated header still parses
     * as a perfectly valid v2 header (a swapped-but-well-formed id), the GCM tag
     * refuses it before any payload byte is interpreted.
     */
    private fun antiSwap(index: Int) {
        val swap = fixture.a("antiSwap")[index].jsonObject
        val sealed = seal()
        val mutated = mutateHeaderText(sealed.envelope, swap.s("from"), swap.s("to"))
        // Read the mutated wire bytes with the RAW framing reader: a swapped id
        // still parses as a valid v2 header and a rolled-back formatVersion no
        // longer satisfies the v2 literal — either way the CRYPTO must refuse,
        // independent of any schema validation.
        val framing = decodePvEnvelopeFraming(mutated)
        failsToDecrypt(sealed, framing.ciphertext, framing.headerBytes)
    }

    @Test
    fun `fails closed on a formatVersion rollback`() = antiSwap(0)

    @Test
    fun `fails closed on a swapped vaultId`() = antiSwap(1)

    @Test
    fun `fails closed on a swapped docId`() = antiSwap(2)

    @Test
    fun `fails closed on a swapped accountBinding`() {
        val swap = fixture.a("antiSwap")[3].jsonObject
        val binding = family("accountBinding")
        // The digests are contract-determined, so they are pinned AND recomputed.
        assertEquals("accountBinding(account)", binding.s("account"), pvAccountBinding(ids.s("accountId")))
        assertEquals(
            "accountBinding(otherAccount)",
            binding.s("otherAccount"),
            pvAccountBinding(ids.s("otherAccountId")),
        )
        assertEquals(swap.s("from"), binding.s("account"))
        assertEquals(swap.s("to"), binding.s("otherAccount"))
        antiSwap(3)
    }

    /**
     * Canonicalization pin (platform review round 1 on #1424): the writer
     * serializes the SCHEMA-parsed header, so two writers handing the codec the
     * same fields in ANY key order must emit byte-identical wire headers —
     * otherwise one logical header could carry two different AADs and a
     * re-encode by a cooperating device would fail decryption.
     *
     * On the platform this rides zod's parse-time key ordering; here it rides
     * [PvDocEnvelopeHeader.toJson] rebuilding the object in schema order. Same
     * property, same test.
     */
    @Test
    fun `serializes a fully key-shuffled header to byte-identical canonical AAD bytes`() {
        val header = makeHeader()
        val canonical = serializePvDocHeader(header)

        fun reverseKeys(value: JsonObject): JsonObject =
            JsonObject(value.entries.reversed().associateTo(LinkedHashMap()) { it.key to it.value })

        val original = header.toJson()
        val shuffled = reverseKeys(
            JsonObject(
                original.toMutableMap().apply {
                    this["keySlots"] = JsonArray(
                        original.a("keySlots").map { reverseKeys(it.jsonObject) },
                    )
                },
            ),
        )
        assertNotEquals("the shuffle must actually reorder", original.keys.toList(), shuffled.keys.toList())

        assertArrayEquals("a shuffled header canonicalizes to the same bytes", canonical, pvCanonicalHeaderBytes(shuffled))
        val envelope = framePvEnvelopeBytes(pvCanonicalHeaderBytes(shuffled), ByteArray(16))
        assertArrayEquals("and the framed envelope carries those bytes", canonical, supported(envelope).headerBytes)
    }

    @Test
    fun `fails closed on any single-byte header mutation`() {
        val sealed = seal()
        val decoded = supported(sealed.envelope)
        val mutatedAad = decoded.headerBytes.copyOf()
        // Flip one bit somewhere in the middle of the serialized header.
        mutatedAad[mutatedAad.size / 2] = (mutatedAad[mutatedAad.size / 2].toInt() xor 0x01).toByte()
        failsToDecrypt(sealed, decoded.ciphertext, mutatedAad)
        // Control: the untouched AAD still decrypts.
        assertEquals(
            family("roundTrip").s("plaintext"),
            String(
                aesGcmDecrypt(sealed.key, sealed.iv, decoded.ciphertext, decoded.headerBytes),
                Charsets.UTF_8,
            ),
        )
    }

    // =======================================================================
    // Family B — strict fail-closed versioning (§5)
    // =======================================================================

    @Test
    fun `refuses a formatVersion 3 envelope with update-required, never parsed`() {
        val versioning = family("versioning")
        val envelope = framePvEnvelope(
            versioning.o("futureFormatHeader"),
            versioning.a("futureFormatCiphertext").map { it.jsonPrimitive.content.toInt().toByte() }.toByteArray(),
        )
        val expected = versioning.o("futureFormatExpectation")
        assertEquals(
            PvDocEnvelopeInspection.UpdateRequired(expected.i("formatVersion"), expected.i("schemaVersion")),
            inspectPvDocEnvelope(envelope),
        )
    }

    @Test
    fun `refuses a v2 envelope carrying a newer payload schemaVersion`() {
        val versioning = family("versioning")
        val newer = versioning.i("futurePayloadSchemaVersion")
        val header = JsonObject(
            makeHeader().toJson().toMutableMap().apply { this["schemaVersion"] = JsonPrimitive(newer) },
        )
        val inspected = inspectPvDocEnvelope(framePvEnvelope(header, ByteArray(16)))
        assertEquals(
            PvDocEnvelopeInspection.UpdateRequired(PvVaultContract.DOC_FORMAT_VERSION, newer),
            inspected,
        )
    }

    @Test
    fun `rejects a v1 ACCOUNT-vault envelope instead of downgrading it`() {
        val envelope = framePvEnvelope(family("versioning").o("v1AccountHeader"), ByteArray(16))
        val error = assertThrows(VaultCryptoError::class.java) { inspectPvDocEnvelope(envelope) }
        assertEquals(VaultCryptoErrorCode.ENVELOPE_INVALID, error.code)
    }

    @Test
    fun `rejects an unknown extra header field, strict and fail closed`() {
        val extra = family("versioning").o("unknownExtraHeaderField")
        val header = JsonObject(makeHeader().toJson().toMutableMap().apply { putAll(extra) })
        val error = assertThrows(VaultCryptoError::class.java) {
            inspectPvDocEnvelope(framePvEnvelope(header, ByteArray(16)))
        }
        assertEquals(VaultCryptoErrorCode.ENVELOPE_INVALID, error.code)
    }

    @Test
    fun `server header read yields exactly formatVersion and docVersion, even for future formats`() {
        val versioning = family("versioning")
        val expected = versioning.o("serverHeader")
        assertEquals(
            PvDocServerHeader(expected.i("formatVersion"), expected.i("docVersion")),
            readPvDocServerHeader(seal().envelope),
        )
        // The blind store must keep accepting newer formats verbatim (§5 makes
        // versioning a CLIENT decision) — only the two CAS fields are read.
        val future = versioning.o("serverHeaderFuture")
        assertEquals(
            PvDocServerHeader(future.i("formatVersion"), future.i("docVersion")),
            readPvDocServerHeader(framePvEnvelope(future, ByteArray(16))),
        )
    }

    // =======================================================================
    // Family C — the media enum and its reserved `local` value (§22)
    // =======================================================================

    @Test
    fun `the CONTRACT accepts local, rejection is a server boundary decision`() {
        val media = family("media")
        assertEquals(
            "the contract's media values",
            media.a("values").map { it.jsonPrimitive.content },
            PvVaultContract.MEDIA_VALUES,
        )
        assertEquals(
            "what the server accepts today",
            media.a("serverAccepted").map { it.jsonPrimitive.content },
            PvVaultContract.SERVER_ACCEPTED_MEDIA,
        )
        val reserved = listOf(media.s("reserved"))
        assertEquals("the contract accepts the reserved value", null, PvVaultConfig.mediaListProblem(reserved))
        assertFalse("the server does not", PvVaultConfig.isServerAcceptedMedia(reserved))
        assertEquals(
            "creating a vault on the reserved medium is a contract-valid request",
            null,
            createVaultProblem(media.o("createVault").a("cases")[0].jsonObject),
        )
    }

    @Test
    fun `rejects an empty or duplicated media list`() {
        val media = family("media")
        media.a("validLists").forEach { list ->
            val values = list.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$values must be valid", null, PvVaultConfig.mediaListProblem(values))
        }
        media.a("invalidLists").forEach { list ->
            val values = list.jsonArray.map { it.jsonPrimitive.content }
            assertNotEquals("$values must be refused", null, PvVaultConfig.mediaListProblem(values))
        }
    }

    @Test
    fun `requires the Drive binding iff the drive medium is selected`() {
        family("media").o("createVault").a("cases").drop(1).forEach { element ->
            val case = element.jsonObject
            val problem = createVaultProblem(case)
            assertEquals(
                "${case.s("name")}: expected valid=${case["valid"]!!.jsonPrimitive.content}, got $problem",
                case["valid"]!!.jsonPrimitive.content.toBoolean(),
                problem == null,
            )
        }
    }

    private fun createVaultProblem(case: JsonObject): String? {
        val create = family("media").o("createVault")
        return PvVaultConfig.createVaultProblem(
            name = case.s("vaultName"),
            media = case.a("media").map { it.jsonPrimitive.content },
            driveConnectionId = case["driveConnectionId"]?.jsonPrimitive?.takeIf { it.isString }?.content,
            keyFingerprint = create.s("keyFingerprint"),
            retirementProofPublicKey = create.s("retirementProofPublicKey"),
        )
    }

    // =======================================================================
    // Family D — the key fingerprint (§4)
    // =======================================================================

    @Test
    fun `accepts exactly 16 base64url chars and nothing else`() {
        val vector = family("keyFingerprint")
        assertEquals(vector.i("chars"), PvVaultContract.KEY_FINGERPRINT_CHARS)
        vector.a("valid").forEach {
            val value = it.jsonPrimitive.content
            assertEquals("'$value' must be a valid fingerprint", null, PvVaultConfig.keyFingerprintProblem(value))
        }
        vector.a("invalid").forEach {
            val value = it.jsonPrimitive.content
            assertNotEquals("'$value' must be refused", null, PvVaultConfig.keyFingerprintProblem(value))
        }
    }

    // =======================================================================
    // Family E — doc buckets (§5), exhaustive and disjoint
    // =======================================================================

    @Test
    fun `assigns every entity kind exactly one bucket`() {
        val buckets = family("docBuckets").o("entityDocBuckets")
        assertEquals(
            "the bucket map covers the whole contract kind list",
            VaultContract.ENTITY_KINDS.sorted(),
            buckets.keys.sorted(),
        )
        val union = (PvDocBuckets.PORTFOLIO_DOC_KINDS + PvDocBuckets.COMMON_DOC_KINDS).sorted()
        assertEquals("the two buckets cover every kind", VaultContract.ENTITY_KINDS.sorted(), union)
        assertEquals("no unbucketed kind", emptySet<String>(), PvDocBuckets.UNBUCKETED_KINDS)
        assertEquals(
            "the buckets do not overlap",
            emptySet<String>(),
            PvDocBuckets.PORTFOLIO_DOC_KINDS intersect PvDocBuckets.COMMON_DOC_KINDS,
        )
        buckets.forEach { (kind, bucket) ->
            assertEquals("bucket of '$kind'", bucket.jsonPrimitive.content, PvDocBuckets.bucketOf(kind))
        }
    }

    @Test
    fun `pins the mechanical scoping rule on the telling cases`() {
        family("docBuckets").o("tellingCases").forEach { (kind, bucket) ->
            assertEquals("bucket of '$kind'", bucket.jsonPrimitive.content, PvDocBuckets.bucketOf(kind))
        }
        // Recorded delta: E0 re-derived budgets as portfolio-keyed, so they left
        // the v2 arc's `common` placement. Both halves are asserted so a silent
        // drift in either direction fails here.
        assertEquals(
            "the E0 delta against the v2 partition",
            family("docBuckets").a("movedFromV2ToPortfolio").map { it.jsonPrimitive.content }.toSet(),
            PvDocBuckets.E0_MOVED_TO_PORTFOLIO,
        )
    }

    // =======================================================================
    // Family F — the doc-set payloads (§5)
    // =======================================================================

    @Test
    fun `header doc carries the roster, the keySlots echo and the creation record`() {
        val vector = family("docPayloads").o("headerDoc")
        val parsed = PvHeaderDoc.parse(vector.o("valid"))
        assertEquals(1, parsed.portfolios.size)
        assertEquals(ids.s("vaultId"), parsed.portfolios[0].id)
        assertEquals(null, parsed.driveConnection)
        assertEquals("re-encoding the parsed doc round-trips", vector.o("valid"), parsed.toJson())
        assertThrows(VaultCryptoError::class.java) { PvHeaderDoc.parse(vector.o("emptyKeySlots")) }
    }

    @Test
    fun `common doc requires clientSecurity and refuses portfolio-bucket kinds`() {
        val vector = family("docPayloads").o("commonDoc")
        val parsed = PvCommonDoc.parse(vector.o("valid"))
        assertEquals(
            family("docPayloads").s("retirementProofPrivateKey"),
            parsed.clientSecurity.retirementProof.privateKey,
        )
        assertThrows(VaultCryptoError::class.java) { PvCommonDoc.parse(vector.o("missingClientSecurity")) }
        assertThrows(VaultCryptoError::class.java) {
            PvCommonDoc.parse(withEntities(vector.o("valid"), vector.s("portfolioBucketKind")))
        }
    }

    @Test
    fun `portfolio doc refuses common-bucket kinds`() {
        val vector = family("docPayloads").o("portfolioDoc")
        val base = vector.o("valid")
        assertEquals(ids.s("vaultId"), PvPortfolioDoc.parse(base).portfolioId)
        assertThrows(VaultCryptoError::class.java) {
            PvPortfolioDoc.parse(withEntities(base, vector.s("commonBucketKind")))
        }
        val allowed = PvPortfolioDoc.parse(withEntities(base, vector.s("portfolioBucketKind")))
        assertEquals(setOf(vector.s("portfolioBucketKind")), allowed.entities.keys)
    }

    private fun withEntities(base: JsonObject, kind: String): JsonObject = JsonObject(
        base.toMutableMap().apply {
            this["entities"] = buildJsonObject { put(kind, JsonArray(emptyList())) }
        },
    )

    // =======================================================================
    // Family G — move-in / move-out / step-up bodies (§9, §10, §15)
    // =======================================================================

    @Test
    fun `step-up requires at least one credential`() {
        val bodies = family("moveBodies")
        assertNotEquals(null, PvStepUpCredential().problem())
        assertEquals(null, stepUp(bodies.o("stepUp")).problem())
        assertEquals(null, stepUp(bodies.o("stepUpCode")).problem())
    }

    @Test
    fun `move-in binds vault, doc CAS, capture revision and step-up`() {
        val bodies = family("moveBodies")
        val moveIn = bodies.o("moveIn")
        assertEquals(
            null,
            PvVaultConfig.moveInProblem(
                vaultId = moveIn.s("vaultId"),
                docVersion = moveIn.i("docVersion"),
                portfolioDataRevision = moveIn.s("portfolioDataRevision"),
                stepUp = stepUp(bodies.o("stepUp")),
            ),
        )
        assertNotEquals(
            null,
            PvVaultConfig.moveInProblem(
                vaultId = moveIn.s("vaultId"),
                docVersion = moveIn.i("docVersion"),
                portfolioDataRevision = moveIn.s("portfolioDataRevision"),
                stepUp = null,
            ),
        )
    }

    @Test
    fun `move-out carries the idempotency key, the restore document and step-up`() {
        val bodies = family("moveBodies")
        val moveOut = bodies.o("moveOut")
        assertEquals(
            null,
            PvVaultConfig.moveOutProblem(
                vaultId = moveOut.s("vaultId"),
                moveOutId = moveOut.s("moveOutId"),
                document = moveOut.o("document"),
                stepUp = stepUp(bodies.o("stepUp")),
            ),
        )
        assertNotEquals(
            null,
            PvVaultConfig.moveOutProblem(
                vaultId = moveOut.s("vaultId"),
                moveOutId = moveOut.s("moveOutId"),
                document = null,
                stepUp = stepUp(bodies.o("stepUp")),
            ),
        )
    }

    private fun stepUp(body: JsonObject): PvStepUpCredential = PvStepUpCredential(
        password = body["password"]?.jsonPrimitive?.content,
        code = body["code"]?.jsonPrimitive?.content,
        recoveryCode = body["recoveryCode"]?.jsonPrimitive?.content,
    )

    // =======================================================================
    // Family H — the envelope v2 header schema
    // =======================================================================

    @Test
    fun `the header schema is strict and pins the literal format version and slot kind`() {
        val header = makeHeader()
        assertEquals(header, PvDocEnvelopeHeader.parse(header.toJson()))

        val rejects = family("headerSchema").a("rejects").map { it.jsonObject }
        val byFormatVersion = JsonObject(
            header.toJson().toMutableMap()
                .apply { this["formatVersion"] = rejects[0]["value"]!! },
        )
        assertThrows(VaultCryptoError::class.java) { PvDocEnvelopeHeader.parse(byFormatVersion) }

        val bySlot = JsonObject(
            header.toJson().toMutableMap().apply {
                this["keySlots"] = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("keyId", JsonPrimitive(ids.s("keyId")))
                            put("slot", rejects[1]["value"]!!)
                            put("wrappedKc", JsonPrimitive("x"))
                        },
                    ),
                )
            },
        )
        assertThrows(VaultCryptoError::class.java) { PvDocEnvelopeHeader.parse(bySlot) }

        val byDocKind = JsonObject(
            header.toJson().toMutableMap().apply { this["docKind"] = rejects[2]["value"]!! },
        )
        assertThrows(VaultCryptoError::class.java) { PvDocEnvelopeHeader.parse(byDocKind) }
    }

    // =======================================================================
    // The v2Partition family, replayed against the pv doc-bucket location
    // =======================================================================

    /**
     * The shipped v2 arc's partition vector (`v2.fixture.json` → `v2Partition`)
     * replayed against [PvDocBuckets], because the pv buckets DERIVE from that
     * partition. Its `coveredKinds` list is the contract's kind list in contract
     * order, and its `commonKinds` list is the v2 placement — which the pv
     * location must reproduce exactly, minus the recorded E0 delta.
     */
    @Test
    fun `v2Partition replays against the pv doc-bucket location`() {
        val stream = javaClass.getResourceAsStream("/vault-vectors/v2.fixture.json")
            ?: error("vault-vectors/v2.fixture.json missing from test resources")
        val v2Partition = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() })
            .jsonObject["v2Partition"]!!.jsonObject

        val covered = v2Partition.a("coveredKinds").map { it.jsonPrimitive.content }
        assertEquals("26 covered kinds", 26, covered.size)
        assertEquals(
            "the pv buckets cover the v2 vector's kind list exactly once",
            covered.sorted(),
            (PvDocBuckets.PORTFOLIO_DOC_KINDS + PvDocBuckets.COMMON_DOC_KINDS).sorted(),
        )
        val v2Common = v2Partition.a("commonKinds").map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            "pv common = v2 common minus the E0 delta",
            v2Common - PvDocBuckets.E0_MOVED_TO_PORTFOLIO,
            PvDocBuckets.COMMON_DOC_KINDS,
        )
        assertTrue(
            "and the delta really was common in v2",
            v2Common.containsAll(PvDocBuckets.E0_MOVED_TO_PORTFOLIO),
        )
    }

    // =======================================================================
    // Port-local coverage — the production write path the vectors do not enter
    // =======================================================================
    //
    // The platform suite seals a RAW plaintext to isolate the envelope; the
    // app's own writer additionally deflates and re-parses the payload. These
    // three tests cover that path. They are ours, not the platform's, and are
    // kept separate so nobody mistakes them for conformance vectors.

    private fun docWrite(docVersion: Int = 1) = PvDocWrite(
        vaultId = ids.s("vaultId"),
        docId = ids.s("docId"),
        accountBinding = pvAccountBinding(ids.s("accountId")),
        keyId = ids.s("keyId"),
        keySlots = listOf(PvKeySlot(ids.s("keyId"), PvVaultContract.KEY_SLOT_SEED_V1, headerLiterals.s("wrappedKc"))),
        docVersion = docVersion,
        deviceId = ids.s("deviceId"),
        writeId = ids.s("writeId"),
        writtenAt = headerLiterals.s("writtenAt"),
    )

    private fun samplePortfolioDoc(): PvPortfolioDoc =
        PvPortfolioDoc(portfolioId = ids.s("vaultId"), entities = emptyMap())

    @Test
    fun `port-local - a portfolio doc round-trips through encrypt and decrypt`() {
        val key = randomBytes(32)
        val sealed = encryptPvDoc(samplePortfolioDoc(), key, docWrite(docVersion = 7))
        assertEquals(PvVaultContract.KIND_PORTFOLIO, sealed.header.docKind)
        assertEquals(7, sealed.header.docVersion)
        val opened = decryptPvDoc(sealed.envelope, key)
        assertEquals(samplePortfolioDoc().toJson(), opened.document.toJson())
        assertEquals(sealed.header, opened.header)
    }

    @Test
    fun `port-local - a doc written for one vault does not open under another vault's header`() {
        val key = randomBytes(32)
        val sealed = encryptPvDoc(samplePortfolioDoc(), key, docWrite())
        val swapped = mutateHeaderText(sealed.envelope, ids.s("vaultId"), ids.s("otherVaultId"))
        val error = assertThrows(VaultCryptoError::class.java) { decryptPvDoc(swapped, key) }
        assertEquals(VaultCryptoErrorCode.AUTHENTICATION_FAILED, error.code)
    }

    @Test
    fun `port-local - the header doc and the common doc round-trip too`() {
        val key = randomBytes(32)
        val headerDoc = PvHeaderDoc.parse(family("docPayloads").o("headerDoc").o("valid"))
        val commonDoc = PvCommonDoc.parse(family("docPayloads").o("commonDoc").o("valid"))
        listOf<PvVaultDoc>(headerDoc, commonDoc).forEach { document ->
            val sealed = encryptPvDoc(document, key, docWrite())
            assertEquals(document.docKind, sealed.header.docKind)
            assertEquals(document.toJson(), decryptPvDoc(sealed.envelope, key).document.toJson())
        }
    }

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) =
        assertEquals(message, expected.toList(), actual.toList())
}
