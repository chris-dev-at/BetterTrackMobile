package at.bettertrack.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The device locale, read the way Compose wants it read (S6 P2-18).
 *
 * Twenty-three lint errors in this app had ONE root cause: composables reaching
 * for the locale by hand, either as `LocalConfiguration.current.locales[0] ?:
 * Locale.getDefault()` (21 near-identical lines) or as a bare
 * `Locale.getDefault()` (which Compose cannot observe at all, so formatted text
 * kept the old language until the screen happened to recompose for some other
 * reason).
 *
 * [rememberBtLocale] is the single supported way to ask. It reads
 * `LocalConfiguration`, so a language change invalidates every caller and
 * everything reformats — which is exactly what the lint rule was asking for.
 *
 * `Locale.getDefault()` remains correct OUTSIDE composition (repositories,
 * view-models, workers) — this helper is for the composable half only.
 */
// The ONE sanctioned `Locale.getDefault()` in composition: it is the fallback
// for a configuration that reports no locale at all, and it sits behind the
// observable `LocalConfiguration` read that satisfies the rule everywhere else.
// Suppressed here so the rule keeps failing the build at all 30 other sites.
@Suppress("NonObservableLocale")
@Composable
@ReadOnlyComposable
fun rememberBtLocale(): Locale {
    val configuration = LocalConfiguration.current
    return configuration.locales[0] ?: Locale.getDefault()
}

/**
 * True when the UI is currently rendering in German. A couple of screens pick
 * between two hand-written copies rather than two resources (long-form legal and
 * about text); they ask here so they observe the configuration like everyone
 * else instead of consulting `Locale.getDefault()`.
 */
@Composable
@ReadOnlyComposable
fun isGermanUi(): Boolean = rememberBtLocale().language == "de"
