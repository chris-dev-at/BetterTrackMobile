package at.bettertrack.app.ui.format

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Discreet mode — hide every ABSOLUTE money amount while leaving relative
 * figures (percentages, weights, quantities, gain/loss colour) live.
 *
 * The server only persists the flag (`discreetMode` on `/settings/account`);
 * the rendering rule is entirely the client's to implement. So this is
 * deliberately a process-wide display mode read from inside
 * [btFormatMoneyCore] / [btFormatUnitPriceCore] rather than a parameter threaded
 * through every screen. That choice is what makes coverage TOTAL: a new screen
 * that formats money the normal way is masked automatically, and there is no
 * way to add a money label that silently forgets to honour the setting.
 *
 * It is backed by Compose snapshot state, so although the formatters are plain
 * functions, a read that happens during composition still registers a
 * recomposition dependency — flipping the toggle repaints every visible amount
 * immediately, with no manual invalidation.
 */
object BtDiscreetMode {

    private var enabledState by mutableStateOf(false)
    private var revealingState by mutableStateOf(false)

    /** The persisted user setting. */
    val enabled: Boolean get() = enabledState

    /** True when amounts should actually render masked right now. */
    val masking: Boolean get() = enabledState && !revealingState

    fun setEnabled(value: Boolean) {
        enabledState = value
        if (!value) revealingState = false
    }

    /**
     * Temporarily reveal real values (press-and-hold on the net-worth hero).
     * Scoped to the gesture: releasing re-masks, and turning the setting off
     * clears it so a stale reveal can never outlive the mode.
     */
    fun setRevealing(value: Boolean) {
        revealingState = value
    }

    /** Test hook — restores the default state. */
    fun resetForTest() {
        enabledState = false
        revealingState = false
    }
}

/**
 * The masked stand-in for an amount: four bullets plus the currency symbol,
 * keeping the app's symbol-LAST convention so a masked row still lines up with
 * the unmasked ones around it and the layout doesn't jump when toggling.
 */
internal fun btMaskedMoney(currencyCode: String, locale: Locale): String =
    "•••• ${btMoneySymbol(currencyCode, locale)}"

/** Mask for a bare number with no currency (chart axes). */
internal const val BT_MASKED_PLAIN: String = "••••"
