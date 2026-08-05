package at.bettertrack.app.ui.paranoid

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.api.ParanoidModeState
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.ui.components.BtCustomTab
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The single paranoid-mode guard (S6 P0-1 + P1-14).
 *
 * Wrap any destination whose data lives in the server-blind portfolio family:
 * while the account is in paranoid mode the guarded [content] is replaced by
 * [ParanoidModeScreen], otherwise it renders untouched.
 *
 * Three things every guarded route now gets, which the seven hand-written
 * guards did not all have:
 *
 *  1. **Reactive**: the flag is collected ([collectAsStateWithLifecycle]), so a
 *     mode change recomposes the route. Six of the seven old sites read
 *     `.value` once at composition and stayed stale until something else
 *     happened to recompose them.
 *  2. **Escapable**: on a PUSHED route (pass [onBack]) the explainer is hosted
 *     in its own Scaffold with a TopAppBar + back arrow. The shell suppresses
 *     both of its bars off the top-level tabs, so without this the screen was a
 *     dead end with no back arrow and no tab bar — gesture-back only.
 *  3. **Truthful**: the copy promises the web app, so `onOpenWeb` is ALWAYS
 *     wired. Every old call site passed it bare (default `null`), which hid the
 *     button and left the promise unkept.
 *
 * Top-level tabs pass no [onBack] — the shell's own bars are showing there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParanoidGate(
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val paranoid by ParanoidModeState.active.collectAsStateWithLifecycle()
    if (!paranoid) {
        content()
        return
    }

    val bt = BtTheme.colors
    val context = LocalContext.current
    val openWeb: () -> Unit = { openBtWebApp(context) }

    if (onBack == null) {
        // The one no-[onBack] caller is the Portfolio TAB, and since R-arc R1-B
        // that tab sets `ownsItsHeader`: the shell renders no top bar over it, so
        // nothing above this consumes the status-bar inset. The overview does it
        // in its own collapsing header; this explainer REPLACES the overview, so
        // it has to do it here or its scrolling copy runs under the clock.
        ParanoidModeScreen(onOpenWeb = openWeb, modifier = Modifier.statusBarsPadding())
        return
    }
    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.bt_paranoid_topbar),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                ),
            )
        },
    ) { innerPadding ->
        ParanoidModeScreen(
            onOpenWeb = openWeb,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Opens the BetterTrack web app in a Custom Tab, falling back to whatever
 * browser the device has. Reads the EFFECTIVE web origin, so a debug build
 * pointed at a dev stack ([ServerOrigins]) opens that stack and not
 * production.
 */
internal fun openBtWebApp(context: Context, path: String = "/") {
    // The URL is this function's job; the tab itself is [BtCustomTab]'s (which
    // keeps the fail-soft browser fallback — a missing browser must never crash
    // the explainer).
    BtCustomTab.open(context, btWebUrl(ServerOrigins.webOrigin, path))
}

/**
 * Joins an origin and a path into one well-formed URL: exactly one slash at the
 * seam, no trailing slash duplication. Kept pure so it is unit-testable without
 * Android (the origin can be hand-typed on the developer screen).
 */
internal fun btWebUrl(origin: String, path: String = "/"): String {
    val base = origin.trim().trimEnd('/')
    val suffix = path.trim()
    return when {
        suffix.isEmpty() || suffix == "/" -> "$base/"
        suffix.startsWith("/") -> base + suffix
        else -> "$base/$suffix"
    }
}
