package at.bettertrack.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.OAuthConfig
import at.bettertrack.app.data.auth.v5ScopesAllowedFor
import at.bettertrack.app.data.prefs.OriginError
import at.bettertrack.app.data.prefs.OriginValidation
import at.bettertrack.app.data.prefs.OriginWarning
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.data.prefs.originWarning
import at.bettertrack.app.data.prefs.validateOrigins
import at.bettertrack.app.data.storage.effective
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.btFieldColors
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme

/**
 * **Settings → Server** — the backend this install talks to (owner ask,
 * 2026-08-04: "the option in the login screen to swap server … so I can change
 * the server to the current dev server and login there").
 *
 * This is the promoted, first-class form of what used to be the hidden
 * `DevBackendScreen`. It is a `github`-flavor feature — visible in github debug
 * AND github release, absent from `play` — and the gate is a single BuildConfig
 * flag read through [ServerOrigins.settingEnabled]. Nothing on this screen is
 * reachable in a Play build: the two entry points are themselves flag-gated, and
 * the store ignores any value it might somehow find.
 *
 * ## The honest parts
 *
 *  - **Restart applies.** Retrofit, the auth repository, the social repository
 *    and the chat socket all capture their origin once, at process start. There
 *    is no per-call origin routing to piggyback on, so a switch means a restart
 *    — and the screen restarts the app for you rather than telling you to
 *    force-stop it.
 *  - **Plain http is called out.** An `http://` origin gets an inline warning
 *    every time; in a RELEASE build it also says the truth that matters — the
 *    release network-security config refuses cleartext, so http simply will not
 *    work there. That config is deliberately NOT weakened for this feature: an
 *    https custom origin works in every build.
 *  - **The LAN dev preset is debug-only.** A release APK must not advertise
 *    somebody's private machine; custom entry still reaches the same place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current

    var apiField by remember { mutableStateOf(ServerOrigins.apiOverride ?: ServerOrigins.defaultApiOrigin) }
    var webField by remember { mutableStateOf(ServerOrigins.webOverride ?: ServerOrigins.defaultWebOrigin) }
    var apiError by remember { mutableStateOf<OriginError?>(null) }
    var webError by remember { mutableStateOf<OriginError?>(null) }
    var status by remember { mutableStateOf<Int?>(null) }
    // Bumped after every save/reset so the "in use" card re-reads ServerOrigins
    // (a plain object, not observable state).
    var revision by remember { mutableIntStateOf(0) }

    val effectiveApi = remember(revision) { ServerOrigins.apiOrigin }
    val effectiveWeb = remember(revision) { ServerOrigins.webOrigin }
    val overridden = remember(revision) { ServerOrigins.isOverridden }

    // Warnings track what is TYPED, not what is saved: the point is to tell the
    // user before they commit to an origin the build cannot reach.
    val apiWarning = originWarning(apiField.trim(), ServerOrigins.cleartextPermitted)
    val webWarning = originWarning(webField.trim(), ServerOrigins.cleartextPermitted)

    fun applyPreset(api: String, web: String, statusRes: Int) {
        apiField = api
        webField = web
        apiError = null
        webError = null
        status = statusRes
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_server),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.bt_server_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            // ── What is actually in use right now ────────────────────────────
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(
                            if (overridden) R.string.bt_server_current_custom else R.string.bt_server_current_official,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (overridden) bt.gold else bt.textSecondary,
                    )
                    MonoRow(stringResource(R.string.bt_server_api), effectiveApi)
                    MonoRow(stringResource(R.string.bt_server_web), effectiveWeb)
                }
            }

            // ── Presets ──────────────────────────────────────────────────────
            BtSectionHeader(stringResource(R.string.bt_server_presets))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BtChip(
                    text = stringResource(R.string.bt_server_preset_official),
                    selected = apiField.trim() == ServerOrigins.defaultApiOrigin &&
                        webField.trim() == ServerOrigins.defaultWebOrigin,
                    onClick = {
                        applyPreset(
                            ServerOrigins.defaultApiOrigin,
                            ServerOrigins.defaultWebOrigin,
                            R.string.bt_server_preset_applied,
                        )
                    },
                )
                // Debug only: a release build never advertises a LAN dev box.
                // Typing the same addresses by hand still works everywhere.
                if (BuildConfig.DEBUG) {
                    BtChip(
                        text = stringResource(R.string.bt_server_preset_local),
                        selected = apiField.trim() == ServerOrigins.devPresetApiOrigin &&
                            webField.trim() == ServerOrigins.devPresetWebOrigin,
                        onClick = {
                            applyPreset(
                                ServerOrigins.devPresetApiOrigin,
                                ServerOrigins.devPresetWebOrigin,
                                R.string.bt_server_preset_applied,
                            )
                        },
                    )
                }
            }

            // ── Editors ──────────────────────────────────────────────────────
            OriginField(
                label = stringResource(R.string.bt_server_api),
                placeholder = ServerOrigins.defaultApiOrigin,
                value = apiField,
                error = apiError,
                warning = apiWarning,
                onValueChange = { apiField = it; apiError = null; status = null },
            )
            OriginField(
                label = stringResource(R.string.bt_server_web),
                placeholder = ServerOrigins.defaultWebOrigin,
                value = webField,
                error = webError,
                warning = webWarning,
                hint = stringResource(R.string.bt_server_web_hint),
                onValueChange = { webField = it; webError = null; status = null },
            )

            status?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = bt.goldInk)
            }

            Text(
                stringResource(R.string.bt_server_restart_note),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Text(
                stringResource(R.string.bt_server_logout_note),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )

            BtPrimaryButton(
                text = stringResource(R.string.bt_server_save_restart),
                onClick = {
                    // Validate both fields together, then write both or neither,
                    // then — and only then — end the process. Every step reports
                    // its own failure: the one thing this button must never do is
                    // look like it worked while changing nothing, which is the
                    // bug the owner hit on 2026-08-05.
                    when (
                        val verdict = validateOrigins(
                            apiRaw = apiField,
                            webRaw = webField,
                            defaultApi = ServerOrigins.defaultApiOrigin,
                            defaultWeb = ServerOrigins.defaultWebOrigin,
                        )
                    ) {
                        is OriginValidation.Invalid -> {
                            apiError = verdict.apiError
                            webError = verdict.webError
                            status = null
                        }

                        is OriginValidation.Valid -> {
                            apiError = null
                            webError = null
                            revision++
                            if (!ServerOrigins.persist(verdict.api, verdict.web)) {
                                // The bytes are not on disk, so a restart would
                                // come back on the OLD server. Say so; change
                                // nothing else.
                                status = R.string.bt_server_save_failed
                            } else if (restartApp(context)) {
                                // The relaunch is queued with the system and the
                                // origins are already committed, so ending the
                                // process here cannot lose them.
                                Runtime.getRuntime().exit(0)
                            } else {
                                // Saved, but this device would not take the
                                // relaunch. Never leave a dead button: tell the
                                // user the one thing left to do.
                                status = R.string.bt_server_saved_restart_manually
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )

            BtSecondaryButton(
                text = stringResource(R.string.bt_server_reset),
                onClick = {
                    val cleared = ServerOrigins.reset()
                    revision++
                    applyPreset(
                        ServerOrigins.defaultApiOrigin,
                        ServerOrigins.defaultWebOrigin,
                        if (cleared) R.string.bt_server_reset_done else R.string.bt_server_save_failed,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )

            if (BuildConfig.DEBUG) {
                DevDiagnostics(revision)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The diagnostics the old hidden dev screen carried: which storage backend is
 * serving this install, and which OAuth scopes the effective origin gets asked
 * for. Debug-only and deliberately English-only/resource-free — this is
 * developer plumbing no translator should ever see, and giving it string
 * resources would churn EN↔DE parity for copy that never reaches a user.
 */
