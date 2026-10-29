package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.ui.format.btFormatMoneyCore
import at.bettertrack.app.ui.format.btFormatUnitPriceCore
import java.util.Locale

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

/**
 * A trade's unit price, in the asset's NATIVE currency — the rule the web's
 * ledger applies (`MoneyText amount={price} currency={asset.currency} unitPrice`).
 *
 * A transaction's `price` and `fee` are native: a 6 × 265,76 AAPL buy is
 * dollars, and the server keeps no EUR copy of them. The rows used to run
 * every one of them through the EUR formatter, printing "6 × 265,76 €" for a
 * USD trade (tablet smoke 2026-10-29). Unit-price precision (rule 4), so a
 * sub-cent crypto tick keeps its digits.
 *
 * [currency] is null only for a queued op that predates the field: that figure
 * renders as a bare number rather than borrowing a symbol it may not have —
 * never a € sign on a figure nobody said was euro.
 */
fun formatTradePrice(price: Double, currency: String?, locale: Locale): String =
    btFormatUnitPriceCore(price, currency.orEmpty(), locale).trimEnd()

/** A trade's money figure — notional or fee — in the asset's native currency (see [formatTradePrice]). */
fun formatTradeAmount(value: Double, currency: String?, locale: Locale): String =
    btFormatMoneyCore(value, currency.orEmpty(), locale, showSign = false).trimEnd()
