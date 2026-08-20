package at.bettertrack.app.ui.components

import androidx.compose.runtime.Composable

/**
 * BetterTrack's haptic vocabulary (R-arc R3 §4) — the **contract**; the
 * platform half lives next to it as an `actual`.
 *
 * ## The rule this type exists to enforce
 *
 * Haptics are **confirmation, never decoration**. A tick that fires on every
 * tappable thing teaches the user nothing and turns into noise they disable in
 * system settings — taking the two haptics that DID mean something with it. So
 * this app has exactly six haptic meanings and no seventh, and each one answers
 * a question the user is actually asking:
 *
 * | Meaning | When | Answers |
 * |---|---|---|
 * | [confirm] | a primary action committed | "did that go through?" |
 * | [toggle]  | a two-state control settled | "which way is it now?" |
 * | [reject]  | the app refused the input | "why didn't that work?" |
 * | [keyTap]  | one character of input landed | "did that key register?" |
 * | [detent]  | a drag crossed a decision boundary | "have I pulled far enough?" |
 * | [scrubTick] | the crosshair crossed one chart point | "how fast am I moving?" |
 *
 * Everything else — navigating, opening a sheet, scrolling, pressing a
 * *secondary* button — stays silent. [BtSecondaryButton] deliberately has no
 * haptic while [BtPrimaryButton] does: that difference is what makes the
 * primary one mean "committed" rather than merely "pressed", and it puts the
 * primary/secondary distinction into the touch channel as well as the colour.
 *
 * ## Why it is an interface as of the web port (Phase W1)
 *
 * It used to be a class, and everything in it was Android: `Vibrator`,
 * `VibrationEffect`, `Settings.System.HAPTIC_FEEDBACK_ENABLED`, a measured
 * argument about one Samsung's LRA. None of that survives a move to
 * `commonMain`, and none of it should — the *vocabulary* is the shared thing,
 * the motor is not.
 *
 * So the six meanings and the rule about them live here, the whole Android
 * implementation moved verbatim into `androidMain` (down to its device
 * measurements and the reason it reads the user's switch itself), and the
 * platforms with no motor get an honest no-op. The change is invisible to the
 * app: `BtHaptics` was named as a type in exactly one place
 * ([at.bettertrack.app.ui.charts.ChartScrub]) and constructed in none, so all
 * 12 call sites still say `rememberBtHaptics()` and nothing about the Android
 * feel moved.
 */
interface BtHaptics {

    /**
     * A primary action committed — a save, a create, a submit, a destructive
     * confirmation. The single tick that says the app took the input.
     */
    fun confirm()

    /**
     * A two-state control settled. On and off feel different on purpose: a
     * toggle is the one control whose *result* the user cannot always see (the
     * switch may be under their thumb), so the direction is worth carrying in
     * the haptic itself.
     */
    fun toggle(on: Boolean)

    /**
     * The app refused the input — a wrong PIN, a failed unlock, an optimistic
     * change the server rolled back. Firmer than [confirm] because it is the
     * one case where the user must look at the screen to find out why.
     */
    fun reject()

    /** One character of input landed. The PIN keypad's per-digit tick. */
    fun keyTap()

    /**
     * A drag crossed a threshold that changes what letting go will do — the
     * sheet notch, where pulling past
     * [at.bettertrack.app.ui.shell.SHEET_NOTCH_END] turns a release from "back
     * one page" into "close the entire stack". Fired once per crossing, never
     * repeated while held. See the Android actual for why that one reaches past
     * Compose to the motor.
     */
    fun detent()

    /**
     * The crosshair crossed one point of a chart series — a row of detents, the
     * way a watch bezel is. Which crossings ring is decided by
     * [at.bettertrack.app.ui.charts.nextScrubTick], not here.
     */
    fun scrubTick()
}

/** The app's haptics, scoped to the current composition. */
@Composable
expect fun rememberBtHaptics(): BtHaptics

/**
 * The platform-has-no-motor implementation. Silence is the correct behaviour,
 * not a stub: a browser tab has no haptic API at all (`navigator.vibrate` is a
 * notification-grade buzz on a subset of mobile browsers, not UI feedback), and
 * faking one would be worse than having none.
 */
internal object BtNoHaptics : BtHaptics {
    override fun confirm() = Unit
    override fun toggle(on: Boolean) = Unit
    override fun reject() = Unit
    override fun keyTap() = Unit
    override fun detent() = Unit
    override fun scrubTick() = Unit
}
