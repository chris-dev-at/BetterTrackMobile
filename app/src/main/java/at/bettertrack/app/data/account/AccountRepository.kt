package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.api.transportErr
import at.bettertrack.app.data.api.dto.ExportDownloadRequest
import at.bettertrack.app.data.api.dto.ExportRequest
import at.bettertrack.app.data.api.dto.ExportRequestResponse
import at.bettertrack.app.data.api.dto.ExportStatusResponse
import at.bettertrack.app.data.api.dto.SetAccountPinRequest
import at.bettertrack.app.data.api.dto.SetPinIdleTimeoutRequest
import at.bettertrack.app.data.api.dto.AccountSettingsResponse
import at.bettertrack.app.data.api.dto.ChangePasswordRequest
import at.bettertrack.app.data.api.dto.DeleteAccountRequest
import at.bettertrack.app.data.api.dto.ProfileSettingsResponse
import at.bettertrack.app.data.api.dto.TwoFactorCodeRequest
import at.bettertrack.app.data.api.dto.TwoFactorDisableRequest
import at.bettertrack.app.data.api.dto.UpdateAccountSettingsRequest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    // ── Account preferences (the whole record) ──────────────────────────────
    //
    // [accountLocale] and [discreetMode] above read one field each and are kept
    // as they are — their callers genuinely want one field. These two exist for
    // the Settings screen, which renders several at once and would otherwise fire
    // the same GET three times.

    /** The account's stored preferences: visibility default, locale, currency, discreet. */
    suspend fun accountSettings(): BtResult<AccountSettingsResponse> =
        apiCall(json) { api.accountSettings() }

    /**
     * Patch one or more preferences and return the re-read record.
     *
     * Send ONLY the fields being changed. The schema is strict and additive, and
     * echoing back values we happen to have cached would clobber anything changed
     * on the web since our last read — `explicitNulls = false` drops the unset
     * ones, so a currency change cannot rewrite the language.
     */
    suspend fun updateAccountSettings(
        update: UpdateAccountSettingsRequest,
    ): BtResult<AccountSettingsResponse> = apiCall(json) { api.updateAccountSettings(update) }

    // ── Social profile ──────────────────────────────────────────────────────

    /** The caller's own profile — username, public flag, bio, icon. */
    suspend fun socialProfile(): BtResult<ProfileSettingsResponse> =
        apiCall(json) { api.socialProfile() }

    /**
     * Set or clear the profile icon.
     *
     * ## Why this builds its body by hand
     *
     * Two traps meet here, and a typed DTO walks into both.
     *
     * The route is a **PUT**: `isPublic` is required on every call, so an icon
     * change must echo back the profile's CURRENT public flag and bio or it will
     * quietly make a public profile private. [current] is that read — the caller
     * must have fetched the profile before calling this, which is why it is a
     * parameter rather than something fetched inside.
     *
     * And `profileIcon` distinguishes **omitted** ("leave it") from **null**
     * ("clear it"). The app's shared `Json` has `explicitNulls = false` and drops
     * null properties, so a DTO carrying `profileIcon = null` serializes to a body
     * with no `profileIcon` key at all — the server reads "leave it", answers 200,
     * and the icon is still there. Clearing would be a silent no-op that looks
     * like success.
     *
     * `buildJsonObject` sidesteps both: the key is written explicitly, as
     * `JsonNull` when clearing and a string otherwise.
     *
     * `acknowledgePublic` is deliberately never sent — it is only meaningful when
     * turning a profile public, which this call never does.
     */
    suspend fun updateProfileIcon(
        current: ProfileSettingsResponse,
        iconId: String?,
    ): BtResult<ProfileSettingsResponse> {
        val body = buildJsonObject {
            put("isPublic", JsonPrimitive(current.isPublic))
            // Echoed as an explicit null when the profile has no bio, for the same
            // reason as the icon: a dropped key on a PUT is not "unchanged", and
            // here it would clear a bio the user never touched.
            put("bio", current.bio?.let { JsonPrimitive(it) } ?: JsonNull)
            put("profileIcon", iconId?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        return apiCall(json) { api.updateSocialProfile(body) }
    }

    // ── Discreet mode (v5 — server stores the flag, the client does the hiding) ──

    /** Read the account's stored discreet-mode flag. */
    suspend fun discreetMode(): BtResult<Boolean> =
        when (val r = apiCall(json) { api.accountSettings() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.discreetMode)
            is BtResult.Err -> r
        }

    /**
     * Persist the flag. Sends ONLY `discreetMode` — the schema is strict and
     * echoing back `locale`/`baseCurrency` we happen to have cached would risk
     * clobbering a change made on the web since our last read.
     */
    suspend fun updateDiscreetMode(enabled: Boolean): BtResult<Unit> =
        when (
            val r = apiCall(json) {
                api.updateAccountSettings(UpdateAccountSettingsRequest(discreetMode = enabled))
            }
        ) {
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
                BtApiError(httpStatus = -2, code = "DELETE_DISABLED"),
            )
        }
        return emptyCall {
            api.deleteAccount(
                DeleteAccountRequest(confirmUsername = confirmUsername.trim(), password = password),
            )
        }
    }

    // ── Public profile: the visibility flag and the bio ──────────────────────

    /**
     * Turn the public profile on or off and/or rewrite the bio.
     *
     * Built by hand for the same two reasons as [updateProfileIcon], plus a
     * third that only applies here:
     *
     *  - the route is a PUT, so every field the caller is not changing must be
     *    echoed at its current value — hence [current];
     *  - `bio` distinguishes omitted from null, and `explicitNulls = false`
     *    would drop a null and turn "clear my bio" into a silent no-op;
     *  - **`acknowledgePublic` must be `true` on every call that leaves the
     *    profile public**, not merely on the off→on transition. The server
     *    re-checks it each time and answers `400 PUBLIC_PROFILE_ACK_REQUIRED`
     *    otherwise, so editing the bio of an already-public profile has to carry
     *    it too. The UI still only ASKS on the transition — that is the friction
     *    ladder's job — but the wire always states it.
     *
     * `profileIcon` is deliberately omitted so a bio edit cannot disturb it.
     */
    suspend fun updateProfileVisibility(
        current: ProfileSettingsResponse,
        isPublic: Boolean,
        bio: String?,
    ): BtResult<ProfileSettingsResponse> {
        val trimmed = bio?.trim()?.takeIf { it.isNotEmpty() }
        val body = buildJsonObject {
            put("isPublic", JsonPrimitive(isPublic))
            put("bio", trimmed?.let { JsonPrimitive(it) } ?: JsonNull)
            if (isPublic) put("acknowledgePublic", JsonPrimitive(true))
        }
        return apiCall(json) { api.updateSocialProfile(body) }
    }

    // ── Account PIN (NOT the device app lock) ────────────────────────────────

    /**
     * The account's PIN state, read from the authoritative `GET /auth/me`.
     *
     * `GET /auth/pin/status` answers the narrower question "is a PIN set" and is
     * already used by the app-lock option; it does not carry the idle timeout,
     * so this screen reads `me` instead of making two calls.
     */
    suspend fun accountPinState(): BtResult<AccountPinState> =
        when (val r = apiCall(json) { api.me() }) {
            is BtResult.Ok -> BtResult.Ok(
                AccountPinState(
                    pinSet = r.value.pinEnabled,
                    idleMinutes = r.value.pinLockIdleMinutes,
                ),
            )

            is BtResult.Err -> r
        }

    /**
     * Set or change the account PIN. Exactly four digits; the server rejects
     * anything else with `400 VALIDATION_ERROR`.
     *
     * The PIN is passed straight to the wire and never stored, logged or echoed
     * back into any state the app keeps.
     */
    suspend fun setAccountPin(pin: String): BtResult<AccountPinState> =
        when (val r = apiCall(json) { api.setAccountPin(SetAccountPinRequest(pin)) }) {
            is BtResult.Ok -> BtResult.Ok(
                AccountPinState(r.value.pinEnabled, r.value.pinLockIdleMinutes),
            )

            is BtResult.Err -> r
        }

    /** Turn the account PIN off. */
    suspend fun disableAccountPin(): BtResult<AccountPinState> =
        when (val r = apiCall(json) { api.disableAccountPin() }) {
            is BtResult.Ok -> BtResult.Ok(
                AccountPinState(r.value.pinEnabled, r.value.pinLockIdleMinutes),
            )

            is BtResult.Err -> r
        }

    /** The account-wide idle timeout before the PIN is asked again, in minutes. */
    suspend fun setPinIdleTimeout(minutes: Int): BtResult<AccountPinState> =
        when (val r = apiCall(json) { api.setPinIdleTimeout(SetPinIdleTimeoutRequest(minutes)) }) {
            is BtResult.Ok -> BtResult.Ok(
                AccountPinState(r.value.pinEnabled, r.value.pinLockIdleMinutes),
            )

            is BtResult.Err -> r
        }

    // ── Data export ──────────────────────────────────────────────────────────

    /**
     * Ask the server to build an export. Re-auth gated on [password].
     *
     * One per day per account; a second inside the window is
     * `429 EXPORT_RATE_LIMITED`. The returned token is the only copy that will
     * ever exist — the caller holds it in memory and nowhere else.
     */
    suspend fun requestExport(password: String): BtResult<ExportRequestResponse> =
        apiCall(json) { api.requestExport(ExportRequest(password = password)) }

    /** Poll the export job. An all-null response means none has been requested. */
    suspend fun exportStatus(): BtResult<ExportStatusResponse> =
        apiCall(json) { api.exportStatus() }

    /**
     * Download the ready export into [target].
     *
     * Streamed to disk rather than buffered: the zip is a file with a
     * destination, and holding an account's whole history in memory to write it
     * out again would be pointless at best. A failure deletes the partial file
     * so a half-written zip can never be shared as if it were complete.
     */
    suspend fun downloadExport(token: String, target: File): BtResult<File> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.downloadExport(ExportDownloadRequest(token))
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    return@withContext BtResult.Err(
                        parseApiError(json, response.code(), response.errorBody()),
                    )
                }
                target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                BtResult.Ok(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A partial zip is worse than none: it would share and open as a
                // corrupt archive that looks like a complete export.
                target.delete()
                transportErr(e)
            }
        }

    /** Map a call whose body is ignored: 2xx → Ok(Unit), else the parsed error. */
    private suspend fun <T> emptyCall(call: suspend () -> Response<T>): BtResult<Unit> =
        at.bettertrack.app.data.api.unitApiCall(json, call)
}

/**
 * The account PIN as the server reports it.
 *
 * [idleMinutes] is null when the account has never chosen one, which means the
 * server's own default applies — not "no timeout". Surfaces must say so rather
 * than render an empty value.
 */
data class AccountPinState(
    val pinSet: Boolean,
    val idleMinutes: Int?,
)
