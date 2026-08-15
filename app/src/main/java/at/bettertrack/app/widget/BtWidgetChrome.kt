package at.bettertrack.app.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import at.bettertrack.app.R
import at.bettertrack.app.data.i18n.LocaleManager
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

/** Comfortable, and identical on both widgets so they read as one family. */
internal val BT_WIDGET_PADDING = 14.dp

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
    // ColumnScope-receiving on purpose: `defaultWeight()` is a member of the
    // scope, and it is the only way a list inside the card can claim the height
    // the label above it did not use.
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = btWidgetCardModifier(colors).clickable(action).padding(BT_WIDGET_PADDING),
    ) {
        content()
    }
}

/** The small gold section label that names the widget. */
@Composable
internal fun BtWidgetLabel(text: String, colors: BtGlanceColors) {
    Text(
        text = text,
        style = TextStyle(color = colors.gold, fontSize = 11.sp, fontWeight = FontWeight.Medium),
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
        Text(
            text = text,
            style = TextStyle(
                color = if (emphasis) colors.gold else colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = if (emphasis) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
            maxLines = 3,
            modifier = GlanceModifier.fillMaxWidth(),
        )
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
 * The context a widget should read strings and format numbers with.
 *
 * `LocaleManager.wrap` is the app's own per-app language switch: it returns a
 * configuration context in the user's chosen language (and syncs the JVM default
 * so the money formatters agree). Without it a widget would render German
 * strings for a German phone but English ones for a German-by-choice user on an
 * English phone — a split the app itself does not have.
 */
internal fun btWidgetContext(context: Context): Context = LocaleManager.wrap(context)

internal fun btWidgetLocale(context: Context): Locale =
    context.resources.configuration.locales[0] ?: Locale.getDefault()
