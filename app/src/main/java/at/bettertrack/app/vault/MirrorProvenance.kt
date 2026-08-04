package at.bettertrack.app.vault

/**
 * Severed-fork MIRRORCHAIN provenance — literal port of
 * `apps/web/src/user/vault/mirrorProvenance.ts` (`docs/paranoid-design.md` §7.1).
 *
 * `mirror_rows` — the chain's logical↔local identity map — dies with the copy at
 * paranoid enable, while the append-only oplog keeps only the LOGICAL id. After a
 * sanctioned financial correction the surviving local row is a replacement, so
 * `localId == mirrorId` is false and restore-time validation cannot re-derive the
 * association. The map therefore has to be captured *before* enable and carried
 * inside the encrypted document.
 *
 * The app never authors one of these (a Drive-only lineage has no MIRRORCHAIN),
 * but the merge engine must carry them through a read/merge/write cycle without
 * loss — "a merge must never be the step that loses an identity map".
 *
 * **Not ported:** `captureForkProvenanceIntoVault` (mirrorProvenance.ts:199-209).
 * It is the only impure function in the file and it drives `VaultSyncEngine`,
 * which does not exist until W4. Everything it composes ([normalizeForkProvenance],
 * [forkProvenanceDominates], [carriedForkProvenance], [withForkProvenance]) is
 * ported and tested here, so W4 adds the fold, not the logic.
 */

/** `KEY_SEPARATOR` (mirrorProvenance.ts:29) — no id or row kind can contain NUL. */
private const val KEY_SEPARATOR = "\u0000"

/**
 * `logicalKey` (mirrorProvenance.ts:37-39) — one logical entity per MEMBERSHIP
 * and row kind.
 *
 * The chain id alone is deliberately not enough: re-joining a chain mints a
 * second membership with a second copy, so one chain can legitimately hold two
 * retained forks carrying the same logical entity under different local ids.
 */
private fun logicalKey(entry: VaultMirrorProvenance): String =
    listOf(entry.kind, entry.membershipId, entry.mirrorId).joinToString(KEY_SEPARATOR)

/** `localKey` (mirrorProvenance.ts:42-44) — one local row per row kind. */
private fun localKey(entry: VaultMirrorProvenance): String =
    listOf(entry.kind, entry.localId).joinToString(KEY_SEPARATOR)

/** `entityKey` (mirrorProvenance.ts:46-48). */
private fun entityKey(kind: String, id: String): String =
    listOf(kind, id).joinToString(KEY_SEPARATOR)

private fun provenanceInvalid(message: String): VaultCryptoError =
    VaultCryptoError(VaultCryptoErrorCode.DOCUMENT_INVALID, message)

/** `parseEntry` (mirrorProvenance.ts:54-60). */
private fun parseEntry(entry: VaultMirrorProvenance): VaultMirrorProvenance = try {
    VaultMirrorProvenance.parse(entry.toJson())
} catch (cause: VaultCryptoError) {
    throw provenanceInvalid("Vault fork provenance does not match the current schema.")
}

/** `sameEntry` (mirrorProvenance.ts:62-71). */
private fun sameEntry(left: VaultMirrorProvenance, right: VaultMirrorProvenance): Boolean =
    left.chainId == right.chainId &&
        left.membershipId == right.membershipId &&
        left.kind == right.kind &&
        left.mirrorId == right.mirrorId &&
        left.portfolioId == right.portfolioId &&
        left.localId == right.localId

/**
 * `normalizeForkProvenance` (mirrorProvenance.ts:79-100) — validate, de-duplicate
 * and order one capture.
 *
 * Two entries claiming the same logical identity with different local rows — or
 * one local row under two logical identities — is a **malformed document, not a
 * conflict to resolve**: the server refuses both, so failing closed here keeps
 * the failure where it can still be fixed.
 */
