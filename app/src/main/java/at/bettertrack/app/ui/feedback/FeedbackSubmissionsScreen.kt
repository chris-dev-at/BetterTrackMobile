package at.bettertrack.app.ui.feedback

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.FeedbackStatus
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.repo.FeedbackCategory
import at.bettertrack.app.data.repo.FeedbackSubmission
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtCountBadge
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtStateFill
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// ── Display copy: the one thing about this feature that is OURS ──────────────

/**
 * The German and English name for a lifecycle status.
 *
 * ## Why this function exists at all
 *
 * The wire values (`new`, `triaged`, `working_on_it`, `saved_as_future_idea`,
 * `declined`, `shipped`) are the platform's and are never translated. The words a
 * user reads are ours, and choosing them well IS the feature — the owner's brief
 * was *"they can see the state… like give them status rejected or like saved as
 * future idea. or working on rn… make up better names but like that."*
 *
 * The register the copy holds to: **calm, factual, and never a verdict on the
 * person**. Two consequences that are easy to get wrong:
 *
 *  - `declined` reads "Nicht umgesetzt" / "Not planned", not "Abgelehnt" /
 *    "Rejected". The submission was not judged; a decision was made about the
 *    product. The reason the maintainer wrote is shown right underneath, which is
 *    the part that actually answers "why", so the label does not have to carry any
 *    weight beyond the fact.
 *  - `saved_as_future_idea` reads "Für später vorgemerkt" / "Saved for later" —
 *    a promise about the idea's place, not about a date. Anything with a "bald" /
 *    "soon" in it would be the app committing on the maintainer's behalf.
 *
 * ## Why a `when` with no `else`
 *
 * A new constant in [FeedbackStatus] must not compile until somebody has written
 * both languages for it. An `else` branch would turn that compile error into a
 * silent fallback — the exact failure mode where a new server status ships and
 * every affected user reads "Eingegangen" about a submission that has moved on.
 * `FeedbackStatusCopyTest` pins the exhaustiveness from the other side.
 *
 * UNKNOWN wire values are a different case and are deliberately NOT handled here:
 * they never become a [FeedbackStatus] at all
 * ([FeedbackStatus.Companion.fromWire] returns `null`), and the chip prints the
 * raw wire string instead — see [StatusChip].
 */
@StringRes
internal fun feedbackStatusLabelRes(status: FeedbackStatus): Int = when (status) {
    FeedbackStatus.New -> R.string.bt_feedback_mine_status_new
    FeedbackStatus.Triaged -> R.string.bt_feedback_mine_status_triaged
    FeedbackStatus.WorkingOnIt -> R.string.bt_feedback_mine_status_working
    FeedbackStatus.SavedAsFutureIdea -> R.string.bt_feedback_mine_status_future
    FeedbackStatus.Declined -> R.string.bt_feedback_mine_status_declined
    FeedbackStatus.Shipped -> R.string.bt_feedback_mine_status_shipped
}

/**
 * How loudly a status chip is allowed to speak.
 *
 * Three tones over six statuses, and the grouping is the message:
 *
 *  - **Gold — alive.** `working_on_it` and `saved_as_future_idea`. The app's one
 *    accent, spent on the two states where the submission is still going somewhere.
 *  - **Gain — done, and it worked.** `shipped`, the only status that is good news.
 *  - **Neutral — everything else**, including `declined`.
 *
 * `declined` is neutral on purpose and the rule is worth stating: **no red**. The
 * loss colour in this app means money went the wrong way; borrowing it for "we are
 * not building this" would read as an alarm about the user's own message. A muted
 * pill plus the maintainer's written reason is the whole treatment.
 *
 * `null` — a status this build does not know — is neutral too. An unknown state is
 * not an emergency, and colouring it would be claiming to know what it means.
 */
internal fun feedbackStatusTone(status: FeedbackStatus?): BtBadgeKind = when (status) {
    null, FeedbackStatus.New, FeedbackStatus.Triaged, FeedbackStatus.Declined ->
        BtBadgeKind.Neutral
    FeedbackStatus.WorkingOnIt, FeedbackStatus.SavedAsFutureIdea -> BtBadgeKind.Gold
    FeedbackStatus.Shipped -> BtBadgeKind.Gain
}

