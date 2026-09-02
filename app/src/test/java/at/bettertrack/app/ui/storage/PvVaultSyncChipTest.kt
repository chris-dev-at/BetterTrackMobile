package at.bettertrack.app.ui.storage

import at.bettertrack.app.R
import at.bettertrack.app.vault.pv.PvVaultSummary
import at.bettertrack.app.vault.pv.sync.PvMedium
import at.bettertrack.app.vault.pv.sync.PvSavedLocallyReason
import at.bettertrack.app.vault.pv.sync.PvSyncFailure
import at.bettertrack.app.vault.pv.sync.PvSyncFailureReason
import at.bettertrack.app.vault.pv.sync.PvVaultSyncState
import at.bettertrack.app.vault.pv.sync.PvVaultSyncStatus
import at.bettertrack.app.vault.pv.store.PvDocKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The §14 chip: its projection, its copy, and its silence.**
 *
 * The rendering itself needs a device and is unverified here (this project's unit
 * suite is deliberately device-free). What IS provable on a JVM is everything a
 * screenshot would not catch anyway: that the aggregate is the engine's own
 * severity fold rather than a second opinion, that every state and every reason
 * has a sentence in both languages, that an untrusted vault name is sanitized
 * before it reaches a row, and that with the flag off this file emits nothing.
 */
class PvVaultSyncChipTest {

    private fun vault(id: String, name: String = "Household", candidates: Int = 0) =
        PvVaultSummary(id, name, listOf(PvMedium.SERVER), candidates)

    private fun state(
        id: String,
        status: PvVaultSyncStatus,
        perMedium: Map<PvMedium, PvVaultSyncStatus> = mapOf(PvMedium.SERVER to status),
        lastSyncedAtMs: Long? = null,
        pending: Set<String> = emptySet(),
    ) = PvVaultSyncState(id, status, perMedium, lastSyncedAtMs, pending)

    private fun failure(reason: PvSyncFailureReason) = PvVaultSyncStatus.Error(
        PvSyncFailure(PvMedium.SERVER, "doc", PvDocKind.PORTFOLIO, reason),
    )

    // ── projection ──────────────────────────────────────────────────────────

    @Test
    fun `a vault the engine has never run a pass for still gets a row`() {
        val rows = pvVaultSyncRows(listOf(vault("a")), emptyMap())
        assertEquals(1, rows.size)
        assertEquals(PvVaultSyncStatus.Idle, rows.single().status)
        assertNull("no pass, no last-sync fact — and no invented one", rows.single().lastSyncedAtMs)
    }

