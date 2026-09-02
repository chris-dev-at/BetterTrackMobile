package at.bettertrack.app.vault.pv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **[VaultQrRejection] against the frozen cross-client outcome vocabulary.**
 *
 * The two clients froze one shared scan-outcome vocabulary on **2026-08-26** —
 * twelve outcomes, [FROZEN_OUTCOMES] below, transcribed from the freeze and not
 * derived from this app's enum. The freeze took the granular side of every open
 * question, on the standing argument that *granular → generic is always
 * derivable, generic → granular never is*: this app's `MISSING_REQUIRED_KEY`
 * fold therefore split into [VaultQrRejection.MISSING_MNEMONIC] and
 * [VaultQrRejection.MISSING_VAULT_ID].
 *
 * This file exists because a vocabulary that lives only in prose drifts. Both
 * clients answer the same question with the same set of answers only for as long
 * as somebody checks, so the correspondence is pinned as a property in both
 * directions rather than as a list of examples:
 *
 *  - **total** — every enum value maps to exactly one frozen outcome, so a value
 *    added here without a place in the vocabulary fails the build;
 *  - **injective** — no two values share an outcome, so a lazy "just reuse
 *    `malformed`" cannot quietly re-fold what the freeze split;
 *  - **onto the non-`ok` outcomes** — every frozen outcome except `ok` is claimed
 *    by exactly one value, so an outcome this client cannot express is a red test
 *    and not a silent gap.
 *
 * `ok` has no counterpart by construction: it is [VaultQrParseResult.Ok], the
 * other arm of the result type, and inventing a `VaultQrRejection.OK` to make the
 * arithmetic tidy would create a rejection that means acceptance.
 *
 * The Kotlin identifiers are deliberately NOT the vocabulary's spellings. They
 * are internal names; the vocabulary is the semantic contract. Renaming eleven
 * values across every call site would change nothing an outside client can
 * observe, so the mapping is what is made exact — here, and in each value's own
 * KDoc, which the last test keeps honest.
 */
class VaultQrRejectionVocabularyTest {

    internal companion object {

        /**
         * The frozen vocabulary, verbatim.
         *
         * **Frozen 2026-08-26.** Twelve outcomes, one shared list for the web
         * client and this app. Order as frozen. Changing this constant is a
         * cross-client wire decision, never a local edit to make a test pass.
         */
        val FROZEN_OUTCOMES: List<String> = listOf(
            "ok",
            "not-a-bettertrack-code",
            "update-required",
            "legacy-code",
            "malformed",
            "missing-mnemonic",
            "missing-vault-id",
            "duplicate-key",
            "invalid-mnemonic",
            "invalid-vault-id",
            "invalid-fingerprint",
            "name-too-long",
        )

        /** The `ok` outcome is the accepted arm, not a rejection — see the class KDoc. */
        const val ACCEPTED_OUTCOME = "ok"

        /**
         * This app's side of the correspondence, written out one value at a time.
         *
         * `internal` so [VaultQrE7ConformanceTest] can replay the platform's
         * vectors against the SAME table instead of writing a second one: two
         * copies of a mapping are two things that can disagree, and the point
         * of this file is that there is one.
         *
         * Written as an explicit table rather than derived from the enum's names:
         * a derivation (lowercase, underscores to hyphens) would map
         * `NOT_A_VAULT_CODE` to `not-a-vault-code` and `PHRASE_INVALID` to
         * `phrase-invalid`, neither of which is in the vocabulary — and a
         * derivation that happened to work would also silently absorb a rename,
         * which is exactly the drift this file is here to catch.
         */
        val MAPPING: Map<VaultQrRejection, String> = mapOf(
            VaultQrRejection.NOT_A_VAULT_CODE to "not-a-bettertrack-code",
            VaultQrRejection.UNSUPPORTED_VERSION to "update-required",
            VaultQrRejection.LEGACY_CODE to "legacy-code",
            VaultQrRejection.MALFORMED to "malformed",
            VaultQrRejection.DUPLICATE_KEY to "duplicate-key",
            VaultQrRejection.MISSING_MNEMONIC to "missing-mnemonic",
            VaultQrRejection.MISSING_VAULT_ID to "missing-vault-id",
            VaultQrRejection.PHRASE_INVALID to "invalid-mnemonic",
            VaultQrRejection.VAULT_ID_INVALID to "invalid-vault-id",
            VaultQrRejection.NAME_TOO_LONG to "name-too-long",
            VaultQrRejection.FINGERPRINT_INVALID to "invalid-fingerprint",
        )
    }

    // ── the frozen list itself ──────────────────────────────────────────────

