package at.bettertrack.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import at.bettertrack.app.ui.theme.BtTheme
import java.text.NumberFormat
import java.util.Currency
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
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
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

/** Formats a EUR amount per locale; optionally with an explicit "+" for gains. */
fun formatEur(value: Double, locale: Locale, showSign: Boolean = false): String {
    val nf = NumberFormat.getCurrencyInstance(locale)
    nf.currency = Currency.getInstance("EUR")
    val formatted = nf.format(value)
    return if (showSign && value > 0.0) "+$formatted" else formatted
}

/**
 * The locale-aware symbol for a currency code ("USD"→"$", "EUR"→"€", "GBP"→"£"),
 * falling back to the raw code for anything the JVM can't resolve. Shared by the
 * transaction form's per-asset native-price labels (Step 19) — mirrors the
 * `currencySymbol()` pattern in `ui/workboard/AlertFormLogic.kt`.
 */
fun currencySymbol(code: String, locale: Locale = Locale.getDefault()): String =
    try {
        Currency.getInstance(code.uppercase(Locale.ROOT)).getSymbol(locale)
    } catch (_: Exception) {
        code
    }

/**
 * Formats an amount in the asset's NATIVE currency (per-asset prices/order totals
 * are native — only portfolio-level totals are EUR, §6.13). EUR routes through
 * [formatEur] so de-AT styling is identical; any other code uses that currency's
 * locale-aware format, falling back to a symbol-prefixed number if the JVM has
 * no format for it.
 */
fun formatMoney(value: Double, currencyCode: String, locale: Locale, showSign: Boolean = false): String {
    val code = currencyCode.uppercase(Locale.ROOT)
    if (code == "EUR") return formatEur(value, locale, showSign)
    val formatted = try {
        val nf = NumberFormat.getCurrencyInstance(locale)
        nf.currency = Currency.getInstance(code)
        nf.format(value)
    } catch (_: Exception) {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        currencySymbol(code, locale) + nf.format(value)
    }
    return if (showSign && value > 0.0) "+$formatted" else formatted
}

/**
 * Formats a percentage given in percent units (e.g. 3.42 → "+3.42%" / "+3,42 %"),
 * locale-aware decimal separator, explicit sign for positives when [showSign].
 */
fun formatPercent(value: Double, locale: Locale, showSign: Boolean = true): String {
    val nf = NumberFormat.getNumberInstance(locale)
    nf.minimumFractionDigits = 2
    nf.maximumFractionDigits = 2
    val sign = if (showSign && value > 0.0) "+" else ""
    return "$sign${nf.format(value)}%"
}
