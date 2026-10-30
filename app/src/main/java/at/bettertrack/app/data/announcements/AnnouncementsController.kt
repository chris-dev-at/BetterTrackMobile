package at.bettertrack.app.data.announcements

import android.util.Log
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.MeResponse
import at.bettertrack.app.data.auth.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/** Why a refresh ran — logged, and the forcing rule keys off it. */
enum class RefreshReason(val force: Boolean) {
    LAUNCH(false),
    RESUME(false),
    SIGN_IN(true),
    /** `announcement.changed` or the handshake's `ACKNOWLEDGEMENT_REQUIRED`. */
    REALTIME(true),
    /** A `403 ACKNOWLEDGEMENT_REQUIRED` from any API call. */
    REFUSAL(true),
    /** After `ANNOUNCEMENT_NOT_PENDING` (contract §5.7.4: refetch `/auth/me`, move on). */
    ACK_FOLLOWUP(true),
    /** The UI locale changed under a list rendered in the other one. */
    LOCALE(true),
    INBOX(false),
}

/**
 * Everything the three surfaces render, in one immutable value. The surfaces
 * read it only through the derivations below ([banner], [onceCard], [gate]),
 * which are pure and unit-tested.
 */
data class AnnouncementsState(
    /** The signed-in account this state belongs to; null = signed out (render nothing). */
    val accountId: String? = null,
    /** The server list, in SERVER order. */
    val items: List<Announcement> = emptyList(),
    /** Debug-preview samples (Settings → Developer). Rendered first, never sent anywhere. */
    val previewItems: List<Announcement> = emptyList(),
    /** `MeResponse.pendingAcknowledgements` (or a refusal's details), as the tri-state. */
    val pending: PendingAcks = PendingAcks.Unknown,
    val previewPending: List<PendingAcknowledgement> = emptyList(),
    /** The full text the gate shows, per pending id (from the list or `GET /{id}`). */
    val gateContent: Map<String, Announcement> = emptyMap(),
    /** The locale the person picked in the gate's language switch, per id. */
    val gateLocale: Map<String, String> = emptyMap(),
    val gateLoading: Set<String> = emptySet(),
    val gateLoadFailed: Set<String> = emptySet(),
    /** `notice`/`once` closed in this session, plus the persisted local dismissals. */
    val hidden: Set<String> = emptySet(),
    /** Obligations settled in this session: acknowledged, or reported NOT_PENDING. */
    val resolved: Set<String> = emptySet(),
    val ackBusy: Boolean = false,
    /** A code to explain above the checkbox (CONTENT_CHANGED, LOCALE_UNAVAILABLE). */
    val gateNotice: String? = null,
    /** A failed acknowledge, for the inline message. */
    val gateError: BtApiError? = null,
    /** The locale the current [items] were fetched in. */
    val fetchedLocale: String? = null,
    /** The clock the window rules read — advanced by the controller at each boundary. */
    val nowMs: Long = 0L,
)

/** What the blocking modal draws for the head of the queue. */
data class GateView(
    val pending: PendingAcknowledgement,
    /** Null while loading or after a failed load — Confirm is disabled without it. */
    val content: Announcement?,
    val position: Int,
    val total: Int,
    val loading: Boolean,
    val loadFailed: Boolean,
    val busy: Boolean,
    val notice: String?,
    val error: BtApiError?,
    val preview: Boolean,
) {
    val id: String get() = pending.id

    /** The server's hash and locale are what make an acknowledgement name the text on screen. */
    val canAcknowledge: Boolean
        get() = content != null && content.contentSha256 != null && !busy && !loading
}

private fun AnnouncementsState.allItems(): List<Announcement> = previewItems + items

private fun AnnouncementsState.surfaceVisible(a: Announcement): Boolean =
    a.status == AnnouncementStatus.ACTIVE &&
        a.inWindow(nowMs) &&
        a.receipt?.dismissedAtMs == null &&
        a.id !in hidden

