package at.bettertrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the ECMAScript `Number::toString` shim ([jsNumberToString]).
 *
 * The ported domain interpolates numbers straight into its error messages, and
 * those messages are part of the audited contract that the conformance vectors
 * assert verbatim — "only 4 held", not "only 4.0 held". Kotlin's own
 * `Double.toString()` disagrees with JavaScript on integers, on the thresholds
 * where exponential notation kicks in, and on how the exponent is spelled, so
 * the shim carries real weight and is tested directly rather than only through
 * the messages that depend on it.
 *
 * Every expectation below was produced by running `String(value)` in Node
 * (V8) — the same engine that produced the vectors.
 */
class JsNumberToStringTest {

    private fun check(expected: String, value: Double) =
        assertEquals("jsNumberToString($value)", expected, jsNumberToString(value))

    @Test
    fun `renders integers without a trailing point`() {
        check("0", 0.0)
        check("1", 1.0)
        check("4", 4.0)
        check("-1", -1.0)
        check("10", 10.0)
        check("11", 11.0)
        check("1000000", 1e6)
        // Java would switch to exponential at 1e7; JavaScript does not.
        check("10000000", 1e7)
        check("100000000000000000000", 1e20)
        check("123456789012345680000", 123456789012345680000.0)
    }

    @Test
    fun `renders negative zero as zero, exactly as JavaScript does`() {
        check("0", -0.0)
    }

    @Test
    fun `renders fractions with the shortest round-tripping digits`() {
        check("3.5", 3.5)
        check("4.001", 4.001)
        check("100.5", 100.5)
        check("0.1", 0.1)
        check("-2.5", -2.5)
        check("0.3333333333333333", 1.0 / 3.0)
        // The value that appears in a real oversell message (5 + 1e-8).
        check("5.00000001", 5 + 1e-8)
        // The deflateSeries point that differs by 1 ulp between V8 and the JVM.
        check("866.9821047971052", 866.9821047971052)
    }

    @Test
    fun `switches to exponential at the JavaScript thresholds, not the Java ones`() {
        // Java goes exponential below 1e-3; JavaScript only below 1e-6.
        check("0.000001", 0.000001)
        check("1e-7", 0.0000001)
        check("1.5e-7", 1.5e-7)
        check("1e-8", 1e-8)
        check("5e-324", Double.MIN_VALUE)
        // Java goes exponential at 1e7; JavaScript only at 1e21.
        check("1e+21", 1e21)
        check("1.5e+21", 1.5e21)
        check("1.7976931348623157e+308", Double.MAX_VALUE)
    }

    @Test
    fun `renders the non-finite values by their JavaScript names`() {
        check("NaN", Double.NaN)
        check("Infinity", Double.POSITIVE_INFINITY)
        check("-Infinity", Double.NEGATIVE_INFINITY)
    }

    @Test
    fun `round-trips every rendering back to the identical double`() {
        val values = listOf(
            0.0, 1.0, -1.0, 3.5, 4.001, 100.5, 0.1, 1.0 / 3.0, 5 + 1e-8,
            1e-7, 1e-8, 1e20, 1e21, 1.5e21, 866.9821047971052,
            Double.MIN_VALUE, Double.MAX_VALUE,
        )
        values.forEach {
            assertEquals("round-trip of $it", it, jsNumberToString(it).toDouble(), 0.0)
        }
    }
}
