package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.vault.MergeVaultDocumentsInput
import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.VaultDocument
import at.bettertrack.app.vault.canonicalJson
import at.bettertrack.app.vault.mergeVaultDocuments
import at.bettertrack.app.vault.pv.envelope.PvClientSecurity
import at.bettertrack.app.vault.pv.envelope.PvCommonDoc
import at.bettertrack.app.vault.pv.envelope.PvDriveConnectionEcho
import at.bettertrack.app.vault.pv.envelope.PvHeaderDoc
import at.bettertrack.app.vault.pv.envelope.PvHeaderPortfolio
import at.bettertrack.app.vault.pv.envelope.PvKeySlot
import at.bettertrack.app.vault.pv.envelope.PvPortfolioDoc
import at.bettertrack.app.vault.pv.envelope.PvVaultDoc

/**
 * **§6's conflict rule, applied per doc.**
 *
 * The rule itself is not restated here and is not re-implemented here. It is
 * `VaultMerge.kt` — the platform-canonical port of `apps/web/src/user/vault/
 * merge.ts`, entity-atomic, with the ordering `rev` → live-beats-tombstone →
 * `editedAt` → `editedBy` → canonical content, `max(parents) + 1` for the merged
 * version, a bounded merge log and a content-addressed fork-provenance union.
 * Re-deriving any of that for the per-vault rail would be two implementations of
 * one binding rule, and the second one would be wrong the first time the first
 * one changed.
 *
 * What this file does is the ADAPTATION the redefinition needs: v1 merged one
 * document per account, and §5 splits a vault into `header` + `common` + one doc
 * per member portfolio. So each pair is mapped onto the shape `VaultMerge`
 * already speaks, merged, and mapped back:
 *
 * | doc kind    | mapped onto                                          | why |
 * | ----------- | ---------------------------------------------------- | --- |
 * | `portfolio` | `VaultDocument` v1 (entities + mergeLog)             | the payload IS an entity graph; no `clientSecurity` exists for a portfolio |
 * | `common`    | `VaultDocument` v2 (+ mirrorProvenance, clientSecurity) | same graph plus exactly the two members v2 was invented to carry |
 * | `header`    | nothing — see [mergeHeaderDocs]                      | a roster and a name are not entities and have no `rev` |
 *
 * The mapping is lossless in both directions, which is what lets the merged
 * result be re-parsed under the pv schema rather than trusted.
 *
 * ## Commutativity and idempotence survive the split
 *
 * They have to, because they are what makes a lost CAS race safe to simply
 * retry. The two entity-carrying kinds inherit them from `VaultMerge` unchanged;
 * [mergeHeaderDocs] is written to have them by construction (every field
 * resolves by a total order over the two sides, never by "left wins"), and
 * `PvDocMergeTest` holds all three with seeded property-style cases.
 */

/** One merged doc, and whether a new CAS successor has to be written. */
data class PvMergedDoc(
    val document: PvVaultDoc,
    val docVersion: Int,
    val divergent: Boolean,
)

private fun mergeInvalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.DOCUMENT_INVALID, message)

/**
 * Merge two versions of the SAME doc.
 *
 * A kind mismatch is fatal rather than resolved: two payloads of different kinds
 * at one address means an address was wrong, and merging them would fold one
 * portfolio's rows into another's doc.
 */
fun mergePvDocs(
    local: PvVaultDoc,
    localVersion: Int,
    remote: PvVaultDoc,
    remoteVersion: Int,
    deviceId: String,
    mergedAt: String,
    /** A known local edit the remote has never seen is a fork even when the remote dominates. */
    forceDivergent: Boolean = false,
): PvMergedDoc {
    if (local.docKind != remote.docKind) {
        mergeInvalid("A vault doc of kind '${local.docKind}' cannot merge with one of kind '${remote.docKind}'.")
    }
    return when (local) {
        is PvPortfolioDoc -> mergePortfolioDocs(
            local,
            localVersion,
            remote as PvPortfolioDoc,
            remoteVersion,
            deviceId,
            mergedAt,
            forceDivergent,
        )

        is PvCommonDoc -> mergeCommonDocs(
            local,
            localVersion,
            remote as PvCommonDoc,
            remoteVersion,
            deviceId,
            mergedAt,
            forceDivergent,
        )

        is PvHeaderDoc -> mergeHeaderDocs(
            local,
            localVersion,
            remote as PvHeaderDoc,
            remoteVersion,
            forceDivergent,
        )
    }
}

// ── portfolio ───────────────────────────────────────────────────────────────

