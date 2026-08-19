package at.bettertrack.app.ui.connections

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.ParanoidModeState
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.AuthorizedApp
import at.bettertrack.app.data.repo.AuthorizedAppsResult
import at.bettertrack.app.data.repo.ConnectionsRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtActionSheet
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSheetAction
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.settings.formatMemberSince
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

// ── State ────────────────────────────────────────────────────────────────────

internal class AuthorizedAppsViewModel(
    private val repo: ConnectionsRepository,
) : ViewModel() {

    /** `null` while the read is in flight — see [ConnectionsViewModel.google]. */
    private val _apps = MutableStateFlow<AuthorizedAppsResult?>(null)
    val apps: StateFlow<AuthorizedAppsResult?> = _apps.asStateFlow()

    /** The grant currently being revoked, so exactly one row shows the busy label. */
    private val _revoking = MutableStateFlow<String?>(null)
    val revoking: StateFlow<String?> = _revoking.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _apps.value = repo.authorizedApps() }
    }

    /** `onDone(null)` means the app's tokens are gone; the list is re-read. */
    fun revoke(grantId: String, onDone: (BtMessage?) -> Unit) {
        if (_revoking.value != null) return
        viewModelScope.launch {
            _revoking.value = grantId
            val r = repo.revokeApp(grantId)
            _revoking.value = null
            when (r) {
                is BtResult.Ok -> {
                    onDone(null)
                    load()
                }

                is BtResult.Err -> onDone(BtMessage(R.string.bt_grants_revoke_failed))
            }
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * Settings → **Authorized apps**, native (owner order 2026-08-08).
 *
 * The privacy half of API access: which third-party apps can reach THIS account,
 * what each of them may do — in the plain words the consent screen used, never
 * the raw `portfolio:read` strings — and one two-step revoke that kills an app's
 * tokens immediately.
 *
 * ## The capability probe
 *
 * `/settings/oauth-grants` is bearer-callable under `account:security` on
 * production, and a deployment that has not opened it answers
 * `403 API_KEY_FORBIDDEN` before the service is reached. The read doubles as the
 * probe, so the screen draws one of four genuinely different things:
 *
 *  - **probing** — skeletons, in the shape the list will have;
 *  - **WebOnly** — the calm explainer plus a hand-off to
 *    `/control/authorized-apps` in a Custom Tab. Not an error: nothing is
 *    broken, and no retry or re-login can change it;
 *  - **Unknown** (offline / 5xx) — an inline error WITH a retry, because a
 *    transient failure must never harden into the permanent-sounding message;
 *  - **Allowed** — the real grants, or the empty state when there are none.
 *
 * ## This app is in its own list, on purpose
 *
 * `listGrants` does not filter first-party, and the platform confirmed that is a
 * deliberate feature rather than an oversight: that row is how someone whose
 * phone was lost or stolen kills its access from a browser. **Remote kill only
 * works if the row is visible**, so this screen annotates it — it does not hide
 * it. Name, scopes and dates all render exactly as any other grant's.
 *
 * What is removed is only the REVOKE, and only on the row this install is
 * actually running on ([isOwnGrant]): tapping it here would kill the tokens of
 * the device in the user's hand, mid-screen, with nothing left to explain what
 * happened. The same intent has an honest expression one screen away — "Sign
 * out", which revokes this grant on its way out ([at.bettertrack.app.data.auth.AuthRepository]).
 * From any OTHER device, and from the web, the row stays fully revocable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorizedAppsScreen(onBack: () -> Unit) {
    val vm: AuthorizedAppsViewModel = viewModel(key = "authorized-apps") {
        AuthorizedAppsViewModel(AppGraph.connectionsRepository)
    }
    val bt = BtTheme.colors
    val state by vm.apps.collectAsStateWithLifecycle()
    val revoking by vm.revoking.collectAsStateWithLifecycle()
    // Paranoid mode blocks some of the scopes a grant carries. The grant still
    // HAS them and they go live again the moment the mode is turned off, so they
    // are marked rather than dropped — see [GrantBlock].
    val paranoid by ParanoidModeState.active.collectAsStateWithLifecycle()

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_authorized_apps),
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
            // Kept prose: revoking is immediate and the app must be re-authorized.
            Text(
                text = stringResource(R.string.bt_grants_intro),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            when (val s = state) {
                null -> BtGroup {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BtSkeleton(Modifier.width(180.dp).height(18.dp))
                        BtSkeleton(Modifier.fillMaxWidth().height(14.dp))
                        BtSkeleton(Modifier.fillMaxWidth().height(14.dp))
                        BtSkeleton(Modifier.width(220.dp).height(12.dp))
                    }
                }

                AuthorizedAppsResult.WebOnly -> CapabilityWebOnlyGroup(webPath = WEB_AUTHORIZED_APPS_PATH)

                is AuthorizedAppsResult.Failed -> BtGroup {
                    BtInlineError(
                        message = s.error.asMessage(),
                        onRetry = { vm.load() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                is AuthorizedAppsResult.Ready ->
                    if (s.apps.isEmpty()) {
                        BtGroup {
                            BtInlineEmpty(
                                text = stringResource(R.string.bt_grants_empty),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        // One group PER app, not one group with dividers in it:
                        // a group is the rows that are parts of one subject, and
                        // each grant is its own subject with its own action. The
                        // tonal step separates them, which is the app-wide rule
                        // ([BtGroup]) — a ruled run inside one block would put a
                        // second, competing separator on the same screen.
                        s.apps.forEach { app ->
                            BtGroup {
                                GrantBlock(
                                    app = app,
                                    // This install's own grant: labelled and
                                    // fully visible, but not revocable from
                                    // here. See the class doc.
                                    isThisApp = isOwnGrant(app.clientId, app.current),
                                    paranoid = paranoid,
                                    revoking = revoking == app.id,
                                    // One revoke at a time: a second confirm while
                                    // the first is in flight would queue a request
                                    // whose outcome nobody is watching.
                                    locked = revoking != null && revoking != app.id,
                                    onRevoke = vm::revoke,
                                )
                            }
                        }
                    }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One authorized app: what it can do, since when, and the two-step revoke.
 *
 * Two steps because this is irreversible from the user's side — the app's tokens
 * die instantly and it has to be authorized again from scratch — and because
 * "Revoke access" sitting one tap from a scrolling list is exactly the shape of
 * a mis-tap. The confirm arrives as a bottom sheet named after the app it is
 * about (owner rule: user-facing confirmations come up from the bottom), so the
 * question can never be answered without having read whose access it is.
 *
 * [isThisApp] removes the affordance entirely rather than disabling it. A greyed
 * "Revoke access" would say the control exists and is temporarily unavailable,
 * which is false: it is not offered here at all, and the row says why.
 */
@Composable
private fun GrantBlock(
    app: AuthorizedApp,
    isThisApp: Boolean,
    paranoid: Boolean,
    revoking: Boolean,
    locked: Boolean,
    onRevoke: (String, (BtMessage?) -> Unit) -> Unit,
) {
    val bt = BtTheme.colors
    val snackbar = LocalBtSnackbar.current
    val locale = rememberBtLocale()
    var confirming by remember(app.id) { mutableStateOf(false) }
    var failure by remember(app.id) { mutableStateOf<BtMessage?>(null) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isThisApp) Icons.Outlined.Smartphone else Icons.Outlined.VerifiedUser,
                contentDescription = null,
                tint = if (isThisApp) bt.goldEmphasis else bt.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.bt_grants_can_access, app.appName),
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }

        if (isThisApp) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.bt_grants_this_device),
                style = MaterialTheme.typography.labelMedium,
                color = bt.goldEmphasis,
            )
        }

        // The plain-language scope descriptions, not the raw scope strings: this
        // is a privacy control, so it reads in the user's words. An unmapped
        // scope shows its wire name rather than vanishing — a shortened list
        // would understate what the app was allowed to do.
        Spacer(Modifier.height(8.dp))
        val inactiveSuffix = stringResource(R.string.bt_grants_scope_inactive)
        app.scopes.forEach { scope ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textFaint,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = scopeLine(
                        label = scopeLabelRes(scope)?.let { stringResource(it) } ?: scope,
                        inactive = paranoid && isParanoidBlockedScope(scope),
                        inactiveSuffix = inactiveSuffix,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
            }
        }

        grantMeta(app.createdAt, app.lastUsedAt, locale)?.let { meta ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (meta.lastUsed == null) {
                    stringResource(R.string.bt_grants_authorized_never, meta.created)
                } else {
                    stringResource(R.string.bt_grants_authorized_since, meta.created, meta.lastUsed)
                },
                style = MaterialTheme.typography.labelSmall,
                color = bt.textFaint,
            )
        }

        failure?.let {
            Spacer(Modifier.height(8.dp))
            BtFormError(it)
        }

        if (isThisApp) {
            // This app's own grant ends here: no revoke row at all, and one line
            // saying where the equivalent action actually lives.
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bt_grants_this_device_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (revoking) {
                    Text(
                        text = stringResource(R.string.bt_grants_revoking),
                        style = MaterialTheme.typography.labelLarge,
                        color = bt.textMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    TextButton(onClick = { confirming = true }, enabled = !locked) {
                        Text(
                            text = stringResource(R.string.bt_grants_revoke),
                            color = if (locked) bt.textMuted else bt.loss,
                        )
                    }
                }
            }
        }
    }

    if (confirming && !isThisApp) {
        BtActionSheet(
            title = app.appName,
            subtitle = stringResource(R.string.bt_grants_revoke_sheet_body),
            actions = listOf(
                BtSheetAction(
                    label = stringResource(R.string.bt_grants_revoke_confirm),
                    destructive = true,
                    enabled = !locked,
                    onClick = {
                        failure = null
                        onRevoke(app.id) { message ->
                            if (message == null) {
                                snackbar.show(R.string.bt_grants_revoked)
                            } else {
                                failure = message
                            }
                        }
                    },
                ),
            ),
            onDismiss = { confirming = false },
        )
    }
}

// ── Pure helpers (unit-tested) ───────────────────────────────────────────────

/**
 * Is this grant the credential THIS install is running on?
 *
 * The list deliberately includes first-party grants — that row is how a user
 * kills a lost or stolen phone's access from a browser — so the screen has to
 * pick its own row out of it rather than expect the server to have removed it.
 *
 * ## Two sources, in priority order
 *
 * **[current]** (platform #1390) is the exact answer and the only one that can
 * be exact: it is derived server-side from the token the request presented, so
 * it distinguishes this phone from the same app on the user's tablet. When the
 * server states it — `true` or `false` — that verdict wins outright.
 *
 * **[grantClientId]** is the fallback while #1390 is unshipped. It identifies the
 * APP, not the INSTALL, so on an account with BetterTrack Mobile on two devices
 * it matches both rows and both lose their revoke button. That is the safe
 * direction of a wrong answer here: the cost is a revoke the user has to perform
 * from the web instead, versus revoking the phone in their hand by accident. It
 * disappears by itself the day the flag ships.
 *
 * `clientId` is also the only usable fallback field — `appName` is display copy
 * the platform can change, and the grant `id` is per authorization, so it differs
 * per device and after every re-consent.
 *
 * A blank configured id can never match: without that guard a build with no
 * `OAUTH_CLIENT_ID`, meeting a grant with a blank `clientId`, would strip the
 * revoke button off a real third-party app.
 */
internal fun isOwnGrant(
    grantClientId: String,
    current: Boolean? = null,
    ownClientId: String = BuildConfig.OAUTH_CLIENT_ID,
): Boolean = current ?: (ownClientId.isNotBlank() && grantClientId == ownClientId)

/**
 * Scopes the paranoid privacy mode refuses at the server.
 *
 * A one-for-one mirror of the web's `PARANOID_BLOCKED_SCOPES`
 * (`apps/web/src/ui/ScopePicker.tsx`), pinned by `AuthorizedAppsFormatTest`
 * against the same list its `ScopePicker.test.tsx` pins. Note what is NOT in it:
 * `vault:sync` stays live, because the ciphertext sync is the whole point of the
 * mode — blocking it would break the vault it exists to protect.
 */
internal fun isParanoidBlockedScope(scope: String): Boolean = scope in PARANOID_BLOCKED_SCOPES

private val PARANOID_BLOCKED_SCOPES = setOf(
    "portfolio:read",
    "portfolio:write",
    "cash:read",
    "cash:write",
    "mirrorchain:read",
    "mirrorchain:write",
)

/**
 * One scope's line — struck through and suffixed when the privacy mode blocks it.
 *
 * The web's rule, and the right one: a blocked scope is MARKED, never dropped.
 * The grant genuinely carries it and it goes live again the moment paranoid mode
 * is turned off, so a shortened list would understate what the app was allowed
 * to do — the exact opposite of what this screen is for. The strike-through says
 * "granted but not in force"; the suffix says why, because a strike-through
 * alone is not information a screen reader can convey.
 */
internal fun scopeLine(label: String, inactive: Boolean, inactiveSuffix: String): AnnotatedString =
    if (!inactive) {
        AnnotatedString(label)
    } else {
        buildAnnotatedString {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(label) }
            append(" ($inactiveSuffix)")
        }
    }

