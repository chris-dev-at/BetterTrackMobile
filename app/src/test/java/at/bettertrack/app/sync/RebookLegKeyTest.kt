package at.bettertrack.app.sync

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The re-book's per-leg idempotency keys.
 *
 * A re-book is two server mutations behind ONE queue op, and the queue has one
 * `clientId` per op. That single key cannot be sent twice: the server stores
 * key → response, so replaying the CREATE under the DELETE's key would hand back
 * the delete's stored 2xx and the user's transaction would never come back —
 * data loss that appears only on a retry, i.e. only when nobody is watching.
 *
 * So each leg derives its own key. The three properties below are the whole
 * contract, and every one of them is load-bearing:
 *
 *  1. **Distinct** — or the two legs collide, as above.
 *  2. **Deterministic** — a crash-resumed op must re-derive the SAME keys, or
 *     every retry becomes a fresh mutation and the user ends up with two of
 *     their trade.
 *  3. **Valid UUIDs** — the server rejects any other shape, and the queue's
 *     `InvalidKey` path would then regenerate the op's key in a loop.
 */
class RebookLegKeyTest {

    private val clientId = "6f1a3c4e-2b7d-4f8a-9c1e-0d5b7a2e9f33"

    @Test
    fun `the two legs get different keys`() {
        assertNotEquals(
            rebookLegKey(clientId, REBOOK_LEG_DELETE),
            rebookLegKey(clientId, REBOOK_LEG_CREATE),
        )
    }

    @Test
    fun `a key is stable across calls - a resumed op replays, never re-mutates`() {
        assertEquals(
            rebookLegKey(clientId, REBOOK_LEG_DELETE),
            rebookLegKey(clientId, REBOOK_LEG_DELETE),
        )
        assertEquals(
            rebookLegKey(clientId, REBOOK_LEG_CREATE),
            rebookLegKey(clientId, REBOOK_LEG_CREATE),
        )
    }

    @Test
    fun `both keys parse as UUIDs - the server accepts nothing else`() {
        listOf(REBOOK_LEG_DELETE, REBOOK_LEG_CREATE).forEach { leg ->
            val key = rebookLegKey(clientId, leg)
            // Round-trips: `fromString` would throw on anything malformed, and
            // comparing the re-rendered form also catches a non-canonical shape.
            assertEquals(key, UUID.fromString(key).toString())
        }
    }

    @Test
    fun `different ops never share a leg key`() {
        val other = "11111111-2222-3333-4444-555555555555"
        assertNotEquals(
            rebookLegKey(clientId, REBOOK_LEG_CREATE),
            rebookLegKey(other, REBOOK_LEG_CREATE),
        )
    }

    @Test
    fun `a leg key is not the op's own key`() {
        // Sending the op's own clientId for a leg would put the leg into the
        // same idempotency slot the queue uses for the op as a whole.
        assertNotEquals(clientId, rebookLegKey(clientId, REBOOK_LEG_DELETE))
        assertNotEquals(clientId, rebookLegKey(clientId, REBOOK_LEG_CREATE))
    }
}
