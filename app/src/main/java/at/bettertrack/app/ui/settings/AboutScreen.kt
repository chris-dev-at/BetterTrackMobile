package at.bettertrack.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.VersionResponse
import at.bettertrack.app.data.api.dto.formatApiBuiltAtDate
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.isGermanUi

/**
 * Settings → About (spec §6.12): the two-color wordmark + "App" edition + tagline,
 * the installed version, a link to the web app, the public privacy-policy page
 * (https://bettertrack.at/privacy/ — required for Play review), and the in-app
 * "What's new" changelog.
 *
 * ## R2 visual pass
 *
 * The screen had nine individually-bordered surfaces in a single column — build
 * info, an update toggle and six link rows, every one of them the same rounded
 * rectangle — so the border was the loudest thing on a page whose actual job is
 * to be quiet. They are now two [BtGroup]s: *what this build is* (version, the
 * update opt-out, the server's build) and *where to read more* (web app + the
 * legal pages + What's new). Nothing moved, nothing was renamed; the wordmark
 * block and the copyright line stay chrome-less prose, because they were never
 * rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    val bt = BtTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val webOrigin = at.bettertrack.app.data.prefs.DevOriginOverride.webOrigin.trimEnd('/')
    val webHost = webOrigin.substringAfter("://")
    // Public legal pages (board #34 — live + final; required for Play review).
    // Fixed public URLs on the marketing domain, independent of the API/web
    // origins. Each page ships EN + DE — follow the app's active language.
    val isDe = isGermanUi()
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

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_settings_about),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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

            // ── This build ───────────────────────────────────────────────────
            // The installed version, the update opt-out that acts on it, and the
            // build the server is running: three answers to one question, so one
            // group. Both build lines are read-only key/value rows and stay that
            // way — the group's tonal step is all the containment they need.
            BtGroup {
                BuildInfoRow(
                    label = stringResource(R.string.bt_settings_version),
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )

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
                        BuildInfoRow(label = stringResource(R.string.bt_about_api_build), value = value)
                    }
                }
            }

            // ── Where to read more ───────────────────────────────────────────
            // Links (only repo-known public URLs). One group: they are the same
            // kind of act — leave the app, land on a page — and the four legal
            // pages in particular are a set, not four separate decisions.
            BtGroup {
                BtGroupRow(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    title = stringResource(R.string.bt_about_open_web),
                    subtitle = webHost,
                    onClick = { onOpenUrl(webOrigin) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.PrivacyTip,
                    title = stringResource(R.string.bt_about_privacy),
                    subtitle = privacyHost,
                    onClick = { onOpenUrl(privacyUrl) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.bt_about_terms),
                    subtitle = legalHost("terms"),
                    onClick = { onOpenUrl(legalUrl("terms")) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.Gavel,
                    title = stringResource(R.string.bt_about_impressum),
                    subtitle = legalHost("impressum"),
                    onClick = { onOpenUrl(legalUrl("impressum")) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.Cookie,
                    title = stringResource(R.string.bt_about_cookies),
                    subtitle = legalHost("cookies"),
                    onClick = { onOpenUrl(legalUrl("cookies")) },
                )
                // "What's new" reads the GitHub dev-channel changelog, so it belongs to
                // the self-update surface — hidden in Play builds (Task B1).
                if (BuildConfig.SELF_UPDATE_ENABLED) {
                    BtGroupRow(
                        icon = Icons.Outlined.NewReleases,
                        title = stringResource(R.string.bt_settings_whatsnew_row),
                        subtitle = stringResource(R.string.bt_settings_whatsnew_sub),
                        onClick = onOpenChangelog,
                    )
                }
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

// R2: `AboutNavRow` is gone — it was a private clone of the app-wide row, and
// `BtGroupRow` inside a `BtGroup` renders the six link rows instead.

/**
 * The auto-update toggle as a [BtGroup] row.
 *
 * Kept as a local wrapper rather than inlined at the call site because
 * `BtGroupRow` has no switch of its own: the switch goes in its `trailing` slot
 * (which also suppresses the chevron, so the row never claims to navigate), and
 * the whole row carries the tap the way `SettingsScreen`'s toggles do — a 32dp
 * switch at the far edge of a 360dp screen is not a one-handed target.
 */
@Composable
private fun AboutToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    BtGroupRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
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
        },
    )
}

/**
 * A read-only `label … value` line inside the build group. Not a [BtGroupRow]:
 * that lays out as title-over-subtitle, and build info reads as a pair — the
 * label on the left, the thing you are being told on the right — the same shape
 * `SettingsScreen` uses for the account fields.
 */
@Composable
private fun BuildInfoRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
    }
}
