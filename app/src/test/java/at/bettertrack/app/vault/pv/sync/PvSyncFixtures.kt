package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.vault.VaultEntity
import at.bettertrack.app.vault.pv.envelope.PvClientSecurity
import at.bettertrack.app.vault.pv.envelope.PvCommonDoc
import at.bettertrack.app.vault.pv.envelope.PvDocEnvelopeHeader
import at.bettertrack.app.vault.pv.envelope.PvDocEnvelopeInspection
import at.bettertrack.app.vault.pv.envelope.PvDocWrite
import at.bettertrack.app.vault.pv.envelope.PvHeaderDoc
import at.bettertrack.app.vault.pv.envelope.PvHeaderPortfolio
import at.bettertrack.app.vault.pv.envelope.PvKeySlot
import at.bettertrack.app.vault.pv.envelope.PvPortfolioDoc
import at.bettertrack.app.vault.pv.envelope.PvRetirementProof
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.envelope.PvVaultCreation
import at.bettertrack.app.vault.pv.envelope.PvVaultDoc
import at.bettertrack.app.vault.pv.envelope.encryptPvDoc
import at.bettertrack.app.vault.pv.envelope.inspectPvDocEnvelope
import at.bettertrack.app.vault.pv.envelope.pvAccountBinding
import at.bettertrack.app.vault.pv.store.PvDocEtag
import at.bettertrack.app.vault.pv.store.PvDocPrecondition
import at.bettertrack.app.vault.pv.store.PvDocReadOutcome
import at.bettertrack.app.vault.pv.store.PvDocRef
import at.bettertrack.app.vault.pv.store.PvDocWriteOutcome
import at.bettertrack.app.vault.pv.store.PvVaultDocDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared fixtures for the per-vault sync suite.
 *
 * The doubles here are deliberately *behavioural*, not stubs that return canned
 * outcomes: [FakeMedium] enforces the real CAS rules (a create against an
 * existing doc is a `412`, a replace against a stale validator is a `412`, a
 * replace against a missing doc is the deployed `404`), so a test that passes
 * has exercised the protocol rather than a mock's opinion of it. The scripted
 * hooks only cover what a real store cannot be asked to do on demand — losing a
 * response, or handing back a foreign envelope.
 */

internal const val VAULT_ID = "018f0000-0000-7000-8000-000000000001"
internal const val OTHER_VAULT_ID = "018f0000-0000-7000-8000-000000000002"
internal const val HEADER_DOC_ID = "018f0000-0000-7000-8000-0000000000a1"
internal const val COMMON_DOC_ID = "018f0000-0000-7000-8000-0000000000a2"
internal const val PORTFOLIO_A = "018f0000-0000-7000-8000-0000000000b1"
internal const val PORTFOLIO_B = "018f0000-0000-7000-8000-0000000000b2"
internal const val DEVICE_A = "018f0000-0000-7000-8000-0000000000d1"
internal const val DEVICE_B = "018f0000-0000-7000-8000-0000000000d2"
internal const val KEY_ID = "018f0000-0000-7000-8000-0000000000e1"
internal const val ACCOUNT_ID = "018f0000-0000-7000-8000-0000000000f1"

internal val DIRECTORY = PvVaultDocDirectory(VAULT_ID, HEADER_DOC_ID, COMMON_DOC_ID)
internal val REF_HEADER: PvDocRef = DIRECTORY.header
internal val REF_COMMON: PvDocRef = DIRECTORY.common
internal val REF_PORTFOLIO_A: PvDocRef = DIRECTORY.portfolio(PORTFOLIO_A)
internal val REF_PORTFOLIO_B: PvDocRef = DIRECTORY.portfolio(PORTFOLIO_B)

internal val ACCOUNT_BINDING: String = pvAccountBinding(ACCOUNT_ID)

internal fun contentKey(seed: Int = 7): ByteArray = ByteArray(32) { (seed + it).toByte() }

internal fun keySlots(vararg keyIds: String = arrayOf(KEY_ID)): List<PvKeySlot> =
    keyIds.map { PvKeySlot(it, PvVaultContract.KEY_SLOT_SEED_V1, "d3JhcHBlZC1rZXktbWF0ZXJpYWw") }

/** A uuid built from a counter, so a test's `writeId`s are readable AND legal. */
internal fun countedUuid(prefix: String, n: Int): String =
    "018f0000-0000-7000-8000-%s%06d".format(prefix.padStart(6, '0').takeLast(6), n)

