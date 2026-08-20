package at.bettertrack.app.vault.pv.docs

import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.v2.VaultV2Contract

/**
 * **Which doc carries which entity kind** — the Android mirror of the E0
 * contract's `VAULT_ENTITY_DOC_BUCKETS` (`paranoid-design.md` §5, platform issue
 * #1410).
 *
 * The rule is mechanical, decided by the row's actual scoping column:
 * portfolio-scoped (a `portfolio_id` anywhere on its ownership chain) ⇒ the
 * member portfolio's own doc; account-scoped-but-vault-referenced ⇒ the vault's
 * `common` doc. Exhaustiveness is the safety property: a kind with no bucket is
 * a kind the split silently drops, so [UNBUCKETED_KINDS] must always be empty
 * and the two sets must always be disjoint.
 *
 * ## Why this re-houses rather than re-types the lists
 *
 * The exhaustive partition already exists in this app as
 * [VaultV2Contract.PORTFOLIO_SCOPED_KINDS] / [VaultV2Contract.COMMON_SCOPED_KINDS]
 * — the v2 arc's mirror of the same platform decision. Copying those 26 strings
 * into a second literal list would create exactly the failure mode the partition
 * exists to prevent: two lists, one updated. So this object *derives* from them.
 *
 * ## The one real difference, recorded rather than hidden
 *
 * E0 moves **`cashBudget` and `cashBudgetFire` from `common` to `portfolio`**
 * ([E0_MOVED_TO_PORTFOLIO]). The v2 arc placed them in `common` because the
 * platform's v2 enumeration did; E0 re-derived every bucket from the live
 * schema's scoping columns and found budgets are portfolio-keyed. The E0 vector
 * suite pins the new answer explicitly (`expect(VAULT_ENTITY_DOC_BUCKETS
 * .cashBudget).toBe('portfolio')`), so this is a contract change, not a porting
 * mistake — and it is expressed as a named delta so the next reader sees both
 * the old placement and the reason it moved. Nothing consumes the v2 placement
 * and the pv placement at once: v2's split is the old account-level arc, this is
 * the per-vault one.
 *
 * Dormant like the rest of the epic — no caller outside tests, gated behind
 * `ParanoidVaultsFlags.enabled`.
 */
object PvDocBuckets {

    /** `VAULT_DOC_BUCKETS`. */
    const val BUCKET_PORTFOLIO: String = "portfolio"
    const val BUCKET_COMMON: String = "common"

    /**
     * The kinds whose bucket E0 corrected relative to the v2 partition: both are
     * portfolio-keyed budget rows, so they ride the member portfolio's doc.
     */
    val E0_MOVED_TO_PORTFOLIO: Set<String> = linkedSetOf("cashBudget", "cashBudgetFire")

    /** Entity kinds carried by each member portfolio's own doc (§5). */
    val PORTFOLIO_DOC_KINDS: Set<String> =
        VaultV2Contract.PORTFOLIO_SCOPED_KINDS + E0_MOVED_TO_PORTFOLIO

    /** Entity kinds carried by the vault-wide `common` doc (§5). */
    val COMMON_DOC_KINDS: Set<String> =
        VaultV2Contract.COMMON_SCOPED_KINDS - E0_MOVED_TO_PORTFOLIO

    /** Must always be empty — see the class note. */
    val UNBUCKETED_KINDS: Set<String> =
        VaultContract.ENTITY_KINDS.filterNot { it in PORTFOLIO_DOC_KINDS || it in COMMON_DOC_KINDS }
            .toSet()

    /** The bucket of one entity kind, or `null` when the kind is unknown. */
    fun bucketOf(kind: String): String? = when (kind) {
        in PORTFOLIO_DOC_KINDS -> BUCKET_PORTFOLIO
        in COMMON_DOC_KINDS -> BUCKET_COMMON
        else -> null
    }
}
