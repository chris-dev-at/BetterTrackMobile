package at.bettertrack.app.ui.feedback

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.FeedbackContextDto
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.repo.FEEDBACK_MESSAGE_MAX
import at.bettertrack.app.data.repo.FEEDBACK_SUBJECT_MAX
import at.bettertrack.app.data.repo.FeedbackCategory
import at.bettertrack.app.data.repo.FeedbackDraft
import at.bettertrack.app.data.repo.FeedbackOrigin
import at.bettertrack.app.data.repo.feedbackContextOf
import at.bettertrack.app.data.repo.isSendable
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.btFieldColors
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.launch

/**
 * The sentence the composer shows when a submission fails.
 *
 * Everything defers to the app-wide catalogue via [asMessage] — with exactly one
 * exception, and the exception is about honesty rather than polish.
 *
 * `/feedback` is rate-limited to roughly **five submissions per user per hour**.
 * The catalogue's generic `RATE_LIMITED` copy says *"wait a moment"*, which is off
 * by two orders of magnitude for an hourly window: somebody who taps Send again
 * thirty seconds later, as instructed, gets refused again and learns the app lies.
 *
 * The branch keys off the HTTP **status**, not an error code, and that is
 * deliberate. The live `openapi.json` documents only `201`, `400`, `401` and a
 * generic error envelope for this route, so the `code` the limiter emits is not
 * knowable from the contract — and an unmapped code falls through
 * [asMessage] to the generic sentence PLUS the server's ENGLISH diagnostic, which
 * on a German phone is exactly the failure P0-4 exists to prevent. Guessing a code
 * into `BtErrorCopy` would be inventing a wire fact; `429` is the status the
 * contract itself names, so that is what this reads.
 *
 * Kept a pure top-level function so the branch is unit-tested without a Compose
 * runtime.
 */
internal fun feedbackFailureMessage(error: BtApiError): BtMessage =
    if (error.httpStatus == 429) {
        BtMessage(R.string.bt_feedback_err_rate_limited)
    } else {
        error.asMessage()
    }

