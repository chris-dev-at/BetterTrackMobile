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
 *
 * V5 adds one more rule, enforced in this file so it cannot be forgotten:
 *  6. **Discreet mode** — when [BtDiscreetMode] is masking, every ABSOLUTE money
 *     amount renders as "•••• €". Percentages, weights and quantities are
 *     RELATIVE and stay live: they leak nothing about the size of a portfolio,
 *     and blanking them would make the app useless rather than discreet.
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

// ── Formatter reuse (perf pass 2026-08-06) ──────────────────────────────────

/** The three formatter configurations this file needs. */
private enum class NfShape {
    /** Money and percent: exactly 2 decimals, grouped. */
    FIXED_2,

    /** Compact abbreviated magnitudes: exactly 1 decimal, grouped. */
    FIXED_1,

    /** Sub-cent unit prices: up to 20 decimals, trailing zeros already trimmed. */
    SIGNIFICANT,

    /** Bare quantities: up to 8 decimals, trailing zeros trimmed. */
    QUANTITY,
}

/**
 * Per-thread, per-locale configured [NumberFormat]s.
 *
 * `NumberFormat.getNumberInstance` is not a getter — it builds a fresh ICU
 * `DecimalFormat`, parsing a pattern and resolving a full symbol set, every
 * call. One holdings row asks for four of them (quantity, weight, market value,
 * change percent), so a 30-position portfolio was constructing ~120 formatters
 * per pass over the list, and doing it again on every emission of the holdings
 * flow and every discreet-mode toggle. The configurations are fixed and there
 * are only three of them, so they are built once per locale and reused.
 *
 * `ThreadLocal` because `NumberFormat` is explicitly NOT thread-safe and these
 * are reached from both the UI thread and (since the same pass moved decoding
 * off Main) background dispatchers. A per-thread copy is a few objects and
 * removes the question entirely; a shared instance behind a lock would trade the
 * allocation for contention on the hotter path.
 */
private val btNumberFormats: ThreadLocal<MutableMap<Pair<Locale, NfShape>, NumberFormat>> =
    ThreadLocal.withInitial { HashMap() }

private fun btNumberFormat(locale: Locale, shape: NfShape): NumberFormat =
    btNumberFormats.get()!!.getOrPut(locale to shape) {
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = true
            when (shape) {
                NfShape.FIXED_2 -> {
                    minimumFractionDigits = 2
                    maximumFractionDigits = 2
                }

                NfShape.FIXED_1 -> {
                    minimumFractionDigits = 1
                    maximumFractionDigits = 1
                }

                NfShape.SIGNIFICANT -> {
                    minimumFractionDigits = 0
                    maximumFractionDigits = 20
                    roundingMode = RoundingMode.HALF_UP
                }

                NfShape.QUANTITY -> {
                    minimumFractionDigits = 0
                    maximumFractionDigits = 8
                    roundingMode = RoundingMode.HALF_UP
                }
            }
        }
    }

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
    // Discreet mode is enforced HERE, at the one function every money label in
    // the app funnels through, so no screen can opt out by accident.
    if (BtDiscreetMode.masking) return btMaskedMoney(currencyCode, locale)
    val bd = BigDecimal.valueOf(withoutNegativeZero(value!!)).setScale(2, RoundingMode.HALF_UP)
    val num = btNumberFormat(locale, NfShape.FIXED_2).format(bd)
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
    if (BtDiscreetMode.masking) return btMaskedMoney(currencyCode, locale)
    val v = value!!
    val magnitude = kotlin.math.abs(v)
    if (magnitude > 0.0 && magnitude < 0.01) {
        val bd = BigDecimal.valueOf(v)
            .round(MathContext(6, RoundingMode.HALF_UP))
            .stripTrailingZeros()
        val nf = btNumberFormat(locale, NfShape.SIGNIFICANT)
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
    val num = btNumberFormat(locale, NfShape.FIXED_2).format(display)
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
    return btNumberFormat(locale, NfShape.QUANTITY).format(bd)
}