/**
 * The category label, reusing the composer's own five strings.
 *
 * Not new copy: a user who picked "Verbesserung" three screens ago has to read the
 * same word back, or the list is describing something else. `when` with no `else`
 * for the same reason as [feedbackStatusLabelRes] — and platform #1400 is what that
 * rule was for: two wire values arrived on 2026-08-20 and this function refused to
 * compile until both had German and English of their own.
 *
 * `help` and `improvement` are KNOWN values now, so a row carrying either renders a
 * translated label rather than the raw wire word. The unknown-value path below
 * (`item.categoryWire`) is unchanged and still catches whatever the platform adds
 * next.
 */
@StringRes
internal fun feedbackCategoryLabelRes(category: FeedbackCategory): Int = when (category) {
    FeedbackCategory.Feature -> R.string.bt_feedback_cat_feature
    FeedbackCategory.Bug -> R.string.bt_feedback_cat_bug
    FeedbackCategory.Other -> R.string.bt_feedback_cat_other
    FeedbackCategory.Help -> R.string.bt_feedback_cat_help
    FeedbackCategory.Improvement -> R.string.bt_feedback_cat_improvement
}

// ── Stamps ───────────────────────────────────────────────────────────────────

/** Which of the three renderings a stamp gets. */
internal enum class FeedbackDay { Today, Yesterday, Date }

/**
 * Bucket a stamp against "now", by **calendar day in [zone]** rather than by
 * elapsed hours.
 *
 * The difference matters at exactly the moment a user looks: a status that changed
 * at 23:50 must say "Gestern" at 00:10, not "vor 20 Minuten". Elapsed-time
 * arithmetic gets that backwards, which is why this counts days between
 * `LocalDate`s.
 *
 * Kept a pure function — no Compose, no `Locale`, no system clock — so both
 * boundaries are unit-tested at fixed instants instead of being hoped about.
 */
internal fun feedbackDayBucket(ms: Long, nowMs: Long, zone: ZoneId): FeedbackDay {
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(day, today)) {
        0L -> FeedbackDay.Today
        1L -> FeedbackDay.Yesterday
        // A future stamp (clock skew between phone and server) falls here and
        // prints its date. "In 4 hours" would be the app reporting the skew as if
        // it were a fact about the submission.
        else -> FeedbackDay.Date
    }
}

/**
 * A stamp as a localized MEDIUM date — "18. Aug. 2026" / "Aug 18, 2026".
 *
 * Date only, no clock. These rows carry two stamps between them and the minute a
 * maintainer moved a status has never been the question anyone came here with.
 * The [locale] is passed in from `rememberBtLocale()` rather than read from
 * `Locale.getDefault()`, so the in-app language switch reformats it (the app owns
 * its locale; see `LocaleManager`).
 */
internal fun feedbackDateText(ms: Long, zone: ZoneId, locale: Locale): String =
    Instant.ofEpochMilli(ms)
        .atZone(zone)
        .toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** The stamp as the row and sheet show it: "Heute", "Gestern", or a date. */
@Composable
private fun stampText(ms: Long): String {
    val locale = rememberBtLocale()
    val zone = remember { ZoneId.systemDefault() }
    // `nowMs` is read once per composition of this row rather than held in state:
    // nothing here ticks, and a submissions list that re-rendered every second to
    // move a date label across midnight would be spending battery on nothing.
    val bucket = feedbackDayBucket(ms, System.currentTimeMillis(), zone)
    return when (bucket) {
        FeedbackDay.Today -> stringResource(R.string.bt_feedback_mine_today)
        FeedbackDay.Yesterday -> stringResource(R.string.bt_feedback_mine_yesterday)
        FeedbackDay.Date -> feedbackDateText(ms, zone, locale)
    }
}

// ── The screen ───────────────────────────────────────────────────────────────

