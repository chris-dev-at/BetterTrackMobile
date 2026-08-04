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
 * Transfers are a separate two-sided flow and are deliberately not in this list.
 */
val CASH_ENTRY_KINDS: List<CashKind> = listOf(CashKind.DEPOSIT, CashKind.WITHDRAWAL, CashKind.FEE)