/**
 * Rule 3b — holding-ROW quantity: a glanceable magnitude, not a ledger figure
 * (owner UI batch 2026-08-17, which REPLACES the 2026-08-16 "three-digit budget"
 * reading: *"max 2 comma so for stuff like 5.6666667 dont do 5.6 but 5.66 … and
 * for numbers like BTC if you have less then 1 total so you have 0.42331 BTC it
 * should take 3 comma values instead of 2"*).
 *
 * The rule, in one sentence: **two decimals for a quantity of one or more, three
 * for a fraction below one, truncated (never rounded up), trailing zeros
 * dropped — except that a fraction too small to survive that keeps its first two
 * significant digits instead of collapsing to zero.**
 *
 *  · `5.6666667`   → `5.66`   (≥ 1 → two decimals, not one)
 *  · `11.66666667` → `11.66`
 *  · `0.42331`     → `0.423`  (< 1 → three decimals)
 *  · `0.0424512`   → `0.042`
 *  · `4.0`         → `4`      (trailing zeros dropped)
 *  · `123.456`     → `123.45` (the integer part no longer eats the budget)
 *  · `0.00042`     → `0.00042` (dust keeps two significant digits, capped at
 *    rule 3's 8-decimal ceiling)
 *
 * TRUNCATED rather than rounded because a rounded-up quantity claims the user
 * owns more than they do — `0.0426` shown as `0.043` is a small lie in the
 * direction small lies are worst, and every example the owner gave is a
 * truncation. The full figure stays one tap away on the holding's detail screen
 * (rule 3).
 *
 * Like rule 3, deliberately NOT masked in discreet mode: a bare quantity is not
 * money and reveals nothing without the price beside it, which IS masked.
 */
internal fun btFormatHoldingQuantityCore(value: Double?, locale: Locale): String {
    if (!isFinite(value)) return BT_EM_DASH
    val bd = BigDecimal.valueOf(withoutNegativeZero(value!!))
    val abs = bd.abs()
    val scale = if (abs < BigDecimal.ONE) HOLDING_QTY_SUB_ONE_DECIMALS else HOLDING_QTY_DECIMALS
    var cut = bd.setScale(scale, RoundingMode.DOWN)
    if (cut.signum() == 0 && bd.signum() != 0) {
        // Sub-0.001 dust: all three decimals are zero. Extend to the first two
        // significant digits so a real position never renders as "0".
        val stripped = abs.stripTrailingZeros()
        val leadingZeros = stripped.scale() - stripped.precision()
        val dustScale = (leadingZeros + HOLDING_QTY_DUST_SIGNIFICANT).coerceAtMost(8)
        cut = bd.setScale(dustScale, RoundingMode.DOWN)
    }
    return btNumberFormat(locale, NfShape.QUANTITY).format(cut.stripTrailingZeros())
}

/** Rule 3b's decimals for |q| ≥ 1 — see [btFormatHoldingQuantityCore]. */
private const val HOLDING_QTY_DECIMALS = 2

/** Rule 3b's decimals for a fraction below one (the BTC case). */
private const val HOLDING_QTY_SUB_ONE_DECIMALS = 3

/** How many significant digits a sub-0.001 quantity keeps. */
private const val HOLDING_QTY_DUST_SIGNIFICANT = 2

// ── Rule 7: compact magnitudes (fundamentals, board #76 arc f) ──────────────
//
// Corporate statement figures are 6 to 12 digits long. Rule 1 renders Apple's
// FY2025 revenue as "416.161.000.000,00 $", which is not a number anyone reads —
// it is a ruler. The fundamentals surface therefore abbreviates: "416,2 Mrd. $".
//
// This is a SEPARATE rule rather than a flag on rule 1 because the two must never
// be confused at a call site: portfolio money is exact and masked, corporate
// money is approximate and public.

