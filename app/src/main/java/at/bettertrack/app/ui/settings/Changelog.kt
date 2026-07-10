package at.bettertrack.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.btPressScale
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * One highlight in an update: a short feature [name] plus a one-line, plain-
 * language [description] — both string-resource ids so the whole "What's new"
 * is localized (EN + DE), never hardcoded English.
 */
data class WhatsNewFeature(
    @get:StringRes val name: Int,
    @get:StringRes val description: Int,
)

/**
 * One shipped update's user-visible highlights. [version] is a short display
 * label (e.g. "0.15") — a coarse feature-wave tag, not the exact build (Settings
 * → About shows the installed version). [features] read like consumer release
 * notes: what you can now do, in plain language, no technical detail.
 */
data class WhatsNewEntry(
    val version: String,
    val features: List<WhatsNewFeature>,
)

/**
 * The bundled in-app "What's new" (owner rework 2026-07-10), **newest first**,
 * shown in Settings → New features. No server dependency and no hardcoded copy:
 * every line is a string resource so EN + DE stay complete.
 *
 * To publish a new update's notes: add ONE [WhatsNewEntry] at the TOP of this
 * list and its handful of `bt_wn_*` strings to `values/` + `values-de/`. The
 * screen shows the newest [WHATS_NEW_VISIBLE_COUNT] and tucks the rest behind
 * "Show more" automatically.
 */
val BT_WHATS_NEW: List<WhatsNewEntry> = listOf(
    WhatsNewEntry(
        version = "0.15",
        features = listOf(
            WhatsNewFeature(R.string.bt_wn_0_15_switcher_name, R.string.bt_wn_0_15_switcher_desc),
            WhatsNewFeature(R.string.bt_wn_0_15_chart_name, R.string.bt_wn_0_15_chart_desc),
            WhatsNewFeature(R.string.bt_wn_0_15_smooth_name, R.string.bt_wn_0_15_smooth_desc),
            WhatsNewFeature(R.string.bt_wn_0_15_orient_name, R.string.bt_wn_0_15_orient_desc),
        ),
    ),
    WhatsNewEntry(
        version = "0.14",
        features = listOf(
            WhatsNewFeature(R.string.bt_wn_0_14_conglo_name, R.string.bt_wn_0_14_conglo_desc),
            WhatsNewFeature(R.string.bt_wn_0_14_custom_name, R.string.bt_wn_0_14_custom_desc),
            WhatsNewFeature(R.string.bt_wn_0_14_updates_name, R.string.bt_wn_0_14_updates_desc),
        ),
    ),
    WhatsNewEntry(
        version = "0.13",
        features = listOf(
            WhatsNewFeature(R.string.bt_wn_0_13_datelink_name, R.string.bt_wn_0_13_datelink_desc),
            WhatsNewFeature(R.string.bt_wn_0_13_selectall_name, R.string.bt_wn_0_13_selectall_desc),
            WhatsNewFeature(R.string.bt_wn_0_13_backdate_name, R.string.bt_wn_0_13_backdate_desc),
        ),
    ),
    WhatsNewEntry(
        version = "0.12",
        features = listOf(
            WhatsNewFeature(R.string.bt_wn_0_12_friendpages_name, R.string.bt_wn_0_12_friendpages_desc),
            WhatsNewFeature(R.string.bt_wn_0_12_sharedby_name, R.string.bt_wn_0_12_sharedby_desc),
            WhatsNewFeature(R.string.bt_wn_0_12_sharing_name, R.string.bt_wn_0_12_sharing_desc),
            WhatsNewFeature(R.string.bt_wn_0_12_watchlists_name, R.string.bt_wn_0_12_watchlists_desc),
            WhatsNewFeature(R.string.bt_wn_0_12_messages_name, R.string.bt_wn_0_12_messages_desc),
        ),
    ),
    WhatsNewEntry(
        version = "0.11",
        features = listOf(
            WhatsNewFeature(R.string.bt_wn_0_11_password_name, R.string.bt_wn_0_11_password_desc),
            WhatsNewFeature(R.string.bt_wn_0_11_twofa_name, R.string.bt_wn_0_11_twofa_desc),
            WhatsNewFeature(R.string.bt_wn_0_11_sessions_name, R.string.bt_wn_0_11_sessions_desc),
            WhatsNewFeature(R.string.bt_wn_0_11_delete_name, R.string.bt_wn_0_11_delete_desc),
            WhatsNewFeature(R.string.bt_wn_0_11_german_name, R.string.bt_wn_0_11_german_desc),
        ),
    ),
    WhatsNewEntry(
        version = "0.10",
        features = listOf(
            WhatsNewFeature(R.string.bt_wn_0_10_pin_name, R.string.bt_wn_0_10_pin_desc),
            WhatsNewFeature(R.string.bt_wn_0_10_biometric_name, R.string.bt_wn_0_10_biometric_desc),
            WhatsNewFeature(R.string.bt_wn_0_10_btpin_name, R.string.bt_wn_0_10_btpin_desc),
        ),
    ),
    WhatsNewEntry(
        version = "0.09",
        features = listOf(
            WhatsNewFeature(R.string.bt_wn_0_09_overview_name, R.string.bt_wn_0_09_overview_desc),
            WhatsNewFeature(R.string.bt_wn_0_09_offline_name, R.string.bt_wn_0_09_offline_desc),
            WhatsNewFeature(R.string.bt_wn_0_09_research_name, R.string.bt_wn_0_09_research_desc),
        ),
    ),
)

