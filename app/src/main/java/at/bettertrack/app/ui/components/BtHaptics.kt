package at.bettertrack.app.ui.components

import android.content.ContentResolver
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
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
 * | [detent]  | a drag crossed a decision boundary | "have I pulled far enough?" |
 *
 * [detent] is a fifth meaning added later, on the owner's explicit instruction,
 * and its KDoc carries the argument for why it belongs rather than assuming it.
 * It has exactly one call site. A sixth needs the same standard of proof.
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
class BtHaptics(
    private val haptics: HapticFeedback,
    /** See [detent]. Null when the device has no vibrator to reach past Compose. */
    private val detentVibrator: BtDetent? = null,
    /** See [scrubTick]. Null on the same devices [detentVibrator] is null on. */
    private val scrubVibrator: BtDetent? = null,
) {

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

    /**
     * A drag crossed a threshold that changes what letting go will do.
     *
     * **The one exception to the four-meanings rule above, added on the owner's
     * explicit instruction (2026-08-09): "make a vibration haptic once you go
     * past it".** It has exactly one call site — the sheet's notch, where pulling
     * past [at.bettertrack.app.ui.shell.SHEET_NOTCH_END] changes a release from
     * "back one page" into "close the entire stack".
     *
     * It earns its place by the same test the other four pass: it answers a
     * question the user is actually asking, and one they cannot answer any other
     * way. The notch is a *decision boundary the finger is standing on*, felt
     * through stiffening resistance rather than seen — the grabber pill widens,
     * but the thumb is on the screen and the eye is not necessarily on the pill.
     * A detent is how physical controls have always reported this.
     *
     * ## Why this one reaches past Compose to the [Vibrator]
     *
     * It shipped as [HapticFeedbackType.LongPress] and the owner did not feel it
     * (2026-08-09: "make that haptic vibration once you go past"). Measured on the
     * test phone: `LongPress` leaves Compose as `View.performHapticFeedback`, which
     * Samsung resolves to its own light pattern — `SemHaptic{mType=50025}` in
     * `dumpsys vibrator_manager` — and then plays on the TOUCH channel, which that
     * phone has turned down to 40% (`VIB_FEEDBACK_MAGNITUDE=2` of 5,
     * `mTouchMagnitude=4000` of 10000). A light pattern at 40% under a moving
     * thumb is not a detent; it is nothing.
     *
     * So the effect is chosen here instead of delegated. `EFFECT_HEAVY_CLICK` is
     * the heaviest predefined effect the LRA in that phone actually supports —
     * probed against the alternatives, it runs 105ms where `CLICK` runs 64ms and
     * `TICK` 61ms, and `THUD` is unsupported outright. Below API 29, where
     * predefined effects do not exist, a short full-amplitude one-shot stands in.
     *
     * ## Why it asks whether it is allowed to, instead of assuming
     *
     * The obvious assumption — that tagging the vibration `USAGE_TOUCH` hands the
     * user's touch-feedback switch back to the platform, the way
     * `performHapticFeedback` does — was TESTED ON THE DEVICE AND IS FALSE. With
     * `Settings.System.haptic_feedback_enabled` set to 0, a `USAGE_TOUCH`
     * `Vibrator.vibrate` on the test phone was still recorded `status: finished`
     * and still buzzed. That switch is enforced by `View.performHapticFeedback`
     * on the way IN; a call that starts at the vibrator has already passed the
     * place where it would have been stopped.
     *
     * So [BtDetent] reads the switch itself, on every crossing. An app that
     * vibrates after being told not to is a bug, and reaching for a louder API
     * would be a very easy way to write that bug by accident.
     *
     * Fired once per crossing, never repeated while held.
     */
    fun detent() {
        if (detentVibrator == null) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            detentVibrator.play()
        }
    }

    /**
     * The crosshair crossed one point of a chart series.
     *
     * **Owner order 2026-08-10: "every point you at gives another haptic feedback
     * so it feels cool like on the samsung watches with the fake bezel spin".**
     * A rotating bezel is a row of detents, and that is exactly what a scrub over
     * a discrete series is — the readout snaps to real points (never an
     * interpolation), so every step the finger takes lands on a *thing*, and the
     * motor is how you feel the row of them going past under your thumb.
     *
     * It is the sixth meaning and it earns its place by the same test [detent]
     * passed: it answers "how fast am I moving through the data", which the eye
     * cannot answer while the eye is busy reading the value.
     *
     * ## Why a different effect from [detent], and why it is not a buzz
     *
     * `EFFECT_TICK` where the notch takes `EFFECT_HEAVY_CLICK`: a detent is one
     * decision and wants weight, a scrub is dozens of crossings a second and wants
     * to stay out of the way — a heavy click repeated at scrub rate is a rattle,
     * not a bezel. The pre-API-29 fallback is correspondingly shorter than
     * [detent]'s.
     *
     * The other half of "not a buzz" is not here: it is
     * [at.bettertrack.app.ui.charts.nextScrubTick], the throttle that decides
     * WHICH crossings ring. A dense range puts several hundred points under a
     * 400px canvas, and firing the motor on every one of them would be a
     * continuous vibrato. Both halves are needed; neither is sufficient.
     *
     * Reuses the [detent] plumbing wholesale, which is the point: that class
     * already carries the measured fact that a `Vibrator.vibrate` bypasses the
     * user's touch-feedback switch, and re-solving that here would be how the app
     * ends up with one haptic that respects the setting and one that does not.
     */
    fun scrubTick() {
        if (scrubVibrator == null) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        } else {
            scrubVibrator.play()
        }
    }
}

