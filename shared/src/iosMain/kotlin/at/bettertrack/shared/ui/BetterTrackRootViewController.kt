package at.bettertrack.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The single hand-off point from UIKit into shared Compose code.
 *
 * The iOS executable (`:iosApp`) owns nothing but an `AppDelegate`; it asks for
 * this controller and makes it the window's root. Everything visible from here
 * down is Kotlin compiled from `:shared`, so growing the iOS app in later phases
 * means adding shared composables — not Swift.
 */
fun BetterTrackRootViewController(): UIViewController = ComposeUIViewController {
    MaterialTheme {
        IosProofScreen()
    }
}
