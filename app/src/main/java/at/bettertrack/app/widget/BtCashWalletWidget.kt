package at.bettertrack.app.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import at.bettertrack.app.R
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.ui.portfolio.displayNote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cash Wallet (the round-3 Codex study, family 02): one cash source, its
 * balance, and the postings that change it — from the launcher.
 *
 * ## The card's identity
 *
 * The configured SOURCE is what identifies this widget, which is why it needs
 * no type header: "Alltagskasse · 428,60 €" says everything a header could.
 * That is the round-1 identification rule applied to a new family.
 *
 * ## The actions, and their order
 *
 * `Bezahlt` then `Erhalten`, always in that order — the owner's 2026-08-17
 * ruling, which the Cash screen's own button pair already follows and whose
 * code comment names this widget as the reason the two must agree. Red is spent
 * only on money leaving, emerald only on money arriving; nothing else on the
 * card may use either.
 *
 * Both deep-link into the app's real entry sheet with the wallet AND the
 * direction preselected ([NotifDeepLink.AddCashEntry]). Nothing is posted from
 * the launcher: a widget that wrote to the ledger on a single tap, with no
 * amount and no undo, is not a shortcut.
 *
 * ## Foto-Buchung: designed now, shipped later
 *
 * The third slot is drawn now so the larger sizes will not change geometry when
 * receipt capture arrives. It carries a `BALD` badge, a dashed-feeling quiet
 * treatment, and the spoken name "Foto-Buchung, bald verfügbar". Tapping it
 * opens the wallet's own Cash screen — an honest, related, non-destructive
 * destination. It is deliberately NOT a dead control (the owner's standing
 * never-a-dead-tap rule) and equally deliberately NOT a fake capture flow: no
 * camera opens, nothing is booked, and the badge has already said why.
 *
 * ## Renditions (each composed for its canvas)
 *
 *  * **2x1** — source + balance, then the two live postings side by side. No
 *    camera: at one row it would take a third of the width from the two
 *    controls that actually work.
 *  * **4x1** — balance block left, the three actions as a row on the right.
 *  * **2x2** — the balance as a hero, then three full-width stacked actions.
 *  * **4x2** — source, balance, the three most recent movements of THIS wallet,
 *    then the action row.
 *
 * ## Data
 *
 * [CashSourceEntity.balanceEur] and each movement's signed `amountEur` are the
 * server's own figures, read from the same Room tables the Cash screen reads.
 * Nothing here sums, converts or derives money — see [BtCashWallet]'s header.
 */
class BtCashWalletWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Everything the card needs, resolved OFF the path to the first frame. */
    private class Loaded(
        val local: Context,
        val snapshot: BtWidgetSnapshot,
        val colors: BtGlanceColors,
        val config: BtWidgetCashConfig,
        val portfolioId: String?,
        val source: CashSourceEntity?,
        val missing: Boolean,
        val movements: List<CashMovementEntity>,
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        btProvideContent(
            context = context,
            id = id,
            load = {
                val snapshot = BtWidgetRepository.load(context)
                val state = btWidgetConfigOrNull("cash state") {
                    getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                }
                // No stored wallet yet ⇒ a just-pinned instance may have one
                // waiting from the in-app builder; otherwise follow mode.
                val stored = state?.let(::btWidgetCashConfig) ?: BtWidgetCashConfig()
                val config = if (stored.sourceId.isBlank()) {
                    btWidgetConfigOrNull("cash") { btWidgetClaimPinnedCash(context, id) } ?: stored
                } else {
                    stored
                }
                // Follow mode resolves the SAME portfolio the app's own switcher
                // would, so the wallet on the launcher and the wallet in the app
                // are the same wallet.
                val portfolioId = config.portfolioId.takeIf { it.isNotBlank() }
                    ?: at.bettertrack.app.data.repo.PortfolioRepository.resolveSelection(
                        snapshot.portfolios,
                        snapshot.selectedPortfolioId,
                    )?.id
                val sources = portfolioId
                    ?.let { BtWidgetRepository.loadCashSources(it) }
                    .orEmpty()
                val source = btWidgetResolveCashSource(config, sources)
                Loaded(
                    local = btWidgetContext(context),
                    snapshot = snapshot,
                    colors = btGlanceColors(btWidgetThemeMode(context)),
                    config = config,
                    portfolioId = portfolioId,
                    source = source,
                    missing = btWidgetCashSourceMissing(config, sources, source),
                    // Only fetched when a size could show them AND the knob is
                    // on — the ledger read is the heaviest part of this load.
                    movements = if (config.movements && source != null && portfolioId != null) {
                        btWidgetCashMovements(
                            BtWidgetRepository.loadRecentMovements(portfolioId, MOVEMENT_SCAN),
                            source.id,
                        )
                    } else {
                        emptyList()
                    },
                )
            },
        ) { data ->
            BtWidgetCard(
                colors = data.colors,
                // The card body opens the wallet's own ledger; the buttons on
                // top of it own their smaller, more specific hit areas.
                action = actionStartActivity(
                    btWidgetIntent(context, BT_WIDGET_TARGET_CASH, portfolioId = data.portfolioId),
                ),
                // A one-row card spends its whole height budget on a figure
                // and a row of thumb-sized buttons, so it takes the inset back
                // (owner addendum 2026-08-17: "make the buttons bigger/easier
                // to use"). 8dp still clears the launcher's rounded corners.
                padding = if (
                    btWidgetRowClass(LocalSize.current.height.value) <= BtWidgetSizeClass.ROW1
                ) {
                    8.dp
                } else {
                    BT_WIDGET_PADDING
                },
            ) {
                Content(context, data)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(context: Context, data: Loaded) {
        val local = data.local
        val colors = data.colors
        when {
            data.snapshot.session == BtWidgetSession.SIGNED_OUT ->
                BtWidgetMessage(local.getString(R.string.bt_widget_signed_out), colors, emphasis = true)

            data.snapshot.session == BtWidgetSession.LOADING ->
                BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)

            // Pinned to a wallet that has been archived or deleted. Name what
            // was lost rather than quietly showing another wallet's money under
            // the old label.
            data.missing -> {
                BtWidgetTag(data.config.sourceName, colors)
                BtWidgetMessage(local.getString(R.string.bt_widget_cash_missing), colors)
            }

            data.source == null ->
                BtWidgetMessage(local.getString(R.string.bt_widget_cash_empty), colors)

            else -> Wallet(context, data, data.source)
        }
    }

    @Composable
    private fun ColumnScope.Wallet(context: Context, data: Loaded, source: CashSourceEntity) {
        val local = data.local
        val colors = data.colors
        val locale = btWidgetLocale(local)
        val size = LocalSize.current
        val wide = btWidgetIsWide(size.width.value)
        val rows = btWidgetRowClass(size.height.value)
        val tall = rows >= BtWidgetSizeClass.ROW2

        val balance = btWidgetHeroMoney(
            source.balanceEur,
            BT_WIDGET_QUOTE_CURRENCY,
            data.snapshot.discreet,
            locale,
        )
        val shownMovements = if (tall && wide && data.config.movements) data.movements else emptyList()

        when {
            // 4x1 — the balance block claims the left, the actions the right.
            !tall && wide -> WideStrip(context, data, source, balance, locale)
            // 2x1 — stacked: identification, figure, then the two live postings.
            !tall -> CompactStrip(context, data, source, balance)
            // 2x2 / 4x2 — hero above, actions below; the wide-and-tall card
            // earns the movement list in between.
            else -> Expanded(context, data, source, balance, shownMovements, wide, locale)
        }
    }

    // ── 2x1 ──────────────────────────────────────────────────────────────────

    @Composable
    private fun ColumnScope.CompactStrip(
        context: Context,
        data: Loaded,
        source: CashSourceEntity,
        balance: String,
    ) {
        // TWO actions here, never three (owner addendum 2026-08-17). At one
        // row and two columns there is room for a balance and two comfortable
        // thumb targets, or for three cramped ones — and the study already
        // treats every size as its own hierarchy, so the camera simply starts
        // at 4x1 rather than everything shrinking to make space for it.
        BtSubjectRow(source.name, data.colors)
        Text(
            text = balance,
            style = TextStyle(
                color = data.colors.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(5.dp))
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            ActionCell(context, data, source, Posting.PAID, fill = true)
            Spacer(GlanceModifier.width(6.dp))
            ActionCell(context, data, source, Posting.RECEIVED, fill = true)
        }
    }

    // ── 4x1 ──────────────────────────────────────────────────────────────────

    @Composable
    private fun ColumnScope.WideStrip(
        context: Context,
        data: Loaded,
        source: CashSourceEntity,
        balance: String,
        locale: Locale,
    ) {
        // `defaultWeight()` on the ROW so the content fills the card's height
        // and centres in it — without it the strip sat in the top half and left
        // an empty band underneath (device QA 2026-08-17).
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                BtSubjectRow(source.name, data.colors)
                Text(
                    text = balance,
                    style = TextStyle(
                        color = data.colors.textPrimary,
                        // 18sp, not 22: the three action cells beside it are
                        // fixed-width, so the figure gets what is left — and a
                        // hero that has to ellipsize is worse than a smaller
                        // one that does not. `btWidgetHeroMoney` still drops
                        // cents before it would ever truncate.
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                AsOf(data, locale)
            }
            Spacer(GlanceModifier.width(8.dp))
            // FIXED widths, not weights. Four equal weights gave the balance a
            // quarter of the card and printed "3.112,…" on the owner's live
            // wallet — a truncated money hero, which is the one thing this
            // family may never show.
            ActionCell(context, data, source, Posting.PAID, width = STRIP_ACTION_W, fill = true)
            Spacer(GlanceModifier.width(6.dp))
            ActionCell(context, data, source, Posting.RECEIVED, width = STRIP_ACTION_W, fill = true)
            Spacer(GlanceModifier.width(6.dp))
            ActionCell(context, data, source, Posting.PHOTO, width = STRIP_ACTION_W, fill = true)
        }
    }

    // ── 2x2 and 4x2 ──────────────────────────────────────────────────────────

    @Composable
    private fun ColumnScope.Expanded(
        context: Context,
        data: Loaded,
        source: CashSourceEntity,
        balance: String,
        movements: List<CashMovementEntity>,
        wide: Boolean,
        locale: Locale,
    ) {
        BtSubjectRow(source.name, data.colors) {
            if (wide) AsOf(data, locale)
        }
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = balance,
            style = TextStyle(
                color = data.colors.textPrimary,
                fontSize = if (wide) 28.sp else 25.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )

        if (movements.isNotEmpty()) {
            // Grouped into ONE child: a RemoteViews container keeps at most ten,
            // and a three-row ledger plus its dividers is how the performance
            // hero lost its whole bottom third once already.
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.height(6.dp))
                BtWidgetDivider(data.colors)
                movements.forEach { MovementRow(data, it, locale) }
            }
        }

        if (wide) {
            // 4x2 — the three actions share one row, as on the 4x1. The
            // elastic spacer pins that row to the bottom under the movements.
            Spacer(GlanceModifier.defaultWeight())
            // An explicit, generous height: wrapping to the text left a ~48dp
            // row, and the owner's addendum is that a launcher button is hit
            // with a thumb over a moving list — err chunky.
            Row(modifier = GlanceModifier.fillMaxWidth().height(BT_CASH_ACTION_H)) {
                ActionCell(context, data, source, Posting.PAID, fill = true)
                Spacer(GlanceModifier.width(6.dp))
                ActionCell(context, data, source, Posting.RECEIVED, fill = true)
                Spacer(GlanceModifier.width(6.dp))
                ActionCell(context, data, source, Posting.PHOTO, fill = true)
            }
        } else {
            // 2x2 — stacked full-width rows; at 2 cells across, three labels
            // side by side would each be four clipped characters.
            //
            // The three share the card's REMAINING height rather than sitting
            // at a fixed size under an elastic spacer. On this launcher a 2-row
            // card is 250dp — far taller than the study's 190dp canvas — and
            // the fixed version left ~50dp of dead air between the balance and
            // the buttons (device QA 2026-08-17). Owner's standing rule:
            // nothing that does not earn its pixels, and a gap earns none.
            Spacer(GlanceModifier.height(8.dp))
            Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                StackedAction(
                    context, data, source, Posting.PAID,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.height(6.dp))
                StackedAction(
                    context, data, source, Posting.RECEIVED,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.height(6.dp))
                StackedAction(
                    context, data, source, Posting.PHOTO,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
    }

    /** One ledger row: mark, description over its kind, signed amount. */
    @Composable
    private fun MovementRow(data: Loaded, movement: CashMovementEntity, locale: Locale) {
        val colors = data.colors
        val kindLabel = btWidgetMovementLabel(data.local, movement.kind)
        // `displayNote` strips the queue's `[bt:<uuid>]` idempotency marker —
        // the raw note would put a uuid on the home screen.
        val note = displayNote(movement.note)?.takeIf { it.isNotBlank() }
        val title = note ?: kindLabel
        // A movement with no description falls back to its KIND for the title,
        // and then repeating the kind underneath printed "Erhalten / Erhalten"
        // on the owner's live ledger (device QA 2026-08-17). The date is the
        // honest second line there — it says something the first line does not.
        val subline = if (note != null) {
            kindLabel
        } else if (movement.executedAtMs > 0L) {
            SimpleDateFormat("d. MMM", locale).format(Date(movement.executedAtMs))
        } else {
            ""
        }
        val initials = btWidgetCashInitials(title)
        val tone = btWidgetCashTone(movement.amountEur)
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (initials.isNotEmpty()) {
                val mark = GlanceModifier
                    .size(20.dp)
                    .background(colors.chip)
                Box(
                    modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        mark.cornerRadius(6.dp)
                    } else {
                        mark
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initials,
                        style = TextStyle(
                            color = colors.textMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.width(7.dp))
            }
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = title,
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                if (subline.isNotEmpty()) {
                    Text(
                        text = subline,
                        style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                        maxLines = 1,
                    )
                }
            }
            Spacer(GlanceModifier.width(6.dp))
            Text(
                // The server's SIGNED amount, shown with its sign — the row's
                // colour and its number must agree, and both come from one field.
                text = btWidgetMoney(
                    movement.amountEur,
                    BT_WIDGET_QUOTE_CURRENCY,
                    data.snapshot.discreet,
                    locale,
                    showSign = true,
                ),
                style = TextStyle(
                    color = colors.tone(tone),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }

    /** The "Stand HH:mm" note, only once the figures have actually aged. */
    @Composable
    private fun AsOf(data: Loaded, locale: Locale) {
        val asOf = data.snapshot.netWorthAsOfMs
        if (data.snapshot.netWorthStale && asOf != null) {
            BtWidgetAsOf(data.local, asOf, data.colors, locale)
        }
    }

    // ── The action controls ──────────────────────────────────────────────────

    /** The three slots, in the order the owner fixed. */
    private enum class Posting { PAID, RECEIVED, PHOTO }

    @Composable
    private fun tone(posting: Posting, colors: BtGlanceColors): Pair<ColorProvider, ColorProvider> =
        when (posting) {
            // The wash tokens are pre-flattened over the card surface, because a
            // RemoteViews background composites over the WALLPAPER, not the card.
            Posting.PAID -> colors.loss to colors.lossWash
            Posting.RECEIVED -> colors.gain to colors.gainWash
            // Gold, not a money colour: a tool is not a direction.
            Posting.PHOTO -> colors.gold to colors.goldWash
        }

    private fun icon(posting: Posting): Int = when (posting) {
        Posting.PAID -> R.drawable.ic_bt_widget_paid
        Posting.RECEIVED -> R.drawable.ic_bt_widget_received
        Posting.PHOTO -> R.drawable.ic_bt_widget_camera
    }

    private fun label(local: Context, posting: Posting): String = when (posting) {
        // The app's OWN cash vocabulary, not a widget-only synonym: the launcher
        // button and the sheet it opens have to use the same word.
        Posting.PAID -> local.getString(R.string.bt_cash_withdraw)
        Posting.RECEIVED -> local.getString(R.string.bt_cash_deposit)
        Posting.PHOTO -> local.getString(R.string.bt_widget_cash_photo)
    }

    /**
     * Where a control lands. The two postings carry the wallet and the
     * direction; the reserved photo control opens that wallet's Cash screen —
     * honest and non-destructive, never a capture flow that does not exist.
     */
    private fun action(context: Context, data: Loaded, source: CashSourceEntity, posting: Posting): Action =
        when (posting) {
            Posting.PHOTO -> actionStartActivity(
                btWidgetIntent(context, BT_WIDGET_TARGET_CASH, portfolioId = data.portfolioId),
            )
            else -> actionStartActivity(
                btWidgetIntent(
                    context,
                    BT_WIDGET_TARGET_CASH_ENTRY,
                    portfolioId = data.portfolioId,
                    sourceId = source.id,
                    inflow = posting == Posting.RECEIVED,
                ),
            )
        }

    /**
     * A control that shares a row: glyph over its verb, on its own wash.
     *
     * [width] null = share the row by weight (2x1, 4x2, where the row IS the
     * whole card). A fixed width is the 4x1's case, where the row also carries
     * the balance and equal weights would starve it.
     */
    @Composable
    private fun RowScope.ActionCell(
        context: Context,
        data: Loaded,
        source: CashSourceEntity,
        posting: Posting,
        width: androidx.compose.ui.unit.Dp? = null,
        /** Claim the row's full height — the owner's "bigger/easier to use". */
        fill: Boolean = false,
    ) {
        val (ink, wash) = tone(posting, data.colors)
        // The reserved control uses the SHORT form in every row rendition. Its
        // cell also has to carry the BALD badge, and "Foto-Buchung" plus a
        // badge does not fit a third of a card at a legible size. The two live
        // postings keep their real verbs everywhere, because those are what the
        // button actually does and an abbreviation there would be a guess.
        val text = if (posting == Posting.PHOTO) {
            data.local.getString(R.string.bt_widget_cash_photo_short)
        } else {
            label(data.local, posting)
        }
        // The WHOLE tile is the target — background, padding, glyph and label
        // all sit inside one clickable, so there is no dead ring around the
        // icon a thumb can land in and miss.
        val base = (if (width != null) GlanceModifier.width(width) else GlanceModifier.defaultWeight())
            .let { if (fill) it.fillMaxHeight() else it.height(BT_CASH_ACTION_H) }
            .background(wash)
            .clickable(action(context, data, source, posting))
            .padding(horizontal = 6.dp, vertical = 6.dp)
        Column(
            modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base.cornerRadius(12.dp)
            } else {
                base
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(icon(posting)),
                contentDescription = describe(data.local, posting, text),
                colorFilter = ColorFilter.tint(ink),
                // Grown with the button: a bigger tile carrying the same small
                // glyph reads as a big empty box, which is not "easier to use".
                modifier = GlanceModifier.size(19.dp),
            )
            Spacer(GlanceModifier.height(3.dp))
            // The badge rides BESIDE the label, not under it. Stacked, the
            // reserved cell needed ~67dp of the row's 60 and the launcher cut
            // the badge in half (device QA 2026-08-17) — the one element whose
            // whole job is to say "not live yet", clipped.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = text,
                    style = TextStyle(
                        color = ink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                )
                if (posting == Posting.PHOTO) {
                    Spacer(GlanceModifier.width(4.dp))
                    SoonBadge(data)
                }
            }
        }
    }

    /** The 2x2's full-width control: glyph, verb, and the badge pushed right. */
    @Composable
    private fun ColumnScope.StackedAction(
        context: Context,
        data: Loaded,
        source: CashSourceEntity,
        posting: Posting,
        modifier: GlanceModifier = GlanceModifier,
    ) {
        val (ink, wash) = tone(posting, data.colors)
        // Short form for the reserved control here too. Stacked rows only
        // happen at 2x2, where a 20dp glyph, a 13sp label and the BALD badge
        // share ~139dp — the full "Foto-Buchung" ellipsized to "Foto-Buch…"
        // once the owner's addendum made the button bigger (device QA
        // 2026-08-17), and a truncated label on a control is never acceptable.
        // The spoken name stays the full "Foto-Buchung, bald verfügbar".
        val text = if (posting == Posting.PHOTO) {
            data.local.getString(R.string.bt_widget_cash_photo_short)
        } else {
            label(data.local, posting)
        }
        val base = modifier
            .fillMaxWidth()
            .background(wash)
            .clickable(action(context, data, source, posting))
            .padding(horizontal = 12.dp, vertical = 12.dp)
        Row(
            modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base.cornerRadius(10.dp)
            } else {
                base
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(icon(posting)),
                contentDescription = describe(data.local, posting, text),
                colorFilter = ColorFilter.tint(ink),
                modifier = GlanceModifier.size(20.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
            Text(
                text = text,
                style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (posting == Posting.PHOTO) SoonBadge(data)
        }
    }

    /** The reserved slot's status: gold fill, dark ink, the study's `Bald`. */
    @Composable
    private fun SoonBadge(data: Loaded) {
        BtContextChip(data.local.getString(R.string.bt_widget_cash_soon), data.colors, gold = true)
    }

    /**
     * What a screen reader says. The photo control announces its unavailability
     * — the badge is a visual cue and a spoken name has to carry the same fact.
     */
    private fun describe(local: Context, posting: Posting, text: String): String =
        if (posting == Posting.PHOTO) {
            local.getString(R.string.bt_widget_cash_photo_cd, text)
        } else {
            text
        }

    private companion object {
        /**
         * How deep into the portfolio's newest movements to look for THIS
         * wallet's three. A portfolio with several wallets interleaves them, so
         * scanning only three rows would leave a quiet wallet's list empty
         * while its movements sat at row four.
         */
        const val MOVEMENT_SCAN = 40

        /**
         * The 4x1's action-cell width. Fits "Erhalten" at 10sp with its inset
         * and leaves the balance the ~110dp it needs at four columns, which is
         * the trade this size is: three real controls AND an untruncated
         * figure, not four things that each half-fit.
         */
        val STRIP_ACTION_W = 76.dp

        /**
         * A cash action's height when nothing taller is available to it.
         *
         * 60dp, deliberately above the platform's 48dp minimum: the owner's
         * 2026-08-17 addendum is that these are hit with a thumb, on a
         * launcher, over a list that may be moving — "make the buttons
         * bigger/easier to use". Every rendition either uses this or fills
         * more, and none uses less.
         */
        val BT_CASH_ACTION_H = 60.dp
    }
}
