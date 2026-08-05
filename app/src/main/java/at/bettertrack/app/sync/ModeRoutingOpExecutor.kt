package at.bettertrack.app.sync

import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.storage.BackendTag

/**
 * Sends each queued op to the backend it was enqueued FOR (S3/S4 plan §1.2).
 *
 * The critical rule is that routing reads the op's OWN persisted
 * [SyncOp.backendTag], not whatever [at.bettertrack.app.data.storage.StorageMode]
 * happens to be active when the queue finally drains. A user who switches modes
 * with work still pending must see that work land where it was meant to land —
 * a server mutation stays a server mutation. Only *enqueue* consults the current
 * mode ([SyncEngine.enqueue]).
 *
 * Because this sits behind the existing [OpExecutor] interface, [SyncEngine] is
 * still constructed exactly once in the object graph and a mode switch needs no
 * process restart.
 *
 * W1 ships the router with only the server arm wired; [vault] defaults to
 * [UnavailableVaultOpExecutor] until W4 lands `VaultOpExecutor`. That arm is
 * unreachable today — nothing can stamp [BackendTag.VAULT] while the mode can
 * only resolve to SERVER — and it parks rather than silently dropping if it ever
 * is reached (e.g. a downgrade after a future Drive build wrote vault ops).
 */
class ModeRoutingOpExecutor(
    private val server: OpExecutor,
    private val vault: OpExecutor = UnavailableVaultOpExecutor,
) : OpExecutor {

    override suspend fun execute(op: SyncOp): ExecResult = when (op.backendTag) {
        BackendTag.SERVER -> server.execute(op)
        BackendTag.VAULT -> vault.execute(op)
    }
}

/**
 * Placeholder for the not-yet-built vault write path. Parks the op as
 * needs-attention with a human sentence (the same raw-`serverError` pattern the
 * pending-sync screens already render verbatim) instead of failing silently or
 * — far worse — sending a vault-tagged mutation to the server.
 */
object UnavailableVaultOpExecutor : OpExecutor {
    override suspend fun execute(op: SyncOp): ExecResult =
        ExecResult.Unsupported(BtErrorCopy.AppCodes.OP_NO_VAULT)

    const val MSG_NO_VAULT =
        "This change was saved for your Google Drive vault, which this version of the app can't open. " +
            "Update the app, then retry."
}
