package at.bettertrack.app.ui.workboard

import at.bettertrack.app.data.repo.AlertKind
import at.bettertrack.app.ui.portfolio.parseLocalizedDecimal
import java.util.Currency
import java.util.Locale

/**
 * Pure logic for the alert create/edit form (unit-tested; owner ask 2026-07-10).
 * Sentence assembly lives in the UI with string resources — this file only
 * validates and formats numbers, mirroring the TransactionFormLogic pattern.
 */

/** Comma/dot-tolerant threshold parse; null when not a positive number. */
fun parseAlertThreshold(raw: String): Double? {
    val v = parseLocalizedDecimal(raw) ?: return null
    return v.takeIf { it > 0.0 }
}

/**
 * A percent threshold additionally caps at 100 for the DOWN kinds — a price
 * cannot fall more than 100%. Up kinds are uncapped (a moonshot can +500%).
 */
fun alertThresholdValid(kind: AlertKind, threshold: Double?): Boolean {
    if (threshold == null || threshold <= 0.0) return false
    if (kind == AlertKind.PctDownFromRef || kind == AlertKind.PctDayDown) {
        return threshold <= 100.0
    }
    return true
}

/** "$" for USD, "€" for EUR, … falls back to the raw code. */
fun currencySymbol(code: String, locale: Locale = Locale.getDefault()): String =
    try {
        Currency.getInstance(code).getSymbol(locale)
    } catch (_: Exception) {
        code
    }

/** Locale decimal separator; whole numbers stay whole ("5"), else 2 decimals. */
fun formatAlertNumber(v: Double, locale: Locale = Locale.getDefault()): String {
    val nf = java.text.NumberFormat.getNumberInstance(locale)
    nf.minimumFractionDigits = if (v % 1.0 == 0.0) 0 else 2
    nf.maximumFractionDigits = 2
    return nf.format(v)
}

/** "$150" / "€80.50" — symbol-prefixed price in the asset's native currency. */
fun formatAlertPrice(v: Double, currency: String, locale: Locale = Locale.getDefault()): String =
    currencySymbol(currency, locale) + formatAlertNumber(v, locale)
