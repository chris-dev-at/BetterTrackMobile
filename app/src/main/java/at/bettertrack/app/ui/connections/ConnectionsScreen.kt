package at.bettertrack.app.ui.connections

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
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.ConnectionsRepository
import at.bettertrack.app.data.repo.GoogleLink
import at.bettertrack.app.data.repo.GoogleLinkResult
import at.bettertrack.app.data.repo.GoogleLinkStartResult
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtCustomTab
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.paranoid.openBtWebApp
import at.bettertrack.app.ui.settings.formatMemberSince
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── State ────────────────────────────────────────────────────────────────────

internal class ConnectionsViewModel(
    private val repo: ConnectionsRepository,
) : ViewModel() {

    /**
     * `null` while the read is in flight.
     *
     * Deliberately distinct from [GoogleLinkResult.Failed]: "we have not asked
     * yet" is a skeleton, "we asked and could not get an answer" is a retryable
     * error, and collapsing the two would show a failure to someone whose request
     * is still travelling.
     */
    private val _google = MutableStateFlow<GoogleLinkResult?>(null)
    val google: StateFlow<GoogleLinkResult?> = _google.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * The connect attempt's verdict, waiting to be announced exactly once.
     *
     * Held here rather than raised as a callback because the round trip that
     * produces it outlives the composition that started it: the user leaves for a
     * Custom Tab and comes back, and on a low-memory device the activity may have
     * been recreated in between. A value on the ViewModel survives that; a
     * lambda captured in a composable does not.
     */
    private val _linkOutcome = MutableStateFlow<GoogleLinkOutcome?>(null)
    val linkOutcome: StateFlow<GoogleLinkOutcome?> = _linkOutcome.asStateFlow()

    init {
        load()
    }

    /**
     * Read (and, on the first call of the process, probe).
     *
     * Never blanked back to `null` first: a settled WebOnly answer is cached and
     * replies in the same breath, so clearing would flash the skeleton for a
     * frame on every re-entry.
     */
    fun load() {
        viewModelScope.launch { _google.value = repo.googleLink() }
    }

    /**
     * Begin the connect: ask the server for a ticketed authorization URL and hand
     * it to [openTab].
     *
     * The URL is NEVER built here. `link/start` accepts no redirect target and
     * mints a one-time, account-bound ticket, so the only URL that can complete
     * this flow is the one the server just returned.
     *
     * The two non-Ready answers are re-reads rather than messages: a 404 means
     * the deployment lost its Google client and the whole group has to disappear;
     * a 403 means the allowlist closed and the group has to become the web-only
     * state. Both are decided by [load], which already draws them.
     */
    fun connect(openTab: (String) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _linkOutcome.value = null
            val started = repo.startGoogleLink()
            _busy.value = false
            when (started) {
                is GoogleLinkStartResult.Ready -> openTab(started.authorizationUrl)
                GoogleLinkStartResult.Unavailable, GoogleLinkStartResult.WebOnly -> load()
                is GoogleLinkStartResult.Failed ->
                    _linkOutcome.value = GoogleLinkOutcome.Failed(started.error.asMessage())
            }
        }
    }

    /**
     * The browser came back. Decide what actually happened — from the SERVER,
     * not from the redirect.
     *
     * The redirect is a hint, not proof: it is a URL a browser handed us, and the
     * word "connected" is worth one round trip to be sure of. So the status is
     * re-read first and the link's own `linked` flag decides. The redirect's
     * error code only picks WHICH failure sentence to show once the re-read has
     * confirmed there is one.
     */
    fun onLinkReturn(ret: GoogleLinkReturn) {
        viewModelScope.launch {
            _busy.value = true
            val fresh = repo.googleLink()
            _google.value = fresh
            _busy.value = false
            val linked = (fresh as? GoogleLinkResult.Ready)?.link?.linked == true
            _linkOutcome.value = when {
                linked -> GoogleLinkOutcome.Linked
                ret is GoogleLinkReturn.Failed -> GoogleLinkOutcome.Failed(googleLinkFailureMessage(ret.code))
                // The redirect claimed the success leg and the account still is
                // not linked. Rare — a race with another device consuming the
                // ticket, or a re-read that failed — but it must not be reported
                // as a success, so it takes the generic failure.
                else -> GoogleLinkOutcome.Failed(BtMessage(R.string.bt_conn_google_link_failed))
            }
        }
    }

    /** The outcome has been shown; do not re-announce it on the next recomposition. */
    fun clearLinkOutcome() {
        _linkOutcome.value = null
    }

    /** `onDone(null)` means it worked; the status is re-read either way. */
    fun unlink(password: String, onDone: (BtMessage?) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val r = repo.unlinkGoogle(password)
            _busy.value = false
            when (r) {
                is BtResult.Ok -> {
                    onDone(null)
                    load()
                }

                is BtResult.Err -> onDone(ConnectionsRepository.unlinkFailure(r.error))
            }
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * Settings → **Connections**, native (owner order 2026-08-08).
 *
 * This screen replaces a `BtWebLinkRow` that bounced the user out to
 * `/control/connections`. It carries the web panel's three groups at capability
 * parity, and each one is a different KIND of thing, which is why they do not
 * share a shape:
 *
 *  1. **Google account** — the real thing: status, connect, and unlink behind a
 *     password re-auth. Env-gated: when the deployment has no Google client the
 *     group renders nothing at all, exactly as the web does — an empty "Google
 *     account" section would advertise a feature the server does not have.
 *  2. **Google Drive** — a link INTO this app, not a second implementation. The
 *     vault's Drive medium already has a full native home at "Where your data
 *     lives"; duplicating its controller here would be two surfaces racing over
 *     the same state.
 *  3. **More connectors** — the web's inert slots (bank/cash, Parqet). Each
 *     names itself, says what it does, states whether it stays connected or is a
 *     one-time import, and wears a "coming soon" chip. No dead buttons.
 *
 * ## Connecting: a browser trip that really does come back
 *
 * Connecting cannot happen inside an HTTP call — it is a Google consent screen,
 * and only a browser can show one. What it CAN do is return. `link/start` mints
 * a one-time, account-bound ticket and returns the authorization URL; the tab
 * runs the consent; the public callback consumes the ticket, links the bound
 * account, mints no session and 302s to `bettertrack://oauth/google-link`
 * (`GoogleLinkDeepLink.kt`). The activity parks that verdict, this screen
 * consumes it once and re-reads the status before saying anything.
 *
 * That is why [R.string.bt_conn_google_connect_hint] — "finishes in your browser,
 * then returns here" — is now literally what happens. It was aspirational for as
 * long as the row's only trick was opening the web control panel.
 *
 * ## The capability probe
 *
 * The Google routes are bearer-callable under `account:security` on production;
 * a deployment that has not opened them refuses with a 403. The group's own read
 * decides which of three things is drawn: the live status, the "manage on the
 * web" explainer, or a retryable error — no app release either way. See
 * [ConnectionsRepository].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(onBack: () -> Unit, onOpenDataHome: () -> Unit = {}) {
    val vm: ConnectionsViewModel = viewModel(key = "connections") {
        ConnectionsViewModel(AppGraph.connectionsRepository)
    }
    val bt = BtTheme.colors
    val context = LocalContext.current
    val snackbar = LocalBtSnackbar.current
    val google by vm.google.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val linkOutcome by vm.linkOutcome.collectAsStateWithLifecycle()

    // The browser's return, parked by the activity while this screen was in the
    // background. Consumed once — a second collection must not re-run the flow.
    val pendingReturn by GoogleLinkReturnHolder.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingReturn) {
        if (pendingReturn != null) {
            GoogleLinkReturnHolder.consume()?.let { vm.onLinkReturn(it) }
        }
    }

    // Success is a snackbar and then gone; a FAILURE stays on the row (see
    // [GoogleNotLinkedGroup]) because it is something the user may want to act on.
    LaunchedEffect(linkOutcome) {
        if (linkOutcome is GoogleLinkOutcome.Linked) {
            snackbar.show(R.string.bt_conn_google_linked_ok)
            vm.clearLinkOutcome()
        }
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_connections),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_conn_intro),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            GoogleSection(
                state = google,
                busy = busy,
                failure = (linkOutcome as? GoogleLinkOutcome.Failed)?.message,
                onRetry = { vm.load() },
                onConnect = { vm.connect { url -> BtCustomTab.open(context, url) } },
                onUnlink = vm::unlink,
            )
            DriveSection(onOpenDataHome = onOpenDataHome)
            ConnectorSlots()

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Google account ───────────────────────────────────────────────────────────