private fun mergePortfolioDocs(
    local: PvPortfolioDoc,
    localVersion: Int,
    remote: PvPortfolioDoc,
    remoteVersion: Int,
    deviceId: String,
    mergedAt: String,
    forceDivergent: Boolean,
): PvMergedDoc {
    if (local.portfolioId != remote.portfolioId) {
        mergeInvalid(
            "Portfolio doc ${local.portfolioId} cannot merge with a doc of portfolio ${remote.portfolioId}.",
        )
    }
    val merged = mergeVaultDocuments(
        MergeVaultDocumentsInput(
            left = local.asVaultDocument(),
            leftVersion = localVersion,
            right = remote.asVaultDocument(),
            rightVersion = remoteVersion,
            forceDivergent = forceDivergent,
            deviceId = deviceId,
            mergedAt = mergedAt,
        ),
    )
    // `mergeVaultDocuments` always emits `mirrorProvenance` on a divergent merge
    // — merge.ts spreads it unconditionally — and a portfolio doc has no such
    // member. Empty is the only value that can arrive here (nothing put one in);
    // anything else means a common-doc payload reached a portfolio address, and
    // dropping it silently is how an identity map gets lost.
    val carried = merged.document.mirrorProvenance.orEmpty()
    if (carried.isNotEmpty()) {
        mergeInvalid("A portfolio doc merge produced fork provenance, which only the common doc carries.")
    }
    return PvMergedDoc(
        document = PvPortfolioDoc(
            portfolioId = local.portfolioId,
            entities = merged.document.entities,
            mergeLog = VaultDocument.trimMergeLog(merged.document.mergeLog),
        ),
        docVersion = merged.vaultVersion,
        divergent = merged.divergent,
    )
}

private fun PvPortfolioDoc.asVaultDocument(): VaultDocument = VaultDocument(
    schemaVersion = VaultContract.DOCUMENT_V1_VERSION,
    entities = entities,
    mergeLog = mergeLog,
    // Absent, not empty: the two are deliberately distinct in this contract, and
    // a portfolio doc has no `mirrorProvenance` member at all.
    mirrorProvenance = null,
    clientSecurity = null,
)

// ── common ──────────────────────────────────────────────────────────────────

private fun mergeCommonDocs(
    local: PvCommonDoc,
    localVersion: Int,
    remote: PvCommonDoc,
    remoteVersion: Int,
    deviceId: String,
    mergedAt: String,
    forceDivergent: Boolean,
): PvMergedDoc {
    val merged = mergeVaultDocuments(
        MergeVaultDocumentsInput(
            left = local.asVaultDocument(),
            leftVersion = localVersion,
            right = remote.asVaultDocument(),
            rightVersion = remoteVersion,
            forceDivergent = forceDivergent,
            deviceId = deviceId,
            mergedAt = mergedAt,
        ),
    )
    // `mergedClientSecurity` throws when the two retirement proofs disagree, so
    // reaching here means they matched; re-parsing rather than picking a side
    // keeps the typed shape honest about what came out.
    val security = merged.document.clientSecurity
        ?: mergeInvalid("A common doc merge lost the vault's retirement proof material.")
    return PvMergedDoc(
        document = PvCommonDoc(
            entities = merged.document.entities,
            mergeLog = VaultDocument.trimMergeLog(merged.document.mergeLog),
            mirrorProvenance = merged.document.mirrorProvenance.orEmpty(),
            clientSecurity = PvClientSecurity.parse(security),
        ),
        docVersion = merged.vaultVersion,
        divergent = merged.divergent,
    )
}

private fun PvCommonDoc.asVaultDocument(): VaultDocument = VaultDocument(
    schemaVersion = VaultContract.DOCUMENT_VERSION,
    entities = entities,
    mergeLog = mergeLog,
    mirrorProvenance = mirrorProvenance,
    clientSecurity = clientSecurity.toJson(),
)

// ── header ──────────────────────────────────────────────────────────────────

/**
 * **The one rule §6 does not give**, decided here and recorded as decided.
 *
 * §6's conflict rule is stated at entity granularity, and the header doc has no
 * entities: it is a name, a member roster, a keySlots echo, a Drive-connection
 * echo and a creation record (§5). There is no `rev` to compare and no
 * `editedAt`, so the entity rules cannot be applied to it at all — and "whoever
 * wrote last wins" would lose a portfolio the moment two devices move one in at
 * the same time, which is precisely the case §5 says per-doc granularity exists
 * to make cheap.
 *
 * So each member gets the resolution its own semantics demand, and every one of
 * them is a **total order over the two sides** rather than a preference for one
 * — which is what makes the whole function commutative and idempotent:
 *
 * - **roster** — union by portfolio id (never lose a member); on a duplicate id
 *   the name from the higher-versioned side wins, and at equal versions the
 *   lexicographically higher name does. Sorted by id, so both devices emit the
 *   same bytes.
 * - **keySlots** — union by `keyId`, sorted. A `keyId` present on both sides
 *   with a DIFFERENT `wrappedKc` fails closed: that is key material diverging,
 *   the same class of fact `VaultMerge` refuses to resolve for the retirement
 *   proof, and picking a side would hand half the devices a key that opens
 *   nothing.
 * - **name** and **driveConnection** — the higher-versioned side; at equal
 *   versions, the lexicographically higher canonical form. Config, recoverable,
 *   never worth failing a sync over.
 * - **created** — the EARLIER instant, tie-broken by the lower `deviceId`. It is
 *   an immutable historical fact; the earliest claim is the true one.
 *
 * Escalated to the board as an open contract question — see the round report.
 */
