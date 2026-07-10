package at.bettertrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.i18n.AppLanguage
import at.bettertrack.app.data.i18n.LocaleManager
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * Settings → Language (spec §6.12): in-app English / German switch via per-app
 * locales. Picking a language applies it immediately ([LocaleManager] recreates
 * the activity so the whole UI flips live) and best-effort mirrors the choice to
 * the account (`PATCH /settings/account` locale) so a later web login matches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    // Reflects the persisted choice; after applyAndRecreate() the activity rebuilds
    // and this re-reads the new language.
    val selected = remember { LocaleManager.current(context) }

    fun choose(language: AppLanguage) {
        // Mirror an explicit choice to the account (best-effort); System leaves the
        // server locale untouched. Then persist + recreate so the UI flips live.
        if (language != AppLanguage.System) {
            scope.launch { AppGraph.accountRepository.updateAccountLocale(language.tag) }
        }
        activity?.let { LocaleManager.applyAndRecreate(it, language) }
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_settings_language), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.bt_lang_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            Spacer(Modifier.size(2.dp))

            LanguageOption(
                title = stringResource(R.string.bt_lang_system),
                subtitle = stringResource(R.string.bt_lang_system_sub),
                selected = selected == AppLanguage.System,
                onClick = { choose(AppLanguage.System) },
            )
            LanguageOption(
                title = stringResource(R.string.bt_lang_english),
                subtitle = "English",
                selected = selected == AppLanguage.English,
                onClick = { choose(AppLanguage.English) },
            )
            LanguageOption(
                title = stringResource(R.string.bt_lang_german),
                subtitle = "Deutsch",
                selected = selected == AppLanguage.German,
                onClick = { choose(AppLanguage.German) },
            )
        }
    }
}

@Composable
private fun LanguageOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        color = if (selected) bt.goldSurface else bt.surface,
        border = BorderStroke(1.dp, if (selected) bt.gold.copy(alpha = 0.5f) else bt.border),
        shape = BtShapes.card,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.Check, contentDescription = null, tint = bt.gold, modifier = Modifier.size(22.dp))
            }
        }
    }
}
