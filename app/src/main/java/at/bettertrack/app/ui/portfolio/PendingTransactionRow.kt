package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.theme.BtTheme
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * §7.4: queued ledger events render alongside the synced ledger as clearly
 * pending rows — same card anatomy as [TransactionRow] plus the inline sync
 * badge. They are annotations next to server truth, never folded into
 * server-computed totals (§7.1).
 */

/** The small inline sync badge (§7.4) — one look everywhere it appears. */
@Composable
fun PendingStatusBadge(status: PendingUiStatus) {
    when (status) {
        PendingUiStatus.PENDING -> BtBadge(
            text = stringResource(R.string.bt_pending_badge),
            kind = BtBadgeKind.Gold,
        )

        PendingUiStatus.SYNCING -> BtBadge(
            text = stringResource(R.string.bt_pending_badge_syncing),
            kind = BtBadgeKind.Neutral,
        )

        PendingUiStatus.NEEDS_ATTENTION -> BtBadge(
            text = stringResource(R.string.bt_pending_badge_attention),
            kind = BtBadgeKind.Loss,
        )

        PendingUiStatus.DONE -> BtBadge(
            text = stringResource(R.string.bt_pending_badge_done),
            kind = BtBadgeKind.Gain,
        )
    }
}

/**
 * A queued buy/sell in the ledger/holding lists. Tap opens the queue editor
 * for editable states (pending / needs-attention); a syncing row is hands-off
 * until the engine reconciles its outcome (§7.3).
 */
@Composable
fun PendingTransactionRow(
    row: PendingTxRow,
    showAsset: Boolean = true,
    onEdit: ((PendingTxRow) -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val editable = row.status == PendingUiStatus.PENDING ||
        row.status == PendingUiStatus.NEEDS_ATTENTION
    BtCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (editable && onEdit != null) {
            { onEdit(row) }
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtBadge(
                text = stringResource(if (row.isBuy) R.string.bt_tx_side_buy else R.string.bt_tx_side_sell),
                kind = if (row.isBuy) BtBadgeKind.Gain else BtBadgeKind.Loss,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (showAsset) {
                            row.assetSymbol
                        } else {
                            formatTxDate(row.executedAtMs, locale)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    PendingStatusBadge(row.status)
                }
                Spacer(Modifier.height(2.dp))
                val amountPrice =
                    "${formatQuantity(row.quantity, locale)} × ${formatEur(row.price, locale)}"
                Text(
                    text = if (showAsset) {
                        formatTxDate(row.executedAtMs, locale) + " · " + amountPrice
                    } else {
                        amountPrice
                    },
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The server's rejection reason, right on the row (§7.3).
                if (row.status == PendingUiStatus.NEEDS_ATTENTION && row.serverError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = row.serverError,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.lossSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            MoneyText(
                value = transactionNotional(row.quantity, row.price),
                style = BtTheme.type.moneySmall,
            )
        }
    }
}
