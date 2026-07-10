package at.bettertrack.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import at.bettertrack.app.data.i18n.LocaleManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import at.bettertrack.app.data.auth.AuthRepository
import at.bettertrack.app.data.notifications.resolveDeepLink
import at.bettertrack.app.data.push.BtMessagingService
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.shell.BtRoot
import kotlinx.serialization.json.Json
import at.bettertrack.app.ui.theme.BetterTrackTheme
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * A [FragmentActivity] (not a bare ComponentActivity) so the Step-17 app lock can
 * host androidx BiometricPrompt, which requires a FragmentActivity. All the
 * Compose/edge-to-edge/splash APIs used here are ComponentActivity extensions,
 * which FragmentActivity still is — so nothing else changes.
 */
class MainActivity : FragmentActivity() {

    private val auth: AuthRepository by lazy { AppGraph.authRepository }

    /**
     * Step 18 (§6.12): apply the chosen per-app language to the activity's base
     * context so strings/formatting resolve in it deterministically on every API
     * level (the framework/appcompat persistence handles the store; this makes the
     * apply robust for a plain FragmentActivity). A no-op for "System default".
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

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
        // Cold-start notification tap (Step 16): park the deep-link target.
        handleNotificationIntent(intent)

        // Step 17 (§5): keep the recents/task-switcher mask in sync with the
        // app-lock enabled state. Driven off the controller (not onPause) so the
        // mask is armed on cold start AND the instant the user toggles the lock,
        // closing the "enable then immediately background" snapshot race.
        lifecycleScope.launch {
            AppGraph.appLockController.config.collect { applyRecentsMasking(it.enabled) }
        }
    }

    /**
     * Hide app content in the recents preview when the lock is on (spec §5).
     * On API 33+ we suppress the task snapshot entirely (`setRecentsScreenshotEnabled`),
     * which shows the splash background in recents and — unlike FLAG_SECURE — does
     * NOT blacken the live window, so legitimate in-app screenshots still work.
     * Pre-33 falls back to FLAG_SECURE (also blocks screenshots, an accepted
     * trade-off on those older devices).
     */
    private fun applyRecentsMasking(lockEnabled: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!lockEnabled)
        } else if (lockEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Warm OAuth callback delivered to the singleTask activity.
        handleAuthDeepLink(intent)
        // Warm notification tap.
        handleNotificationIntent(intent)
    }

    /**
     * A tapped push (from [BtMessagingService]) carries the notification type +
     * payload as extras. Resolve them to a deep-link target and park it on the
     * shared holder; the shell consumes it once (and only when logged in).
     */
    private fun handleNotificationIntent(intent: Intent?) {
        val type = intent?.getStringExtra(BtMessagingService.EXTRA_TYPE) ?: return
        val payloadRaw = intent.getStringExtra(BtMessagingService.EXTRA_PAYLOAD)
        val payload = payloadRaw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        resolveDeepLink(type, payload)?.let { AppGraph.pendingDeepLink.value = it }
        // Consume so a rotation/restart doesn't re-fire the deep link.
        intent.removeExtra(BtMessagingService.EXTRA_TYPE)
        intent.removeExtra(BtMessagingService.EXTRA_PAYLOAD)
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