/** Newest updates always shown before "Show more" reveals the full history. */
const val WHATS_NEW_VISIBLE_COUNT = 5

/** The newest-shown / behind-"Show more" partition of the changelog. */
data class WhatsNewSplit(
    val visible: List<WhatsNewEntry>,
    val hidden: List<WhatsNewEntry>,
)

/**
 * Split the changelog into the newest [visibleCount] entries (always shown) and
 * the older remainder revealed behind "Show more". Pure + unit-tested — the
 * screen renders exactly this partition, so the last-5/Show-more rule is proven
 * without a UI test. Negative counts clamp to 0; counts past the end simply show
 * everything with nothing hidden.
 */
fun splitWhatsNew(
    entries: List<WhatsNewEntry>,
    visibleCount: Int = WHATS_NEW_VISIBLE_COUNT,
): WhatsNewSplit {
    val safe = visibleCount.coerceAtLeast(0)
    return WhatsNewSplit(visible = entries.take(safe), hidden = entries.drop(safe))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val split = remember { splitWhatsNew(BT_WHATS_NEW) }
    // Collapsed is the default every time the screen opens (a fresh nav entry
    // gets fresh saveable state); rememberSaveable only guards a rotation.
    var expanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bt_settings_whatsnew_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(split.visible, key = { it.version }) { entry ->
                WhatsNewCard(entry)
            }
            if (split.hidden.isNotEmpty()) {
                if (expanded) {
                    items(split.hidden, key = { it.version }) { entry ->
                        WhatsNewCard(entry)
                    }
                }
                item(key = "whatsnew_toggle") {
                    ShowMoreButton(expanded = expanded, onToggle = { expanded = !expanded })
                }
            }
        }
    }
}

@Composable
private fun WhatsNewCard(entry: WhatsNewEntry) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // Version tag — a small gold pill so each update reads as a header.
            Surface(
                shape = BtShapes.pill,
                color = bt.gold.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.45f)),
            ) {
                Text(
                    text = stringResource(R.string.bt_settings_whatsnew_version, entry.version),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = bt.goldEmphasis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            entry.features.forEachIndexed { index, feature ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    // Small gold bullet — a check-style dot without an extra icon dep.
                    Surface(
                        shape = CircleShape,
                        color = bt.gold,
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(5.dp),
                    ) {}
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(feature.name),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = bt.textPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(feature.description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = bt.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The "Show more" / "Show less" affordance: a centered, bordered pill with a
 * 48dp target and [btPressScale] press feedback (reduced-motion aware via the
 * shared modifier). A simple, snappy expand — no content animation to keep the
 * older history one instant tap away.
 */
@Composable
private fun ShowMoreButton(expanded: Boolean, onToggle: () -> Unit) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onToggle,
            interactionSource = interaction,
            shape = BtShapes.pill,
            color = bt.surface,
            border = BorderStroke(1.dp, bt.border),
            modifier = Modifier.btPressScale(interaction),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.bt_settings_whatsnew_show_less
                        else R.string.bt_settings_whatsnew_show_more,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = bt.textSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp
                    else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = bt.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
