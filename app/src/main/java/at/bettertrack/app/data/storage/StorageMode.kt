package at.bettertrack.app.data.storage

/**
 * Where this install's portfolio data lives (S3/S4 plan §1.4).
 *
 * W1 introduces the value and every rule that reads it; the first-run wizard
 * that lets a user CHOOSE it is W5. Until then the app is SERVER-shaped:
 *
 *  - [UNSET] is the value a genuinely clean install starts with. It exists so
 *    W5 can tell "never asked" apart from "chose the server", and **nothing
 *    else**: every behavioural rule in the app reads [effective], which maps
 *    UNSET → SERVER. So an un-migrated install behaves exactly as it does today.
 *  - Existing installs are grandfathered to [SERVER] exactly once at startup
 *    (plan §4.3, [resolveGrandfatheredMode]) so they never meet the W5 wizard.
 */
enum class StorageMode(val wire: String) {
    /** Never chosen. Behaves as [SERVER] everywhere except the stored value. */
    UNSET("unset"),

    /** Today's world: the BetterTrack server is the store and the calculator. */
    SERVER("server"),

    /** Drive-autonomous (W4/W5): the encrypted vault is the store, no account. */
    DRIVE("drive"),

    /** Server-authoritative with a client-encrypted Drive mirror (plan §1.5). */
    BOTH("both"),
    ;

    companion object {
        fun fromWire(wire: String?): StorageMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The mode every behavioural rule must branch on: [StorageMode.UNSET] resolves
 * to [StorageMode.SERVER]. Only the wizard (W5) and the persistence layer look
 * at the raw stored value.
 */
val StorageMode.effective: StorageMode
    get() = if (this == StorageMode.UNSET) StorageMode.SERVER else this

/** True when mutations must be sent to the BetterTrack API (plan §1.2 router). */
val StorageMode.writesToServer: Boolean
    get() = effective == StorageMode.SERVER || effective == StorageMode.BOTH

/**
 * True when there is no server account at all — the drain has no bearer to wait
 * for, so the sync engine's session gate must let it through (plan §1.2).
 */
val StorageMode.isDriveOnly: Boolean
    get() = effective == StorageMode.DRIVE

/** True when a Drive vault exists in this mode (drives the logout wipe scope). */
val StorageMode.holdsVault: Boolean
    get() = effective == StorageMode.DRIVE || effective == StorageMode.BOTH

/**
 * Which backend an already-enqueued op belongs to. Stamped on the op at enqueue
 * time and persisted (`sync_ops.backendTag`, DB v7), so a mode change never
 * re-routes work that was queued for the other backend (plan §1.2).
 */
enum class BackendTag(val wire: String) {
    SERVER("server"),
    VAULT("vault"),
    ;

    companion object {
        /** Unknown/legacy values read as [SERVER] — the pre-v7 world. */
        fun fromWire(wire: String?): BackendTag = entries.firstOrNull { it.wire == wire } ?: SERVER
    }
}

/** The backend a mutation enqueued RIGHT NOW belongs to. */
fun StorageMode.backendTag(): BackendTag =
    if (writesToServer) BackendTag.SERVER else BackendTag.VAULT

// ── Grandfathering (plan §4.3) ───────────────────────────────────────────────

/**
 * The one-shot startup rule that keeps existing installs out of the W5 wizard.
 *
 * An install that has ever held a session — a live token, a cached user, a Room
 * DB with an owner key, or the `everSignedIn` device flag — chose the server
 * long ago; it resolves to [StorageMode.SERVER] and the resolution is persisted,
 * so it happens exactly once. A genuinely clean install has none of those
 * signals and stays [StorageMode.UNSET] (which still BEHAVES as SERVER until W5
 * ships the wizard — see [effective]).
 *
 * Pure so [at.bettertrack.app.data.storage.StorageModeStore] stays a thin shell
 * and the rule is unit-tested without Android.
 *
 * @param stored the currently persisted mode
 * @param everSignedIn the persistent device flag set on every successful login
 * @param hasTokens a live OAuth token pair is present
 * @param hasCachedUser the secure store still holds a user record
 * @param hasDbOwner the Room `meta` table carries an owner key (any DB that ever
 *   held a session — survives logout per `AuthRepository.forceLoggedOut`)
 */
fun resolveGrandfatheredMode(
    stored: StorageMode,
    everSignedIn: Boolean,
    hasTokens: Boolean,
    hasCachedUser: Boolean,
    hasDbOwner: Boolean,
): StorageMode = when {
    stored != StorageMode.UNSET -> stored
    everSignedIn || hasTokens || hasCachedUser || hasDbOwner -> StorageMode.SERVER
    else -> StorageMode.UNSET
}

// ── Logout wipe scope (plan §4.4 row 2) ──────────────────────────────────────

/** How much local data an explicit logout destroys. */
enum class WipeScope {
    /** Today's behaviour: every table, queue included. */
    EVERYTHING,

    /**
     * Server caches + server-tagged queue only; the vault tables survive.
     * Unreachable until a Drive mode can be selected (W5) — W4 adds the vault
     * tables this scope is defined to spare.
     */
    SERVER_ONLY,
}

/**
 * Logout wipe rule (plan §4.4): logging out of the BetterTrack account must not
 * destroy a vault the user still owns. In any mode that holds a vault the wipe
 * is scoped; in SERVER — and in UNSET, which behaves as SERVER — it stays the
 * full [WipeScope.EVERYTHING] wipe the app has always done.
 */
fun logoutWipeScope(mode: StorageMode): WipeScope =
    if (mode.holdsVault) WipeScope.SERVER_ONLY else WipeScope.EVERYTHING