/**
 * The in-app feedback composer (platform #1315 / #1316 / #1317).
 *
 * ## Reachability
 *
 * Live since 2026-08-19. `POST /feedback` has been on production since the
 * platform's 2026-08-18 deploy, accepts a bearer token, and `feedback:write` is
 * seeded to the mobile OAuth client — including on consents that already existed,
 * so no re-login was required. The two entry rows are gated by
 * [at.bettertrack.app.data.repo.feedbackEntryVisible]: the capability flag AND this
 * install having a BetterTrack account, because a Drive-autonomous install has no
 * account and therefore no bearer token to send.
 *
 * Only v1 exists — one POST, no history. `GET /feedback/mine`, the per-submission
 * thread, `PATCH /feedback/{id}` and the status/notification model are platform
 * #1338–#1342, queued behind the admin inbox #1316. Nothing on this screen may
 * promise them: the sent card says the message arrived, not that a reply will.
 *
 * ## Three decisions worth stating
 *
 * **The character counter counts what will be SENT, and the field hard-caps at
 * [FEEDBACK_MESSAGE_MAX].** The contract says over-length returns a validation
 * envelope; the honest response to a rule the app already knows is to enforce it at
 * the keyboard rather than let somebody write 6,000 characters and lose them to a
 * 400. The server's refusal is still surfaced verbatim if one arrives anyway —
 * the cap is the app being helpful, not the app claiming to be the authority.
 *
 * **The attached context is shown, itemised, before sending.** A form that quietly
 * ships device details is a form people stop trusting. Every value here is visible
 * on the screen that sends it, and the list is exhaustive: there is no account id,
 * no token, no portfolio figure and nothing the user did not type.
 *
 * **There is no offline queue.** Feedback is not a portfolio mutation — it has no
 * ordering constraints, no ledger to keep consistent, and no value in arriving
 * three days late from a queue the user has forgotten about. Pressing Send while
 * offline says so and keeps the text; that is the whole behaviour, and it is
 * truthful in a way "queued for later" would not be.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    origin: String = FeedbackOrigin.SETTINGS,
) {
    val bt = BtTheme.colors
    val repo = AppGraph.feedbackRepository
    val scope = rememberCoroutineScope()
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    val locale = rememberBtLocale()
    // Signed-out is not a state this screen can normally be opened in — it lives
    // behind Settings, which lives behind the session. It is handled anyway because
    // a token can be revoked mid-session while the composer is on screen, and the
    // failure mode without this is a 401 arriving after the user has typed 2,000
    // characters. The text is kept; only Send is withheld.
    val authState by AppGraph.authRepository.authState.collectAsStateWithLifecycle()
    val signedIn = authState is AuthState.LoggedIn || authState is AuthState.PasswordChangeRequired

    // `rememberSaveable` on the draft, not `remember`: a rotation mid-sentence
    // must not throw away 900 characters somebody just typed.
    var category by rememberSaveable { mutableStateOf<FeedbackCategory?>(null) }
    var subject by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var failure by remember { mutableStateOf<BtMessage?>(null) }

    val context = remember(locale, origin) {
        feedbackContextOf(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            locale = locale.toLanguageTag(),
            screen = origin,
        )
    }

    val draft = FeedbackDraft(category = category, subject = subject, message = message)
    val sendable = draft.isSendable()

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_feedback),
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
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sent) {
                // The success state REPLACES the form rather than sitting above a
                // still-populated one. A form that stays filled after a successful
                // send invites a second, identical submission — and the rate limit
                // makes that second tap a 429 the user cannot explain.
                SentCard(
                    onAgain = {
                        sent = false
                        category = null
                        subject = ""
                        message = ""
                        failure = null
                    },
                    onDone = onBack,
                )
                return@Column
            }

            Text(
                stringResource(R.string.bt_feedback_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            // ── CATEGORY ─────────────────────────────────────────────────────
            // Rows rather than a segmented control: the German "Feature/
            // Verbesserung" does not fit a third of a pill track, and each option
            // earns a line of copy saying which one to pick. The wire values stay
            // feature|bug|other regardless of what is drawn here.
            BtSectionHeader(stringResource(R.string.bt_feedback_category_header))
            BtGroup {
                CategoryRow(
                    category = FeedbackCategory.Feature,
                    selected = category,
                    titleRes = R.string.bt_feedback_cat_feature,
                    subtitleRes = R.string.bt_feedback_cat_feature_sub,
                    icon = Icons.Outlined.Lightbulb,
                    enabled = !sending,
                ) { category = it }
                CategoryRow(
                    category = FeedbackCategory.Bug,
                    selected = category,
                    titleRes = R.string.bt_feedback_cat_bug,
                    subtitleRes = R.string.bt_feedback_cat_bug_sub,
                    icon = Icons.Outlined.BugReport,
                    enabled = !sending,
                ) { category = it }
                CategoryRow(
                    category = FeedbackCategory.Other,
                    selected = category,
                    titleRes = R.string.bt_feedback_cat_other,
                    subtitleRes = R.string.bt_feedback_cat_other_sub,
                    icon = Icons.Outlined.ChatBubbleOutline,
                    enabled = !sending,
                ) { category = it }
            }

            // ── MESSAGE ──────────────────────────────────────────────────────
            BtSectionHeader(stringResource(R.string.bt_feedback_message_header))
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it.take(FEEDBACK_SUBJECT_MAX) },
                label = { Text(stringResource(R.string.bt_feedback_subject_label)) },
                singleLine = true,
                enabled = !sending,
                colors = btFieldColors(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(FEEDBACK_MESSAGE_MAX) },
                label = { Text(stringResource(R.string.bt_feedback_message_label)) },
                enabled = !sending,
                colors = btFieldColors(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
            )
            // The live counter. Right-aligned under the field it counts, and it
            // turns gold at the ceiling so "nothing else is going in" is visible
            // rather than something you deduce from keys doing nothing.
            val full = message.length >= FEEDBACK_MESSAGE_MAX
            // Resolved out here: `Modifier.semantics` runs outside composition and
            // cannot call `stringResource`. A screen reader hearing "1234 slash
            // 5000" learns nothing; the spoken form says what the number is.
            val counterCd = stringResource(
                R.string.bt_feedback_counter_cd, message.length, FEEDBACK_MESSAGE_MAX,
            )
            Text(
                stringResource(R.string.bt_feedback_counter, message.length, FEEDBACK_MESSAGE_MAX),
                style = MaterialTheme.typography.bodySmall,
                color = if (full) bt.goldEmphasis else bt.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = counterCd },
            )

            // ── WHAT GOES WITH IT ────────────────────────────────────────────
            ContextCard(context)

            // ── SEND ─────────────────────────────────────────────────────────
            if (!signedIn) {
                Text(
                    stringResource(R.string.bt_feedback_signed_out),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            } else if (!online) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = bt.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.bt_feedback_offline),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
            failure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }
            BtPrimaryButton(
                text = stringResource(R.string.bt_feedback_send),
                onClick = {
                    failure = null
                    sending = true
                    scope.launch {
                        when (val r = repo.submit(draft, context)) {
                            is BtResult.Ok -> sent = true
                            is BtResult.Err -> failure = feedbackFailureMessage(r.error)
                        }
                        sending = false
                    }
                },
                // Offline is a hard stop, not a warning: there is no queue behind
                // this button, so letting it fire would produce a network error
                // the offline line already predicted.
                enabled = sendable && online && signedIn,
                loading = sending,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CategoryRow(
    category: FeedbackCategory,
    selected: FeedbackCategory?,
    titleRes: Int,
    subtitleRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onPick: (FeedbackCategory) -> Unit,
) {
    val bt = BtTheme.colors
    val isSelected = selected == category
    BtGroupRow(
        icon = icon,
        iconTint = if (isSelected) bt.goldEmphasis else null,
        title = stringResource(titleRes),
        subtitle = stringResource(subtitleRes),
        onClick = if (enabled) ({ onPick(category) }) else null,
        trailing = {
            if (isSelected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = bt.goldEmphasis,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

/**
 * The exhaustive list of what leaves the phone alongside the message. Rendered
 * from the very object that is about to be serialized, so it cannot drift out of
 * date the way a hand-written list would.
 */
@Composable
private fun ContextCard(context: FeedbackContextDto) {
    val bt = BtTheme.colors
    BtSectionHeader(stringResource(R.string.bt_feedback_context_header))
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ContextLine(R.string.bt_feedback_context_platform, context.platform)
            ContextLine(R.string.bt_feedback_context_app, context.appVersion)
            ContextLine(R.string.bt_feedback_context_os, context.osVersion)
            ContextLine(R.string.bt_feedback_context_device, context.device)
            ContextLine(R.string.bt_feedback_context_locale, context.locale)
            ContextLine(R.string.bt_feedback_context_screen, context.screen)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.bt_feedback_context_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

/** One label/value pair — skipped entirely when the value is absent, never blank. */
@Composable
private fun ContextLine(labelRes: Int, value: String?) {
    if (value.isNullOrBlank()) return
    val bt = BtTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SentCard(onAgain: () -> Unit, onDone: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = bt.gain,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.bt_feedback_sent_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                )
            }
            Text(
                stringResource(R.string.bt_feedback_sent_body),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_feedback_sent_again),
                    onClick = onAgain,
                    modifier = Modifier.weight(1f),
                )
                BtPrimaryButton(
                    text = stringResource(R.string.bt_action_done),
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
