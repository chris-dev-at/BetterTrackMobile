package at.bettertrack.app.vault.pv.custody

import at.bettertrack.app.ui.vault.custody.pvCustodyActionLabel
import at.bettertrack.app.ui.vault.custody.pvCustodyStateSubline
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §12's binding invariant, mechanically: **a state without a next action is a
 * design bug.**
 *
 * The recorded v2 anti-pattern was a locked vault with no unlock path, which is
 * the kind of hole nobody writes on purpose — it appears when a fourth state
 * arrives and one `when` somewhere grows an `else`. So this file asserts three
 * things a code review cannot keep asserting forever: the state hierarchy is
 * exactly the three §12 names, every one of them maps to an affordance, and
 * every affordance has a label a surface can actually render.
 */
class PvCustodyStateTest {

    private val vaultId = "018f0000-0000-7000-8000-00000000c0de"

    /** Every state the endpoint can be in, including the session-dependent split. */
    private val everyState = listOf(
        PvCustodyState.Wrapped(vaultId, sessionUnlocked = false),
        PvCustodyState.Wrapped(vaultId, sessionUnlocked = true),
        PvCustodyState.Plain(vaultId),
        PvCustodyState.Absent(vaultId),
    )

    @Test
    fun `the hierarchy is exactly the three states the spec names`() {
        // Two guards, on purpose. The `when`s below carry no `else`, so a fourth
        // member of either hierarchy stops this file compiling — the strongest
        // form the check can take, and the reason it is not written with
        // reflection (which would also drag kotlin-reflect into the test
        // runtime for nothing).
        everyState.forEach { state ->
            val named = when (state) {
                is PvCustodyState.Wrapped -> "Wrapped"
                is PvCustodyState.Plain -> "Plain"
                is PvCustodyState.Absent -> "Absent"
            }
            assertTrue("unnamed state $state", named.isNotEmpty())
        }
        listOf(PvCustodyAction.Unlock, PvCustodyAction.Open, PvCustodyAction.Acquire).forEach { action ->
            val named = when (action) {
                PvCustodyAction.Unlock -> "Unlock"
                PvCustodyAction.Open -> "Open"
                PvCustodyAction.Acquire -> "Acquire"
            }
            assertTrue("unnamed action $action", named.isNotEmpty())
        }

        // And the counts, from the source, so a fourth member is noticed even
        // if someone reaches for an `else` to make the compiler quiet again.
        val models = sourceFile("vault/pv/custody/PvCustodyModels.kt").readText()
        assertEquals(
            "PvCustodyState gained or lost a member",
            3,
            Regex("""data (class|object) (Wrapped|Plain|Absent)\b""").findAll(models).count(),
        )
        assertEquals(
            "PvCustodyAction gained or lost a member",
            3,
            Regex("""data object (Unlock|Open|Acquire)\b""").findAll(models).count(),
        )
    }

    @Test
    fun `every state offers the affordance the spec assigns it`() {
        // §12, verbatim: stored+wrapped → "Unlock" (password); stored+plain →
        // opens silently; not-on-this-endpoint → "Enter words / Scan QR".
        assertEquals(
            PvCustodyAction.Unlock,
            PvCustodyState.Wrapped(vaultId, sessionUnlocked = false).nextAction(),
        )
        assertEquals(
            "once the session is open, a wrapped vault stops asking",
            PvCustodyAction.Open,
            PvCustodyState.Wrapped(vaultId, sessionUnlocked = true).nextAction(),
        )
        assertEquals(PvCustodyAction.Open, PvCustodyState.Plain(vaultId).nextAction())
        assertEquals(PvCustodyAction.Acquire, PvCustodyState.Absent(vaultId).nextAction())
    }

    @Test
    fun `no state is a dead end and every affordance has a label`() {
        everyState.forEach { state ->
            val action = state.nextAction()
            assertNotEquals("no label for the affordance of $state", 0, pvCustodyActionLabel(action))
            assertNotEquals("no subline for $state", 0, pvCustodyStateSubline(state))
        }
        // Every affordance is reachable from some state — a value nothing can
        // produce is a branch nobody will maintain.
        assertEquals(
            setOf(PvCustodyAction.Unlock, PvCustodyAction.Open, PvCustodyAction.Acquire),
            everyState.map { it.nextAction() }.toSet(),
        )
    }

    @Test
    fun `the three states read differently to the user`() {
        // The subline is the *reason* for the affordance, so two states that
        // share one would be indistinguishable on screen — which is how "why
        // does this vault ask and that one not?" becomes unanswerable.
        val sublines = everyState.map { pvCustodyStateSubline(it) }.toSet()
        assertEquals("wrapped locked and wrapped unlocked share a subline by design", 3, sublines.size)
    }

