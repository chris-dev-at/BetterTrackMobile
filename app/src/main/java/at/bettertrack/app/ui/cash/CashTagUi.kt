package at.bettertrack.app.ui.cash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.CashSystemTagKeys
import at.bettertrack.app.data.db.CashTagEntity
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Shared rendering primitives for cash tags (V5 S2c).
 *
 * A tag is a **name plus a server-chosen tint**, and the tint is the whole point
 * of the affordance: a ledger row carrying three tags has to be scannable at a
 * glance, which a row of identical grey chips is not. But the palette comes from
 * the platform (nine seeded system tints plus whatever a user picks on the web),
 * so it is arbitrary colour arriving over the wire into a dark, gold-accented
 * app — it cannot be trusted as a background or as text.
 *
 * Hence the shape used everywhere: the tag's colour renders as a small filled
 * DOT, and the chip itself stays the app's own surface/border/text treatment.
 * The colour identifies, the app's palette styles. That keeps an unreadable
 * user-picked tint (near-black, near-background) from ever producing an
 * illegible label, and it makes tag chips sit visually beside the existing
 * source badges instead of fighting them.
 */

/**
 * Parse a `#RRGGBB` wire tint into a Compose [Color].
 *
 * Tolerant on purpose: the value is server data rendered inside a list, so a
 * malformed string must degrade to a neutral dot rather than take down the row.
 * Accepts an optional leading `#` and both `RRGGBB` and `AARRGGBB`.
 */
fun parseTagColor(raw: String?, fallback: Color): Color {
    val hex = raw?.trim()?.removePrefix("#") ?: return fallback
    if (hex.length != 6 && hex.length != 8) return fallback
    val value = hex.toLongOrNull(16) ?: return fallback
    return if (hex.length == 6) Color(value or 0xFF000000L) else Color(value)
}

/** The tint choices offered when creating or recolouring a tag. */
val CashTagPalette: List<String> = listOf(
    "#ef4444", "#f97316", "#f59e0b", "#eab308",
    "#22c55e", "#10b981", "#14b8a6", "#06b6d4",
    "#6366f1", "#8b5cf6", "#a855f7", "#ec4899",
    "#64748b", "#94a3b8",
)

/**
 * One tag chip: colour dot + name.
 *
 * [onClick] is optional so the same chip serves a read-only ledger row and a
 * tappable picker without two near-identical composables drifting apart.
 * [selected] gives the picker its state without a second chip type.
 */
@Composable
fun CashTagChip(
    name: String,
    color: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    Surface(
        modifier = modifier,
        shape = BtShapes.pill,
        color = if (selected) bt.goldSurface else bt.surface,
        contentColor = if (selected) bt.textPrimary else bt.textSecondary,
        border = BorderStroke(1.dp, if (selected) bt.gold else bt.border),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(parseTagColor(color, bt.tagFallback)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The tag chips for one ledger row, resolved from the movement's stored tag ids.
 *
 * Renders NOTHING when the movement has no tags — an "untagged" row must not
 * gain an empty strip of chrome, since most rows in a real ledger are untagged.
 * Ids the catalog cannot resolve are dropped rather than shown as raw UUIDs:
 * the cache refreshes on the next load, and a UUID in a ledger row is worse than
 * a missing chip.
 *
 * [max] caps the visible chips so one over-tagged movement cannot push the
 * amount off a narrow row; the remainder collapses into a "+N" chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CashTagChipRow(
    tagIds: List<String>,
    tagsById: Map<String, CashTagEntity>,
    modifier: Modifier = Modifier,
    max: Int = 3,
) {
    if (tagIds.isEmpty()) return
    val resolved = tagIds.mapNotNull { tagsById[it] }
    if (resolved.isEmpty()) return
    val shown = resolved.take(max)
    val overflow = resolved.size - shown.size
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { CashTagChip(name = cashTagDisplayName(it), color = it.color) }
        if (overflow > 0) {
            val bt = BtTheme.colors
            Surface(
                shape = BtShapes.pill,
                color = bt.surface,
                contentColor = bt.textMuted,
                border = BorderStroke(1.dp, bt.border),
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ── Built-in tag names: what the user READS vs what the server STORES ───────

/**
 * The localized display name of a built-in tag identity, or null for a user tag
 * and for a `systemKey` this build has never heard of.
 *
 * ## Why this exists (device QA 2026-09-01, defect #10)
 *
 * A German ledger row showed the chip **"Withdrawal"** beside German ones. It was
 * not the cash-KIND map failing — that one is complete and correct, and it renders
 * the row's title ("Bezahlt"). The chip is a TAG, and a tag's name is server data:
 * the platform seeds nine built-in tags with English names, so every account gets
 * an English chip in a German list, and no amount of app-side vocabulary work
 * touches it because nothing in the app was allowed to.
 *
 * The stable identity is [at.bettertrack.app.data.api.dto.CashSystemTagKeys], not
 * the name, which is exactly what makes a display-side translation safe: the app
 * shows German, the wire keeps English, the auto-tag engine keys off neither.
 *
 * Distinct from `CASH_SYSTEM_TAG_DEFAULT_NAMES`, which is the WRITE path (restore
 * writes the canonical English name back over the wire) and must stay English —
 * a German name PATCHed from this phone would follow the account to the web.
 */
@StringRes
fun cashSystemTagNameRes(systemKey: String?): Int? = when (systemKey) {
    CashSystemTagKeys.INVESTMENT -> R.string.bt_tags_builtin_name_investment
    CashSystemTagKeys.SALE_PROCEEDS -> R.string.bt_tags_builtin_name_sale_proceeds
    CashSystemTagKeys.DIVIDEND -> R.string.bt_tags_builtin_name_dividend
    CashSystemTagKeys.INTEREST -> R.string.bt_tags_builtin_name_interest
    CashSystemTagKeys.FEES -> R.string.bt_tags_builtin_name_fees
    CashSystemTagKeys.TAX -> R.string.bt_tags_builtin_name_tax
    CashSystemTagKeys.TRANSFER -> R.string.bt_tags_builtin_name_transfer
    CashSystemTagKeys.DEPOSIT -> R.string.bt_tags_builtin_name_deposit
    CashSystemTagKeys.WITHDRAWAL -> R.string.bt_tags_builtin_name_withdrawal
    else -> null
}

/**
 * What a tag chip says: the localized built-in name when the tag is a seeded one
 * the user has NOT renamed, and the stored name otherwise.
 *
 * The rename check ([cashSystemTagIsAtDefault]) is the load-bearing half. A user
 * who deliberately renamed the "Fees" tag to "Broker-Kosten" must keep seeing
 * their own word — replacing it with "Gebühren" would silently overrule a choice
 * they made, which is a worse bug than the English one this fixes.
 *
 * The tags MANAGEMENT screen deliberately does not use this: that screen is about
 * the stored name (it renames it, restores it, and prints the canonical default
 * beside it), and showing a translation where a wire value is being edited would
 * make the rename field disagree with its own row.
 */
@Composable
fun cashTagDisplayName(tag: CashTagEntity): String {
    if (!tag.system) return tag.name
    if (!cashSystemTagIsAtDefault(tag)) return tag.name
    val res = cashSystemTagNameRes(tag.systemKey) ?: return tag.name
    return stringResource(res)
}
