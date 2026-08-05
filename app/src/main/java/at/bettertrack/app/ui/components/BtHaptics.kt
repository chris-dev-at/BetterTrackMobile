package at.bettertrack.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * BetterTrack's haptic vocabulary (R-arc R3 §4).
 *
 * ## The rule this type exists to enforce
 *
 * Haptics are **confirmation, never decoration**. A tick that fires on every
 * tappable thing teaches the user nothing and turns into noise they disable in
 * system settings — taking the two haptics that DID mean something with it. So
 * this app has exactly four haptic meanings and no fifth, and each one answers a
 * question the user is actually asking:
 *
 * | Meaning | When | Answers |
 * |---|---|---|
 * | [confirm] | a primary action committed | "did that go through?" |
 * | [toggle]  | a two-state control settled | "which way is it now?" |
 * | [reject]  | the app refused the input | "why didn't that work?" |
 * | [keyTap]  | one character of input landed | "did that key register?" |
 *
 * Everything else — navigating, opening a sheet, scrolling, pressing a
 * *secondary* button — stays silent. [BtSecondaryButton] deliberately has no
 * haptic while [BtPrimaryButton] does: that difference is what makes the primary
 * one mean "committed" rather than merely "pressed", and it puts the
 * primary/secondary distinction into the touch channel as well as the colour.
 *
 * ## Why it wraps Compose's [HapticFeedback] rather than the View API
 *
 * The app already had two idioms — `LocalView.current.performHapticFeedback(...)`
 * in the PIN keypad and `LocalHapticFeedback` in the two unlock failure paths.
 * This unifies on the Compose one, which maps each type to the best platform
 * constant the running API level has (and falls back on older ones) instead of
 * making every call site carry its own `Build.VERSION.SDK_INT` branch.
 *
 * Crucially, neither path forces feedback: both end at
 * `View.performHapticFeedback` *without* `FLAG_IGNORE_GLOBAL_SETTING`, so a user
 * who has turned touch feedback off in system settings gets silence. That was a
 * deliberate property of the keypad's original implementation and it survives
 * here — an app that vibrates after being told not to is a bug, not a feature.
 */
@JvmInline
value class BtHaptics(private val haptics: HapticFeedback) {

    /**
     * A primary action committed — a save, a create, a submit, a destructive
     * confirmation. The single tick that says the app took the input.
     */
    fun confirm() = haptics.performHapticFeedback(HapticFeedbackType.Confirm)

    /**
     * A two-state control settled. On and off feel different on purpose: a
     * toggle is the one control whose *result* the user cannot always see (the
     * switch may be under their thumb), so the direction is worth carrying in
     * the haptic itself.
     */
    fun toggle(on: Boolean) = haptics.performHapticFeedback(
        if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
    )

    /**
     * The app refused the input — a wrong PIN, a failed unlock, an optimistic
     * change the server rolled back. Firmer than [confirm] because it is the one
     * case where the user must look at the screen to find out why.
     */
    fun reject() = haptics.performHapticFeedback(HapticFeedbackType.Reject)

    /** One character of input landed. The PIN keypad's per-digit tick. */
    fun keyTap() = haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
}

/** The app's haptics, scoped to the current composition. */
@Composable
fun rememberBtHaptics(): BtHaptics {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { BtHaptics(haptics) }
}