/**
 * "Meine Einreichungen" — every feedback submission this account has sent, with
 * what happened to it (`GET /feedback/mine`, platform #1338, live 2026-08-20).
 *
 * ## The scope story, kept because it can recur
 *
 * The feedback module split its scope in two when the read half landed. For a few
 * hours this screen's expected state was a clean `403 INSUFFICIENT_SCOPE` — until
 * the #1393 grant-widening was proven live on production (a pre-existing bearer
 * answered 200) and
 * [at.bettertrack.app.data.auth.OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED] flipped
 * on the same day. The 403 branch stays fully built regardless: a session whose
 * token predates the widening, or any future scope split, renders the catalogued
 * `bt_err_insufficient_scope` copy — whose remedy ("sign out and back in") is
 * literally sufficient. Nothing about this screen changes across that boundary.
 *
 * ## Reachability
 *
 * One door: the composer ([FeedbackScreen]), via a footer row and via the
 * post-send card. That is deliberate — it inherits the composer's own gate
 * ([at.bettertrack.app.data.repo.feedbackEntryVisible]: the capability flag AND
 * this install having a BetterTrack account) by construction, so there is no
 * second visibility rule to keep in sync and no way for a Drive-autonomous install
 * (no account, no bearer token, nothing for the endpoint to attribute) to reach a
 * list that could only ever be empty or 401.
 *
 * ## `unreadReplyCount` is RESERVED
 *
 * The per-submission reply thread is not live; the server always sends 0. The
 * badge is written and gated on `> 0`, so it is never drawn today and lights up on
 * the day threads ship without an app change. `FeedbackSubmissionsDisciplineTest`
 * pins the gate, because "never visible" is exactly the kind of property a
 * refactor removes without anyone noticing.
 *
 * ## One GET, no polling
 *
 * The list loads once per entry and once more per Retry tap. There is no refresh
 * loop and no swipe-to-refresh: a status moves when a human moves it, which is on
 * the order of days, and re-asking a rate-limited feedback endpoint on a timer
 * would cost the user their submissions quota for nothing.
 *
 * ## Deleting: the list is the truth (platform #1400, live 2026-08-20)
 *
 * `DELETE /feedback/{id}` is a SOFT delete — the row leaves this list, the
 * maintainer keeps a tombstone — and the deployed contract declares `204` with **no
 * 404**, i.e. it is idempotent. A success therefore proves nothing on its own: an
 * id that was already gone answers 204 too. So the confirm path calls the route,
 * **re-reads `/feedback/mine`, and reports whatever the fresh list says** — the same
 * discipline the trusted-devices screen had to adopt for the same reason. Saying
 * "deleted" on a status code alone would be a claim this app has no way to check.
 *
 * Deleting is never offered optimistically: the action is disabled while offline
 * (there is no queue behind it, exactly as there is none behind the composer's Send)
 * and the sheet says so rather than letting a tap produce a network error the screen
 * could have predicted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackSubmissionsScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.feedbackRepository
    val scope = rememberCoroutineScope()
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
    val authState by AppGraph.authRepository.authState.collectAsStateWithLifecycle()
    val signedIn = authState is AuthState.LoggedIn || authState is AuthState.PasswordChangeRequired

    var items by remember { mutableStateOf<List<FeedbackSubmission>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<BtApiError?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<FeedbackSubmission?>(null) }
    var confirmDelete by remember { mutableStateOf<FeedbackSubmission?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Int?>(null) }
    var actionError by remember { mutableStateOf<BtMessage?>(null) }

    /** Fetch and RETURN the fresh list, so a delete can judge its own outcome. */
    suspend fun fetch(): List<FeedbackSubmission>? =
        when (val r = repo.mine()) {
            is BtResult.Ok -> {
                items = r.value
                failure = null
                r.value
            }
            // The previous list is deliberately NOT cleared on a failed reload: a
            // dropped Retry should leave what the user was reading on screen.
            is BtResult.Err -> {
                failure = r.error
                null
            }
        }

    LaunchedEffect(reloadKey, signedIn) {
        if (!signedIn) {
            // Not an error and not a load: a signed-out session has nothing to ask
            // for. Leaving `loading` true would spin a skeleton forever.
            loading = false
            return@LaunchedEffect
        }
        loading = true
        failure = null
        fetch()
        loading = false
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_feedback_mine_title),
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
        val content = Modifier.fillMaxSize()
        // Pulled out of the `when` so the branch smart-casts instead of needing a
        // `!!` — an assertion that would be correct today and wrong the first time
        // somebody reorders these arms.
        val error = failure

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
        // ── What the last delete did ─────────────────────────────────────────
        // INLINE, not a snackbar, and this is a measured decision rather than a
        // style one: this screen is a full-screen SHEET destination, and the sheet
        // layer is composed OVER everything the shell's Scaffold drew — including
        // its `snackbarHost`. A snackbar raised from here is therefore painted
        // behind the sheet and never seen. Verified on the owner's device on
        // 2026-08-20: a delete that answered `500` showed the user nothing at all.
        // A banner that lives inside this screen cannot be occluded by it.
        //
        // Above the state `when`, not inside its list arm, because two of the three
        // outcomes have to survive the list going empty (a successful delete of the
        // last row switches the screen to its empty state) or staying empty (a
        // failure while nothing is listed).
        actionError?.let { message ->
            BtInlineError(
                message = message,
                onRetry = {
                    actionError = null
                    reloadKey++
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (actionError == null) {
            outcome?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                !signedIn -> BtStateFill(content) {
                    BtEmptyState(
                        icon = Icons.Outlined.Inbox,
                        title = stringResource(R.string.bt_feedback_mine_signed_out),
                    )
                }

                loading -> BtScrollFill(content) { SubmissionsSkeleton() }

                // Offline only claims the screen when there is nothing to show. A
                // failed reload over a list that is already on screen keeps the list.
                !online && items.isEmpty() -> BtStateFill(content) {
                    BtOfflineState(
                        message = stringResource(R.string.bt_feedback_mine_offline),
                        onRetry = { reloadKey++ },
                    )
                }

                error != null && items.isEmpty() -> BtStateFill(content) {
                    BtErrorState(
                        // The scope refusal is the one failure with a named cause and a
                        // remedy the user can actually perform, so it gets its own
                        // title. The MESSAGE is the app-wide catalogued sentence for
                        // INSUFFICIENT_SCOPE, resolved by `asMessage()` — not copy
                        // invented here, which is what keeps one remedy in one place.
                        title = if (error.isInsufficientScope) {
                            stringResource(R.string.bt_feedback_mine_scope_title)
                        } else {
                            stringResource(R.string.bt_error_generic_title)
                        },
                        message = error.asMessage(),
                        onRetry = { reloadKey++ },
                    )
                }

                items.isEmpty() -> BtStateFill(content) {
                    BtEmptyState(
                        icon = Icons.Outlined.Inbox,
                        title = stringResource(R.string.bt_feedback_mine_empty_title),
                        message = stringResource(R.string.bt_feedback_mine_empty_body),
                        action = {
                            // The only door in is the composer, so Back IS "write
                            // feedback". Labelling it for what it does beats a bare
                            // "Back" the empty state would not need to draw at all.
                            BtSecondaryButton(
                                text = stringResource(R.string.bt_feedback_mine_empty_action),
                                onClick = onBack,
                            )
                        },
                    )
                }

                else -> LazyColumn(
                    modifier = content,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        SubmissionRow(item = item, onOpen = { opened = item })
                    }
                }
            }
        } // Box
    } // Column
    }

    opened?.let { item ->
        SubmissionSheet(
            item = item,
            // Offline is a hard stop, not a warning, for the same reason the
            // composer's Send is: there is no queue behind this call.
            deleteEnabled = online && !deleting,
            online = online,
            onDelete = {
                // Dismiss-then-open, the order every converted call site uses, so
                // the confirmation never stacks on top of the detail sheet.
                opened = null
                confirmDelete = item
            },
            onDismiss = { opened = null },
        )
    }

    confirmDelete?.let { target ->
        FeedbackDeleteConfirmSheet(
            detail = target.subject ?: target.message,
            enabled = online && !deleting,
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                deleting = true
                scope.launch {
                    when (val r = repo.delete(target.id)) {
                        is BtResult.Ok -> {
                            // The 204 says nothing on its own — the route is
                            // idempotent and answers the same for an id that was
                            // already gone. The re-read decides what the user is
                            // told, and a re-read that itself failed says nothing
                            // at all rather than guessing.
                            val after = fetch()
                            outcome = when {
                                after == null -> null
                                after.none { it.id == target.id } ->
                                    R.string.bt_feedback_mine_deleted
                                else -> R.string.bt_feedback_mine_still_listed
                            }
                        }
                        is BtResult.Err -> actionError = r.error.asMessage()
                    }
                    deleting = false
                }
            },
        )
    }
}