/** The shell banner: the first `notice` (unknown kinds included) in the window. */
fun AnnouncementsState.banner(): Announcement? =
    if (accountId == null) null else allItems().firstOrNull { it.kind == AnnouncementKind.NOTICE && surfaceVisible(it) }

/** The Overview card: the first `once` in the window that has not been closed. */
fun AnnouncementsState.onceCard(): Announcement? =
    if (accountId == null) null else allItems().firstOrNull { it.kind == AnnouncementKind.ONCE && surfaceVisible(it) }

/**
 * The obligations to present, oldest first (the given order), one at a time.
 * [PendingAcks.Unknown] contributes NOTHING — an older server gets no modal.
 */
fun AnnouncementsState.gateQueue(): List<PendingAcknowledgement> {
    if (accountId == null) return emptyList()
    val known = (pending as? PendingAcks.Known)?.items.orEmpty()
    return (previewPending + known)
        .filter { it.id !in resolved && it.isOpen(nowMs) }
        .distinctBy { it.id }
}

fun AnnouncementsState.gate(): GateView? {
    val queue = gateQueue()
    val head = queue.firstOrNull() ?: return null
    return GateView(
        pending = head,
        content = gateContent[head.id],
        position = 1,
        total = queue.size,
        loading = head.id in gateLoading,
        loadFailed = head.id in gateLoadFailed,
        busy = ackBusy,
        notice = gateNotice,
        error = gateError,
        preview = previewPending.any { it.id == head.id },
    )
}

/** True while a REAL (non-preview) obligation blocks the app — drives the realtime pause. */
fun AnnouncementsState.realGateActive(): Boolean = gate()?.let { !it.preview } == true

/** The next instant at which a window opens or closes, strictly after [AnnouncementsState.nowMs]. */
fun AnnouncementsState.nextBoundaryMs(): Long? {
    val instants = buildList {
        allItems().forEach { a -> a.startsAtMs?.let(::add); a.endsAtMs?.let(::add) }
        (pending as? PendingAcks.Known)?.items?.forEach { p -> p.startsAtMs?.let(::add); p.endsAtMs?.let(::add) }
        previewPending.forEach { p -> p.startsAtMs?.let(::add); p.endsAtMs?.let(::add) }
    }
    return instants.filter { it > nowMs }.minOrNull()
}

/**
 * The announcements pipeline: one process-scoped owner of what the three
 * surfaces show and of every call that changes it.
 *
 * ## Trigger points (contract §5.7.1–2)
 *  - **launch / sign-in** — [start] observes the auth state; a new account resets
 *    and refreshes;
 *  - **resume** — [onForeground] from the process lifecycle;
 *  - **realtime** — [onRealtimeChanged] (`announcement.changed`) and
 *    [onHandshakeAckRequired] (the connect error);
 *  - **any API call answering `403 ACKNOWLEDGEMENT_REQUIRED`** — [onGateRefusal],
 *    fed by the OkHttp interceptor with the refusal's `details`;
 *  - **every `MeResponse`** — [onMeResponse], the AuthRepository seam.
 *
 * A refresh reads `/auth/me` (the pending tri-state) and the list. Nothing here
 * ever throws into a caller: a failed read keeps what was there.
 */
