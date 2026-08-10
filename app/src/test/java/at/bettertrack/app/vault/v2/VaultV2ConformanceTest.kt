package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.VaultDocument
import at.bettertrack.app.vault.VaultEntity
import at.bettertrack.app.vault.bytesToBase64
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six Vaults v2 conformance families (`docs/VAULTS_V2_DESIGN.md` r3 §25),
 * replayed against the app's own v2 port.
 *
 * `v2.fixture.json` is the platform's published byte-exact oracle, vendored from
 * `packages/domain/src/vaultVectors/v2.fixture.json` at platform pin `8884c5cb`
 * and copied byte-identically into this module's test resources. The bytes were
 * produced by the platform's REAL crypto path — real Argon2id (64 MiB, t=3),
 * real AES-256-GCM, real HKDF-SHA256 — over fixed deterministic inputs.
 *
 * The safety framing matters and is the platform's own: **v2 migration writes
 * use deterministic IVs, so vector conformance is a SAFETY property.** Any drift
 * from these pinned bytes is a security bug, not a cosmetic one — two claim
 * holders that disagree about `K_c` or an IV write mutually undecryptable blobs
 * under one document identity (mobile finding A2.1), and a repeated `(key, IV)`
 * over two DIFFERENT plaintexts breaks GCM outright.
 *
 * Nothing here is typed by hand: every expectation is a byte array or a base64
 * string read out of the fixture, and every regeneration runs the production
 * code path with a counting byte source standing in for the CSPRNG.
 *
 * Real Argon2id runs in this suite (that is the point of a conformance vector),
 * so it is deliberately slower than its siblings.
 */