@Composable
private fun GoogleSection(
    state: GoogleLinkResult?,
    busy: Boolean,
    failure: BtMessage?,
    onRetry: () -> Unit,
    onConnect: () -> Unit,
    onUnlink: (String, (BtMessage?) -> Unit) -> Unit,
) {
    // The feature is absent on this deployment — no header, no group, no trace.
    if (state is GoogleLinkResult.Unavailable) return

    BtSectionHeader(stringResource(R.string.bt_conn_google_title))
    when (state) {
        // Asking. The group is already there, so the section does not jump when
        // the answer lands; only its contents resolve.
        null -> BtGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                BtSkeleton(Modifier.fillMaxWidth().height(18.dp))
                Spacer(Modifier.height(8.dp))
                BtSkeleton(Modifier.width(140.dp).height(14.dp))
            }
        }

        GoogleLinkResult.WebOnly -> CapabilityWebOnlyGroup(webPath = WEB_CONNECTIONS_PATH)

        // We could not ASK. Not the same as being refused, so it must not render
        // as the settled "manage on the web" answer.
        is GoogleLinkResult.Failed -> BtGroup {
            BtInlineError(
                message = state.error.asMessage(),
                onRetry = onRetry,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        is GoogleLinkResult.Ready ->
            if (state.link.linked) {
                GoogleLinkedGroup(link = state.link, busy = busy, onUnlink = onUnlink)
            } else {
                GoogleNotLinkedGroup(busy = busy, failure = failure, onConnect = onConnect)
            }

        // Handled above; the `when` stays exhaustive without an else branch.
        GoogleLinkResult.Unavailable -> Unit
    }
}

