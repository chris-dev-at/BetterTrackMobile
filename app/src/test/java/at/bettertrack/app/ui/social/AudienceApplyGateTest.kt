package at.bettertrack.app.ui.social

import at.bettertrack.app.data.repo.ShareAudience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The friction ladder's gate, pinned rung by rung against the WEB's own rule.
 *
 * ## What this file is a record of
 *
 * The parity audit named a contract `audienceTransitionRequiresConfirmation`.
 * That identifier exists in neither codebase — not here (working tree, full git
 * object database, every doc) and not in the platform source (whole dev stack,
 * `node_modules` included). The real rule is the web picker's `canSubmit`:
 *
 * ```ts
 * // apps/web/src/user/components/AudiencePicker.tsx:259-264
 * const canSubmit =
 *   snapshotReady &&
 *   !mutation.isPending &&
 *   !(audience === 'public_link' && !acknowledged) &&
 *   !(audience === 'group' && !groupId);
 * ```
 *
 * It is keyed on the **selected rung**, not on the transition. These tests assert
 * that reading in both directions: the public rung always asks, and no other rung
 * ever does — including widenings such as private → all friends.
 */
class AudienceApplyGateTest {

    private val everyRung = ShareAudience.entries.toList()

    // ── The blocking guarantee ───────────────────────────────────────────────

    @Test
    fun `the public rung always asks for the acknowledgment`() {
        assertTrue(audienceRequiresPublicAcknowledgment(ShareAudience.PublicLink))
        assertFalse(gate(selected = ShareAudience.PublicLink, acknowledged = false))
        assertTrue(gate(selected = ShareAudience.PublicLink, acknowledged = true))
    }

    /**
     * The divergence this test was written for. The app used to skip the tick
     * when the item was already public with a live link; the web clears
     * `acknowledged` on every snapshot load — including one whose loaded audience
     * already is `public_link` (`AudiencePicker.tsx:189`) — and then demands it
     * again. Re-saving a public share is still a save that keeps holdings world
     * readable.
     */
    @Test
    fun `an already-public item is asked again`() {
        assertFalse(
            "re-applying public must not bypass the tick",
            gate(selected = ShareAudience.PublicLink, acknowledged = false),
        )
        // The state that used to be exempt: already public, link live.
        assertFalse(
            gate(selected = ShareAudience.PublicLink, acknowledged = false),
        )
        assertFalse(
            audienceMintsPublicLink(
                current = ShareAudience.PublicLink,
                selected = ShareAudience.PublicLink,
                linkActive = true,
            ),
        )
        // …and "no new link is minted" must NOT mean "no tick owed".
        assertTrue(audienceRequiresPublicAcknowledgment(ShareAudience.PublicLink))
    }

    @Test
    fun `public link is the only rung that ever asks`() {
        everyRung.forEach { rung ->
            assertEquals(
                "acknowledgment owed for $rung",
                rung == ShareAudience.PublicLink,
                audienceRequiresPublicAcknowledgment(rung),
            )
        }
    }

    /**
     * The web gives `all_friends` an informational Alert and no gate
     * (`AudiencePicker.tsx:489-491`), which is §6.9's "light confirm". Spelled
     * out one widening at a time so a future change to any of them is loud.
     */
    @Test
    fun `widening inside the friends half of the ladder is not gated`() {
        listOf(
            ShareAudience.SpecificFriends,
            ShareAudience.Group,
            ShareAudience.AllFriends,
        ).forEach { rung ->
            assertFalse("$rung must not ask for an acknowledgment", audienceRequiresPublicAcknowledgment(rung))
        }
        assertTrue(gate(selected = ShareAudience.AllFriends))
    }

    @Test
    fun `narrowing is never gated`() {
        assertTrue(gate(selected = ShareAudience.Private))
    }

    // ── Wording, which is NOT the gate ───────────────────────────────────────

    @Test
    fun `minting a link is a different question from owing the tick`() {
        everyRung.filter { it != ShareAudience.PublicLink }.forEach { from ->
            assertTrue(
                "$from -> PublicLink mints a link",
                audienceMintsPublicLink(from, ShareAudience.PublicLink, linkActive = false),
            )
            // A live link under a NARROWER audience is not this item's link.
            assertTrue(
                "$from with a live link -> PublicLink still mints",
                audienceMintsPublicLink(from, ShareAudience.PublicLink, linkActive = true),
            )
        }
        // Already public with a live link: re-saving does not mint a second one.
        assertFalse(
            audienceMintsPublicLink(ShareAudience.PublicLink, ShareAudience.PublicLink, linkActive = true),
        )
        // Public audience whose link is dead: re-selecting it mints one.
        assertTrue(
            audienceMintsPublicLink(ShareAudience.PublicLink, ShareAudience.PublicLink, linkActive = false),
        )
    }

