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
                    _loginPhase.value = LoginPhase.Failed(
                        if (exchanged.error.isNetwork) LoginError.NETWORK else LoginError.EXCHANGE_FAILED,
                        exchanged.error.userMessage,
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
                        // Detects an account switch and wipes local data if so (§7.3).
                        localAccountData.onSessionEstablished(user.id)
                        _authState.value = if (user.mustChangePassword) {
                            AuthState.PasswordChangeRequired(user)
                        } else {
                            AuthState.LoggedIn(user)
                        }
                        _loginPhase.value = LoginPhase.Idle
                        Log.i(TAG, "Logged in as @${user.username} (${user.email}), role=${user.role}")
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
        revokeGrantBestEffort()
        // Explicit logout wipes the account-keyed Room data too: caches AND the
        // outbound sync queue, plus any scheduled sync work (§7.3).
        localAccountData.wipeAll()
        store.wipeAll()
        _authState.value = AuthState.LoggedOut
        _loginPhase.value = LoginPhase.Idle
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
