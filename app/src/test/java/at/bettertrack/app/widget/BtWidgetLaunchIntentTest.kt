package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The whole-card tap opens the app, not a page** (owner ruling 2026-08-18:
 * *"the default thing if you click any widget should be not the overview but
 * just open the app. on no specific page. because always getting set to overview
 * is annoying when you click the edge of a widget."*).
 *
 * `btWidgetLaunchIntent` itself needs a `Context` and an `Intent`, neither of
 * which exists on the JVM, so what is pinned here is everything about it that
 * IS pure — the action string's uniqueness, and the fact that its slug resolves
 * to no destination — plus a source scan for the two properties that carry the
 * behaviour: no target extra, and no `FLAG_ACTIVITY_CLEAR_TOP`.
 */
class BtWidgetLaunchIntentTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile || it.isDirectory }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun deepLinksSource(): String =
        projectFile("src/main/java/at/bettertrack/app/widget/BtWidgetDeepLinks.kt").readText()

    /** Every destination the widget vocabulary can name. */
    private val targets = listOf(
        BT_WIDGET_TARGET_OVERVIEW,
        BT_WIDGET_TARGET_ASSET,
        BT_WIDGET_TARGET_CASH,
        BT_WIDGET_TARGET_PORTFOLIO,
        BT_WIDGET_TARGET_ADD_TRANSACTION,
        BT_WIDGET_TARGET_ADD_CASH,
        BT_WIDGET_TARGET_SEARCH,
        BT_WIDGET_TARGET_CHAT,
        BT_WIDGET_TARGET_SOCIAL,
        BT_WIDGET_TARGET_WATCHLIST,
        BT_WIDGET_TARGET_CASH_ENTRY,
    )

    @Test
    fun `the launch slug names no destination at all`() {
        // The whole point of the plain launch: nothing is parked on
        // AppGraph.pendingDeepLink, so AppShell runs none of its landing
        // discipline and the app resumes on whatever screen it was left on.
        assertNull(btWidgetDeepLink(BT_WIDGET_LAUNCH_SLUG, null))
        assertNull(btWidgetDeepLink(BT_WIDGET_LAUNCH_SLUG, "AAPL", "pf-1", "src-1", true))
        // …and the no-target case MainActivity actually sees.
        assertNull(btWidgetDeepLink(null, null))
    }

    @Test
    fun `the launch slug is not one of the destinations`() {
        assertTrue(
            "'$BT_WIDGET_LAUNCH_SLUG' collides with a real target, so a launch tap could " +
                "start navigating",
            BT_WIDGET_LAUNCH_SLUG !in targets,
        )
    }

    @Test
    fun `the launch action cannot collapse into a targeted PendingIntent`() {
        // PendingIntent equality is Intent.filterEquals, which ignores extras —
        // so two widget intents that share an action are ONE PendingIntent. If
        // the launch action could be produced by any targeted intent, the
        // launcher would fire whichever was registered first and the plain
        // launch would silently navigate.
        val targeted = buildList<String> {
            targets.forEach { target ->
                add(btWidgetIntentAction(target, null))
                add(btWidgetIntentAction(target, "AAPL"))
                add(btWidgetIntentAction(target, "pf-1"))
                add(btWidgetIntentAction(target, "pf-1", "src-1.in"))
                add(btWidgetIntentAction(target, "pf-1", "src-1.out"))
            }
        }
        assertTrue(
            "the launch action '$BT_WIDGET_LAUNCH_ACTION' is also a targeted action",
            BT_WIDGET_LAUNCH_ACTION !in targeted,
        )
        // It is built out of the same vocabulary, so it stays recognisable as
        // one of ours rather than being a loose string.
        assertEquals(btWidgetIntentAction(BT_WIDGET_LAUNCH_SLUG, null), BT_WIDGET_LAUNCH_ACTION)
        assertTrue(BT_WIDGET_LAUNCH_ACTION.startsWith("bt.widget.open."))
    }

    @Test
    fun `all ten widgets share one launch PendingIntent, on purpose`() {
        // Stability, not distinctness, is the property here: every plain launch
        // does the identical thing, so collapsing them is correct — and it also
        // means the string may not be built per-call out of anything variable.
        assertEquals(BT_WIDGET_LAUNCH_ACTION, btWidgetIntentAction(BT_WIDGET_LAUNCH_SLUG, null))
    }

    @Test
    fun `the launch intent carries no target extra and does not clear the task`() {
        val body = Regex(
            """fun btWidgetLaunchIntent\(context: Context\): Intent =(.*?)\n {4}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(deepLinksSource())?.groupValues?.get(1)
            ?: error("btWidgetLaunchIntent is gone")

        assertTrue(
            "the launch intent puts an extra on itself; anything MainActivity can read as a " +
                "target defeats the whole ruling",
            !body.contains("putExtra"),
        )
        assertTrue(
            "FLAG_ACTIVITY_CLEAR_TOP tears the task back down to MainActivity, which is the " +
                "opposite of resuming where the user left off",
            !body.contains("FLAG_ACTIVITY_CLEAR_TOP"),
        )
        assertTrue(
            "a launcher-process start needs FLAG_ACTIVITY_NEW_TASK",
            body.contains("FLAG_ACTIVITY_NEW_TASK"),
        )
        assertTrue(
            "the launch intent must carry the distinct launch action",
            body.contains("BT_WIDGET_LAUNCH_ACTION"),
        )
    }

    // ── Which cards took the new default ──────────────────────────────────────

    private fun widgetFile(name: String): String =
        projectFile("src/main/java/at/bettertrack/app/widget/$name").readText()

    /**
     * The cards with no single subject. Their whole surface is a near-miss zone,
     * so a tap on it must not navigate — this is the set the owner named.
     */
    private val plainLaunchCards = listOf(
        "BtNetWorthWidget.kt",
        "BtPortfolioWidget.kt",
        "BtWatchlistWidget.kt",
        "BtMoversWidget.kt",
        "BtAllocationWidget.kt",
        "BtQuickLinksWidget.kt",
    )

    @Test
    fun `the subject-less cards open the app rather than the Overview`() {
        val missing = plainLaunchCards.filter { name ->
            !widgetFile(name).contains("action = actionStartActivity(btWidgetLaunchIntent(context))")
        }
        assertTrue(
            "these do not give their card background the plain launch: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `nothing in the widget package still forces the Overview from a tap`() {
        // The exact expression the owner's complaint was about. Scoped to the
        // TAP (actionStartActivity), so a per-tile Overview target in the
        // Quick-Links catalog — a tile the user deliberately chose — is left
        // alone; it is the whole-card default that had to change.
        val forced = Regex(
            """actionStartActivity\(\s*btWidgetIntent\(context,\s*BT_WIDGET_TARGET_OVERVIEW""",
        )
        val offenders = projectFile("src/main/java/at/bettertrack/app/widget")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { forced.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue("these still send a tap straight to the Overview: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the specific tap targets keep their deep links`() {
        // The ruling is about the card BACKGROUND. An element that names one
        // thing — a ticker row, a Quick-Links tile, a posting button, the asset
        // card, the cash cards — must still open that thing, or the widgets stop
        // being shortcuts at all.
        mapOf(
            "BtRowFamily.kt" to BT_WIDGET_TARGET_ASSET,
            "BtAssetWidget.kt" to BT_WIDGET_TARGET_ASSET,
            "BtBudgetWidget.kt" to BT_WIDGET_TARGET_CASH,
            "BtSpendingWidget.kt" to BT_WIDGET_TARGET_CASH,
            "BtCashWalletWidget.kt" to BT_WIDGET_TARGET_CASH_ENTRY,
        ).forEach { (name, target) ->
            assertTrue(
                "$name lost its $target deep link",
                widgetFile(name).contains("BT_WIDGET_TARGET_${target.uppercase()}") ||
                    widgetFile(name).contains(target),
            )
        }
        // The Quick-Links grid keeps per-tile targets even though its card
        // background became a plain launch — a tile the user deliberately
        // configured must still go where they configured it.
        assertTrue(
            "the Quick-Links tiles lost their targeted intents",
            widgetFile("BtQuickLinksWidget.kt").contains("btWidgetIntent("),
        )
    }
}
