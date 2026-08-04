package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Friend groups as sharing audiences (V5 social, `social:*`) — platform
 * `packages/contracts/src/social.ts` (`friendGroupSchema`), routes
 * `socialRoutes.ts`.
 *
 * Endpoints:
 *  - `GET    /social/groups`                       → [FriendGroupListResponse]
 *  - `POST   /social/groups`                       → 201 bare [FriendGroupDto]
 *  - `PATCH  /social/groups/{groupId}`             → 200 bare [FriendGroupDto]
 *  - `DELETE /social/groups/{groupId}`             → 204
 *  - `POST   /social/groups/{groupId}/members`     → 200 bare [FriendGroupDto]
 *  - `DELETE /social/groups/{groupId}/members/{userId}` → **200** bare [FriendGroupDto]
 *
 * Three shapes here are easy to get wrong and are pinned by tests:
 *  1. **Add-member puts the user id in the BODY**, not the path — the path form
 *     is the DELETE only.
 *  2. **Member removal answers 200 with the refreshed group**, not 204, so the
 *     roster repaints from the response instead of a refetch.
 *  3. There is **no single-group GET** — the list is the only read.
 *
 * A group has no colour and no icon: `{id, name, memberCount, members[]}`.
 * Names are trimmed server-side, 1..60 chars, and are **not** unique.
 */

@Serializable
data class FriendGroupDto(
    val id: String = "",
    val name: String = "",
    val memberCount: Int = 0,
    val members: List<SocialUserDto> = emptyList(),
)

@Serializable
data class FriendGroupListResponse(
    val groups: List<FriendGroupDto> = emptyList(),
)

/** `POST /social/groups` and `PATCH /social/groups/{id}` share this body. */
@Serializable
data class FriendGroupNameRequest(
    val name: String,
)

/** `POST /social/groups/{groupId}/members` — the member id rides the body. */
@Serializable
data class AddGroupMemberRequest(
    val userId: String,
)
