package at.bettertrack.app.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.account.AccountPinState
import at.bettertrack.app.data.account.TwoFactorState
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.ProfileSettingsResponse
import at.bettertrack.app.data.auth.SessionUser
import at.bettertrack.app.data.i18n.AppLanguage
import at.bettertrack.app.data.i18n.LocaleManager
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.data.prefs.themeModeFromName
import at.bettertrack.app.data.repo.GoogleLinkResult
import at.bettertrack.app.data.repo.TaxSettings
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtChoiceSheet
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtPickerOption
import at.bettertrack.app.ui.tax.taxModeLabelRes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The body of one wizard step — everything between the frame's question and its
 * anchored action.
 *
 * Every step follows the same three rules:
 *
 *  1. **It shows the current answer**, read from the same endpoint the real
 *     editor writes to. Nothing is assumed and nothing is defaulted into a
 *     confident-looking value.
 *  2. **It hands off** to that real editor rather than embedding a second copy of
 *     it (see [FirstRunEditor]).
 *  3. **It reports honestly.** [FirstRunStepStatus.COMPLETE] only when the thing
 *     is observably set up; otherwise SKIPPED — including when the read failed,
 *     because "we could not tell" is not "it is done".
 *
 * [revision] increments whenever a hosted editor closes, so every read here
 * re-runs on the way back and the summary cannot show a pre-visit answer.
 */
@Composable
internal fun FirstRunStepBody(
    step: FirstRunStepMeta,
    user: SessionUser?,
    revision: Int,
    completing: Boolean,
    completeError: BtMessage?,
    completeSignedOut: Boolean,
    recorded: Map<FirstRunStepId, FirstRunStepStatus>,
    onReport: (FirstRunStepStatus, Boolean) -> Unit,
    onOpenEditor: (FirstRunEditor) -> Unit,
    onRetryFinish: () -> Unit,
) {
    val bt = BtTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        step.hint?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
        }
        when (step.id) {
            FirstRunStepId.PROFILE -> ProfileStepBody(user, onReport)
            FirstRunStepId.VERIFY_EMAIL -> VerifyEmailStepBody(user, revision, onReport)
            FirstRunStepId.SECURITY -> SecurityStepBody(revision, onReport, onOpenEditor)
            FirstRunStepId.PREFERENCES -> PreferencesStepBody(revision, onReport, onOpenEditor)
            FirstRunStepId.TAX -> TaxStepBody(revision, onReport, onOpenEditor)
            FirstRunStepId.PUBLIC_PROFILE -> PublicProfileStepBody(revision, onReport, onOpenEditor)
            FirstRunStepId.DONE -> DoneStepBody(
                recorded = recorded,
                completing = completing,
                completeError = completeError,
                completeSignedOut = completeSignedOut,
                onReport = onReport,
                onRetryFinish = onRetryFinish,
            )
        }
    }
}

// ── 1 · profile ──────────────────────────────────────────────────────────────

/**
 * Who you are. A confirmation, not a form: registration collected both values and
 * there is no rename endpoint anywhere in the API (nothing on
 * `PATCH /settings/account` or `PUT /social/profile` carries a name), so showing
 * them and saying so plainly is the honest surface. Seeing your identity IS the
 * step, which is why it always reports complete — the same rule the web uses.
 */
