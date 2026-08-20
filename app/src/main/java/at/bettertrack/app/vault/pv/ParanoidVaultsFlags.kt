package at.bettertrack.app.vault.pv

/**
 * The compile-time gate for the **paranoid vaults** program
 * (`paranoid-design.md`, owner ruling 2026-08-19).
 *
 * The program is being built epic by epic against a spec that is already
 * binding but whose surfaces do not yet exist end to end: a vault is only
 * usable once creation, custody, transfer, storage media and move-in all land.
 * Shipping half of that reachable would let a user create a vault nothing can
 * open — which, in a design where a lost phrase is unrecoverable by
 * construction (§16), is not a rough edge but data loss.
 *
 * So every paranoid-vaults surface hangs off this one boolean. While it is
 * `false` the app must be **behaviourally identical** to a build without the
 * code: no navigation entry, no settings row, no background work, no migration.
 * The classes still compile and are still unit-tested — that is the point of a
 * flag rather than an unmerged branch.
 *
 * A `val`, not a `const val`, deliberately: a compile-time constant makes every
 * gated block dead code the compiler warns about, and the warnings would push
 * the next author to delete the guard rather than keep it.
 */
object ParanoidVaultsFlags {

    /** Whether any paranoid-vaults surface is reachable. Flipped once the arc is whole. */
    val enabled: Boolean = false
}
