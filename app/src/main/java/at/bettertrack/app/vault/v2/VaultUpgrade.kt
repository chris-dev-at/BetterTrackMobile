package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.VaultDocument
import at.bettertrack.app.vault.VaultEntity
import kotlinx.serialization.json.JsonPrimitive

/**
 * v1 → v2 split (`docs/VAULTS_V2_DESIGN.md` r2 §8/§11) — literal port of the
 * platform's `apps/web/src/user/vault/v2/upgrade.ts`.
 *
 * A v1 account holds ONE encrypted document. v2 wants one `common` doc plus one
 * doc per portfolio, all inside vault #1. This module performs the split on
 * already-decrypted material — it is pure, so the property that matters can be
 * tested directly:
 *
 *   **every entity in, exactly one entity out.**
 *
 * Portfolio-scoped rows are routed by their portfolio, directly or through a
 * parent chain. Everything account/vault-scoped goes to `common` (r2 §8), which
 * also carries `mergeLog`, `mirrorProvenance` and `clientSecurity`.
 *
 * Rows whose portfolio cannot be resolved (a dangling parent reference in an old
 * document) are NOT dropped: they land in `common` and are reported in
 * [VaultUpgradeReport.orphans], so a migration is auditable instead of quietly
 * lossy.
 *
 * Every produced doc has a **deterministic identity** (r2 §11 step 2): the
 * portfolio docs are keyed by `portfolioId` and `common` is the vault's single
 * common doc, which is what makes a resumed migration idempotent — and, with
 * the r3 §18 derivations, byte-identical.
 */

/** How each portfolio-scoped kind finds its portfolio. */
private sealed interface PortfolioResolution {
    data object Self : PortfolioResolution
    data class Field(val field: String) : PortfolioResolution
    data class Parent(val field: String, val parent: String) : PortfolioResolution
}

private val PORTFOLIO_RESOLUTION: Map<String, PortfolioResolution> = linkedMapOf(
    "portfolio" to PortfolioResolution.Self,
    "transaction" to PortfolioResolution.Field("portfolioId"),
    "dividend" to PortfolioResolution.Field("portfolioId"),
    "cashSource" to PortfolioResolution.Field("portfolioId"),
    "cashMovement" to PortfolioResolution.Field("portfolioId"),
    "portfolioSetting" to PortfolioResolution.Field("portfolioId"),
    "standingOrder" to PortfolioResolution.Field("portfolioId"),
    "importBatch" to PortfolioResolution.Field("portfolioId"),
    "portfolioDailySnapshot" to PortfolioResolution.Field("portfolioId"),
    "portfolioSnapshotState" to PortfolioResolution.Field("portfolioId"),
    "standingOrderRun" to PortfolioResolution.Parent("standingOrderId", "standingOrder"),
    "importRow" to PortfolioResolution.Parent("batchId", "importBatch"),
    "cashMovementTag" to PortfolioResolution.Parent("movementId", "cashMovement"),
)

enum class VaultUpgradeOrphanReason(val wire: String) {
    MISSING_REFERENCE("missing-reference"),
    UNKNOWN_PORTFOLIO("unknown-portfolio"),
    UNSCOPED_KIND("unscoped-kind"),
}

data class VaultUpgradeOrphan(
    val kind: String,
    val entityId: String,
    val reason: VaultUpgradeOrphanReason,
)

data class VaultUpgradeReport(
    /** Total entities read out of the v1 document, tombstones included. */
    val entitiesIn: Int,
    /** Total entities written across every produced doc. Must equal [entitiesIn]. */
    val entitiesOut: Int,
    val orphans: List<VaultUpgradeOrphan>,
)

data class VaultUpgradeSplit(
    val portfolioDocs: List<VaultContentDoc.Portfolio>,
    val commonDoc: VaultContentDoc.Common,
    val index: List<VaultPortfolioIndexEntry>,
    val report: VaultUpgradeReport,
)

/**
 * Split one decrypted v1 document into per-portfolio docs plus the vault's
 * `common` doc. Pure: no crypto, no I/O, no clock.
 *
 * [aliases] supplies display names for the portfolio index. A portfolio without
 * an override falls back to its own `portfolio` entity name, then to a neutral
 * label. The alias is CLEARTEXT (§2 portfolio index).
 */
