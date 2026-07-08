package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.TransactionEntity

/**
 * Pure display-filter logic for the transaction history (spec §6.2 —
 * "filterable by asset and type"). The platform's ledger endpoint has no
 * server-side filters (cursor+limit only), so these narrow the CACHED rows —
 * a display filter over server data, not a computation.
 */

enum class TxSideFilter { ALL, BUY, SELL }

fun filterTransactions(
    transactions: List<TransactionEntity>,
    side: TxSideFilter,
    assetId: String?,
): List<TransactionEntity> = transactions.filter { tx ->
    val sideOk = when (side) {
        TxSideFilter.ALL -> true
        TxSideFilter.BUY -> tx.side == "buy"
        TxSideFilter.SELL -> tx.side == "sell"
    }
    sideOk && (assetId == null || tx.assetId == assetId)
}

/** One distinct asset present in the cached ledger (filter-sheet rows). */
data class TxAssetOption(
    val assetId: String,
    val symbol: String,
    val name: String,
)

/** Distinct assets across the cached rows, alphabetical by symbol. */
fun distinctTxAssets(transactions: List<TransactionEntity>): List<TxAssetOption> =
    transactions
        .distinctBy { it.assetId }
        .map { TxAssetOption(it.assetId, it.assetSymbol, it.assetName) }
        .sortedBy { it.symbol.lowercase() }

/**
 * A row's trade value for display: quantity × unit price of the SAME server
 * row (the fee is listed separately) — the one product the reference web app
 * also renders per ledger row.
 */
fun transactionNotional(quantity: Double, price: Double): Double = quantity * price
