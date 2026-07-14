package at.bettertrack.app.ui.market

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
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
fun assetTypeLabel(type: String): String = when (type) {
    "stock" -> stringResource(R.string.bt_asset_type_stock)
    "etf" -> stringResource(R.string.bt_asset_type_etf)
    "index" -> stringResource(R.string.bt_asset_type_index)
    "fx" -> stringResource(R.string.bt_asset_type_fx)
    "commodity" -> stringResource(R.string.bt_asset_type_commodity)
    "crypto" -> stringResource(R.string.bt_asset_type_crypto)
    "custom" -> stringResource(R.string.bt_asset_type_custom)
    else -> type.replaceFirstChar { it.uppercase() }
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
