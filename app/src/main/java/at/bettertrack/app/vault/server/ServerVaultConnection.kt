package at.bettertrack.app.vault.server

import at.bettertrack.app.vault.DataHomeAbsent
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeCorrupt
import at.bettertrack.app.vault.DataHomeFailureCode
import at.bettertrack.app.vault.DataHomeTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides whether BetterTrack is currently one of this vault's storage places —
 * and, when it is not, *why*, in the user's terms.
 *
 * ## The rule
 *
 * The server medium is connected when all three hold:
 *
 * 1. a BetterTrack session exists on this device, and
 * 2. its token carries `vault:sync` — knowable only by asking, since a token
 *    minted before migration `0081` looks identical until the platform answers
 *    `403 INSUFFICIENT_SCOPE`, and
 * 3. the account **has** a server vault.
 *
 * Rule 3 is the one worth defending. The app could create one — a bearer `PUT`
 * with `If-None-Match: *` is accepted for any account — and that is exactly why
 * it must not. Staging a vault is a *paranoid-mode transition*, and every such
 * transition is deliberately session-only and web-side (`PATCH /vault/media`,
 * every `account/paranoid` transition; `vaultRoutes.ts:78-98`). Writing ciphertext into a
 * normal account's blind store would produce bytes nothing reads, a "storage
 * place" the web app does not list, and a claim in our own UI that the user
 * never agreed to. So the app **joins** a media set; it never enlarges one.
 *
 * ## Why a probe and not a flag
 *
 * `privacyMode` from `/auth/me` says the account is paranoid. It does not say
 * whether the *server* medium is part of that vault's media set — a paranoid
 * Drive-only user is paranoid and has no server bytes, forever and correctly.
 * Only `GET /vault` (+ `GET /vault/media` to explain a `404`) can tell those
 * apart, so this class asks once and remembers, rather than inferring.
 */
class ServerVaultConnection(
    private val home: () -> ServerVaultDataHome?,
    private val hasSession: () -> Boolean,
) {

    private val _status = MutableStateFlow<ServerMediumStatus>(ServerMediumStatus.Unknown)

    /** What the "Where your data lives" screen renders for the BetterTrack row. */
    val status: StateFlow<ServerMediumStatus> = _status.asStateFlow()

    private val probeLock = Mutex()

    /**
     * The medium to push to, or `null` when BetterTrack is not one right now.
     *
     * Cheap after the first call: only an unresolved status re-probes, so the
     * sync loop does not pay a round trip per pass. [invalidate] is the way back
     * to a fresh answer after anything that could change it — a login, a logout,
     * or the user turning paranoid mode on in the web app.
     */
    suspend fun connectedMedium(): ServerVaultDataHome? {
        if (!hasSession()) {
            _status.value = ServerMediumStatus.NotSignedIn
            return null
        }
        val resolved = probeLock.withLock {
            if (_status.value is ServerMediumStatus.Unknown) probe() else _status.value
        }
        return if (resolved is ServerMediumStatus.Connected) home() else null
    }

    /** Forces the next [connectedMedium] to re-ask the platform. */
    fun invalidate() {
        _status.value = ServerMediumStatus.Unknown
    }

    /** Re-probes now and returns the fresh status — the settings screen's refresh. */
    suspend fun refresh(): ServerMediumStatus = probeLock.withLock {
        if (!hasSession()) {
            _status.value = ServerMediumStatus.NotSignedIn
            _status.value
        } else {
            probe()
        }
    }

    private suspend fun probe(): ServerMediumStatus {
        val server = home()
        if (server == null) {
            _status.value = ServerMediumStatus.NotSignedIn
            return _status.value
        }
        val next = when (val read = server.read()) {
            is DataHomeBytes -> ServerMediumStatus.Connected(read.info.version)

            is DataHomeAbsent -> ServerMediumStatus.NoServerVault(absenceOf(server))

            is DataHomeTransport -> when (read.failure.code) {
                DataHomeFailureCode.SCOPE_MISSING -> ServerMediumStatus.ScopeMissing
                DataHomeFailureCode.OFFLINE -> ServerMediumStatus.Unreachable(read.failure.message)
                DataHomeFailureCode.TOKEN_EXPIRED -> ServerMediumStatus.NotSignedIn
                else -> ServerMediumStatus.Unreachable(read.failure.message)
            }

            // Bytes exist but this build cannot read them. That is still a
            // connection — refusing to overwrite is the correct behaviour, and
            // the sync loop's own corrupt branch already does exactly that.
            is DataHomeCorrupt -> ServerMediumStatus.Unreadable(read.message)
        }
        _status.value = next
        return next
    }

    private suspend fun absenceOf(server: ServerVaultDataHome): ServerVaultAbsence =
        when (val media = server.mediaState()) {
            is ServerVaultMediaResult.Failure -> ServerVaultAbsence.UNKNOWN
            is ServerVaultMediaResult.Ok -> when {
                !media.state.isParanoid -> ServerVaultAbsence.ACCOUNT_IS_NORMAL
                !media.state.mediaSetContainsServer -> ServerVaultAbsence.DRIVE_ONLY_VAULT
                else -> ServerVaultAbsence.NO_BYTES_YET
            }
        }
}

/** The BetterTrack medium's disposition, as a designed state rather than a boolean. */
sealed interface ServerMediumStatus {
    /** Not asked yet. Never rendered — it resolves on first use. */
    data object Unknown : ServerMediumStatus

    /** BetterTrack holds this vault at [version]; it is a live push target. */
    data class Connected(val version: Int) : ServerMediumStatus

    /**
     * The account has no server vault. [reason] carries the sentence: a normal
     * account is told paranoid mode is a web setting, a Drive-only vault is told
     * where its bytes actually live.
     */
    data class NoServerVault(val reason: ServerVaultAbsence) : ServerMediumStatus

    /** `403 INSUFFICIENT_SCOPE` — one act fixes it: sign out and back in. */
    data object ScopeMissing : ServerMediumStatus

    data object NotSignedIn : ServerMediumStatus

    data class Unreachable(val message: String) : ServerMediumStatus

    /** Bytes are there but a newer app wrote them, or they are damaged. */
    data class Unreadable(val message: String) : ServerMediumStatus
}
