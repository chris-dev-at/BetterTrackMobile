package at.bettertrack.app.data.auth

import android.net.Uri
import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.db.LocalAccountData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The single source of truth for authentication (spec §4). It:
 *  - resolves startup routing from the persisted session (survives process death),
 *  - runs the PKCE Authorization-Code round-trip (begin → callback → exchange),
 *  - fetches the current user and gates admin / disabled / must-change-password,
 *  - performs logout with best-effort server-side revocation + full local wipe,
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
    private val store: SecureStore,
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
     * Run after an explicit logout has completed (S3/S4 plan §4.4 row 2).
     *
     * Logging out of a BetterTrack account in BOTH mode must not leave the
     * install claiming a server medium it no longer has — the vault survived the
     * scoped wipe and is now the only place the data lives, so the mode demotes
     * to DRIVE. Kept as a hook rather than a direct storage-mode dependency so
     * this class stays about sessions.
     */
    private val onAfterLogout: () -> Unit = {},
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _loginPhase = MutableStateFlow<LoginPhase>(LoginPhase.Idle)
    val loginPhase: StateFlow<LoginPhase> = _loginPhase.asStateFlow()

    init {
        _authState.value = resolveInitialState()
        // Confirm local-data ownership for a restored session (no-op when same user).
        (authState.value as? AuthState.LoggedIn)?.let { s ->
            scope.launch { localAccountData.onSessionEstablished(s.user.id) }
        }
        // Any downstream refresh rejection wipes the session ⇒ drop to login.
        scope.launch {
            tokenManager.sessionInvalidated.collect { forceLoggedOut() }
        }
    }

    /** Startup routing (spec §4): a stored token = still logged in across restarts. */
    private fun resolveInitialState(): AuthState {
        if (!tokenManager.hasTokens()) return AuthState.LoggedOut
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
                        if (exchanged.error.isNetwork) LoginError.NETWORK else LoginError.EXCHANGE_FAILED,
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

    /** After a successful token exchange: fetch the user and gate the session. */
    private suspend fun completeLogin() {
        when (val me = apiCall(json) { btApi.me() }) {
            is BtResult.Ok -> {
                val user = me.value.toSessionUser()
                when {
                    user.role == "admin" -> wipeAndFail(LoginError.ADMIN_NOT_ALLOWED)
                    user.status == "disabled" -> wipeAndFail(LoginError.ACCOUNT_DISABLED)
                    else -> {
                        store.saveUser(user)
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
                        Log.i(TAG, "Logged in (role=${user.role}).")
                        onSessionAuthenticated()
                    }
                }
            }

            is BtResult.Err -> {
                if (me.error.isAuthHardFailure) {
                    // The freshly-minted token is already invalid — abort to login.
                    wipeAndFail(LoginError.GENERIC)
                } else {
                    // /auth/me unavailable (network hiccup — or the platform's
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
        scope.launch {
            when (val me = apiCall(json) { btApi.me() }) {
                is BtResult.Ok -> {
                    val user = me.value.toSessionUser()
                    when {
                        user.role == "admin" -> wipeAndFail(LoginError.ADMIN_NOT_ALLOWED)
                        user.status == "disabled" -> wipeAndFail(LoginError.ACCOUNT_DISABLED)
                        else -> {
                            store.saveUser(user)
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
                        }
                    }
                }

                is BtResult.Err -> Log.w(TAG, "refreshUser failed: ${me.error.message}")
            }
        }
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /** Fire-and-forget logout for UI callers. */
    fun requestLogout() {
        scope.launch { logout() }
    }

    /** Revoke server-side (best effort), then wipe ALL local state (spec §4). */
    suspend fun logout() {
        // Deregister the FCM device token FIRST, while the bearer is still valid
        // (bounded + fail-soft inside the hook — logout never blocks on it).
        onBeforeLogout()
        revokeGrantBestEffort()
        // Explicit logout wipes the account-keyed Room data too: caches AND the
        // outbound sync queue, plus any scheduled sync work (§7.3). The wipe is
        // storage-mode aware (S3/S4 plan §4.4): in SERVER mode — the only mode
        // reachable today — it is the same full wipe as always, but once a Drive
        // vault can exist, logging out of the BetterTrack account must not
        // destroy data the user still owns.
        localAccountData.wipeForLogout()
        store.wipeAll()
        _authState.value = AuthState.LoggedOut
        _loginPhase.value = LoginPhase.Idle
        // AFTER the wipe: the wipe scope itself is decided by the mode we are
        // leaving, so demoting first would run the full EVERYTHING wipe under a
        // mode that still holds a vault — and destroy it.
        onAfterLogout()
    }

    /**
     * Find our OAuth grant and revoke it, killing the access + refresh tokens
     * server-side. Session-cookie scoped in the OpenAPI, so an OAuth bearer may
     * be refused (scope/session) — we log the outcome and always fall through to
     * the local wipe. TODO(platform): a bearer-reachable self-revocation endpoint.
     */
    private suspend fun revokeGrantBestEffort() {
        try {
            when (val grants = apiCall(json) { btApi.oauthGrants() }) {
                is BtResult.Ok -> {
                    val grant = grants.value.grants.firstOrNull { it.clientId == clientId }
                    if (grant == null) {
                        Log.i(TAG, "No matching OAuth grant to revoke (client=$clientId).")
                        return
                    }
                    val resp = btApi.revokeOAuthGrant(grant.id)
                    Log.i(TAG, "Revoked grant ${grant.id}: HTTP ${resp.code()}")
                }

                is BtResult.Err ->
                    Log.w(TAG, "Grant list unavailable for revocation: ${grants.error.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Best-effort revocation failed: ${e.message}")
        }
    }

    private fun wipeAndFail(error: LoginError) {
        // A gated account (admin / disabled) must leave no local data behind.
        scope.launch { localAccountData.wipeAll() }
        store.wipeAll()
        _authState.value = AuthState.LoggedOut
        _loginPhase.value = LoginPhase.Failed(error)
    }

    private fun forceLoggedOut() {
        // Session expiry — NOT a logout: tokens go, but the Room caches and the
        // outbound queue survive so a re-login of the same user resumes the
        // drain with nothing lost (§7.3). The owner gate at next login wipes if
        // it turns out to be a DIFFERENT user.
        store.wipeAll()
        _authState.value = AuthState.LoggedOut
        _loginPhase.value = LoginPhase.Idle
    }

    // ── Web links (open in the browser, spec §4) ───────────────────────────────
    fun webHomeUrl(): String = webOrigin.trimEnd('/')
    fun needAccountUrl(): String = "${webOrigin.trimEnd('/')}/register"
    fun forgotPasswordUrl(): String = "${webOrigin.trimEnd('/')}/forgot-password"

    private companion object {
        const val TAG = "BtAuth"
    }
}
