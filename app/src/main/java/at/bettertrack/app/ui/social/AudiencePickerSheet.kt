package at.bettertrack.app.ui.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Group
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.ShareableKind
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The §16 sharing sheet with the friction ladder — now fully LIVE against the
 * unified audience model (`PUT /social/audience/:kind/:subjectId`):
 *  - **Private**: no friction.
 *  - **Specific friends** (multi-select): pick exactly who; seeded from the item's
 *    current `friendIds`.
 *  - **All friends**: a light, non-blocking confirm line.
 *  - **Public link**: a strong, BLOCKING acknowledgment (`acknowledgePublic`) — the
 *    action can't fire until "I understand…" is ticked. The token is minted
 *    server-side and surfaced ONCE by the caller after apply.
 *
 * The sheet only chooses; [onApply] hands the caller the audience + friendIds +
 * ack so the repository call and the one-time link reveal live in the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiencePickerSheet(
    itemName: String,
    kind: ShareableKind,
    currentAudience: ShareAudience,
    friends: List<Friend>,
    initialFriendIds: Set<String>,
    linkActive: Boolean,
    busy: Boolean,
    onApply: (audience: ShareAudience, friendIds: Set<String>, acknowledge: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selected by remember { mutableStateOf(currentAudience) }
    var ack by remember { mutableStateOf(false) }
    var selectedFriends by remember { mutableStateOf(initialFriendIds) }

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
                .padding(bottom = 24.dp),
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
                icon = Icons.Outlined.Group,
                title = stringResource(R.string.bt_social_audience_all_friends),
                subtitle = stringResource(R.string.bt_social_audience_all_friends_sub),
                selected = selected == ShareAudience.AllFriends,
                onClick = { selected = ShareAudience.AllFriends; ack = false },
            )
            if (selected == ShareAudience.AllFriends) {
                Spacer(Modifier.height(8.dp))
                HintCard(
                    stringResource(
                        when (kind) {
                            ShareableKind.Portfolio -> R.string.bt_social_hint_all_friends_portfolio
                            ShareableKind.Watchlist -> R.string.bt_social_hint_all_friends_watchlist
                            ShareableKind.Conglomerate -> R.string.bt_social_hint_all_friends_conglomerate
                        },
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            AudienceOption(
                icon = Icons.Outlined.Link,
                title = stringResource(R.string.bt_social_audience_public_link_title),
                subtitle = if (linkActive) stringResource(R.string.bt_social_audience_public_link_sub_active) else stringResource(R.string.bt_social_audience_public_link_sub_inactive),
                selected = selected == ShareAudience.PublicLink,
                onClick = { selected = ShareAudience.PublicLink; ack = false },
            )
            if (selected == ShareAudience.PublicLink) {
                Spacer(Modifier.height(10.dp))
                if (linkActive && currentAudience == ShareAudience.PublicLink) {
                    HintCard(
                        stringResource(
                            when (kind) {
                                ShareableKind.Portfolio -> R.string.bt_social_hint_public_active_portfolio
                                ShareableKind.Watchlist -> R.string.bt_social_hint_public_active_watchlist
                                ShareableKind.Conglomerate -> R.string.bt_social_hint_public_active_conglomerate
                            },
                        ),
                    )
                } else {
                    PublicAcknowledgment(checked = ack, onToggle = { ack = !ack })
                }
            }

            Spacer(Modifier.height(20.dp))

            val isPublic = selected == ShareAudience.PublicLink
            val alreadyPublic = isPublic && linkActive && currentAudience == ShareAudience.PublicLink
            val canApply = when {
                busy -> false
                isPublic && !alreadyPublic -> ack
                selected == ShareAudience.SpecificFriends -> selectedFriends.isNotEmpty()
                else -> true
            }
            BtPrimaryButton(
                text = when {
                    isPublic && !alreadyPublic -> stringResource(R.string.bt_social_create_public_link)
                    else -> stringResource(R.string.bt_social_apply)
                },
                onClick = { onApply(selected, selectedFriends, ack) },
                enabled = canApply,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )
        }
    }
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
    val container = if (selected) bt.gold.copy(alpha = 0.12f) else bt.bg
    val border = if (selected) bt.gold.copy(alpha = 0.5f) else bt.border
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

@Composable
private fun HintCard(text: String) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.card,
        color = bt.gold.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.25f)),
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

@Composable
private fun PublicAcknowledgment(checked: Boolean, onToggle: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.card,
        color = bt.loss.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, bt.loss.copy(alpha = 0.40f)),
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
                            uncheckedColor = bt.loss.copy(alpha = 0.6f),
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
        BtAvatar(name = friend.username, size = 32.dp)
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
