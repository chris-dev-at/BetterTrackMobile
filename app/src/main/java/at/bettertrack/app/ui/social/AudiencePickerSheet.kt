package at.bettertrack.app.ui.social

import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * The §6.9 sharing sheet with the friction ladder replicated EXACTLY:
 *  - **Private**: no friction.
 *  - **Specific friends** (multi-select): no friction (light).
 *  - **All friends**: a light, non-blocking confirm line.
 *  - **Public link**: a strong, BLOCKING acknowledgment — the action can't fire
 *    until the "I understand…" box is ticked — then the Android share-sheet opens
 *    with the link.
 *
 * [advancedEnabled] gates the two app-ahead tiers (Specific friends / Public
 * link): in release they render as calm "Coming soon" rows the user can't pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiencePickerSheet(
    itemName: String,
    kind: ShareableKind,
    currentAudience: ShareAudience,
    friends: List<Friend>,
    advancedEnabled: Boolean,
    publicLink: String,
    busy: Boolean,
    onApply: (ShareAudience) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selected by remember { mutableStateOf(currentAudience) }
    var ack by remember { mutableStateOf(false) }
    var selectedFriends by remember { mutableStateOf(setOf<String>()) }

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
            Text(
                text = "Share",
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
            Text(
                text = itemName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Choose who can see this ${kind.label()}.",
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            Spacer(Modifier.height(16.dp))

            AudienceOption(
                icon = Icons.Outlined.Lock,
                title = "Private",
                subtitle = "Only you",
                selected = selected == ShareAudience.Private,
                enabled = true,
                onClick = { selected = ShareAudience.Private; ack = false },
            )
            Spacer(Modifier.height(8.dp))
            AudienceOption(
                icon = Icons.Outlined.People,
                title = "Specific friends",
                subtitle = "Pick exactly who can see it",
                selected = selected == ShareAudience.SpecificFriends,
                enabled = advancedEnabled,
                comingSoon = !advancedEnabled,
                onClick = { selected = ShareAudience.SpecificFriends; ack = false },
            )
            // Specific-friends multi-select appears inline when chosen.
            if (selected == ShareAudience.SpecificFriends && advancedEnabled) {
                Spacer(Modifier.height(8.dp))
                if (friends.isEmpty()) {
                    HintCard("Add friends first — then you can pick exactly who sees this.")
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp),
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
                title = "All friends",
                subtitle = "Everyone you're friends with can view it",
                selected = selected == ShareAudience.AllFriends,
                enabled = true,
                onClick = { selected = ShareAudience.AllFriends; ack = false },
            )
            // Light, non-blocking confirm for all-friends.
            if (selected == ShareAudience.AllFriends) {
                Spacer(Modifier.height(8.dp))
                HintCard("Your friends will see this ${kind.label()} in “Shared with me”. You can revoke access any time.")
            }
            Spacer(Modifier.height(8.dp))
            AudienceOption(
                icon = Icons.Outlined.Link,
                title = "Public link",
                subtitle = "Anyone with the link can view it",
                selected = selected == ShareAudience.PublicLink,
                enabled = advancedEnabled,
                comingSoon = !advancedEnabled,
                onClick = { selected = ShareAudience.PublicLink; ack = false },
            )
            // Strong BLOCKING acknowledgment for public.
            if (selected == ShareAudience.PublicLink && advancedEnabled) {
                Spacer(Modifier.height(10.dp))
                PublicAcknowledgment(checked = ack, onToggle = { ack = !ack })
            }

            Spacer(Modifier.height(20.dp))

            val isPublic = selected == ShareAudience.PublicLink
            val canApply = when {
                busy -> false
                isPublic -> ack
                else -> true
            }
            BtPrimaryButton(
                text = if (isPublic) "Create link & share" else "Apply",
                onClick = {
                    if (isPublic) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, publicLink)
                            putExtra(Intent.EXTRA_SUBJECT, itemName)
                        }
                        context.startActivity(Intent.createChooser(send, "Share link"))
                    }
                    onApply(selected)
                },
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
    enabled: Boolean,
    comingSoon: Boolean = false,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val container = if (selected) bt.gold.copy(alpha = 0.12f) else bt.bg
    val border = if (selected) bt.gold.copy(alpha = 0.5f) else bt.border
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
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
                tint = if (!enabled) bt.textMuted else if (selected) bt.goldEmphasis else bt.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) bt.textPrimary else bt.textMuted,
                    )
                    if (comingSoon) {
                        Spacer(Modifier.width(8.dp))
                        BtBadge(text = "Coming soon", kind = BtBadgeKind.Neutral)
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
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
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = bt.loss, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "This becomes public",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.lossSoft,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Anyone with the link can see your holdings and net worth — even people who aren't your friends. You can revoke the link any time.",
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
                        "I understand and want a public link",
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

private fun ShareableKind.label(): String = when (this) {
    ShareableKind.Portfolio -> "portfolio"
    ShareableKind.Watchlist -> "watchlist"
    ShareableKind.Conglomerate -> "conglomerate"
}