/** Abbreviation ladder, largest first. Each entry is (threshold, DE, EN). */
private val BT_COMPACT_TIERS: List<Triple<Double, String, String>> = listOf(
    Triple(1e12, "Bio.", "T"),
    Triple(1e9, "Mrd.", "B"),
    Triple(1e6, "Mio.", "M"),
    Triple(1e3, "Tsd.", "K"),
)

/**
 * The abbreviated magnitude of [value] — the number scaled into its tier, at one
 * decimal, plus that tier's unit word — or `null` when |value| < 1000 and the
 * caller should fall back to an exact rendering.
 *
 * Handles the rounding-promotion case that makes naive versions of this function
 * wrong: `999_950_000` sits in the "Mio." tier, but rounds at one decimal to
 * `1000,0`, which must be promoted to `1,0 Mrd.` rather than printed as
 * "1.000,0 Mio.".
 */
private fun btCompactParts(value: Double, locale: Locale): Pair<String, String>? {
    val german = locale.language == "de"
    val magnitude = kotlin.math.abs(value)
    var index = BT_COMPACT_TIERS.indexOfFirst { magnitude >= it.first }
    if (index < 0) return null

    var scaled = BigDecimal.valueOf(value)
        .divide(BigDecimal.valueOf(BT_COMPACT_TIERS[index].first))
        .setScale(1, RoundingMode.HALF_UP)
    // Rounding may have pushed the value into the next tier up.
    if (scaled.abs() >= BigDecimal.valueOf(1000) && index > 0) {
        index -= 1
        scaled = BigDecimal.valueOf(value)
            .divide(BigDecimal.valueOf(BT_COMPACT_TIERS[index].first))
            .setScale(1, RoundingMode.HALF_UP)
    }

    val tier = BT_COMPACT_TIERS[index]
    val number = btNumberFormat(locale, NfShape.FIXED_1).format(scaled)
    return number to (if (german) tier.second else tier.third)
}

/**
 * Rule 7 — a **corporate** money figure, abbreviated: "416,2 Mrd. $" (DE) /
 * "416.2B $" (EN). Values under 1000 fall through to rule 1 so a small figure
 * stays exact rather than becoming "0,4 Tsd.".
 *
 * Symbol-last like every other money label in the app, so a fundamentals card
 * reads consistently with the price above it. German gets the spelled-out
 * Tsd./Mio./Mrd./Bio. ladder with a space before the unit; every other locale
 * gets the tight K/M/B/T ladder.
 *
 * **Deliberately NOT masked by discreet mode.** Rule 6 blanks absolute money
 * because it reveals the size of the user's portfolio. A company's revenue is
 * public filing data that says nothing whatsoever about who is looking at it —
 * the identical reasoning that leaves earnings EPS unmasked
 * ([at.bettertrack.app.ui.market.IntelEarningsBlock]). Masking it would hide a
 * public fact and make discreet mode look broken, not discreet.
 */
internal fun btFormatCompactMoneyCore(
    value: Double?,
    currencyCode: String,
    locale: Locale,
): String {
    if (!isFinite(value)) return BT_EM_DASH
    val v = withoutNegativeZero(value!!)
    val symbol = btMoneySymbol(currencyCode, locale)
    val parts = btCompactParts(v, locale)
        ?: return "${btNumberFormat(locale, NfShape.FIXED_2).format(
            BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP),
        )} $symbol"
    val (number, unit) = parts
    val glue = if (locale.language == "de") " " else ""
    return "$number$glue$unit $symbol"
}

/**
 * Rule 7 without a currency — the bare abbreviated magnitude ("416,2 Mrd."), for
 * chart axis labels where the unit is already stated once in the card's header
 * and repeating the symbol on every gridline is noise.
 */
internal fun btFormatCompactNumberCore(value: Double?, locale: Locale): String {
    if (!isFinite(value)) return BT_EM_DASH
    val v = withoutNegativeZero(value!!)
    val parts = btCompactParts(v, locale)
        ?: return btNumberFormat(locale, NfShape.FIXED_2).format(
            BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP),
        )
    val (number, unit) = parts
    val glue = if (locale.language == "de") " " else ""
    return "$number$glue$unit"
}
