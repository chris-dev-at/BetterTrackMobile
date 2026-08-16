package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the home-screen widgets' manifest wiring.
 *
 * ## The failure this exists to catch
 *
 * An app widget is assembled from three things that nothing checks against each
 * other: a `<receiver>` in the manifest, a `<meta-data android:name=
 * "android.appwidget.provider">` pointing at an `appwidget-provider` XML, and a
 * class the receiver names. Break any one of the three and the build still
 * succeeds, the app still installs, and the widget is simply **not in the
 * picker** — with nothing in logcat, because from the framework's point of view
 * nothing went wrong. It is a silent-absence bug, which is the shape that
 * survives longest and is hardest to attribute.
 *
 * So the three are pinned to each other here, in both directions: a receiver
 * without its XML fails, and a widget CLASS that no receiver declares fails too.
 * The second direction is the one that matters when a widget is added — writing
 * the Glance class is the interesting part, and the manifest entry is the part
 * that gets forgotten.
 *
 * Reads the sources the same way [at.bettertrack.app.i18n.StringParityTest] and
 * [at.bettertrack.app.ui.components.CollapsingHeaderScrollGuardTest] read theirs:
 * relative to the module dir, tolerating a repo-root CWD.
 */
class BtWidgetManifestTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile || it.isDirectory }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun manifest(): String = projectFile("src/main/AndroidManifest.xml").readText()

    private fun mainSources(): List<File> =
        projectFile("src/main/java/at/bettertrack/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** `<receiver …>…</receiver>` blocks, as (name, body). Self-closing ones have an empty body. */
    private fun receivers(): List<Pair<String, String>> {
        val block = Regex("""<receiver\b([^>]*?)(/>|>(.*?)</receiver>)""", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("""android:name\s*=\s*"([^"]+)"""")
        return block.findAll(manifest()).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[3]
            name.find(attrs)?.groupValues?.get(1)?.let { it to body }
        }.toList()
    }

    /** The receivers that actually declare themselves as app widgets. */
    private fun widgetReceivers(): List<Pair<String, String>> =
        receivers().filter { (_, body) -> body.contains("android.appwidget.action.APPWIDGET_UPDATE") }

    /** Simple class name from a manifest `android:name` (".widget.Foo" → "Foo"). */
    private fun simpleName(manifestName: String): String = manifestName.substringAfterLast('.')

    @Test
    fun `the scan finds the app's widget receivers`() {
        // Guards against the regexes silently matching nothing after a manifest
        // reformat — a green suite that checks zero receivers is the worst
        // outcome this file could have.
        assertTrue("expected some <receiver> entries, found none", receivers().isNotEmpty())
        assertEquals(
            "expected the nine widget receivers of the 2026-08-16 redesign " +
                "(pulse/net worth, performance/portfolio, asset, watchlist, " +
                "movers, budget, monthly flow, allocation, quick actions), " +
                "found " + widgetReceivers().map { it.first },
            9,
            widgetReceivers().size,
        )
    }

    @Test
    fun `every widget receiver names an appwidget-provider xml that exists`() {
        val provider = Regex(
            """<meta-data\b[^>]*?android:name\s*=\s*"android\.appwidget\.provider"[^>]*?""" +
                """android:resource\s*=\s*"@xml/([A-Za-z0-9_]+)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val offenders = widgetReceivers().mapNotNull { (name, body) ->
            val res = provider.find(body)?.groupValues?.get(1)
                ?: return@mapNotNull "$name: no android.appwidget.provider meta-data"
            val file = File(projectFile("src/main/res/xml"), "$res.xml")
            if (!file.isFile) "$name: @xml/$res does not exist" else null
        }
        assertTrue(
            "A widget receiver whose provider XML is missing is absent from the " +
                "picker with no error at all (see this class's KDoc): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every widget receiver names a class that exists`() {
        val offenders = widgetReceivers().filterNot { (name, _) ->
            val simple = simpleName(name)
            mainSources().any { it.readText().contains("class $simple ") }
        }.map { it.first }
        assertTrue("manifest names a receiver class that does not exist: $offenders", offenders.isEmpty())
    }

    @Test
    fun `every declared widget class is wired into the manifest`() {
        // The direction that catches the real mistake: the Glance work is done,
        // the manifest entry is not, and the widget never appears. Concrete
        // receivers extend the shared BtWidgetReceiver base (which is itself the
        // GlanceAppWidgetReceiver subclass), so both spellings are scanned.
        val declared = widgetReceivers().map { simpleName(it.first) }.toSet()
        val defined = mainSources().flatMap { file ->
            Regex("""class\s+([A-Za-z0-9_]+)\s*:\s*(?:GlanceAppWidgetReceiver|BtWidgetReceiver)\(\)""")
                .findAll(file.readText())
                .map { it.groupValues[1] }
                .filterNot { it == "BtWidgetReceiver" }
                .toList()
        }
        assertTrue("no widget receiver subclasses found — has the scan broken?", defined.isNotEmpty())
        val missing = defined.filterNot { it in declared }
        assertTrue("widget receiver subclasses not declared in the manifest: $missing", missing.isEmpty())
    }

    @Test
    fun `every widget receiver is exported`() {
        // Not a relaxation: the launcher is another process and cannot bind an
        // unexported provider, so `exported="false"` here means "never shown".
        // Pinned because it reads like a security tightening and is a silent break.
        val offenders = receivers()
            .filter { (_, body) -> body.contains("android.appwidget.action.APPWIDGET_UPDATE") }
            .filterNot { (name, _) ->
                Regex("""<receiver\b[^>]*?android:name\s*=\s*"${Regex.escape(name)}"[^>]*?android:exported\s*=\s*"true"""", RegexOption.DOT_MATCHES_ALL)
                    .containsMatchIn(manifest())
            }
            .map { it.first }
        assertTrue("widget receivers must be exported to appear in the picker: $offenders", offenders.isEmpty())
    }

    @Test
    fun `every configure activity a provider names is a registered, exported activity`() {
        // The configurable widgets' equivalent of the receiver/XML pairing: an
        // android:configure that names an unregistered (or unexported) Activity
        // is a widget whose placement hangs or silently cancels — the host is
        // another app and can only launch what the manifest admits to.
        val xmlDir = projectFile("src/main/res/xml")
        val configures = xmlDir.listFiles().orEmpty()
            .filter { it.isFile && it.readText().contains("<appwidget-provider") }
            .mapNotNull { file ->
                Regex("""android:configure\s*=\s*"([^"]+)"""").find(file.readText())
                    ?.groupValues?.get(1)?.let { file.name to it }
            }
        assertTrue("expected at least one configurable widget", configures.isNotEmpty())

        val offenders = configures.flatMap { (xml, activity) ->
            val simple = simpleName(activity)
            buildList {
                val registered = Regex(
                    """<activity\b[^>]*?android:name\s*=\s*"[^"]*$simple"[^>]*?>""",
                    RegexOption.DOT_MATCHES_ALL,
                ).containsMatchIn(manifest())
                if (!registered) add("$xml: $activity is not a registered <activity>")
                val exported = Regex(
                    """<activity\b[^>]*?android:name\s*=\s*"[^"]*$simple"[^>]*?android:exported\s*=\s*"true"""",
                    RegexOption.DOT_MATCHES_ALL,
                ).containsMatchIn(manifest()) || Regex(
                    """<activity\b[^>]*?android:exported\s*=\s*"true"[^>]*?android:name\s*=\s*"[^"]*$simple"""",
                    RegexOption.DOT_MATCHES_ALL,
                ).containsMatchIn(manifest())
                if (registered && !exported) add("$xml: $activity must be exported for the host to launch it")
                if (!mainSources().any { it.readText().contains("class $simple ") }) {
                    add("$xml: $activity names a class that does not exist")
                }
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `every appwidget-provider xml declares what a Glance widget needs`() {
        val xmlDir = projectFile("src/main/res/xml")
        val providers = xmlDir.listFiles().orEmpty()
            .filter { it.isFile && it.readText().contains("<appwidget-provider") }
        assertTrue("no appwidget-provider XML found in res/xml", providers.isNotEmpty())

        val offenders = providers.flatMap { file ->
            val text = file.readText()
            buildList {
                // A Glance widget has no layout of its own; without an
                // initialLayout the host has nothing to inflate on placement.
                if (!text.contains("android:initialLayout")) add("${file.name}: no initialLayout")
                if (!text.contains("android:resizeMode")) add("${file.name}: no resizeMode")
                // The platform's own update alarm cannot be network-constrained
                // and wakes the device; BtWidgetScheduler owns the cadence.
                if (!text.contains("""android:updatePeriodMillis="0"""")) {
                    add("${file.name}: updatePeriodMillis must be 0 — see BtWidgetScheduler")
                }
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `every provider ships real picker previews, not the loading spinner`() {
        // The owner's 2026-08-16 verdict: "the preview for these dont load …
        // give them like a dummy view". previewLayout (API 31+) and
        // previewImage (older pickers) are the fix, and both must exist and
        // point at resources that exist — a provider without them regresses to
        // the spinner (or the bare app icon) silently.
        val xmlDir = projectFile("src/main/res/xml")
        val layoutDir = projectFile("src/main/res/layout")
        val drawableDir = projectFile("src/main/res/drawable")
        val providers = xmlDir.listFiles().orEmpty()
            .filter { it.isFile && it.readText().contains("<appwidget-provider") }
        assertTrue("no appwidget-provider XML found", providers.isNotEmpty())

        val offenders = providers.flatMap { file ->
            val text = file.readText()
            buildList {
                val layout = Regex("""android:previewLayout\s*=\s*"@layout/([A-Za-z0-9_]+)"""")
                    .find(text)?.groupValues?.get(1)
                when {
                    layout == null -> add("${file.name}: no previewLayout")
                    layout.startsWith("glance_") ->
                        add("${file.name}: previewLayout is the Glance spinner again")
                    !File(layoutDir, "$layout.xml").isFile ->
                        add("${file.name}: previewLayout @layout/$layout does not exist")
                }
                val image = Regex("""android:previewImage\s*=\s*"@drawable/([A-Za-z0-9_]+)"""")
                    .find(text)?.groupValues?.get(1)
                when {
                    image == null -> add("${file.name}: no previewImage (pre-31 pickers)")
                    !File(drawableDir, "$image.xml").isFile ->
                        add("${file.name}: previewImage @drawable/$image does not exist")
                }
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `optional configuration always names a configure activity`() {
        // configuration_optional without android:configure is a contradiction
        // the platform ignores silently: the flag only means anything when
        // there is a configure step to skip.
        val xmlDir = projectFile("src/main/res/xml")
        val offenders = xmlDir.listFiles().orEmpty()
            .filter { it.isFile && it.readText().contains("configuration_optional") }
            .filterNot { it.readText().contains("android:configure") }
            .map { it.name }
        assertTrue(
            "configuration_optional without android:configure: $offenders",
            offenders.isEmpty(),
        )
    }
}
