package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every widget configuration screen must OPEN ON THE INSTANCE'S OWN SETTINGS,
 * and must COMMIT ONLY ON SAVE.
 *
 * ## The defect this exists to prevent from coming back
 *
 * Owner, 2026-08-18: *"mache überall speicher buttons darunter und lade die
 * einstellungen vom jeweiligen widget. weil wenn ich zb einstellung x als
 * standard habe und jetzt einstellung y einstelle und dann das menu neu öffne
 * und dann wieder x ausgewählt ist stört das."*
 *
 * Every editor in `BtWidgetConfigActivities.kt` used to begin
 * `var thing by remember { mutableStateOf(SOME_DEFAULT) }` and never read
 * `getAppWidgetState` at all. Reconfiguring a placed widget therefore showed a
 * fresh set of defaults rather than that widget's real settings — and because
 * four of the editors also committed on ROW TAP, the first tap wrote those
 * wrong defaults back over the user's actual configuration.
 *
 * Both halves are structural, so both are guarded structurally. A codec
 * round-trip test (`BtWidgetConfigTest`) cannot catch either one: the codecs
 * were always correct, they were simply never called on the way IN.
 */
class BtWidgetConfigHydrationTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("Cannot locate $relative from ${File(".").absolutePath}")
    }

    private fun source(): String =
        projectFile("src/main/java/at/bettertrack/app/widget/BtWidgetConfigActivities.kt").readText()

    /**
     * Each config Activity's `Content()`, as raw text — split on the class
     * declarations so a per-editor assertion can name the offender.
     */
    private fun editors(): Map<String, String> {
        val text = source()
        val heads = Regex("""(?m)^(?:abstract )?class (Bt\w*ConfigActivity)\b""")
            .findAll(text)
            .map { it.groupValues[1] to it.range.first }
            .toList()
        require(heads.isNotEmpty()) { "no config activities found — did the file move?" }
        return heads.mapIndexed { i, (name, start) ->
            val end = heads.getOrNull(i + 1)?.second ?: text.length
            name to text.substring(start, end)
        }.toMap()
    }

    /** The base class is plumbing, not an editor — it has no `Content()` of its own. */
    private fun concreteEditors(): Map<String, String> =
        editors().filterKeys { it != "BtWidgetConfigActivity" }

    @Test
    fun `every config activity is discovered, so this test cannot pass by finding nothing`() {
        val names = concreteEditors().keys
        // The row family's two presets inherit one `Content()` from
        // BtRowsConfigActivity, so they are editors without a body of their own.
        assertTrue("expected the full set of editors, found $names", names.size >= 9)
        listOf(
            "BtAssetWidgetConfigActivity",
            "BtPortfolioWidgetConfigActivity",
            "BtBudgetWidgetConfigActivity",
            "BtCashWalletWidgetConfigActivity",
            "BtNetWorthWidgetConfigActivity",
            "BtAllocationWidgetConfigActivity",
            "BtSpendingWidgetConfigActivity",
            "BtQuickLinksWidgetConfigActivity",
            "BtRowsConfigActivity",
        ).forEach { assertTrue("$it is missing from the config file", it in names) }
    }

    @Test
    fun `every editor hydrates this instance's stored settings before it paints`() {
        concreteEditors().forEach { (name, body) ->
            // The two row presets are bodiless subclasses of BtRowsConfigActivity,
            // which carries the hydration for both.
            if (!body.contains("override fun Content()")) return@forEach
            assertTrue(
                "$name renders an editor without reading the instance's state — " +
                    "it will open on defaults and overwrite the user's real settings on save",
                body.contains("InstanceConfig("),
            )
        }
    }

    @Test
    fun `no editor seeds a config value from a hardcoded default`() {
        // `remember { mutableStateOf(...) }` is still legitimate for the CHOICE
        // LISTS an editor loads (assets, portfolios, budgets, wallets) — those
        // are not configuration. It is never legitimate for a setting.
        val banned = Regex("""var (\w+) by remember \{ mutableStateOf\(Bt\w+\.""")
        val offenders = banned.findAll(source()).map { it.groupValues[1] }.toList()
        assertEquals(
            "a widget setting is being seeded from a hardcoded default instead of the " +
                "instance's stored state: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no editor commits on row tap`() {
        // The four pick-one editors used to call confirm{} straight out of a
        // row's onClick. A mis-tap then wrote and closed, with nothing to undo.
        val text = source()
        assertFalse(
            "a row's onClick still commits directly — selection must only update the draft",
            Regex("""onClick = \{\s*confirm\b""").containsMatchIn(text),
        )
        assertFalse(
            "the commit-on-tap list scaffold is back",
            Regex("""fun <T> ConfigList\(""").containsMatchIn(text),
        )
    }

    @Test
    fun `every editor ends in a Save affordance`() {
        val scaffolds = listOf("ConfigPickPanel(", "ConfigPanel(", "ConfigPanelScroll(", "SaveButton(")
        concreteEditors().forEach { (name, body) ->
            if (!body.contains("override fun Content()")) return@forEach
            assertTrue(
                "$name has no Save affordance — the owner asked for a save button on every " +
                    "widget configuration surface",
                scaffolds.any { body.contains(it) },
            )
        }
    }

    @Test
    fun `every Save-bearing scaffold is actually wired to a commit`() {
        // A Save button with no `confirm` behind it would be worse than none.
        concreteEditors().forEach { (name, body) ->
            if (!body.contains("override fun Content()")) return@forEach
            assertTrue("$name never calls confirm", body.contains("confirm {"))
        }
    }

    @Test
    fun `the post-save repaint outlives the config screen`() {
        // `lifecycleScope` is cancelled by finish(), so a repaint launched there
        // dies halfway and the card keeps its old content — the same symptom the
        // owner reported, in a different costume.
        val text = source()
        val confirmBody = text.substringAfter("protected fun confirm(").substringBefore("\n    protected abstract")
        assertTrue(
            "confirm() must hand the repaint to the process scope",
            confirmBody.contains("AppGraph.appScope.launch"),
        )
        assertTrue(
            "confirm() must still AWAIT the durable state write before returning",
            confirmBody.contains("updateAppWidgetState("),
        )
        assertTrue(
            "the warm pass must be queued after the cache repaint, never before it",
            confirmBody.indexOf("redraw(app, glanceId)") < confirmBody.indexOf("refreshNow()"),
        )
    }
}
