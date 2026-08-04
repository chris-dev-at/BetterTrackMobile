package at.bettertrack.app.ui.debug

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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.auth.OAuthConfig
import at.bettertrack.app.data.auth.v5ScopesAllowedFor
import at.bettertrack.app.data.prefs.DevOriginOverride
import at.bettertrack.app.data.storage.effective
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.btFieldColors
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Hidden **Developer → Dev backend** screen (V5 S1 sprint infrastructure).
 *
 * Lets a debug build be pointed at any API/web origin — during the holiday
 * sprint that's the local dev stack (`http://localhost:3000` /
 * `http://localhost:6771`, reachable from the phone through `adb reverse`).
 *
 * Deliberately English-only and resource-free: this is developer plumbing that
 * never ships to a user, and adding string resources would churn the EN↔DE
 * parity count for strings no translator should ever see. Release builds never
 * reach this screen (the Developer section is `BuildConfig.DEBUG`-gated) AND
 * would ignore the stored values anyway ([DevOriginOverride]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevBackendScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors

    var apiField by remember { mutableStateOf(DevOriginOverride.apiOverride ?: "") }
    var webField by remember { mutableStateOf(DevOriginOverride.webOverride ?: "") }
    var apiError by remember { mutableStateOf<String?>(null) }
    var webError by remember { mutableStateOf<String?>(null) }
    // Bumped after every successful save/reset so the "current effective" card
    // re-reads DevOriginOverride (a plain object, not observable state).
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    val effectiveApi = remember(revision) { DevOriginOverride.apiOrigin }
    val effectiveWeb = remember(revision) { DevOriginOverride.webOrigin }
    val overridden = remember(revision) { DevOriginOverride.isOverridden }
    val v5ScopesOn = remember(revision) { v5ScopesAllowedFor(DevOriginOverride.apiOrigin) }
    val requestedScopeCount = remember(revision) { OAuthConfig.SCOPES.split(" ").size }

    // V5 W1 (S3/S4 plan §1.2/§1.4): the active storage mode and the classes
    // actually serving reads/writes. The class NAMES are the point — this is the
    // one-glance proof that a debug build is on the backend you think it is.
    val storedMode = AppGraph.storageModeStore.mode.collectAsState().value
    val storageModeLabel = "${storedMode.effective.name} (stored: ${storedMode.name})"
    val backendName = remember { AppGraph.portfolioBackend::class.java.simpleName }
    val marketSourceName = remember { AppGraph.marketDataSource::class.java.simpleName }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text("Dev backend", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Current effective values ──────────────────────────────────────
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (overridden) "Currently in use — OVERRIDDEN" else "Currently in use — build defaults",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (overridden) bt.gold else bt.textSecondary,
                    )
                    MonoRow("API", effectiveApi)
                    MonoRow("Web", effectiveWeb)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Build defaults: ${DevOriginOverride.defaultApiOrigin} · ${DevOriginOverride.defaultWebOrigin}",
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                    Text(
                        "Requested scopes (${requestedScopeCount}): ${OAuthConfig.SCOPES}",
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

            // ── V5 W1: which storage backend is serving this install ──────────
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Storage",
                        style = MaterialTheme.typography.labelLarge,
                        color = bt.textSecondary,
                    )
                    MonoRow("Mode", storageModeLabel)
                    MonoRow("Backend", backendName)
                    MonoRow("Prices", marketSourceName)
                    Text(
                        "UNSET behaves exactly as SERVER until the first-run wizard ships (W5); " +
                            "an existing install is grandfathered to SERVER once, at startup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }

            Text(
                "A change applies on the NEXT APP START — force-stop and relaunch after saving. " +
                    "Log out first if you are switching to a different backend: tokens are minted " +
                    "per-server and a foreign token just 401s.",
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )

            // ── Editors ───────────────────────────────────────────────────────
            OriginField(
                label = "API origin override",
                placeholder = DevOriginOverride.defaultApiOrigin,
                value = apiField,
                error = apiError,
                onValueChange = { apiField = it; apiError = null; status = null },
            )
            OriginField(
                label = "Web / consent origin override",
                placeholder = DevOriginOverride.defaultWebOrigin,
                value = webField,
                error = webError,
                onValueChange = { webField = it; webError = null; status = null },
            )

            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = bt.gold)
            }

            BtPrimaryButton(
                text = "Save",
                onClick = {
                    var ok = true
                    try {
                        DevOriginOverride.setApiOrigin(apiField)
                    } catch (e: IllegalArgumentException) {
                        apiError = e.message; ok = false
                    }
                    try {
                        DevOriginOverride.setWebOrigin(webField)
                    } catch (e: IllegalArgumentException) {
                        webError = e.message; ok = false
                    }
                    if (ok) {
                        apiField = DevOriginOverride.apiOverride ?: ""
                        webField = DevOriginOverride.webOverride ?: ""
                        revision++
                        status = "Saved. Force-stop and relaunch the app to apply."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )

            BtSecondaryButton(
                text = "Reset to build defaults",
                onClick = {
                    DevOriginOverride.reset()
                    apiField = ""
                    webField = ""
                    apiError = null
                    webError = null
                    revision++
                    status = "Overrides cleared. Force-stop and relaunch the app to apply."
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )

            Text(
                "Sprint quick-set: API http://localhost:3000 · Web http://localhost:6771 " +
                    "(needs `adb reverse tcp:3000 tcp:3000` and `tcp:6771 tcp:6771` on the Mac). " +
                    "Leave a field blank to fall back to that build default. " +
                    "Debug build: ${BuildConfig.DEBUG}.",
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

@Composable
private fun OriginField(
    label: String,
    placeholder: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
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
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = bt.loss)
        }
    }
}

@Composable
private fun MonoRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = bt.textPrimary,
        )
    }
}
