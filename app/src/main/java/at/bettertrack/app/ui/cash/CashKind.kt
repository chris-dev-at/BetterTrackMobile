package at.bettertrack.app.ui.cash

/**
 * The cash-ledger movement kinds as they appear on the v5 wire.
 *
 * Mirrors the platform's `cash_movement_kind` Postgres enum exactly (10 values,
 * verified against the running v5 backend). Before v5 the app only modelled six
 * of them and rendered everything else as its RAW wire token — `fee`,
 * `dividend`, `tax_withholding` and `tax_refund` all leaked untranslated into
 * the ledger. Mapping lives here, once, so both label and icon stay in sync.
 *
 * [handTyped] marks the kinds a user creates directly and may therefore edit or
 * delete. The rest are DERIVED: the server posts them as a side effect of a
 * parent row (a trade leg, a dividend inflow, a tax settlement, a transfer leg)
 * and answers `PATCH`/`DELETE` on them with `409 CASH_MOVEMENT_NOT_EDITABLE`.
 * The UI only OFFERS edit/delete on hand-typed kinds; the 409 mapping stays as
 * the backstop for anything this table gets wrong.
 */
enum class CashKind(val wire: String, val handTyped: Boolean) {
    /** User-entered money in. */
    DEPOSIT("deposit", handTyped = true),

    /** User-entered money out — does NOT drag performance. */
    WITHDRAWAL("withdrawal", handTyped = true),

    /** v5: user-entered cost (custody, order, account fees) — DOES drag performance. */
    FEE("fee", handTyped = true),

    /** Derived: cash leg of a buy paid from cash. */
    BUY("buy", handTyped = false),

    /** Derived: cash leg of a sell. */
    SELL_PROCEEDS("sell_proceeds", handTyped = false),

    /** Derived: outgoing leg of a source-to-source transfer. */
    TRANSFER_OUT("transfer_out", handTyped = false),

    /** Derived: incoming leg of a source-to-source transfer. */
    TRANSFER_IN("transfer_in", handTyped = false),

    /** Derived: cash inflow booked by a dividend. */
    DIVIDEND("dividend", handTyped = false),

    /** Derived: tax withheld at source (AT/DE tax modes). */
    TAX_WITHHOLDING("tax_withholding", handTyped = false),

    /** Derived: tax paid back. */
    TAX_REFUND("tax_refund", handTyped = false),
    ;

    companion object {
        fun fromWire(wire: String): CashKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * True when the ledger row may be edited/deleted by the user, i.e. the UI should
 * offer the actions at all. Unknown (future) kinds are treated as NOT editable —
 * offering an action that is certain to 409 is worse than hiding it.
 */
fun isEditableCashKind(wire: String): Boolean = CashKind.fromWire(wire)?.handTyped == true

/**
 * The kinds the cash entry form can create, in the order the chooser shows them.
 *
 * PAID leads (owner order 2026-08-17: *"1. option ist bezahlt und 2. erhalten"*),
 * so the edit sheet's kind chips read in the same order as the two buttons on the
 * cash overview and as the approved Cash-Wallet widget's tiles. The enum's own
 * declaration order stays wire-shaped; this list is the DISPLAY order and is the
 * only place it is decided.
 *
 * Transfers are a separate two-sided flow and are deliberately not in this list.
 */
val CASH_ENTRY_KINDS: List<CashKind> = listOf(CashKind.WITHDRAWAL, CashKind.DEPOSIT, CashKind.FEE)

/**
 * Which kind a cash entry is, from the two things the form actually asks.
 *
 * ## Web parity (owner, 2026-08-07)
 *
 * The owner's report: *"I specifically made the fee different in the web app —
 * there's a tick to subtract from performance or not; changing things up on the
 * phone is weird."* He is right about the drift. The web's current cash form
 * (`apps/web/src/user/portfolio/cashflow/RecordCashDialog.tsx`) asks TWO
 * questions — a Money-in/Money-out direction, and, only when the answer is out, a
 * "Holding cost" checkbox — and derives the kind from them:
 *
 * ```ts
 * const kind = direction === 'in' ? 'deposit' : countsToPerformance ? 'fee' : 'withdrawal';
 * ```
 *
 * This function is that line. The app used to ask instead by offering three
 * buttons — Deposit, Withdraw and a separate Fee — which made fee-ness a
 * different KIND of choice on each platform: a destination on the phone, a
 * property of an outflow on the web. The property is the truer model, because
 * "was that money spent on investing, or just spent?" is a question you answer
 * *about* a withdrawal you are already recording.
 *
 * The wire is unchanged and identical on both platforms: the create call carries
 * no kind at all, it is chosen by ENDPOINT (`/cash/deposit`, `/cash/withdraw`,
 * `/cash/fee`), and only a later PATCH names `kind` explicitly.
 */
fun cashEntryKind(inflow: Boolean, holdingCost: Boolean): CashKind = when {
    inflow -> CashKind.DEPOSIT
    holdingCost -> CashKind.FEE
    else -> CashKind.WITHDRAWAL
}