private fun mergeHeaderDocs(
    local: PvHeaderDoc,
    localVersion: Int,
    remote: PvHeaderDoc,
    remoteVersion: Int,
    forceDivergent: Boolean,
): PvMergedDoc {
    val merged = PvHeaderDoc(
        name = pickText(local.name, localVersion, remote.name, remoteVersion),
        portfolios = mergeRoster(local, localVersion, remote, remoteVersion),
        keySlots = mergeKeySlots(local.keySlots, remote.keySlots),
        driveConnection = pickDriveConnection(local, localVersion, remote, remoteVersion),
        created = pickCreated(local, remote),
    )

    // The same three short-circuits `mergeVaultDocuments` opens with, for the
    // same reason: minting a new version on every reconcile makes two devices
    // ping-pong versions forever without ever converging.
    if (!forceDivergent) {
        val mergedJson = canonicalJson(merged.toJson())
        if (localVersion > remoteVersion && mergedJson == canonicalJson(local.toJson())) {
            return PvMergedDoc(local, localVersion, divergent = false)
        }
        if (remoteVersion > localVersion && mergedJson == canonicalJson(remote.toJson())) {
            return PvMergedDoc(remote, remoteVersion, divergent = false)
        }
        if (localVersion == remoteVersion && canonicalJson(local.toJson()) == canonicalJson(remote.toJson())) {
            return PvMergedDoc(local, localVersion, divergent = false)
        }
    }
    return PvMergedDoc(merged, maxOf(localVersion, remoteVersion) + 1, divergent = true)
}

private fun mergeRoster(
    local: PvHeaderDoc,
    localVersion: Int,
    remote: PvHeaderDoc,
    remoteVersion: Int,
): List<PvHeaderPortfolio> {
    val byId = LinkedHashMap<String, PvHeaderPortfolio>()
    local.portfolios.forEach { byId[it.id] = it }
    remote.portfolios.forEach { entry ->
        val held = byId[entry.id]
        byId[entry.id] = if (held == null) {
            entry
        } else {
            PvHeaderPortfolio(entry.id, pickText(held.name, localVersion, entry.name, remoteVersion))
        }
    }
    return byId.values.sortedBy { it.id }
}

private fun mergeKeySlots(local: List<PvKeySlot>, remote: List<PvKeySlot>): List<PvKeySlot> {
    val byKeyId = LinkedHashMap<String, PvKeySlot>()
    (local + remote).forEach { slot ->
        val held = byKeyId[slot.keyId]
        if (held != null && held != slot) {
            mergeInvalid("Vault key slot ${slot.keyId} diverged across replicas.")
        }
        byKeyId[slot.keyId] = slot
    }
    if (byKeyId.isEmpty()) mergeInvalid("A vault header doc must keep at least one key slot.")
    return byKeyId.values.sortedBy { it.keyId }
}

private fun pickDriveConnection(
    local: PvHeaderDoc,
    localVersion: Int,
    remote: PvHeaderDoc,
    remoteVersion: Int,
): PvDriveConnectionEcho? {
    val here = local.driveConnection
    val there = remote.driveConnection
    if (here == there) return here
    if (here == null) return there
    if (there == null) return here
    return if (
        pickText(
            canonicalJson(here.toJson()),
            localVersion,
            canonicalJson(there.toJson()),
            remoteVersion,
        ) == canonicalJson(here.toJson())
    ) {
        here
    } else {
        there
    }
}

private fun pickCreated(local: PvHeaderDoc, remote: PvHeaderDoc) = when {
    local.created == remote.created -> local.created
    local.created.at != remote.created.at ->
        if (local.created.at < remote.created.at) local.created else remote.created

    else -> if (local.created.deviceId <= remote.created.deviceId) local.created else remote.created
}

/**
 * The higher-versioned side's text; at equal versions the lexicographically
 * higher one, so both devices choose the same value without either being
 * privileged.
 */
private fun pickText(local: String, localVersion: Int, remote: String, remoteVersion: Int): String = when {
    local == remote -> local
    localVersion > remoteVersion -> local
    remoteVersion > localVersion -> remote
    else -> if (local > remote) local else remote
}
