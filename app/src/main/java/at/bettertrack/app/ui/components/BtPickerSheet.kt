package at.bettertrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The app's **one** picker surface — a bottom sheet with a title, a marked
 * current value, and picking-is-confirming semantics.
 *
 * ## Why this component exists
 *
 * Settings grew four pickers and answered them three different ways: a shared
 * `SettingsChoiceDialog` for currency and the (now retired) visibility default,
 * the *same* dialog with a different close label for the theme, and a separate
 * `ProfileIconDialog` for the 4×4 icon grid. All three were centre `AlertDialog`s,
 * which is where the owner's verdict — *"the pickers look shit"* — comes from,
 * and each one was free to make its own decisions about how the current value is
 * marked and how big a tap target is. Every one of those decisions is now made
 * once, here.
 *
 * ## What was actually wrong with a centre dialog, and what a sheet fixes
 *
 *  - **Reach.** An `AlertDialog` puts its list in the vertical middle of a 6.8"
 *    phone. A bottom sheet puts it under the thumb that opened it. Nothing else
 *    about the interaction changed and it is immediately better one-handed.
 *  - **The current value.** The old list marked it with a gold *tint* on the
 *    label plus an 18dp tick that only appeared for the selected row, so the
 *    labels of the other rows sat 18dp wider and the column edge wobbled. Here
 *    the trailing slot is claimed unconditionally (see [BtPickerRow]), selection
 *    is a wash + a hairline + a tick, and the label keeps `textPrimary` — gold
 *    ink on a gold wash is the one combination the light table cannot carry
 *    (`goldInk` is 2.41:1 on white by explicit owner decision).
 *  - **Targets.** The dialog rows were `12.dp` of vertical padding around a
 *    `bodyMedium` — roughly 44dp, under the 48dp floor. Rows here are 56dp.
 *  - **Long lists.** The dialog let its content grow until Material clipped it.
 *    The body scrolls, capped well below full expansion so an inner fling never
 *    fights the sheet's own drag (the wobble `PortfolioSwitcherSheet` documents).
 *  - **Dismissal.** Scrim, back and drag all work, for free, and the sheet still
 *    refuses to leave while a write is in flight.
 *
 * ## The semantics this preserves exactly
 *
 * **Picking is the confirmation.** There is no Save button anywhere in this
 * family — the same immediate-apply model the language screen uses. [busy] is
 * the in-flight write (options stop responding, the picked row shows its own
 * spinner rather than a spinner floating somewhere else), and a [message] keeps
 * the sheet OPEN under the list, because the choice the user made is still on
 * screen and still the thing they want.
 *
 * **The theme picker stays open on pick.** Nothing here dismisses anything: the
 * caller owns dismissal, exactly as it did with the dialog. That is what lets
 * the theme picker be its own live preview — and a sheet is strictly better at
 * it than a centre dialog was, because it covers the bottom third of the app
 * instead of the middle, so there is more repainting app to see.
 *
 * ## Search
 *
 * Optional, and off unless [onSearchQueryChange] is given. It exists because a
 * `<select>` on desktop is type-to-jump and the IANA zone list is 400+ rows: on
 * a phone that list is unusable without a filter, and a filter drawn by the one
 * caller that needs it would be the fourth place this family had already stopped
 * making its own decisions. The field sits ABOVE the scrolling body, not in it,
 * so it cannot scroll away from the list it filters — and the caller owns the
 * filtering, because only the caller knows which rows a query is allowed to
 * remove (the timezone picker's "no timezone set" row is a clear action wearing
 * a list row, and is never a search result).
 *
 * @param closeLabel the closing button's label, or null for no button. "Cancel"
 *   is right for a picker whose choice is still in flight when the button is
 *   reachable; a picker that has already applied its choice says "Done".
 * @param searchQuery the current filter text. Rendered only together with
 *   [onSearchQueryChange]; the caller holds the state, as it holds every other
 *   piece of picker state here.
 */
@Composable
fun BtPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    busy: Boolean = false,
    message: BtMessage? = null,
    closeLabel: String? = null,
    searchQuery: String? = null,
    searchLabel: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PickerSheetScaffold(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        subtitle = subtitle,
        busy = busy,
        message = message,
        closeLabel = closeLabel,
        searchQuery = searchQuery,
        searchLabel = searchLabel,
        onSearchQueryChange = onSearchQueryChange,
    ) { bodyModifier ->
        Column(
            modifier = bodyModifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            content = content,
        )
    }
}

