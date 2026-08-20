package at.bettertrack.app.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android: "remove animations" is the animator duration scale at 0. Moved here
 * verbatim from `BtSkeleton.kt` (web port, W1) — same read, same fallback of
 * `1f` when the setting is absent, same `remember` so it is sampled once per
 * composition rather than on every frame of a press.
 */
@Composable
actual fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
