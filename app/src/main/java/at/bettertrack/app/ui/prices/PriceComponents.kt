package at.bettertrack.app.ui.prices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.formatMoney
import at.bettertrack.app.ui.portfolio.formatTxDate
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The rendered half of the no-live-prices states (S3/S4 plan §5 W6, item 2).
 *
 * Every composable here exists so a Drive-mode screen can be *specific* about
 * what it does not know. The alternative the plan forbids is a screen that stays
 * quiet and lets a partial number read as a complete one.
 */

/**
 * "manual, 3 Aug" — the provenance badge.
 *
 * A price the user entered in March is not a claim about today, and a bare
 * number next to a holding is read as today's. The badge is therefore not
 * decoration: it is the difference between a fact and a misreading, which is why
 * it always carries the date rather than just the word.
 *
 * Modelled on `SourceBadgeChip`'s pill so it reads as the same family of
 * "where this came from" markers the app already uses.
 */
@Composable
fun ManualPriceBadge(
    asOfIso: String?,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val label = buildString {
        append(stringResource(R.string.bt_price_manual_badge))
        formatPriceDate(asOfIso, locale)?.let { append(", ").append(it) }
    }
    Surface(
        modifier = modifier,
        color = bt.surface,
        contentColor = bt.textMuted,
        shape = BtShapes.pill,
        border = BorderStroke(1.dp, bt.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.EditNote,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = bt.textMuted,
            )
            Spacer(Modifier.width(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        }
    }
}

/**
 * One line of price text plus its provenance, for anywhere a quote would go.
 *
 * [AssetPriceState.Absent] renders "No price yet — add one" (or just
 * "No price yet" where the user cannot fix it) rather than a dash: a dash says
 * "nothing here", and the true statement is "nothing here *yet*, and you can
 * change that".
 */
@Composable
fun AssetPriceLine(
    state: AssetPriceState,
    locale: Locale,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = BtTheme.type.moneySmall,
) {
    val bt = BtTheme.colors
    when (state) {
        is AssetPriceState.Known -> Column(modifier) {
            Text(
                text = formatMoney(state.value, state.currency, locale),
                style = style,
                color = bt.textPrimary,
            )
            if (state.provenance == PriceProvenance.MANUAL) {
                Spacer(Modifier.padding(top = 3.dp))
                ManualPriceBadge(asOfIso = state.asOfIso, locale = locale)
            }
        }

        is AssetPriceState.Absent -> Text(
            text = stringResource(
                if (state.canAddManually) R.string.bt_price_none_hint else R.string.bt_price_none,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = modifier,
        )
    }
}

/**
 * "Excludes 3 holdings with no price" — the caveat that keeps a partial total
 * from reading as a complete one.
 *
 * Renders nothing when coverage is complete. That is deliberate: a permanent
 * disclaimer is noise users learn to skip, and this must still be readable as a
 * warning on the day it matters.
 */
@Composable
fun UnpricedNote(
    coverage: PriceCoverage,
    modifier: Modifier = Modifier,
) {
    if (coverage.complete) return
    Text(
        text = pluralStringResource(R.plurals.bt_price_unpriced_note, coverage.unpriced, coverage.unpriced),
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.textMuted,
        modifier = modifier,
    )
}

/**
 * The hero's designed empty for [NetWorthState.Unpriceable].
 *
 * Shown instead of `0,00 €` when nothing can be valued and there is no cash.
 * The copy leads with what IS safe (transactions and cash are on the device)
 * because the state is alarming enough to read as data loss if it is not
 * addressed directly.
 */
@Composable
fun NoPricesHero(modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.bt_price_unpriced_title),
            style = BtTheme.type.moneyLarge,
            color = bt.textMuted,
        )
        Text(
            text = stringResource(R.string.bt_price_unpriced_body),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

/** `yyyy-MM-dd` → the app's short date, or null when it cannot be parsed. */
internal fun formatPriceDate(iso: String?, locale: Locale): String? {
    if (iso == null) return null
    return try {
        val date = LocalDate.parse(iso)
        formatTxDate(date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), locale)
    } catch (_: Exception) {
        null
    }
}