    @Test
    fun `the frozen vocabulary is twelve distinct outcomes`() {
        assertEquals(
            "the vocabulary froze at twelve outcomes on 2026-08-26; a different " +
                "count here means the constant was edited, which is a cross-client " +
                "decision and not a local one",
            12,
            FROZEN_OUTCOMES.size,
        )
        assertEquals(
            "a repeated outcome in the frozen list would make the 1:1 claim below " +
                "unfalsifiable",
            FROZEN_OUTCOMES.size,
            FROZEN_OUTCOMES.toSet().size,
        )
        assertTrue(
            "the accepted arm must be in the vocabulary, or the non-ok arithmetic is wrong",
            ACCEPTED_OUTCOME in FROZEN_OUTCOMES,
        )
    }

    // ── the correspondence, both directions ─────────────────────────────────

    @Test
    fun `every rejection maps to exactly one frozen outcome`() {
        val unmapped = VaultQrRejection.entries.filterNot { it in MAPPING }
        assertEquals(
            "these rejection values have no frozen counterpart. Adding a reason is " +
                "a cross-client vocabulary change: get the outcome frozen, then map " +
                "it here and name it in the value's KDoc.",
            emptyList<VaultQrRejection>(),
            unmapped,
        )
        val foreign = MAPPING.values.filterNot { it in FROZEN_OUTCOMES }
        assertEquals(
            "these are not outcomes in the frozen vocabulary — an invented outcome " +
                "is a client speaking a dialect",
            emptyList<String>(),
            foreign,
        )
    }

    @Test
    fun `no two rejections claim the same outcome`() {
        val collisions = MAPPING.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { it.size > 1 }
        assertEquals(
            "two rejection values mapping to one outcome re-folds what the freeze " +
                "split, and the fold is not recoverable from the wire",
            emptyMap<String, List<VaultQrRejection>>(),
            collisions,
        )
    }

    @Test
    fun `every frozen outcome except ok is claimed by exactly one rejection`() {
        val expected = FROZEN_OUTCOMES.filterNot { it == ACCEPTED_OUTCOME }.toSet()
        assertEquals(
            "an outcome the vocabulary defines but this client cannot express is a " +
                "hole in the cross-check, not a detail: it means a scan this app " +
                "refuses cannot be reported in the shared vocabulary at all",
            expected,
            MAPPING.values.toSet(),
        )
        assertEquals(
            "eleven non-ok outcomes, eleven rejection values",
            expected.size,
            VaultQrRejection.entries.size,
        )
    }

    @Test
    fun `ok is the accepted arm and never a rejection`() {
        assertTrue(
            "a VaultQrRejection meaning 'ok' would be a rejection that means " +
                "acceptance; the accepted arm is VaultQrParseResult.Ok",
            ACCEPTED_OUTCOME !in MAPPING.values,
        )
        assertTrue(
            "no rejection value may be spelled OK either",
            VaultQrRejection.entries.none { it.name.equals(ACCEPTED_OUTCOME, ignoreCase = true) },
        )
    }

    // ── the KDoc says the same thing as the table ───────────────────────────

    @Test
    fun `every rejection's KDoc names its frozen counterpart`() {
        // Same shape as the app's other discipline tests: read the source, assert
        // the property, name the offender. Without this, the mapping lives twice —
        // here and in the KDoc a reader of the enum actually sees — and the two
        // are free to disagree.
        val offenders = VaultQrRejection.entries.mapNotNull { value ->
            val outcome = MAPPING[value] ?: return@mapNotNull "${value.name}: not mapped"
            val doc = kdocFor(value.name)
            if (doc.contains("`$outcome`")) null else "${value.name} must name `$outcome`"
        }
        assertTrue(
            "the enum's KDoc and this file's table disagree:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun repoFile(relative: String): File {
        // Unit tests run with the module dir as CWD; tolerate the repo root.
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("not found: ${candidates.map { it.absolutePath }}")
    }

    /** The lines of the `enum class VaultQrRejection { … }` declaration, KDoc included. */
    private fun enumLines(): List<String> {
        val lines = repoFile("src/main/java/at/bettertrack/app/vault/pv/VaultQrPayload.kt").readLines()
        val start = lines.indexOfFirst { it.startsWith("enum class VaultQrRejection") }
        check(start >= 0) { "VaultQrRejection's declaration moved — this guard must move with it" }
        val end = lines.withIndex().first { (i, line) -> i > start && line == "}" }.index
        return lines.subList(start, end)
    }

    /**
     * The KDoc block immediately above an enum constant.
     *
     * Walks back from the constant's own line over the comment lines and stops at
     * the first line that is neither — a blank line separates the values, so a
     * value with no KDoc yields an empty string and fails the test rather than
     * borrowing its neighbour's.
     */
    private fun kdocFor(name: String): String {
        val lines = enumLines()
        val at = lines.indexOfFirst { it.trim() == "$name," || it.trim() == name }
        check(at >= 0) { "no declaration line found for $name" }
        val doc = mutableListOf<String>()
        var i = at - 1
        while (i >= 0) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("*") || trimmed.startsWith("/**")) {
                doc += trimmed
                i--
            } else {
                break
            }
        }
        return doc.reversed().joinToString("\n")
    }
}
