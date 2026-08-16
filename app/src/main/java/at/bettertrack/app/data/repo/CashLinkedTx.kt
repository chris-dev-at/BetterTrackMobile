package at.bettertrack.app.data.repo

import at.bettertrack.app.data.db.CashMovementEntity

/**
 * The cash coupling of one ledger transaction, and the rule for editing it.
 *
 * ## The problem this file exists to solve
 *
 * A trade can be *paid from* a wallet (`payFromCash`) or *settle into* one
 * (`addProceedsToCash`). When it is, the server writes a matching cash movement
 * beside the transaction — and then refuses to let the transaction's economics
 * change: `PATCH /portfolios/{id}/transactions/{txId}` throws
 * `TRANSACTION_CASH_LINKED` for any edit to side/quantity/price/fee/executedAt,
 * because the update contract (`updateTransactionRequestSchema`, `.strict()`)
 * carries no cash fields and the server will not let the two rows drift apart.
 * The cash side is closed in the same way: editing the movement returns
 * `CASH_MOVEMENT_NOT_EDITABLE` and points back at the trade. A deliberate closed
 * loop, and the reason the app used to dead-end the user with *"delete it and
 * add it again"*.
 *
 * The platform's own correction path (mirror replica-apply) does not have a
 * secret endpoint either — it performs **delete + re-create**, re-applying the
 * cash intent it reconstructs from the ledger. This file is that reconstruction,
 * as a pure function, so the app can do the same thing deliberately and
 * atomically instead of asking the user to do it by hand.
 *
 * ## Why the intent has to be reconstructed at all
 *
 * The transaction read model has no cash field — not in this app, not in the
 * contract, not for the web client. The coupling is only visible from the OTHER
 * side of the join: the movements carry `transactionId`. So the intent is
 * recovered by looking at which legs exist, exactly as the server does it.
 */

/**
 * What the cash legs of a transaction say its coupling was.
 *
 * @param payFromCash the trade was funded from a wallet (a `buy` leg exists).
 * @param addProceedsToCash the proceeds landed in a wallet (a `sell_proceeds`
 *   leg exists).
 * @param cashSourceId the wallet the legs belong to. Carried explicitly because
 *   the create endpoint defaults to Main when it is absent — a re-book that
 *   dropped it would move a trade's money to a different wallet.
 */
data class CashLink(
    val payFromCash: Boolean,
    val addProceedsToCash: Boolean,
    val cashSourceId: String?,
) {
    /** True when this transaction is coupled to cash at all. */
    val linked: Boolean get() = payFromCash || addProceedsToCash
}

/** The movement kinds that a TRADE books; every other kind is somebody else's row. */
private val TRADE_LEG_KINDS = setOf("buy", "sell_proceeds")

/**
 * Reconstruct a transaction's cash coupling from its movements.
 *
 * A literal translation of the server's `cashIntentForLocalTx`: filter the
 * portfolio's movements to this transaction's trade legs, then read the intent
 * off which kinds are present and take the wallet from the first leg.
 *
 * The wallet comes from the FIRST leg rather than from a search for agreement
 * because the server writes exactly one leg per trade — a buy leg or a proceeds
 * leg, never both — so "first" and "only" are the same row in practice, and
 * inventing a reconciliation rule for a case the server cannot produce would be
 * a guess dressed as robustness.
 *
 * [movements] may be the whole portfolio's list or a pre-filtered one; the
 * `transactionId` match is applied here either way, so callers cannot get it
 * subtly wrong in two places.
 */
fun cashLinkOf(transactionId: String, movements: List<CashMovementEntity>): CashLink {
    val legs = movements.filter { it.transactionId == transactionId && it.kind in TRADE_LEG_KINDS }
    return CashLink(
        payFromCash = legs.any { it.kind == "buy" },
        addProceedsToCash = legs.any { it.kind == "sell_proceeds" },
        cashSourceId = legs.firstOrNull()?.sourceId,
    )
}

/**
 * Whether an edit changes the transaction's ECONOMICS, i.e. whether the server's
 * cash guard applies to it.
 *
 * The guard fires on side, quantity, price, fee and executedAt. It deliberately
 * does NOT fire on the note: a note-only edit stays a plain `PATCH` and returns
 * 200 even on a cash-linked row, which is why this is a question and not an
 * assumption. Re-booking a transaction to correct a typo in its note would
 * destroy and recreate a ledger row — and change its id — for nothing.
 */
fun isFinancialEdit(
    sideChanged: Boolean,
    quantityChanged: Boolean,
    priceChanged: Boolean,
    feeChanged: Boolean,
    dateChanged: Boolean,
): Boolean = sideChanged || quantityChanged || priceChanged || feeChanged || dateChanged

/**
 * How an edit to a synced transaction must be delivered.
 *
 * Naming the decision as a value — rather than letting it live as a condition
 * inside the form's submit function — is what makes it testable, and this is a
 * decision worth testing: choosing PATCH for a cash-linked economic edit dead-
 * ends the user, and choosing RE-BOOK for a note change needlessly rewrites
 * their ledger.
 */
enum class TxEditRoute {
    /** Nothing changed; there is nothing to send. */
    NOTHING,

    /** A plain `PATCH` — the transaction is unlinked, or only the note moved. */
    PATCH,

    /** Delete + re-create, preserving the cash coupling. */
    REBOOK,
}

/**
 * Pick the delivery route for an edit.
 *
 * @param financial did the edit touch the economics ([isFinancialEdit]).
 * @param noteChanged did the note move.
 * @param link the transaction's reconstructed coupling ([cashLinkOf]).
 */
fun txEditRoute(financial: Boolean, noteChanged: Boolean, link: CashLink): TxEditRoute = when {
    !financial && !noteChanged -> TxEditRoute.NOTHING
    financial && link.linked -> TxEditRoute.REBOOK
    else -> TxEditRoute.PATCH
}
