package at.bettertrack.app.data.auth

import android.net.Uri
import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.db.LocalAccountData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * The single source of truth for authentication (spec §4). It:
 *  - resolves startup routing from the persisted session (survives process death),
 *  - runs the PKCE Authorization-Code round-trip (begin → callback → exchange),
 *  - fetches the current user and gates admin / disabled / must-change-password,
 *  - performs logout with best-effort revocation of THIS install's token family
 *    (`POST /oauth/revoke`, PR-B) + full local wipe,
 *  - flips to logged-out whenever a refresh is rejected downstream.
 *
 * UI observes [authState] (navigation gate) and [loginPhase] (login screen).
 *
 * [onSessionAuthenticated] fires once a login round-trip yields a usable session
 * (used to register the FCM device token); [onBeforeLogout] runs at the START of
 * an explicit logout, while the bearer is still valid, so the device token can be
 * deregistered BEFORE credentials are wiped. Both are decoupled seams (default
 * no-ops) so this class stays free of push/notification knowledge and testable.
 */
class AuthRepository(
    private val tokenManager: TokenManager,
    private val btApi: BtApi,
    private val store: SessionStore,
    private val json: Json,
    private val webOrigin: String,
    private val clientId: String,
    private val scope: CoroutineScope,
    /** Step 5: account-keyed Room data — wiped on logout / account switch (§7.3). */
    private val localAccountData: LocalAccountData,
    /** Fired when a login yields a usable session (e.g. register the push token). */
    private val onSessionAuthenticated: () -> Unit = {},
    /** Run at the start of an explicit logout, before any local wipe (bearer valid). */
    private val onBeforeLogout: suspend () -> Unit = {},
    /**
     * The `Accept-Language` for `GET /auth/me` — in-app language, else device
     * language ([at.bettertrack.app.data.i18n.acceptLanguageFor]); `null` omits
     * the header. The server localises announcement titles from it (#2094).
     */
    private val acceptLanguage: () -> String? = { null },
    /**
     * Run after an explicit logout has completed (S3/S4 plan §4.4 row 2).
     *
     * Logging out of a BetterTrack account in BOTH mode must not leave the
     * install claiming a server medium it no longer has — the vault survived the
     * scoped wipe and is now the only place the data lives, so the mode demotes
     * to DRIVE. Kept as a hook rather than a direct storage-mode dependency so
     * this class stays about sessions.
     */
    private val onAfterLogout: () -> Unit = {},
    /**
     * Where every transition to signed-out is written down. The owner was being
     * returned to the login screen with no explanation and no way to find one
     * after the fact; this is that record. See [SignOutLedger].
     */
    private val ledger: SignOutLedger = NoopSignOutLedger,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Every `MeResponse` this class receives, as it arrives — the announcements
     * pipeline reads `pendingAcknowledgements` off it (contract §4.6: the field
     * is on `/auth/me` AND on every sign-in/account response). A decoupled seam
     * with a no-op default, like the two hooks above, so this class stays about
     * sessions and its tests need not care.
     */
    private val onMeResponse: (at.bettertrack.app.data.api.dto.MeResponse) -> Unit = {},
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _loginPhase = MutableStateFlow<LoginPhase>(LoginPhase.Idle)
    val loginPhase: StateFlow<LoginPhase> = _loginPhase.asStateFlow()

    init {
        val presence = tokenManager.sessionPresence()
        _authState.value = resolveInitialState(presence)
        // Confirm local-data ownership for a restored session (no-op when same user).
        (authState.value as? AuthState.LoggedIn)?.let { s ->
            scope.launch { localAccountData.onSessionEstablished(s.user.id) }
        }
        // Any downstream refresh rejection wipes the session ⇒ drop to login.
        scope.launch {
            tokenManager.sessionInvalidated.collect { forceLoggedOut(it) }
        }
        // The store said "I cannot read myself right now" (a busy Keystore, a
        // data directory that was not ready). The bytes are still there and
        // SecureStore no longer deletes them, so keep asking: a session that
        // comes back within a few seconds never reaches the user as a logout.
        if (presence is TokenRead.Unavailable) scope.launch { recoverUnreadableStore() }
    }

    private suspend fun recoverUnreadableStore() {
        repeat(STORE_RECOVERY_ATTEMPTS) { attempt ->
            delay(STORE_RECOVERY_DELAY_MS * (attempt + 1))
            if (_authState.value !is AuthState.LoggedOut) return
            if (!store.reopen()) return@repeat
            val recovered = resolveInitialState(tokenManager.sessionPresence())
            if (recovered !is AuthState.LoggedOut) {
                Log.i(TAG, "Encrypted store came back; session restored without a re-login.")
                _authState.value = recovered
                (recovered as? AuthState.LoggedIn)?.let {
                    localAccountData.onSessionEstablished(it.user.id)
                }
                return
            }
        }
    }

    /** Startup routing (spec §4): a stored token = still logged in across restarts. */
    private fun resolveInitialState(presence: TokenRead): AuthState {
        when (presence) {
            is TokenRead.Present -> Unit

            TokenRead.None -> return AuthState.LoggedOut

            is TokenRead.Unavailable -> {
                // NOT a logout in any meaningful sense — nothing was revoked and
                // nothing was deleted. It only LOOKS like one, which is exactly
                // why it has to be written down.
                Log.w(TAG, "Session storage unreadable at startup (${presence.cause}); showing login.")
                ledger.record(
                    SignOutEvent(
                        at = nowMs(),
                        reason = SignOutReason.SECURE_STORE_UNAVAILABLE.name,
                        errorCode = presence.cause,
                        trigger = SignOutTrigger.STARTUP.name,
                        caller = "AuthRepository.resolveInitialState",
                    ),
                )
                return AuthState.LoggedOut
            }
        }
        val user = store.loadUser() ?: SessionUser.unknown()
        // Cold start: route a paranoid account to the explainer from the PERSISTED
        // privacyMode, before /auth/me has answered — that is the whole point of
        // the pre-flight signal (#39.1). refreshUser() corrects it either way.
        at.bettertrack.app.data.api.ParanoidModeState.applyPrivacyMode(user.privacyMode)
        return if (user.mustChangePassword) {
            AuthState.PasswordChangeRequired(user)
        } else {
            AuthState.LoggedIn(user)
        }
    }

    // ── Login round-trip ──────────────────────────────────────────────────────

    /**
     * Begin authorization: generate PKCE + state, persist them (so the callback
     * survives process death), flip to in-progress, and return the authorize URL
     * to open in a Custom Tab.
     */
    fun beginAuthorization(): Uri {
        val codeVerifier = Pkce.generateCodeVerifier()
        val codeChallenge = Pkce.codeChallengeFor(codeVerifier)
        val state = Pkce.generateState()
        store.savePending(codeVerifier, state)
        _loginPhase.value = LoginPhase.InProgress
        return OAuthConfig.authorizeUrl(webOrigin, codeChallenge, state)
    }

    /** The user returned from the Custom Tab without completing — silent idle (§4). */
    fun onAuthorizationCancelled() {
        if (_loginPhase.value is LoginPhase.InProgress) {
            _loginPhase.value = LoginPhase.Idle
        }
        store.clearPending()
    }

    /** Handle the `bettertrack://oauth/callback` deep link (cold or warm). */
    fun onAuthorizationResult(uri: Uri) {
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        val pending = store.loadPending()

        scope.launch {
            // Server-side denial / error on the authorize page.
            if (error != null) {
                store.clearPending()
                _loginPhase.value = if (error == "access_denied") {
                    LoginPhase.Idle // user chose "cancel" on the web page — not an error
                } else {
                    LoginPhase.Failed(LoginError.SERVER_DENIED, error)
                }
                return@launch
            }
            // No code, no pending request, or a state mismatch ⇒ reject.
            if (code == null || pending == null || returnedState == null ||
                returnedState != pending.second
            ) {
                store.clearPending()
                _loginPhase.value = LoginPhase.Failed(LoginError.STATE_MISMATCH)
                return@launch
            }

            _loginPhase.value = LoginPhase.InProgress
            when (val exchanged = tokenManager.exchange(code, pending.first)) {
                is BtResult.Err -> {
                    store.clearPending()
                    // `detail` is a diagnostic the login screen never renders (it
                    // shows app copy keyed off LoginError), so this stays the
                    // server's raw words rather than going through BtErrorCopy.
                    _loginPhase.value = LoginPhase.Failed(
                        loginErrorForExchange(exchanged.error),
                        exchanged.error.diagnostic,
                    )
                }

                is BtResult.Ok -> {
                    store.clearPending()
                    completeLogin()
                }
            }
        }
    }

    /**
     * After a successful token exchange: fetch the user and gate the session.
     * `internal` so `LoginIdentityGateTest` can drive it against a scripted
     * `/auth/me` (the deep link that reaches it carries an `android.net.Uri`).
     */
    internal suspend fun completeLogin() {
        when (val me = apiCall(json) { btApi.me(acceptLanguage()) }) {
            is BtResult.Ok -> {
                val user = me.value.toSessionUser()
                when {
                    user.role == "admin" -> wipeAndFail(LoginError.ADMIN_NOT_ALLOWED)
                    user.status == "disabled" -> wipeAndFail(LoginError.ACCOUNT_DISABLED)
                    else -> {
                        store.saveUser(user)
                        reportMe(me.value)
                        // PROACTIVE paranoid routing (#39.1): the server told us the
                        // account's privacyMode, so the portfolio surfaces can show the
                        // explainer instead of firing a burst of doomed calls and
                        // flashing error states until a 403 teaches the interceptor.
                        at.bettertrack.app.data.api.ParanoidModeState
                            .applyPrivacyMode(user.privacyMode)
                        // Detects an account switch and wipes local data if so (§7.3).
                        localAccountData.onSessionEstablished(user.id)
                        _authState.value = if (user.mustChangePassword) {
                            AuthState.PasswordChangeRequired(user)
                        } else {
                            AuthState.LoggedIn(user)
                        }
                        _loginPhase.value = LoginPhase.Idle
                        // No username/email in logs — logcat must stay PII-free.
                        Log.i(TAG, "Logged in (role=${user.role}, firstRun=${user.firstRun}).")
                        onSessionAuthenticated()
                    }
                }
            }

            is BtResult.Err -> {
                if (me.error.isIdentityRejected) {
                    // The freshly-minted token itself was refused (401) — abort to login.
                    wipeAndFail(LoginError.GENERIC)
                } else {
                    // /auth/me unavailable (network hiccup, a 5xx, a 400 that is
                    // about the request rather than the token — or the platform's
                    // missing bearer identity endpoint): the session is valid, so
                    // proceed with a placeholder user; Settings refetches later.
                    val user = SessionUser.unknown()
                    store.saveUser(user)
                    // Identity unknown ⇒ the owner gate keeps existing data (§7.3
                    // — an expired session must never cost queued entries).
                    localAccountData.onSessionEstablished(user.id)
                    _authState.value = AuthState.LoggedIn(user)
                    _loginPhase.value = LoginPhase.Idle
                    Log.w(TAG, "Logged in but /auth/me failed transiently: ${me.error.message}")
                    onSessionAuthenticated()
                }
            }
        }
    }

    /**
     * Best-effort refresh of the cached user (e.g. when Settings opens, or after a
     * transient failure during login). Never changes auth state on failure.
     */
    fun refreshUser() {
        if (!tokenManager.hasTokens()) return
        scope.launch { refreshUserNow() }
    }

    /**
     * [refreshUser], awaited, answering what `/auth/me` said. The announcements
     * pipeline calls this on launch and resume: it needs `pendingAcknowledgements`
     * from the very response that also keeps the session user current, so the
     * two can never be read from different answers.
     */
    suspend fun refreshUserNow(): BtResult<at.bettertrack.app.data.api.dto.MeResponse> {
        if (!tokenManager.hasTokens()) {
            return BtResult.Err(at.bettertrack.app.data.api.BtApiError(401, "UNAUTHENTICATED", "no session"))
        }
        val me = apiCall(json) { btApi.me(acceptLanguage()) }
        run {
            when (me) {
                is BtResult.Ok -> {
                    val user = me.value.toSessionUser()
                    when {
                        user.role == "admin" -> wipeAndFail(LoginError.ADMIN_NOT_ALLOWED)
                        user.status == "disabled" -> wipeAndFail(LoginError.ACCOUNT_DISABLED)
                        else -> {
                            store.saveUser(user)
                            reportMe(me.value)
                            // Keeps the paranoid routing honest inside a live session:
                            // a mode flipped web-side is picked up on the next refresh
                            // without waiting for a portfolio call to be refused.
                            at.bettertrack.app.data.api.ParanoidModeState
                                .applyPrivacyMode(user.privacyMode)
                            // Late identity resolution upgrades the local-data owner key.
                            localAccountData.onSessionEstablished(user.id)
                            _authState.value = if (user.mustChangePassword) {
                                AuthState.PasswordChangeRequired(user)
                            } else {
                                AuthState.LoggedIn(user)
                            }
                            // The first-run signal, named rather than inferred.
                            // UNKNOWN and DONE look identical on every surface —
                            // both simply show no wizard — so without this line
                            // there is no way to tell "the server says this
                            // account is set up" from "the server never sent the
                            // field". PII-free by construction: it is one of
                            // three enum names.
                            Log.i(TAG, "Session refreshed (firstRun=${user.firstRun}).")
                        }
                    }
                }

                is BtResult.Err -> Log.w(TAG, "refreshUser failed: ${me.error.message}")
            }
        }
        return me
    }

    /** Hand a fresh `MeResponse` to [onMeResponse]; a failing listener never fails auth. */
    private fun reportMe(me: at.bettertrack.app.data.api.dto.MeResponse) {
        try {
            onMeResponse(me)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "onMeResponse listener failed: ${e.javaClass.simpleName}")
        }
    }

    // ── First-run setup (§6.12) ───────────────────────────────────────────────

    /**
     * Tell the server this account's setup run is over — finished or dismissed.
     *
     * The endpoint answers the **fresh** `/auth/me`, so the stored session user is
     * rebuilt from that body rather than from an optimistic local edit: the
     * server's own `firstRunCompletedAt` is what the gate reads, and inventing a
     * timestamp the server may not have written would hide a failed call.
     *
     * Deliberately NOT best-effort-silent like [refreshUser]: the wizard's
     * terminal step renders this result (retry on failure), because a completion
     * that quietly did not land would bring the wizard back on the next launch
     * with no explanation. The local dismissal record is what keeps the user out
     * of a loop meanwhile.
     */
    suspend fun completeFirstRun(): BtResult<SessionUser> =
        when (val r = apiCall(json) { btApi.completeFirstRun() }) {
            is BtResult.Ok -> {
                val user = r.value.toSessionUser()
                store.saveUser(user)
                reportMe(r.value)
                _authState.value = if (user.mustChangePassword) {
                    AuthState.PasswordChangeRequired(user)
                } else {
                    AuthState.LoggedIn(user)
                }
                Log.i(TAG, "First-run setup marked complete (state=${user.firstRun}).")
                BtResult.Ok(user)
            }

            is BtResult.Err -> {
                Log.w(TAG, "completeFirstRun failed: ${r.error.message}")
                r
            }
        }

    // ── The one-time paranoid fresh-start notice (§17 step 3) ─────────────────

    /**
     * Acknowledge the fresh-start notice, spending it server-side.
     *
     * Modelled on [completeFirstRun] down to the last line, because the platform
     * modelled the route on `/auth/first-run/complete`: no payload, set-once, and
     * it answers the fresh `/auth/me` body — so the stored session is rebuilt
     * from the server's own `paranoidFreshStartPending` instead of an optimistic
     * local `false`. That is the whole guarantee that a failed acknowledgement
     * cannot hide an unacknowledged server state: nothing local ever writes the
     * flag down as spent.
     *
     * NOT best-effort-silent like [refreshUser]: the sheet renders this result
     * inline (progress, then error + retry). A silent failure would look like a
     * dismissal and bring the notice back next launch with no explanation.
     */
    suspend fun acknowledgeFreshStartNotice(): BtResult<SessionUser> =
        when (val r = apiCall(json) { btApi.acknowledgeFreshStartNotice() }) {
            is BtResult.Ok -> {
                val user = r.value.toSessionUser()
                store.saveUser(user)
                reportMe(r.value)
                _authState.value = if (user.mustChangePassword) {
                    AuthState.PasswordChangeRequired(user)
                } else {
                    AuthState.LoggedIn(user)
                }
                Log.i(TAG, "Fresh-start notice acknowledged (pending=${user.paranoidFreshStartPending}).")
                BtResult.Ok(user)
            }

            is BtResult.Err -> {
                Log.w(TAG, "acknowledgeFreshStartNotice failed: ${r.error.message}")
                r
            }
        }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget logout for UI callers.
     *
     * [reason] is recorded in the sign-out ledger so the login screen can tell a
     * deliberate logout apart from a forced one — the whole point of the ledger
     * is that "why am I here again?" has an answer, and "because you tapped Log
     * out" is one of the answers.
     */
    fun requestLogout(reason: SignOutReason = SignOutReason.USER_LOGOUT) {
        scope.launch { logout(reason) }
    }

    /**
     * Revoke server-side (best effort), then wipe ALL local state (spec §4).
     *
     * **Logging out always completes.** Every step before the credential wipe is
     * best-effort and individually guarded, because each one of them talks to
     * something that can be unavailable — the network (deregister, revoke) or
     * Room (the account-scoped wipe). A logout that threw half-way used to leave
     * the tokens on disk and the user still "signed in" to a server they cannot
     * reach; the local wipe is the part the user actually asked for, so it runs
     * whatever the rest did.
     */
    suspend fun logout(reason: SignOutReason = SignOutReason.USER_LOGOUT) {
        // Read the pair FIRST: everything after this may lose it (a refresh the
        // deregistration triggers rotates it; a refused one wipes it), and the
        // revocation needs a token of this install's family to name it.
        val held = store.loadTokens()
        ledger.record(
            SignOutEvent(
                at = nowMs(),
                reason = reason.name,
                trigger = SignOutTrigger.USER.name,
                caller = "AuthRepository.logout",
            ),
        )
        // Deregister the FCM device token FIRST, while the bearer is still valid
        // (bounded + fail-soft inside the hook — logout never blocks on it).
        bestEffort("deregister device token") { onBeforeLogout() }
        // The newest pair on disk if the deregistration rotated it; the one read
        // above if something wiped it meanwhile. Either names the same family.
        revokeInstallBestEffort(store.loadTokens() ?: held)
        // Explicit logout wipes the account-keyed Room data too: caches AND the
        // outbound sync queue, plus any scheduled sync work (§7.3). The wipe is
        // storage-mode aware (S3/S4 plan §4.4): in SERVER mode — the only mode
        // reachable today — it is the same full wipe as always, but once a Drive
        // vault can exist, logging out of the BetterTrack account must not
        // destroy data the user still owns.
        bestEffort("wipe local account data") { localAccountData.wipeForLogout() }
        store.wipeAll()
        _authState.value = AuthState.LoggedOut
        _loginPhase.value = LoginPhase.Idle
        // AFTER the wipe: the wipe scope itself is decided by the mode we are
        // leaving, so demoting first would run the full EVERYTHING wipe under a
        // mode that still holds a vault — and destroy it.
        bestEffort("post-logout storage demotion") { onAfterLogout() }
    }

    /** One logout step whose failure must not stop the ones after it. */
    private suspend fun bestEffort(what: String, step: suspend () -> Unit) {
        try {
            step()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Logout step '$what' failed; continuing with the wipe.", e)
        }
    }

    /**
     * Revoke THIS install's token family server-side — its access and refresh
     * tokens, and any successor the server minted that the phone never saw —
     * see [revokeInstallOnSignOut] for the two paths and their order.
     *
     * Per install, never grant-wide: since PR-B every install of the app shares
     * one grant, so deleting the grant would also sign out the user's tablet.
     * Other installs, the account, its data and its web sessions are untouched.
     *
     * Best-effort in every direction and bounded in time: a refusal, a network
     * failure or a 5xx is logged and the local wipe proceeds regardless. A
     * sign-out that cannot reach the server must still leave the phone with
     * nothing on it, and must not keep the person waiting to find that out.
     */
    private suspend fun revokeInstallBestEffort(held: AuthTokens?) {
        try {
            val outcome = revokeInstallOnSignOut(
                held = held,
                revoke = { token, hint -> tokenManager.postRevoke(token, hint) },
                bearerLogout = { btApi.logout() },
            )
            Log.i(TAG, "Sign-out revocation: $outcome")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Best-effort revocation failed: ${e.javaClass.simpleName}")
        }
    }

    private fun wipeAndFail(error: LoginError) {
        ledger.record(
            SignOutEvent(
                at = nowMs(),
                reason = SignOutReason.ACCOUNT_GATE.name,
                errorCode = error.name,
                trigger = SignOutTrigger.ACCOUNT_GATE.name,
                caller = "AuthRepository.wipeAndFail",
            ),
        )
        // A gated account (admin / disabled) must leave no local data behind.
        scope.launch { localAccountData.wipeAll() }
        store.wipeAll()
        _authState.value = AuthState.LoggedOut
        _loginPhase.value = LoginPhase.Failed(error)
    }

    private fun forceLoggedOut(invalidation: SessionInvalidation) {
        ledger.record(
            SignOutEvent(
                at = nowMs(),
                reason = invalidation.reason.name,
                httpStatus = invalidation.httpStatus,
                errorCode = invalidation.errorCode,
                trigger = SignOutTrigger.REFRESH.name,
                caller = invalidation.caller,
            ),
        )
        // Session expiry — NOT a logout: tokens go, but the Room caches and the
        // outbound queue survive so a re-login of the same user resumes the
        // drain with nothing lost (§7.3). The owner gate at next login wipes if
        // it turns out to be a DIFFERENT user.
        store.wipeAll()
        _authState.value = AuthState.LoggedOut
        _loginPhase.value = LoginPhase.Idle
    }

    /** The sign-out history, newest first — rendered by the login screen. */
    fun signOutHistory(): List<SignOutEvent> = ledger.recent()

    // ── Web links (open in the browser, spec §4) ───────────────────────────────
    fun webHomeUrl(): String = webOrigin.trimEnd('/')
    fun needAccountUrl(): String = "${webOrigin.trimEnd('/')}/register"
    fun forgotPasswordUrl(): String = "${webOrigin.trimEnd('/')}/forgot-password"

    private companion object {
        const val TAG = "BtAuth"

        /** How persistently a momentarily-unreadable Keystore is re-tried. */
        const val STORE_RECOVERY_ATTEMPTS = 4
        const val STORE_RECOVERY_DELAY_MS = 500L
    }
}

