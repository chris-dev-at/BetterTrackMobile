package at.bettertrack.app.ui.insights

/**
 * Which insight cards the page shows, and in what order.
 *
 * Kept apart from [BtInsightConfig] on purpose: *visibility and order* are facts
 * about the page, while *form, period and scope* are facts about a card. The
 * separation is what lets `Standardansicht wiederherstellen` restore the page
 * without touching a single saved card setting — the study is explicit that the
 * restore "changes only visibility/order, never money data or saved card
 * configuration", and [InsightsPageTest] pins that.
 *
 * Hiding is not deleting. A hidden card keeps its configuration and returns
 * instantly from *Ausgeblendet*, which is why [hidden] is derived rather than
 * stored: everything not in [visible] is hidden, and the catalog is the
 * authority on what exists.
 */
data class BtInsightsPage(
    /** Visible cards, top to bottom. Never contains duplicates. */
    val visible: List<BtInsight>,
) {
    /** Everything the catalog knows that is not currently on the page, in rank order. */
    val hidden: List<BtInsight>
        get() = BT_INSIGHTS_RANKED.filterNot { it in visible }

    val isDefault: Boolean get() = visible == BT_INSIGHTS_DEFAULT

    companion object {
        /** The five default insights, in rank order. What a new user sees. */
        val DEFAULT: BtInsightsPage = BtInsightsPage(BT_INSIGHTS_DEFAULT)
    }
}

/** Show a hidden card. It joins the end of the page, keeping its saved settings. */
fun insightsPageShow(page: BtInsightsPage, insight: BtInsight): BtInsightsPage =
    if (insight in page.visible) page else BtInsightsPage(page.visible + insight)

/** Hide a visible card. Its configuration survives untouched. */
fun insightsPageHide(page: BtInsightsPage, insight: BtInsight): BtInsightsPage =
    BtInsightsPage(page.visible.filterNot { it == insight })

/**
 * Move [insight] by [delta] places (−1 = up). Out-of-range moves are clamped
 * rather than rejected, so the TalkBack "move up" action on the first row is a
 * no-op instead of an error the user cannot see.
 */
fun insightsPageMove(page: BtInsightsPage, insight: BtInsight, delta: Int): BtInsightsPage {
    val from = page.visible.indexOf(insight)
    if (from < 0) return page
    val to = (from + delta).coerceIn(0, page.visible.lastIndex)
    if (to == from) return page
    val next = page.visible.toMutableList()
    next.removeAt(from)
    next.add(to, insight)
    return BtInsightsPage(next)
}

/** Reorder by absolute index — the drag handle's commit. */
fun insightsPageReorder(page: BtInsightsPage, from: Int, to: Int): BtInsightsPage {
    if (from !in page.visible.indices) return page
    val target = to.coerceIn(0, page.visible.lastIndex)
    if (target == from) return page
    val next = page.visible.toMutableList()
    next.add(target, next.removeAt(from))
    return BtInsightsPage(next)
}

private const val PAGE_SEP = ","

/** Encode the page order. `null` when it is the default, so nothing is stored. */
fun insightsPageEncode(page: BtInsightsPage): String? =
    if (page.isDefault) null else page.visible.joinToString(PAGE_SEP) { it.name }

/**
 * Decode a stored page.
 *
 * Unknown names are dropped rather than fatal — that is what happens when a user
 * downgrades after an insight was added, and losing one row beats losing the
 * page. An empty or unreadable value falls back to the default five.
 */
fun insightsPageDecode(raw: String?): BtInsightsPage {
    if (raw.isNullOrBlank()) return BtInsightsPage.DEFAULT
    val visible = raw.split(PAGE_SEP)
        .mapNotNull { name -> BtInsight.entries.firstOrNull { it.name == name.trim() } }
        .distinct()
    return if (visible.isEmpty()) BtInsightsPage.DEFAULT else BtInsightsPage(visible)
}
