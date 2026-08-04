package at.bettertrack.app.vault.server

import at.bettertrack.app.vault.DataHomeTransportFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What `GET /vault/media` tells the app about **this account** — the pre-flight
 * that decides whether a server medium exists to connect to at all
 * (`vaultRoutes.ts:213-218`, response shape `paranoidMediaStateResponseSchema`,
 * `packages/contracts/src/vault.ts:321-335`).
 *
 * The contract's own invariant, enforced server-side by a `superRefine`: a
 * normal account has `mediaState === null`, a paranoid one never does. So the
 * two fields below are not redundant — [privacyMode] says what kind of account
 * this is, [hasMediaState] says whether it has vault media at all, and only
 * [mediaSetContainsServer] says whether **BetterTrack** is one of the places its
 * bytes live. A paranoid Drive-only user is exactly the case where the last two
 * disagree, and telling that user "something went wrong" instead of "your vault
 * lives only in your Drive" would be the kind of lie the plan forbids.
 */
data class ServerVaultMediaState(
    /** `"normal"` / `"paranoid"`; `null` when the server omitted it. */
    val privacyMode: String?,
    val hasMediaState: Boolean,
    val mediaSetContainsServer: Boolean,
) {
    val isParanoid: Boolean get() = privacyMode == PRIVACY_PARANOID

    /**
     * True when the app may expect `GET /vault` to hold bytes. A paranoid account
     * whose media set excludes `server` legitimately answers `404` forever, and
     * that is not an absence to repair.
     */
    val serverVaultExpected: Boolean get() = isParanoid && mediaSetContainsServer

    companion object {
        const val PRIVACY_NORMAL = "normal"
        const val PRIVACY_PARANOID = "paranoid"
    }
}

/** `VAULT_MEDIA` (`packages/contracts/src/vault.ts:101`) — the server's own name for itself. */
private const val MEDIUM_SERVER = "server"

/**
 * Whether the durable `mediaSet` names the server medium.
 *
 * Read positionally out of raw JSON rather than through a modelled type: the app
 * cannot *change* this state (`PATCH /vault/media` is session-only,
 * `vaultRoutes.ts:220`), so modelling every field would be weight that breaks the
 * day the platform adds one — while this reading keeps working.
 */
internal fun mediaSetContainsServer(mediaState: JsonElement?): Boolean {
    if (mediaState == null || mediaState is JsonNull) return false
    return try {
        val set = mediaState.jsonObject["mediaSet"] as? JsonArray ?: return false
        set.any { it.jsonPrimitive.content == MEDIUM_SERVER }
    } catch (_: Exception) {
        false
    }
}

/** Outcome of [ServerVaultDataHome.mediaState]. */
sealed interface ServerVaultMediaResult {
    data class Ok(val state: ServerVaultMediaState) : ServerVaultMediaResult

    data class Failure(val failure: DataHomeTransportFailure) : ServerVaultMediaResult
}

/**
 * One retained server-side version — `vaultHistoryMetadataSchema`
 * (`packages/contracts/src/vault.ts:470-478`). Metadata only; the ciphertext is
 * fetched separately and is the only thing that ever holds money.
 */
data class ServerVaultHistoryEntry(
    val version: Int,
    val createdAt: String?,
    val sizeBytes: Long?,
)

/** Outcome of [ServerVaultDataHome.history] — the restore picker's data layer. */
sealed interface ServerVaultHistoryResult {
    data class Ok(
        val items: List<ServerVaultHistoryEntry>,
        val nextCursor: Int?,
    ) : ServerVaultHistoryResult

    /**
     * `403 VAULT_PARANOID_MODE_REQUIRED`. A designed explainer, not an error:
     * only a paranoid account retains vault versions, so a normal one has an
     * empty history *by definition* rather than by failure.
     */
    data object ModeRequired : ServerVaultHistoryResult

    data class Failure(val failure: DataHomeTransportFailure) : ServerVaultHistoryResult
}