/**
 * The login screen's verdict on a failed code exchange. Two refusals get their
 * own sentence because "try again" is wrong for both: `INVALID_SCOPE` means the
 * app was narrowed to nothing this account consented to (retrying re-asks the
 * same question), and `INVALID_CLIENT` means this build's client id is unknown
 * (only an update fixes it).
 */
internal fun loginErrorForExchange(error: at.bettertrack.app.data.api.BtApiError): LoginError = when {
    error.isNetwork -> LoginError.NETWORK
    error.code.equals("INVALID_SCOPE", ignoreCase = true) -> LoginError.SCOPE_REFUSED
    error.code.equals("INVALID_CLIENT", ignoreCase = true) -> LoginError.CLIENT_REJECTED
    else -> LoginError.EXCHANGE_FAILED
}

/** What a sign-out's server-side revocation achieved — logged, and pinned by tests. */
internal enum class LogoutRevocation {
    /** `POST /oauth/revoke` answered 2xx: this install's family is dead (or already was). */
    REVOKED,

    /** `/oauth/revoke` failed without a verdict; bearer `POST /auth/logout` revoked the family. */
    LOGGED_OUT,

    /** `/oauth/revoke` failed without a verdict and the bearer was refused (401): already gone. */
    ALREADY_GONE,

    /**
     * `/oauth/revoke` refused the REQUEST (`400`: `INVALID_CLIENT`, or a body the
     * strict schema rejects) — a build problem, not a network one, so no
     * fallback: nothing about retrying another route makes this build's client
     * id known. The family stays live server-side until it expires; the user can
     * still end it from the web.
     */
    REFUSED,