/**
 * [BtPickerSheet] for a list too long to compose eagerly.
 *
 * Same chrome, same rows, same semantics — the body is a [LazyColumn] instead of
 * a scrolling [Column]. That is the whole difference, and it is not a style
 * choice: the timezone picker offers every IANA zone (400+ rows), and the
 * eager sheet would build every one of them on open, on the frame the user
 * tapped. Anything short enough to measure at once should keep using
 * [BtPickerSheet], which composes in the caller's `ColumnScope` and is simpler.
 *
 * It is a separate function rather than a flag because the two bodies take
 * genuinely different content lambdas — `ColumnScope` vs [LazyListScope] — and
 * an overload pair distinguished only by a trailing lambda's receiver is exactly
 * the call site where Kotlin's resolution gets ambiguous.
 */
@Composable
fun BtLazyPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    busy: Boolean = false,
    message: BtMessage? = null,
    closeLabel: String? = null,
    searchQuery: String? = null,
    searchLabel: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    PickerSheetScaffold(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        subtitle = subtitle,
        busy = busy,
        message = message,
        closeLabel = closeLabel,
        searchQuery = searchQuery,
        searchLabel = searchLabel,
        onSearchQueryChange = onSearchQueryChange,
    ) { bodyModifier ->
        LazyColumn(
            modifier = bodyModifier,
            // Hoistable so a long picker can OPEN on the row it has ticked. A
            // list of 400 that marks the current value somewhere past the
            // horizon has marked nothing, and this family's whole argument for
            // the tick is that the user can see where they already are.
            state = state,
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            content = content,
        )
    }
}

/**
 * The chrome both picker sheets share: the sheet window, the title block, the
 * optional search field, the error, and the close button. [body] receives the
 * modifier that carries the height cap — it is handed over rather than applied
 * here because a `verticalScroll` and a `LazyColumn` want it in different places
 * in their own modifier chains.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    subtitle: String?,
    busy: Boolean,
    message: BtMessage?,
    closeLabel: String?,
    searchQuery: String?,
    searchLabel: String?,
    onSearchQueryChange: ((String) -> Unit)?,
    body: @Composable (Modifier) -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // A pick IS a write, so while one is in flight the sheet does not slide
        // away under the thumb. This gates the DRAG only, and refusing a drag is
        // a true no-op — the sheet simply stays expanded.
        confirmValueChange = { !busy },
    )
    // Capped well below full expansion: a sheet whose content fills (near-)max
    // height oscillates forever once the inner scroll has to hand a fling back
    // to the sheet's drag. Short lists — which is every picker in Settings
    // today — still wrap smaller than this.
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * BODY_HEIGHT_FRACTION).dp

    ModalBottomSheet(
        // Unconditional, and deliberately NOT the dialog's
        // `if (!busy) onDismiss()`. Scrim and back both HIDE the sheet before
        // this fires, so refusing here would leave an invisible modal window
        // composed over the app, still eating taps, with nothing left to bring
        // it back — a soft-lock a centre dialog could not produce because a
        // dialog has no hidden state. The refusal that mattered is kept where it
        // is safe: the drag (above) and the close button (below), and the write
        // itself runs on the SCREEN's scope, so it completes and reports either
        // way.
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        // §2 A1 / §1.4: a sheet is the top of the neutral ramp, not the card level.
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = bt.textMuted) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                // A ModalBottomSheet ships no content insets, so the padding
                // above is a content margin only — the close button would sit
                // behind a 3-button nav bar without this. The IME joins the
                // union rather than replacing it: `union` takes the LARGER of
                // the two per edge, so a sheet with the search keyboard up is
                // lifted clear of it, and the same sheet with the keyboard down
                // still clears the nav bar. Padding for both in sequence would
                // stack them and leave a keyboard-height hole.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            if (searchQuery != null && onSearchQueryChange != null) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = searchLabel?.let { { Text(it) } },
                    singleLine = true,
                    // A pick is a write, and while one is in flight every other
                    // control on the sheet stops responding. Re-filtering the
                    // list under an in-flight choice is no exception.
                    enabled = !busy,
                    colors = btFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(14.dp))
            body(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight),
            )
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                BtFormError(message)
            }
            if (closeLabel != null) {
                Spacer(Modifier.height(16.dp))
                BtSecondaryButton(
                    text = closeLabel,
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }
}

/**
 * One option inside a [BtPickerSheet].
 *
 * Selection is carried by three redundant signals — a [BtTheme] gold wash, a
 * gold hairline and a tick — because colour alone is not an accessible marker
 * and a tint alone was what the old dialog leaned on. The label deliberately
 * stays `textPrimary` in both states: see the component KDoc.
 *
 * The trailing 20dp box is claimed whether or not anything is drawn in it, so
 * the label column has the same width on every row and the list edge does not
 * move when the selection does.
 *
 * @param onClick null makes the row non-tappable while KEEPING its
 *   `selected` semantics — which is what the current value needs. Announcing it
 *   as *disabled* (the obvious alternative) would be a lie: it is not disabled,
 *   it is already chosen.
 * @param pending draws this row's own spinner in the trailing slot. The picker
 *   family has no Save button, so the row the user touched is the only honest
 *   place to say "this is being written".
 */