/**
 * Wire scope → the plain-language sentence the consent screen showed.
 *
 * Mirrors the platform's `OAUTH_SCOPE_LABELS` one-for-one. `null` for a scope
 * this build has never heard of, and the caller then renders the RAW wire string:
 * a scope the platform ships after this release still describes a real
 * permission the app was granted, and dropping it from the list would quietly
 * under-report what a third party can do — the opposite of this screen's job.
 */
@StringRes
internal fun scopeLabelRes(scope: String): Int? = when (scope) {
    "portfolio:read" -> R.string.bt_scope_portfolio_read
    "portfolio:write" -> R.string.bt_scope_portfolio_write
    "workboard:read" -> R.string.bt_scope_workboard_read
    "workboard:write" -> R.string.bt_scope_workboard_write
    "market:read" -> R.string.bt_scope_market_read
    "social:read" -> R.string.bt_scope_social_read
    "social:write" -> R.string.bt_scope_social_write
    "notifications:read" -> R.string.bt_scope_notifications_read
    "notifications:write" -> R.string.bt_scope_notifications_write
    "chat:read" -> R.string.bt_scope_chat_read
    "chat:write" -> R.string.bt_scope_chat_write
    "account:security" -> R.string.bt_scope_account_security
    "alerts:read" -> R.string.bt_scope_alerts_read
    "alerts:write" -> R.string.bt_scope_alerts_write
    "cash:read" -> R.string.bt_scope_cash_read
    "cash:write" -> R.string.bt_scope_cash_write
    "mirrorchain:read" -> R.string.bt_scope_mirrorchain_read
    "mirrorchain:write" -> R.string.bt_scope_mirrorchain_write
    "vault:sync" -> R.string.bt_scope_vault_sync
    "feedback:write" -> R.string.bt_scope_feedback_write
    else -> null
}

/** The two dates a grant's meta line needs, already formatted. */
internal data class GrantMeta(val created: String, val lastUsed: String?)

/**
 * The "Authorized … · last used …" line's arguments, or `null` when there is no
 * honest line to draw.
 *
 * [created] is what the sentence is ABOUT, so an absent or unparseable
 * `createdAt` drops the whole line rather than rendering "Authorized — ". A
 * missing `lastUsedAt` is different: it is a real answer ("never used"), and the
 * caller picks the other resource for it.
 *
 * Formatting goes through the app's own [formatMemberSince] — device zone,
 * localized medium style — rather than a second date helper that would drift
 * from the rest of Settings.
 */
internal fun grantMeta(
    createdAt: String?,
    lastUsedAt: String?,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): GrantMeta? {
    val created = formatMemberSince(createdAt, locale, zone) ?: return null
    return GrantMeta(created, formatMemberSince(lastUsedAt, locale, zone))
}
