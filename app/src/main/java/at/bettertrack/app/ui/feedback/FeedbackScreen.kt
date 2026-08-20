package at.bettertrack.app.ui.feedback

import android.os.Build
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
 * That branch keys off the HTTP **status**, not an error code, and that is
 * deliberate. The live `openapi.json` documents only `201`, `400`, `401` and a
 * generic error envelope for this route, so the `code` the limiter emits is not
 * knowable from the contract — and an unmapped code falls through
 * [asMessage] to the generic sentence PLUS the server's ENGLISH diagnostic, which
 * on a German phone is exactly the failure P0-4 exists to prevent. Guessing a code
 * into `BtErrorCopy` would be inventing a wire fact; `429` is the status the
 * contract itself names, so that is what this reads.
 *
 * ## Which is why the open-submission cap has to be checked FIRST
 *
 * `FEEDBACK_OPEN_LIMIT` (platform #1400) is a code the catalogue DOES own copy for,
 * and the contract does not say which status carries it. Reading the status first
 * would let a `429 FEEDBACK_OPEN_LIMIT` be answered with the hourly sentence —
 * "try again a bit later" — which is false advice for a cap that clears only when a
 * submission is triaged or deleted. The code is the more specific fact, so the code
 * wins; the status branch stays underneath it, for the limiter whose code nobody
 * knows.
 *
 * Kept a pure top-level function so both branches are unit-tested without a Compose
 * runtime.
 */
internal fun feedbackFailureMessage(error: BtApiError): BtMessage = when {
    // The open-submission cap (platform #1400) beats the status branch, and the
    // order is the whole point. `FEEDBACK_OPEN_LIMIT` is a CODE the catalogue owns
    // real copy for — copy that names the actual remedy, which is to wait for
    // triage or delete an open request. Whatever HTTP status the platform chose to
    // carry it (the contract does not say, and 429 is a plausible one), letting the
    // status branch answer first would replace that remedy with "about five per
    // hour" — advice that is false here and that no amount of waiting satisfies.
    error.code == BtApiError.Codes.FEEDBACK_OPEN_LIMIT -> error.asMessage()

    error.httpStatus == 429 -> BtMessage(R.string.bt_feedback_err_rate_limited)

    else -> error.asMessage()
}

/**
 * The order the composer lists the five categories in.
 *
 * A product decision, deliberately NOT
 * [at.bettertrack.app.data.repo.FeedbackCategory]'s declaration order — that one
 * mirrors the wire enum (`feature, bug, other, help, improvement`), where `other`
 * sits in the middle because that is where the platform appended things. Reading
 * order goes the two "I want something" options first, the two "something is wrong
 * / I don't understand" options next, and the catch-all last, where a catch-all
 * belongs.
 *
 * Existing as a LIST rather than as five hand-written calls is what makes
 * `FeedbackComposerCategoryTest` able to assert that every category the enum names
 * is actually drawn: five hand-written rows would let the next widening ship a
 * category the composer silently cannot select.
 */
internal val FEEDBACK_CATEGORY_ORDER: List<FeedbackCategory> = listOf(
    FeedbackCategory.Feature,
    FeedbackCategory.Improvement,
    FeedbackCategory.Bug,
    FeedbackCategory.Help,
    FeedbackCategory.Other,
)

/**
 * The line of copy under each category name — the part that actually disambiguates
 * them, and the reason these are rows rather than chips.
 *
 * `when` with no `else`, same rule as `feedbackStatusLabelRes`: a sixth category
 * must fail to COMPILE until somebody has written both languages for it, rather
 * than silently inheriting a neighbour's sentence.
 */
@StringRes
internal fun feedbackCategorySubRes(category: FeedbackCategory): Int = when (category) {
    FeedbackCategory.Feature -> R.string.bt_feedback_cat_feature_sub
    FeedbackCategory.Improvement -> R.string.bt_feedback_cat_improvement_sub
    FeedbackCategory.Bug -> R.string.bt_feedback_cat_bug_sub
    FeedbackCategory.Help -> R.string.bt_feedback_cat_help_sub
    FeedbackCategory.Other -> R.string.bt_feedback_cat_other_sub
}

/** The leading glyph per category. Exhaustive for the same reason as the copy. */
internal fun feedbackCategoryIcon(category: FeedbackCategory): ImageVector = when (category) {
    FeedbackCategory.Feature -> Icons.Outlined.Lightbulb
    // A slider, not a sparkle: "improvement" is adjusting something that already
    // exists, and the sparkle glyph is spent on AI surfaces elsewhere in this app.
    FeedbackCategory.Improvement -> Icons.Outlined.Tune
    FeedbackCategory.Bug -> Icons.Outlined.BugReport
    FeedbackCategory.Help -> Icons.AutoMirrored.Outlined.HelpOutline
    FeedbackCategory.Other -> Icons.Outlined.ChatBubbleOutline
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
 * The status LIST exists since 2026-08-20 ([FeedbackSubmissionsScreen],
 * `GET /feedback/mine`) and is reachable from here twice: a footer row, and a link
 * on the sent card. The per-submission reply THREAD and `PATCH /feedback/{id}`
 * still do not exist, and nothing on this screen may promise them — the sent card
 * says the message arrived and where to watch it, never that a reply will come
 * back in the app.
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
    /**
     * Opens "Meine Einreichungen". Deliberately has NO default: a no-op default
     * would let a future call site render both entry points as dead taps, and a
     * row that looks like a link and does nothing is worse than no row at all.
     */
    onOpenSubmissions: () -> Unit,
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
                    onOpenSubmissions = onOpenSubmissions,
                )
                return@Column
            }

            Text(
                stringResource(R.string.bt_feedback_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )

            // ── CATEGORY ─────────────────────────────────────────────────────
            // Rows rather than a segmented control or a chip track, and the
            // widening to FIVE (platform #1400) is what settles that for good: five
            // chips cannot hold "Verbesserung" and "Sonstiges" on one line of a
            // narrow phone without either wrapping into a ragged second row or
            // shrinking the type, and the whole difficulty here is telling
            // "Feature" from "Verbesserung" from "Hilfe" — which is what the second
            // line of each row does. A row costs vertical space; the alternative
            // costs the user the distinction.
            //
            // Driven from FEEDBACK_CATEGORY_ORDER rather than hand-written, so a
            // sixth wire value cannot ship as a category nobody can select.
            BtSectionHeader(stringResource(R.string.bt_feedback_category_header))
            BtGroup {
                FEEDBACK_CATEGORY_ORDER.forEach { option ->
                    CategoryRow(
                        category = option,
                        selected = category,
                        titleRes = feedbackCategoryLabelRes(option),
                        subtitleRes = feedbackCategorySubRes(option),
                        icon = feedbackCategoryIcon(option),
                        enabled = !sending,
                    ) { category = it }
                }
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

            // ── MY SUBMISSIONS ───────────────────────────────────────────────
            // A footer row rather than a header action: the header's one slot is
            // worth more to a back arrow on a full-screen sheet, and this is not
            // something anyone opens the composer to do — it is what they reach
            // for after writing, or when they come back a week later.
            //
            // Rendered UNCONDITIONALLY inside this screen, which is the whole
            // point: the composer is itself behind `feedbackEntryVisible`, so this
            // row inherits that gate by construction. A second copy of the rule
            // here is exactly the drift `FeedbackEntryDisciplineTest` exists to
            // prevent. It is not gated on being signed in either — the list has
            // its own signed-out state, and hiding the door mid-session because a
            // token expired would look like the feature was removed.
            Spacer(Modifier.height(6.dp))
            BtGroup {
                BtGroupRow(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.bt_feedback_mine_title),
                    subtitle = stringResource(R.string.bt_feedback_mine_open_sub),
                    onClick = onOpenSubmissions,
                )
            }
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
    icon: ImageVector,
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
private fun SentCard(
    onAgain: () -> Unit,
    onDone: () -> Unit,
    onOpenSubmissions: () -> Unit,
) {
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
            // The natural next question after "sent" is "and then what?", so the
            // status list is offered right here rather than only on the form the
            // user has just left. A text button, not a third filled one: it is an
            // aside, and three buttons of equal weight would make none of them the
            // obvious one.
            TextButton(
                onClick = onOpenSubmissions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.bt_feedback_mine_sent_link),
                    style = MaterialTheme.typography.labelLarge,
                    color = bt.goldEmphasis,
                )
            }
        }
    }
}
