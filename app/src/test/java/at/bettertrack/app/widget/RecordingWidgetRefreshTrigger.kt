package at.bettertrack.app.widget

/**
 * A [WidgetRefreshTrigger] that only records — the fake every data-layer test
 * hands its repository to assert "this write tells the widgets, that one does
 * not". [onCall] runs inside each call, so a test can snapshot other fakes at
 * the moment the trigger fired (ordering: refetch BEFORE refresh).
 */
class RecordingWidgetRefreshTrigger(
    private val onCall: (Call) -> Unit = {},
) : WidgetRefreshTrigger {

    data class Call(val cash: Boolean, val reason: BtWidgetRefreshReason, val portfolioId: String?)

    val calls = mutableListOf<Call>()

    val cash: List<Call> get() = calls.filter { it.cash }
    val investments: List<Call> get() = calls.filterNot { it.cash }

    override fun cashChanged(reason: BtWidgetRefreshReason, portfolioId: String?) {
        val c = Call(cash = true, reason = reason, portfolioId = portfolioId)
        calls += c
        onCall(c)
    }

    override fun investmentsChanged(reason: BtWidgetRefreshReason, portfolioId: String?) {
        val c = Call(cash = false, reason = reason, portfolioId = portfolioId)
        calls += c
        onCall(c)
    }

    fun clear() = calls.clear()
}
