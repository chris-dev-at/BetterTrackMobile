package at.bettertrack.app.vault

import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject

/**
 * Entity-atomic vault merge — literal port of
 * `apps/web/src/user/vault/merge.ts` (`docs/paranoid-design.md` §4, plan §2.6).
 *
 * Two devices editing the same Drive vault offline produce two successors of one
 * parent. This file is what turns those into a single deterministic document
 * **without a server arbitrating**, which is the entire premise of Drive-only
 * mode. The four binding rules (plan §2.6):
 *
 * 1. Per entity `id`: higher [VaultEntity.rev] wins → tie: later `editedAt` →
 *    tie: lexicographically higher `editedBy`. Total determinism.
 * 2. **Tombstone vs concurrent edit ⇒ the edit wins.** (Ordered *after* `rev` and
 *    *before* `editedAt` — see [chooseVaultEntity].)
 * 3. Merged `vaultVersion = max(parents) + 1`, recorded in `mergeLog`, capped at
 *    [VAULT_MERGE_LOG_LIMIT].
 * 4. Whole-blob fallback: the highest readable version wins; unreadable material
 *    fails closed rather than being silently discarded.
 *
 * Because the rules are commutative and idempotent, two devices that merge the
 * same pair in opposite order reach byte-identical documents, and re-merging an
 * already-merged pair changes nothing. That is what makes a lost CAS race safe
 * to simply retry.
 */

/** `VAULT_MERGE_LOG_LIMIT` (merge.ts:23). */
const val VAULT_MERGE_LOG_LIMIT: Int = 20

/** `MergeVaultDocumentsInput` (merge.ts:25-36). */
data class MergeVaultDocumentsInput(
    val left: VaultDocument,
    val leftVersion: Int,
    val right: VaultDocument,
    val rightVersion: Int,
    /** A known locally pending write is an offline fork, even when it dominates. */
    val forceDivergent: Boolean = false,
    /** Device recording this deterministic merged successor. */
    val deviceId: String,
    /** An injected clock makes merge records reproducible in matrix tests. */
    val mergedAt: String,
)

/** `MergedVaultDocument` (merge.ts:38-43). */
data class MergedVaultDocument(
    val document: VaultDocument,
    val vaultVersion: Int,
    /** Whether a new CAS successor must be written. */
    val divergent: Boolean,
)

/** `NormalizedInstant` (merge.ts:45-48). */
private data class NormalizedInstant(val epochSecond: Double, val fraction: String)