/**
 * The linked identity, and the two-step unlink.
 *
 * The email IS the status — one positive row, no separate "Linked" line saying
 * the same thing a second time. The unlink reveals a password field first and
 * only then offers the destructive confirm, which is the web's flow and the
 * right one: re-auth is not a dialog to dismiss by reflex.
 */
@Composable
private fun GoogleLinkedGroup(
    link: GoogleLink,
    busy: Boolean,
    onUnlink: (String, (BtMessage?) -> Unit) -> Unit,
) {
    val bt = BtTheme.colors
    val snackbar = LocalBtSnackbar.current
    val locale = rememberBtLocale()
    var unlinking by remember(link.email) { mutableStateOf(false) }
    var password by remember(link.email) { mutableStateOf("") }
    var failure by remember(link.email) { mutableStateOf<BtMessage?>(null) }

    BtGroup {
        BtGroupRow(
            icon = Icons.Outlined.CheckCircle,
            iconTint = bt.gain,
            title = stringResource(
                R.string.bt_conn_google_linked_as,
                link.email.orEmpty(),
            ),
            subtitle = formatMemberSince(link.linkedAt, locale)
                ?.let { stringResource(R.string.bt_conn_google_linked_on, it) },
            trailing = if (link.canUnlink && !unlinking) {
                {
                    TextButton(onClick = { unlinking = true }) {
                        Text(
                            text = stringResource(R.string.bt_conn_google_unlink),
                            color = bt.loss,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else {
                null
            },
        )

        // The one constraint worth a line: the server refuses an unlink that
        // would leave the account with no way in. Stated instead of hiding the
        // button silently, because a missing control with no explanation reads
        // as a bug.
        if (!link.canUnlink) {
            NoteLine(
                icon = Icons.Outlined.WarningAmber,
                tint = bt.goldEmphasis,
                text = stringResource(R.string.bt_conn_google_only_method),
            )
        }

        if (link.canUnlink && unlinking) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.bt_conn_google_unlink_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.bt_conn_google_unlink_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                BtTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        failure = null
                    },
                    label = stringResource(R.string.bt_conn_google_password_label),
                    isPassword = true,
                    isError = failure != null,
                    enabled = !busy,
                    imeAction = ImeAction.Done,
                )
                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    BtFormError(it)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val canConfirm = password.isNotEmpty() && !busy
                    TextButton(
                        onClick = {
                            failure = null
                            onUnlink(password) { message ->
                                if (message == null) {
                                    unlinking = false
                                    password = ""
                                    snackbar.show(R.string.bt_conn_google_unlinked)
                                } else {
                                    failure = message
                                }
                            }
                        },
                        enabled = canConfirm,
                    ) {
                        Text(
                            text = stringResource(R.string.bt_conn_google_confirm_unlink),
                            color = if (canConfirm) bt.loss else bt.textMuted,
                        )
                    }
                    TextButton(
                        onClick = {
                            unlinking = false
                            password = ""
                            failure = null
                        },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                    }
                }
            }
        }
    }
}

/**
 * No Google identity yet — the state, and the one thing that can change it.
 *
 * The row still leaves the app, because a Google consent screen only exists in a
 * browser — but it now leaves for a URL the SERVER minted, carrying a one-time
 * account-bound ticket, and it comes back. The old row opened the web control
 * panel and left the user to finish there; the hint claiming it "returns here"
 * was the one line on this screen that was not true.
 *
 * While [busy] the row keeps its place and only its subtitle changes to "opening
 * Google" — swapping it for a spinner would collapse the group for the half
 * second `link/start` takes, and a layout that jumps under a finger reads as a
 * mis-tap.
 *
 * A [failure] from the last attempt sits under the row rather than in a snackbar:
 * it is the answer to something the user just did, and it should still be there
 * when they look back at the screen to decide whether to try again.
 */
