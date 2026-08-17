package at.bettertrack.app.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import at.bettertrack.app.R
import at.bettertrack.app.data.i18n.LocaleManager
import at.bettertrack.app.ui.format.btFormatLocale
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The chrome both widgets share: the card, the section label, and the three
 * states that are not data (signed out, syncing, empty).
 *
 * ## The card
 *
 * `appWidgetBackground()` tags the root with `@android:id/background`, which is
 * what lets the launcher round and animate the widget on Android 12+. The corner
 * radius uses the SYSTEM dimension rather than a number of our own, so the widget
 * matches whatever the device's launcher does to everything else on the screen —
 * a hardcoded radius is the classic way a widget ends up looking pasted on.
 *
 * Below API 31 the radius is simply not applied. That is deliberate and is not a
 * gap: `RemoteViews` had no corner-radius support before 31, and widgets on those
 * releases are square by platform convention, so square is what "matches the
 * system" means there.
 */

/**
 * Tight on purpose, and identical across the family so it reads as one.
 *
 * 14dp was the first release's value and the owner's verdict on the result was
 * "bloated like hell / so much wasted space" — the padding is where a small
 * card loses its content area fastest, so it is the first thing the redesign
 * takes back. 12dp still clears the launcher's rounded corners at every size.
 */
internal val BT_WIDGET_PADDING = 12.dp

internal fun btWidgetCardModifier(colors: BtGlanceColors): GlanceModifier {
    val base = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(colors.surface)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        base.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        base
    }
}

/**
 * The card, with the whole surface clickable.
 *
 * The click sits on the ROOT rather than on the value, because a widget is a
 * small target on a crowded screen and "anywhere on the card" is the only hit
 * area a user should have to find.
 */
@Composable
internal fun BtWidgetCard(
    colors: BtGlanceColors,
    action: Action,
    // One-cell STRIP layouts cannot afford even the family padding — 24dp of
    // a ~50dp height would be air. Every 2-cell rendition keeps the default.
    padding: Dp = BT_WIDGET_PADDING,
    // ColumnScope-receiving on purpose: `defaultWeight()` is a member of the
    // scope, and it is the only way a list inside the card can claim the height
    // the label above it did not use.
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = btWidgetCardModifier(colors).clickable(action).padding(padding),
    ) {
        content()
    }
}

/**
 * The whole card as one state message, with NO click target.
 *
 * This is what [btProvideContent] publishes as its first frame, before the load
 * has returned. It is the Glance twin of `@layout/bt_widget_loading` — same
 * surface, same corner behaviour, same gold-dot-over-one-line affordance — so
 * the hand-off from the launcher's inflated XML to the first real frame is a
 * content swap the eye does not catch.
 *
 * Deliberately not clickable: nothing has been resolved yet, so there is no
 * honest destination to send a tap to, and a card that opens the wrong screen
 * is worse than one that waits a moment.
 */
@Composable
internal fun BtWidgetStatusCard(colors: BtGlanceColors, text: String) {
    Column(modifier = btWidgetCardModifier(colors).padding(BT_WIDGET_PADDING)) {
        BtWidgetMessage(text, colors)
    }
}

/**
 * A tiny DATA line — a portfolio's name, an asset's symbol.
 *
 * This is what replaced `BtWidgetLabel`, the gold section header every card
 * used to open with. The owner's ruling was direct — "they all dont need a
 * header to know what they are" — and he is right: the content identifies the
 * widget (a lone big € figure is the net worth; a list of tickers is the
 * watchlist). What REMAINS is per-instance data a user cannot infer, like WHICH
 * portfolio a card shows, and that is the only thing this may carry. Muted by
 * default; [gold] is for the one line per card that earns the accent (a
 * ticker symbol), per the design system's "gold sparingly".
 */