internal fun entity(
    id: String,
    rev: Int = 1,
    editedAt: String = "2026-09-01T10:00:00.000Z",
    editedBy: String = DEVICE_A,
    deletedAt: String? = null,
    payload: Int = rev,
): VaultEntity = VaultEntity(
    id = id,
    rev = rev,
    editedAt = editedAt,
    editedBy = editedBy,
    deletedAt = deletedAt,
    data = JsonObject(linkedMapOf("n" to JsonPrimitive(payload))),
)

internal fun portfolioDoc(
    portfolioId: String = PORTFOLIO_A,
    transactions: List<VaultEntity> = emptyList(),
): PvPortfolioDoc = PvPortfolioDoc(
    portfolioId = portfolioId,
    entities = if (transactions.isEmpty()) emptyMap() else mapOf("transaction" to transactions),
)

internal fun commonDoc(customAssets: List<VaultEntity> = emptyList()): PvCommonDoc = PvCommonDoc(
    entities = if (customAssets.isEmpty()) emptyMap() else mapOf("customAsset" to customAssets),
    clientSecurity = PvClientSecurity(
        PvRetirementProof(publicKey = "cHVibGljLWtleQ", privateKey = "cHJpdmF0ZS1rZXk"),
    ),
)

internal fun headerDoc(
    name: String = "Household",
    portfolios: List<PvHeaderPortfolio> = listOf(PvHeaderPortfolio(PORTFOLIO_A, "Main")),
    slots: List<PvKeySlot> = keySlots(),
    createdBy: String = DEVICE_A,
    createdAt: String = "2026-08-01T08:00:00.000Z",
): PvHeaderDoc = PvHeaderDoc(
    name = name,
    portfolios = portfolios,
    keySlots = slots,
    driveConnection = null,
    created = PvVaultCreation(createdAt, createdBy),
)

/** Seal one doc exactly as the engine would, for seeding a medium. */
internal fun seal(
    document: PvVaultDoc,
    docId: String,
    docVersion: Int,
    key: ByteArray = contentKey(),
    vaultId: String = VAULT_ID,
    deviceId: String = DEVICE_B,
    writeId: String = countedUuid("aa", docVersion),
    writtenAt: String = "2026-09-01T09:00:00.000Z",
): ByteArray = encryptPvDoc(
    document = document,
    contentKey = key,
    write = PvDocWrite(
        vaultId = vaultId,
        docId = docId,
        accountBinding = ACCOUNT_BINDING,
        keyId = KEY_ID,
        keySlots = keySlots(),
        docVersion = docVersion,
        deviceId = deviceId,
        writeId = writeId,
        writtenAt = writtenAt,
    ),
).envelope

internal fun headerOf(envelope: ByteArray): PvDocEnvelopeHeader =
    when (val inspected = inspectPvDocEnvelope(envelope)) {
        is PvDocEnvelopeInspection.Supported -> inspected.envelope.header
        is PvDocEnvelopeInspection.UpdateRequired -> error("fixture envelope is not readable")
    }

// ── Doubles ─────────────────────────────────────────────────────────────────

/**
 * A blind blob store with real CAS semantics.
 *
 * Every rule it enforces is one the deployed E1 route enforces; every hook it
 * offers is a condition a real store cannot be asked for on demand.
 */
