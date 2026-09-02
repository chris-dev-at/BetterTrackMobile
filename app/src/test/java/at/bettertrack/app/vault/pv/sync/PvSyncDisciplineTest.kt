package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.vault.VaultSyncScheduler
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structural rules the per-vault rail rests on, held by a test rather than a
 * review note.
 *
 * Two of them are about the LIVE v1 rail, which serves real users until the
 * platform's §19 deletion train and must not be disturbed by a round that sits
 * beside it. The rest are the dormancy promise: while `ParanoidVaultsFlags
 * .enabled` is `false` this build must be behaviourally identical to a build
 * without any of this code, and "identical" is only a fact if nothing outside
 * the epic can reach it.
 */
class PvSyncDisciplineTest {

    private fun moduleRoot(): File =
        listOf(File("src"), File("app/src")).firstOrNull { it.isDirectory }
            ?: error("app sources not found from ${File(".").absolutePath}")

    private fun mainSources(): List<File> =
        moduleRoot().resolve("main/java/at/bettertrack/app").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun source(relative: String): File =
        moduleRoot().resolve("main/java/at/bettertrack/app/$relative").also {
            assertTrue("missing source ${it.absolutePath}", it.isFile)
        }

    private fun syncSources(): List<File> =
        moduleRoot().resolve("main/java/at/bettertrack/app/vault/pv/sync").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .also { assertTrue("the sync package is missing", it.isNotEmpty()) }

