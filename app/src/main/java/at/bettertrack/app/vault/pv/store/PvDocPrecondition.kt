package at.bettertrack.app.vault.pv.store

import at.bettertrack.app.vault.server.parseVaultEtag
import at.bettertrack.app.vault.server.vaultEtag

/**
 * **The CAS token of one vault doc, carried verbatim.**
 *
 * The deployed contract is HTTP preconditions, not numeric version comparison:
 * a read answers `ETag`, and a write must carry `If-Match: "<that ETag>"` (or
 * `If-None-Match: *` for a first creation). The v1 account-level client asserted
 * that the ETag's integer EQUALS the envelope's `docVersion` and called a
 * disagreement corruption (`ServerVaultDataHome.readEnvelope`). That assertion
 * is deliberately NOT carried over here, because E1 changed the fact it rested
 * on:
 *
 * > "The server never version-gates on `docVersion` — versioning is a client
 * > decision; the server stores verbatim and uses `docVersion` only for history
 * > addressing."
 *
 * With the server no longer deriving its row version from the header, "the ETag
 * is the envelope version" stops being contract-guaranteed, and asserting it
 * would turn a legal server response into a corruption verdict — in a store
 * where "corrupt" means the app refuses to write and the user's data stops
 * syncing. So this type treats the validator as **opaque**: whatever string came
 * back is what goes out again, byte for byte. [version] is offered as a
 * best-effort read for history addressing and diagnostics, and is allowed to be
 * `null` without that meaning anything is wrong.
 *
 * The parse itself is the shipped one (`parseVaultEtag`), reused rather than
 * re-ported: an optional weak marker and quotes are stripped, and anything that
 * is not a bare non-negative integer answers `null`.
 */
@JvmInline
value class PvDocEtag(
    /** The exact `ETag` header value, quotes included — what `If-Match` must repeat. */
    val header: String,
) {
    /** The integer inside the validator, when it is one. Never required to be. */
    val version: Int? get() = parseVaultEtag(header)

    override fun toString(): String = header

    companion object {
        /** `ETag: "<version>"` — the contract's own spelling. */
        fun of(version: Int): PvDocEtag = PvDocEtag(vaultEtag(version))

        /**
         * The validator a response carried, or `null` when it carried none.
         *
         * `*` is refused: it is the create wildcard, never a validator, and
         * letting it through would build an `If-Match: *` that means "replace
         * whatever is there" — the blind overwrite this whole layer exists to
         * make impossible.
         */
        fun parse(header: String?): PvDocEtag? {
            val trimmed = header?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed == "*") return null
            return PvDocEtag(trimmed)
        }
    }
}

/**
 * **The precondition a doc write MUST carry — with no way to spell "none".**
 *
 * `A PUT with no precondition is refused 428.` That refusal is a programming
 * error, not a runtime condition: there is no state of the world in which the
 * right thing to do is write a vault doc without saying what you expect to be
 * there. So it is made unrepresentable rather than handled, in three layers that
 * each have to be defeated separately:
 *
 * 1. **This type has exactly two inhabitants** — [CreateOnly] and [Replace] —
 *    and [Replace] holds a non-null [PvDocEtag]. There is no `None`, no
 *    nullable field, and no default. A caller cannot construct "no precondition"
 *    to pass in.
 * 2. **`BtApi` has no unconditional PUT.** The two doc-write methods are
 *    `pvCreateVaultDoc`, whose `If-None-Match: *` is baked into a static
 *    `@Headers` annotation and so cannot be omitted or mistyped at a call site,
 *    and `pvReplaceVaultDoc`, whose `If-Match` is a non-null `String` parameter
 *    that Kotlin's null-safety makes a compile error to leave out.
 * 3. **A discipline test reads the interface back** and fails if any `PUT` under
 *    `vaults/{vaultId}/docs/` ever appears without one of those two.
 *
 * Layer 3 is what keeps layers 1 and 2 from being quietly undone later.
 */
sealed interface PvDocPrecondition {

    /**
     * `If-None-Match: *` — "create, and fail if anything is already there".
     * The only correct precondition for a doc's very first write.
     *
     * Carries no header value of its own on purpose: the wildcard has exactly one
     * legal spelling and it lives in `BtApi.pvCreateVaultDoc`'s static `@Headers`
     * annotation. A second copy here would be a second thing to get wrong, and
     * the discipline test would then be checking the wrong one.
     */
    data object CreateOnly : PvDocPrecondition

    /**
     * `If-Match: "<etag>"` — replace exactly the version that was read.
     *
     * [headerValue] is the validator VERBATIM. The server compares validators,
     * not integers, so this client repeats what it was given rather than
     * re-deriving anything from it.
     */
    @JvmInline
    value class Replace(val etag: PvDocEtag) : PvDocPrecondition {
        val headerValue: String get() = etag.header
    }
}
