package at.bettertrack.app.ui.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.FriendGroup
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.ShareableKind
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The §16 sharing sheet with the friction ladder — now fully LIVE against the
 * unified audience model (`PUT /social/audience/:kind/:subjectId`):
 *  - **Private**: no friction.
 *  - **Specific friends** (multi-select): pick exactly who; seeded from the item's
 *    current `friendIds`.
 *  - **Group** (single-select, V5): one named set of friends. A share targets
 *    exactly ONE group — the wire carries a scalar `groupId`, not a list — and the
 *    membership is resolved at read time, so the rung says out loud that adding
 *    someone to the group later shares the item with them too.
 *  - **All friends**: a light, non-blocking confirm line.
 *  - **Public link**: a strong, BLOCKING acknowledgment (`acknowledgePublic`) — the
 *    action can't fire until "I understand…" is ticked, **every time**, including
 *    when the item is already public. The token is minted server-side and
 *    surfaced ONCE by the caller after apply.
 *
 * The rungs render in [ShareAudience] declaration order, which is the ladder's
 * own order of increasing exposure.
 *
 * The friction itself is NOT written inline here: which rung needs the tick is
 * [audienceRequiresPublicAcknowledgment] and whether Apply may fire at all is
 * [audienceApplyAllowed] — both pure, both unit-tested rung by rung against the
 * web picker's own `canSubmit`, so the ladder's guarantee is reviewable without
 * reading a Composable.
 *
 * The sheet only chooses; [onApply] hands the caller the audience + friendIds +
 * groupId + ack so the repository call and the one-time link reveal live in the
 * ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiencePickerSheet(
    itemName: String,
    kind: ShareableKind,
    currentAudience: ShareAudience,
    friends: List<Friend>,
    initialFriendIds: Set<String>,
    groups: List<FriendGroup>,
    initialGroupId: String?,
    linkActive: Boolean,
    busy: Boolean,
    onApply: (audience: ShareAudience, friendIds: Set<String>, groupId: String?, acknowledge: Boolean) -> Unit,
    onOpenGroups: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selected by remember { mutableStateOf(currentAudience) }
    var ack by remember { mutableStateOf(false) }
    var selectedFriends by remember { mutableStateOf(initialFriendIds) }
    // A group that no longer exists must not survive as a stale selection — the
    // server would answer GROUP_AUDIENCE_INVALID for a foreign id.
    var selectedGroup by remember {
        mutableStateOf(initialGroupId?.takeIf { id -> groups.any { it.id == id } })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                // A ModalBottomSheet ships no content insets, so the 24dp above is
                // a content margin only — the Apply button at the end of this
                // scroll would sit behind a 3-button nav bar without this.
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.bt_social_share_label), style = MaterialTheme.typography.labelMedium, color = bt.textMuted)
            Text(
                text = itemName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    when (kind) {
                        ShareableKind.Portfolio -> R.string.bt_social_share_choose_portfolio
                        ShareableKind.Watchlist -> R.string.bt_social_share_choose_watchlist
                        ShareableKind.Conglomerate -> R.string.bt_social_share_choose_conglomerate
                        // V5: an idea is a saved workboard analysis — a name, the
                        // thesis its author wrote, and the backtest behind it.
                        ShareableKind.Idea -> R.string.bt_share_choose_idea
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            Spacer(Modifier.height(16.dp))

            AudienceOption(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.bt_social_audience_private),
                subtitle = stringResource(R.string.bt_social_audience_private_sub),
                selected = selected == ShareAudience.Private,
                onClick = { selected = ShareAudience.Private; ack = false },
            )
            Spacer(Modifier.height(8.dp))
            AudienceOption(
                icon = Icons.Outlined.People,
                title = stringResource(R.string.bt_social_audience_specific_title),
                subtitle = stringResource(R.string.bt_social_audience_specific_sub),
                selected = selected == ShareAudience.SpecificFriends,
                onClick = { selected = ShareAudience.SpecificFriends; ack = false },
            )
            if (selected == ShareAudience.SpecificFriends) {
                Spacer(Modifier.height(8.dp))
                if (friends.isEmpty()) {
                    HintCard(stringResource(R.string.bt_social_hint_add_friends_first))
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        friends.forEach { f ->
                            FriendCheckRow(
                                friend = f,
                                checked = f.userId in selectedFriends,
                                onToggle = {
                                    selectedFriends = if (f.userId in selectedFriends) {
                                        selectedFriends - f.userId
                                    } else {
                                        selectedFriends + f.userId
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            AudienceOption(
                icon = Icons.Outlined.Groups,
                title = stringResource(R.string.bt_groups_audience_title),
                subtitle = stringResource(R.string.bt_groups_audience_sub),
                selected = selected == ShareAudience.Group,
                onClick = { selected = ShareAudience.Group; ack = false },
            )
            if (selected == ShareAudience.Group) {
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    // Never a dead end: the rung explains what a group is for and
                    // hands over a way to make one.
                    HintCard(stringResource(R.string.bt_groups_audience_empty))
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.bt_groups_audience_pick),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        groups.forEach { g ->
                            GroupPickRow(
                                group = g,
                                selected = g.id == selectedGroup,
                                // Single-select: picking one replaces the other. The
                                // wire has room for exactly one group id.
                                onPick = { selectedGroup = g.id },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HintCard(stringResource(R.string.bt_groups_audience_live))
                }
            }
            // Hoisted out of the empty/non-empty split entirely, and gated by the
            // named rule rather than by whichever branch it sits in: it used to
            // live inside `groups.isEmpty()`, so the way to manage groups
            // vanished the moment you had one. See [audienceGroupManagementOffered].
            if (audienceGroupManagementOffered(selected, groups.size)) {
                Spacer(Modifier.height(8.dp))
                BtSecondaryButton(
                    text = stringResource(R.string.bt_groups_audience_goto),
                    onClick = onOpenGroups,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            AudienceOption(
                icon = Icons.Outlined.Group,
                title = stringResource(R.string.bt_social_audience_all_friends),
                subtitle = stringResource(R.string.bt_social_audience_all_friends_sub),
                selected = selected == ShareAudience.AllFriends,
                onClick = { selected = ShareAudience.AllFriends; ack = false },
            )
            if (selected == ShareAudience.AllFriends) {
                Spacer(Modifier.height(8.dp))
                // One sentence, not four. The `when (kind)` used to pick between
                // strings that differed only by the noun — and the option's own
                // title sits directly above this card, so the noun was restating
                // context the user already had.
                HintCard(stringResource(R.string.bt_social_hint_all_friends))
            }
            Spacer(Modifier.height(8.dp))
            AudienceOption(
                icon = Icons.Outlined.Link,
                title = stringResource(R.string.bt_social_audience_public_link_title),
                subtitle = if (linkActive) stringResource(R.string.bt_social_audience_public_link_sub_active) else stringResource(R.string.bt_social_audience_public_link_sub_inactive),
                selected = selected == ShareAudience.PublicLink,
                onClick = { selected = ShareAudience.PublicLink; ack = false },
            )
            // Two DIFFERENT questions, and conflating them was the bug: whether
            // the tick is owed (always, on this rung) and whether applying mints
            // a new link (only when one is not already live). The first gates,
            // the second only picks a word.
            val mintsLink = audienceMintsPublicLink(
                current = currentAudience,
                selected = selected,
                linkActive = linkActive,
            )

            if (selected == ShareAudience.PublicLink) {
                Spacer(Modifier.height(10.dp))
                // Kept from before: a live link is worth saying out loud, and the
                // way to switch it off is not obvious. It is now an ADDITION to
                // the acknowledgment rather than a replacement for it.
                if (!mintsLink) {
                    HintCard(stringResource(R.string.bt_social_hint_public_active))
                    Spacer(Modifier.height(8.dp))
                }
                PublicAcknowledgment(checked = ack, onToggle = { ack = !ack })
            }

            Spacer(Modifier.height(20.dp))

            val canApply = audienceApplyAllowed(
                selected = selected,
                acknowledged = ack,
                hasGroup = selectedGroup != null,
                busy = busy,
            )
            BtPrimaryButton(
                text = when {
                    mintsLink -> stringResource(R.string.bt_social_create_public_link)
                    else -> stringResource(R.string.bt_social_apply)
                },
                onClick = { onApply(selected, selectedFriends, selectedGroup, ack) },
                enabled = canApply,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )
        }
    }
}

// ── The friction ladder, as a rule rather than an expression ─────────────────

/**
 * Whether the group rung offers a way to **manage** friend groups.
 *
 * ## The bug this replaces
 *
 * The button lived inside the `groups.isEmpty()` branch, so it appeared only
 * while the user had zero groups and disappeared the instant they made one. That
 * is backwards on its face — group management is *more* useful once groups
 * exist, not less — and it left the sheet with no route to the groups screen for
 * everyone who actually uses the feature. Nothing was lost outright (People →
 * Friends → Groups is still a live door), but this rung became a dead end at
 * exactly the moment its subject became real.
 *
 * ## Why this is additive to the web rather than a copy of it
 *
 * The web's picker has **no group-management affordance at all**. Its group tier
 * renders the radio list, or — only when `groups.length === 0` — one line of
 * static prose:
 *
 * ```tsx
 * // apps/web/src/user/components/AudiencePicker.tsx:466-470
 * {audience === 'group' ? (
 *   <div className="flex flex-col gap-2">
 *     {groups.length === 0 ? (
 *       <p className="bt-meta">{t('sharing.groupsNone')}</p>
 * ```
 *
 * `sharing.groupsNone` (en.json:2954) reads *"You have no groups yet. Create one
 * on the Friends page to share with a circle."* — a sentence, not a link; the
 * file imports no router at all. So the web points at a destination and makes the
 * user find it, and it stops pointing once you have a group.
 *
 * The parity law binds the ladder's *gates* ([audienceApplyAllowed] is the web's
 * `canSubmit` clause for clause) — it does not require reproducing a navigation
 * gap. On the web the Friends page is one persistent nav item away from an
 * always-visible sidebar; on a phone this sheet is modal over a tab, there is no
 * address bar, and the groups screen is three levels back. The web's pointer has
 * to become a real control here or it is not a pointer.
 *
 * Pure and named so the condition is pinned by a unit test rather than by
 * whichever branch of an `if` a future edit happens to leave it in.
 */
@Suppress("UNUSED_PARAMETER")
internal fun audienceGroupManagementOffered(selected: ShareAudience, groupCount: Int): Boolean =
    // `groupCount` is accepted and deliberately NOT read. The defect was a rule
    // that read it; a signature blind to it would let the same mistake back in
    // without ever touching this function, and the test that pins "same answer
    // for 0 groups and for 5" needs the argument to pass in.
    selected == ShareAudience.Group

/**
 * Whether the public rung's **blocking acknowledgment** is owed.
 *
 * ## The contract this mirrors, and the one that does not exist
 *
 * A parity audit asked for this to match a platform contract named
 * `audienceTransitionRequiresConfirmation`. **That identifier exists in neither
 * codebase** — not in this repo (working tree, full git object database
 * including dangling objects, and every doc) and not in the platform source
 * (whole dev stack, `node_modules` included). The audit named something that was
 * never written. What is real is the web's own gate, and that is what this
 * mirrors:
 *
 * ```ts
 * // apps/web/src/user/components/AudiencePicker.tsx:259-264
 * const canSubmit =
 *   snapshotReady &&
 *   !mutation.isPending &&
 *   !(audience === 'public_link' && !acknowledged) &&
 *   // The group tier's friction: a group must actually be chosen to share.
 *   !(audience === 'group' && !groupId);
 * ```
 *
 * So the rule is a property of the **selected rung**, not of the transition:
 * `public_link` needs the tick, nothing else does. Private → all friends,
 * specific friends → all friends and group → all friends are all widenings that
 * apply on one tap in both clients — the web gives `all_friends` an informational
 * Alert (`AudiencePicker.tsx:489-491`) and no gate, which is exactly what §6.9's
 * "light confirm" asks for.
 *
 * ## No already-public exemption (the divergence this fixed)
 *
 * This app used to skip the tick when the item was *already* public with a live
 * link. The web has no such carve-out: it clears `acknowledged` on every
 * authoritative snapshot load — including one whose loaded audience already IS
 * `public_link` (`AudiencePicker.tsx:189`) — and `canSubmit` then demands the tick
 * again. Re-saving a public share is still a save that keeps holdings and net
 * worth world-readable, and the app was the looser of the two clients on the
 * ladder's most dangerous rung. Friction on this rung only ever goes up.
 */
internal fun audienceRequiresPublicAcknowledgment(selected: ShareAudience): Boolean =
    selected == ShareAudience.PublicLink

/**
 * Whether applying would **mint a new public link**, as opposed to re-saving one
 * that is already live.
 *
 * Wording only — this decides whether the button says "Create link & share" or
 * "Apply", and it must never be mistaken for the gate. The gate is
 * [audienceApplyAllowed], which asks for the tick either way.
 */
internal fun audienceMintsPublicLink(
    current: ShareAudience,
    selected: ShareAudience,
    linkActive: Boolean,
): Boolean =
    selected == ShareAudience.PublicLink &&
        !(linkActive && current == ShareAudience.PublicLink)

/**
 * Whether Apply may fire at all.
 *
 * Every clause is the web's `canSubmit` verbatim in Kotlin: not busy, the public
 * tick, and a group audience that actually names a group (which the server
 * answers `GROUP_AUDIENCE_INVALID` for). The rungs are mutually exclusive, so a
 * `when` chain and the web's conjunction decide identically.
 *
 * ## Why `specific_friends` has NO gate (coordinator ruling, 2026-08-08)
 *
 * This client briefly required that rung to name at least one friend. The web
 * does not, and its own test asserts Save is enabled the moment the rung is
 * picked with nobody selected (`AudiencePicker.test.tsx:253-267`). That test
 * makes it a CONTRACT rather than an oversight, and the owner's parity law is
 * absolute: exactly the same as the web, or not at all.
 *
 * It is also harmless on inspection, which is why the contract reads the way it
 * does — "specific friends, none named" is an audience nobody is in, i.e. the
 * same exposure as private. There is nothing to protect the user from, so the
 * stricter client was buying no safety and costing a divergence.
 *
 * The web's fourth clause, `snapshotReady`, has no counterpart here and needs
 * none: the sheet is only composed once the caller holds the audience, friends
 * and groups it seeds from, so there is no stale-cache state to gate against.
 */
internal fun audienceApplyAllowed(
    selected: ShareAudience,
    acknowledged: Boolean,
    hasGroup: Boolean,
    busy: Boolean,
): Boolean = when {
    busy -> false
    audienceRequiresPublicAcknowledgment(selected) -> acknowledged
    selected == ShareAudience.Group -> hasGroup
    else -> true
}

@Composable
private fun AudienceOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val container = if (selected) bt.wash(bt.gold, 0.12f) else bt.bg
    val border = if (selected) bt.edge(bt.gold, 0.5f) else bt.border
    Surface(
        onClick = onClick,
        shape = BtShapes.card,
        color = container,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) bt.goldEmphasis else bt.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = bt.goldEmphasis, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * A gold advisory note inside the audience sheet.
 *
 * ## Tonal, not boxed
 *
 * This used to be a `gold @7%` fill wrapped in a `1dp gold @25%` ring. R2 moved
 * the app off border walls onto tonal steps ([BtGroup]) and R3 took the ring off
 * the state badge for the same reason: a tinted fill on a darker page is already
 * a boundary, and drawing a line around it is a second boundary doing the first
 * one's job.
 *
 * Losing the ring means the fill has to hold the shape by itself, so the tint
 * steps up (7% → 11%) — the *colour* is what says "advisory", and it says it
 * without an outline. The shape moves to `BtShapes.group` too: a hint is a block
 * of one subject, not a row competing for a tap, which is the distinction
 * [BtGroup]'s KDoc draws between the two containment tiers.
 */
@Composable
private fun HintCard(text: String) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.group,
        color = bt.wash(bt.gold, 0.11f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * The "this will be visible to anyone with the link" tick.
 *
 * Tonal for the same reason as [HintCard] — the red ring came off and the fill
 * stepped up (9% → 13%) to hold the shape without it. The `WarningAmber` glyph
 * keeps its full `loss` tint, so the severity still reads before any word does:
 * that is a colour carrying meaning, which is what R3 kept on the error state
 * badge when it dropped that badge's outline.
 */
@Composable
private fun PublicAcknowledgment(checked: Boolean, onToggle: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.group,
        color = bt.wash(bt.loss, 0.13f),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = bt.loss, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.bt_social_public_ack_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.lossSoft,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.bt_social_public_ack_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = bt.loss,
                            uncheckedColor = bt.edge(bt.loss, 0.6f),
                            checkmarkColor = bt.textPrimary,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.bt_social_public_ack_checkbox),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                    )
                }
            }
        }
    }
}

/**
 * One group in the single-select list. A [RadioButton] rather than a checkbox on
 * purpose: the control itself has to say "one of these", because the wire has
 * room for exactly one group id and a checkbox would promise otherwise.
 */
@Composable
private fun GroupPickRow(group: FriendGroup, selected: Boolean, onPick: () -> Unit) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Groups,
            contentDescription = null,
            tint = if (selected) bt.goldEmphasis else bt.textMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.bt_groups_member_count, group.memberCount, group.memberCount),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
        }
        RadioButton(
            selected = selected,
            onClick = onPick,
            colors = RadioButtonDefaults.colors(
                selectedColor = bt.gold,
                unselectedColor = bt.textMuted,
            ),
        )
    }
}

@Composable
private fun FriendCheckRow(friend: Friend, checked: Boolean, onToggle: () -> Unit) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BtAvatar(name = friend.username, iconId = friend.profileIcon, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "@${friend.username}",
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = bt.gold,
                uncheckedColor = bt.textMuted,
                checkmarkColor = bt.onGold,
            ),
        )
    }
}