internal class FakeMedium(
    override val vaultId: String = VAULT_ID,
    override val medium: PvMedium = PvMedium.SERVER,
) : PvDocMedium {

    private class Slot(val envelope: ByteArray, val etag: String, val docVersion: Int)

    private val slots = LinkedHashMap<String, Slot>()
    private var etagCounter = 0

    /** Every write this medium was ASKED to do, in order. */
    val writeAttempts = mutableListOf<String>()

    /** Every write it actually stored. */
    val committed = mutableListOf<String>()
    val readAttempts = mutableListOf<String>()
    var notModified = 0
        private set

    /** When set, every write returns this and state is untouched. */
    var refuseWrites: (() -> PvDocWriteOutcome)? = null

    /** Store the next write, then report the response as lost. */
    var swallowNextResponse = false

    /** How a REPLACE against a doc that is not there answers. */
    var missingReplace: () -> PvDocWriteOutcome =
        { PvDocWriteOutcome.Refused(BtApiError(404, "NOT_FOUND", "No such document.")) }

    /** Hijack a read entirely — a foreign envelope, a corrupt one, a refusal. */
    var readOverride: ((PvDocRef) -> PvDocReadOutcome?)? = null

    fun seed(ref: PvDocRef, envelope: ByteArray) {
        store(ref, envelope)
    }

    fun etagOf(docId: String): PvDocEtag? = slots[docId]?.let { PvDocEtag(it.etag) }

    fun versionOf(docId: String): Int? = slots[docId]?.docVersion

    fun envelopeOf(docId: String): ByteArray? = slots[docId]?.envelope

    private fun store(ref: PvDocRef, envelope: ByteArray): Slot {
        etagCounter++
        val slot = Slot(envelope.copyOf(), "\"$etagCounter\"", headerOf(envelope).docVersion)
        slots[ref.docId] = slot
        return slot
    }

    override suspend fun read(ref: PvDocRef, ifNoneMatch: PvDocEtag?): PvDocReadOutcome {
        readAttempts += ref.docId
        readOverride?.invoke(ref)?.let { return it }
        val held = slots[ref.docId] ?: return PvDocReadOutcome.Absent
        if (ifNoneMatch != null && ifNoneMatch.header == held.etag) {
            notModified++
            return PvDocReadOutcome.NotModified(PvDocEtag(held.etag))
        }
        return PvDocReadOutcome.Loaded(
            ref = ref,
            envelope = held.envelope,
            etag = PvDocEtag(held.etag),
            header = headerOf(held.envelope),
        )
    }

    override suspend fun write(
        ref: PvDocRef,
        precondition: PvDocPrecondition,
        envelope: ByteArray,
    ): PvDocWriteOutcome {
        writeAttempts += ref.docId
        refuseWrites?.let { return it() }
        val held = slots[ref.docId]
        when (precondition) {
            PvDocPrecondition.CreateOnly ->
                if (held != null) return PvDocWriteOutcome.PreconditionStale(PvDocEtag(held.etag))

            is PvDocPrecondition.Replace -> {
                if (held == null) return missingReplace()
                if (held.etag != precondition.etag.header) {
                    return PvDocWriteOutcome.PreconditionStale(PvDocEtag(held.etag))
                }
            }
        }
        val slot = store(ref, envelope)
        committed += ref.docId
        if (swallowNextResponse) {
            swallowNextResponse = false
            return PvDocWriteOutcome.Transport(BtApiError(0, BtApiError.Codes.NETWORK, "Connection reset."))
        }
        return PvDocWriteOutcome.Written(PvDocEtag(slot.etag), slot.docVersion)
    }
}

/**
 * The local doc set, with the version discipline the engine relies on — keyed by
 * vault, because the whole point of this round is that two vaults are two
 * independent things.
 */
internal class FakeLocalStore(private val directory: PvVaultDocDirectory = DIRECTORY) : PvVaultLocalStore {

    private val byVault = LinkedHashMap<String, LinkedHashMap<String, PvLocalDoc>>()
    val candidates = mutableListOf<PvRejectedCandidate>()
    val adoptions = mutableListOf<PvLocalDoc>()

    fun docs(vaultId: String = VAULT_ID): LinkedHashMap<String, PvLocalDoc> =
        byVault.getOrPut(vaultId) { LinkedHashMap() }

    fun doc(ref: PvDocRef, vaultId: String = VAULT_ID): PvLocalDoc? = docs(vaultId)[ref.docId]

    /** Seed state that is already agreed with a medium (no version bump). */
    fun seed(ref: PvDocRef, document: PvVaultDoc, docVersion: Int, vaultId: String = VAULT_ID) {
        docs(vaultId)[ref.docId] = PvLocalDoc(ref, document, docVersion)
    }

    /** A local commit: new content, version + 1 — the contract [PvLocalDoc] states. */
    fun edit(ref: PvDocRef, document: PvVaultDoc, vaultId: String = VAULT_ID) {
        docs(vaultId)[ref.docId] = PvLocalDoc(ref, document, (docs(vaultId)[ref.docId]?.docVersion ?: 0) + 1)
    }

    override suspend fun snapshot(vaultId: String): PvVaultSnapshot =
        PvVaultSnapshot(vaultId, directory, docs(vaultId).values.toList())

    override suspend fun adopt(vaultId: String, doc: PvLocalDoc) {
        docs(vaultId)[doc.ref.docId] = doc
        adoptions += doc
    }

    override suspend fun keepCandidate(candidate: PvRejectedCandidate) {
        candidates += candidate
    }
}

internal class FakeKeys(
    private val key: ByteArray = contentKey(),
    private val slots: List<PvKeySlot> = keySlots(),
) : PvVaultKeys {

    var locked = false

    override suspend fun unlocked(vaultId: String): PvUnlockedVault? {
        if (locked) return null
        // A fresh copy every pass: the engine zeroes what it is handed, which is
        // the §12 "never cached across sessions" discipline in miniature.
        return PvUnlockedVault(key.copyOf(), KEY_ID, slots, ACCOUNT_BINDING)
    }
}
