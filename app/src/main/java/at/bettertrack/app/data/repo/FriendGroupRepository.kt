package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AddGroupMemberRequest
import at.bettertrack.app.data.api.dto.FriendGroupDto
import at.bettertrack.app.data.api.dto.FriendGroupNameRequest
import at.bettertrack.app.data.api.unitApiCall
import kotlinx.serialization.json.Json

/**
 * Friend groups (V5, `social:*`) — named sets of accepted friends that act as a
 * single sharing audience.
 *
 * A group is a *live* reference, not a snapshot: an item shared to "Family"
 * becomes visible to whoever is in Family at read time, and deleting the group
 * fails closed (the share resolves to nobody). That is worth knowing at the call
 * site, because it means adding a member is retroactive sharing — the UI says so
 * rather than letting it be a surprise.
 */

/** Server bounds: trimmed, 1..60. Names are NOT unique. */
const val FRIEND_GROUP_NAME_MAX = 60

data class FriendGroupMember(
    val userId: String,
    val username: String,
    val profileIcon: String?,
)

data class FriendGroup(
    val id: String,
    val name: String,
    val memberCount: Int,
    val members: List<FriendGroupMember>,
)

class FriendGroupRepository(
    private val api: BtApi,
    private val json: Json,
) {

    /** All my groups, each with its full roster (there is no single-group read). */
    suspend fun groups(): BtResult<List<FriendGroup>> =
        when (val r = apiCall(json) { api.friendGroups() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.groups.map { it.toDomain() })
            is BtResult.Err -> r
        }

    suspend fun create(name: String): BtResult<FriendGroup> =
        mapGroup(apiCall(json) { api.createFriendGroup(FriendGroupNameRequest(name.trim())) })

    suspend fun rename(groupId: String, name: String): BtResult<FriendGroup> =
        mapGroup(apiCall(json) { api.renameFriendGroup(groupId, FriendGroupNameRequest(name.trim())) })

    /** 204 — everything shared to this group stops being shared. */
    suspend fun delete(groupId: String): BtResult<Unit> =
        unitApiCall(json) { api.deleteFriendGroup(groupId) }

    /**
     * Add an accepted friend (idempotent). Both member mutations answer with the
     * whole refreshed group, so the caller repaints from the response and never
     * needs a follow-up list call.
     */
    suspend fun addMember(groupId: String, userId: String): BtResult<FriendGroup> =
        mapGroup(apiCall(json) { api.addFriendGroupMember(groupId, AddGroupMemberRequest(userId)) })

    /** Remove a member — 200 with the refreshed group (deliberately not 204). */
    suspend fun removeMember(groupId: String, userId: String): BtResult<FriendGroup> =
        mapGroup(apiCall(json) { api.removeFriendGroupMember(groupId, userId) })

    private fun mapGroup(r: BtResult<FriendGroupDto>): BtResult<FriendGroup> = when (r) {
        is BtResult.Ok -> BtResult.Ok(r.value.toDomain())
        is BtResult.Err -> r
    }

    private fun FriendGroupDto.toDomain() = FriendGroup(
        id = id,
        name = name,
        // The roster is authoritative when present; memberCount is the server's
        // own tally and the two agree — prefer the count field so an empty
        // roster from a future trimmed response can't silently read as zero.
        memberCount = memberCount,
        members = members.map { FriendGroupMember(it.id, it.username, it.profileIcon) },
    )

    companion object {
        /** 400 — the platform refuses anyone who is not an accepted friend. */
        const val CODE_NOT_FRIEND = "GROUP_MEMBER_NOT_FRIEND"

        /** 404 — unknown or someone else's group. */
        const val CODE_GROUP_NOT_FOUND = "FRIEND_GROUP_NOT_FOUND"

        /** 400 — a `group` audience was set without (or with a foreign) group id. */
        const val CODE_GROUP_AUDIENCE_INVALID = "GROUP_AUDIENCE_INVALID"
    }
}