@Composable
fun BtPickerRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    pending: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    // Aliased because `selected` is also a SemanticsPropertyReceiver extension —
    // the same rename `ProfileIconCell` makes, for the same reason.
    val isSelected = selected
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .then(
                if (onClick != null) {
                    Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
                } else {
                    Modifier.semantics {
                        role = Role.RadioButton
                        this.selected = isSelected
                    }
                },
            ),
        shape = BtShapes.card,
        color = if (selected) bt.goldWash else Color.Transparent,
        contentColor = bt.textPrimary,
        // Only the selection is outlined. Giving every row an edge would rebuild
        // the wall of boxes R2 spent a whole pass removing, and the one row that
        // matters would stop being the only marked thing on the sheet.
        border = if (selected) BorderStroke(1.dp, bt.goldEdge) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = bt.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(TRAILING_SLOT), contentAlignment = Alignment.Center) {
                when {
                    pending -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = bt.goldInk,
                        strokeWidth = 2.dp,
                    )

                    selected -> Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = bt.goldEmphasis,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** One choice in a [BtChoiceSheet]: the wire value, what to call it, and an optional aside. */
@Immutable
data class BtPickerOption(
    val value: String,
    val label: String,
    val supporting: String? = null,
)

/**
 * The single-select list picker — [BtPickerSheet] plus one [BtPickerRow] per
 * option, and the direct replacement for Settings' old `SettingsChoiceDialog`.
 *
 * The already-selected row is not tappable, exactly as before: re-picking the
 * current value would spend a round trip to arrive where it already is.
 */
@Composable
fun BtChoiceSheet(
    title: String,
    options: List<BtPickerOption>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    busy: Boolean = false,
    message: BtMessage? = null,
    closeLabel: String? = null,
) {
    val haptics = rememberBtHaptics()
    // Which row the user touched, so the spinner can sit ON it. Cleared when the
    // write settles either way — on success the caller usually dismisses, on
    // failure the sheet stays open and the row must go back to being tappable.
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(busy) { if (!busy) pending = null }

    BtPickerSheet(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        subtitle = subtitle,
        busy = busy,
        message = message,
        closeLabel = closeLabel,
    ) {
        btPickerOptionsWithSelected(options, selected).forEach { option ->
            val isSelected = option.value == selected
            BtPickerRow(
                label = option.label,
                supporting = option.supporting,
                selected = isSelected,
                pending = busy && option.value == pending,
                onClick = if (busy || isSelected) {
                    null
                } else {
                    {
                        // Picking IS the confirm, so it gets the confirm tick —
                        // the same haptic a Save button would have fired.
                        haptics.confirm()
                        pending = option.value
                        onPick(option.value)
                    }
                },
            )
        }
    }
}

/**
 * The options a picker should actually render, given the value the server says
 * is current.
 *
 * A closed choice set is closed *as of this build*. When the account carries a
 * value this APK has never heard of — a currency added server-side, a mode from
 * a newer platform — the honest picker shows it, ticked, at the end of the list
 * rather than rendering a list in which nothing is selected and quietly implying
 * the setting is unset. That is the same rule the retired `visibilityLabelRes`
 * followed with its em dash: never name a setting the user does not have, and
 * never hide one they do.
 *
 * The synthesised entry uses the wire value as its own label, because there is
 * nothing else to call it. It is never *pickable* — [BtChoiceSheet] refuses taps
 * on the current value — so this can only ever add information.
 */
fun btPickerOptionsWithSelected(
    options: List<BtPickerOption>,
    selected: String?,
): List<BtPickerOption> {
    val current = selected?.trim().orEmpty()
    if (current.isEmpty() || options.any { it.value == current }) return options
    return options + BtPickerOption(value = current, label = current)
}

/** 48dp is the floor; 56 is what a list of one-line choices wants. */
private val ROW_MIN_HEIGHT = 56.dp

/** Gap between option rows — the same in both sheet bodies, so it is stated once. */
private val ROW_GAP = 4.dp

/** Tick / spinner slot — claimed on every row so the label column never shifts. */
private val TRAILING_SLOT = 20.dp

/** Share of the viewport the scrollable body may take. See [BtPickerSheet]. */
private const val BODY_HEIGHT_FRACTION = 0.55f
