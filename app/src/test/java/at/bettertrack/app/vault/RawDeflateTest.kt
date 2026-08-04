package at.bettertrack.app.vault

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.Inflater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [RawDeflate] reproduces fflate's raw-DEFLATE bytes exactly.
 *
 * ## Why byte identity, not just "it decompresses"
 *
 * DEFLATE is a family of valid encodings of the same bytes, so "our output
 * inflates back to the input" proves almost nothing about interoperability with
 * the published `BTVAULT1` fixtures. The vault's envelope bytes are
 * `AES-256-GCM(rawDeflate(json))`, and the platform publishes the exact expected
 * envelope; if the compressor differs by one bit the envelope differs entirely.
 * `java.util.zip.Deflater` was measured against these same vectors at every
 * level 0..9 crossed with every strategy and matches **none** of them, which is
 * precisely why [RawDeflate] exists.
 *
 * ## Where the expectations come from
 *
 * `vault-vectors/deflate.json` is derived, never hand-typed: the compressed
 * halves are the real fflate outputs carried inside the platform's published
 * fixtures (`vectors.fixture.json` and `clientMoney.fixture.json`), extracted by
 * AES-GCM-decrypting each envelope with its fixture vault key and the header
 * bytes as AAD; the plaintext halves are those bytes raw-inflated. The same
 * identity is re-proved end to end, straight from the fixtures, by
 * [VaultConformanceTest] — this test exists to localise a failure to the
 * compressor instead of to "some byte in the envelope changed".
 *
 * ## The second half of the suite
 *
 * A compressor that is subtly wrong would silently corrupt real users' vaults,
 * and the four fixture cases are small. So every round trip is also verified
 * against an **independent** inflater ([java.util.zip.Inflater], not our own
 * code) across empty, tiny, huge, incompressible, highly-repetitive and
 * block-boundary-crossing inputs.
 */
class RawDeflateTest {

    private val cases: List<JsonObject> by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/deflate.json")
            ?: error("vault-vectors/deflate.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() })
            .jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    }