/**
 * One submission, as a tappable card.
 *
 * Card and not [at.bettertrack.app.ui.components.BtGroupRow]: these rows are peers
 * competing for a tap, which is the tier the design system reserves cards for
 * (holdings, alerts, watchlist entries) — a group would say "parts of one subject".
 *
 * Anatomy — two columns, each internally stacked:
 *
 * | left (weighted) | right (intrinsic) |
 * |---|---|
 * | subject, or the message when there is none | status chip |
 * | category · when the status last moved | version chip, then the reserved badge |
 *
 * Two things about that arrangement are deliberate. The status chip is **stacked,
 * not inline with the title**, because the longest German label —
 * "Für später vorgemerkt" — is wider than any inline slot could give it without
 * either wrapping the pill or crushing the title; a right column takes its
 * intrinsic width once and the title flows around it. And the version chip sits
 * directly under the status chip it belongs to, because "Umgesetzt" and "0.131"
 * are one fact and reading them as two is the "they feel disconnected" defect.
 */
@Composable
private fun SubmissionRow(item: FeedbackSubmission, onOpen: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    // No hand-rolled substring: `maxLines` + ellipsis cuts at the
                    // measured width instead of at a character count that would
                    // clip mid-word on one phone and leave a gap on the next.
                    text = item.subject ?: item.message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = rowSubline(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChip(item)
                item.shippedVersion?.let { version ->
                    BtBadge(text = version, kind = BtBadgeKind.Neutral)
                }
                // RESERVED. `unreadReplyCount` is always 0 until the reply thread
                // ships, so this never draws today; the gate is what makes it
                // correct on the day it starts arriving non-zero.
                if (item.unreadReplyCount > 0) {
                    BtCountBadge(count = item.unreadReplyCount)
                }
            }
        }
    }
}