@Composable
private fun DevDiagnostics(revision: Int) {
    val bt = BtTheme.colors
    val storedMode = AppGraph.storageModeStore.mode.collectAsState().value
    val storageModeLabel = "${storedMode.effective.name} (stored: ${storedMode.name})"
    val backendName = remember { AppGraph.portfolioBackend::class.java.simpleName }
    val marketSourceName = remember { AppGraph.marketDataSource::class.java.simpleName }
    val v5ScopesOn = remember(revision) { v5ScopesAllowedFor(ServerOrigins.apiOrigin) }
    val requestedScopeCount = remember(revision) { OAuthConfig.SCOPES.split(" ").size }

    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Debug diagnostics", style = MaterialTheme.typography.labelLarge, color = bt.textSecondary)
            MonoRow("Storage", storageModeLabel)
            MonoRow("Backend", backendName)
            MonoRow("Prices", marketSourceName)
            MonoRow("Build", "${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE}")
            Text(
                "Requested scopes ($requestedScopeCount): ${OAuthConfig.SCOPES}",
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Text(
                if (v5ScopesOn) {
                    "v5 scopes (cash:* mirrorchain:*): ON — effective API origin is not production."
                } else {
                    "v5 scopes (cash:* mirrorchain:*): OFF — production origin keeps the proven 14."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (v5ScopesOn) bt.gold else bt.textMuted,
            )
        }
    }
}

