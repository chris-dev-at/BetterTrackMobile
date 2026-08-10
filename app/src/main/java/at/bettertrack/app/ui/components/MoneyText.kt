package at.bettertrack.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import at.bettertrack.app.ui.format.btFormatCompactMoneyCore
import at.bettertrack.app.ui.format.btFormatCompactNumberCore
import at.bettertrack.app.ui.format.btFormatMoneyCore
import at.bettertrack.app.ui.format.btFormatPercentCore
import at.bettertrack.app.ui.format.btMoneySymbol
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale

/**
 * How a money value is colored.
 */
enum class MoneyColorMode {
    /** Inherit the ambient content color (plain value display). */
    Neutral,

    /** Gain/loss coloring: emerald for positive, red for negative (spec §3.3). */
    GainLoss,
}

/**
 * EUR money text (spec §3.4/§6.13): locale-aware EUR formatting (de-AT style in
 * German), optional gain/loss coloring, and tabular figures via the money text
 * styles so digit columns always align.
 */
@Composable
fun MoneyText(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = BtTheme.type.moneyMedium,
    colorMode: MoneyColorMode = MoneyColorMode.Neutral,
    showSign: Boolean = false,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val resolvedColor = when {
        color.isSpecified -> color
        colorMode == MoneyColorMode.GainLoss && value > 0.0 -> bt.gain
        colorMode == MoneyColorMode.GainLoss && value < 0.0 -> bt.loss
        colorMode == MoneyColorMode.GainLoss -> bt.textSecondary
        else -> Color.Unspecified
    }
    Text(
        text = formatEur(value, locale, showSign),
        modifier = modifier,
        style = style,
        color = resolvedColor,
        textAlign = textAlign,
        maxLines = 1,
    )
}

/**
 * Formats a EUR amount per locale, symbol-last, 2 decimals half-away-from-zero
 * (rule 1); optionally with an explicit "+" for gains. Delegates to the shared
 * [btFormatMoneyCore] so money renders identically to the web client.
 */
fun formatEur(value: Double, locale: Locale, showSign: Boolean = false): String =
    btFormatMoneyCore(value, "EUR", locale, showSign)

/**
 * The locale-aware symbol for a currency code ("USD"→"$", "EUR"→"€", "GBP"→"£"),
 * falling back to the raw code for anything the JVM can't resolve. Shared by the
 * transaction form's per-asset native-price labels.
 */
fun currencySymbol(code: String, locale: Locale = Locale.getDefault()): String =
    btMoneySymbol(code, locale)

/**
 * Formats an amount in the asset's NATIVE currency (per-asset prices/order totals
 * are native — only portfolio-level totals are EUR, §6.13). Symbol-last, 2 decimals
 * half-away-from-zero (rule 1) for every currency via [btFormatMoneyCore].
 */
fun formatMoney(value: Double, currencyCode: String, locale: Locale, showSign: Boolean = false): String =
    btFormatMoneyCore(value, currencyCode, locale, showSign)

/**
 * Formats a percentage given in percent units (rule 2): 2 decimals, DE space
 * before "%", EN none; explicit "+" for positive values when [showSign], nothing
 * for zero. Delegates to [btFormatPercentCore].
 */
fun formatPercent(value: Double, locale: Locale, showSign: Boolean = true): String =
    btFormatPercentCore(value, locale, signed = showSign)

/**
 * Formats a **corporate** money figure abbreviated (rule 7): "416,2 Mrd. $" (DE),
 * "416.2B $" (EN); under 1000 it falls through to the exact rule-1 rendering.
 *
 * For public company data — revenue, net income, market cap — NOT for the user's
 * own money, which must stay exact and maskable through [formatMoney]. Unlike
 * rule 1 this does not blank in discreet mode; see [btFormatCompactMoneyCore].
 */
fun formatCompactMoney(value: Double, currencyCode: String, locale: Locale): String =
    btFormatCompactMoneyCore(value, currencyCode, locale)

/** [formatCompactMoney] without the currency symbol — for chart axis labels. */
fun formatCompactNumber(value: Double, locale: Locale): String =
    btFormatCompactNumberCore(value, locale)