/**
 * The sheet notch's detent, played straight at the motor. See [BtHaptics.detent]
 * for why it exists and why [enabled] is checked here rather than trusted to the
 * platform.
 */
class BtDetent internal constructor(
    private val vibrator: Vibrator,
    private val settings: ContentResolver,
    /**
     * How heavy this instance's crossing feels. Both weights go through the same
     * [enabled] gate, which is the reason this is a constructor parameter rather
     * than a second class.
     *
     * It is an enum rather than the platform's `EFFECT_*` int so that every
     * API-29 constant stays *inside* [play]'s version guard — minSdk is 28, and a
     * default argument naming `VibrationEffect.EFFECT_TICK` would put one of them
     * on a construction path that runs on 28.
     */
    private val weight: Weight = Weight.NOTCH,
) {

    /** See [weight]. */
    enum class Weight { NOTCH, TICK }

    /**
     * The user's touch-feedback switch — the AOSP one every OEM toggle writes.
     *
     * Marked deprecated since API 33 with nothing offered in its place, and the
     * framework has not stopped using it: `View.performHapticFeedback` still gates
     * on exactly this key. Reading the deprecated constant is how the app stays
     * silent in the same cases the platform would have silenced it.
     */
    @Suppress("DEPRECATION")
    private fun enabled(): Boolean =
        Settings.System.getInt(settings, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    fun play() {
        if (!enabled()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                when (weight) {
                    Weight.NOTCH -> VibrationEffect.EFFECT_HEAVY_CLICK
                    Weight.TICK -> VibrationEffect.EFFECT_TICK
                },
            )
        } else {
            VibrationEffect.createOneShot(
                when (weight) {
                    Weight.NOTCH -> BT_DETENT_FALLBACK_MS
                    Weight.TICK -> BT_SCRUB_FALLBACK_MS
                },
                VibrationEffect.DEFAULT_AMPLITUDE,
            )
        }
        // USAGE_TOUCH is still the right tag even though it does not gate: it is
        // what puts the vibration on the touch-intensity channel, so a user who
        // has turned the strength DOWN rather than off still gets it down.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
            )
        } else {
            // The pre-33 spelling of the same thing: sonification content on the
            // sonification usage is what the platform maps to USAGE_TOUCH.
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }
}

/** How long the pre-API-29 stand-in buzzes for. Long enough to register, short
 *  enough to still read as one event rather than an alert. */
private const val BT_DETENT_FALLBACK_MS = 35L

/** The scrub tick's pre-API-29 stand-in: half the notch's, because it repeats. */
private const val BT_SCRUB_FALLBACK_MS = 16L

/** The app's haptics, scoped to the current composition. */
@Composable
fun rememberBtHaptics(): BtHaptics {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    return remember(haptics, context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        val motor = vibrator?.takeIf { it.hasVibrator() }
        BtHaptics(
            haptics = haptics,
            detentVibrator = motor?.let { BtDetent(it, context.contentResolver) },
            scrubVibrator = motor?.let {
                BtDetent(it, context.contentResolver, BtDetent.Weight.TICK)
            },
        )
    }
}
