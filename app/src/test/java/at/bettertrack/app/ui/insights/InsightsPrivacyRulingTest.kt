package at.bettertrack.app.ui.insights

import at.bettertrack.app.ui.charts.viz.VizDatum
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two privacy rulings this feature ships with, and the reason they differ.
 *
 * ## Ruling 1 — a SHARED IMAGE hides amounts by default, every time
 *
 * `Beträge ausblenden` starts on whenever image sharing starts, and an "off"
 * choice is never remembered as the next default. The destination is unknown at
 * render time and publication is hard to undo, so the reversible state is the
 * only safe default — and remembering "off" would turn one considered decision
 * into a standing one for an audience the user has not thought about yet.
 *
 * ## Ruling 2 — the PDF report carries REAL amounts
 *
 * Consistent with the shipped ledger-export ruling (`CashExport.kt`, owner
 * 2026-08-17): a file the user explicitly asked for, for their own records, is
 * not the place to hide their own numbers.
 *
 * The rulings differ because the DESTINATIONS differ, not because the data does.
 * Both halves are asserted here, structurally, because both are the kind of rule
 * that a well-meaning refactor quietly deletes.
 */
class InsightsPrivacyRulingTest {

    private fun sourceRoot(): File {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app"),
            File("app/src/main/java/at/bettertrack/app"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("sources not found; tried ${roots.map { it.absolutePath }}")
    }

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("$relative not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    // ── Ruling 1: the transform ─────────────────────────────────────────────

    private fun snapshot(): BtInsightSnapshot = BtInsightSnapshot(
        insight = BtInsight.ASSET_CLASSES,
        asOfEpochDay = 20_684L,
        fromEpochDay = 20_684L,
        toEpochDay = 20_684L,
        datums = listOf(
            VizDatum("type:stock", "Aktien", 16_203.28),
            VizDatum("type:crypto", "Krypto", 6_172.68),
        ),
        headline = BtInsightValue.Money(38_579.23),
        facts = listOf(
            BtInsightFact(1, BtInsightValue.Money(16_203.28)),
            BtInsightFact(2, BtInsightValue.Percent(42.0)),
            BtInsightFact(3, BtInsightValue.Text("Aktien")),
            BtInsightFact(4, BtInsightValue.Count(6, 5)),
        ),
        caption = BtInsightCaption(6, "Aktien", BtInsightValue.Percent(42.0)),
        total = 38_579.23,
    )

    @Test
    fun `hiding amounts removes every absolute euro value`() {
        val hidden = insightHideAmounts(snapshot())
        assertTrue(
            "a euro headline survived the transform",
            hidden.headline !is BtInsightValue.Money,
        )
        assertTrue(
            "a euro amount survived the transform",
            hidden.facts.none { it.value is BtInsightValue.Money },
        )
    }

    /**
     * "Promote an available meaningful percentage to the headline." A poster
     * whose biggest line reads `Betrag ausgeblendet` has removed the answer
     * along with the balance, so a part-to-whole insight leads with its largest
     * mark's share instead.
     */
    @Test
    fun `a removed money headline is replaced by the largest share`() {
        val hidden = insightHideAmounts(snapshot())
        val headline = hidden.headline
        assertTrue("expected a promoted share, got $headline", headline is BtInsightValue.Percent)
        // 16.203,28 of 38.579,23 — the largest drawn mark.
        assertEquals(42.0, (headline as BtInsightValue.Percent).pct, 0.05)
    }

    /** With no whole to be a share of, the placeholder is still the honest answer. */
    @Test
    fun `a signed insight keeps the placeholder because it has no denominator`() {
        val signed = snapshot().copy(
            signed = true,
            total = 0.0,
            headline = BtInsightValue.Money(-116.99, signed = true),
        )
        assertEquals(BtInsightValue.Hidden, insightHideAmounts(signed).headline)
    }

    @Test
    fun `hiding amounts keeps percentages, counts and names`() {
        val hidden = insightHideAmounts(snapshot())
        val kinds = hidden.facts.map { it.value::class }
        assertTrue(kinds.contains(BtInsightValue.Percent::class))
        assertTrue(kinds.contains(BtInsightValue.Text::class))
        assertTrue(kinds.contains(BtInsightValue.Count::class))
    }

    /**
     * The geometry must survive: a treemap whose tiles were zeroed would not be
     * a private chart, it would be a blank one. The renderer is what must not
     * PRINT the values, and [BT_INSIGHT_IMAGE_LABELS_ARE_SHARES] is the flag
     * that tells it so.
     */
    @Test
    fun `hiding amounts leaves the chart data intact so shapes still read`() {
        val original = snapshot()
        val hidden = insightHideAmounts(original)
        assertEquals(original.datums, hidden.datums)
        assertEquals(original.total, hidden.total, 0.001)
        assertTrue(BT_INSIGHT_IMAGE_LABELS_ARE_SHARES)
    }

    /** "Promote an available meaningful percentage to the headline." */
    @Test
    fun `a money-and-percentage headline degrades to its percentage`() {
        val hidden = insightHideAmounts(
            snapshot().copy(headline = BtInsightValue.MoneyPercent(4_827.10, 14.30)),
        )
        assertEquals(BtInsightValue.Percent(14.30, signed = true), hidden.headline)
    }

    @Test
    fun `a caption whose only argument was an amount is dropped rather than gutted`() {
        val hidden = insightHideAmounts(
            snapshot().copy(caption = BtInsightCaption(7, null, BtInsightValue.Money(1_234.0))),
        )
        assertNull(hidden.caption)
    }

    @Test
    fun `a caption keeps its percentage argument`() {
        val hidden = insightHideAmounts(snapshot())
        assertEquals(BtInsightValue.Percent(42.0), hidden.caption?.value)
        assertEquals("Aktien", hidden.caption?.name)
    }

    @Test
    fun `facts reduced to a bare placeholder are dropped, not printed as noise`() {
        val hidden = insightHideAmounts(snapshot())
        assertTrue(hidden.facts.none { it.value == BtInsightValue.Hidden })
        // The three non-money facts survive.
        assertEquals(3, hidden.facts.size)
    }

    // ── Ruling 1: the default, and that it is never remembered ──────────────

    /**
     * The default is `true`, expressed as a plain `remember` in the sheet. If
     * this assertion ever needs relaxing, the ruling is being changed — which is
     * an owner decision, not a refactor.
     */
    @Test
    fun `the hide-amounts switch starts on every time the sheet opens`() {
        val sheet = read("ui/insights/InsightShareSheet.kt")
        assertTrue(
            "hideAmounts must be initialised to true",
            Regex("""var\s+hideAmounts\s+by\s+remember\s*\{\s*mutableStateOf\(true\)\s*\}""")
                .containsMatchIn(sheet),
        )
        assertFalse(
            "hideAmounts must not be seeded from a saved value",
            Regex("""mutableStateOf\(\s*\w*[Pp]refs""").containsMatchIn(sheet),
        )
    }

    @Test
    fun `nothing anywhere persists the hide-amounts choice`() {
        val offenders = sourceRoot()
            .resolve("ui/insights")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                Regex("""(setCard|setPage|setConfig|edit\s*\{|putBoolean)""")
                    .containsMatchIn(text) && text.contains("hideAmounts")
            }
            .map { it.name }
            .toList()
        assertTrue(
            "a file both mentions hideAmounts and writes a preference: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the insights preference store has no key for hiding amounts`() {
        val prefs = read("data/prefs/InsightsPrefs.kt")
        val keys = Regex("""const val KEY_[A-Z_]+\s*=\s*"([^"]+)"""")
            .findAll(prefs)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("the store declares no keys at all — did it change shape?", keys.isNotEmpty())
        keys.forEach { key ->
            assertFalse(
                "InsightsPrefs must not store an amount-hiding preference: $key",
                key.contains("hide", ignoreCase = true) || key.contains("amount", ignoreCase = true),
            )
        }
    }

    // ── Ruling 2: the PDF ───────────────────────────────────────────────────

    @Test
    fun `the PDF is declared to carry real values`() {
        assertTrue(BT_INSIGHTS_PDF_CARRIES_REAL_VALUES)
    }

    /**
     * The report document must build its paint labels with `showAmounts` on. A
     * report that silently masked its own numbers would be unusable as the
     * personal record it is meant to be.
     */
    @Test
    fun `the report document never disables amounts`() {
        val doc = read("ui/insights/InsightsReportDoc.kt")
        assertTrue(
            "the report must set showAmounts from the ruling constant",
            doc.contains("showAmounts = BT_INSIGHTS_PDF_CARRIES_REAL_VALUES"),
        )
        assertFalse(
            "the report must never build labels with amounts hidden",
            doc.contains("showAmounts = false"),
        )
        assertFalse(
            "the report must not apply the image privacy transform",
            doc.contains("insightHideAmounts"),
        )
    }

    /**
     * The export formatter, not the screen formatter. Discreet mode masks the
     * screen; a file the user asked for is not a screen.
     */
    @Test
    fun `the report formats money through the export path`() {
        val doc = read("ui/insights/InsightsReportDoc.kt")
        assertTrue(doc.contains("btFormatMoneyExport"))
        assertTrue(doc.contains("export = true"))
    }

    @Test
    fun `the export formatter is the only masked-value bypass these files use`() {
        // `masked = false` may appear in exactly one file app-wide
        // (`BtNumberFormat.kt`); the insights package goes through the named
        // export helper instead. This mirrors DiscreetExportRulingTest's rule.
        val offenders = sourceRoot()
            .resolve("ui/insights")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""masked\s*=\s*false""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue("insights files must not bypass masking directly: $offenders", offenders.isEmpty())
    }

    // ── Metadata hygiene ────────────────────────────────────────────────────

    /**
     * A PNG written by `Bitmap.compress` carries no EXIF, so the leaks that
     * remain possible are the ones we would have written ourselves: a filesystem
     * path, a content URI or an account identifier in a file name or a caption.
     */
    @Test
    fun `an exported file name can never carry a path or an account identifier`() {
        val hostile = insightImageFileName(
            subject = "/data/user/0/at.bettertrack.app/chris@example.com",
            isoDate = "2026-08-18",
            suffix = "quadrat",
        )
        BT_INSIGHT_IMAGE_FORBIDDEN_TOKENS
            .filterNot { it == "@" }
            .forEach { token ->
                assertFalse("file name leaked $token: $hostile", hostile.contains(token))
            }
    }

    @Test
    fun `the image renderer is never handed a portfolio id`() {
        val sheet = read("ui/insights/InsightShareSheet.kt")
        // The doc builder takes a display LABEL, never an id set. Passing
        // `scopeIds` here would put a database identifier on a public poster.
        assertFalse(
            "the image document must not receive portfolio ids",
            Regex("""buildInsightImageDoc\([^)]*portfolioIds""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(sheet),
        )
        assertTrue(
            "with amounts hidden the scope must degrade to a generic label",
            sheet.contains("if (hideAmounts) overallScope else scopeLabel"),
        )
    }
}