/** "Bug · Gestern" — the category, then when the status last moved. */
@Composable
private fun rowSubline(item: FeedbackSubmission): String {
    val category = categoryText(item)
    val stamp = item.lastStatusChangeAtMs?.let { stampText(it) }
    return if (stamp == null) category else "$category · $stamp"
}

/** The category label, falling back to the raw wire value this build cannot name. */
@Composable
private fun categoryText(item: FeedbackSubmission): String =
    item.category?.let { stringResource(feedbackCategoryLabelRes(it)) }
        ?: item.categoryWire

/**
 * The status chip, plus the version chip when a submission shipped.
 *
 * Two pills rather than one sentence: "Umgesetzt" is the state and the version is
 * the evidence, and keeping them separate lets the version stay VERBATIM. The app
 * never prepends a "v" or reformats what the platform sent — how a release is
 * spelled is the platform's to decide, and a client that "helpfully" normalises it
 * will eventually print a version that does not exist.
 */
@Composable
private fun StatusChips(item: FeedbackSubmission) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusChip(item)
        item.shippedVersion?.let { version ->
            Spacer(Modifier.width(6.dp))
            BtBadge(text = version, kind = BtBadgeKind.Neutral)
        }
    }
}

/**
 * One status pill.
 *
 * An unknown wire value prints ITSELF, in the neutral tone. That is the whole
 * forward-compatibility contract for this screen: a status the platform adds next
 * month neither disappears (which would tell the user nothing happened) nor
 * crashes the list (which would tell them the app is broken) — it shows up as an
 * unfamiliar word they can quote in the next feedback message.
 */
@Composable
private fun StatusChip(item: FeedbackSubmission) {
    val label = item.status?.let { stringResource(feedbackStatusLabelRes(it)) }
        ?: item.statusWire
    BtBadge(text = label, kind = feedbackStatusTone(item.status))
}

