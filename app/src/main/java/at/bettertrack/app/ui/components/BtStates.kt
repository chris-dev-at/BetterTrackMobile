package at.bettertrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The shared "state" scaffold behind every empty/error surface (spec §6.13): a
 * calm, centered column with the glyph carried in a soft circular badge, a clear
 * title, a short secondary message, and an optional next action. Wrapping the
 * icon in a 64dp surface badge (instead of a bare floating glyph) gives these
 * states intentional presence and is the template ALL downstream screens inherit.
 *
 * ## R3: the badge is tonal, not outlined
 *
 * The badge used to be a `surface` fill plus a 1dp ring. R2 moved the whole app
 * from border walls to tonal steps (see [BtGroup]), and this badge was the last
 * piece of the *state* system still drawing an outline — which mattered more
 * than its size suggests, because it is the one shape every empty and error
 * surface in the app inherits. A ring around a glyph that is already a filled
 * disc on a darker page is a second boundary doing the first one's job; the
 * tonal step alone reads cleaner and is now the same containment language the
 * groups use. The error badge keeps its red tint (a colour, not a border), so
 * "this is a failure" still reads before any word does.
 */
@Composable
private fun BtStateScaffold(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = BtTheme.colors.textSecondary,
    badgeColor: Color = BtTheme.colors.surface,
    message: String? = null,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val bt = BtTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(badgeColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        // Diagnostic line: the server's own words, one step quieter than the
        // app's sentence, so it reads as supporting detail and never competes
        // with the explanation above it.
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * Empty state (spec §6.13): helpful, centered — muted glyph badge, clear title,
 * short message, optional next action.
 */
@Composable
fun BtEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    BtStateScaffold(
        title = title,
        modifier = modifier,
        icon = icon,
        message = message,
        action = action,
    )
}

/**
 * A failure inside ONE section of a screen whose primary content already loaded
 * (R3 §2).
 *
 * ## Why this is not [BtErrorState]
 *
 * [BtErrorState] claims the surface — correct when the screen has nothing else
 * to show. It is wrong for a chart under a price that arrived fine, or a
 * backtest under a conglomerate's positions: a 64dp badge and a centred title
 * over a section would say the *page* failed. This is one line and a retry, at
 * the weight the failure actually has.
 *
 * The retry is not optional, and that is the whole reason this exists as a
 * component. Three screens had grown their own private version of this row and a
 * fourth had no error branch at all — it rendered its section's failure as the
 * section's *empty* state, so a dropped request read as "there is no data here".
 * Without a retry the only cure for a dropped request is to leave the screen and
 * come back, which users do not know to do.
 *
 * The diagnostic (present only for a server code this build has no copy for)
 * rides after an em dash rather than claiming a second line the compact layout
 * does not have.
 */
@Composable
fun BtInlineError(
    message: BtMessage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = bt.lossSoft,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message.resolveWithDiagnostic(),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.bt_action_retry),
                style = MaterialTheme.typography.labelLarge,
                color = bt.goldEmphasis,
            )
        }
    }
}

/**
 * "This screen needs a connection" (R3 §2).
 *
 * ## Why offline gets its own composable
 *
 * Five screens rendered this exact state — a centred [BtEmptyState] carrying
 * `bt_requires_connection_title` and a screen-specific sentence — and between
 * them they used **four different glyphs**: `Dashboard`, `NotificationsActive`,
 * `People`, `Search`, and on the asset page a navigation *back arrow*. None of
 * them used `CloudOff`, which is the mark this app already uses for "offline" in
 * seven other places, starting with the global offline banner the user has
 * almost certainly just seen. A user who learns what `CloudOff` means from the
 * banner should not have to re-learn it per screen, and a screen's own domain
 * glyph says "this feature" where the state needs to say "the network".
 *
 * So the glyph is fixed here rather than passed in — that is the entire point of
 * the component — and each screen supplies only [message], the one thing that is
 * genuinely screen-specific.
 *
 * ## [onRetry]
 *
 * Every one of those five call sites sat directly beside a [BtErrorState] with a
 * working Retry, while the offline branch — the one a user is far more likely to
 * be able to fix, by turning the network back on — offered no way to try again.
 * Pass the same action the sibling error branch uses.
 */
@Composable
fun BtOfflineState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.bt_requires_connection_title),
    onRetry: (() -> Unit)? = null,
) {
    BtStateScaffold(
        title = title,
        modifier = modifier,
        icon = Icons.Outlined.CloudOff,
        message = message,
        action = onRetry?.let {
            {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_action_retry),
                    onClick = it,
                )
            }
        },
    )
}

/**
 * Error state with retry (spec §6.13): human-readable, never a raw error string.
 * The badge picks up the red-tinted destructive surface so the state reads as an
 * error at a glance without shouting.
 *
 * [message] is a [BtMessage] — a string RESOURCE plus an optional diagnostic —
 * not a `String`. That is the S6 P0-4 contract made compiler-enforced: there is
 * no longer a parameter a raw `error.userMessage` could be passed to. When the
 * message carries a diagnostic (only ever for a server code this build has no
 * copy for), it renders beneath the main line in a dimmer, smaller style so it
 * reads as detail rather than as the explanation.
 */
@Composable
fun BtErrorState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.bt_error_generic_title),
    message: BtMessage = BtMessage(R.string.bt_error_generic_message),
    onRetry: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    BtStateScaffold(
        title = title,
        modifier = modifier,
        icon = Icons.Outlined.ErrorOutline,
        iconTint = bt.loss,
        badgeColor = bt.lossSurface,
        // resolve() — not stringResource(message.res) — because a handful of
        // catalogued codes name a currency through a %1$s argument, and dropping
        // it would render the placeholder itself to the user.
        message = message.resolve(),
        detail = message.diagnostic,
        action = onRetry?.let {
            {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_action_retry),
                    onClick = it,
                )
            }
        },
    )
}