@Composable
private fun GoogleNotLinkedGroup(
    busy: Boolean,
    failure: BtMessage?,
    onConnect: () -> Unit,
) {
    val bt = BtTheme.colors
    BtGroup {
        BtGroupRow(
            icon = Icons.Outlined.LinkOff,
            iconTint = bt.textMuted,
            title = stringResource(R.string.bt_conn_google_not_linked),
        )
        BtGroupRow(
            icon = Icons.Outlined.Link,
            iconTint = bt.goldEmphasis,
            title = stringResource(R.string.bt_conn_google_connect),
            titleColor = bt.goldEmphasis,
            subtitle = stringResource(
                if (busy) R.string.bt_conn_google_connecting else R.string.bt_conn_google_connect_hint,
            ),
            onClick = if (busy) null else onConnect,
            trailing = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = bt.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        failure?.let {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            ) {
                BtFormError(it)
            }
        }
    }
}

// ── Google Drive ─────────────────────────────────────────────────────────────

/**
 * Drive, as a signpost rather than a second controller.
 *
 * The web manages the paranoid vault's Drive medium in this panel because the
 * web has nowhere else to put it. This app does: "Where your data lives" owns
 * the whole medium — connect, disconnect, storage copies, the retired-server
 * purge — and two surfaces mutating that state would be a race with a UI on
 * both ends. So the group states what Drive is FOR and navigates in-app.
 */
@Composable
private fun DriveSection(onOpenDataHome: () -> Unit) {
    val bt = BtTheme.colors
    BtSectionHeader(stringResource(R.string.bt_conn_drive_title))
    BtGroup {
        Text(
            text = stringResource(R.string.bt_conn_drive_body),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        BtGroupRow(
            icon = Icons.Outlined.CloudSync,
            title = stringResource(R.string.bt_storage_settings_row),
            onClick = onOpenDataHome,
        )
    }
}

// ── More connectors ──────────────────────────────────────────────────────────

/**
 * The v6 connectors as designed-but-inert slots.
 *
 * Each row says three things — what it is, what it would do, and whether it
 * would stay connected or import once — and wears a plain "coming soon" chip.
 * No button, disabled or otherwise: a greyed-out control promises a permission
 * that no amount of tapping can acquire, and these have no endpoint at all.
 */
@Composable
private fun ConnectorSlots() {
    BtSectionHeader(stringResource(R.string.bt_conn_slots_title))
    BtGroup {
        ConnectorSlotRow(
            icon = Icons.Outlined.AccountBalance,
            name = stringResource(R.string.bt_conn_slot_bank_name),
            purpose = stringResource(R.string.bt_conn_slot_bank_purpose),
            sync = stringResource(R.string.bt_conn_sync_stay),
        )
        ConnectorSlotRow(
            icon = Icons.Outlined.PieChart,
            name = stringResource(R.string.bt_conn_slot_parqet_name),
            purpose = stringResource(R.string.bt_conn_slot_parqet_purpose),
            sync = stringResource(R.string.bt_conn_sync_one_time),
        )
    }
}

@Composable
private fun ConnectorSlotRow(
    icon: ImageVector,
    name: String,
    purpose: String,
    sync: String,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
            )
            Text(
                text = purpose,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sync,
                style = MaterialTheme.typography.labelSmall,
                color = bt.textFaint,
            )
        }
        Spacer(Modifier.width(12.dp))
        BtChip(text = stringResource(R.string.bt_conn_coming_soon))
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

/** The web control panel these two screens hand off to when the bearer is refused. */
internal const val WEB_CONNECTIONS_PATH = "/control/connections"
internal const val WEB_AUTHORIZED_APPS_PATH = "/control/authorized-apps"

/**
 * The "not released by this server yet" block, shared by both connection panels.
 *
 * What it is NOT: an error. Nothing is broken, nothing the user did caused it,
 * and no amount of retrying or re-logging-in will change it — the platform's
 * bearer allowlist simply has not opened this route to API tokens. So it reads
 * as a statement plus the one thing the user can actually do right now, and it
 * points at the MATCHING web panel rather than the web app's front door: sending
 * someone to a home page while telling them "this is where the feature lives"
 * is only half an answer.
 */
@Composable
internal fun CapabilityWebOnlyGroup(webPath: String) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    BtGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_cap_web_title),
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.bt_cap_web_body),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
        }
        BtGroupRow(
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            iconTint = bt.goldEmphasis,
            title = stringResource(R.string.bt_cap_web_open),
            titleColor = bt.goldEmphasis,
            subtitle = stringResource(R.string.bt_settings_managed_on_web),
            onClick = { openBtWebApp(context, webPath) },
        )
    }
}

/** A quiet, icon-led note inside a group — a constraint, not a failure. */
@Composable
private fun NoteLine(icon: ImageVector, tint: Color, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = BtTheme.colors.textSecondary,
        )
    }
}