/** Three placeholder cards. Same geometry as a real row, so nothing jumps. */
@Composable
private fun SubmissionsSkeleton() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) {
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BtSkeleton(
                            Modifier
                                .weight(1f)
                                .height(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        BtSkeleton(
                            Modifier
                                .width(76.dp)
                                .height(18.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    BtSkeleton(
                        Modifier
                            .width(140.dp)
                            .height(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * The whole submission: the message as written, both stamps, whatever the status
 * carries with it, and the one thing the user can DO about it — delete.
 *
 * A bottom sheet, not a pushed page — the app pops everything transient from the
 * bottom, and this is a detail view over a row that stays on screen behind it.
 * There is no close button: the drag handle and the scrim are the dismiss, exactly
 * as in every other detail sheet.
 *
 * Delete lives at the BOTTOM, under the message, and it is the only affordance in
 * here. That placement is the point: somebody opening a row is reading it, not
 * looking for a destructive verb, so the verb waits at the end of the reading rather
 * than sitting next to the title where a mis-tap lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubmissionSheet(
    item: FeedbackSubmission,
    deleteEnabled: Boolean,
    online: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // A sheet whose content reaches full height fights its own inner scroll — the
    // same wobble guard the chain and switcher sheets use.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = item.subject ?: stringResource(R.string.bt_feedback_mine_no_subject),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (item.subject == null) bt.textMuted else bt.textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BtBadge(text = categoryText(item), kind = BtBadgeKind.Neutral)
                Spacer(Modifier.width(6.dp))
                StatusChips(item)
                // RESERVED, same gate as the row: never drawn while the reply
                // thread does not exist.
                if (item.unreadReplyCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    BtCountBadge(count = item.unreadReplyCount)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.bt_feedback_mine_replies_cd,
                            item.unreadReplyCount,
                            item.unreadReplyCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textMuted,
                    )
                }
            }

            // ── The status history, such as it is ────────────────────────────
            // Two stamps is the entire history the contract exposes: when it was
            // sent, and when the status last moved. There is no per-transition log
            // on `GET /feedback/mine`, so this line says exactly what is known and
            // does not imply a timeline nobody can see.
            Spacer(Modifier.height(14.dp))
            item.createdAtMs?.let {
                HistoryLine(stringResource(R.string.bt_feedback_mine_sent_on, stampText(it)))
            }
            item.lastStatusChangeAtMs?.let {
                HistoryLine(stringResource(R.string.bt_feedback_mine_changed_on, stampText(it)))
            }

            // ── What the status carries ──────────────────────────────────────
            // Server invariants: a reason exists only on `declined`, a version only
            // on `shipped`. The repository already drops either one arriving on the
            // wrong status, so a `let` here is enough — there is no second rule to
            // re-check and get subtly different.
            item.declinedReason?.let { reason ->
                Spacer(Modifier.height(16.dp))
                SheetSection(
                    header = stringResource(R.string.bt_feedback_mine_declined_header),
                    body = reason,
                )
            }
            item.shippedVersion?.let { version ->
                Spacer(Modifier.height(16.dp))
                SheetSection(
                    header = stringResource(R.string.bt_feedback_mine_shipped_header),
                    body = version,
                )
            }

            Spacer(Modifier.height(16.dp))
            SheetSection(
                header = stringResource(R.string.bt_feedback_mine_message_header),
                body = item.message,
            )

            // ── Delete ───────────────────────────────────────────────────────
            Spacer(Modifier.height(20.dp))
            Surface(
                onClick = onDelete,
                enabled = deleteEnabled,
                color = bt.surface,
                border = BorderStroke(
                    1.dp,
                    if (deleteEnabled) bt.edge(bt.loss, 0.4f) else bt.border,
                ),
                shape = BtShapes.card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = if (deleteEnabled) bt.loss else bt.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.bt_feedback_mine_delete),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (deleteEnabled) bt.loss else bt.textMuted,
                    )
                }
            }
            // The disabled row stays VISIBLE and says why, rather than vanishing:
            // a control that disappears when the signal drops reads as a feature
            // that was taken away.
            if (!online) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.bt_feedback_mine_delete_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * "Are you sure?" for the one destructive action on this screen — a bottom sheet,
 * per the owner's 2026-08-16 order, with the same chrome and the same button
 * hierarchy the trusted-devices confirmation uses: the destructive verb is the
 * filled loss-coloured button and Cancel is the quiet one, so the dangerous choice
 * has to be aimed at.
 *
 * The body copy states the two facts a soft delete has to state — it leaves YOUR
 * list, and the maintainer keeps what was already answered — because a "Delete" that
 * silently means "hide" is the kind of promise this app does not make.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackDeleteConfirmSheet(
    detail: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = bt.textMuted) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                // No `ime` in the union: this sheet hosts no text field.
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.bt_feedback_mine_delete_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                // Which submission this is about, in the words the row showed —
                // two lines, because a subjectless submission falls back to its
                // whole message and an unbounded one would push the buttons off.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.bt_feedback_mine_delete_message),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onConfirm,
                enabled = enabled,
                shape = BtShapes.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = bt.loss,
                    contentColor = bt.bg,
                    disabledContainerColor = bt.border,
                    disabledContentColor = bt.textMuted,
                ),
                elevation = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.bt_feedback_mine_delete_confirm))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        }
    }
}

@Composable
private fun HistoryLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SheetSection(header: String, body: String) {
    val bt = BtTheme.colors
    Text(
        text = header,
        style = MaterialTheme.typography.labelMedium,
        color = bt.textMuted,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = bt.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}
