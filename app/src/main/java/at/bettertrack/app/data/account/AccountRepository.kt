package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.api.dto.ChangePasswordRequest
import at.bettertrack.app.data.api.dto.DeleteAccountRequest
import at.bettertrack.app.data.api.dto.TwoFactorCodeRequest
import at.bettertrack.app.data.api.dto.TwoFactorDisableRequest
import at.bettertrack.app.data.api.dto.UpdateAccountSettingsRequest
import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * The Settings → Account & Security network seam (spec §6.12). Every call rides
 * the authenticated [BtApi] (OAuth bearer + `account:security` scope). Bodies map
 * to the shared [BtResult]/[BtApiError] currency so screens surface the server's
 * own message inline (e.g. "Current password is incorrect.").
 *
 * SAFETY: [deleteAccount] is double-gated by [DeleteAccountFeature.armed] — with
 * the flag OFF it never touches the network, so the destructive call is impossible
 * to fire against the live production account.
 */
class AccountRepository(
    private val api: BtApi,
    private val json: Json,
) {

    // ── Change password ──────────────────────────────────────────────────────
    /** Voluntary change. A wrong [current] surfaces the server 401 inline. */
    suspend fun changePassword(current: String, new: String): BtResult<Unit> =
        emptyCall { api.changePassword(ChangePasswordRequest(currentPassword = current, newPassword = new)) }

    // ── Two-factor authentication ────────────────────────────────────────────
    suspend fun twoFactorStatus(): BtResult<TwoFactorState> =
        when (val r = apiCall(json) { api.twoFactorStatus() }) {
            is BtResult.Ok -> BtResult.Ok(
                TwoFactorState(
                    totpEnabled = r.value.totpEnabled,
                    totpPending = r.value.totpPending,
                    emailEnabled = r.value.emailEnabled,
                    recoveryCodesRemaining = r.value.recoveryCodesRemaining,
                ),
            )
            is BtResult.Err -> r
        }

    suspend fun twoFactorEnroll(): BtResult<TwoFactorEnrollment> =
        when (val r = apiCall(json) { api.twoFactorEnroll() }) {
            is BtResult.Ok -> BtResult.Ok(TwoFactorEnrollment(r.value.otpauthUri, r.value.secret))
            is BtResult.Err -> r
        }

    /** Confirm TOTP; returns the one-time recovery codes when this is the first method (else null). */
    suspend fun twoFactorConfirm(code: String): BtResult<List<String>?> =
        when (val r = apiCall(json) { api.twoFactorConfirm(TwoFactorCodeRequest(code.trim())) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.recoveryCodes)
            is BtResult.Err -> r
        }

    suspend fun twoFactorDisable(code: String): BtResult<Unit> =
        emptyCall { api.twoFactorDisable(TwoFactorDisableRequest(code.trim())) }

    suspend fun twoFactorEmailEnroll(): BtResult<Unit> =
        emptyCall { api.twoFactorEmailEnroll() }

    suspend fun twoFactorEmailConfirm(code: String): BtResult<List<String>?> =
        when (val r = apiCall(json) { api.twoFactorEmailConfirm(TwoFactorCodeRequest(code.trim())) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.recoveryCodes)
            is BtResult.Err -> r
        }

    suspend fun twoFactorEmailDisable(): BtResult<Unit> =
        emptyCall { api.twoFactorEmailDisable() }

    suspend fun regenerateRecoveryCodes(): BtResult<List<String>> =
        when (val r = apiCall(json) { api.twoFactorRegenerateRecoveryCodes() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.recoveryCodes)
            is BtResult.Err -> r
        }

    // ── Active sessions ──────────────────────────────────────────────────────
    suspend fun sessions(): BtResult<List<AccountSession>> =
        when (val r = apiCall(json) { api.sessions() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.sessions.map { SessionMapper.from(it) })
            is BtResult.Err -> r
        }

    suspend fun revokeSession(id: String): BtResult<Unit> =
        emptyCall { api.revokeSession(id) }

    /** Revoke every OTHER session; returns how many were killed. */
    suspend fun revokeOtherSessions(): BtResult<Int> =
        when (val r = apiCall(json) { api.revokeOtherSessions() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.revoked)
            is BtResult.Err -> r
        }

    // ── Language (server-side locale mirror) ─────────────────────────────────
    /** The account's stored UI language tag ("en"/"de"), best-effort. */
    suspend fun accountLocale(): BtResult<String> =
        when (val r = apiCall(json) { api.accountSettings() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.locale)
            is BtResult.Err -> r
        }

    /** Mirror the in-app language choice to the account so the web matches. */
    suspend fun updateAccountLocale(tag: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.updateAccountSettings(UpdateAccountSettingsRequest(locale = tag)) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    // ── Delete account (irreversible — double-gated) ─────────────────────────
    /**
     * Hard-delete the account. Refused BEFORE any network call unless
     * [DeleteAccountFeature.armed] is true, so it cannot fire against the live
     * production account. When armed, re-auths with the typed username + password.
     */
    suspend fun deleteAccount(confirmUsername: String, password: String): BtResult<Unit> {
        if (!DeleteAccountFeature.armed) {
            return BtResult.Err(
                BtApiError(
                    httpStatus = -2,
                    code = "DELETE_DISABLED",
                    userMessage = "Account deletion is disabled in this build.",
                ),
            )
        }
        return emptyCall {
            api.deleteAccount(
                DeleteAccountRequest(confirmUsername = confirmUsername.trim(), password = password),
            )
        }
    }

    /** Map a call whose body is ignored: 2xx → Ok(Unit), else the parsed error. */
    private suspend inline fun <T> emptyCall(crossinline call: suspend () -> Response<T>): BtResult<Unit> =
        try {
            val resp = call()
            if (resp.isSuccessful) {
                BtResult.Ok(Unit)
            } else {
                BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
            }
        } catch (_: java.io.IOException) {
            BtResult.Err(
                BtApiError(0, BtApiError.Codes.NETWORK, "No connection. Check your network and try again."),
            )
        } catch (e: Exception) {
            BtResult.Err(BtApiError(-1, BtApiError.Codes.UNKNOWN, e.message ?: "Unexpected error."))
        }
}
