package at.bettertrack.app.domain

/**
 * The NON-JVM actual of [formatScientific] — compiled verbatim for BOTH
 * Kotlin/Native (iOS) and Kotlin/Wasm (browser), which is why it sits in
 * `nonAndroidMain` rather than `iosMain` (KMP web port, Phase W0). A second copy
 * of this arithmetic is the one thing that could make the two runtimes disagree
 * on bytes the vault depends on, so there is exactly one.
 *
 * It reproduces `String.format(Locale.ROOT, "%.<fractionDigits>e", value)`
 * WITHOUT `java.text` (neither runtime has one), using an exact base-10
 * big-integer expansion of the `Double`. No `BigInteger`/`BigDecimal` exist on
 * either runtime, so the few big operations needed (multiply by 2 / by 5,
 * decimal stringify, +1) are hand-rolled on a little-endian digit list.
 *
 * Java's `%e` does two things, reproduced here in the same order:
 *   1. take the SHORTEST round-trip decimal of the double (its `Double.toString`
 *      digits, chosen round-half-to-even), then
 *   2. round/zero-pad THOSE digits to `fractionDigits + 1` significant digits
 *      with round-HALF_UP, normalize the mantissa and emit a `>= 2`-digit signed
 *      exponent.
 *
 * This makes the migrated [jsNumberToString] byte-identical to the JVM across the
 * whole precision range it ever asks for (it stops at the first round-tripping
 * precision — i.e. the shortest length — which is exactly where the two agree).
 * See PlatformFormat.kt for why byte-identity is load-bearing (vault / GCM AAD).
 */

// base-10 little-endian magnitude, each element 0..9.
private fun fromLong(value: Long): MutableList<Int> {
    var m = value
    val d = ArrayList<Int>()
    if (m == 0L) { d.add(0); return d }
    while (m > 0L) { d.add((m % 10L).toInt()); m /= 10L }
    return d
}

private fun mulSmall(d: MutableList<Int>, factor: Int) {
    var carry = 0
    for (i in d.indices) {
        val t = d[i] * factor + carry
        d[i] = t % 10
        carry = t / 10
    }
    while (carry > 0) { d.add(carry % 10); carry /= 10 }
}

// big-endian decimal string of the little-endian magnitude.
private fun toStr(d: MutableList<Int>): String {
    val sb = StringBuilder(d.size)
    for (i in d.indices.reversed()) sb.append(('0' + d[i]))
    return sb.toString()
}

// add 1 to a fixed big-endian decimal string; grows length by 1 on all-nines.
private fun incrementDecimal(s: String): String {
    val c = s.toCharArray()
    var i = c.size - 1
    while (i >= 0) {
        if (c[i] == '9') { c[i] = '0'; i-- } else { c[i] = c[i] + 1; return c.concatToString() }
    }
    return "1" + c.concatToString()
}

private fun anyNonZeroFrom(s: String, start: Int): Boolean {
    for (i in start until s.length) if (s[i] != '0') return true
    return false
}

/**
 * Round big-endian digit string [sig] to [keep] significant digits.
 * [halfEven] = true → round-half-to-even (stage-1 shortest-digit search, matching
 * `Double.toString`); false → HALF_UP (stage-2 trim, matching Java `%e`).
 * Returns the [keep]-digit result and an exponent adjustment (1 if a carry grew it).
 */
private fun roundTo(sig: String, keep: Int, halfEven: Boolean): Pair<String, Int> {
    if (sig.length <= keep) return sig to 0
    var head = sig.substring(0, keep)
    val dropFirst = sig[keep]
    val up = when {
        dropFirst > '5' -> true
        dropFirst < '5' -> false
        anyNonZeroFrom(sig, keep + 1) -> true            // strictly greater than half
        halfEven -> (head[keep - 1] - '0') % 2 == 1       // exact half → to even
        else -> true                                      // exact half, HALF_UP → up
    }
    var adj = 0
    if (up) {
        head = incrementDecimal(head)
        if (head.length == keep + 1) { adj = 1; head = head.substring(0, keep) }
    }
    return head to adj
}

private fun buildSci(digits: String, e10: Int): String {
    val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits[0].toString()
    val absExp = kotlin.math.abs(e10).toString().let { if (it.length < 2) "0$it" else it }
    return mantissa + "e" + (if (e10 >= 0) "+" else "-") + absExp
}

internal actual fun formatScientific(value: Double, fractionDigits: Int): String {
    if (value == 0.0) {
        val m = if (fractionDigits == 0) "0" else "0." + "0".repeat(fractionDigits)
        val negZero = value.toRawBits() != 0L
        return (if (negZero) "-" else "") + m + "e+00"
    }
    val neg = value < 0
    val av = kotlin.math.abs(value)
    val bits = av.toRawBits()
    val biasedExp = ((bits ushr 52) and 0x7FFL).toInt()
    val fracBits = bits and 0xFFFFFFFFFFFFFL
    val mant: Long
    val e2: Int
    if (biasedExp == 0) { mant = fracBits; e2 = -1074 } // subnormal (av > 0 ⇒ mant > 0)
    else { mant = fracBits or (1L shl 52); e2 = biasedExp - 1075 }

    // Exact coefficient C and point position: av = C · 10^(-pointFromRight).
    val big = fromLong(mant)
    val pointFromRight: Int
    if (e2 >= 0) { repeat(e2) { mulSmall(big, 2) }; pointFromRight = 0 }
    else { val s = -e2; repeat(s) { mulSmall(big, 5) }; pointFromRight = s }
    val all = toStr(big)

    var fnz = 0
    while (fnz < all.length && all[fnz] == '0') fnz++
    val baseE10 = (all.length - 1 - fnz) - pointFromRight
    val exactSig = all.substring(fnz)

    // Stage 1: shortest round-trip decimal (round-half-even), S = 1..17.
    var shortSig = exactSig
    var shortE10 = baseE10
    for (s in 1..17) {
        val (cand, candAdj) = roundTo(exactSig, s, halfEven = true)
        val candE10 = baseE10 + candAdj
        if (buildSci(cand, candE10).toDouble() == av) { shortSig = cand; shortE10 = candE10; break }
    }

    // Stage 2: render shortest digits to want = fractionDigits + 1 (HALF_UP), pad if longer.
    val want = fractionDigits + 1
    val rounded: String
    var e10 = shortE10
    if (want >= shortSig.length) {
        rounded = shortSig + "0".repeat(want - shortSig.length)
    } else {
        val (r, adj) = roundTo(shortSig, want, halfEven = false)
        rounded = r; e10 += adj
    }
    return (if (neg) "-" else "") + buildSci(rounded, e10)
}