class VaultV2ConformanceTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/v2.fixture.json")
            ?: error("vault-vectors/v2.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun family(name: String): JsonObject = fixture[name]!!.jsonObject

    private fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.o(key: String): JsonObject = this[key]!!.jsonObject
    private fun JsonObject.a(key: String): JsonArray = this[key]!!.jsonArray

    private fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)

    /**
     * The counting byte source the platform generator uses — `buildVectors.ts`
     * `counting(start)`. It is the ONLY source of "randomness" in the vectors.
     */
    private fun counting(start: Int): RandomBytes {
        var cursor = start
        return RandomBytes { length ->
            ByteArray(length) { index -> ((cursor + index) % 256).toByte() }
                .also { cursor = (cursor + length) % 256 }
        }
    }

    // Fixed inputs, mirroring `buildVectors.ts`.
    private val vectorVaultId = "4f6f3f1e-9f2a-4a53-9a6a-9b8f2f8c1a01"
    private val portfolioA = "11111111-1111-4111-8111-111111111111"
    private val portfolioB = "22222222-2222-4222-8222-222222222222"
    private val deviceId = "2f2f3f1e-9f2a-4a53-9a6a-9b8f2f8c1a02"
    private val writeId = "6f6f3f1e-9f2a-4a53-9a6a-9b8f2f8c1a03"
    private val writtenAt = "2026-08-08T09:00:00.000Z"

    // -----------------------------------------------------------------------
    // Family 1 — header derive / wrap / unwrap, including the r3 §21 mac
    // -----------------------------------------------------------------------

    @Test
    fun family1_headerDeriveWrapUnwrap() {
        val vector = family("v2Header")
        val built = buildVaultHeader(
            vaultId = vectorVaultId,
            name = "Drive vault",
            backends = "drive",
            passphrase = vector.s("passphrase"),
            deviceId = deviceId,
            writeId = writeId,
            writtenAt = writtenAt,
            portfolios = listOf(VaultPortfolioIndexEntry(portfolioA, "Tech")),
            randomBytes = counting(7),
        )

        // Byte-exact against the published oracle — the whole point.
        assertEquals(
            "family 1 header bytes",
            vector.s("headerBytesBase64"),
            bytesToBase64(encodeHeaderDoc(built.header)),
        )
        assertEquals(
            "family 1 mac tag",
            vector.o("header").o("mac").s("tag"),
            built.header.mac!!.tag,
        )
        assertEquals("family 1 content key", vector.s("contentKeyBase64"), bytesToBase64(built.contentKey))

        // The frozen fixture parses through the port's own contract…
        val header = VaultHeaderDoc.parse(vector.o("header"))
        assertEquals("re-encoding the parsed fixture header round-trips",
            vector.s("headerBytesBase64"), bytesToBase64(encodeHeaderDoc(header)))

        // …and it opens: verified seal plus the pinned content key.
        val opened = openVaultHeader(header, vector.s("passphrase"))
        assertEquals(VaultHeaderSealState.VERIFIED, opened.sealState)
        assertEquals(vector.s("contentKeyBase64"), bytesToBase64(opened.contentKey))
        assertEquals(header.keySlots[0].slotId, opened.slotId)
    }

    /**
     * r3 §21 fails CLOSED. A header whose index was relabelled by a blob store
     * keeps a valid-looking structure but no longer matches its tag, and that
     * must be an error rather than a survived edit.
     */
    @Test
    fun family1_tamperedIndexFailsTheSeal() {
        val vector = family("v2Header")
        val header = VaultHeaderDoc.parse(vector.o("header"))
        val relabelled = header.copy(
            portfolios = listOf(VaultPortfolioIndexEntry(portfolioA, "Not Tech")),
        )
        val error = runCatching { openVaultHeader(relabelled, vector.s("passphrase")) }.exceptionOrNull()
        assertTrue(
            "a relabelled portfolio index must fail the header seal, got $error",
            error is at.bettertrack.app.vault.VaultCryptoError &&
                error.code == at.bettertrack.app.vault.VaultCryptoErrorCode.AUTHENTICATION_FAILED,
        )
    }

    /** The transported bytes are schema-ordered; the MAC input is key-sorted. */
    @Test
    fun family1_macInputIsCanonicalNotWireOrder() {
        val header = VaultHeaderDoc.parse(family("v2Header").o("header"))
        val wire = String(encodeHeaderDoc(header), Charsets.UTF_8)
        val macInput = String(headerMacInputBytes(header), Charsets.UTF_8)
        assertTrue("the wire form leads with formatVersion", wire.startsWith("{\"formatVersion\":2,"))
        assertTrue("the mac input is sorted, so it leads with backends", macInput.startsWith("{\"backends\":"))
        assertTrue("the mac input excludes the mac member itself", !macInput.contains("\"mac\""))
        assertNotEquals("the two serializations are deliberately different", wire, macInput)
    }

    // -----------------------------------------------------------------------
    // Family 2 — multi-slot keySlots[]
    // -----------------------------------------------------------------------

    @Test
    fun family2_multiSlotEitherPhraseOpensTheSameContentKey() {
        val vector = family("v2MultiSlot")
        val built = buildVaultHeader(
            vaultId = vectorVaultId,
            name = "Shared vault",
            backends = "server",
            passphrase = vector.s("firstPassphrase"),
            deviceId = deviceId,
            writeId = writeId,
            writtenAt = writtenAt,
            randomBytes = counting(20),
        )
        val twoSlot = addPassphraseSlot(
            header = built.header,
            contentKey = built.contentKey,
            passphrase = vector.s("secondPassphrase"),
            deviceId = deviceId,
            writeId = writeId,
            writtenAt = writtenAt,
            randomBytes = counting(120),
        )
        assertEquals(
            "family 2 header bytes",
            vector.s("headerBytesBase64"),
            bytesToBase64(encodeHeaderDoc(twoSlot)),
        )

        val header = VaultHeaderDoc.parse(vector.o("header"))
        assertEquals("two key slots", 2, header.keySlots.size)
        val first = openVaultHeader(header, vector.s("firstPassphrase"))
        val second = openVaultHeader(header, vector.s("secondPassphrase"))
        assertEquals(vector.s("contentKeyBase64"), bytesToBase64(first.contentKey))
        assertEquals(vector.s("contentKeyBase64"), bytesToBase64(second.contentKey))
        assertEquals("each phrase opens its OWN slot", header.keySlots[0].slotId, first.slotId)
        assertEquals(header.keySlots[1].slotId, second.slotId)
    }

    /**
     * The slot AAD binds the slot INDEX, not only its id, so a blob store
     * cannot reorder `keySlots[]` and re-attribute a wrapped key.
     */
    @Test
    fun family2_reorderingSlotsBreaksTheUnwrap() {
        val vector = family("v2MultiSlot")
        val header = VaultHeaderDoc.parse(vector.o("header"))
        val swapped = header.copy(keySlots = header.keySlots.reversed(), mac = null)
        val error = runCatching { openVaultHeader(swapped, vector.s("firstPassphrase")) }.exceptionOrNull()
        assertTrue(
            "reordered key slots must not authenticate, got $error",
            error is at.bettertrack.app.vault.VaultCryptoError &&
                error.code == at.bettertrack.app.vault.VaultCryptoErrorCode.AUTHENTICATION_FAILED,
        )
    }

    // -----------------------------------------------------------------------
    // Family 3 — the per-portfolio split across all 26 entity kinds
    // -----------------------------------------------------------------------

    private fun vectorEntity(id: String, data: Map<String, String> = emptyMap()): VaultEntity =
        VaultEntity(
            id = id,
            rev = 1,
            editedAt = writtenAt,
            editedBy = deviceId,
            deletedAt = null,
            data = buildJsonObject { data.forEach { (key, value) -> put(key, JsonPrimitive(value)) } },
        )

    /** `vectorFullAccount()` — a v1 account touching every one of the 26 kinds. */
    private fun vectorFullAccount(): VaultDocument {
        val standingOrder = "aaaa1111-1111-4111-8111-111111111111"
        val importBatch = "bbbb2222-2222-4222-8222-222222222222"
        val cashMovement = "dddd4444-4444-4444-8444-444444444444"
        val cashBudget = "cccc3333-3333-4333-8333-333333333333"
        val cashTag = "66666666-6666-4666-8666-666666666666"
        val cashRule = "a4444444-4444-4444-8444-444444444445"
        val expenseCategory = "a5555555-5555-4555-8555-555555555556"
        val expenseBudget = "a6666666-6666-4666-8666-666666666667"
        val entities = linkedMapOf(
            "portfolio" to listOf(
                vectorEntity(portfolioA, mapOf("name" to "Tech")),
                vectorEntity(portfolioB, mapOf("name" to "Pension")),
            ),
            "transaction" to listOf(
                vectorEntity("a0000000-0000-4000-8000-000000000001", mapOf("portfolioId" to portfolioA)),
            ),
            "dividend" to listOf(
                vectorEntity("a0000000-0000-4000-8000-000000000002", mapOf("portfolioId" to portfolioB)),
            ),
            "cashSource" to listOf(
                vectorEntity("a0000000-0000-4000-8000-000000000003", mapOf("portfolioId" to portfolioA)),
            ),
            "cashMovement" to listOf(vectorEntity(cashMovement, mapOf("portfolioId" to portfolioB))),
            "cashMovementTag" to listOf(
                vectorEntity(
                    "a0000000-0000-4000-8000-000000000004",
                    mapOf("movementId" to cashMovement, "tagId" to cashTag),
                ),
            ),
            "portfolioSetting" to listOf(
                vectorEntity("a0000000-0000-4000-8000-000000000005", mapOf("portfolioId" to portfolioA)),
            ),
            "standingOrder" to listOf(vectorEntity(standingOrder, mapOf("portfolioId" to portfolioA))),
            "standingOrderRun" to listOf(
                vectorEntity(
                    "a0000000-0000-4000-8000-000000000006",
                    mapOf("standingOrderId" to standingOrder),
                ),
            ),
            "importBatch" to listOf(vectorEntity(importBatch, mapOf("portfolioId" to portfolioB))),
            "importRow" to listOf(
                vectorEntity("a0000000-0000-4000-8000-000000000007", mapOf("batchId" to importBatch)),
            ),
            "portfolioDailySnapshot" to listOf(
                vectorEntity("a0000000-0000-4000-8000-000000000008", mapOf("portfolioId" to portfolioA)),
            ),
            "portfolioSnapshotState" to listOf(
                vectorEntity("a0000000-0000-4000-8000-000000000009", mapOf("portfolioId" to portfolioB)),
            ),
            // common-scoped
            "taxSetting" to listOf(
                vectorEntity("b0000000-0000-4000-8000-000000000001", mapOf("mode" to "country")),
            ),
            "customAsset" to listOf(
                vectorEntity("b0000000-0000-4000-8000-000000000002", mapOf("symbol" to "PRIV")),
            ),
            "customAssetValue" to listOf(
                vectorEntity(
                    "b0000000-0000-4000-8000-000000000003",
                    mapOf("assetId" to "b0000000-0000-4000-8000-000000000002"),
                ),
            ),
            "cashTag" to listOf(vectorEntity(cashTag, mapOf("name" to "Rent"))),
            "cashRule" to listOf(vectorEntity(cashRule, mapOf("name" to "Groceries"))),
            "cashBudget" to listOf(vectorEntity(cashBudget, mapOf("tagId" to cashTag))),
            "expenseCategory" to listOf(vectorEntity(expenseCategory, mapOf("name" to "Food"))),
            "expenseRule" to listOf(
                vectorEntity("b0000000-0000-4000-8000-000000000004", mapOf("name" to "Fuel")),
            ),
            "expenseBudget" to listOf(
                vectorEntity(expenseBudget, mapOf("categoryId" to expenseCategory)),
            ),
            "expenseTransaction" to listOf(
                vectorEntity(
                    "b0000000-0000-4000-8000-000000000005",
                    mapOf("categoryId" to expenseCategory, "amount" to "10"),
                ),
            ),
            "expenseBudgetFire" to listOf(
                vectorEntity("b0000000-0000-4000-8000-000000000006", mapOf("budgetId" to expenseBudget)),
            ),
            "cashBudgetFire" to listOf(
                vectorEntity("b0000000-0000-4000-8000-000000000007", mapOf("budgetId" to cashBudget)),
            ),
            "cashRuleTag" to listOf(
                vectorEntity(
                    "b0000000-0000-4000-8000-000000000008",
                    mapOf("ruleId" to cashRule, "tagId" to cashTag),
                ),
            ),
        )
        return VaultDocument(
            schemaVersion = VaultContract.DOCUMENT_V1_VERSION,
            entities = entities,
            mergeLog = emptyList(),
            mirrorProvenance = null,
            clientSecurity = null,
        )
    }

    @Test
    fun family3_perPortfolioSplitAcrossAll26Kinds() {
        val vector = family("v2Partition")
        val split = splitVaultDocument(vectorFullAccount(), vectorVaultId)

        assertEquals(
            "coveredKinds is the whole contract kind list, in contract order",
            vector.a("coveredKinds").map { it.jsonPrimitive.content },
            VaultContract.ENTITY_KINDS.toList(),
        )
        assertEquals("26 covered kinds", 26, vector.a("coveredKinds").size)

        assertEquals(
            "commonKinds",
            vector.a("commonKinds").map { it.jsonPrimitive.content },
            split.commonDoc.entities.keys.sorted(),
        )
        assertEquals("13 common kinds", 13, vector.a("commonKinds").size)

        val expectedDocs = vector.a("portfolioDocs").map { it.jsonObject }
        assertEquals("portfolio doc count", expectedDocs.size, split.portfolioDocs.size)
        expectedDocs.forEachIndexed { index, expected ->
            val actual = split.portfolioDocs[index]
            assertEquals("portfolioDocs[$index].portfolioId", expected.s("portfolioId"), actual.portfolioId)
            assertEquals(
                "portfolioDocs[$index].kinds",
                expected.a("kinds").map { it.jsonPrimitive.content },
                actual.entities.keys.sorted(),
            )
        }

        val expectedIndex = vector.a("index").map { it.jsonObject }
        assertEquals("index size", expectedIndex.size, split.index.size)
        expectedIndex.forEachIndexed { position, expected ->
            assertEquals(expected.s("portfolioId"), split.index[position].portfolioId)
            assertEquals(expected.s("alias"), split.index[position].alias)
        }

        val report = vector.o("report")
        assertEquals("entitiesIn", report.i("entitiesIn"), split.report.entitiesIn)
        assertEquals("entitiesOut", report.i("entitiesOut"), split.report.entitiesOut)
        assertEquals("no orphans", 0, split.report.orphans.size)
        assertEquals("the fixture agrees there are no orphans", 0, report.a("orphans").size)
        assertEquals(
            "every entity in, exactly one entity out",
            split.report.entitiesIn,
            split.report.entitiesOut,
        )
    }

    /**
     * The two scopes must PARTITION every entity kind. A kind added to the
     * contract without a v2 scope would otherwise be routed as an orphan on
     * every migration — this is the assertion that makes the split's
     * never-loses-a-row guarantee real.
     */
    @Test
    fun family3_scopesPartitionEveryEntityKindExactly() {
        assertEquals("no unscoped kinds", emptySet<String>(), VaultV2Contract.UNSCOPED_KINDS)
        assertEquals(
            "the two scopes cover the contract exactly once",
            VaultContract.ENTITY_KINDS.size,
            VaultV2Contract.PORTFOLIO_SCOPED_KINDS.size + VaultV2Contract.COMMON_SCOPED_KINDS.size,
        )
        assertEquals(
            "the scopes do not overlap",
            emptySet<String>(),
            VaultV2Contract.PORTFOLIO_SCOPED_KINDS intersect VaultV2Contract.COMMON_SCOPED_KINDS,
        )
    }

    // -----------------------------------------------------------------------
    // Family 4 — the byte-exact migration transcript
    // -----------------------------------------------------------------------

    @Test
    fun family4_migrationTranscriptIsByteExact() {
        val vector = family("v2Migration")
        val legacyVaultKey = b64(vector.s("legacyVaultKeyBase64"))

        val contentKey = deriveMigrationContentKey(legacyVaultKey)
        assertEquals(
            "K_c = HKDF-SHA256(VK, \"btv2-migration-v1\", 32)",
            vector.s("contentKeyBase64"),
            bytesToBase64(contentKey),
        )

        val vaultId = deriveMigrationVaultId(vector.s("scopeId"))
        assertEquals("derived vault id", vector.s("derivedVaultId"), vaultId)

        val headerMaterial = deriveMigrationHeaderMaterial(contentKey)
        val built = buildVaultHeader(
            vaultId = vaultId,
            name = "My vault",
            backends = "server",
            // r2 §9: a v1-migrated vault keeps its legacy free-text passphrase.
            passphrase = family("v2Header").s("passphrase"),
            legacyPassphrase = true,
            contentKey = contentKey,
            kdfSalt = bytesToBase64(ByteArray(16) { (it + 1).toByte() }),
            portfolios = listOf(VaultPortfolioIndexEntry(portfolioA, "Tech")),
            deviceId = deviceId,
            writeId = writeId,
            writtenAt = writtenAt,
            randomBytes = migrationHeaderRandom(headerMaterial),
        )
        assertEquals(
            "family 4 successor header bytes",
            vector.s("headerBytesBase64"),
            bytesToBase64(encodeHeaderDoc(built.header)),
        )

        val document = VaultDocument(
            schemaVersion = VaultContract.DOCUMENT_V1_VERSION,
            entities = linkedMapOf(
                "portfolio" to listOf(vectorEntity(portfolioA, mapOf("name" to "Tech"))),
                "transaction" to listOf(
                    vectorEntity("c0000000-0000-4000-8000-000000000001", mapOf("portfolioId" to portfolioA)),
                ),
                "customAsset" to listOf(
                    vectorEntity("c0000000-0000-4000-8000-000000000002", mapOf("symbol" to "AAA")),
                ),
            ),
            mergeLog = emptyList(),
            mirrorProvenance = null,
            clientSecurity = null,
        )
        val split = splitVaultDocument(document, vaultId)
        val blobs = buildMigrationBlobs(split, contentKey, writtenAt)

        assertEquals(
            "family 4 common envelope",
            vector.s("commonEnvelopeBase64"),
            bytesToBase64(blobs.common.envelope),
        )
        val expectedPortfolios = vector.a("portfolioEnvelopes").map { it.jsonObject }
        assertEquals("portfolio envelope count", expectedPortfolios.size, blobs.portfolios.size)
        expectedPortfolios.forEachIndexed { index, expected ->
            val (portfolioId, blob) = blobs.portfolios[index]
            assertEquals(expected.s("portfolioId"), portfolioId)
            assertEquals(
                "family 4 portfolio envelope [$index]",
                expected.s("envelopeBase64"),
                bytesToBase64(blob.envelope),
            )
        }

        // The transcript's blobs decrypt under the derived K_c.
        val common = decryptVaultBlob(b64(vector.s("commonEnvelopeBase64")), contentKey)
        assertTrue("common doc kind", common.document is VaultContentDoc.Common)
        for (expected in expectedPortfolios) {
            val decrypted = decryptVaultBlob(b64(expected.s("envelopeBase64")), contentKey)
            val doc = decrypted.document as? VaultContentDoc.Portfolio
                ?: error("expected a portfolio doc")
            assertEquals(expected.s("portfolioId"), doc.portfolioId)
        }
    }

    /**
     * The migration is idempotent in BYTES, not just in addressing (r3 §18,
     * closing mobile A2.1). Running the whole derive→split→encrypt path twice
     * must produce identical ciphertext, or two racing claim holders write
     * mutually undecryptable blobs under one identity.
     */
    @Test
    fun family4_migrationWritesAreByteIdempotent() {
        val vector = family("v2Migration")
        val legacyVaultKey = b64(vector.s("legacyVaultKeyBase64"))
        val document = VaultDocument(
            schemaVersion = VaultContract.DOCUMENT_V1_VERSION,
            entities = linkedMapOf(
                "portfolio" to listOf(vectorEntity(portfolioA, mapOf("name" to "Tech"))),
            ),
            mergeLog = emptyList(),
            mirrorProvenance = null,
            clientSecurity = null,
        )

        fun writeOnce(): String {
            val contentKey = deriveMigrationContentKey(legacyVaultKey)
            val vaultId = deriveMigrationVaultId(vector.s("scopeId"))
            val blobs = buildMigrationBlobs(splitVaultDocument(document, vaultId), contentKey, writtenAt)
            return bytesToBase64(blobs.common.envelope)
        }
        assertEquals("two claim holders write identical bytes", writeOnce(), writeOnce())
    }

    /** The deterministic writer identity is itself derived, and vector-pinned. */
    @Test
    fun family4_writerIdentityIsDerived() {
        val vector = family("v2Migration")
        val contentKey = b64(vector.s("contentKeyBase64"))
        val decoded = decodeVaultBlob(b64(vector.s("commonEnvelopeBase64")))
        assertEquals(
            "deviceId = uuid(HKDF(K_c, \"btv2-migration-device\", 16))",
            decoded.header.deviceId,
            deriveMigrationDeviceId(contentKey),
        )
        assertEquals(
            "writeId = uuid(HKDF(K_c, \"btv2-migration-write\" ‖ docId, 16))",
            decoded.header.writeId,
            deriveMigrationWriteId(contentKey, "common"),
        )
        assertEquals(
            "iv = HKDF(K_c, \"btv2-migration-iv\" ‖ docId, 12)",
            decoded.header.iv,
            bytesToBase64(deriveMigrationIv(contentKey, "common")),
        )
    }

    // -----------------------------------------------------------------------
    // Family 5 — the v2 recovery kit
    // -----------------------------------------------------------------------

    @Test
    fun family5_recoveryKitV2() {
        val vector = family("v2RecoveryKit")
        val kit = serializeRecoveryKitV2(
            vaultId = vector.s("vaultId"),
            vaultName = vector.s("vaultName"),
            backends = vector.s("backends"),
            passphrase = vector.s("passphrase"),
        )
        assertEquals("family 5 kit bytes", vector.s("kitBase64"), bytesToBase64(kit.bytes))
        assertEquals(RECOVERY_KIT_V2_FILENAME, kit.filename)

        val imported = importRecoveryKitV2(b64(vector.s("kitBase64")))
        assertEquals(VaultV2Contract.HEADER_FORMAT_VERSION, imported.formatVersion)
        assertEquals(vector.s("vaultId"), imported.vaultId)
        assertEquals(vector.s("vaultName"), imported.vaultName)
        assertEquals(vector.s("backends"), imported.backends)
        assertEquals(vector.s("passphrase"), imported.passphrase)
    }

    // -----------------------------------------------------------------------
    // Family 6 — the canonical QR string + code KDF
    // -----------------------------------------------------------------------

    @Test
    fun family6_canonicalQrStringAndCodeKdf() {
        val vector = family("v2Qr")
        val payload = buildVaultQrPayload(
            vaultId = vector.s("vaultId"),
            name = vector.s("name"),
            passphrase = family("v2Header").s("passphrase"),
            code = vector.s("code"),
            randomBytes = counting(5),
        )
        assertEquals("family 6 canonical QR payload", vector.s("payload"), payload)

        val parsed = parseVaultQrPayload(vector.s("payload"))
        assertTrue("the canonical QR payload must parse", parsed is VaultQrParseResult.Ok)
        val unwrapped = unwrapVaultQrPayload(
            (parsed as VaultQrParseResult.Ok).payload,
            vector.s("code"),
        )
        assertEquals(
            VaultQrUnwrapResult.Ok(family("v2Header").s("passphrase")),
            unwrapped,
        )
    }

    /** r3 §19 sized the code at exactly 40 bits of Crockford base32. */
    @Test
    fun family6_codeNormalizationIsCrockford() {
        val vector = family("v2Qr")
        val code = vector.s("code")
        assertEquals(8, VaultV2Contract.QR_CODE_LENGTH)
        assertEquals(40, VaultV2Contract.QR_CODE_BITS)
        assertEquals("the alphabet excludes I, L, O and U", 32, VaultV2Contract.QR_CODE_ALPHABET.length)
        assertTrue(VaultV2Contract.QR_CODE_ALPHABET.none { it in "ILOU" })

        // Case, separators and the classic confusions all canonicalize to one form.
        assertEquals(code, normalizeQrCode(code.lowercase()))
        assertEquals(code, normalizeQrCode(formatQrCode(code)))
        assertEquals(code, normalizeQrCode(code.replace('1', 'I')))
        assertEquals(code, normalizeQrCode(code.replace('0', 'O')))
        assertEquals("a short code is not a code", null, normalizeQrCode("ABC"))
    }

    /** A wrong code is indistinguishable from a corrupted `w`. */
    @Test
    fun family6_wrongCodeIsOpaque() {
        val vector = family("v2Qr")
        val parsed = parseVaultQrPayload(vector.s("payload")) as VaultQrParseResult.Ok
        val wrong = unwrapVaultQrPayload(parsed.payload, "00000000")
        assertEquals(VaultQrUnwrapResult.Failed("code-wrong"), wrong)
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: the substrate the families stand on
    // -----------------------------------------------------------------------

    /** The wordlist is the canonical BIP-39 English list, byte for byte. */
    @Test
    fun bip39WordlistIsCanonical() {
        assertEquals(2048, BIP39_ENGLISH.size)
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            bip39EnglishDigestHex(),
        )
        assertEquals("abandon", BIP39_ENGLISH.first())
        assertEquals("zoo", BIP39_ENGLISH.last())
    }

    /** Every fixture passphrase is a checksum-valid 12-word phrase. */
    @Test
    fun fixturePassphrasesPassTheChecksum() {
        for (phrase in listOf(
            family("v2Header").s("passphrase"),
            family("v2MultiSlot").s("firstPassphrase"),
            family("v2MultiSlot").s("secondPassphrase"),
        )) {
            assertTrue(
                "'$phrase' must be a valid 12-word phrase",
                checkVaultPassphrase(phrase) is VaultPassphraseCheck.Valid,
            )
        }
        // A single-word swap breaks the checksum rather than passing silently.
        val tampered = family("v2Header").s("passphrase").replace("yellow", "zoo")
        assertTrue(
            "a swapped last word must fail the checksum",
            checkVaultPassphrase(tampered) is VaultPassphraseCheck.Invalid,
        )
    }

    /** RFC 5869 test vector A.1, so the HKDF port is proven independently. */
    @Test
    fun hkdfMatchesRfc5869VectorA1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = hkdfSha256(ikm, info, 42, salt)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }
}
