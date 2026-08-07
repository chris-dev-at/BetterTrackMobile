package at.bettertrack.app.ui.portfolio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot "open the portfolio switcher" request (owner directive, 2026-08-07).
 *
 * Tapping the **Portfolio** item in the bottom bar while the Portfolio tab is
 * already showing opens the switcher sheet — the same sheet the header pill
 * opens. The sheet is owned by `PortfolioOverviewViewModel`, which is scoped to
 * the Portfolio nav entry and therefore does not exist from the shell's point of
 * view, so the shell raises this flag and [PortfolioOverviewScreen] honours it on
 * the next composition.
 *
 * Exactly the idiom [at.bettertrack.app.ui.workboard.WorkboardEntry] already uses
 * to land a deep link on the Workbench's Alerts segment, and for the same reason:
 * the alternative is parameterising the tab route, which would change the tab's
 * identity and break the bottom bar's save/restore.
 */
object PortfolioTabEntry {
    private val _pendingSwitcher = MutableStateFlow(false)

    /** True while a switcher request is waiting to be honoured. */
    val pendingSwitcher: StateFlow<Boolean> = _pendingSwitcher

    fun requestSwitcher() {
        _pendingSwitcher.value = true
    }

    fun consume() {
        _pendingSwitcher.value = false
    }
}