internal fun splitVaultDocument(
    document: VaultDocument,
    vaultId: String,
    aliases: Map<String, String> = emptyMap(),
): VaultUpgradeSplit {
    fun byKind(kind: String): List<VaultEntity> = document.entities[kind] ?: emptyList()

    // Index every entity id per kind once so parent lookups stay linear.
    val idIndex = LinkedHashMap<String, Map<String, VaultEntity>>()
    for (kind in VaultContract.ENTITY_KINDS) {
        idIndex[kind] = byKind(kind).associateBy { it.id }
    }

    val knownPortfolioIds = byKind("portfolio").map { it.id }.toSet()
    val perPortfolio = LinkedHashMap<String, LinkedHashMap<String, MutableList<VaultEntity>>>()
    val commonEntities = LinkedHashMap<String, MutableList<VaultEntity>>()
    val orphans = mutableListOf<VaultUpgradeOrphan>()
    var entitiesIn = 0
    var entitiesOut = 0

    fun pushPortfolio(portfolioId: String, kind: String, entity: VaultEntity) {
        perPortfolio.getOrPut(portfolioId) { LinkedHashMap() }
            .getOrPut(kind) { mutableListOf() }
            .add(entity)
        entitiesOut += 1
    }

    fun pushCommon(kind: String, entity: VaultEntity) {
        commonEntities.getOrPut(kind) { mutableListOf() }.add(entity)
        entitiesOut += 1
    }

    for (kind in VaultContract.ENTITY_KINDS) {
        for (entity in byKind(kind)) {
            entitiesIn += 1

            if (VaultV2Contract.isCommonScopedKind(kind)) {
                pushCommon(kind, entity)
                continue
            }
            if (!VaultV2Contract.isPortfolioScopedKind(kind)) {
                // A kind added to the contract without a v2 scope. Keep the row
                // and make the gap loud rather than silently discarding money data.
                orphans += VaultUpgradeOrphan(kind, entity.id, VaultUpgradeOrphanReason.UNSCOPED_KIND)
                pushCommon(kind, entity)
                continue
            }

            val resolved = resolvePortfolioId(kind, entity, idIndex)
            if (resolved == null) {
                orphans += VaultUpgradeOrphan(
                    kind, entity.id, VaultUpgradeOrphanReason.MISSING_REFERENCE,
                )
                pushCommon(kind, entity)
                continue
            }
            if (resolved !in knownPortfolioIds) {
                orphans += VaultUpgradeOrphan(
                    kind, entity.id, VaultUpgradeOrphanReason.UNKNOWN_PORTFOLIO,
                )
                pushCommon(kind, entity)
                continue
            }
            pushPortfolio(resolved, kind, entity)
        }
    }

    // Order by the portfolio entity order so the produced docs and index are
    // deterministic across runs — a resumed migration must rewrite
    // byte-identical documents (r2 §11 step 2).
    val orderedPortfolioIds = byKind("portfolio").map { it.id } +
        perPortfolio.keys.filter { it !in knownPortfolioIds }

    val portfolioDocs = mutableListOf<VaultContentDoc.Portfolio>()
    val index = mutableListOf<VaultPortfolioIndexEntry>()
    for (portfolioId in orderedPortfolioIds) {
        val bucket = perPortfolio[portfolioId] ?: LinkedHashMap()
        portfolioDocs += VaultContentDoc.Portfolio(
            vaultId = vaultId,
            portfolioId = portfolioId,
            entities = bucket.mapValues { (_, rows) -> rows.toList() },
            mergeLog = emptyList(),
        )
        index += VaultPortfolioIndexEntry(
            portfolioId = portfolioId,
            alias = aliasFor(portfolioId, aliases, idIndex["portfolio"]?.get(portfolioId)),
        )
    }

    val commonDoc = VaultContentDoc.Common(
        vaultId = vaultId,
        entities = commonEntities.mapValues { (_, rows) -> rows.toList() },
        // r3 §20: mergeLog and mirrorProvenance are per-vault MEMBERS of
        // `common`. The log is trimmed on write (never rejected on read) so an
        // oversized diagnostic array can never make the common doc — and with
        // it the whole vault — unparseable.
        mergeLog = VaultDocument.trimMergeLog(document.mergeLog),
        mirrorProvenance = document.mirrorProvenance,
        clientSecurity = if (document.schemaVersion == VaultContract.DOCUMENT_VERSION) {
            document.clientSecurity
        } else {
            null
        },
    )

    if (entitiesIn != entitiesOut) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.DOCUMENT_INVALID,
            "The v1→v2 split lost rows ($entitiesIn in, $entitiesOut out).",
        )
    }
    return VaultUpgradeSplit(
        portfolioDocs = portfolioDocs,
        commonDoc = commonDoc,
        index = index,
        report = VaultUpgradeReport(entitiesIn, entitiesOut, orphans),
    )
}

private fun resolvePortfolioId(
    kind: String,
    entity: VaultEntity,
    idIndex: Map<String, Map<String, VaultEntity>>,
): String? {
    return when (val resolution = PORTFOLIO_RESOLUTION[kind]) {
        null -> null
        is PortfolioResolution.Self -> entity.id
        is PortfolioResolution.Field -> entity.stringField(resolution.field)
        is PortfolioResolution.Parent -> {
            val raw = entity.stringField(resolution.field) ?: return null
            val parent = idIndex[resolution.parent]?.get(raw) ?: return null
            resolvePortfolioId(resolution.parent, parent, idIndex)
        }
    }
}

private fun VaultEntity.stringField(field: String): String? =
    (data[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

private fun aliasFor(
    portfolioId: String,
    aliases: Map<String, String>,
    portfolioEntity: VaultEntity?,
): String {
    val override = aliases[portfolioId]?.trim()
    if (!override.isNullOrEmpty()) return override.take(VaultV2Contract.NAME_MAX_LENGTH)
    val name = (portfolioEntity?.data?.get("name") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim()
    if (!name.isNullOrEmpty()) return name.take(VaultV2Contract.NAME_MAX_LENGTH)
    return "Portfolio ${portfolioId.take(8)}"
}
