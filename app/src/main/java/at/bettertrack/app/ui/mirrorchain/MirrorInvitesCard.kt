package at.bettertrack.app.ui.mirrorchain

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.MirrorInvite
import at.bettertrack.app.data.repo.MirrorchainRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── State ────────────────────────────────────────────────────────────────────

/**
 * There is deliberately no `Error` case that draws anything. This card lives on
 * someone else's screen (the Social tab) and is an interruption by definition —
 * it may only take space when it has something the user must answer. A failed
 * load, an empty list, or a still-running first load therefore all render as
 * [Hidden]: zero height, no skeleton, no "couldn't load invites" apology for a
 * thing the user never asked to see.
 */
internal sealed interface MirrorInvitesUiState {
    data object Hidden : MirrorInvitesUiState
    data class Loaded(val incoming: List<MirrorInvite>) : MirrorInvitesUiState
}

/** A message with its args, resolved to a string only at the composable. */
internal sealed interface MirrorInviteMessage {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : MirrorInviteMessage

    /**
     * A refusal this card has no copy of its own for. It carries the app-owned
     * [BtMessage] for the server's error code — not the server's English
     * sentence, which used to be pasted straight onto the row (S6 P0-4) and now
     * survives only as that message's dim diagnostic half.
     */
    data class Failure(val message: BtMessage) : MirrorInviteMessage
}

/** An inline refusal pinned to the invite row it belongs to. */
internal data class MirrorInviteRowError(val inviteId: String, val message: MirrorInviteMessage)

internal class MirrorInvitesViewModel(
    private val repo: MirrorchainRepository,
    private val portfolios: PortfolioRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<MirrorInvitesUiState>(MirrorInvitesUiState.Hidden)
    val state: StateFlow<MirrorInvitesUiState> = _state.asStateFlow()

    private val _busyInviteId = MutableStateFlow<String?>(null)
    val busyInviteId: StateFlow<String?> = _busyInviteId.asStateFlow()

    private val _rowError = MutableStateFlow<MirrorInviteRowError?>(null)
    val rowError: StateFlow<MirrorInviteRowError?> = _rowError.asStateFlow()

    private val _toast = MutableStateFlow<MirrorInviteMessage?>(null)
    val toast: StateFlow<MirrorInviteMessage?> = _toast.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = when (val r = repo.invites()) {
                // OUTGOING invites are deliberately not rendered. An invite I sent
                // needs nothing from me — I cannot revoke it from the phone either,
                // because revoke is one of the session-only admin routes — so a
                // second list would be a read-only reminder of a thing I already
                // know, on a card whose entire justification is "something needs
                // your answer". Incoming only.
                is BtResult.Ok -> MirrorInvitesUiState.Loaded(r.value.incoming)
                is BtResult.Err -> MirrorInvitesUiState.Hidden
            }
        }
    }

    fun accept(invite: MirrorInvite) = act(invite) {
        when (val r = repo.accept(invite.id)) {
            is BtResult.Ok -> {
                // Accepting materializes a brand-new local portfolio server-side;
                // pull it down so the switcher shows it before the toast lands.
                portfolios.refreshPortfolios()
                _toast.value = MirrorInviteMessage.Res(
                    R.string.bt_chain_invite_accepted_toast,
                    listOf(invite.chainName),
                )
                load()
            }

            is BtResult.Err -> handle(invite.id, r.error)
        }
    }

    fun decline(invite: MirrorInvite) = act(invite) {
        when (val r = repo.decline(invite.id)) {
            is BtResult.Ok -> {
                _toast.value = MirrorInviteMessage.Res(R.string.bt_chain_invite_declined_toast)
                load()
            }

            is BtResult.Err -> handle(invite.id, r.error)
        }
    }

    fun consumeToast() { _toast.value = null }

    private fun act(invite: MirrorInvite, block: suspend () -> Unit) {
        if (_busyInviteId.value != null) return
        viewModelScope.launch {
            _busyInviteId.value = invite.id
            _rowError.value = null
            block()
            _busyInviteId.value = null
        }
    }

    private fun handle(inviteId: String, error: BtApiError) {
        val message = when (error.code) {
            // 404: gone, expired, or already answered elsewhere. The honest
            // response is to stop showing a row that no longer exists — not to
            // report a failure for an action that is, in effect, already done.
            MirrorchainRepository.CODE_INVITE_NOT_FOUND -> {
                load()
                return
            }

            MirrorchainRepository.CODE_MEMBER_CAP ->
                MirrorInviteMessage.Res(R.string.bt_chain_err_cap)

            MirrorchainRepository.CODE_NOT_FRIENDS ->
                MirrorInviteMessage.Res(R.string.bt_chain_err_not_friends)

            // 503 is lock contention, not a refusal. The row stays, the buttons
            // stay live — tapping again IS the retry, which beats a second button
            // that does the same thing as the first.
            MirrorchainRepository.CODE_BUSY ->
                MirrorInviteMessage.Res(R.string.bt_chain_err_busy)

            else -> MirrorInviteMessage.Failure(error.asMessage())
        }
        _rowError.value = MirrorInviteRowError(inviteId, message)
    }
}

