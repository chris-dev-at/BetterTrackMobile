package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.VersionResponse
import kotlinx.serialization.json.Json

/**
 * The running build of the live server — `GET /api/v1/version` (public, no auth).
 * Purely cosmetic: fed into the About screen's "API build" row (and the hidden
 * dev screen). Fail-soft — callers hide the row when this returns an error.
 */
class BuildInfoRepository(
    private val api: BtApi,
    private val json: Json,
) {
    suspend fun apiBuild(): BtResult<VersionResponse> = apiCall(json) { api.version() }
}
