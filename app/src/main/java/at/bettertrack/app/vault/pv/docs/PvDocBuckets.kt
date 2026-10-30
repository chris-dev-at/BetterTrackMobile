package at.bettertrack.app.vault.pv.docs

import at.bettertrack.app.vault.VaultContract

/**
 * **Which doc carries which entity kind** — the Android mirror of the E0
 * contract's `VAULT_ENTITY_DOC_BUCKETS` (`packages/contracts/src/vaults.ts`,
 * `paranoid-design.md` §5, platform issue #1410).
 *
 * The rule is mechanical, decided by the row's actual scoping column:
 * portfolio-scoped (a `portfolio_id` anywhere on its ownership chain) ⇒ the
 * member portfolio's own doc; account-scoped-but-vault-referenced ⇒ the vault's
 * `common` doc. Exhaustiveness is the safety property: a kind with no bucket is
 * a kind the split silently drops, so [UNBUCKETED_KINDS] must always be empty
 * and the two sets must always be disjoint.
 *
 * ## The record is the contract, the sets are derived from it
 *
 * [ENTITY_DOC_BUCKETS] is the platform's typed `Record<VaultEntityKind,
 * VaultDocBucket>` transcribed in its declaration order, and
 * [PORTFOLIO_DOC_KINDS] / [COMMON_DOC_KINDS] are `bucketKinds('portfolio')` /
 * `bucketKinds('common')` — derived, exactly as `VAULT_PORTFOLIO_DOC_ENTITY_KINDS`
 * and `VAULT_COMMON_DOC_ENTITY_KINDS` are upstream. One literal, two views; the
 * board-pinned E0 `docBuckets` vector (`pv-envelope.fixture.json`) replays every
 * kind against [bucketOf] in `PvEnvelopeConformanceTest`.
 *
 * Until MB-17 (2026-10-30) this object DERIVED its sets from the retired Vaults
 * v2 arc's partition (`VaultV2Contract.PORTFOLIO_SCOPED_KINDS` /
 * `COMMON_SCOPED_KINDS`) plus a named delta, so that the two lists could not
 * drift apart. The platform removed that arc (#1392) and the app followed; with
 * no second consumer left, the partition is stated once, here, in the
 * platform's own order.
 *
 * ## The one historical difference, recorded rather than hidden
 *
 * E0 moved **`cashBudget` and `cashBudgetFire` from `common` to `portfolio`**
 * ([E0_MOVED_TO_PORTFOLIO]) relative to the v2 enumeration: E0 re-derived every
 * bucket from the live schema's scoping columns and found budgets are
 * portfolio-keyed. The E0 vector suite pins that delta explicitly
 * (`movedFromV2ToPortfolio`), so it stays named here even though nothing
 * derives from it any more — the next reader sees both the old placement and
 * the reason it moved.
 *
 * Dormant like the rest of the epic — no caller outside tests, gated behind
 * `ParanoidVaultsFlags.enabled`.
 */
object PvDocBuckets {

    /** `VAULT_DOC_BUCKETS`. */
    const val BUCKET_PORTFOLIO: String = "portfolio"
    const val BUCKET_COMMON: String = "common"

    /**
     * `VAULT_ENTITY_DOC_BUCKETS` — the binding doc bucket per entity kind, in
     * the platform's declaration order (portfolio-scoped first, then the
     * movement-scoped link rows and portfolio-keyed budgets, then the
     * account-scoped kinds that ride `common`).
     */
    val ENTITY_DOC_BUCKETS: Map<String, String> = linkedMapOf(
        // portfolio-scoped → the portfolio doc (§5)
        "portfolio" to BUCKET_PORTFOLIO,
        "transaction" to BUCKET_PORTFOLIO,
        "dividend" to BUCKET_PORTFOLIO,
        "cashSource" to BUCKET_PORTFOLIO,
        "cashMovement" to BUCKET_PORTFOLIO,
        "portfolioSetting" to BUCKET_PORTFOLIO,
        "standingOrder" to BUCKET_PORTFOLIO,
        "standingOrderRun" to BUCKET_PORTFOLIO,
        "importBatch" to BUCKET_PORTFOLIO,
        "importRow" to BUCKET_PORTFOLIO,
        "portfolioDailySnapshot" to BUCKET_PORTFOLIO,
        "portfolioSnapshotState" to BUCKET_PORTFOLIO,
        // movement-scoped link rows and portfolio-keyed budgets follow the movement
        "cashMovementTag" to BUCKET_PORTFOLIO,
        "cashBudget" to BUCKET_PORTFOLIO,
        "cashBudgetFire" to BUCKET_PORTFOLIO,
        // account-scoped, vault-referenced → the common doc (§5)
        "taxSetting" to BUCKET_COMMON,
        "customAsset" to BUCKET_COMMON,
        "customAssetValue" to BUCKET_COMMON,
        "expenseCategory" to BUCKET_COMMON,
        "expenseTransaction" to BUCKET_COMMON,
        "expenseRule" to BUCKET_COMMON,
        "expenseBudget" to BUCKET_COMMON,
        "expenseBudgetFire" to BUCKET_COMMON,
        "cashTag" to BUCKET_COMMON,
        "cashRule" to BUCKET_COMMON,
        "cashRuleTag" to BUCKET_COMMON,
    )

    /**
     * The kinds whose bucket E0 corrected relative to the retired v2 partition:
     * both are portfolio-keyed budget rows, so they ride the member portfolio's
     * doc. History, pinned by the E0 vector; nothing derives from it.
     */
    val E0_MOVED_TO_PORTFOLIO: Set<String> = linkedSetOf("cashBudget", "cashBudgetFire")

    /** `bucketKinds(bucket)` — the kinds of one bucket, sorted as upstream sorts them. */
    private fun bucketKinds(bucket: String): Set<String> =
        ENTITY_DOC_BUCKETS.entries.filter { it.value == bucket }.map { it.key }.sorted().toSet()

    /** `VAULT_PORTFOLIO_DOC_ENTITY_KINDS` — entity kinds carried by each member portfolio's own doc (§5). */
    val PORTFOLIO_DOC_KINDS: Set<String> = bucketKinds(BUCKET_PORTFOLIO)

    /** `VAULT_COMMON_DOC_ENTITY_KINDS` — entity kinds carried by the vault-wide `common` doc (§5). */
    val COMMON_DOC_KINDS: Set<String> = bucketKinds(BUCKET_COMMON)

    /**
     * Must always be empty — see the class note. Upstream the typed `Record`
     * over the kind enum makes this a compile-time property; here it is a
     * value the conformance suite asserts.
     */
    val UNBUCKETED_KINDS: Set<String> =
        VaultContract.ENTITY_KINDS.filterNot { it in ENTITY_DOC_BUCKETS }.toSet()

    /** The bucket of one entity kind, or `null` when the kind is unknown. */
    fun bucketOf(kind: String): String? = ENTITY_DOC_BUCKETS[kind]
}