class AnnouncementsController(
    private val enabled: Boolean,
    private val repo: AnnouncementRepository,
    private val authState: StateFlow<AuthState>,
    /** `/auth/me` through AuthRepository, so the session user stays in step. */
    private val fetchMe: suspend () -> BtResult<MeResponse>,
    private val localDismiss: LocalDismissStore,
    /** The app's content locale, `en` or `de`. */
    private val locale: () -> String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Real gate up/down — the realtime socket pauses while an obligation blocks. */
    private val onGateActiveChange: (Boolean) -> Unit = {},
    /** Launch fires from two places (auth state + first onStart); collapse them. */
    private val minRefreshIntervalMs: Long = 3_000L,
) {
    private val _state = MutableStateFlow(AnnouncementsState(nowMs = now()))
    val state: StateFlow<AnnouncementsState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    @Volatile private var rerun = false
    @Volatile private var inRefresh = false
    @Volatile private var lastRefreshAtMs = Long.MIN_VALUE / 2
    private val seenReported = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val retryDismiss = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private inline fun mutate(crossinline change: AnnouncementsState.() -> AnnouncementsState) {
        _state.update { it.change().copy(nowMs = now()) }
    }

    fun start() {
        if (!enabled || !started.compareAndSet(false, true)) return
        scope.launch {
            authState.collect { s ->
                try {
                    when (s) {
                        is AuthState.LoggedIn -> onSignedIn(s.user.id)
                        AuthState.LoggedOut, is AuthState.PasswordChangeRequired -> reset(null)
                        AuthState.Unknown -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "auth transition handling failed: ${e.javaClass.simpleName}")
                }
            }
        }
        // The window clock: wake at the next start/end so a banner hides AT `endsAt`
        // and an obligation lapses exactly then, with nobody touching anything.
        scope.launch {
            _state.map { it.nextBoundaryMs() }.distinctUntilChanged().collectLatest { boundary ->
                if (boundary != null) {
                    delay((boundary - now()).coerceAtLeast(0L) + CLOCK_SLACK_MS)
                    _state.update { it.copy(nowMs = now()) }
                }
            }
        }
        scope.launch {
            _state.map { it.realGateActive() }.distinctUntilChanged().drop(1).collect { active ->
                try {
                    onGateActiveChange(active)
                } catch (e: Exception) {
                    Log.w(TAG, "gate-active hook failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun onSignedIn(accountId: String) {
        val current = _state.value.accountId
        if (current == accountId) return
        if (current == "" && accountId.isNotEmpty()) {
            // Late identity resolution of the same session: keep what we have.
            mutate { copy(accountId = accountId, hidden = hidden + localDismiss.load(accountId)) }
            return
        }
        reset(accountId)
        refresh(RefreshReason.SIGN_IN)
    }

    private fun reset(accountId: String?) {
        seenReported.clear()
        retryDismiss.clear()
        lastRefreshAtMs = Long.MIN_VALUE / 2
        _state.value = AnnouncementsState(
            accountId = accountId,
            hidden = accountId?.let(localDismiss::load).orEmpty(),
            nowMs = now(),
        )
    }

    // ── Triggers ──────────────────────────────────────────────────────────────

    fun onForeground() = refresh(if (lastRefreshAtMs < 0) RefreshReason.LAUNCH else RefreshReason.RESUME)

    fun onRealtimeChanged(@Suppress("UNUSED_PARAMETER") announcementId: String?) = refresh(RefreshReason.REALTIME)

    fun onHandshakeAckRequired() = refresh(RefreshReason.REALTIME)

    fun onInboxOpened() = refresh(RefreshReason.INBOX)

    /** The UI's locale, observed by the root host: a switch re-reads the list in it. */
    fun onUiLocale(languageTag: String?) {
        val wanted = announcementLocaleOf(languageTag)
        val fetched = _state.value.fetchedLocale ?: return
        if (fetched != wanted) refresh(RefreshReason.LOCALE)
    }

    /** Every `MeResponse` the app receives (sign-in, `/auth/me`, account writes). */
    fun onMeResponse(me: MeResponse) {
        if (!enabled) return
        ingestPending(pendingAcksOf(me.pendingAcknowledgements))
    }

    /**
     * A `403 ACKNOWLEDGEMENT_REQUIRED` answered some request. Its `details` carry
     * the current obligations in the `/auth/me` shape (never empty): show them.
     * Called on an OkHttp thread — state updates are atomic and the content load
     * is launched.
     */
    fun onGateRefusal(pending: List<PendingAcknowledgement>?) {
        if (!enabled || _state.value.accountId == null) return
        if (pending.isNullOrEmpty()) {
            refresh(RefreshReason.REFUSAL)
            return
        }
        ingestPending(PendingAcks.Known(pending))
    }

    private fun ingestPending(pending: PendingAcks) {
        mutate { copy(pending = pending) }
        // Mid-refresh the list is about to arrive and usually carries the text:
        // the refresh's own final pass loads whatever is still missing.
        if (!inRefresh) ensureGateContent()
    }

    fun refresh(reason: RefreshReason) {
        if (!enabled) return
        scope.launch { refreshNow(reason) }
    }

    /** One refresh: `/auth/me` (pending), then the list, then the gate's text. Single-flight. */
    suspend fun refreshNow(reason: RefreshReason) {
        if (!enabled || _state.value.accountId == null) return
        if (!reason.force && now() - lastRefreshAtMs < minRefreshIntervalMs) return
        if (!refreshMutex.tryLock()) {
            // One is in flight: make it run once more when it finishes, so a
            // realtime change arriving mid-read is never lost.
            rerun = true
            return
        }
        try {
            inRefresh = true
            do {
                rerun = false
                lastRefreshAtMs = now()
                when (val me = fetchMe()) {
                    is BtResult.Ok -> ingestPending(pendingAcksOf(me.value.pendingAcknowledgements))
                    is BtResult.Err -> Log.w(TAG, "refresh(${reason.name}): /auth/me failed (${me.error.code})")
                }
                val loc = locale()
                when (val list = repo.list(loc)) {
                    is BtResult.Ok -> {
                        mutate {
                            // A list copy of an obligation replaces the gate's text unless the
                            // person picked another language for it.
                            val refreshed = gateContent.mapValues { (id, old) ->
                                if (gateLocale[id] != null && gateLocale[id] != loc) {
                                    old
                                } else {
                                    list.value.firstOrNull { it.id == id && it.kind == AnnouncementKind.ACK_REQUIRED } ?: old
                                }
                            }
                            copy(items = list.value, fetchedLocale = loc, gateContent = refreshed)
                        }
                        // PII-free: counts and kind names only — the on-device evidence that
                        // whatever shape the server answers decodes.
                        val pendingLabel = when (val p = _state.value.pending) {
                            PendingAcks.Unknown -> "unknown"
                            is PendingAcks.Known -> p.items.size.toString()
                        }
                        Log.i(
                            TAG,
                            "refresh(${reason.name}): ${list.value.size} announcement(s) " +
                                "${list.value.groupingBy { it.kind.wire }.eachCount()}, pending=$pendingLabel, locale=$loc",
                        )
                        retryFailedDismissals(list.value)
                    }
                    is BtResult.Err -> Log.w(TAG, "refresh(${reason.name}): list failed (${list.error.code})")
                }
                inRefresh = false
                ensureGateContent()
                inRefresh = true
            } while (rerun)
        } finally {
            inRefresh = false
            refreshMutex.unlock()
        }
    }

    private suspend fun retryFailedDismissals(items: List<Announcement>) {
        val ids = synchronized(retryDismiss) { retryDismiss.toList() }
        for (id in ids) {
            if (items.none { it.id == id }) {
                retryDismiss.remove(id)
                continue
            }
            if (dismissRemote(id)) retryDismiss.remove(id)
        }
    }

    // ── The gate ────────────────────────────────────────────────────────────────

    /** Make sure the head of the queue has its full text, from the list or `GET /{id}`. */
    private fun ensureGateContent() {
        val s = _state.value
        val head = s.gateQueue().firstOrNull() ?: return
        if (s.previewPending.any { it.id == head.id }) return
        if (s.gateContent[head.id] != null || head.id in s.gateLoading || head.id in s.gateLoadFailed) return
        val wanted = s.gateLocale[head.id] ?: locale()
        val fromList = s.items.firstOrNull { it.id == head.id && it.kind == AnnouncementKind.ACK_REQUIRED }
        if (fromList != null && (fromList.contentLocale == null || fromList.contentLocale == wanted)) {
            mutate { copy(gateContent = gateContent + (head.id to fromList)) }
            return
        }
        loadGateContent(head.id, wanted)
    }

    private fun loadGateContent(id: String, locale: String) {
        mutate { copy(gateLoading = gateLoading + id, gateLoadFailed = gateLoadFailed - id) }
        scope.launch {
            when (val r = repo.get(id, locale)) {
                is BtResult.Ok -> mutate {
                    copy(gateContent = gateContent + (id to r.value), gateLoading = gateLoading - id)
                }
                is BtResult.Err -> {
                    if (r.error.code == AnnouncementCodes.NOT_FOUND) {
                        // Out of the audience, archived away or never ours: not pending.
                        mutate { copy(gateLoading = gateLoading - id, resolved = resolved + id) }
                        refresh(RefreshReason.ACK_FOLLOWUP)
                    } else {
                        mutate { copy(gateLoading = gateLoading - id, gateLoadFailed = gateLoadFailed + id) }
                    }
                }
            }
        }
    }

    fun retryGateContent() {
        val g = _state.value.gate() ?: return
        if (g.preview) return
        loadGateContent(g.id, _state.value.gateLocale[g.id] ?: locale())
    }

    /** The gate's language switch (contract §5.7.3): re-fetch in [newLocale]. */
    fun switchGateLocale(newLocale: String) {
        val g = _state.value.gate() ?: return
        if (g.content?.contentLocale == newLocale) return
        if (g.preview) {
            val sample = AnnouncementPreviews.sample(AnnouncementKind.ACK_REQUIRED, newLocale)
            mutate { copy(gateContent = gateContent + (g.id to sample), gateLocale = gateLocale + (g.id to newLocale), gateNotice = null, gateError = null) }
            return
        }
        mutate { copy(gateLocale = gateLocale + (g.id to newLocale), gateNotice = null, gateError = null) }
        loadGateContent(g.id, newLocale)
    }

    /**
     * Confirm — the ticked checkbox. Echoes the `contentLocale` and
     * `contentSha256` of exactly the item on screen.
     */
    fun acknowledge() {
        if (!enabled) return
        val s = _state.value
        val g = s.gate() ?: return
        if (!g.canAcknowledge) return
        val content = g.content ?: return
        val sha = content.contentSha256 ?: return
        if (g.preview) {
            // Fake receipt: the preview never touches the network.
            mutate {
                copy(
                    resolved = resolved + g.id,
                    previewPending = previewPending.filterNot { it.id == g.id },
                    gateContent = gateContent - g.id,
                    gateNotice = null,
                    gateError = null,
                )
            }
            return
        }
        val loc = content.contentLocale ?: s.gateLocale[g.id] ?: locale()
        mutate { copy(ackBusy = true, gateNotice = null, gateError = null) }
        scope.launch {
            when (val outcome = repo.acknowledge(g.id, loc, sha)) {
                is AckOutcome.Acknowledged -> {
                    mutate {
                        copy(
                            ackBusy = false,
                            resolved = resolved + g.id,
                            pending = outcome.pending as? PendingAcks.Known ?: pending,
                            gateContent = gateContent - g.id,
                        )
                    }
                    ensureGateContent()
                }
                AckOutcome.ContentChanged -> {
                    mutate { copy(ackBusy = false, gateNotice = AnnouncementCodes.CONTENT_CHANGED, gateContent = gateContent - g.id) }
                    loadGateContent(g.id, loc)
                }
                AckOutcome.LocaleUnavailable -> {
                    val fallback = content.availableLocales.firstOrNull { it != loc }
                        ?: if (loc == LOCALE_EN) LOCALE_DE else LOCALE_EN
                    mutate {
                        copy(
                            ackBusy = false,
                            gateNotice = AnnouncementCodes.LOCALE_UNAVAILABLE,
                            gateLocale = gateLocale + (g.id to fallback),
                            gateContent = gateContent - g.id,
                        )
                    }
                    loadGateContent(g.id, fallback)
                }
                is AckOutcome.NoLongerPending -> {
                    mutate { copy(ackBusy = false, resolved = resolved + g.id, gateContent = gateContent - g.id) }
                    refresh(RefreshReason.ACK_FOLLOWUP)
                }
                is AckOutcome.Failed -> {
                    mutate { copy(ackBusy = false, gateError = outcome.error) }
                    // A 2xx we could not read DID land (first-write-wins): let the
                    // server's own pending list say so.
                    if (outcome.error.httpStatus == -1 || outcome.error.httpStatus in 200..299) {
                        refresh(RefreshReason.ACK_FOLLOWUP)
                    }
                }
            }
        }
    }

    // ── notice / once ───────────────────────────────────────────────────────────

    /** Report an item as shown — once per process, never for a preview. */
    fun markSeen(item: Announcement) {
        if (!enabled || item.preview || item.receipt?.seenAtMs != null) return
        if (!seenReported.add(item.id)) return
        scope.launch {
            when (repo.seen(item.id)) {
                ReceiptOutcome.Recorded, ReceiptOutcome.Gone, ReceiptOutcome.ScopeMissing,
                ReceiptOutcome.NotDismissible,
                -> Unit
                is ReceiptOutcome.Failed -> seenReported.remove(item.id) // a later show retries
            }
        }
    }

    /** The gate's seen receipt: once its text is on screen. */
    fun markGateSeen() {
        val g = _state.value.gate() ?: return
        g.content?.let(::markSeen)
    }

    /**
     * Close a `notice` or `once` (the banner's ✕, the card's ✕ / "Got it"). The
     * item hides at once; the server stamps the dismissal. A grant without
     * `notifications:write` (`403 INSUFFICIENT_SCOPE`) dismisses LOCALLY and
     * persists it per id, so it never returns on this account.
     */
    fun dismiss(item: Announcement) {
        if (!enabled || item.kind == AnnouncementKind.ACK_REQUIRED) return
        mutate { copy(hidden = hidden + item.id) }
        if (item.preview) return
        scope.launch {
            if (!dismissRemote(item.id)) retryDismiss.add(item.id)
        }
    }

    /** True when settled (server or local); false = transport failure, retried on the next refresh. */
    private suspend fun dismissRemote(id: String): Boolean =
        when (repo.dismiss(id)) {
            ReceiptOutcome.Recorded, ReceiptOutcome.Gone -> true
            ReceiptOutcome.ScopeMissing, ReceiptOutcome.NotDismissible -> {
                _state.value.accountId?.let { localDismiss.add(it, id) }
                true
            }
            is ReceiptOutcome.Failed -> false
        }

    // ── Debug preview ───────────────────────────────────────────────────────────

    /** Inject a sample of [kind] into the SAME pipeline the server feeds (debug builds only). */
    fun preview(kind: AnnouncementKind) {
        if (!enabled) return
        val sample = AnnouncementPreviews.sample(kind, locale())
        when (kind) {
            AnnouncementKind.ACK_REQUIRED -> mutate {
                copy(
                    previewPending = listOf(PendingAcknowledgement(sample.id, sample.severity, sample.title)),
                    gateContent = gateContent + (sample.id to sample),
                    gateLocale = gateLocale - sample.id,
                    resolved = resolved - sample.id,
                )
            }
            else -> mutate {
                copy(previewItems = previewItems.filterNot { it.kind == kind } + sample, hidden = hidden - sample.id)
            }
        }
    }

    fun clearPreviews() {
        mutate {
            val ids = previewItems.map { it.id }.toSet() + previewPending.map { it.id }
            copy(previewItems = emptyList(), previewPending = emptyList(), gateContent = gateContent - ids)
        }
    }

    private companion object {
        const val TAG = "BtAnnouncements"
        const val CLOCK_SLACK_MS = 25L
    }
}

/**
 * The modal's Confirm rule (contract §5.7.3): enabled ONLY when the box is
 * ticked, and only while there is a server-hashed text on screen and nothing in
 * flight. Pure, so the rule is unit-tested rather than eyeballed.
 */
fun ackConfirmEnabled(ticked: Boolean, gate: GateView): Boolean = ticked && gate.canAcknowledge
