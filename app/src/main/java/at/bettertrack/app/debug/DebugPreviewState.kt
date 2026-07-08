package at.bettertrack.app.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Debug-only preview switches, toggled from the component gallery. Real wiring
 * (connectivity + data age) replaces the offline-banner flag in Step 5.
 */
object DebugPreviewState {
    var showOfflineBanner by mutableStateOf(false)
}