    /** No session on the phone (or it is unreadable): there is nothing to name. */
    NOTHING_HELD,

    /** Neither path landed (offline, timeout, 5xx). The local wipe still runs. */
    FAILED,
}

/** The longest a sign-out waits for each server call. Two at most: ≤ 2 × this. */
internal const val SIGN_OUT_STEP_TIMEOUT_MS: Long = 6_000L

/**
 * Revoke this install's token family at sign-out.
 *
 * **Primary: `POST /oauth/revoke` with the stored refresh token** (RFC 7009,
 * PR-B #2085), over the bare one-shot token client — no bearer, so it works when
 * the access token has long expired, and no OkHttp re-send. The server revokes
 * the presented token's whole FAMILY, i.e. this install and nothing else.
 *
 * The refresh token on disk is the right one to present even when a refresh of
 * it is pending (a lost answer, see [PendingRefresh]): the server resolves a
 * revocable token "in ANY state (expired, consumed, revoked)"
 * (`oauthRepository.ts` → `findTokenForRevocation`, platform main at #2085) and
 * revokes its family, which takes the unseen successor with it. The phone never
 * holds a token newer than the one on disk — the write-ahead record stores only
 * a fingerprint of that same token.
 *
 * **Secondary: bearer `POST /auth/logout`**, which since PR-B also revokes only
 * the caller's family — taken ONLY when `/oauth/revoke` failed without a verdict:
 * a transport failure, a timeout, a 5xx, a 429, or any status that is not the
 * endpoint judging this request (a 404 from a deployment without the route). A
 * `2xx` is success and a `400` is the endpoint's verdict, so neither falls back.
 *
 * **Never** the grant list + `DELETE /settings/oauth-grants/{id}`: that is
 * GRANT-WIDE since PR-B and would sign out every device running the app.
 *
 * Each call is bounded by [stepTimeoutMs], so a dead network costs a sign-out at
 * most two short waits, never the token client's full call timeout.
 */