    @Test
    fun `the affordance mapping carries no escape hatch`() {
        // The rule this whole file exists for, checked at the source level too:
        // an `else` in either `when` would satisfy every assertion above while
        // quietly re-opening the hole.
        val sources = listOf(
            sourceFile("vault/pv/custody/PvCustodyModels.kt"),
            sourceFile("ui/vault/custody/PvLockVaultsSurface.kt"),
        )
        sources.forEach { file ->
            val offenders = codeLines(file).filter { (_, line) ->
                Regex("""\belse\s*->""").containsMatchIn(line)
            }
            assertTrue(
                "${file.name} maps a custody state through an `else ->`, which is how a " +
                    "fourth state reaches a screen with no action:\n" +
                    offenders.joinToString("\n") { (ln, line) -> "  :$ln  ${line.trim()}" },
                offenders.isEmpty(),
            )
        }
    }

    // ── The flag ────────────────────────────────────────────────────────────

    @Test
    fun `the program flag is still off`() {
        // The tripwire, in the shape `BtThemeDisciplineTest` uses for light
        // mode: flipping the paranoid arc on must be a deliberate edit that
        // fails a test until someone updates it, never a side effect.
        assertFalse(
            "paranoid vaults went live without the arc being whole — see paranoid-design.md §20",
            ParanoidVaultsFlags.enabled,
        )
    }

    @Test
    fun `every custody surface is gated on the flag`() {
        // Nothing in `ui/vault/custody` may be renderable while the arc is off.
        // The gate is one line per public composable, and this is what notices
        // when a new surface arrives without it.
        val root = uiRoot().resolve("vault/custody")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no custody UI found at ${root.absolutePath}", files.isNotEmpty())

        // `@Composable` immediately followed by a bare `fun` is the public form;
        // `internal fun` / `private fun` bodies are the previewable innards and
        // are unreachable on their own.
        val publicComposable = Regex("""@Composable\s+fun\s+(\w+)\(""")
        val gate = Regex("""if\s*\(!ParanoidVaultsFlags\.enabled\)\s*return""")
        files.forEach { file ->
            val text = file.readText()
            val names = publicComposable.findAll(text).map { it.groupValues[1] }.toList()
            assertEquals(
                "${file.name}: public composable(s) $names but ${gate.findAll(text).count()} flag gate(s)",
                names.size,
                gate.findAll(text).count(),
            )
        }
    }

    // ── The copy the code promises ──────────────────────────────────────────

    @Test
    fun `the too-short copy says the number the code enforces`() {
        // The sentence spells the minimum as a word rather than formatting it
        // from the constant (a `%d` beside a plural noun is its own bug class),
        // so the two are pinned here instead.
        assertEquals(8, PV_DEVICE_PASSWORD_MIN_LENGTH)
        assertTrue(
            "the English copy no longer says eight",
            stringValue("", "bt_pv_custody_choice_too_short").contains("eight"),
        )
        assertTrue(
            "the German copy no longer says acht",
            stringValue("-de", "bt_pv_custody_choice_too_short").contains("acht"),
        )
    }

    @Test
    fun `the reset copy states that nothing is lost`() {
        // §12 requires the keystore reset to say, in one sentence, that it loses
        // no data and how the phrase comes back. A reset prompt that only warns
        // would make users choose the unrecoverable path (a new vault) over the
        // free one.
        val en = stringValue("", "bt_pv_custody_reset_body")
        assertTrue("the EN reset copy must say no vault data is lost", en.contains("no vault data is lost"))
        assertTrue("the EN reset copy must name the way back in", en.contains("QR"))
        val de = stringValue("-de", "bt_pv_custody_reset_body")
        assertTrue("the DE reset copy must say no vault data is lost", de.contains("keine Tresordaten"))
        assertTrue("the DE reset copy must name the way back in", de.contains("QR"))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun moduleRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("app sources not found from ${File(".").absolutePath}")

    private fun uiRoot(): File = moduleRoot().resolve("java/at/bettertrack/app/ui")

    private fun sourceFile(relative: String): File =
        moduleRoot().resolve("java/at/bettertrack/app/$relative").also {
            assertTrue("missing source ${it.absolutePath}", it.isFile)
        }

    private fun stringValue(qualifier: String, name: String): String {
        val file = moduleRoot().resolve("res/values$qualifier/strings.xml")
        val match = Regex("""<string\s+name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())
        return match?.groupValues?.get(1) ?: error("$name missing from values$qualifier/strings.xml")
    }

    /** Strip `//` line comments and block-comment bodies so prose never trips a rule. */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlock = false
        file.readLines().forEachIndexed { index, raw ->
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) return@forEachIndexed
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
            val slash = line.indexOf("//")
            if (slash >= 0) line = line.substring(0, slash)
            if (line.isNotBlank()) out += (index + 1) to line
        }
        return out
    }
}
