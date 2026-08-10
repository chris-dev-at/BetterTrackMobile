package at.bettertrack.app.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Shared types and JS-runtime shims for the literal Kotlin port of the platform's
 * audited `packages/domain` engine (`docs/S3S4_STORAGE_PLAN.md` §3).
 *
 * **This package is pure, multiplatform Kotlin.** No `android.*`, no Compose,
 * no Room, no `java.*` — only the Kotlin stdlib, `kotlinx.coroutines` (for
 * `suspend`) and `kotlinx-datetime` (the multiplatform replacement for the
 * `java.time` this file used before it moved into `:shared`). That is what makes
 * it JVM-unit-testable *and* compilable for Kotlin/Native, and it is what lets
 * the generated conformance vectors be replayed with exact `Double` equality.
 *
 * The one genuinely platform-specific primitive — fixed-precision scientific
 * formatting inside [jsNumberToString] — is isolated behind
 * [formatScientific] (`expect`/`actual`); see that declaration.
 *
 * ## Visibility note (KMP/iOS port, Phase 2)
 *
 * Three JS-runtime shims — [jsNumberToString], [jsNum] and [jsDateParse] — are
 * `public` rather than `internal`. They were `internal` while this file lived in
 * `:app`, where module-internal visibility still reached the `:app` consumers
 * that use them: `vault/VaultJson.kt` (which delegates the vault JSON number
 * rendering to [jsNumberToString]) and the JVM test harness that pins them. Once
 * the file moved into `:shared`, `internal` would hide them across the module
 * boundary and break both — so the visibility is widened. It changes no behavior
 * and no output byte. The two shims used only *within* this package
 * ([jsDateOnlyToMs], [jsIsoDay]) stay `internal`.
 *
 * ## Why the port is *literal*
 *
 * Plan §3.3 is binding: translate line-for-line, preserve arithmetic operation
 * order (IEEE-754 addition is not associative), `Double` everywhere, copy the
 * epsilon constants rather than re-deriving them, keep insertion-ordered maps
 * (`LinkedHashMap`) wherever a traversal feeds a floating-point accumulation,
 * and mirror the TS error classes. The conformance vectors in
 * `app/src/test/resources/domain-vectors/` were produced by running the real
 * TypeScript, so any restructuring shows up immediately as a failing test.
 */

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * Base class for every failure the ported domain raises — the Kotlin counterpart
 * of a bare `throw new Error(...)` in the TypeScript source (plan §3.3 rule 6).
 *
 * The message text is reproduced **character for character**, including the way
 * JavaScript renders interpolated numbers (see [jsNumberToString]), because
 * these messages are part of the audited contract: they carry the offending
 * quantities on the money path.
 */
open class DomainException(message: String) : RuntimeException(message)

// ---------------------------------------------------------------------------
// Injected dependencies
// ---------------------------------------------------------------------------

/**
 * Currency conversion into the base currency (EUR in v1, a parameter throughout).
 * The domain stays decoupled and pure: rates arrive through this interface, never
 * from a clock, a network call or a database.
 *
 * Port of `CurrencyConverter` in `holdings.ts`. The TypeScript signature is
 * `toBase(amount, currency, opts?: { date?, base? })`; the optional bag is
 * flattened into two default-null parameters, where `null` means exactly what
 * `undefined` meant — *use the current spot rate* for [date], and *use the
 * default base currency* for [base].
 *
 * `async` becomes `suspend` (plan §3.3 rule 6).
 */
interface CurrencyConverter {
    suspend fun toBase(
        amount: Double,
        currency: String,
        date: String? = null,
        base: String? = null,
    ): Double
}

// ---------------------------------------------------------------------------
// JS runtime shims
// ---------------------------------------------------------------------------
//
// These are NOT domain logic. They reproduce the handful of JavaScript runtime
// behaviours the TypeScript source leans on, so that the ported arithmetic and
// the ported *messages* are indistinguishable from the original.

/**
 * ECMAScript `Number::toString` (spec 7.1.12.1) for a `Double`.
 *
 * Needed because the domain interpolates numbers straight into error messages
 * (`` `…got ${value}` ``) and those messages are asserted verbatim. Kotlin's own
 * `Double.toString()` disagrees with JavaScript in three ways that all show up in
 * real vectors: it appends `.0` to integers (`4.0` vs `4`), it switches to
 * exponential notation at different thresholds, and it spells the exponent
 * differently (`1.0E-8` vs `1e-8`).
 *
 * Implemented as: find the shortest decimal digit string that round-trips to the
 * same `Double` (this JDK is 17, whose `Double.toString` is *not* the
 * shortest-round-trip algorithm, so the search is done here), then lay those
 * digits out using the spec's own case analysis.
 */