fun normalizeForkProvenance(entries: List<VaultMirrorProvenance> = emptyList()): List<VaultMirrorProvenance> {
    val byLogical = LinkedHashMap<String, VaultMirrorProvenance>()
    val byLocal = LinkedHashMap<String, VaultMirrorProvenance>()
    for (raw in entries) {
        val entry = parseEntry(raw)
        val existingLogical = byLogical[logicalKey(entry)]
        if (existingLogical != null && !sameEntry(existingLogical, entry)) {
            throw provenanceInvalid("Two local rows claim one logical MIRRORCHAIN identity.")
        }
        val existingLocal = byLocal[localKey(entry)]
        if (existingLocal != null && !sameEntry(existingLocal, entry)) {
            throw provenanceInvalid("One local row claims two logical MIRRORCHAIN identities.")
        }
        byLogical[logicalKey(entry)] = entry
        byLocal[localKey(entry)] = entry
    }
    // mirrorProvenance.ts:97-99 — sort by the logical key with the reference's
    // own `<`/`>` string comparison (§3.3 rule 5), not a locale collator.
    return byLogical.values.sortedWith { left, right -> compareText(logicalKey(left), logicalKey(right)) }
}

/**
 * `mergeForkProvenance` (mirrorProvenance.ts:107-112) — the CAS/merge union.
 *
 * Provenance is **content-addressed rather than entity-atomic** (every replica
 * captured the same server rows), so a union keyed by logical identity is
 * deterministic and order-independent.
 */
fun mergeForkProvenance(
    left: List<VaultMirrorProvenance>?,
    right: List<VaultMirrorProvenance>?,
): List<VaultMirrorProvenance> = normalizeForkProvenance(left.orEmpty() + right.orEmpty())

/** `forkProvenanceDominates` (mirrorProvenance.ts:115-124). */
fun forkProvenanceDominates(
    left: List<VaultMirrorProvenance>?,
    right: List<VaultMirrorProvenance>?,
): Boolean {
    val mine = left.orEmpty().associateBy { logicalKey(it) }
    return right.orEmpty().all { entry ->
        val candidate = mine[logicalKey(entry)]
        candidate != null && sameEntry(candidate, entry)
    }
}

/**
 * `pruneForkProvenance` (mirrorProvenance.ts:136-153) — keep only entries whose
 * local row is still live.
 *
 * A row the user deleted locally after the capture has no provenance to prove,
 * and the server rejects an entry naming no restored row — so a stale alias must
 * never accumulate, or the account could no longer leave paranoid mode.
 */
fun pruneForkProvenance(
    entries: List<VaultMirrorProvenance>?,
    entitiesByKind: Map<String, List<VaultEntity>>,
): List<VaultMirrorProvenance> {
    if (entries.orEmpty().isEmpty()) return emptyList()
    val live = HashSet<String>()
    for ((kind, entities) in entitiesByKind) {
        for (entity in entities) {
            if (entity.deletedAt == null) live.add(entityKey(kind, entity.id))
        }
    }
    return normalizeForkProvenance(entries.orEmpty()).filter { entry ->
        live.contains(entityKey(MIRROR_PROVENANCE_ENTITY_KINDS.getValue(entry.kind), entry.localId))
    }
}

/**
 * `VAULT_MIRROR_PROVENANCE_ENTITY_KINDS` (vault.ts:1199-1204) — which vault
 * entity kind each `mirror_rows.kind` resolves to.
 */
val MIRROR_PROVENANCE_ENTITY_KINDS: Map<String, String> = linkedMapOf(
    "transaction" to "transaction",
    "dividend" to "dividend",
    "cash_movement" to "cashMovement",
    "cash_source" to "cashSource",
)

/**
 * `carriedForkProvenance` (mirrorProvenance.ts:162-167) — the provenance a
 * document is about to carry, normalized and pruned, **without inventing the key
 * on a document that has none**.
 *
 * `null` (absent) and `[]` mean the same thing — "no severed fork" — but they are
 * not the same *bytes*, and defaulting one in would change the plaintext of every
 * fork-free vault in existence.
 */
fun carriedForkProvenance(document: VaultDocument): List<VaultMirrorProvenance>? {
    if (document.mirrorProvenance == null) return null
    return pruneForkProvenance(document.mirrorProvenance, document.entities)
}

/**
 * `withForkProvenance` (mirrorProvenance.ts:174-184) — fold one capture into the
 * document, idempotently.
 */
fun withForkProvenance(
    document: VaultDocument,
    captured: List<VaultMirrorProvenance>,
): VaultDocument {
    val mirrorProvenance = pruneForkProvenance(
        mergeForkProvenance(document.mirrorProvenance, captured),
        document.entities,
    )
    if (mirrorProvenance.isEmpty() && document.mirrorProvenance == null) return document
    return document.copy(mirrorProvenance = mirrorProvenance)
}
