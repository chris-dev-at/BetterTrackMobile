package at.bettertrack.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * The app's single way to hand a URL to the browser.
 *
 * Three copies of this builder had accumulated — the OAuth launcher in
 * `MainActivity`, the paranoid explainer's "open the web app", and the market
 * intel section's news links — each with the same brand colours and the same
 * fail-soft fallback, and each one a place the app's chrome could drift out of
 * step with the other two. This is that builder, once.
 *
 * Behaviour worth knowing at the call site:
 *  - A Custom Tab keeps the user inside the app's task with the app's dark
 *    chrome, which is why it is preferred over a bare `ACTION_VIEW`.
 *  - It is **fail-soft by design**: with no Custom Tabs provider installed it
 *    degrades to a plain browser intent, and if that fails too it does nothing
 *    rather than throwing. Opening a news article must never be able to crash
 *    the screen that linked to it.
 *  - [FLAG_ACTIVITY_NEW_TASK] is set on the fallback so a non-Activity context
 *    (a composable reached through an application context) still works.
 */
object BtCustomTab {

    /** The app's near-black chrome (`BtColors.bg`) for the tab's bars. */
    private const val BRAND_BG = 0xFF0B0E14.toInt()

    /**
     * Open [url] in a Custom Tab. Returns true when something took it — callers
     * that want to say "couldn't open this" can, though most correctly ignore it.
     */
    fun open(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return open(context, uri)
    }

    fun open(context: Context, uri: Uri): Boolean {
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(BRAND_BG)
            .setNavigationBarColor(BRAND_BG)
            .build()
        val customTabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(colors)
            .build()
        if (runCatching { customTabs.launchUrl(context, uri) }.isSuccess) return true
        // No Custom Tabs provider (or a non-Activity context): a plain VIEW
        // intent still gets the user there.
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }
}