@Composable
private fun OriginField(
    label: String,
    placeholder: String,
    value: String,
    error: OriginError?,
    warning: OriginWarning,
    onValueChange: (String) -> Unit,
    hint: String? = null,
) {
    val bt = BtTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder, color = bt.textMuted) },
            singleLine = true,
            isError = error != null,
            colors = btFieldColors(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            error != null -> Text(
                stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = bt.loss,
            )
            warning == OriginWarning.INSECURE_AND_BLOCKED -> Text(
                stringResource(R.string.bt_server_warn_insecure_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = bt.loss,
            )
            warning == OriginWarning.INSECURE -> Text(
                stringResource(R.string.bt_server_warn_insecure),
                style = MaterialTheme.typography.bodySmall,
                color = bt.goldInk,
            )
            hint != null -> Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

private fun OriginError.messageRes(): Int = when (this) {
    OriginError.SCHEME -> R.string.bt_server_err_scheme
    OriginError.HOST -> R.string.bt_server_err_host
    OriginError.SPACE -> R.string.bt_server_err_space
    OriginError.PORT -> R.string.bt_server_err_port
}

@Composable
private fun MonoRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = bt.textPrimary,
        )
    }
}

/**
 * Hands the system a fresh launch of this app on a cleared task, so the new
 * origin is actually in force.
 *
 * The alternative — "force-stop the app yourself, then reopen it" — is what the
 * hidden dev screen used to say, and it is a bad instruction to give a user who
 * just wants to point their app at another server. Starting the launch intent on
 * a cleared task and then ending the process gives the same clean cold start
 * that a force-stop would, without asking anyone to visit system settings.
 *
 * **This function no longer ends the process itself.** Killing the process is
 * the caller's decision and must happen only after the origins are committed to
 * disk — the two used to be welded together, and that is precisely how the save
 * came to be lost (see `ServerOrigins.persist`). Verified on R5CN80ABXBK /
 * Android 13: `startActivity` + `Runtime.exit(0)` does relaunch reliably from
 * the foreground, so the mechanism is kept; only its ordering changed.
 *
 * @return true when the relaunch was accepted and the caller may end the
 *   process; false when this device refused it, in which case the caller must
 *   keep the app alive and say what is left to do.
 */
private fun restartApp(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: return false
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    return try {
        context.startActivity(launch)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
