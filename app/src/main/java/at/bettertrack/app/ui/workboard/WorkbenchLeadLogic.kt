package at.bettertrack.app.ui.workboard

/**
 * What the Workbench's "Needs you" block shows, as a pure function (R-arc R2).
 *
 * Extracted for the same reason `nextFabVisible` was: this is a *rule*, not a
 * layout — alerts outrank ideas, the block is capped, and the remainder must be
 * counted honestly — and a rule that can only be checked by looking at a phone
 * is a rule that quietly rots. There is no device in this arc, so the ordering
 * and the cap arithmetic are asserted here instead.
 *
 * Generic in both element types on purpose: it does arithmetic over two lists
 * and nothing else, so it neither needs nor should know what a `PriceAlert` is.
 */
internal data class NeedsYouPlan<A, I>(
    /** The alerts to render, in order. */
    val alerts: List<A>,
    /** The ideas to render, after the alerts. */
    val ideas: List<I>,
    /** How many actionable items did NOT fit. Zero means the block is complete. */
    val hidden: Int,
) {
    /** True when there is nothing waiting — the block must not render at all. */
    val isEmpty: Boolean get() = alerts.isEmpty() && ideas.isEmpty()
}

/**
 * Builds the plan.
 *
 * **Alerts always win the available slots.** A fired price alert is about money
 * moving right now; an idea saved without its thesis has been waiting since
 * whenever it was saved. If the cap forces a choice, it is not a close call.
 *
 * [hidden] counts everything dropped from BOTH lists, so the block can say how
 * much it is not showing rather than implying it is showing all of it.
 */
internal fun <A, I> needsYouPlan(
    triggeredAlerts: List<A>,
    unfinishedIdeas: List<I>,
    max: Int,
): NeedsYouPlan<A, I> {
    require(max >= 0) { "max must not be negative, was $max" }
    val alerts = triggeredAlerts.take(max)
    val ideas = unfinishedIdeas.take(max - alerts.size)
    val total = triggeredAlerts.size + unfinishedIdeas.size
    return NeedsYouPlan(
        alerts = alerts,
        ideas = ideas,
        hidden = total - alerts.size - ideas.size,
    )
}