@Composable
internal fun BtWidgetTag(text: String, colors: BtGlanceColors, gold: Boolean = false) {
    Text(
        text = text,
        style = TextStyle(
            color = if (gold) colors.gold else colors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

// ── Round-2 primitives (the Codex study's language) ──────────────────────────

/**
 * The subject row that opens most cards: a small gold dot, the configured
 * subject ("Alle Depots", "BAYN.DE", "Food"), and an optional right-aligned
 * trailing slot (a meta line, a context chip). This is the study's
 * identification rule made concrete — the card never says what KIND of widget
 * it is, it names WHOSE data it shows.
 */
@Composable
internal fun BtSubjectRow(
    subject: String,
    colors: BtGlanceColors,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot is a text glyph rather than a shaped Box: it is round on every
        // API level, needs no drawable, and scales with font settings.
        Text(
            text = "●",
            style = TextStyle(color = colors.gold, fontSize = 7.sp),
            maxLines = 1,
        )
        Text(
            text = " $subject",
            style = TextStyle(
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        trailing?.invoke()
    }
}

/**
 * The tinted delta pill: arrow + signed figures on the hue's own wash. The
 * wash is a pre-flattened opaque token ([BtGlanceColors.gainWash] …), because a
 * RemoteViews background composites over the wallpaper, not the card. Flat
 * tone renders on the neutral chip fill — zero is not a gain.
 *
 * Rounded on API 31+; square below, matching the card's own corner behaviour.
 */
@Composable
internal fun BtDeltaPill(text: String, tone: BtWidgetTone, colors: BtGlanceColors) {
    val bg = when (tone) {
        BtWidgetTone.UP -> colors.gainWash
        BtWidgetTone.DOWN -> colors.lossWash
        BtWidgetTone.FLAT -> colors.chip
    }
    val base = GlanceModifier.background(bg).padding(horizontal = 7.dp, vertical = 4.dp)
    Box(
        modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.cornerRadius(7.dp)
        } else {
            base
        },
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = colors.tone(tone),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The neutral context chip ("EUR", a month, a grouping, an inactive range).
 * Quiet by design: one tonal step off the card, muted ink, no accent.
 */
@Composable
internal fun BtContextChip(text: String, colors: BtGlanceColors, gold: Boolean = false) {
    val base = GlanceModifier
        .background(if (gold) colors.gold else colors.chip)
        .padding(horizontal = 7.dp, vertical = 3.dp)
    Box(
        modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.cornerRadius(6.dp)
        } else {
            base
        },
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (gold) colors.onGold else colors.textMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

/** The 1dp divider above a footer zone. */
@Composable
internal fun BtWidgetDivider(colors: BtGlanceColors) {
    Box(
        modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(colors.border),
    ) {}
}

/**
 * A micro label (the study's uppercase 10px captions): "BESTAND", "TIEF"…
 * Root-locale uppercasing on purpose: the app ships de/en, where ROOT casing is
 * correct, and a composable must not read the process locale non-observably.
 */
@Composable
internal fun BtMicroLabel(text: String, colors: BtGlanceColors) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = TextStyle(color = colors.textMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
}

/**
 * A whole-card message: the signed-out CTA, the syncing state, an empty board.
 *
 * Centred and single-line-ish on purpose — these render at 2x2, where a
 * paragraph is unreadable and a truncated paragraph is worse.
 */
@Composable
internal fun ColumnScope.BtWidgetMessage(
    text: String,
    colors: BtGlanceColors,
    emphasis: Boolean = false,
) {
    Box(
        // `defaultWeight`, not `fillMaxSize`: this sits below the section label
        // in a column, and match_parent height in a vertical LinearLayout would
        // push the label it belongs to off the top of the card.
        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        contentAlignment = Alignment.Center,
    ) {
        // A DESIGNED empty state (device review round 3): the brand dot over one
        // short muted line — never a bare sentence floating in a white void.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "●",
                style = TextStyle(color = colors.gold, fontSize = 10.sp),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
            Text(
                text = text,
                style = TextStyle(
                    color = if (emphasis) colors.gold else colors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The "as of HH:mm" footnote, shown only when the figures have gone stale.
 *
 * Time-only, not a date: a widget that says "as of 14:03" is telling the user
 * something they can act on, and one that says a full timestamp is spending
 * three lines of a 2x2 to do it. [BT_WIDGET_STALE_AFTER_MS] keeps the window
 * short enough that the day is never ambiguous.
 */
@Composable
internal fun BtWidgetAsOf(context: Context, asOfMs: Long, colors: BtGlanceColors, locale: Locale) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(asOfMs))
    Text(
        text = context.getString(R.string.bt_widget_as_of, time),
        style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
        maxLines = 1,
    )
}

/**
 * The user's font scale — every text-height budget must multiply by this, or a
 * large-font device overflows layouts that fit at 1.0.
 */
internal fun btWidgetFontScale(context: Context): Float =
    context.resources.configuration.fontScale.coerceAtLeast(1f)

/**
 * The dp a text line spends, approximated as sp × fontScale × 1.34 (the
 * platform's default line-height ratio). Used ONLY to budget chart heights —
 * being a couple of dp conservative costs air, being generous costs clipping.
 */
internal fun btWidgetTextDp(sp: Float, fontScale: Float): Float = sp * fontScale * 1.34f

/**
 * The context a widget should read strings and format numbers with.
 *
 * `LocaleManager.wrap` is the app's own per-app language switch: it returns a
 * configuration context in the user's chosen language (and syncs the JVM default
 * so the money formatters agree). Without it a widget would render German
 * strings for a German phone but English ones for a German-by-choice user on an
 * English phone — a split the app itself does not have.
 */
internal fun btWidgetContext(context: Context): Context = LocaleManager.wrap(context)

/**
 * The widget's formatting locale.
 *
 * The normalization it used to perform inline — any German variant formats as
 * plain German, because CLDR gives de-AT a narrow-space thousands separator —
 * now lives in [btFormatLocale], next to the formatter factory it governs.
 *
 * That move is the actual fix. Normalizing here fixed the launcher and left the
 * app screens on U+202F, so the owner's phone showed `3.112,08 €` on a widget
 * and `5 712,08 €` in Cash (device review 2026-08-17) — the same split, moved.
 * One rule, one place, both surfaces; this function is now only "which locale
 * is this widget's context in".
 */
internal fun btWidgetLocale(context: Context): Locale =
    btFormatLocale(context.resources.configuration.locales[0] ?: Locale.getDefault())