    @Test
    fun `no rung other than public ever mints a link`() {
        for (from in everyRung) {
            for (to in everyRung.filter { it != ShareAudience.PublicLink }) {
                for (linkActive in listOf(false, true)) {
                    assertFalse(
                        "$from -> $to (linkActive=$linkActive)",
                        audienceMintsPublicLink(from, to, linkActive),
                    )
                }
            }
        }
    }

    // ── The "this body would be a 400" gate, and the one deliberate extra ────

    @Test
    fun `group needs exactly one group chosen`() {
        // Web parity: `!(audience === 'group' && !groupId)`.
        assertFalse(gate(selected = ShareAudience.Group, hasGroup = false))
        assertTrue(gate(selected = ShareAudience.Group, hasGroup = true))
    }

    /**
     * The mirror of the web's own case (`AudiencePicker.test.tsx:253-267`,
     * *"specific-friends needs no acknowledgment"*): Save is enabled the moment
     * that rung is picked, with nobody selected.
     *
     * The app briefly gated this on a non-empty selection. That was the one
     * place the two clients disagreed, and the coordinator ruled it out on
     * 2026-08-08 under the owner's parity law — the web's test makes the absence
     * of a gate a contract, not an oversight. It is also harmless: "specific
     * friends, none named" is an audience nobody is in, i.e. exactly the
     * exposure of private.
     */
    @Test
    fun `specific friends applies with nobody selected — web parity`() {
        assertTrue(gate(selected = ShareAudience.SpecificFriends))
    }

    @Test
    fun `private and all friends need nothing`() {
        assertTrue(gate(selected = ShareAudience.Private))
        assertTrue(gate(selected = ShareAudience.AllFriends))
    }

    @Test
    fun `an empty friend set does not block any rung`() {
        assertTrue(gate(selected = ShareAudience.Private, hasGroup = false))
        assertTrue(gate(selected = ShareAudience.AllFriends, hasGroup = false))
        assertTrue(gate(selected = ShareAudience.SpecificFriends, hasGroup = false))
    }

    // ── busy ─────────────────────────────────────────────────────────────────

    @Test
    fun `an in-flight apply blocks every rung`() {
        everyRung.forEach { to ->
            assertFalse(
                "$to must be blocked while busy",
                gate(selected = to, acknowledged = true, hasGroup = true, busy = true),
            )
        }
    }

    // ── Group management: reachable BECAUSE you have groups, not despite ─────
    //
    // The 2026-08-09 reachability audit found the "Go to friend groups" button
    // rendered inside the `groups.isEmpty()` branch only, so the sheet's route to
    // group management disappeared the moment the user made their first group —
    // exactly inverted. The rule is now a named function so the condition cannot
    // drift back into whichever `if` branch an edit leaves it in.

    @Test
    fun `group management is offered whether or not groups exist`() {
        // The regression, stated directly: 0 and N must answer the same.
        assertTrue(audienceGroupManagementOffered(ShareAudience.Group, groupCount = 0))
        assertTrue(audienceGroupManagementOffered(ShareAudience.Group, groupCount = 1))
        assertTrue(audienceGroupManagementOffered(ShareAudience.Group, groupCount = 27))
    }

    @Test
    fun `group management belongs to the group rung and to no other`() {
        // It is a property of the rung, like every other rule in this file — the
        // sheet must not grow a groups button under "Private" or "Public link".
        everyRung.filter { it != ShareAudience.Group }.forEach { rung ->
            assertFalse(
                "$rung must not offer group management",
                audienceGroupManagementOffered(rung, groupCount = 3),
            )
        }
    }

    @Test
    fun `offering management never changes whether apply may fire`() {
        // The affordance is navigation, not friction: adding it must leave the
        // web-parity gate exactly where it was on the group rung.
        assertFalse(gate(selected = ShareAudience.Group, hasGroup = false))
        assertTrue(gate(selected = ShareAudience.Group, hasGroup = true))
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun gate(
        selected: ShareAudience,
        acknowledged: Boolean = false,
        hasGroup: Boolean = false,
        busy: Boolean = false,
    ): Boolean = audienceApplyAllowed(
        selected = selected,
        acknowledged = acknowledged,
        hasGroup = hasGroup,
        busy = busy,
    )
}
