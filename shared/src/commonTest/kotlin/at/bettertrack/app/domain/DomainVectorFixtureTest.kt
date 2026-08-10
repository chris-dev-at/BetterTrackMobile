package at.bettertrack.app.domain

import at.bettertrack.app.domain.vectors.GeneratedVectorFixtures
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the code-generation seam that replaced `getResourceAsStream`.
 *
 * Kotlin/Native has no classpath resources, so `shared/build.gradle.kts` embeds
 * the vector JSON into Kotlin source. That introduces two ways to be silently
 * wrong, and this test closes both:
 *
 *  1. **The 64 KB split.** A JVM string literal cannot exceed 65535 modified-UTF8
 *     bytes, so each fixture is emitted as a list of sub-16000-character chunks
 *     joined at runtime. The generator recorded the SOURCE file's character count
 *     and FNV-1a 64 hash before splitting; re-deriving both from the REJOINED
 *     string here proves the split, the Kotlin escaping (backslashes, quotes,
 *     `$`, newlines, non-ASCII as `\uXXXX`) and the rejoin are byte-identical to
 *     the JSON on disk — on every target, including Kotlin/Native.
 *  2. **A fixture swap.** MANIFEST.json is embedded alongside the vectors, so the
 *     pinned platform commit and the per-module counts travel WITH the data and
 *     are cross-checked against literals written here by hand. Re-pinning the
 *     vectors now has to be a deliberate, visible edit in three places.
 */
class DomainVectorFixtureTest {

    /** The generator's hash, character for character. Changing one changes both. */
    private fun fnv1a64(s: String): Long {
        var h = -3750763034362895579L // 0xcbf29ce484222325
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h
    }

    @Test
    fun everyEmbeddedFixtureRejoinsToTheExactSourceJson() {
        assertEquals(8, GeneratedVectorFixtures.NAMES.size, "embedded fixture count")
        GeneratedVectorFixtures.NAMES.forEach { name ->
            val text = GeneratedVectorFixtures.text(name)
            assertEquals(
                GeneratedVectorFixtures.chars(name),
                text.length,
                "$name.json: rejoined length differs from the source file",
            )
            assertEquals(
                GeneratedVectorFixtures.hash(name),
                fnv1a64(text),
                "$name.json: rejoined content differs from the source file (FNV-1a 64)",
            )
        }
    }

    @Test
    fun theManifestStillPinsTheSamePlatformCommitAndTheSameVectorCounts() {
        assertEquals(
            "cb530f7e30a2ce3502e708f4b05711d1d0bde685",
            GeneratedVectorFixtures.PINNED_AT,
            "the vectors' pinned platform commit changed",
        )

        val manifest = VECTOR_JSON
            .parseToJsonElement(GeneratedVectorFixtures.text("MANIFEST"))
            .jsonObject
        assertEquals(
            GeneratedVectorFixtures.PINNED_AT,
            manifest.s("pinnedAt"),
            "MANIFEST.pinnedAt disagrees with the build's pin",
        )

        val counts = manifest.o("counts")
        EXPECTED_VECTOR_COUNTS.forEach { (module, expected) ->
            assertEquals(expected, counts.i(module), "MANIFEST.counts.$module")
        }
        assertEquals(
            EXPECTED_VECTOR_COUNTS.values.sum(),
            manifest.i("totalVectors"),
            "MANIFEST.totalVectors",
        )
        assertEquals(622, manifest.i("totalVectors"), "the suite must still hold 622 vectors")
    }

    @Test
    fun everyModuleFixtureHoldsTheVectorCountTheManifestClaims() {
        var total = 0
        EXPECTED_VECTOR_COUNTS.forEach { (module, expected) ->
            val loaded = loadVectorFile(module).size
            assertEquals(expected, loaded, "$module: vectors decoded from the embedded fixture")
            total += loaded
        }
        assertEquals(622, total, "vectors decoded across all six modules")
    }

    private companion object {
        /**
         * Written out by hand on purpose: a fixture regeneration that changes a
         * count has to be acknowledged here, not absorbed silently.
         */
        val EXPECTED_VECTOR_COUNTS = linkedMapOf(
            "holdings" to 104,
            "seriesStats" to 41,
            "settingsScope" to 9,
            "cashLedger" to 191,
            "tax" to 273,
            "serverTwrParity" to 4,
        )
    }
}
