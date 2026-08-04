package at.bettertrack.app.vault

import at.bettertrack.app.data.db.VaultMetaKeys
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive-mode working store: version discipline, the merge metadata a
 * document round trip must not lose, and the payload shapes this client authors.
 *
 * The single most important assertion in this file is that **every mutation
 * bumps `vaultVersion` exactly once, atomically**. That counter is the CAS token
 * every medium compares against ([DataHome.write]'s `ifVersion`) and the input to
 * merge rule 3. A mutation that changed entities without bumping it would let two
 * different documents share one token — after which a Drive push can overwrite
 * another device's work while every check in the system passes.
 */
class VaultStoreTest {

    private val portfolioId = "018f0000-0000-7000-8000-0000000001aa"

    // ── Version discipline ──────────────────────────────────────────────────

    @Test
    fun bumpsTheVaultVersionExactlyOncePerMutation() = runBlocking {
        val store = testVaultStore()
        assertEquals("a fresh vault starts at 1", 1, store.vaultVersion())

        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }
        assertEquals(2, store.vaultVersion())

        // Several entities in ONE mutation is still one version.
        store.mutate { graph, context ->
            graph.create(VaultKinds.CASH_SOURCE, "018f0000-0000-7000-8000-0000000001bb", VaultPayloads.cashSource(portfolioId, "Main", "cash", true, context.now), context.now, context.deviceId)
            graph.create(VaultKinds.CASH_SOURCE, "018f0000-0000-7000-8000-0000000001cc", VaultPayloads.cashSource(portfolioId, "Savings", "bank", false, context.now), context.now, context.deviceId)
        }
        assertEquals("one mutation, one version", 3, store.vaultVersion())
    }

    /**
     * The block gets a COPY of the graph, so a domain refusal — which is exactly
     * what `VaultOpExecutor` lets propagate — leaves nothing written and the
     * version unmoved.
     */
    @Test
    fun leavesNothingBehindWhenAMutationThrows() = runBlocking {
        val dao = FakeVaultDao()
        val store = testVaultStore(dao)
        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }
        val versionBefore = store.vaultVersion()
        val replaceCountBefore = dao.replaceCount

        val thrown = runCatching {
            store.mutate<Unit> { graph, context ->
                graph.create(VaultKinds.CASH_SOURCE, "018f0000-0000-7000-8000-0000000001dd", VaultPayloads.cashSource(portfolioId, "Doomed", "cash", false, context.now), context.now, context.deviceId)
                throw IllegalStateException("a domain refusal")
            }
        }

        assertTrue("the refusal propagates to the caller", thrown.isFailure)
        assertEquals("the CAS token did not move", versionBefore, store.vaultVersion())
        assertEquals("nothing was persisted", replaceCountBefore, dao.replaceCount)
        assertEquals("and the doomed entity is not there", 0, store.snapshot().graph.live(VaultKinds.CASH_SOURCE).size)
    }

    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * `deviceId` is merge rule 1's final tie-break and the `editedBy` of every
     * row this install writes, so it must be minted once and never change.
     */
    @Test
    fun mintsAStableDeviceIdAndVaultAccountId() = runBlocking {
        val dao = FakeVaultDao()
        val store = testVaultStore(dao)

        val deviceId = store.deviceId()
        assertEquals("stable within an instance", deviceId, store.deviceId())
        assertEquals("and across a cold start", deviceId, testVaultStore(dao).deviceId())

        val accountId = store.vaultAccountId()
        assertEquals("the Drive file name must not move", accountId, testVaultStore(dao).vaultAccountId())
        assertFalse("they are separate identities", deviceId == accountId)
    }

    @Test
    fun stampsEveryWrittenRowWithTheDeviceId() = runBlocking {
        val store = testVaultStore()
        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }
        assertEquals(store.deviceId(), store.snapshot().graph.live(VaultKinds.PORTFOLIO).single().editedBy)
    }

    // ── Rev / tombstone discipline ──────────────────────────────────────────

    /**
     * An edit that left `rev` alone would tie with the other device's copy and
     * fall through to the `editedAt` tie-break, where a skewed clock can lose an
     * edit made later.
     */
    @Test
    fun bumpsRevOnEveryEdit() = runBlocking {
        val store = testVaultStore()
        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }
        assertEquals("a freshly authored row starts at rev 0", 0, store.snapshot().graph.live(VaultKinds.PORTFOLIO).single().rev)

        repeat(3) { index ->
            store.mutate { graph, context ->
                graph.edit(VaultKinds.PORTFOLIO, portfolioId, context.now, context.deviceId) { data ->
                    JsonObject(LinkedHashMap(data).apply { put("name", JsonPrimitive("rename-$index")) })
                }
            }
        }
        val row = store.snapshot().graph.live(VaultKinds.PORTFOLIO).single()
        assertEquals(3, row.rev)
        assertEquals("rename-2", row.text("name"))
    }

    /**
     * A row removed rather than tombstoned would be silently resurrected by the
     * next merge with a device that still has it — merge rule 1 has nothing to
     * compare against an absent id.
     */
    @Test
    fun tombstonesRatherThanRemoving() = runBlocking {
        val store = testVaultStore()
        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }
        store.mutate { graph, context -> graph.tombstone(VaultKinds.PORTFOLIO, portfolioId, context.now, context.deviceId) }

        val graph = store.snapshot().graph
        assertEquals("invisible to every projection", 0, graph.live(VaultKinds.PORTFOLIO).size)
        val tombstone = graph.all(VaultKinds.PORTFOLIO).single()
        assertNotNull("but the row and its deletion survive", tombstone.deletedAt)
        assertEquals("with a rev that beats the pre-delete copy", 1, tombstone.rev)
    }

    // ── Document round trip ─────────────────────────────────────────────────

    /**
     * The app never authors `clientSecurity` (it writes v1 documents, board
     * #40.2) — but a vault the web PWA upgraded to v2 must survive an Android
     * read/edit/write cycle with its proof material intact. Losing it would
     * silently destroy the user's ability to retire their server medium.
     */
    @Test
    fun preservesAV2DocumentsProofMaterialThroughAnEditCycle() = runBlocking {
        val store = testVaultStore()
        val clientSecurity = JsonObject(mapOf("proof" to JsonPrimitive("browser-only material")))
        val theirs = VaultDocument(
            schemaVersion = VaultContract.DOCUMENT_VERSION,
            entities = mapOf(
                VaultKinds.PORTFOLIO to listOf(
                    VaultEntity(portfolioId, 0, "2026-08-04T09:00:00.000Z", "018f0000-0000-7000-8000-0000000002aa", null, VaultPayloads.portfolio(null, "From the web")),
                )
            ),
            mergeLog = emptyList(),
            mirrorProvenance = null,
            clientSecurity = clientSecurity,
        )

        store.adopt(theirs, vaultVersion = 12)
        store.mutate { graph, context ->
            graph.edit(VaultKinds.PORTFOLIO, portfolioId, context.now, context.deviceId) { data ->
                JsonObject(LinkedHashMap(data).apply { put("name", JsonPrimitive("Renamed on Android")) })
            }
        }

        val document = store.document()
        assertEquals("the schema version is carried, never rewritten", VaultContract.DOCUMENT_VERSION, document.schemaVersion)
        assertEquals("the proof material is re-emitted verbatim", clientSecurity, document.clientSecurity)
        assertEquals("Renamed on Android", document.entities.getValue(VaultKinds.PORTFOLIO).single().text("name"))
        assertEquals("the adopted version is honoured, then bumped once", 13, store.vaultVersion())
    }

    /**
     * `mirrorProvenance` is `.optional()` with NO default. Re-emitting an absent
     * one as `[]` would change the plaintext — and therefore the envelope bytes —
     * of every fork-free vault in existence.
     */
    @Test
    fun keepsAnAbsentMirrorProvenanceAbsent() = runBlocking {
        val store = testVaultStore()
        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }
        assertNull(store.document().mirrorProvenance)
        assertFalse(
            "and it does not appear in the serialized document either",
            store.document().toJson().containsKey("mirrorProvenance"),
        )
    }

    @Test
    fun carriesTheMergeLogAcrossAnAdoptAndEdit() = runBlocking {
        val store = testVaultStore()
        val record = VaultMergeRecord(
            mergedAt = "2026-08-04T10:00:00.000Z",
            parents = listOf(3, 4),
            into = 5,
            deviceId = "018f0000-0000-7000-8000-0000000002bb",
        )
        store.adopt(VaultDocument.v1(entities = emptyMap(), mergeLog = listOf(record)), vaultVersion = 5)

        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }
        assertEquals("the merge history survives the next edit", listOf(record), store.document().mergeLog)
    }

    /** A merged document replaces the graph wholesale, never merges into it. */
    @Test
    fun adoptReplacesTheGraphRatherThanUnioningIt() = runBlocking {
        val store = testVaultStore()
        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "Local"), context.now, context.deviceId)
        }

        val replacement = "018f0000-0000-7000-8000-0000000002cc"
        store.adopt(
            VaultDocument.v1(
                entities = mapOf(
                    VaultKinds.PORTFOLIO to listOf(
                        VaultEntity(replacement, 0, "2026-08-04T09:00:00.000Z", "018f0000-0000-7000-8000-0000000002dd", null, VaultPayloads.portfolio(null, "Merged")),
                    )
                )
            ),
            vaultVersion = 9,
        )

        val ids = store.snapshot().graph.all(VaultKinds.PORTFOLIO).map { it.id }
        assertEquals("only the merged document's entities remain", listOf(replacement), ids)
        assertEquals(9, store.vaultVersion())
    }

    @Test
    fun wipeClearsEverything() = runBlocking {
        val store = testVaultStore()
        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }
        store.putMeta(VaultMetaKeys.LAST_PUSHED_VERSION, "7")

        store.wipe()

        assertEquals(0, store.snapshot().graph.all(VaultKinds.PORTFOLIO).size)
        assertNull(store.meta(VaultMetaKeys.LAST_PUSHED_VERSION))
        assertEquals("a wiped vault reads as fresh", 1, store.vaultVersion())
    }

    // ── Payload shapes ──────────────────────────────────────────────────────

    /**
     * The member sets below are read off the platform's own published
     * `clientMoney.fixture.json`, decrypted. The web PWA validates each kind with
     * zod, so a payload missing `taxMode` or spelling `type` where the contract
     * says `side` is a row the other client refuses to load — in a vault only the
     * user can decrypt, with no server anywhere to notice.
     */
    @Test
    fun authorsTheSameMemberSetsThePublishedVaultCarries() {
        val fixture = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/vault-vectors/clientMoney.fixture.json")!!
                .bufferedReader().use { it.readText() },
        ).jsonObject
        val published = decryptVaultDocument(
            Base64.getDecoder().decode(fixture["envelopeBase64"]!!.jsonPrimitive.content),
            Base64.getDecoder().decode(fixture["vaultKeyBase64"]!!.jsonPrimitive.content),
        ).document

        fun publishedMembers(kind: String): Set<String> =
            published.entities.getValue(kind).first().data.keys

        assertEquals(
            "portfolio",
            publishedMembers(VaultKinds.PORTFOLIO),
            VaultPayloads.portfolio(userId = null, name = "x").keys,
        )
        assertEquals(
            "cashSource",
            publishedMembers(VaultKinds.CASH_SOURCE),
            VaultPayloads.cashSource("p", "Main", "cash", true, "2026-08-04T10:00:00.000Z").keys,
        )
        assertEquals(
            "transaction",
            publishedMembers(VaultKinds.TRANSACTION),
            VaultPayloads.transaction("p", "a", "buy", 1.0, 2.0, 0.0, "2026-08-04T10:00:00.000Z").keys,
        )
        assertEquals(
            "cashMovement",
            publishedMembers(VaultKinds.CASH_MOVEMENT),
            VaultPayloads.cashMovement("p", "s", "deposit", 1.0, "2026-08-04T10:00:00.000Z", "2026-08-04T10:00:00.000Z").keys,
        )
        assertEquals(
            "customAsset",
            publishedMembers(VaultKinds.CUSTOM_ASSET),
            VaultPayloads.customAsset(null, "stock", "S", "N", "EUR").keys,
        )
    }

    /**
     * Money is a decimal STRING, spelled the way the platform spells it: the
     * fixture's own `quantity` is `"10"`, not `"10.0"`. The string IS the value —
     * two clients that disagree on the spelling produce different payloads for
     * the same trade, and every content-addressed merge comparison then reports a
     * difference where there is none.
     */
    @Test
    fun writesMoneyTheWayThePlatformSpellsIt() {
        val transaction = VaultPayloads.transaction("p", "a", "buy", 10.0, 100.5, 0.0, "2026-08-04T10:00:00.000Z")
        assertEquals("10", transaction["quantity"]!!.jsonPrimitive.content)
        assertEquals("100.5", transaction["price"]!!.jsonPrimitive.content)
        assertEquals("0", transaction["fee"]!!.jsonPrimitive.content)

        val movement = VaultPayloads.cashMovement("p", "s", "withdrawal", -200.0, "2026-08-04T10:00:00.000Z", "2026-08-04T10:00:00.000Z")
        assertEquals("-200", movement["amountEur"]!!.jsonPrimitive.content)
        assertEquals(
            "sub-cent precision is preserved, never rounded here",
            "0.015",
            VaultPayloads.customAssetValue("a", "2026-08-04", 0.015)["value"]!!.jsonPrimitive.content,
        )
    }

    /** An unknown kind must round-trip untouched — the app models six of 26. */
    @Test
    fun carriesKindsItDoesNotModelThroughUnchanged() = runBlocking {
        val store = testVaultStore()
        val reserved = VaultEntity(
            id = "018f0000-0000-7000-8000-0000000003aa",
            rev = 4,
            editedAt = "2026-08-04T09:00:00.000Z",
            editedBy = "018f0000-0000-7000-8000-0000000003bb",
            deletedAt = null,
            data = JsonObject(mapOf("nextRunAt" to JsonPrimitive("2026-09-01"), "amountEur" to JsonPrimitive("50"))),
        )
        store.adopt(VaultDocument.v1(entities = mapOf("standingOrder" to listOf(reserved))), vaultVersion = 4)

        store.mutate { graph, context ->
            graph.create(VaultKinds.PORTFOLIO, portfolioId, VaultPayloads.portfolio(null, "A"), context.now, context.deviceId)
        }

        val carried = store.document().entities.getValue("standingOrder").single()
        assertEquals("every field survives, including ones this build has no column for", reserved.data, carried.data)
        assertEquals(4, carried.rev)
    }
}
