package at.bettertrack.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import at.bettertrack.app.data.i18n.LocaleManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import at.bettertrack.app.data.auth.AuthRepository
import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.notifications.resolveDeepLink
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.data.push.BtMessagingService
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCustomTab
import at.bettertrack.app.ui.shell.BtRoot
import at.bettertrack.app.widget.BT_WIDGET_EXTRA_ASSET_ID
import at.bettertrack.app.widget.BT_WIDGET_EXTRA_PORTFOLIO_ID
import at.bettertrack.app.widget.BT_WIDGET_EXTRA_TARGET
import at.bettertrack.app.widget.btWidgetDeepLink
import kotlinx.serialization.json.Json
import at.bettertrack.app.ui.theme.BetterTrackTheme
import at.bettertrack.app.ui.theme.resolveDarkTheme
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
        // Screen-orientation lock (owner ask): apply the persisted preference before
        // the first frame — ON (default) = portrait-locked; OFF = follow the sensor.
        applyOrientation(AppGraph.devicePrefs.orientationLockedNow())
        lifecycleScope.launch {
            // `lifecycleScope` is a plain root scope: a throw inside a collector
            // reaches the process's default handler and takes the app down. None
            // of these device-preference mirrors is worth that.
            AppGraph.devicePrefs.orientationLocked.collect { locked ->
                guarded("applyOrientation") { applyOrientation(locked) }
            }
        }
        // System bars follow the resolved theme (B2 §1.5 B6). Applied before the
        // first frame from the synchronous preference read, then re-applied
        // whenever the choice changes — a bar style is a window attribute, so it
        // does not recompose with the Compose tree.
        applySystemBars(AppGraph.devicePrefs.themeModeNow())
        lifecycleScope.launch {
            AppGraph.devicePrefs.themeMode.collect { mode ->
                guarded("applySystemBars") { applySystemBars(mode) }
            }
        }
        setContent {
            val themeMode by AppGraph.devicePrefs.themeMode.collectAsState()
            val trueBlack by AppGraph.devicePrefs.trueBlack.collectAsState()
            BetterTrackTheme(mode = themeMode, trueBlack = trueBlack) {
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
        // Cold-start home-screen widget tap.
        handleWidgetIntent(intent)

        // Step 17 (§5): keep the recents/task-switcher mask in sync with the
        // app-lock enabled state. Driven off the controller (not onPause) so the
        // mask is armed on cold start AND the instant the user toggles the lock,
        // closing the "enable then immediately background" snapshot race.
        lifecycleScope.launch {
            AppGraph.appLockController.config.collect { config ->
                guarded("applyRecentsMasking") { applyRecentsMasking(config.enabled) }
            }
        }
    }

    /**
     * Runs a window/system side effect that is not the user's task. A failure is
     * logged and skipped rather than crashing the activity: an unmasked recents
     * preview or an unpinned orientation is a cosmetic defect, a dead app is not.
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(BtCrashGuard.NONFATAL_TAG, "$what failed; continuing.", e)
        }
    }

    /**
     * Point the status/navigation bar icon polarity at whichever colour table
     * the app is actually going to render in.
     *
     * `SystemBarStyle.auto` is given an explicit detector rather than its default
     * (which reads the system's night mode) because the app's theme is a user
     * choice that may deliberately disagree with the system — a forced-Dark app
     * on a light phone still needs light icons. Both scrims stay transparent:
     * the app draws edge-to-edge under the bars.
     */
    private fun applySystemBars(mode: BtThemeMode) {
        val detectDark: (Resources) -> Boolean = { res ->
            val systemInDark = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            resolveDarkTheme(mode, systemInDark)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
                detectDark,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
                detectDark,
            ),
        )
        // Turn OFF the platform's enforced navigation-bar contrast scrim.
        //
        // Found on device during the B2-B light matrix: in dark, the bottom 48dp
        // measured #11141A while the bar above it measured #1C222B — a visibly
        // darker band under the bottom bar, reading as an unintended second bar
        // directly beneath the one §6.2 just gave its own tone.
        //
        // The cause is `SystemBarStyle.auto`: androidx enables
        // `isNavigationBarContrastEnforced` for the auto style, and the platform
        // then paints a translucent scrim behind a transparent nav bar. The old
        // pre-B2 code used `SystemBarStyle.dark(...)`, which does not, so this
        // arrived with the B6 theme-aware bar styles and not with the bar itself.
        //
        // Disabling it is safe HERE specifically: the scrim exists to keep system
        // nav buttons legible over unknown app content, and the buttons are not
        // over unknown content — [BtBottomBar] paints an opaque `navBar` beneath
        // them and this very method has already pointed the button polarity at
        // the same resolved theme. Full-screen destinations run their own
        // Scaffold on an opaque page colour, so the guarantee holds there too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    /**
     * Apply the orientation preference. Locked ⇒ pinned PORTRAIT (exactly as
     * before). Unlocked ⇒ FULL_USER: follow the device sensor when the user's
     * auto-rotate is on (a tablet can go landscape), while still honoring an
     * explicit rotation lock — the well-behaved Android choice.
     */
    private fun applyOrientation(locked: Boolean) {
        requestedOrientation = when (at.bettertrack.app.data.prefs.orientationModeFor(locked)) {
            at.bettertrack.app.data.prefs.ScreenOrientationMode.LOCKED_PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            at.bettertrack.app.data.prefs.ScreenOrientationMode.FOLLOW_SENSOR ->
                ActivityInfo.SCREEN_ORIENTATION_FULL_USER
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
        // Warm home-screen widget tap.
        handleWidgetIntent(intent)
    }

    /**
     * A tapped home-screen widget carries its target as extras (see
     * [at.bettertrack.app.widget.btWidgetIntent]). Resolve and park it on the
     * same holder the push path uses, so the shell applies the identical landing
     * discipline — clear the sheets, switch to the owning tab, then push.
     *
     * Separate from [handleNotificationIntent] on purpose: that one decodes the
     * FCM wire, and a widget is not a notification. Both funnel into
     * `AppGraph.pendingDeepLink`, which is where the sharing belongs.
     */
    private fun handleWidgetIntent(intent: Intent?) {
        val target = intent?.getStringExtra(BT_WIDGET_EXTRA_TARGET) ?: return
        val assetId = intent.getStringExtra(BT_WIDGET_EXTRA_ASSET_ID)
        val portfolioId = intent.getStringExtra(BT_WIDGET_EXTRA_PORTFOLIO_ID)
        btWidgetDeepLink(target, assetId, portfolioId)?.let { AppGraph.pendingDeepLink.value = it }
        // Consume so a rotation/restart doesn't re-fire the deep link.
        intent.removeExtra(BT_WIDGET_EXTRA_TARGET)
        intent.removeExtra(BT_WIDGET_EXTRA_ASSET_ID)
        intent.removeExtra(BT_WIDGET_EXTRA_PORTFOLIO_ID)
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
        // No specific target ⇒ the INBOX, which is what this path always claimed
        // to do and never did: the old `?.let` dropped the null and the app
        // cold-opened on Portfolio, so tapping a budget, chain or id-less alert
        // push showed the user nothing at all. See [NotifDeepLink.Inbox].
        AppGraph.pendingDeepLink.value = resolveDeepLink(type, payload) ?: NotifDeepLink.Inbox
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
        // The tab's chrome and its browser fallback live in [BtCustomTab] — this
        // was the original of the three copies that had grown apart. The only
        // thing added here is the log line, because a failed OAuth hand-off is
        // worth knowing about in a way a failed news link is not.
        if (!BtCustomTab.open(this, url)) {
            Log.w(TAG, "No Custom Tabs / browser available to open the authorize URL.")
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
    }
}
