package at.bettertrack.web

import at.bettertrack.app.domain.jsNumberToString
import kotlin.math.abs

/**
 * Locale PRESENTATION of numbers the shared domain engine produced.
 *
 * ## Read this before reusing any of it
 *
 * This is a deliberately small, deliberately temporary shim, and it is NOT the
 * app's money formatter. The audited one is `ui/format/BtNumberFormat.kt` in
 * `:app`: 194 lines on `java.text.NumberFormat` (ICU), `BigDecimal` and
 * `Currency.getSymbol`, contractually byte-identical to the web client
 * (PLATFORM_ASKS #18/#19). It is one of the three byte-identity formatters
 * docs/KMP_PLAN.md §4.2 names as the sharpest correctness risk in the whole
 * port, and R8 has it as 1-of-3 closed. Porting it is real, vector-gated work
 * (decision D1) and it is scheduled as W3 — it is emphatically NOT something to
 * reimplement by hand in a bring-up phase.
 *
 * What this file therefore does is the strictly smaller job: it takes the
 * canonical decimal string the SHARED engine already produced via
 * [jsNumberToString] — the same audited `expect`/`actual` path that decides
 * vault plaintext and the GCM AAD header — and only moves separators around it
 * to the de-AT conventions rule 1/2/3 of BtNumberFormat specify:
 *
 *  - money: exactly 2 decimals, HALF-UP, locale separators, symbol LAST
 *    ("1.234,56 €" in de-AT, "1,234.56 €" in en);
 *  - percent: 2 decimals, with a space before `%` in DE and none in EN
 *    ("+12,89 %" / "+12.89%");
 *  - quantity: whole numbers plain, otherwise up to 8 decimals, zeros trimmed.
 *
 * The separators and the percent spacing come from [BtWebLocale], seeded from
 * `navigator.language` (W1). That is the only thing the locale changes here —
 * the VALUE is whatever the shared engine produced, and rounding still happens
 * on the decimal string rather than on a re-derived number.
 *
 * The rounding is done on the DECIMAL STRING, not by re-deriving the value, so
 * nothing here can disagree with the engine about what the number is — the worst
 * it can do is disagree about how to spell it, which W3 then settles for real.
 */
internal const val WEB_EM_DASH: String = "—"

/** Add 1 to a big-endian decimal digit string; grows by one digit on all-nines. */
private fun incDecimal(s: String): String {
    val c = s.toCharArray()
    var i = c.size - 1
    while (i >= 0) {
        if (c[i] == '9') {
            c[i] = '0'
            i--
        } else {
            c[i] = c[i] + 1
            return c.concatToString()
        }
    }
    return "1" + c.concatToString()
}

/**
 * `value` (finite, >= 0) as a plain decimal string with exactly [scale] fraction
 * digits, rounded HALF-UP — the same mode `BigDecimal.setScale(2, HALF_UP)` gives
 * the Android formatter. Returns null when [jsNumberToString] chose exponential
 * notation (|v| >= 1e21 or < 1e-6), which no money label should ever hit; the
 * caller degrades visibly rather than silently mis-spelling a magnitude.
 */
private fun fixedDecimal(value: Double, scale: Int): String? {
    val canonical = jsNumberToString(value)
    if (canonical.indexOf('e') >= 0) return null
    val dot = canonical.indexOf('.')
    val intPart = if (dot < 0) canonical else canonical.substring(0, dot)
    val fracPart = if (dot < 0) "" else canonical.substring(dot + 1)

    if (fracPart.length <= scale) {
        return if (scale == 0) intPart else intPart + "." + fracPart.padEnd(scale, '0')
    }
    var combined = intPart + fracPart.substring(0, scale)
    if (fracPart[scale] >= '5') combined = incDecimal(combined)
    val cut = combined.length - scale
    val head = combined.substring(0, cut).ifEmpty { "0" }
    return if (scale == 0) head else head + "." + combined.substring(cut)
}

/** The locale's group separator, every three digits from the right. */
private fun group(intDigits: String, locale: BtWebLocale): String {
    val sb = StringBuilder(intDigits.length + intDigits.length / 3)
    var count = 0
    for (i in intDigits.indices.reversed()) {
        sb.append(intDigits[i])
        count++
        if (count % 3 == 0 && i > 0) sb.append(locale.groupSeparator)
    }
    return sb.reverse().toString()
}

/** True when every digit of a plain decimal string is `0` — i.e. it rounded to zero. */
private fun isAllZero(plain: String): Boolean = plain.all { it == '0' || it == '.' }

private fun localeFixed(value: Double, scale: Int, showSign: Boolean, locale: BtWebLocale): String? {
    val collapsed = if (value == 0.0) 0.0 else value // -0.0 never renders a minus
    val fixed = fixedDecimal(abs(collapsed), scale) ?: return null
    val dot = fixed.indexOf('.')
    val body = if (dot < 0) {
        group(fixed, locale)
    } else {
        group(fixed.substring(0, dot), locale) + locale.decimalSeparator + fixed.substring(dot + 1)
    }
    // A value that rounds AWAY to zero (-0.001 at scale 2) must not keep a sign,
    // matching BigDecimal.setScale, whose result has signum 0.
    val sign = when {
        isAllZero(fixed) -> ""
        collapsed < 0 -> "-"
        showSign -> "+"
        else -> ""
    }
    return sign + body
}

/** Rule 1 — fiat money, symbol-last, exactly 2 decimals. */
internal fun webMoney(
    value: Double?,
    locale: BtWebLocale,
    symbol: String = "€",
    showSign: Boolean = false,
): String {
    if (value == null || !value.isFinite()) return WEB_EM_DASH
    val body = localeFixed(value, scale = 2, showSign = showSign, locale = locale)
        ?: return jsNumberToString(value) + " " + symbol // out of the plain-decimal range
    return "$body $symbol"
}

/** Rule 2 — percent, 2 decimals; DE spaces before `%`, EN does not. */
internal fun webPercent(value: Double?, locale: BtWebLocale, showSign: Boolean = false): String {
    val pct = if (locale.spaceBeforePercent) " %" else "%"
    if (value == null || !value.isFinite()) return WEB_EM_DASH
    val body = localeFixed(value, scale = 2, showSign = showSign, locale = locale)
        ?: return jsNumberToString(value) + pct
    return body + pct
}

/** Rule 3 — bare quantity, up to 8 decimals, trailing zeros trimmed. */
internal fun webQuantity(value: Double?, locale: BtWebLocale): String {
    if (value == null || !value.isFinite()) return WEB_EM_DASH
    val fixed = fixedDecimal(abs(value), scale = 8) ?: return jsNumberToString(value)
    val dot = fixed.indexOf('.')
    val trimmed = fixed.substring(dot + 1).trimEnd('0')
    val body = group(fixed.substring(0, dot), locale) +
        if (trimmed.isEmpty()) "" else "${locale.decimalSeparator}$trimmed"
    return (if (value < 0 && !isAllZero(fixed)) "-" else "") + body
}
