package at.bettertrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.VersionResponse
import at.bettertrack.app.data.api.dto.formatApiBuiltAtDate
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Settings → About (spec §6.12): the two-color wordmark + "App" edition + tagline,
 * the installed version, a link to the web app, the public privacy-policy page
 * (https://bettertrack.at/privacy/ — required for Play review), and the in-app
 * "What's new" changelog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    val bt = BtTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val webOrigin = BuildConfig.WEB_ORIGIN.trimEnd('/')
    val webHost = webOrigin.substringAfter("://")
    // Public legal pages (board #34 — live + final; required for Play review).
    // Fixed public URLs on the marketing domain, independent of the API/web
    // origins. Each page ships EN + DE — follow the app's active language.
    val isDe = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "de"
    fun legalUrl(path: String) = "https://bettertrack.at/$path/" + if (isDe) "de/" else ""
    fun legalHost(path: String) = "bettertrack.at/$path"
    val privacyUrl = legalUrl("privacy")
    val privacyHost = legalHost("privacy")
    val onOpenUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
            )
        }
    }

    // Cosmetic: the live server's running build (public GET /version), loaded
    // lazily + fail-soft. Null (not fetched / failed) simply hides the row — this
    // is decorative build-info and must never show an error state.
    val apiBuild by produceState<VersionResponse?>(initialValue = null) {
        value = (AppGraph.buildInfoRepository.apiBuild() as? BtResult.Ok)?.value
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_settings_about), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg, titleContentColor = bt.textPrimary, navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Brand header.
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Wordmark(fontSize = 34.sp, edition = stringResource(R.string.bt_edition_app))
                Text(
                    stringResource(R.string.bt_about_edition_line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.bt_login_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    textAlign = TextAlign.Center,
                )
            }

            // Version.
            Surface(color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.bt_settings_version), style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
                    Text(
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                    )
                }
            }

            // Automatic update checks (owner ask 2026-07-12): the About-level opt-out
            // for the dev update notifier. Default ON; OFF stops launch/foreground
            // checks so no dialog appears until the user turns it back on. The whole
            // control is COMPILED OUT of Play builds (Task B1) — self-update is not
            // allowed there, so the toggle must not appear.
            if (BuildConfig.SELF_UPDATE_ENABLED) {
                val autoUpdate by AppGraph.updateChecker.autoCheckEnabled.collectAsStateWithLifecycle()
                AboutToggleRow(
                    icon = Icons.Outlined.SystemUpdateAlt,
                    title = stringResource(R.string.bt_settings_auto_update),
                    subtitle = stringResource(R.string.bt_settings_auto_update_sub),
                    checked = autoUpdate,
                    onCheckedChange = { AppGraph.updateChecker.setAutoCheckEnabled(it) },
                )
            }

            // API build (cosmetic; hidden until the public /version fetch returns).
            apiBuild?.let { info ->
                val shortCommit = info.shortCommit.ifBlank { info.commit.take(7) }
                if (shortCommit.isNotBlank()) {
                    val date = formatApiBuiltAtDate(info.builtAt)
                    val value = if (date.isBlank()) shortCommit else "$shortCommit · $date"
                    Surface(color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.bt_about_api_build), style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
                            Text(value, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
                        }
                    }
                }
            }

            // Links (only repo-known public URLs).
            AboutNavRow(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                title = stringResource(R.string.bt_about_open_web),
                subtitle = webHost,
                onClick = { onOpenUrl(webOrigin) },
            )
            AboutNavRow(
                icon = Icons.Outlined.PrivacyTip,
                title = stringResource(R.string.bt_about_privacy),
                subtitle = privacyHost,
                onClick = { onOpenUrl(privacyUrl) },
            )
            AboutNavRow(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.bt_about_terms),
                subtitle = legalHost("terms"),
                onClick = { onOpenUrl(legalUrl("terms")) },
            )
            AboutNavRow(
                icon = Icons.Outlined.Gavel,
                title = stringResource(R.string.bt_about_impressum),
                subtitle = legalHost("impressum"),
                onClick = { onOpenUrl(legalUrl("impressum")) },
            )
            AboutNavRow(
                icon = Icons.Outlined.Cookie,
                title = stringResource(R.string.bt_about_cookies),
                subtitle = legalHost("cookies"),
                onClick = { onOpenUrl(legalUrl("cookies")) },
            )
            // "What's new" reads the GitHub dev-channel changelog, so it belongs to
            // the self-update surface — hidden in Play builds (Task B1).
            if (BuildConfig.SELF_UPDATE_ENABLED) {
                AboutNavRow(
                    icon = Icons.Outlined.NewReleases,
                    title = stringResource(R.string.bt_settings_whatsnew_row),
                    subtitle = stringResource(R.string.bt_settings_whatsnew_sub),
                    onClick = onOpenChangelog,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.bt_about_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AboutToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    Surface(color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = bt.onGold,
                    checkedTrackColor = bt.gold,
                    checkedBorderColor = bt.gold,
                    uncheckedThumbColor = bt.textMuted,
                    uncheckedTrackColor = bt.surface,
                    uncheckedBorderColor = bt.borderStrong,
                ),
            )
        }
    }
}

@Composable
private fun AboutNavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(onClick = onClick, color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}