    /**
     * Strip `//` line comments and block-comment bodies: KDoc prose NAMES the v1
     * rail on purpose (that is how the reasoning is recorded), and a rule a
     * doc comment can break is a rule that gets deleted rather than obeyed.
     */
    private fun codeOf(file: File): String {
        val out = StringBuilder()
        var inBlock = false
        file.readLines().forEach { raw ->
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) return@forEach
                line = line.substring(end + 2)
                inBlock = false
            }
            while (true) {
                val start = line.indexOf("/*")
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    line = line.substring(0, start)
                    inBlock = true
                    break
                }
                line = line.substring(0, start) + line.substring(end + 2)
            }
            val slashes = line.indexOf("//")
            if (slashes >= 0) line = line.substring(0, slashes)
            out.append(line).append('\n')
        }
        return out.toString()
    }

    // ── 1. Dormancy ─────────────────────────────────────────────────────────

    /**
     * The names no ordinary file may say.
     *
     * Extended in S2 slice 2 with the parts that round the rail out: the Room
     * store, the key registry and the bootstrap that assembles them.
     */
    private val gated = listOf(
        "PvVaultSyncEngine",
        "PvVaultSyncScheduler",
        "PvVaultSyncWorker",
        "PvVaultSyncRuntime",
        "PvVaultSyncState",
        "PvServerDocMedium",
        "PvDocCursorStore",
        "PvVaultLocalStore",
        "PvVaultKeys",
        "PvVaultKeyRegistry",
        "PvVaultsBootstrap",
        "PvVaultsSession",
        "RoomPvDocTransactions",
    )

    /**
     * The two doors, and there are exactly two.
     *
     * Slice 1 could assert that NOTHING outside `vault/pv/` named the rail,
     * because the rail had no callers at all. Slice 2 gives it the two it needs —
     * a graph that starts it and a chip that renders it — so the rule sharpens
     * rather than loosens: this list is closed, and
     * [every gated caller refuses to act while the flag is off] requires each
     * entry to carry the flag guard IN THE SAME FILE. A third door cannot be cut
     * without editing this test, which is the point of it.
     */
    private val gatedCallers = mapOf(
        "di/AppGraph.kt" to "if (!at.bettertrack.app.vault.pv.ParanoidVaultsFlags.enabled) return",
        "ui/storage/PvVaultSyncChip.kt" to "if (!ParanoidVaultsFlags.enabled) return",
    )

    @Test
    fun `nothing outside the paranoid-vaults package reaches the sync engine`() {
        val allowed = gatedCallers.keys.map { it.substringAfterLast('/') }.toSet()
        val offenders = mainSources()
            .filterNot { it.path.replace(File.separatorChar, '/').contains("/vault/pv/") }
            .filterNot { it.name in allowed }
            .mapNotNull { file ->
                val text = codeOf(file)
                gated.filter { it in text }.takeIf { it.isNotEmpty() }?.let { file.name to it }
            }
        assertEquals(
            "the per-vault sync rail must stay unreachable while ParanoidVaultsFlags.enabled is false",
            emptyList<Pair<String, List<String>>>(),
            offenders,
        )
    }

    @Test
    fun `every gated caller refuses to act while the flag is off`() {
        gatedCallers.forEach { (relative, guard) ->
            val code = codeOf(source(relative))
            assertTrue(
                "$relative reaches the paranoid rail without `$guard`",
                guard in code,
            )
        }
    }

    /**
     * The graph's mention is ONE line and it is inside the guard.
     *
     * A `by lazy` property would make the engine part of a shipped build's
     * surface, and "dormant" would then rest on nobody happening to touch it.
     */
    @Test
    fun `the app graph builds the rail in one guarded place and holds no reference to it`() {
        val graph = codeOf(source("di/AppGraph.kt"))
        assertEquals(
            "PvVaultsBootstrap must be named exactly once in the graph",
            1,
            Regex("PvVaultsBootstrap").findAll(graph).count(),
        )
        val guarded = Regex(
            """if \(!at\.bettertrack\.app\.vault\.pv\.ParanoidVaultsFlags\.enabled\) return\s+""" +
                """at\.bettertrack\.app\.vault\.pv\.PvVaultsBootstrap\.start\(""",
        )
        assertTrue(
            "the bootstrap call must sit immediately behind the flag guard",
            guarded.containsMatchIn(graph),
        )
    }

    /**
     * The chip's mount point adds exactly one call and nothing else.
     *
     * The flag-off promise for the storage screen is byte identity: the spacer,
     * the divider and every emission live INSIDE the gated composable, so with
     * the flag off the card emits the tree it emitted before this file existed.
     */
    @Test
    fun `the storage screen mounts the per-vault chip with one call and no chrome of its own`() {
        val screen = codeOf(source("ui/storage/WhereYourDataLivesScreen.kt"))
        val mentions = Regex("Pv[A-Za-z]+").findAll(screen).map { it.value }.toSet()
        assertEquals(
            "the only paranoid symbol the v1 screen may name is the gated section itself",
            setOf("PvVaultSyncSection"),
            mentions,
        )
        assertEquals(
            "one mount point, no second surface",
            1,
            Regex("""PvVaultSyncSection\(\)""").findAll(screen).count(),
        )
    }

    @Test
    fun `the worker resolves its engine through the package's own holder, never the app graph`() {
        // A reference from `di/` into this package would make the epic reachable
        // — so the direction is inverted and the engine is PUBLISHED here.
        val worker = codeOf(source("vault/pv/sync/PvVaultSyncScheduler.kt"))
        assertTrue("the worker must read PvVaultSyncRuntime", "PvVaultSyncRuntime.engine()" in worker)
        assertTrue("the worker must not reach into the app graph", "AppGraph" !in worker)
    }

    // ── 2. The live v1 rail is untouched ────────────────────────────────────

    @Test
    fun `the sync package never names the v1 coordinator, worker or scheduler`() {
        val banned = listOf(
            "VaultSyncCoordinator",
            "VaultSyncWorker",
            "VaultSyncScheduler",
            "VaultSyncState",
            "VaultSyncStatus",
            "vaultLastPushedKey",
            "VaultMetaKeys",
        )
        syncSources().forEach { file ->
            val code = codeOf(file)
            banned.forEach { needle ->
                // Anchored on a word boundary so `PvVaultSyncWorker` is not read
                // as a use of `VaultSyncWorker`.
                val used = Regex("(?<![A-Za-z0-9_])$needle").containsMatchIn(code)
                assertTrue(
                    "${file.name} names '$needle' — the live v1 rail must not be reachable from " +
                        "the per-vault one, in either direction",
                    !used,
                )
            }
        }
    }

    @Test
    fun `the v1 account push keeps its single chain, and the per-vault rail never joins it`() {
        assertEquals(
            "the shipped v1 unique-work name must not move: an install mid-upgrade would " +
                "otherwise run two chains for one push",
            "bt-vault-push",
            VaultSyncScheduler.WORK_NAME,
        )
        assertNotEquals(
            "a shared chain would rebuild the serialisation this round exists to remove",
            VaultSyncScheduler.WORK_NAME,
            PvVaultSyncScheduler.WORK_NAME_PREFIX,
        )
        val v1 = codeOf(source("vault/VaultSyncWorker.kt")) + codeOf(source("vault/VaultSyncCoordinator.kt"))
        assertTrue("the v1 rail must not have learned about the per-vault one", "pv.sync" !in v1)
        assertTrue("the v1 rail must not have learned about the per-vault one", "PvVault" !in v1)
    }

    // ── 3. One chain per vault ──────────────────────────────────────────────

    @Test
    fun `every vault gets its own unique work name`() {
        val a = PvVaultSyncScheduler.workName(VAULT_ID)
        val b = PvVaultSyncScheduler.workName(OTHER_VAULT_ID)
        assertEquals("bt-pv-vault-push:$VAULT_ID", a)
        assertNotEquals("two vaults must never share a chain", a, b)
        assertTrue("the vault id must be IN the name", VAULT_ID in a)
    }

    // ── 4. The §6 merge is called, never re-implemented ─────────────────────

    @Test
    fun `the sync package calls the platform-canonical merge instead of restating it`() {
        val merge = codeOf(source("vault/pv/sync/PvDocMerge.kt"))
        assertTrue(
            "the entity rules are VaultMerge's; the split adapts onto them",
            "mergeVaultDocuments(" in merge,
        )
        // A second implementation of a binding rule is wrong the first time the
        // first one changes, so the names that would mark one are banned outright.
        listOf("chooseVaultEntity", "documentDominates", "compareInstants", "VAULT_MERGE_LOG_LIMIT")
            .forEach { needle ->
                assertTrue(
                    "PvDocMerge.kt names '$needle' — the §6 entity rules must be CALLED, not restated",
                    needle !in merge,
                )
            }
    }

    // ── 5. Cursors are per medium, structurally ─────────────────────────────

    @Test
    fun `the cursor table is keyed by medium, so one landed write cannot speak for another`() {
        val text = source("data/db/PvVaultEntities.kt").readText()
        assertTrue(
            "vault_doc_cursors must exist",
            "tableName = \"vault_doc_cursors\"" in text,
        )
        assertTrue(
            "the medium must be part of the primary key (§6: independent cursors per medium)",
            "primaryKeys = [\"vaultId\", \"docId\", \"medium\"]" in text,
        )
    }
}
