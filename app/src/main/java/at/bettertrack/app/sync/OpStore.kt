package at.bettertrack.app.sync

import at.bettertrack.app.data.db.SyncOpDao
import at.bettertrack.app.data.db.SyncOpEntity

/**
 * Persistence port of the sync engine — pure interface so the queue state
 * machine unit-tests against an in-memory fake; [RoomOpStore] is the app's
 * durable implementation on top of [SyncOpDao].
 */
interface OpStore {
    /** Append a new pending op; returns it with its assigned FIFO id. */
    suspend fun append(
        clientId: String,
        type: OpType,
        portfolioId: String?,
        payloadJson: String,
        accountKey: String,
        nowMs: Long,
    ): SyncOp

    /** Head of the FIFO queue: the oldest pending or in-flight op. */
    suspend fun firstOpen(): SyncOp?

    suspend fun getById(id: Long): SyncOp?

    suspend fun markInFlight(id: Long, nowMs: Long)

    /** Keep in-flight (ambiguous outcome) but record the attempt + backoff gate. */
    suspend fun markInFlightAttempt(id: Long, attemptCount: Int, nextAttemptAtMs: Long, nowMs: Long)

    suspend fun markDone(id: Long, serverResultJson: String?, nowMs: Long)

    suspend fun markNeedsAttention(id: Long, error: String, nowMs: Long)

    suspend fun markPending(id: Long, attemptCount: Int, nextAttemptAtMs: Long, nowMs: Long)

    /**
     * Replace the op's payload (queue edit, §7.2) and reset it to a fresh
     * pending state (attempts 0, no gate, error cleared). Client id unchanged.
     */
    suspend fun markEdited(id: Long, payloadJson: String, nowMs: Long)

    /** Manual drain: make every open op immediately eligible. */
    suspend fun resetBackoffGates()

    /**
     * Mint a fresh idempotency key (client UUID) for an op whose previous key the
     * server rejected as invalid (platform #432). Status/payload/attempts are
     * untouched — only the key (and marker identity) changes. Returns the updated
     * op, or null if it no longer exists.
     */
    suspend fun regenerateClientId(id: Long, nowMs: Long): SyncOp?

    suspend fun delete(id: Long)

    /** Prune old done rows, keeping the newest [keep]. */
    suspend fun pruneDone(keep: Int)

    suspend fun countOpen(): Int
}

/** Room-backed durable op store. */
class RoomOpStore(private val dao: SyncOpDao) : OpStore {

    override suspend fun append(
        clientId: String,
        type: OpType,
        portfolioId: String?,
        payloadJson: String,
        accountKey: String,
        nowMs: Long,
    ): SyncOp {
        val entity = SyncOpEntity(
            clientId = clientId,
            opType = type.wire,
            portfolioId = portfolioId,
            payloadJson = payloadJson,
            status = OpStatus.PENDING.wire,
            attemptCount = 0,
            nextAttemptAtMs = 0L,
            serverError = null,
            serverResultJson = null,
            accountKey = accountKey,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        val id = dao.insert(entity)
        return entity.copy(id = id).toModel()
    }

    override suspend fun firstOpen(): SyncOp? = dao.firstOpen()?.toModel()

    override suspend fun getById(id: Long): SyncOp? = dao.getById(id)?.toModel()

    override suspend fun markInFlight(id: Long, nowMs: Long) {
        val op = dao.getById(id) ?: return
        dao.updateState(
            id = id,
            status = OpStatus.IN_FLIGHT.wire,
            attemptCount = op.attemptCount,
            nextAttemptAtMs = op.nextAttemptAtMs,
            serverError = null,
            serverResultJson = null,
            updatedAtMs = nowMs,
        )
    }

    override suspend fun markInFlightAttempt(
        id: Long,
        attemptCount: Int,
        nextAttemptAtMs: Long,
        nowMs: Long,
    ) {
        dao.updateState(
            id = id,
            status = OpStatus.IN_FLIGHT.wire,
            attemptCount = attemptCount,
            nextAttemptAtMs = nextAttemptAtMs,
            serverError = null,
            serverResultJson = null,
            updatedAtMs = nowMs,
        )
    }

    override suspend fun markDone(id: Long, serverResultJson: String?, nowMs: Long) {
        val op = dao.getById(id) ?: return
        dao.updateState(
            id = id,
            status = OpStatus.DONE.wire,
            attemptCount = op.attemptCount,
            nextAttemptAtMs = 0L,
            serverError = null,
            serverResultJson = serverResultJson,
            updatedAtMs = nowMs,
        )
    }

    override suspend fun markNeedsAttention(id: Long, error: String, nowMs: Long) {
        val op = dao.getById(id) ?: return
        dao.updateState(
            id = id,
            status = OpStatus.NEEDS_ATTENTION.wire,
            attemptCount = op.attemptCount,
            nextAttemptAtMs = 0L,
            serverError = error,
            serverResultJson = null,
            updatedAtMs = nowMs,
        )
    }

    override suspend fun markPending(
        id: Long,
        attemptCount: Int,
        nextAttemptAtMs: Long,
        nowMs: Long,
    ) {
        dao.updateState(
            id = id,
            status = OpStatus.PENDING.wire,
            attemptCount = attemptCount,
            nextAttemptAtMs = nextAttemptAtMs,
            serverError = null,
            serverResultJson = null,
            updatedAtMs = nowMs,
        )
    }

    override suspend fun markEdited(id: Long, payloadJson: String, nowMs: Long) =
        dao.updatePayload(
            id = id,
            payloadJson = payloadJson,
            status = OpStatus.PENDING.wire,
            updatedAtMs = nowMs,
        )

    override suspend fun resetBackoffGates() = dao.resetBackoffGates()

    override suspend fun regenerateClientId(id: Long, nowMs: Long): SyncOp? {
        dao.updateClientId(id, java.util.UUID.randomUUID().toString(), nowMs)
        return dao.getById(id)?.toModel()
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun pruneDone(keep: Int) = dao.pruneDone(keep)

    override suspend fun countOpen(): Int = dao.countOpen()
}

/** Entity ↔ pure-model mapping (unknown wire values degrade defensively). */
fun SyncOpEntity.toModel(): SyncOp = SyncOp(
    id = id,
    clientId = clientId,
    type = OpType.fromWire(opType) ?: OpType.TX_BUY,
    portfolioId = portfolioId,
    payloadJson = payloadJson,
    status = OpStatus.fromWire(status) ?: OpStatus.NEEDS_ATTENTION,
    attemptCount = attemptCount,
    nextAttemptAtMs = nextAttemptAtMs,
    serverError = serverError,
    serverResultJson = serverResultJson,
    accountKey = accountKey,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
)
