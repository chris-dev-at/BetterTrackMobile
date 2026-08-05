package at.bettertrack.app.ui.market

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.format.btFormatUnitPriceCore
import at.bettertrack.app.ui.theme.BtTheme
import java.util.Locale

/**
 * Format a UNIT price in its native currency (asset pages show USD/EUR/… directly
 * — [at.bettertrack.app.ui.components.formatEur] is EUR-only). Symbol-last, 2
 * decimals half-away-from-zero, but sub-cent prices (0 < |x| < 0.01) render up to
 * 6 significant decimals so a crypto tick never collapses to "0,00" (rule 4).
 */
fun formatPrice(value: Double, currency: String, locale: Locale): String =
    btFormatUnitPriceCore(value, currency, locale)

/** A localized human label for an asset type ("Stock", "ETF", "Crypto"…). */
@Composable
fun assetTypeLabel(type: String): String = rememberAssetTypeLabeller()(type)

/**
 * The same mapping as [assetTypeLabel], as a plain function that can be called
 * from outside a composition.
 *
 * Exists because the Portfolio donut groups holdings by `assetType` inside
 * `allocationSegments` — a pure, non-composable function — and therefore could
 * not call [assetTypeLabel]. Before R3 it carried its own private copy of this
 * `when`, returning hard-coded English, so the allocation ring said "Stocks",
 * "ETFs" and "Commodities" to a German user while the asset page beside it said
 * "Aktien", "ETFs" and "Rohstoffe". Handing the caller a resolver instead of
 * letting it re-implement the table is what makes a second divergence
 * impossible: there is one `when` over these server strings in the app, and it
 * is this one.
 *
 * The unknown-type branch is deliberately NOT a string resource: the server owns
 * this vocabulary and may add to it, and echoing a new type back capitalised is
 * more honest than mapping it to a catch-all label that claims to know it.
 */
@Composable
fun rememberAssetTypeLabeller(): (String) -> String {
    val stock = stringResource(R.string.bt_asset_type_stock)
    val etf = stringResource(R.string.bt_asset_type_etf)
    val index = stringResource(R.string.bt_asset_type_index)
    val fx = stringResource(R.string.bt_asset_type_fx)
    val commodity = stringResource(R.string.bt_asset_type_commodity)
    val crypto = stringResource(R.string.bt_asset_type_crypto)
    val custom = stringResource(R.string.bt_asset_type_custom)
    return remember(stock, etf, index, fx, commodity, crypto, custom) {
        { type ->
            when (type) {
                "stock" -> stock
                "etf" -> etf
                "index" -> index
                "fx" -> fx
                "commodity" -> commodity
                "crypto" -> crypto
                "custom" -> custom
                else -> type.replaceFirstChar { it.uppercase() }
            }
        }
    }
}

/**
 * The state-aware watchlist star — filled gold when on the list, outline
 * otherwise. Toggling never navigates (stays in place, §6.5).
 */
@Composable
fun WatchlistStar(
    inWatchlist: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    IconButton(onClick = onToggle, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = if (inWatchlist) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(
                if (inWatchlist) R.string.bt_watchlist_remove else R.string.bt_watchlist_add,
            ),
            tint = when {
                !enabled -> bt.border
                inWatchlist -> bt.gold
                else -> bt.textMuted
            },
        )
    }
}