    @Test
    fun `the aggregate is the engine's own severity fold, not a second opinion`() {
        val rows = pvVaultSyncRows(
            listOf(vault("a"), vault("b"), vault("c")),
            mapOf(
                "a" to state("a", PvVaultSyncStatus.Idle),
                "b" to state("b", PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.LOCKED)),
                "c" to state("c", PvVaultSyncStatus.Pushing),
            ),
        )
        // attention > syncing > locked > synced (§14). No error here, so syncing.
        assertEquals(PvVaultSyncStatus.Pushing, pvAggregateSyncStatus(rows))
        assertEquals(
            "and it must agree with the fold the engine publishes per medium",
            PvVaultSyncState.fold(rows.map { it.status }),
            pvAggregateSyncStatus(rows),
        )
    }

    @Test
    fun `one vault needing a human outranks every other state`() {
        val rows = pvVaultSyncRows(
            listOf(vault("a"), vault("b", name = "Kitchen table")),
            mapOf(
                "a" to state("a", PvVaultSyncStatus.Pushing),
                "b" to state("b", failure(PvSyncFailureReason.TOO_LARGE)),
            ),
        )
        assertTrue(pvAggregateSyncStatus(rows) is PvVaultSyncStatus.Error)
        // The name arrives sanitized — the aggregate line is a render site like
        // any other, so it gets the isolate the sanitizer wraps every untrusted
        // label in. What must survive is the letters.
        assertTrue(pvAttentionVaultName(rows).orEmpty().contains("Kitchen table"))
    }

    @Test
    fun `the locked count is what the aggregate line prints`() {
        val rows = pvVaultSyncRows(
            listOf(vault("a"), vault("b"), vault("c")),
            mapOf(
                "a" to state("a", PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.LOCKED)),
                "b" to state("b", PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.LOCKED)),
                "c" to state("c", PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.OFFLINE)),
            ),
        )
        assertEquals(2, pvLockedVaultCount(rows))
        assertEquals(
            "locked outranks offline, so the chip says locked",
            PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.LOCKED),
            pvAggregateSyncStatus(rows),
        )
    }

    @Test
    fun `a vault name is sanitized before it can reach a row`() {
        // §21 ruling 4: the name is server-visible CONFIG, which makes it
        // renderable while locked — and still untrusted text. A planted
        // right-to-left override must not survive into the sheet.
        // Written as an escape, never pasted: an invisible reordering character
        // in this file would be exactly the unreviewable thing it tests for.
        val hostile = "safe" + RTL_OVERRIDE + "tluav"
        val rows = pvVaultSyncRows(listOf(vault("a", name = hostile)), emptyMap())
        assertFalse(
            "an unstripped bidi control reorders the label the user is deciding on",
            rows.single().name.any { it == RTL_OVERRIDE },
        )
    }

    @Test
    fun `the row carries the per-medium detail and the pending count the sheet renders`() {
        val rows = pvVaultSyncRows(
            listOf(vault("a")),
            mapOf(
                "a" to state(
                    "a",
                    PvVaultSyncStatus.Pushing,
                    perMedium = mapOf(
                        PvMedium.SERVER to PvVaultSyncStatus.Pushing,
                        PvMedium.DRIVE to PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.OFFLINE),
                    ),
                    lastSyncedAtMs = 1_700_000_000_000L,
                    pending = setOf("d1", "d2"),
                ),
            ),
        )
        val row = rows.single()
        assertEquals(2, row.perMedium.size)
        assertEquals(2, row.pendingDocs)
        assertEquals(1_700_000_000_000L, row.lastSyncedAtMs)
    }

    // ── the retry affordance ────────────────────────────────────────────────

    @Test
    fun `retry is offered only where it could change something`() {
        fun canRetry(status: PvVaultSyncStatus) =
            pvVaultSyncRows(listOf(vault("a")), mapOf("a" to state("a", status))).single().canRetry

        assertTrue(canRetry(PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.OFFLINE)))
        assertTrue(canRetry(PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.RETRY_QUEUED)))
        assertTrue(canRetry(failure(PvSyncFailureReason.REFUSED)))
        assertTrue(canRetry(failure(PvSyncFailureReason.CONFLICT_UNRESOLVED)))

        // Unlocking is the remedy for LOCKED, adding a medium for NO_MEDIUM, and
        // updating the app for UPDATE_REQUIRED. A push fixes none of the three.
        assertFalse(canRetry(PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.LOCKED)))
        assertFalse(canRetry(PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.NO_MEDIUM)))
        assertFalse(canRetry(failure(PvSyncFailureReason.UPDATE_REQUIRED)))
        assertFalse(canRetry(PvVaultSyncStatus.Idle))
        assertFalse(canRetry(PvVaultSyncStatus.Pushing))
    }

    // ── copy ────────────────────────────────────────────────────────────────

    @Test
    fun `every status, reason and failure has its own sentence`() {
        val statuses = listOf(
            PvVaultSyncStatus.Idle,
            PvVaultSyncStatus.Pushing,
            PvVaultSyncStatus.ConflictMerging,
            failure(PvSyncFailureReason.REFUSED),
        ) + PvSavedLocallyReason.entries.map { PvVaultSyncStatus.SavedLocally(it) }
        val ids = statuses.map { it.pvLabelRes() }
        assertEquals("two states sharing a sentence is one state the user cannot tell apart", ids.size, ids.toSet().size)
        assertTrue(ids.none { it == 0 })

        val reasons = PvSyncFailureReason.entries.map { it.pvLabelRes() }
        assertEquals(PvSyncFailureReason.entries.size, reasons.toSet().size)

        val media = PvMedium.entries.map { it.pvLabelRes() }
        assertEquals(PvMedium.entries.size, media.toSet().size)
    }

    @Test
    fun `every doc kind the store can park has a label`() {
        val kinds = PvDocKind.entries.map { pvDocKindLabelRes(it.wire) }
        assertEquals(PvDocKind.entries.size, kinds.toSet().size)
        assertEquals(
            "an unreadable framing still needs a word",
            R.string.bt_pv_doc_kind_unknown,
            pvDocKindLabelRes(null),
        )
        assertEquals(R.string.bt_pv_doc_kind_unknown, pvDocKindLabelRes("something-new"))
    }

    /**
     * Every key this file names exists in BOTH catalogues.
     *
     * `StringParityTest` proves EN and DE agree with each other; it cannot prove
     * that a key a composable asks for exists at all. A `stringResource` for a
     * missing id is a crash on the phone, and this family is behind a flag, so
     * nobody would meet it in a manual pass.
     */
    @Test
    fun `every string the chip names exists in both languages`() {
        val source = chipSource()
        val keys = Regex("""R\.(string|plurals)\.(bt_[a-z0-9_]+)""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSet()
        assertTrue("the chip names no copy at all — did the file move?", keys.size > 20)
        listOf("" to "EN", "-de" to "DE").forEach { (qualifier, label) ->
            val text = resFile(qualifier).readText()
            val missing = keys.filterNot { (kind, name) ->
                Regex("""<$kind\s+name="$name"[\s>]""").containsMatchIn(text)
            }.map { it.second }.sorted()
            assertTrue("$label is missing: $missing", missing.isEmpty())
        }
    }

    // ── the flag-off promise ────────────────────────────────────────────────

    /**
     * With the flag off this file emits nothing, and the storage card is
     * therefore byte-identical to what shipped.
     *
     * Three things have to hold for that, and all three are structural: the entry
     * point's FIRST statement is the guard, there is no second public composable
     * to mount, and nothing that emits (not even a `Spacer`) sits outside the
     * guard in the caller — the last of which
     * `PvSyncDisciplineTest.the storage screen mounts the per-vault chip with one
     * call and no chrome of its own` holds from the other side.
     */
    @Test
    fun `the only public composable in the file is guarded by its first statement`() {
        val source = chipSource()
        val publicComposables = Regex("""@Composable\s*\n(?:@[A-Za-z].*\n)*fun\s+([A-Z]\w*)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "one door, and it is PvVaultSyncSection",
            listOf("PvVaultSyncSection"),
            publicComposables,
        )
        val guarded = Regex(
            """fun PvVaultSyncSection\([^)]*\) \{\s*\n\s*if \(!ParanoidVaultsFlags\.enabled\) return""",
        )
        assertTrue(
            "the guard must be the FIRST statement — anything above it is emitted in a shipped build",
            guarded.containsMatchIn(source),
        )
    }

    @Test
    fun `the sheet is a bottom sheet, never an anchored popover`() {
        // Owner order 2026-08-16: menus and detail panels come from the bottom.
        // The web anchors this content under the chip; the CONTENT carries over,
        // the container does not.
        val source = chipSource()
        assertTrue("the §14 detail must be a ModalBottomSheet", "ModalBottomSheet(" in source)
        listOf("DropdownMenu", "Popup(", "ExposedDropdown").forEach {
            assertFalse("$it is not this app's language for a detail panel", it in source)
        }
    }

    @Test
    fun `a retry outcome is reported inside the sheet, never through the shell snackbar`() {
        // A verdict that appears behind the sheet the user is looking at is a
        // verdict nobody reads (the settled rule for verdict-like outcomes).
        val source = chipSource()
        listOf("Snackbar", "showSnackbar", "BtSnackbar").forEach {
            assertFalse("$it would land behind the sheet", it in source)
        }
        assertTrue("the outcome has to render as a row in the sheet", "outcomeRes" in source)
    }

    private fun chipSource(): String = repoFile(
        "src/main/java/at/bettertrack/app/ui/storage/PvVaultSyncChip.kt",
    ).readText()

    private fun resFile(qualifier: String): File = repoFile("src/main/res/values$qualifier/strings.xml")

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("not found: $relative")

    private companion object {
        /**
         * U+202E RIGHT-TO-LEFT OVERRIDE, as an escape rather than as a pasted
         * character: an invisible reordering control inside this file would be
         * exactly the unreviewable thing the test exists to catch.
         */
        const val RTL_OVERRIDE = '\u202E'
    }
}