@Composable
private fun ProfileStepBody(
    user: SessionUser?,
    onReport: (FirstRunStepStatus, Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    LaunchedEffect(Unit) { onReport(FirstRunStepStatus.COMPLETE, false) }
    BtGroup {
        BtGroupRow(
            icon = Icons.Outlined.Person,
            title = stringResource(R.string.bt_firstrun_profile_name),
            subtitle = user?.username?.ifBlank { null } ?: stringResource(R.string.bt_firstrun_unknown_value),
        )
        BtGroupRow(
            icon = Icons.Outlined.MailOutline,
            title = stringResource(R.string.bt_firstrun_profile_email),
            subtitle = user?.email?.ifBlank { null } ?: stringResource(R.string.bt_firstrun_unknown_value),
        )
    }
    Text(
        text = stringResource(R.string.bt_firstrun_profile_parked),
        style = MaterialTheme.typography.bodySmall,
        color = bt.textSecondary,
    )
}

// ── 2 · verify email ─────────────────────────────────────────────────────────

/**
 * Email verification, with no invented backend.
 *
 * Only a Google identity arrives already verified — the API sets `emailVerified`
 * from the ID token — so `GET /auth/google/link-status` is what decides this. A
 * password account has nothing to verify against: there is no verification-mail
 * delivery and no `POST /auth/email/verify` route, so the step says so and
 * records itself as skipped rather than pretending to offer a code box.
 */
@Composable
private fun VerifyEmailStepBody(
    user: SessionUser?,
    revision: Int,
    onReport: (FirstRunStepStatus, Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    var result by remember(revision) { mutableStateOf<GoogleLinkResult?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(revision, reload) {
        result = null
        result = AppGraph.connectionsRepository.googleLink()
    }

    val link = (result as? GoogleLinkResult.Ready)?.link
    val verified = link?.linked == true
    LaunchedEffect(result, verified) {
        onReport(
            if (verified) FirstRunStepStatus.COMPLETE else FirstRunStepStatus.SKIPPED,
            result == null,
        )
    }

    when {
        result == null -> StepLoading(online)

        verified -> BtGroup {
            BtGroupRow(
                icon = Icons.Outlined.MailOutline,
                title = stringResource(R.string.bt_firstrun_verify_verified),
                subtitle = link?.email?.ifBlank { null } ?: user?.email?.ifBlank { null },
            )
        }

        result is GoogleLinkResult.Failed -> BtInlineError(
            message = (result as GoogleLinkResult.Failed).error.asMessage(),
            onRetry = { reload++ },
        )

        else -> {
            BtGroup {
                BtGroupRow(
                    icon = Icons.Outlined.MailOutline,
                    title = user?.email?.ifBlank { null }
                        ?: stringResource(R.string.bt_firstrun_unknown_value),
                    subtitle = stringResource(R.string.bt_firstrun_verify_parked_sub),
                )
            }
            Text(
                text = stringResource(R.string.bt_firstrun_verify_parked),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
        }
    }
}

// ── 3 · security ─────────────────────────────────────────────────────────────

/**
 * The second lock. Complete when ANY of the three real measures is on — account
 * 2FA, the account PIN, or this device's app lock — because each of them is a
 * genuine second factor and requiring a particular one would be an invented rule.
 * The hand-off is `SecurityScreen` itself, fully wired (see [FirstRunEditor]).
 */
@Composable
private fun SecurityStepBody(
    revision: Int,
    onReport: (FirstRunStepStatus, Boolean) -> Unit,
    onOpenEditor: (FirstRunEditor) -> Unit,
) {
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    val lock by AppGraph.appLockController.config.collectAsStateWithLifecycle()
    var twoFactor by remember(revision) { mutableStateOf<TwoFactorState?>(null) }
    var accountPin by remember(revision) { mutableStateOf<AccountPinState?>(null) }
    var loading by remember(revision) { mutableStateOf(true) }
    var error by remember(revision) { mutableStateOf<BtMessage?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(revision, reload) {
        loading = true
        error = null
        val repo = AppGraph.accountRepository
        when (val r = repo.twoFactorStatus()) {
            is BtResult.Ok -> twoFactor = r.value
            is BtResult.Err -> error = r.error.asMessage()
        }
        when (val r = repo.accountPinState()) {
            is BtResult.Ok -> accountPin = r.value
            is BtResult.Err -> if (error == null) error = r.error.asMessage()
        }
        loading = false
    }

    val appLockOn = lock.enabled && lock.hasPin
    val anyOn = twoFactor?.anyEnabled == true || accountPin?.pinSet == true || appLockOn
    LaunchedEffect(anyOn, loading) {
        onReport(
            if (anyOn) FirstRunStepStatus.COMPLETE else FirstRunStepStatus.SKIPPED,
            loading,
        )
    }

    if (loading) {
        StepLoading(online)
        return
    }
    BtGroup {
        BtGroupRow(
            icon = Icons.Outlined.Shield,
            title = stringResource(R.string.bt_firstrun_security_row),
            subtitle = stringResource(
                if (anyOn) R.string.bt_firstrun_security_on else R.string.bt_firstrun_security_off,
            ),
            onClick = { onOpenEditor(FirstRunEditor.Security) },
        )
    }
    error?.let { BtInlineError(message = it, onRetry = { reload++ }) }
}

// ── 4 · preferences ──────────────────────────────────────────────────────────

/**
 * Language and appearance.
 *
 * Both always have a value — there is no unset state to skip — so seeing and
 * accepting them completes the step, exactly as on the web. The only thing that
 * can leave it unresolved is a failed read of the account's language, which is
 * surfaced rather than swallowed: it is the one of the two that lives on the
 * server, and a wizard that silently showed the device default would be lying
 * about what a later web login will use.
 */
@Composable
private fun PreferencesStepBody(
    revision: Int,
    onReport: (FirstRunStepStatus, Boolean) -> Unit,
    onOpenEditor: (FirstRunEditor) -> Unit,
) {
    val context = LocalContext.current
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    val themeMode by AppGraph.devicePrefs.themeMode.collectAsStateWithLifecycle()
    var loading by remember(revision) { mutableStateOf(true) }
    var error by remember(revision) { mutableStateOf<BtMessage?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showTheme by remember { mutableStateOf(false) }

    LaunchedEffect(revision, reload) {
        loading = true
        error = null
        when (val r = AppGraph.accountRepository.accountLocale()) {
            is BtResult.Ok -> Unit
            is BtResult.Err -> error = r.error.asMessage()
        }
        loading = false
    }

    LaunchedEffect(loading, error) {
        onReport(
            if (!loading && error == null) FirstRunStepStatus.COMPLETE else FirstRunStepStatus.SKIPPED,
            loading,
        )
    }

    // The device's applied language, not the account's: this is the row that says
    // what the app in your hand is speaking right now, and `LanguageScreen` (the
    // hand-off) mirrors an explicit choice to the account on the way out.
    val language = remember(revision) { LocaleManager.current(context) }
    BtGroup {
        BtGroupRow(
            icon = Icons.Outlined.Translate,
            title = stringResource(R.string.bt_firstrun_prefs_language),
            subtitle = stringResource(languageLabelRes(language)),
            onClick = { onOpenEditor(FirstRunEditor.Language) },
        )
        BtGroupRow(
            icon = Icons.Outlined.Contrast,
            title = stringResource(R.string.bt_firstrun_prefs_theme),
            subtitle = stringResource(firstRunThemeLabelRes(themeMode)),
            onClick = { showTheme = true },
        )
    }
    error?.let { BtInlineError(message = it, onRetry = { reload++ }) }
    if (loading) StepLoading(online)

    if (showTheme) {
        BtChoiceSheet(
            title = stringResource(R.string.bt_firstrun_prefs_theme),
            options = BtThemeMode.entries.map {
                BtPickerOption(value = it.name, label = stringResource(firstRunThemeLabelRes(it)))
            },
            selected = themeMode.name,
            busy = false,
            message = null,
            onPick = { AppGraph.devicePrefs.setThemeMode(themeModeFromName(it)) },
            closeLabel = stringResource(R.string.bt_action_done),
            onDismiss = { showTheme = false },
        )
    }
}

// ── 5 · tax ──────────────────────────────────────────────────────────────────

/**
 * Tax handling — the account-level default, through the real
 * `TaxSettingsScreen`.
 *
 * Only a change made during this run counts as complete: the stored default has a
 * value from the moment the account exists, so "it is set" cannot by itself tell
 * a decision from an untouched default. The baseline is captured on the first
 * read and deliberately survives [revision] bumps, so the comparison is against
 * where the run started rather than against the previous visit.
 */
@Composable
private fun TaxStepBody(
    revision: Int,
    onReport: (FirstRunStepStatus, Boolean) -> Unit,
    onOpenEditor: (FirstRunEditor) -> Unit,
) {
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    var settings by remember(revision) { mutableStateOf<TaxSettings?>(null) }
    var loading by remember(revision) { mutableStateOf(true) }
    var error by remember(revision) { mutableStateOf<BtMessage?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val baseline = remember { mutableStateOf<TaxSettings?>(null) }

    LaunchedEffect(revision, reload) {
        loading = true
        error = null
        when (val r = AppGraph.taxRepository.userTaxSettings()) {
            is BtResult.Ok -> {
                settings = r.value
                if (baseline.value == null) baseline.value = r.value
            }

            is BtResult.Err -> error = r.error.asMessage()
        }
        loading = false
    }

    val changed = baseline.value != null && settings != null && settings != baseline.value
    LaunchedEffect(changed, loading) {
        onReport(
            if (changed) FirstRunStepStatus.COMPLETE else FirstRunStepStatus.SKIPPED,
            loading,
        )
    }

    if (loading) {
        StepLoading(online)
        return
    }
    BtGroup {
        BtGroupRow(
            icon = Icons.Outlined.Percent,
            title = stringResource(R.string.bt_firstrun_tax_row),
            subtitle = settings?.let {
                if (it.isKnownMode) stringResource(taxModeLabelRes(it.mode)) else it.mode
            } ?: stringResource(R.string.bt_firstrun_unknown_value),
            onClick = { onOpenEditor(FirstRunEditor.Tax) },
        )
    }
    error?.let { BtInlineError(message = it, onRetry = { reload++ }) }
}

// ── 6 · public profile ───────────────────────────────────────────────────────

/**
 * Your public face — the real `PublicProfileScreen`, which keeps the §16 friction
 * ladder (turning a profile public needs the explicit acknowledgement) intact.
 * Shortening that for a slimmer wizard would weaken a privacy boundary.
 *
 * A private profile is the default, so "still private" cannot be told apart from
 * "walked past": only an actual change during this run counts.
 */
@Composable
private fun PublicProfileStepBody(
    revision: Int,
    onReport: (FirstRunStepStatus, Boolean) -> Unit,
    onOpenEditor: (FirstRunEditor) -> Unit,
) {
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    var profile by remember(revision) { mutableStateOf<ProfileSettingsResponse?>(null) }
    var loading by remember(revision) { mutableStateOf(true) }
    var error by remember(revision) { mutableStateOf<BtMessage?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val baseline = remember { mutableStateOf<Pair<Boolean, String?>?>(null) }

    LaunchedEffect(revision, reload) {
        loading = true
        error = null
        when (val r = AppGraph.accountRepository.socialProfile()) {
            is BtResult.Ok -> {
                profile = r.value
                if (baseline.value == null) baseline.value = r.value.isPublic to r.value.bio
            }

            is BtResult.Err -> error = r.error.asMessage()
        }
        loading = false
    }

    val now = profile?.let { it.isPublic to it.bio }
    val changed = baseline.value != null && now != null && now != baseline.value
    LaunchedEffect(changed, loading) {
        onReport(
            if (changed) FirstRunStepStatus.COMPLETE else FirstRunStepStatus.SKIPPED,
            loading,
        )
    }

    if (loading) {
        StepLoading(online)
        return
    }
    BtGroup {
        BtGroupRow(
            icon = Icons.Outlined.Public,
            title = stringResource(R.string.bt_firstrun_public_row),
            subtitle = stringResource(
                when {
                    profile == null -> R.string.bt_firstrun_unknown_value
                    profile?.isPublic == true -> R.string.bt_firstrun_public_on
                    else -> R.string.bt_firstrun_public_off
                },
            ),
            onClick = { onOpenEditor(FirstRunEditor.PublicProfile) },
        )
    }
    error?.let { BtInlineError(message = it, onRetry = { reload++ }) }
}

// ── 7 · done ─────────────────────────────────────────────────────────────────

/**
 * What you just set, and what is still waiting — read from what THIS run
 * recorded, so the list reflects the pass the user just made rather than an
 * accumulated history.
 *
 * This is also where the one write of the whole wizard is reported: the terminal
 * action posts `/auth/first-run/complete`, and a failure stays on screen with a
 * retry (plus the frame's escape hatch) instead of quietly leaving the account
 * pending.
 */
@Composable
private fun DoneStepBody(
    recorded: Map<FirstRunStepId, FirstRunStepStatus>,
    completing: Boolean,
    completeError: BtMessage?,
    completeSignedOut: Boolean,
    onReport: (FirstRunStepStatus, Boolean) -> Unit,
    onRetryFinish: () -> Unit,
) {
    val bt = BtTheme.colors
    LaunchedEffect(completing) { onReport(FirstRunStepStatus.COMPLETE, completing) }
    BtGroup {
        FIRST_RUN_STEPS.filterNot { it.terminal }.forEach { meta ->
            val complete = recorded[meta.id] == FirstRunStepStatus.COMPLETE
            BtGroupRow(
                title = stringResource(meta.label),
                trailing = {
                    Text(
                        text = stringResource(
                            if (complete) R.string.bt_firstrun_done_set else R.string.bt_firstrun_done_later,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (complete) bt.gainSoft else bt.textSecondary,
                    )
                },
            )
        }
    }
    if (completeSignedOut) {
        Text(
            text = stringResource(R.string.bt_firstrun_signed_out),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
    }
    completeError?.let { BtInlineError(message = it, onRetry = onRetryFinish) }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

/**
 * The read-in-flight state.
 *
 * When the device is offline it says so instead of spinning forever on a request
 * that cannot land — every one of these steps reads the account, and a wizard on
 * a plane should not look broken.
 */
@Composable
private fun StepLoading(online: Boolean) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (online) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = bt.gold, strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = bt.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = stringResource(
                if (online) R.string.bt_firstrun_loading else R.string.bt_firstrun_offline,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
    }
}

private fun languageLabelRes(language: AppLanguage): Int = when (language) {
    AppLanguage.System -> R.string.bt_lang_system
    AppLanguage.English -> R.string.bt_lang_english
    AppLanguage.German -> R.string.bt_lang_german
}

/**
 * Local copy of Settings' theme labels. Three `when` arms against an enum the
 * compiler makes exhaustive — cheaper than widening a private helper in a
 * 1,400-line file, and it cannot drift silently: adding a mode breaks both.
 */
private fun firstRunThemeLabelRes(mode: BtThemeMode): Int = when (mode) {
    BtThemeMode.System -> R.string.bt_settings_theme_system
    BtThemeMode.Light -> R.string.bt_settings_theme_light
    BtThemeMode.Dark -> R.string.bt_settings_theme_dark
}
