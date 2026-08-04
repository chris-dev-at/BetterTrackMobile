package at.bettertrack.app.sync

import at.bettertrack.app.data.storage.BackendTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Op routing across a storage-mode switch (S3/S4 plan §1.2).
 *
 * The rule that matters: an op goes to the backend it was ENQUEUED for, read
 * from its own persisted tag — never to whatever mode happens to be active when
 * the queue finally drains. Anything else silently sends a user's server
 * mutation into a local vault (or the reverse) after a switch.
 */
class ModeRoutingOpExecutorTest {

    private class RecordingExecutor(
        private val result: ExecResult = ExecResult.Success(null),
    ) : OpExecutor {
        val seen = mutableListOf<Long>()
        override suspend fun execute(op: SyncOp): ExecResult {
            seen += op.id
            return result
        }
    }

    private fun op(id: Long, tag: BackendTag) = SyncOp(
        id = id,
        clientId = "client-$id",
        type = OpType.TX_BUY,
        portfolioId = "p1",
        payloadJson = "{}",
        status = OpStatus.PENDING,
        attemptCount = 0,
        nextAttemptAtMs = 0L,
        serverError = null,
        serverResultJson = null,
        accountKey = "user-1",
        createdAtMs = 0L,
        updatedAtMs = 0L,
        backendTag = tag,
    )

    @Test
    fun `a server-tagged op goes to the server executor`() = runBlocking {
        val server = RecordingExecutor()
        val vault = RecordingExecutor()

        val result = ModeRoutingOpExecutor(server = server, vault = vault).execute(op(1, BackendTag.SERVER))

        assertEquals(listOf(1L), server.seen)
        assertTrue(vault.seen.isEmpty())
        assertTrue(result is ExecResult.Success)
    }

    @Test
    fun `a vault-tagged op goes to the vault executor`() = runBlocking {
        val server = RecordingExecutor()
        val vault = RecordingExecutor()

        ModeRoutingOpExecutor(server = server, vault = vault).execute(op(2, BackendTag.VAULT))

        assertEquals(listOf(2L), vault.seen)
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `a mixed queue is split by tag, not by the current mode`() = runBlocking {
        // The switch case: ops queued in server mode are still draining when the
        // user moves to Drive. The router is stateless about mode by design —
        // the tag on each row is the whole decision.
        val server = RecordingExecutor()
        val vault = RecordingExecutor()
        val router = ModeRoutingOpExecutor(server = server, vault = vault)

        for (o in listOf(op(1, BackendTag.SERVER), op(2, BackendTag.VAULT), op(3, BackendTag.SERVER))) {
            router.execute(o)
        }

        assertEquals(listOf(1L, 3L), server.seen)
        assertEquals(listOf(2L), vault.seen)
    }

    @Test
    fun `the executor's verdict is passed through untouched`() = runBlocking {
        // The router must not reclassify outcomes — the engine's state machine
        // depends on the exact ExecResult (Rejected parks, Ambiguous replays…).
        val rejected = ExecResult.Rejected("nope")
        val router = ModeRoutingOpExecutor(
            server = RecordingExecutor(result = rejected),
            vault = RecordingExecutor(),
        )
        assertEquals(rejected, router.execute(op(1, BackendTag.SERVER)))
    }

    @Test
    fun `without a vault backend a vault op parks instead of hitting the server`() = runBlocking {
        // W1 ships no VaultOpExecutor. The failure mode that must NOT happen is
        // a vault-tagged mutation quietly going to the API; parking it as
        // needs-attention surfaces it in the existing pending-sync UI.
        val server = RecordingExecutor()

        val result = ModeRoutingOpExecutor(server = server).execute(op(9, BackendTag.VAULT))

        assertTrue(server.seen.isEmpty())
        assertTrue(result is ExecResult.Unsupported)
        assertEquals(UnavailableVaultOpExecutor.MSG_NO_VAULT, (result as ExecResult.Unsupported).message)
    }

    @Test
    fun `default routing is unchanged for an op with no explicit tag`() = runBlocking {
        // Every pre-v7 row deserializes with the SERVER default.
        val server = RecordingExecutor()
        val untagged = op(1, BackendTag.SERVER).copy()
        ModeRoutingOpExecutor(server = server).execute(untagged)
        assertEquals(listOf(1L), server.seen)
    }
}
