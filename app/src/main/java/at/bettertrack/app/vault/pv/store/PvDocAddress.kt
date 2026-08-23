package at.bettertrack.app.vault.pv.store

import at.bettertrack.app.data.api.dto.VaultConfigDto
import at.bettertrack.app.vault.pv.envelope.PvVaultContract

/**
 * **Doc addressing** — the rule the whole per-vault store is shaped around.
 *
 * A vault's document set is: at most one `header` doc, at most one `common` doc,
 * and one `portfolio` doc per member portfolio (`paranoid-design.md` §5). The two
 * singletons carry their own client-minted uuids, registered on the vault row at
 * creation (`CreateVaultRequest.headerDocId` / `.commonDocId`). For every other
 * doc the contract's rule is identity, not lookup:
 *
 * > for `docKind: 'portfolio'`, **`docId` IS the portfolio UUID**.
 *
 * ## Why this file is built the way it is
 *
 * A mapping table between portfolio ids and doc ids would be a correctness bomb:
 * two ids for one thing, one of them stale, and a doc written to an address the
 * next reader does not look at — in a store where the server cannot read the
 * bytes and tell anyone they went to the wrong place. So the identity is made
 * structural rather than documented:
 *
 * - [PvDocRef.Portfolio] is a `value class` over the portfolio uuid. It has
 *   exactly ONE field, so there is physically nowhere to put a second id, and
 *   `docId` is a getter that returns it. A future author cannot "add the doc id
 *   next to it" without deleting the value class first.
 * - Every store entry point takes a [PvDocRef], never a bare `docId: String`, so
 *   an id can only reach the wire through a constructor that already knows which
 *   kind it is.
 * - [PvVaultDocDirectory] is the ONLY place that turns a raw doc id back into a
 *   kind, it does so from the vault row's two registered singletons, and it
 *   refuses a vault whose two singletons collide.
 *
 * `PvBlobStoreDisciplineTest` holds all three of those properties.
 *
 * ## The kind is what selects the size cap
 *
 * Server-side the per-kind ceiling (header 1 MiB, common 4 MiB, portfolio 8 MiB)
 * is chosen by the **validated** kind, so a header doc cannot claim
 * `docKind: 'portfolio'` in its envelope to borrow the bigger one. This client
 * validates the same way and in the same order: the kind comes from the vault's
 * registered ids via [PvVaultDocDirectory], and the envelope's own `docKind`
 * claim is then checked AGAINST it rather than trusted.
 *
 * Dormant behind `ParanoidVaultsFlags.enabled` — no caller outside `vault/pv/…`
 * and its tests.
 */
enum class PvDocKind(val wire: String) {
    HEADER(PvVaultContract.KIND_HEADER),
    COMMON(PvVaultContract.KIND_COMMON),
    PORTFOLIO(PvVaultContract.KIND_PORTFOLIO),
    ;

    /**
     * The per-kind ciphertext ceiling, read from the shared contract constant
     * rather than repeated — one table, so a re-tuned cap moves in one place.
     * The server refuses past it; it never truncates.
     */
    val maxBytes: Int get() = PvVaultContract.DOC_MAX_BYTES_DEFAULTS.getValue(wire)

    companion object {
        fun ofWire(wire: String): PvDocKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** One addressable document of one vault. */
sealed interface PvDocRef {
    val docId: String
    val kind: PvDocKind

    /** The vault's single `header` doc, at its registered uuid. */
    @JvmInline
    value class Header(override val docId: String) : PvDocRef {
        override val kind: PvDocKind get() = PvDocKind.HEADER
    }

    /** The vault's single `common` doc, at its registered uuid. */
    @JvmInline
    value class Common(override val docId: String) : PvDocRef {
        override val kind: PvDocKind get() = PvDocKind.COMMON
    }

    /**
     * One member portfolio's doc. **[docId] is [portfolioId]** — the same value,
     * not a value derived from it, which is why this class holds one field.
     */
    @JvmInline
    value class Portfolio(val portfolioId: String) : PvDocRef {
        override val docId: String get() = portfolioId
        override val kind: PvDocKind get() = PvDocKind.PORTFOLIO
    }
}

/** Why a doc reference could not be resolved or accepted. */
class PvDocAddressError(message: String) : IllegalArgumentException(message)

/**
 * The doc addresses of ONE vault, derived from its configuration row.
 *
 * Built from [VaultConfigDto] rather than assembled by hand so the two singleton
 * ids can only ever be the ones the server actually holds — the same two the
 * client minted at creation. A directory whose singletons collide is refused at
 * construction: `headerDocId == commonDocId` would make [refOf] ambiguous, and
 * an ambiguous address in a blind store resolves silently and wrongly.
 */
class PvVaultDocDirectory(
    val vaultId: String,
    val headerDocId: String,
    val commonDocId: String,
) {
    init {
        if (headerDocId == commonDocId) {
            throw PvDocAddressError(
                "Vault $vaultId registers the same id for its header and common docs.",
            )
        }
    }

    val header: PvDocRef.Header get() = PvDocRef.Header(headerDocId)

    val common: PvDocRef.Common get() = PvDocRef.Common(commonDocId)

    /**
     * The doc of one member portfolio.
     *
     * Refuses a portfolio id that equals one of the singletons. That collision
     * cannot happen with well-minted uuids, and precisely because it cannot, a
     * caller that produced one is holding a wrong id — writing 8 MiB of portfolio
     * ciphertext over the vault's header would be the consequence.
     */
    fun portfolio(portfolioId: String): PvDocRef.Portfolio {
        if (portfolioId == headerDocId || portfolioId == commonDocId) {
            throw PvDocAddressError(
                "Portfolio $portfolioId collides with a singleton doc id of vault $vaultId.",
            )
        }
        return PvDocRef.Portfolio(portfolioId)
    }

    /**
     * The **validated kind** of a raw doc id — the resolution the size cap and
     * every envelope check key off.
     *
     * Total by construction: an id that is neither registered singleton is a
     * portfolio doc, and then it IS the portfolio uuid.
     */
    fun refOf(docId: String): PvDocRef = when (docId) {
        headerDocId -> PvDocRef.Header(docId)
        commonDocId -> PvDocRef.Common(docId)
        else -> PvDocRef.Portfolio(docId)
    }

    /** True when [ref] addresses a doc of THIS vault at the kind this vault gives it. */
    fun accepts(ref: PvDocRef): Boolean = refOf(ref.docId) == ref

    companion object {
        fun of(config: VaultConfigDto): PvVaultDocDirectory = PvVaultDocDirectory(
            vaultId = config.id,
            headerDocId = config.headerDocId,
            commonDocId = config.commonDocId,
        )
    }
}