fun jsNumberToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value == Double.POSITIVE_INFINITY) return "Infinity"
    if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
    if (value == 0.0) return "0" // covers -0.0, which JS also renders as "0"
    if (value < 0) return "-" + jsNumberToString(-value)

    // Shortest round-tripping representation, as `d.dddde±xx`. [formatScientific]
    // is the sole platform-specific step (`expect`/`actual`): on the JVM it is
    // `String.format(Locale.ROOT, "%.<n>e", value)`; on Kotlin/Native it is an
    // exact-decimal reimplementation that produces the identical bytes.
    var scientific = ""
    for (significantDigits in 1..17) {
        scientific = formatScientific(value, significantDigits - 1)
        if (scientific.toDouble() == value) break
    }

    val ePos = scientific.indexOf('e')
    val mantissa = scientific.substring(0, ePos)
    // `n` is the spec's exponent: value == 0.s * 10^n, with `s` the digit string.
    val n = scientific.substring(ePos + 1).toInt() + 1
    val digits = mantissa.replace(".", "").trimEnd('0').ifEmpty { "0" }
    val k = digits.length

    return when {
        // Integer that needs no exponent: digits then (n - k) zeros.
        k <= n && n <= 21 -> digits + "0".repeat(n - k)
        // Decimal point sits inside the digit string.
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        // Small magnitude: "0." then leading zeros then the digits.
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        // Exponential form.
        k == 1 -> digits + "e" + (if (n - 1 >= 0) "+" else "-") + kotlin.math.abs(n - 1)
        else ->
            digits.substring(0, 1) + "." + digits.substring(1) +
                "e" + (if (n - 1 >= 0) "+" else "-") + kotlin.math.abs(n - 1)
    }
}

/** Shorthand for [jsNumberToString] at the message-interpolation sites. */
fun jsNum(value: Double): String = jsNumberToString(value)

/**
 * `Date.parse("<date>T00:00:00Z")` — UTC midnight epoch-ms of an ISO
 * `YYYY-MM-DD` date, or `NaN` when the date does not exist.
 *
 * Returning `NaN` rather than throwing is load-bearing: `computeSeriesStats`
 * feeds unvalidated dates in here and relies on `NaN` propagating through
 * `years` so that `years > 0` is false and the CAGR comes back `null`. Likewise
 * `valueOverTime` only regex-checks its dates, so an impossible-but-well-shaped
 * date like `2026-13-45` must yield an empty series, not an exception.
 *
 * **Widened catch (KMP/iOS port).** The `java.time` version caught
 * `DateTimeParseException`. `kotlinx-datetime`'s `LocalDate.parse` instead
 * signals bad input with `IllegalArgumentException` (its `DateTimeFormatException`
 * is a subclass), a *different* type — so the catch is widened to
 * `IllegalArgumentException`. This is load-bearing on Kotlin/Native, which has no
 * checked exceptions: had the narrow type been kept, malformed server data would
 * turn the **designed** `NaN` fallback into a crash instead. Both the impossible
 * date (`2026-13-45`) and a structurally wrong string funnel to `NaN` exactly as
 * before, which the JVM vector suite still pins.
 */
internal fun jsDateOnlyToMs(date: String): Double =
    try {
        LocalDate.parse(date).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds().toDouble()
    } catch (_: IllegalArgumentException) {
        Double.NaN
    }

/**
 * `Date.parse(<iso-8601 timestamp>)` — epoch-ms of a full ISO-8601 instant, or
 * `NaN` when it cannot be parsed.
 *
 * Accepts an explicit offset (`…Z`, `…+02:00`) and, like JavaScript, a bare
 * date. A timestamp with **no** offset is deliberately `NaN` here: JavaScript
 * would interpret it in the host's local zone, which is not deterministic and
 * which `holdings.ts` explicitly tells callers not to rely on ("callers should
 * pass timestamps in a single, consistent zone"). Failing loud beats guessing on
 * the money path.
 *
 * **KMP/iOS port.** `java.time.OffsetDateTime` has no `kotlinx-datetime`
 * equivalent, so an offset instant is parsed with
 * `DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET` — which, like
 * `OffsetDateTime.parse`, *requires* an explicit offset and rejects a bare date
 * (that then falls through to [jsDateOnlyToMs], preserving the original
 * two-stage behavior). The catch is widened to `IllegalArgumentException` for the
 * same reason as [jsDateOnlyToMs]: `kotlinx-datetime` throws that (not
 * `DateTimeParseException`) on unparseable input, and the fallback must not
 * become a crash on Native.
 */
fun jsDateParse(value: String): Double =
    try {
        DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(value)
            .toInstantUsingOffset().toEpochMilliseconds().toDouble()
    } catch (_: IllegalArgumentException) {
        jsDateOnlyToMs(value)
    }

/** `new Date(ms).toISOString().slice(0, 10)` — the UTC calendar day of an instant. */
internal fun jsIsoDay(ms: Double): String =
    Instant.fromEpochMilliseconds(ms.toLong()).toLocalDateTime(TimeZone.UTC).date.toString()
