package at.bettertrack.app.domain

/**
 * The single platform-specific primitive of [jsNumberToString] (KMP/iOS port,
 * Phase 2 — decision D1). Formats [value] in fixed-precision scientific notation
 * **byte-for-byte identical** to the JVM's
 * `String.format(Locale.ROOT, "%.<fractionDigits>e", value)`:
 *
 *  - a normalized mantissa: one digit before the point and exactly
 *    [fractionDigits] digits after it (no point at all when [fractionDigits] == 0);
 *  - `e`, then an explicit `+`/`-` sign;
 *  - an exponent padded to **at least two digits** (`e+00`, `e-08`, and three
 *    digits when the magnitude needs them, `e+308`);
 *  - rounding is **HALF_UP** — the mode `java.util.Formatter` uses for `%e`.
 *
 * `jsNumberToString` only ever calls this with a **finite, strictly positive**
 * value (it handles NaN/±Infinity/0/negative before the search loop), but the
 * actuals stay total for safety.
 *
 * Why a seam at all: everything else in `jsNumberToString` is already pure
 * Kotlin. Isolating this one line keeps the ported number→string rendering
 * identical across Android and iOS while letting each platform reach the same
 * bytes its own way — the JVM through `String.format`, Kotlin/Native through an
 * exact decimal expansion (there is no `java.text` on Native). Its output feeds
 * vault plaintext and the GCM AAD header downstream, so it must be bit-exact.
 */
internal expect fun formatScientific(value: Double, fractionDigits: Int): String
