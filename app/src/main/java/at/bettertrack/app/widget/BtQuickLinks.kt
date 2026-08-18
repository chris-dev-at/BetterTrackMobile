package at.bettertrack.app.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import at.bettertrack.app.R
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.PortfolioEntity
import java.util.Locale

/**
 * The Quick Links widget's catalog and per-instance configuration — pure
 * Kotlin, no Android UI, so every rule below is a JVM unit test.
 *
 * ## What this replaces
 *
 * The Quick-actions widget was three TEXT tiles ("Neue Transaktion", "Cash-
 * Buchung", "Märkte"). The owner's verdict was that a launcher shortcut should
 * look like a launcher shortcut: *icon buttons that look like app icons, no
 * labels, and let me choose which ones*. The round-3 study answers that with a
 * grid of rounded-square tiles carrying one gold line glyph each.
 *
 * ## Why a catalog rather than a hardcoded triple
 *
 * The whole point of the redesign is that the SET is the user's choice. So the
 * catalog is the single list of "destinations a tile may point at", each with
 * exactly one pictogram, one accessibility name, and one existing deep-link
 * target. Adding a destination is one entry here plus one branch in
 * [btWidgetDeepLink] — and it cannot be added at all unless the app really has
 * the screen, which is the property that keeps a launcher full of icons from
 * accumulating dead taps.
 */

/**
 * One destination a Quick-Links tile can open.
 *
 * [key] is the STABLE config token. It is deliberately not `name`: the enum
 * may be reordered or renamed, and a per-instance config written by an older
 * build must keep meaning the same thing. Unknown keys decode to null and are
 * dropped, so a config from a newer build degrades to the tiles this build
 * understands instead of crashing the launcher.
 */
