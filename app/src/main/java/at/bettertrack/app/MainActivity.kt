package at.bettertrack.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import at.bettertrack.app.data.auth.AuthRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.shell.BtRoot
import at.bettertrack.app.ui.theme.BetterTrackTheme

class MainActivity : ComponentActivity() {

    private val auth: AuthRepository by lazy { AppGraph.authRepository }

    /**
     * True while the OAuth Custom Tab is open and we're waiting to come back. If
     * we resume WITHOUT having received the redirect (the user closed the tab),
     * that's a silent cancel → back to idle, never an error (spec §4).
     */
    private var awaitingTabReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Dark-only app ⇒ force dark system bars regardless of system setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            BetterTrackTheme {
                BtRoot(
                    onStartLogin = { startLogin() },
                    onOpenUrl = { url -> openInBrowser(url) },
                )
            }
        }
        // Cold-start OAuth callback (e.g. after process death while the tab was open).
        handleAuthDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Warm OAuth callback delivered to the singleTask activity.
        handleAuthDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        // Returned from the Custom Tab without a redirect ⇒ user cancelled.
        if (awaitingTabReturn) {
            awaitingTabReturn = false
            auth.onAuthorizationCancelled()
        }
    }

    private fun startLogin() {
        val url = auth.beginAuthorization()
        awaitingTabReturn = true
        launchCustomTab(url)
    }

    private fun handleAuthDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme == REDIRECT_SCHEME && data.host == REDIRECT_HOST) {
            awaitingTabReturn = false
            auth.onAuthorizationResult(data)
            // Consume it so a rotation / restart doesn't re-process the callback.
            intent.data = null
            setIntent(intent)
        }
    }

    private fun launchCustomTab(url: Uri) {
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
        try {
            customTabs.launchUrl(this, url)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No Custom Tabs / browser available; falling back to ACTION_VIEW.", e)
            openInBrowser(url.toString())
        }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No browser available to open $url", e)
        }
    }

    private companion object {
        const val TAG = "BtMainActivity"
        const val REDIRECT_SCHEME = "bettertrack"
        const val REDIRECT_HOST = "oauth"
        val BRAND_BG = AndroidColor.parseColor("#0B0E14")
    }
}
