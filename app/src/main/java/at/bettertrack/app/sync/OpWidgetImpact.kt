package at.bettertrack.app.sync

/**
 * Does a landed op of this type move a figure the CASH widgets draw?
 *
 * An exhaustive `when`, deliberately: a new [OpType] does not compile until
 * somebody decides which widgets it moves.
 *
 *  * the four cash ops move a wallet by definition;
 *  * trades move the net-worth hero (holdings), and a cash-coupled one
 *    (`payFromCash` / `addProceedsToCash`, and every re-book — that op exists
 *    only for cash-linked trades) moves a wallet too. Telling the two apart
 *    would mean parsing the payload to save one cheap, debounced pass that
 *    fetches no quotes — not worth a second rule;
 *  * a custom-asset value point moves an investment valuation only.
 */
val OpType.touchesCashWidgets: Boolean
    get() = when (this) {
        OpType.CASH_DEPOSIT,
        OpType.CASH_WITHDRAW,
        OpType.CASH_FEE,
        OpType.CASH_TRANSFER,
        OpType.TX_BUY,
        OpType.TX_SELL,
        OpType.TX_REBOOK,
        -> true

        OpType.CUSTOM_ASSET_VALUE_POINT -> false
    }
