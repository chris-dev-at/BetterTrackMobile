package at.bettertrack.app.ui.prices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bettertrack.app.data.storage.ManualPricePoint
import at.bettertrack.app.data.storage.ManualPriceStore
import at.bettertrack.app.data.storage.ManualPriceValidation
import at.bettertrack.app.data.storage.validateManualPrice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Hosts the manual price book for one asset (S3/S4 plan §5 W6, item 1).
 *
 * Small on purpose — the rules live in [validateManualPrice] and
 * [ManualPriceStore], both pure/JVM-testable. What is left here is the
 * write-then-recompute ordering, which is the one thing that has to be right:
 *
 * ```
 * store.record(price)   →   recompute()   →   reload()
 * ```
 *
 * The recompute is not fire-and-forget. Room's holdings rows are what the screen
 * renders, and they are only rewritten by the projection; a price saved without
 * one would sit in `price_cache` while the hero kept showing the old value, and
 * the user would reasonably conclude that entering prices does nothing.
 */
class ManualPriceViewModel(
    private val assetId: String,
    private val store: ManualPriceStore,
    private val recompute: suspend () -> Unit,
) : ViewModel() {

    private val _points = MutableStateFlow<List<ManualPricePoint>>(emptyList())
    val points: StateFlow<List<ManualPricePoint>> = _points.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    init {
        reload()
    }

    fun openSheet() {
        reload()
        _sheetOpen.value = true
    }

    fun closeSheet() {
        _sheetOpen.value = false
    }

    fun reload() {
        viewModelScope.launch { _points.value = store.pointsFor(assetId) }
    }

    /**
     * Validates, stores and recomputes.
     *
     * Re-validating here rather than trusting the caller is not belt-and-braces:
     * the sheet's enabled-state and this call are separated by a click, and a
     * price is money. An invalid draft is dropped silently because the form is
     * already showing the reason — reporting it twice would be the noise.
     */
    fun record(date: LocalDate, rawValue: String, currency: String, valuationCurrency: String) {
        val validation = validateManualPrice(
            assetId = assetId,
            rawValue = rawValue,
            date = date,
            today = LocalDate.now(),
            currency = currency,
            valuationCurrency = valuationCurrency,
        )
        val price = (validation as? ManualPriceValidation.Valid)?.price ?: return
        viewModelScope.launch {
            _busy.value = true
            store.record(price)
            recompute()
            _points.value = store.pointsFor(assetId)
            _busy.value = false
            _sheetOpen.value = false
        }
    }

    fun delete(dateIso: String) {
        viewModelScope.launch {
            _busy.value = true
            store.delete(assetId, dateIso)
            recompute()
            _points.value = store.pointsFor(assetId)
            _busy.value = false
        }
    }
}
