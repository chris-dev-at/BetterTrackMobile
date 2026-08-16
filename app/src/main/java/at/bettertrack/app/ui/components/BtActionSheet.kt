package at.bettertrack.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * One entry of a [BtActionSheet] — a VERB the user can perform on the thing the
 * sheet is about.
 *
 * @param label the verb ("Rename", "Delete"). Callers pass already-resolved
 *   strings so the data class stays free of composition.
 * @param icon optional leading glyph. Either every action of a sheet should
 *   carry one or none should — a mixed column reads as two lists.
 * @param destructive inks the row in the loss colour. The colour is a warning,
 *   not the confirmation: destructive actions still confirm behind this sheet
 *   exactly as they did behind the menu it replaced.
 * @param enabled disabled rows stay visible (muted) rather than vanishing, so
 *   the sheet's shape does not change with transient state like being offline.
 */
@Immutable
data class BtSheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The app's context menu — a bottom sheet of actions about ONE named thing.
 *
 * ## Why a sheet and not a [androidx.compose.material3.DropdownMenu]
 *
 * Owner order 2026-08-16: *"replace every remaining anchored 3-dot menu — the
 * cheap square that opens on top of the dots — with bottom sheets."* The app
 * already speaks bottom-sheet for every picker, form and switcher; the anchored
 * menu was the one popup that did not, and it looked like it: a floating
 * Material square in a UI whose every other transient surface slides up from
 * the bottom with a drag handle. This component closes that gap with the SAME
 * chrome the picker family uses ([BtPickerSheet]'s scaffold): `surfaceHigh`
 * container, drag handle, titleLarge header, nav-bar insets.
 *
 * ## The title is the anchor
 *
 * An anchored menu says what it is about by WHERE it opens. A sheet loses that
 * geometry, so the title must carry it instead: pass the name of the thing the
 * actions act on (the source's name, the tag's name, the notification's title)
 * — never a generic "Options". [subtitle] can carry the entity's type when the
 * name alone is ambiguous.
 *
 * ## Contract
 *
 * Tapping an enabled action calls [onDismiss] FIRST and the action second —
 * the exact `menuOpen = false; action()` order every converted call site used —
 * so an action that opens a dialog or another sheet never stacks it on top of
 * this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtActionSheet(
    title: String,
    actions: List<BtSheetAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = bt.textMuted) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                // A ModalBottomSheet ships no content insets — without this the
                // last action would sit behind a 3-button nav bar. No `ime` in
                // the union: an action sheet never hosts a text field.
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            actions.forEach { action ->
                ActionRow(
                    action = action,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(action: BtSheetAction, onDismiss: () -> Unit) {
    val bt = BtTheme.colors
    val ink = when {
        !action.enabled -> bt.textMuted
        action.destructive -> bt.loss
        else -> bt.textPrimary
    }
    Surface(
        onClick = {
            // Dismiss-then-act, in this order — see the component KDoc.
            onDismiss()
            action.onClick()
        },
        enabled = action.enabled,
        shape = BtShapes.card,
        color = Color.Transparent,
        contentColor = ink,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_ROW_MIN_HEIGHT),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (action.icon != null) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Text(
                text = action.label,
                style = MaterialTheme.typography.bodyLarge,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A row is a full-width target at least this tall — the menu items this sheet
 * replaces were 48dp Material rows behind a 28–40dp anchor; the sheet's rows
 * must never be smaller than the thing they replaced.
 */
private val ACTION_ROW_MIN_HEIGHT = 52.dp