enum class BtQuickLink(
    val key: String,
    val target: String,
    @param:DrawableRes val icon: Int,
    /** The caption AND the content description — see [btQuickLinkDescription]. */
    @param:StringRes val label: Int,
    /** What a per-tile target picker may narrow this destination to. */
    val targeting: BtQuickLinkTargeting = BtQuickLinkTargeting.NONE,
) {
    /** The all-portfolios landing view; the broadest portfolio destination. */
    OVERVIEW("overview", BT_WIDGET_TARGET_OVERVIEW, R.drawable.ic_bt_widget_overview, R.string.bt_ql_overview),

    /**
     * ONE named portfolio, drawn as its monogram instead of a pictogram. The
     * first catalog entry that carried data ([BtQuickLinkAction.portfolioId]),
     * because "which portfolio" is exactly what makes the tile personal — and
     * the pattern the 2026-08-18 targeting round generalised to the rest.
     */
    PORTFOLIO(
        "portfolio",
        BT_WIDGET_TARGET_PORTFOLIO,
        0,
        R.string.bt_ql_portfolio,
        BtQuickLinkTargeting.PORTFOLIO,
    ),

    /** Discovery and market data. */
    MARKETS("markets", BT_WIDGET_TARGET_SEARCH, R.drawable.ic_bt_widget_markets, R.string.bt_ql_markets),

    /** BetterTrack chat (the conversation LIST, never someone's thread). */
    CHAT("chat", BT_WIDGET_TARGET_CHAT, R.drawable.ic_bt_widget_chat, R.string.bt_ql_chat),

    /** The community feed. */
    SOCIAL("social", BT_WIDGET_TARGET_SOCIAL, R.drawable.ic_bt_widget_social, R.string.bt_ql_social),

    /** Saved symbols and monitored assets. */
    WATCHLIST("watchlist", BT_WIDGET_TARGET_WATCHLIST, R.drawable.ic_bt_widget_watchlist, R.string.bt_ql_watchlist),

    /** Cash sources and balances — optionally ONE named wallet. */
    CASH(
        "cash",
        BT_WIDGET_TARGET_CASH,
        R.drawable.ic_bt_widget_wallet,
        R.string.bt_ql_cash,
        BtQuickLinkTargeting.CASH_SOURCE,
    ),

    /** Create a portfolio transaction — buy, sell, distribution — in ONE portfolio. */
    ADD_TRANSACTION(
        "tx_add",
        BT_WIDGET_TARGET_ADD_TRANSACTION,
        R.drawable.ic_bt_widget_transaction_add,
        R.string.bt_ql_add_transaction,
        BtQuickLinkTargeting.PORTFOLIO,
    ),

    /** Create a cash-ledger entry against ONE named wallet. */
    ADD_CASH(
        "cash_add",
        BT_WIDGET_TARGET_ADD_CASH,
        R.drawable.ic_bt_widget_cash_add,
        R.string.bt_ql_add_cash,
        BtQuickLinkTargeting.CASH_SOURCE,
    ),
    ;

    /**
     * True when the tile paints a monogram rather than [icon] — i.e. when the
     * destination IS the named thing, so the name is the most useful mark.
     *
     * Deliberately NOT true for the two `ADD_*` entries even though they now
     * carry a target. On those the verb is the point: a tile that books a
     * transaction into "Langfristig" has to read as *book a transaction* first,
     * and a bare "L" would say only *Langfristig* — the same tile a
     * [PORTFOLIO] link already draws, which is two different destinations
     * wearing one mark. They keep their action glyph and state the target in
     * the caption and the content description instead.
     */
    val isMonogram: Boolean get() = this == PORTFOLIO

    companion object {
        fun fromKey(key: String?): BtQuickLink? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What a per-tile picker may narrow a destination to (owner 2026-08-18).
 *
 * *"what if i want to have 3 buttons that each bring me to the overview of
 * another cash source. […] and for all the other stuff too like open portfolio
 * or add transaction. like where to?"*
 *
 * Before this, a catalog entry was a bare destination and the only tile that
 * could be aimed was [BtQuickLink.PORTFOLIO]. Three Cash tiles were therefore
 * three identical tiles. A destination now declares which target it accepts,
 * which is what lets the editor show the right picker — and lets the ones that
 * genuinely have no target ([BtQuickLinkTargeting.NONE]: the feed, chat, the
 * watchlist, global search, the all-portfolios overview) show none at all
 * rather than an empty control.
 */
enum class BtQuickLinkTargeting {
    /** A single global destination; nothing to aim. */
    NONE,

    /** One portfolio. Required for [BtQuickLink.PORTFOLIO], optional elsewhere. */
    PORTFOLIO,

    /** One cash source, which also fixes the portfolio it belongs to. */
    CASH_SOURCE,
}

/**
 * One configured tile: a destination, plus the portfolio identity the
 * [BtQuickLink.PORTFOLIO] entry needs.
 *
 * The portfolio's NAME is snapshotted alongside its id for the same reason
 * [BtWidgetAssetConfig] snapshots a symbol: the widget must be able to say what
 * it opens even after the row it was configured from has gone, and a monogram
 * derived from an id would be a hex digit.
 */
data class BtQuickLinkAction(
    val link: BtQuickLink,
    val portfolioId: String = "",
    val portfolioName: String = "",
    /** A manual single-character override for the derived monogram; "" = derive. */
    val monogram: String = "",
    /** The wallet a [BtQuickLinkTargeting.CASH_SOURCE] tile aims at; "" = none. */
    val sourceId: String = "",
    /** Snapshotted for the same reason [portfolioName] is — see the KDoc above. */
    val sourceName: String = "",
) {
    /**
     * The target this tile is actually aimed at, or null when it is the
     * destination's broad form ("Cash", not "Cash · Sparkonto").
     *
     * A cash target names the SOURCE; a portfolio target names the portfolio.
     * Both are the snapshotted name, never the id — an id has nothing to show
     * a user and nothing to derive a monogram from.
     */
    val targetName: String?
        get() = when (link.targeting) {
            BtQuickLinkTargeting.NONE -> null
            BtQuickLinkTargeting.PORTFOLIO -> portfolioName.takeIf { it.isNotBlank() }
            BtQuickLinkTargeting.CASH_SOURCE -> sourceName.takeIf { it.isNotBlank() }
        }
}

/**
 * What one Quick-Links instance shows: an ORDERED tile list and the caption
 * toggle.
 *
 * Never null — an instance with no stored config renders [BT_QUICK_LINKS_DEFAULT]
 * rather than a "nothing selected" card, which is what lets the widget be
 * `configuration_optional` and useful the moment it is dropped.
 */
data class BtQuickLinksConfig(
    val actions: List<BtQuickLinkAction>,
    /** The study's optional tiny captions. OFF by default, per the study. */
    val captions: Boolean = false,
)

/**
 * The most tiles any instance stores, and the 4x2 grid's capacity.
 *
 * Eight is the study's densest rendition. Storing more would let a user build a
 * config that no size can render, which is the "configuration must never allow
 * an illegible result" rule in its simplest form.
 */
const val BT_QUICK_LINKS_MAX: Int = 8

/**
 * The set a freshly pinned instance shows.
 *
 * Ordered so that every rendition's PREFIX is a sensible widget on its own —
 * the grid always takes the first N that fit, so the order is the priority. The
 * front door leads, then the two things the owner reaches for constantly
 * (markets, booking a trade), then cash, then the browsing destinations.
 *
 * The named-portfolio tile is deliberately absent: it needs a portfolio the
 * default cannot know, and a monogram tile with no portfolio behind it would be
 * the empty state the config is supposed to make impossible.
 */
val BT_QUICK_LINKS_DEFAULT: List<BtQuickLinkAction> = listOf(
    BtQuickLink.OVERVIEW,
    BtQuickLink.MARKETS,
    BtQuickLink.ADD_TRANSACTION,
    BtQuickLink.CASH,
    BtQuickLink.WATCHLIST,
    BtQuickLink.CHAT,
    BtQuickLink.SOCIAL,
    BtQuickLink.ADD_CASH,
).map { BtQuickLinkAction(it) }

// ── Codec ────────────────────────────────────────────────────────────────────
//
// One preference string, not one key per slot: the list is ordered and variable
// length, and a key-per-slot scheme would need its own compaction rules the
// first time a user removes the middle tile.
//
// The separators are the ASCII control characters that exist for exactly this
// (RS between records, US between fields). A portfolio NAME is user text and
// may contain commas, pipes, colons and newlines; it cannot contain these.

private const val REC = '\u001E'
private const val FLD = '\u001F'

val BT_WIDGET_PREF_LINKS: Preferences.Key<String> = stringPreferencesKey("bt_links")
val BT_WIDGET_PREF_LINKS_CAPTIONS: Preferences.Key<String> = stringPreferencesKey("bt_links_captions")

/**
 * Encode the ordered tile list. Pure, so the round trip is a unit test.
 *
 * The cash-source fields were APPENDED (positions 4 and 5) rather than slotted
 * in, which is what makes the format backward compatible in both directions: a
 * record written by the pre-targeting build has four fields and decodes with
 * empty source fields, and a record written here is read by an older build as
 * its first four fields with the tail ignored.
 */
fun btQuickLinksEncode(actions: List<BtQuickLinkAction>): String =
    actions.take(BT_QUICK_LINKS_MAX).joinToString(REC.toString()) { a ->
        listOf(a.link.key, a.portfolioId, a.portfolioName, a.monogram, a.sourceId, a.sourceName)
            .joinToString(FLD.toString())
    }

/**
 * Decode a stored tile list, dropping anything this build cannot honour:
 * unknown destination keys (a config from a newer build) and monogram tiles
 * with no portfolio behind them (a half-written record). Both would otherwise
 * render as a blank tile that opens nothing.
 */
fun btQuickLinksDecode(raw: String?): List<BtQuickLinkAction> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split(REC).mapNotNull { record ->
        val f = record.split(FLD)
        val link = BtQuickLink.fromKey(f.getOrNull(0)) ?: return@mapNotNull null
        val action = BtQuickLinkAction(
            link = link,
            portfolioId = f.getOrNull(1).orEmpty(),
            portfolioName = f.getOrNull(2).orEmpty(),
            monogram = f.getOrNull(3).orEmpty(),
            sourceId = f.getOrNull(4).orEmpty(),
            sourceName = f.getOrNull(5).orEmpty(),
        )
        // Only the monogram tile is dropped when its target is missing, and
        // only because it would render as a blank mark. Every other targeted
        // entry has an honest untargeted reading (Cash = the cash screen,
        // ADD_CASH = the sheet on its own default wallet), so a missing target
        // widens the tile rather than deleting it.
        action.takeUnless { link.isMonogram && it.portfolioId.isBlank() }
    }.take(BT_QUICK_LINKS_MAX)
}

/** Read an instance's config; an empty/absent list falls back to the defaults. */
fun btQuickLinksConfig(prefs: Preferences): BtQuickLinksConfig {
    val actions = btQuickLinksDecode(prefs[BT_WIDGET_PREF_LINKS])
    return BtQuickLinksConfig(
        actions = actions.ifEmpty { BT_QUICK_LINKS_DEFAULT },
        captions = prefs[BT_WIDGET_PREF_LINKS_CAPTIONS] == "1",
    )
}

fun btQuickLinksPutConfig(prefs: MutablePreferences, config: BtQuickLinksConfig) {
    prefs[BT_WIDGET_PREF_LINKS] = btQuickLinksEncode(config.actions)
    prefs[BT_WIDGET_PREF_LINKS_CAPTIONS] = if (config.captions) "1" else "0"
}

// ── Presentation rules (pure) ────────────────────────────────────────────────

/**
 * The monogram a portfolio tile paints: the manual override if the user set
 * one, else the name's first LETTER OR DIGIT.
 *
 * Skipping past punctuation matters more than it looks: a portfolio called
 * "★ Langfristig" would otherwise get a star tile that says nothing, and
 * "(alt) Depot" would get a bracket. Uppercased with ROOT rather than the
 * device locale, because Turkish's dotless-i would turn "Investment" into "İ"
 * on a Turkish phone and the tile is a brand mark, not prose.
 *
 * Falls back to "•" when the name carries no alphanumeric at all — a dot is the
 * house's own placeholder glyph, and it is never an empty tile.
 */
/**
 * The monogram this tile paints, or null when it paints its pictogram instead.
 *
 * ## Why a targeted Cash tile gives up the wallet glyph
 *
 * Owner 2026-08-18, on per-tile targets: *"Icons should reflect the target
 * where sensible (monogram/initial)."* His own example is three Cash tiles
 * aimed at three different wallets — and with captions off (the default) three
 * wallet glyphs are three identical tiles. The pictogram is the less useful
 * mark there: the user placing three of them already knows they are cash, and
 * what they cannot see is WHICH. So a Cash tile with a chosen wallet paints
 * that wallet's initial.
 *
 * The `ADD_*` entries deliberately keep their glyph even when aimed — see
 * [BtQuickLink.isMonogram]. An untargeted tile also keeps its glyph, because
 * there is nothing to take an initial from.
 */
fun btQuickLinkTileMonogram(action: BtQuickLinkAction): String? = when (action.link.targeting) {
    BtQuickLinkTargeting.NONE -> null
    BtQuickLinkTargeting.PORTFOLIO ->
        if (action.link.isMonogram) {
            action.portfolioName.takeIf { it.isNotBlank() }
                ?.let { btQuickLinkMonogram(it, action.monogram) }
        } else {
            null
        }

    BtQuickLinkTargeting.CASH_SOURCE ->
        if (action.link == BtQuickLink.CASH) {
            action.sourceName.takeIf { it.isNotBlank() }
                ?.let { btQuickLinkMonogram(it, action.monogram) }
        } else {
            null
        }
}

fun btQuickLinkMonogram(name: String, override: String = ""): String {
    override.trim().takeIf { it.isNotEmpty() }?.let { return it.take(1).uppercase(Locale.ROOT) }
    val ch = name.firstOrNull { it.isLetterOrDigit() } ?: return "•"
    return ch.toString().uppercase(Locale.ROOT)
}

/**
 * How many tiles fit, given the card's CONTENT box in dp and how many rows it
 * is drawing.
 *
 * Derived from a minimum PITCH rather than a size-class table because a
 * launcher hands out widths the four named renditions do not cover — a 3-column
 * placement is a real thing on this device and must get 4 or 5 icons, not the
 * 2x1's three. The pitch differs per row count for the same reason the study's
 * tiles do: with two rows of vertical room the tiles are bigger, so fewer of
 * them fit across.
 *
 * The clamps are the study's own capacities (6 across on one row, 4 on two), so
 * a very wide placement stops adding icons rather than turning into a keypad.
 */
fun btQuickLinksPerRow(contentWidthDp: Float, rows: Int): Int {
    val gap = btQuickLinkGap(rows)
    val pitch = if (rows > 1) BT_QUICK_LINK_PITCH_GRID else BT_QUICK_LINK_PITCH_STRIP
    val maxAcross = if (rows > 1) 4 else 6
    // `+ gap` because n tiles cost n*tile + (n-1)*gap, not n*(tile+gap) — the
    // last tile has no gap after it. Dropping that term loses a whole icon at
    // every size, which is how a 2x1 ends up showing two of the study's three.
    return ((contentWidthDp + gap) / pitch).toInt().coerceIn(1, maxAcross)
}

/** The gap between tiles, which the widget and the capacity rule must share. */
fun btQuickLinkGap(rows: Int): Float =
    if (rows > 1) BT_QUICK_LINK_GAP_GRID else BT_QUICK_LINK_GAP_STRIP

const val BT_QUICK_LINK_GAP_STRIP: Float = 5f
const val BT_QUICK_LINK_GAP_GRID: Float = 10f

/**
 * Minimum dp one tile claims across INCLUDING its gap, on a single-row card:
 * the study's 48dp launcher tile plus [BT_QUICK_LINK_GAP_STRIP]. 48dp is also
 * the platform's minimum tap target, so this is the floor twice over.
 */
const val BT_QUICK_LINK_PITCH_STRIP: Float = 48f + BT_QUICK_LINK_GAP_STRIP

/**
 * The same, on a two-row grid. The study's 4x2 tile is 56dp with a 15dp gutter;
 * 60+10 is that proportion on the measured cell dp of this launcher (4 columns
 * = 366dp), which is what puts four across a 4x2 and two across a 2x2.
 */
const val BT_QUICK_LINK_PITCH_GRID: Float = 60f + BT_QUICK_LINK_GAP_GRID

/**
 * Split the configured tiles into the rows a given card draws, taking only what
 * fits. The remainder is not rendered anywhere: a tile half off the card is
 * worse than a tile the user has to resize to see, and the config screen states
 * each size's capacity so the omission is never a surprise.
 */
fun btQuickLinksRows(
    actions: List<BtQuickLinkAction>,
    perRow: Int,
    rows: Int,
): List<List<BtQuickLinkAction>> {
    if (actions.isEmpty() || perRow <= 0 || rows <= 0) return emptyList()
    return actions
        .take((perRow * rows).coerceAtMost(BT_QUICK_LINKS_MAX))
        .chunked(perRow)
}

/**
 * A tile's spoken name: "Portfolio Langfristig öffnen" for the monogram,
 * the plain destination name otherwise. Never rendered on the default widget —
 * these tiles are icon-only by design, and the description is the ONLY place
 * the destination is stated for a screen reader.
 */
fun btQuickLinkDescription(action: BtQuickLinkAction, label: String): String =
    action.targetName?.let { "$label $it" } ?: label

/**
 * A tile's target as an EDITOR row reads it: the aim, qualified by whatever it
 * takes to tell two aims apart.
 *
 * A wallet needs its portfolio appended and the widget caption does not. Four
 * of this account's five cash sources are called "Main" — one per portfolio —
 * so a list that showed only the source name printed "Cash · Main" three times
 * and made the picker unusable. The caption escapes this because only ONE tile
 * is on screen at a time; a list is exactly where the ambiguity bites.
 */
fun btQuickLinkTargetLabel(action: BtQuickLinkAction): String? = when {
    action.link.targeting == BtQuickLinkTargeting.CASH_SOURCE && action.sourceName.isNotBlank() ->
        action.portfolioName.takeIf { it.isNotBlank() }
            ?.let { "${action.sourceName} · $it" }
            ?: action.sourceName

    else -> action.targetName
}

/**
 * The tiny caption an opted-in tile carries: the TARGET when the tile has one,
 * else the destination.
 *
 * The target wins because it is the part that differs. Three Cash tiles all
 * captioned "Cash" is the state the owner reported; captioning them
 * "Girokonto" / "Sparkonto" / "Bank 2" is the answer he asked for, and the
 * pictogram (or [btQuickLinkTileMonogram]) still carries the kind.
 */
fun btQuickLinkCaption(action: BtQuickLinkAction, label: String): String =
    action.targetName ?: label

/** What the picker offers for the monogram tile: the ACTIVE portfolios. */
fun btQuickLinkPortfolioActions(portfolios: List<PortfolioEntity>): List<BtQuickLinkAction> =
    btWidgetPortfolioChoices(portfolios).map {
        BtQuickLinkAction(BtQuickLink.PORTFOLIO, portfolioId = it.id, portfolioName = it.name)
    }

/**
 * The target choices offered for one catalog entry, the untargeted "broad"
 * reading first.
 *
 * The broad reading leads because it is the honest default and, for the
 * entries where a target is optional, the one a user who does not care should
 * land on. [BtQuickLink.PORTFOLIO] is the exception and does NOT get it: a
 * monogram tile with no portfolio is the blank mark [btQuickLinksDecode] drops,
 * so offering it here would be offering a tile that deletes itself on save.
 */
fun btQuickLinkTargetChoices(
    link: BtQuickLink,
    portfolios: List<PortfolioEntity>,
    sources: List<CashSourceEntity>,
): List<BtQuickLinkAction> = when (link.targeting) {
    BtQuickLinkTargeting.NONE -> emptyList()

    BtQuickLinkTargeting.PORTFOLIO -> {
        val aimed = btWidgetPortfolioChoices(portfolios).map {
            BtQuickLinkAction(link, portfolioId = it.id, portfolioName = it.name)
        }
        if (link.isMonogram) aimed else listOf(BtQuickLinkAction(link)) + aimed
    }

    BtQuickLinkTargeting.CASH_SOURCE -> {
        val names = portfolios.associate { it.id to it.name }
        listOf(BtQuickLinkAction(link)) +
            sources.filter { it.archivedAt == null }.map {
                BtQuickLinkAction(
                    link,
                    // The wallet fixes its portfolio, so both travel together —
                    // a cash deep link that named a source but not its portfolio
                    // would make the app resolve the source against whichever
                    // portfolio happened to be selected.
                    portfolioId = it.portfolioId,
                    portfolioName = names[it.portfolioId].orEmpty(),
                    sourceId = it.id,
                    sourceName = it.name,
                )
            }
    }
}

/**
 * True when [candidate] is the target [current] already points at — what the
 * target picker marks as selected.
 *
 * Compared on the IDs, never the snapshotted names: two wallets in two
 * portfolios are routinely both called "Bank", and a name comparison would tick
 * the wrong row.
 */
fun btQuickLinkSameTarget(current: BtQuickLinkAction, candidate: BtQuickLinkAction): Boolean =
    current.link == candidate.link &&
        current.portfolioId == candidate.portfolioId &&
        current.sourceId == candidate.sourceId
