package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.dto.MirrorRowInfoDto
import at.bettertrack.app.data.api.dto.PortfolioMirrorBadgeDto
import at.bettertrack.app.data.db.PortfolioMirror
import at.bettertrack.app.data.db.RowMirror

/**
 * Wire → cache mapping for the v5 mirrorchain overlays.
 *
 * Deliberately total and lenient: every input shape, including a well-formed
 * object with an empty username or a missing `sync` block, produces something
 * renderable. The overlays are decoration — a surprise on this seam must never
 * cost the user their ledger.
 */

/** Row provenance, or null when the row is not part of a chain. */
fun MirrorRowInfoDto?.toRowMirror(): RowMirror? {
    val dto = this ?: return null
    return RowMirror(
        mirrorId = dto.mirrorId,
        mirrorVersion = dto.version,
        // Blank attribution is treated as ABSENT so the UI shows no chip rather
        // than an empty "added by" with a dangling @.
        mirrorAddedByName = dto.addedBy?.username?.takeIf { it.isNotBlank() },
        mirrorAddedByIcon = dto.addedBy?.profileIcon?.takeIf { it.isNotBlank() },
    )
}

/** Chain badge, or null when the portfolio is not a chain copy. */
fun PortfolioMirrorBadgeDto?.toPortfolioMirror(): PortfolioMirror? {
    val dto = this ?: return null
    return PortfolioMirror(
        mirrorChainId = dto.chainId,
        mirrorChainName = dto.chainName.takeIf { it.isNotBlank() },
        mirrorRole = dto.role.takeIf { it.isNotBlank() },
        mirrorMemberCount = dto.memberCount,
        // An absent `sync` block reads as fully synced: the server only omits it
        // in shapes that have nothing to catch up on.
        mirrorSyncPercent = dto.sync?.percent ?: 100,
        mirrorSynced = dto.sync?.synced ?: true,
    )
}
