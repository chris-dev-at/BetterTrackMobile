package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.HoldingEntity

/**
 * Display rules for the Holdings list (owner UI batch 2026-08-16).
 *
 * Pure Kotlin — no Compose — so both rules are unit tests rather than
 * screenshots, and so the overview screen, its view model and the insights
 * subpage cannot each grow a private variant.
 */

/**
 * The holdings a portfolio SURFACE may show: every position that is not
 * completely sold.
 *
 * The server keeps a row for a sold-out position (`quantity == 0`) because the
 * ledger behind it still exists — realized P&L, tax years, transaction history
 * all hang off it. The *display* has nothing to say about it: it is worth
 * nothing, weighs nothing, and (owner report: a 0-quantity PEBIX row) drags a
 * "no price" caveat into banners about money that is not there. So it is
 * filtered HERE, once, as a display rule — the server's totals are rendered
 * untouched (§7.1), and the position's history stays reachable through the
 * transactions screen.
 *
 * Exact `0.0`, deliberately: a dust position (`0.00000001 BTC`) is real and
 * must stay visible. Only a position the ledger has fully closed nets to an
 * exact server-computed zero.
 */
fun visibleHoldings(holdings: List<HoldingEntity>): List<HoldingEntity> =
    holdings.filter { it.quantity != 0.0 }

/**
 * The ticker annotation that follows an asset's name on a holdings row, or null
 * when there is nothing worth adding (owner order 2026-08-17: *"add the short
 * (BAYN.DE for Bayer for example) to the end of the name … like grayish and
 * thin"*).
 *
 * The name answers *what is this*; the symbol answers *which listing exactly* —
 * two Bayers on two exchanges are one name and two tickers, and the row was
 * showing only the ambiguous half. It is an annotation, never a second title:
 * the row prints it at the NAME's size (owner, 2026-08-17) and keeps it
 * secondary by ink and weight alone — muted and Normal against the name's
 * SemiBold — and the NAME is what ellipsizes when the pair does not fit,
 * because a truncated name is still recognisable while a truncated ticker
 * identifies nothing.
 *
 * Returns null when:
 *  - there is no symbol at all (a custom asset the user typed a name for);
 *  - the name simply IS the symbol — `BTC-USD` / `BTC-USD` would print the same
 *    string twice, which reads as a rendering bug rather than as detail;
 *  - the name already ENDS with the symbol (some server names arrive as
 *    "Bayer AG BAYN.DE"), for the same reason.
 *
 * Case- and whitespace-insensitive, because those are formatting differences
 * between two server fields rather than a real distinction.
 */
fun holdingTicker(assetName: String, assetSymbol: String): String? {
    val symbol = assetSymbol.trim()
    if (symbol.isEmpty()) return null
    val name = assetName.trim()
    if (name.equals(symbol, ignoreCase = true)) return null
    if (name.endsWith(symbol, ignoreCase = true)) return null
    return symbol
}

/** The two orders the holdings list can be read in. */
enum class HoldingsSort { ALLOCATION, PROFIT }

/**
 * The list in the chosen order.
 *
 * ALLOCATION is the DAO's own order (`marketValueEur DESC`) restated, so the
 * default render never re-sorts what Room already delivered sorted — it is
 * here so PROFIT has a symmetric, testable counterpart. Nulls sink to the
 * bottom in both orders: a row with no price (or no P&L yet) has no rank to
 * claim, and floating it would put the least-known rows first.
 *
 * PROFIT ranks by unrealized P&L in EUR — the owner's "Profit" toggle — best
 * first, losses last, so the two ends of the list are the two answers the sort
 * exists for.
 */
fun sortedHoldings(holdings: List<HoldingEntity>, sort: HoldingsSort): List<HoldingEntity> =
    when (sort) {
        HoldingsSort.ALLOCATION -> holdings.sortedByDescending { it.marketValueEur ?: Double.NEGATIVE_INFINITY }
        HoldingsSort.PROFIT -> holdings.sortedByDescending { it.unrealizedPnlEur ?: Double.NEGATIVE_INFINITY }
    }