/** `INSTANT_PATTERN` (merge.ts:50). */
private val INSTANT_PATTERN =
    Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?Z$""")

private fun mergeDocumentInvalid(message: String): VaultCryptoError =
    VaultCryptoError(VaultCryptoErrorCode.DOCUMENT_INVALID, message)

/**
 * `mergeVaultDocuments` (merge.ts:56-118).
 *
 * Note the three short-circuits before any merge generation is minted: a
 * strictly newer document that already contains every winning entity of the
 * older parent is a *linear successor*, not a fork, and equal-version
 * byte-equivalent documents need no new generation either. Skipping those would
 * mint a fresh `vaultVersion` on every reconcile and two devices would ping-pong
 * versions forever without ever converging.
 */
fun mergeVaultDocuments(input: MergeVaultDocumentsInput): MergedVaultDocument {
    assertVersion(input.leftVersion)
    assertVersion(input.rightVersion)
    val left = parseDocument(input.left)
    val right = parseDocument(input.right)

    if (!input.forceDivergent) {
        if (input.leftVersion > input.rightVersion && documentDominatesParsed(left, right)) {
            return MergedVaultDocument(left, input.leftVersion, false)
        }
        if (input.rightVersion > input.leftVersion && documentDominatesParsed(right, left)) {
            return MergedVaultDocument(right, input.rightVersion, false)
        }
        if (input.leftVersion == input.rightVersion && sameDocument(left, right)) {
            return MergedVaultDocument(left, input.leftVersion, false)
        }
    }

    // Rule 3: max(parents) + 1.
    val vaultVersion = maxOf(input.leftVersion, input.rightVersion) + 1
    assertVersion(vaultVersion)

    val entityKinds = LinkedHashSet<String>().apply {
        addAll(left.entities.keys)
        addAll(right.entities.keys)
    }
    val entities = LinkedHashMap<String, List<VaultEntity>>()
    for (kind in entityKinds.sortedWith { a, b -> compareText(a, b) }) {
        val merged = mergeEntityKind(left.entities[kind].orEmpty(), right.entities[kind].orEmpty())
        if (merged.isNotEmpty()) entities[kind] = merged
    }

    val record = parseMergeRecord(
        VaultMergeRecord(
            mergedAt = input.mergedAt,
            // Dedup then ascending NUMERIC sort — merge.ts:92 passes an explicit
            // `(a, b) => a - b` comparator precisely because a bare `.sort()`
            // would order these Numbers as strings (§3.3 rule 5).
            parents = linkedSetOf(input.leftVersion, input.rightVersion).sorted(),
            into = vaultVersion,
            deviceId = input.deviceId,
        )
    )
    val clientSecurity = mergedClientSecurity(left, right)
    val union = mergeForkProvenance(left.mirrorProvenance, right.mirrorProvenance)

    // merge.ts:101-106 — §7.1 severed-fork provenance is content-addressed, not
    // entity-atomic: the union keyed by logical identity is what every replica
    // converges on, and a merge must never be the step that loses an identity
    // map. It is pruned against the MERGED entities, so a row one side deleted
    // takes its provenance with it instead of the union resurrecting an alias
    // the server would reject.
    //
    // NOTE this is the one place a merged document ALWAYS carries the
    // `mirrorProvenance` key, even as `[]` — unlike everywhere else in the vault,
    // where absent and empty are deliberately distinct. That asymmetry is the
    // reference's (merge.ts:98-107 spreads it unconditionally into `common`) and
    // is reproduced rather than "fixed": a merged document is a new document, so
    // no published envelope's bytes depend on it.
    val mirrorProvenance = pruneForkProvenance(union, entities)
    val mergeLog = appendMergeRecord(left.mergeLog, right.mergeLog, record)

    val document = if (clientSecurity == null) {
        VaultDocument(
            schemaVersion = VaultContract.DOCUMENT_V1_VERSION,
            entities = entities,
            mergeLog = mergeLog,
            mirrorProvenance = mirrorProvenance,
            clientSecurity = null,
        )
    } else {
        VaultDocument(
            schemaVersion = VaultContract.DOCUMENT_VERSION,
            entities = entities,
            mergeLog = mergeLog,
            mirrorProvenance = mirrorProvenance,
            clientSecurity = clientSecurity,
        )
    }

    return MergedVaultDocument(document, vaultVersion, true)
}

/**
 * `chooseVaultEntity` (merge.ts:127-153) — the deterministic winner for one id.
 *
 * Both entities are validated and **both instants parsed before any early winner
 * is selected**, so a malformed timestamp fails closed even when `rev` would
 * otherwise have decided the comparison without ever looking at the dates.
 *
 * The ordering is: `rev` → live-vs-tombstone → `editedAt` → `editedBy` →
 * canonical content. The live-vs-tombstone step sitting *second* is rule 2: at
 * equal `rev`, a concurrent edit beats a delete regardless of which happened
 * later by the clock. Deleting is cheap to redo; an edit that vanished is data
 * loss the user cannot even see.
 */
fun chooseVaultEntity(left: VaultEntity, right: VaultEntity): VaultEntity {
    val parsedLeft = parseEntity(left)
    val parsedRight = parseEntity(right)
    if (parsedLeft.id != parsedRight.id) {
        throw mergeDocumentInvalid("Vault merge candidates must have the same entity id.")
    }

    val leftInstant = parseInstant(parsedLeft.editedAt)
    val rightInstant = parseInstant(parsedRight.editedAt)

    if (parsedLeft.rev != parsedRight.rev) {
        return if (parsedLeft.rev > parsedRight.rev) parsedLeft else parsedRight
    }

    val leftLive = parsedLeft.deletedAt == null
    val rightLive = parsedRight.deletedAt == null
    if (leftLive != rightLive) return if (leftLive) parsedLeft else parsedRight

    val editedAt = compareInstants(leftInstant, rightInstant)
    if (editedAt != 0) return if (editedAt > 0) parsedLeft else parsedRight

    val editedBy = compareText(parsedLeft.editedBy, parsedRight.editedBy)
    if (editedBy != 0) return if (editedBy > 0) parsedLeft else parsedRight

    val wholeEntity = compareText(canonicalJson(parsedLeft.toJson()), canonicalJson(parsedRight.toJson()))
    return if (wholeEntity >= 0) parsedLeft else parsedRight
}

/** `documentDominates` (merge.ts:156-158) — every atomic state in `right` loses to `left`. */
fun documentDominates(left: VaultDocument, right: VaultDocument): Boolean =
    documentDominatesParsed(parseDocument(left), parseDocument(right))

/** `documentDominatesParsed` (merge.ts:160-195). */
private fun documentDominatesParsed(left: VaultDocument, right: VaultDocument): Boolean {
    // merge.ts:161-165 — a linear successor must already carry the loser's fork
    // provenance; otherwise taking it verbatim would silently drop an identity
    // the other replica captured. Both sides are pruned against their OWN
    // entities first: an entry whose row the loser itself deleted is not an
    // identity to preserve, and treating it as one would force a divergent merge
    // on every reconcile without ever converging.
    if (!forkProvenanceDominates(carriedForkProvenance(left), carriedForkProvenance(right))) {
        return false
    }
    val leftSecurity = clientSecurityOf(left)
    val rightSecurity = clientSecurityOf(right)
    if (rightSecurity != null && leftSecurity == null) return false
    if (leftSecurity != null &&
        rightSecurity != null &&
        canonicalJson(leftSecurity) != canonicalJson(rightSecurity)
    ) {
        throw mergeDocumentInvalid("Vault retirement proof material diverged across replicas.")
    }
    for ((kind, entities) in right.entities) {
        val candidates = entityMap(left.entities[kind].orEmpty())
        for (rightEntity in entities) {
            val leftEntity = candidates[rightEntity.id]
            if (leftEntity == null || !sameEntity(chooseVaultEntity(leftEntity, rightEntity), leftEntity)) {
                return false
            }
        }
    }
    return true
}

/** `mergedClientSecurity` (merge.ts:197-210). */
private fun mergedClientSecurity(left: VaultDocument, right: VaultDocument): JsonObject? {
    val leftSecurity = clientSecurityOf(left)
    val rightSecurity = clientSecurityOf(right)
    if (leftSecurity == null && rightSecurity == null) return null
    if (leftSecurity == null) return rightSecurity
    if (rightSecurity == null) return leftSecurity
    if (canonicalJson(leftSecurity) != canonicalJson(rightSecurity)) {
        throw mergeDocumentInvalid("Vault retirement proof material diverged across replicas.")
    }
    return leftSecurity
}

/** `clientSecurityOf` (merge.ts:212-214). */
private fun clientSecurityOf(document: VaultDocument): JsonObject? =
    if (document.schemaVersion == VaultContract.DOCUMENT_VERSION) document.clientSecurity else null

/** `mergeEntityKind` (merge.ts:216-218). */
private fun mergeEntityKind(left: List<VaultEntity>, right: List<VaultEntity>): List<VaultEntity> =
    entityMap(left + right).values.sortedWith { a, b -> compareText(a.id, b.id) }

/** `entityMap` (merge.ts:220-228) — insertion-ordered, last-writer resolved by rule 1. */
private fun entityMap(entities: List<VaultEntity>): Map<String, VaultEntity> {
    val byId = LinkedHashMap<String, VaultEntity>()
    for (entity in entities) {
        val parsed = parseEntity(entity)
        val existing = byId[parsed.id]
        byId[parsed.id] = if (existing == null) parsed else chooseVaultEntity(existing, parsed)
    }
    return byId
}

/**
 * `appendMergeRecord` (merge.ts:230-253) — rule 3's bounded diagnostic history.
 *
 * De-duplicates by canonical content (both parents may already know the same
 * merge), orders by `mergedAt` with the canonical string as a deterministic
 * tie-break, then keeps the **newest 19** so the appended record makes 20.
 */
private fun appendMergeRecord(
    left: List<VaultMergeRecord>,
    right: List<VaultMergeRecord>,
    appended: VaultMergeRecord,
): List<VaultMergeRecord> {
    val appendedKey = canonicalJson(appended.toJson())
    val uniqueHistory = LinkedHashMap<String, VaultMergeRecord>()
    for (record in left + right) {
        val key = canonicalJson(record.toJson())
        if (key != appendedKey) uniqueHistory[key] = record
    }

    val history = uniqueHistory.entries
        .sortedWith { leftEntry, rightEntry ->
            val mergedAt = compareInstants(
                parseInstant(leftEntry.value.mergedAt, "mergeLog mergedAt"),
                parseInstant(rightEntry.value.mergedAt, "mergeLog mergedAt"),
            )
            if (mergedAt == 0) compareText(leftEntry.key, rightEntry.key) else mergedAt
        }
        .takeLast(VAULT_MERGE_LOG_LIMIT - 1)
        .map { it.value }
    return history + appended
}

/** `parseDocument` (merge.ts:255-262). */
private fun parseDocument(document: VaultDocument): VaultDocument {
    val parsed = try {
        VaultDocument.parse(document.toJson())
    } catch (cause: VaultCryptoError) {
        throw mergeDocumentInvalid("Vault document does not match the current schema.")
    }
    // merge.ts:260 — called for its validation side effect (finite numbers only).
    canonicalJson(parsed.toJson())
    return parsed
}

/** `parseEntity` (merge.ts:264-271). */
private fun parseEntity(entity: VaultEntity): VaultEntity {
    val parsed = try {
        VaultEntity.parse(entity.toJson())
    } catch (cause: VaultCryptoError) {
        throw mergeDocumentInvalid("Vault entity does not match the current schema.")
    }
    canonicalJson(parsed.toJson())
    return parsed
}

/** `parseMergeRecord` (merge.ts:273-279). */
private fun parseMergeRecord(record: VaultMergeRecord): VaultMergeRecord = try {
    VaultMergeRecord.parse(record.toJson())
} catch (cause: VaultCryptoError) {
    throw mergeDocumentInvalid("Vault merge metadata does not match the current schema.")
}

/**
 * `parseInstant` (merge.ts:281-322).
 *
 * Deliberately **not** `java.time.Instant.parse`. The reference splits an instant
 * into a whole-second epoch plus a *string* fraction and compares the fraction
 * lexicographically after zero-padding, so `…:00.1Z` and `…:00.10Z` compare
 * equal while `…:00.100000001Z` still sorts above both — precision the reference
 * keeps and a `long` of nanos would silently truncate.
 *
 * It also validates by **round trip**: the components are re-read off the
 * constructed date and compared, so a well-shaped but impossible instant
 * (`2026-02-30T…`, `…T25:00:00Z`) is rejected instead of rolling over into a
 * different real date. `LocalDate.plusMonths`/`plusDays` reproduce JavaScript's
 * `setUTCFullYear`/`setUTCHours` overflow semantics exactly, so the round-trip
 * check catches the same inputs.
 */
private fun parseInstant(value: String, field: String = "entity editedAt"): NormalizedInstant {
    val match = INSTANT_PATTERN.find(value)
        ?: throw mergeDocumentInvalid("Vault $field must be a parseable RFC 3339 instant.")

    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val hour = match.groupValues[4].toInt()
    val minute = match.groupValues[5].toInt()
    val second = match.groupValues[6].ifEmpty { "00" }.toInt()
    val fractionText = match.groupValues[7]

    val date = try {
        LocalDate.of(year, 1, 1)
            .plusMonths((month - 1).toLong())
            .plusDays((day - 1).toLong())
            .atStartOfDay(ZoneOffset.UTC)
            .plusHours(hour.toLong())
            .plusMinutes(minute.toLong())
            .plusSeconds(second.toLong())
    } catch (cause: RuntimeException) {
        throw mergeDocumentInvalid("Vault $field must be a parseable RFC 3339 instant.")
    }

    if (date.year != year ||
        date.monthValue != month ||
        date.dayOfMonth != day ||
        date.hour != hour ||
        date.minute != minute ||
        date.second != second
    ) {
        throw mergeDocumentInvalid("Vault $field must be a parseable RFC 3339 instant.")
    }

    return NormalizedInstant(
        epochSecond = date.toInstant().toEpochMilli().toDouble() / 1_000,
        fraction = fractionText.trimEnd('0'),
    )
}

/** `compareInstants` (merge.ts:324-330). */
private fun compareInstants(left: NormalizedInstant, right: NormalizedInstant): Int {
    if (left.epochSecond != right.epochSecond) {
        return if (left.epochSecond < right.epochSecond) -1 else 1
    }
    val width = maxOf(left.fraction.length, right.fraction.length)
    return compareText(left.fraction.padEnd(width, '0'), right.fraction.padEnd(width, '0'))
}

/** `sameDocument` (merge.ts:332-334). */
private fun sameDocument(left: VaultDocument, right: VaultDocument): Boolean =
    canonicalJson(left.toJson()) == canonicalJson(right.toJson())

/** `sameEntity` (merge.ts:336-338). */
private fun sameEntity(left: VaultEntity, right: VaultEntity): Boolean =
    canonicalJson(left.toJson()) == canonicalJson(right.toJson())

/** `assertVersion` (merge.ts:414-421). */
private fun assertVersion(version: Int) {
    if (version < 1 || version > VaultContract.VERSION_MAX) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.ENVELOPE_INVALID,
            "Vault versions must be positive safe integers.",
        )
    }
}