    private fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)

    // -----------------------------------------------------------------------
    // The oracle
    // -----------------------------------------------------------------------

    @Test
    fun reproducesEveryPublishedFflateOutputByteForByte() {
        assertEquals("expected all four published deflate vectors", 4, cases.size)
        val seen = mutableListOf<String>()
        for (case in cases) {
            val name = case["case"]!!.jsonPrimitive.content
            val plaintext = b64(case["plaintextBase64"]!!.jsonPrimitive.content)
            val expected = b64(case["deflatedBase64"]!!.jsonPrimitive.content)
            assertEquals(
                "$name: fixture plaintext length",
                case["plaintextLength"]!!.jsonPrimitive.int,
                plaintext.size,
            )
            assertEquals(
                "$name: fixture deflated length",
                case["deflatedLength"]!!.jsonPrimitive.int,
                expected.size,
            )

            val actual = RawDeflate.deflate(plaintext)

            // Report the FIRST diverging byte: "arrays differ" is useless when
            // debugging a bit-level encoder.
            if (!expected.contentEquals(actual)) {
                val limit = minOf(expected.size, actual.size)
                val offset = (0 until limit).firstOrNull { expected[it] != actual[it] } ?: limit
                throw AssertionError(
                    "$name: deflate output diverges at byte $offset " +
                        "(expected ${expected.size} bytes, got ${actual.size}); " +
                        "expected 0x%02x, got %s".format(
                            if (offset < expected.size) expected[offset].toInt() and 0xFF else 0,
                            if (offset < actual.size) "0x%02x".format(actual[offset].toInt() and 0xFF) else "<end>",
                        )
                )
            }
            seen += name
        }
        assertEquals(
            "every published case must be exercised",
            listOf("initial", "passphraseChanged", "rotated", "clientMoney"),
            seen,
        )
    }

    @Test
    fun publishedOutputsAreReadableByAnIndependentInflater() {
        for (case in cases) {
            val name = case["case"]!!.jsonPrimitive.content
            val plaintext = b64(case["plaintextBase64"]!!.jsonPrimitive.content)
            assertArrayEquals(
                "$name: java.util.zip must read our output",
                plaintext,
                jdkInflate(RawDeflate.deflate(plaintext)),
            )
        }
    }

    /**
     * The measurement that justifies this whole file: if some future JDK made
     * `Deflater` agree with fflate, [RawDeflate] could be retired. It does not,
     * and this test says so out loud instead of leaving it as folklore.
     */
    @Test
    fun javaUtilZipCannotReproduceFflateAtAnyLevelOrStrategy() {
        val plaintext = b64(cases.first { it["case"]!!.jsonPrimitive.content == "clientMoney" }
            .let { it["plaintextBase64"]!!.jsonPrimitive.content })
        val expected = b64(cases.first { it["case"]!!.jsonPrimitive.content == "clientMoney" }
            .let { it["deflatedBase64"]!!.jsonPrimitive.content })
        for (level in 0..9) {
            for (strategy in intArrayOf(
                java.util.zip.Deflater.DEFAULT_STRATEGY,
                java.util.zip.Deflater.FILTERED,
                java.util.zip.Deflater.HUFFMAN_ONLY,
            )) {
                val deflater = java.util.zip.Deflater(level, true)
                deflater.setStrategy(strategy)
                deflater.setInput(plaintext)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
                deflater.end()
                assertTrue(
                    "java.util.zip level=$level strategy=$strategy unexpectedly matched fflate — " +
                        "RawDeflate may now be redundant",
                    !expected.contentEquals(out.toByteArray()),
                )
            }
        }
        // ...and ours does match, on the same input.
        assertArrayEquals(expected, RawDeflate.deflate(plaintext))
    }

    // -----------------------------------------------------------------------
    // Safety net: a subtly wrong compressor corrupts real vaults
    // -----------------------------------------------------------------------

    @Test
    fun roundTripsThroughAnIndependentInflater() {
        for ((label, input) in roundTripInputs()) {
            val deflated = RawDeflate.deflate(input)
            assertArrayEquals("$label: independent inflater round trip", input, jdkInflate(deflated))
            assertArrayEquals("$label: own inflater round trip", input, RawDeflate.inflate(deflated))
        }
    }

    @Test
    fun isDeterministic() {
        for ((label, input) in roundTripInputs()) {
            assertArrayEquals(
                "$label: the same input must always produce the same bytes",
                RawDeflate.deflate(input),
                RawDeflate.deflate(input),
            )
        }
    }

    private fun roundTripInputs(): List<Pair<String, ByteArray>> {
        val random = Random(20260804L) // fixed seed: deterministic, reproducible failures
        val incompressible = ByteArray(200_000).also { random.nextBytes(it) }
        val vaultish = buildString {
            append("""{"schemaVersion":1,"entities":{"transaction":[""")
            repeat(400) { index ->
                if (index > 0) append(',')
                append(
                    """{"id":"018f0000-0000-7000-8000-%012d","rev":%d,"editedAt":"2026-07-24T10:00:00.000Z",""".format(index, index % 7) +
                        """"editedBy":"018f0000-0000-7000-8000-00000000000b","deletedAt":null,""" +
                        """"data":{"side":"buy","quantity":"%d","price":"%d.50","note":"Übung ✓ %d"}}""".format(index, index, index)
                )
            }
            append("""]},"mergeLog":[]}""")
        }.toByteArray(Charsets.UTF_8)

        return listOf(
            "empty" to ByteArray(0),
            "one byte" to byteArrayOf(0x42),
            "two bytes" to byteArrayOf(0x00, 0xFF.toByte()),
            "all zeros 100k" to ByteArray(100_000),
            "all 0xFF 100k" to ByteArray(100_000) { 0xFF.toByte() },
            "incompressible 200k" to incompressible,
            "repetitive text" to "the quick brown fox ".repeat(5_000).toByteArray(),
            // Well past fflate's internal block boundaries and the 32 KiB window.
            "block boundary 300k" to ByteArray(300_000) { (it % 251).toByte() },
            "every byte value" to ByteArray(256) { it.toByte() },
            "utf-8 vault document" to vaultish,
        )
    }

    /** An inflater that shares no code with [RawDeflate]. */
    private fun jdkInflate(deflated: ByteArray): ByteArray {
        if (deflated.isEmpty()) return ByteArray(0)
        val inflater = Inflater(true)
        inflater.setInput(deflated)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val produced = inflater.inflate(buffer)
            if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, produced)
        }
        inflater.end()
        return out.toByteArray()
    }
}
