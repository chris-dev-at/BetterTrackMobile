package at.bettertrack.app.ui.format

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Canonical display-layer number/money formatting — the single source of truth
 * the app's public helpers ([at.bettertrack.app.ui.components.formatMoney],
 * `formatEur`, `formatPercent`, [at.bettertrack.app.ui.market.formatPrice],
 * [at.bettertrack.app.ui.portfolio.formatQuantity], `formatWeight`) all delegate
 * to. These mirror the web client's as-shipped formatter 1:1 (PLATFORM_ASKS
 * #18/#19, web PR #442) so both apps render every number identically.
 *
 * Rules (verbatim from #19):
 *  1. **Fiat money** — exactly 2 decimals, **half-away-from-zero** rounding,
 *     locale separators, **symbol-last** ("1.234,56 €", "-50,00 $");
 *     null/NaN/±Infinity → em dash.
 *  2. **Percent** — 2 decimals; DE has a space before "%", EN none; the signed
 *     variant prepends "+"/"-" but shows nothing for zero ("0,00 %").
 *  3. **Quantities** — whole numbers plain ("12"), fractional up to 8 decimals
 *     with trailing zeros trimmed.
 *  4. **Unit prices** — 0 < |x| < 0.01 → up to 6 significant decimals
 *     ("0,000012 €"); exactly 0 and ≥ 0.01 → rule 1.
 *  5. Locale/currency come only from the i18n layer (passed in by the caller).
 *
 * STRICTLY display-only: nothing here parses input, mutates stored values, or
 * decides what goes on the wire.
 */

/** Rendered in place of an absent or non-finite value (rule 1). */
const val BT_EM_DASH: String = "—"

/**
 * Locale-aware currency symbol ("USD"→"$", "EUR"→"€"), falling back to the raw
 * ISO code when the JVM can't resolve it. Case-insensitive on the code.
 */
internal fun btMoneySymbol(code: String, locale: Locale): String =
    try {
        Currency.getInstance(code.uppercase(Locale.ROOT)).getSymbol(locale)
    } catch (_: Exception) {
        code
    }

/**
 * Collapse an exact negative zero to positive zero so a computed delta never
 * renders a stray leading minus (mirrors the web's `withoutNegativeZero`). Only
 * exact ±0 is collapsed — a tiny negative like -0.001 keeps its sign, exactly as
 * the web (Intl) does.
 */
private fun withoutNegativeZero(value: Double): Double = if (value == 0.0) 0.0 else value

private fun isFinite(value: Double?): Boolean = value != null && !value.isNaN() && !value.isInfinite()

/**
 * Rule 1 — fiat money, symbol-last, exactly 2 decimals, half-away-from-zero.
 * [showSign] prepends a literal "+" for positive values (gain/loss money);
 * negatives always carry the locale minus from the number itself.
 *
 * Rounds on the shortest round-trip decimal ([BigDecimal.valueOf]) so a value the
 * user sees as `2.125` rounds half-away-from-zero to `2,13` deterministically,
 * rather than on the raw IEEE bits.
 */
internal fun btFormatMoneyCore(
    value: Double?,
    currencyCode: String,
    locale: Locale,
    showSign: Boolean,
): String {
    if (!isFinite(value)) return BT_EM_DASH
    val bd = BigDecimal.valueOf(withoutNegativeZero(value!!)).setScale(2, RoundingMode.HALF_UP)
    val nf = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = true
    }
    val num = nf.format(bd)
    val signed = if (showSign && bd.signum() > 0) "+$num" else num
    return "$signed ${btMoneySymbol(currencyCode, locale)}"
}

/**
 * Rule 4 — unit price. 0 < |x| < 0.01 renders up to 6 SIGNIFICANT decimals with
 * trailing zeros trimmed ("0,000012 €") so a sub-cent price never collapses to
 * "0,00"; exactly 0 and |x| ≥ 0.01 fall through to rule 1. Symbol-last.
 */
internal fun btFormatUnitPriceCore(value: Double?, currencyCode: String, locale: Locale): String {
    if (!isFinite(value)) return BT_EM_DASH
    val v = value!!
    val magnitude = kotlin.math.abs(v)
    if (magnitude > 0.0 && magnitude < 0.01) {
        val bd = BigDecimal.valueOf(v)
            .round(MathContext(6, RoundingMode.HALF_UP))
            .stripTrailingZeros()
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 20
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = true
        }
        return "${nf.format(bd)} ${btMoneySymbol(currencyCode, locale)}"
    }
    return btFormatMoneyCore(v, currencyCode, locale, showSign = false)
}

/**
 * Rule 2 — percent, 2 decimals. DE (and every non-English locale) puts a space
 * before "%", EN doesn't. When [signed], prepends "+" for positive values and
 * shows nothing for values that round to zero ("0,00 %"); negatives keep the
 * locale minus. Half-away-from-zero rounding.
 */
internal fun btFormatPercentCore(value: Double?, locale: Locale, signed: Boolean): String {
    if (!isFinite(value)) return BT_EM_DASH
    val rounded = BigDecimal.valueOf(value!!).setScale(2, RoundingMode.HALF_UP)
    // Collapse a value that rounds to zero (incl. -0.00) so no stray sign shows.
    val display = if (rounded.signum() == 0) BigDecimal.ZERO.setScale(2) else rounded
    val nf = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = true
    }
    val num = nf.format(display)
    val sign = if (signed && display.signum() > 0) "+" else ""
    val space = if (locale.language == "en") "" else " "
    return "$sign$num$space%"
}

/**
 * Rule 3 — bare quantity, whole numbers plain, fractional up to 8 decimals with
 * trailing zeros trimmed. null/non-finite → em dash.
 */
internal fun btFormatQuantityCore(value: Double?, locale: Locale): String {
    if (!isFinite(value)) return BT_EM_DASH
    val bd = BigDecimal.valueOf(withoutNegativeZero(value!!))
    val nf = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 8
        roundingMode = RoundingMode.HALF_UP
        isGroupingUsed = true
    }
    return nf.format(bd)
}