internal suspend fun revokeInstallOnSignOut(
    held: AuthTokens?,
    revoke: suspend (token: String, tokenTypeHint: String) -> Response<Unit>,
    bearerLogout: suspend () -> Response<Unit>,
    stepTimeoutMs: Long = SIGN_OUT_STEP_TIMEOUT_MS,
): LogoutRevocation {
    if (held == null) return LogoutRevocation.NOTHING_HELD
    val primary = bounded(stepTimeoutMs) { revoke(held.refreshToken, "refresh_token") }
    if (primary != null) {
        if (primary.isSuccessful) return LogoutRevocation.REVOKED
        if (primary.code() == 400) return LogoutRevocation.REFUSED
    }
    val fallback = bounded(stepTimeoutMs) { bearerLogout() } ?: return LogoutRevocation.FAILED
    return when {
        fallback.isSuccessful -> LogoutRevocation.LOGGED_OUT
        fallback.code() == 401 -> LogoutRevocation.ALREADY_GONE
        else -> LogoutRevocation.FAILED
    }
}

/** [call] within [timeoutMs]; `null` for a timeout or a transport failure. Cancellation propagates. */
private suspend fun <T> bounded(timeoutMs: Long, call: suspend () -> Response<T>): Response<T>? =
    try {
        withTimeoutOrNull(timeoutMs) { call() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
