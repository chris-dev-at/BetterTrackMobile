package at.bettertrack.app.ui.components

import androidx.compose.runtime.Composable

/**
 * Haptics on the two platforms with no motor behind them (web port, W1).
 *
 * **Web:** a browser tab has no haptic API. `navigator.vibrate` exists on some
 * mobile browsers, but it is a notification-grade buzz with no vocabulary and
 * no user-facing intensity setting to respect, so wiring it up would be
 * inventing a feel rather than porting one. Silence is the honest answer.
 *
 * **iOS:** `UIImpactFeedbackGenerator`/`UINotificationFeedbackGenerator` are the
 * real counterparts of the six meanings and the iOS app will want them — but
 * the iOS app has no production UI yet (it renders the domain proof screen), so
 * there is nothing to feel. Named here rather than left implicit: whoever gives
 * `:iosApp` its first real screen should give it its haptics in the same pass.
 */
@Composable
actual fun rememberBtHaptics(): BtHaptics = BtNoHaptics