// ── Card ─────────────────────────────────────────────────────────────────────

/**
 * Incoming group-portfolio (mirrorchain) invites — accept or decline, nothing
 * else. Self-contained: it owns its ViewModel so a host screen only has to place
 * it, and it collapses to **zero height** whenever there is nothing to answer.
 */
@Composable
fun MirrorInvitesCard(modifier: Modifier = Modifier) {
    val vm: MirrorInvitesViewModel = viewModel {
        MirrorInvitesViewModel(AppGraph.mirrorchainRepository, AppGraph.portfolioRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val busyId by vm.busyInviteId.collectAsStateWithLifecycle()
    val rowError by vm.rowError.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbar = LocalBtSnackbar.current

    // One feedback idiom (S6 P1-9): the app-level snackbar, not a system toast.
    // Both of this card's confirmations take at most the chain name, so they map
    // straight onto a resource plus its single format argument.
    LaunchedEffect(toast) {
        when (val t = toast) {
            null -> return@LaunchedEffect
            is MirrorInviteMessage.Failure -> snackbar.showError(t.message)
            is MirrorInviteMessage.Res -> snackbar.show(t.id, *t.args.toTypedArray())
        }
        vm.consumeToast()
    }

    val invites = (state as? MirrorInvitesUiState.Loaded)?.incoming.orEmpty()
    if (invites.isEmpty()) return

    val bt = BtTheme.colors
    // A gold-tinted surface rather than a plain BtCard: this is the one card on
    // the screen that asks for a decision, and the app's gold accent is exactly
    // the "act on me" signal. The deep amber goldSurface fill stays reserved for
    // selection, so a translucent tint + gold border is the right weight here.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BtShapes.card,
        color = bt.gold.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.30f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Group,
                    contentDescription = null,
                    tint = bt.goldEmphasis,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.bt_chain_invites_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (invites.size > 1) {
                    BtBadge(text = invites.size.toString(), kind = BtBadgeKind.Gold)
                }
            }
            invites.forEach { invite ->
                Spacer(Modifier.height(10.dp))
                InviteRow(
                    invite = invite,
                    busy = busyId != null,
                    error = rowError?.takeIf { it.inviteId == invite.id }?.message,
                    onAccept = { vm.accept(invite) },
                    onDecline = { vm.decline(invite) },
                )
            }
        }
    }
}

@Composable
private fun InviteRow(
    invite: MirrorInvite,
    busy: Boolean,
    error: MirrorInviteMessage?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BtAvatar(name = invite.fromUsername ?: invite.chainName, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = invite.chainName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // `fromUsername` is null when the inviter's account is gone; the
                // invite itself is still answerable, so the line just drops the
                // "who" instead of inventing one.
                val who = invite.fromUsername?.let {
                    stringResource(R.string.bt_chain_invite_from, it)
                }
                Text(
                    text = listOfNotNull(who, formatChainMoment(invite.createdAt, locale))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (error) {
                    // One line to work with, so the diagnostic (when there is
                    // one) trails the app's sentence instead of taking a row.
                    is MirrorInviteMessage.Failure -> error.message.resolveWithDiagnostic()
                    is MirrorInviteMessage.Res ->
                        stringResource(error.id, *error.args.toTypedArray())
                },
                style = MaterialTheme.typography.bodySmall,
                color = bt.loss,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDecline, enabled = !busy) {
                Text(
                    text = stringResource(R.string.bt_chain_invite_decline),
                    color = if (busy) bt.textMuted else bt.textSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            BtPrimaryButton(
                text = stringResource(R.string.bt_chain_invite_accept),
                onClick = onAccept,
                enabled = !busy,
                modifier = Modifier.height(40.dp),
            )
        }
    }
}

// ── Shared time formatting ───────────────────────────────────────────────────

/**
 * Renders a server ISO-8601 instant in the device's locale and zone.
 *
 * A relative "2d" would be shorter, but every relative formatter in this app is
 * English-only, and these timestamps sit next to German copy. A localized medium
 * date/short time is honest in both languages and needs no string resource. A
 * value the parser cannot read is shown verbatim rather than dropped — better a
 * raw timestamp than a silently missing one.
 */
internal fun formatChainMoment(iso: String, locale: Locale): String {
    val instant = try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (_: Exception) {
        try {
            Instant.parse(iso)
        } catch (_: Exception) {
            null
        }
    } ?: return iso
    return DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}
