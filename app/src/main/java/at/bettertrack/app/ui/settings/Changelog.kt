package at.bettertrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * One shipped version's user-visible highlights. [version] is a short label
 * (e.g. "0.15"); [highlights] are the plain-language "what's new" lines.
 */
data class ChangelogEntry(
    val version: String,
    val highlights: List<String>,
)

/**
 * The bundled in-app changelog (owner request 2026-07-09): what each recent
 * version brought, **newest first**, shown in Settings → New features. There is
 * no server dependency — to publish a new version's notes, add ONE entry at the
 * TOP of this list. Seeded honestly from the actual recent user-visible changes.
 *
 * Note: version labels are the app's coarse feature-wave tags; the exact build
 * shown in Settings → About is the source of truth for the installed version.
 */
val BT_CHANGELOG: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        version = "0.15",
        highlights = listOf(
            "The current-portfolio selector now lives in the top bar, right beside the BetterTrack wordmark.",
            "The portfolio value graph blends full-width into the page as a hero chart.",
            "Conglomerate budget calculator: choose whole or fractional shares.",
            "This “New features” changelog, plus the app version, in Settings.",
            "When the app lock uses your BetterTrack account PIN, Change PIN is managed on the web.",
        ),
    ),
    ChangelogEntry(
        version = "0.14",
        highlights = listOf(
            "Transaction form: type a price to jump the date to the most recent day the asset traded there — the date ↔ price link now works both ways.",
            "Amount fields select all on focus, so tapping in and retyping is instant.",
            "Back-dated buys warn instead of blocking.",
        ),
    ),
    ChangelogEntry(
        version = "0.13",
        highlights = listOf(
            "Social v2: per-friend overviews, live sharing including public links, and named watchlists.",
            "Chat is built into the app (go-live pending platform scope).",
        ),
    ),
    ChangelogEntry(
        version = "0.12",
        highlights = listOf(
            "App lock with a PIN, biometric unlock, and the option to reuse your BetterTrack account PIN.",
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
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
            items(BT_CHANGELOG.size, key = { BT_CHANGELOG[it].version }) { i ->
                ChangelogCard(BT_CHANGELOG[i])
            }
        }
    }
}

@Composable
private fun ChangelogCard(entry: ChangelogEntry) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // Version tag — a small gold pill so each release reads as a header.
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
            Spacer(Modifier.height(12.dp))
            entry.highlights.forEachIndexed { index, line ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    // Small gold bullet — a check-style dot without an extra icon dep.
                    Surface(
                        shape = CircleShape,
                        color = bt.gold,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(5.dp),
                    ) {}
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textSecondary,
                    )
                }
            }
        }
    }
}
