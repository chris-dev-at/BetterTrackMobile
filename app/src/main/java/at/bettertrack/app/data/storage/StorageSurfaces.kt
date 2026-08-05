package at.bettertrack.app.data.storage

/**
 * Which surfaces a [StorageMode] can honestly render (S3/S4 plan §4.5).
 *
 * ## "Absent, not greyed"
 *
 * The plan's rule is deliberate and it is a design rule, not a technical one: a
 * Drive-only install has **no BetterTrack account**, so there is no friend list,
 * no alert engine, no push topic and no server to share anything with. Showing
 * those entries disabled would be a promise the mode can never keep — the user
 * would spend the life of the install tapping a greyed row hoping for an upsell
 * that never comes. So they are simply not there, and the wizard says so **before**
 * the choice is made rather than after.
 *
 * The one place greying is right is [SurfaceAvailability.DEGRADED]: search and
 * watchlists genuinely exist in Drive mode, they are just poorer (no live quotes
 * until W6's manual prices / opt-in lookup, device-local watchlist membership per
 * board #40.3). A degraded surface must render its own honest state; it must not
 * render a €0 lie.
 *
 * ## Why a pure function and not a `when` at each call site
 *
 * There are a dozen render points (bottom bar, top bar actions, settings
 * sections, the wizard's own "what you give up" copy). If each one branched on
 * the mode independently they would drift, and the drift would be invisible until
 * a Drive user found a social row that led to a crash. One table, one test, every
 * caller reads it.
 */
enum class BtSurface {
    /**
     * The Home tab (R-arc R1).
     *
     * Home is not a feature — it is an INDEX over whatever this mode has, so it
     * can never be absent and it is `FULL` in every branch of the table below by
     * construction. It gets a surface anyway, rather than being special-cased
     * into [visibleTabSurfaces], for one reason: the shell's invariant is that
     * "the bars and the routes below cannot disagree about what this install can
     * do", and that invariant only holds while EVERY tab is gated through this
     * one table. A tab that bypassed it would be the first crack in it.
     */
    HOME,

    /** Portfolios, holdings, transactions, cash, custom assets. */
    PORTFOLIO,

    /** History graph + performance series. */
    HISTORY,

    /** Search, asset pages, live quotes. */
    MARKET,

    /** Watchlists (device-local in Drive mode — board #40.3). */
    WATCHLISTS,

    /** Conglomerates, backtest, allocate. */
    CONGLOMERATES,

    /** Social, sharing, chat, friend groups. */
    SOCIAL,

    /** Alerts, the notification inbox, push. */
    ALERTS_NOTIFICATIONS,

    /** AT/DE/FI tax modes (Drive mode ships `taxMode = none` — plan §3.2). */
    TAX_MODES,

    /** The local PIN/biometric app lock. Every mode has it. */
    APP_LOCK,

    /** The BetterTrack account rows: username, email, password, 2FA, sessions, delete. */
    ACCOUNT_SETTINGS,

    /** The vault section: passphrase, recovery kit, lock, Drive status. */
    VAULT_SETTINGS,

    /** The outbound server queue's "Pending sync" screen. */
    PENDING_SYNC,
}

enum class SurfaceAvailability {
    /** Renders exactly as it does today. */
    FULL,

    /** Present but honestly poorer — must show its own reduced state. */
    DEGRADED,

    /** Not rendered at all. Never a greyed row. */
    ABSENT,
    ;

    val isVisible: Boolean get() = this != ABSENT
}

/**
 * The §4.5 table, verbatim.
 *
 * [StorageMode.UNSET] resolves through [effective] to SERVER: an install that has
 * not answered the wizard yet still behaves exactly as the app always has.
 */
fun surfaceAvailability(mode: StorageMode, surface: BtSurface): SurfaceAvailability =
    when (mode.effective) {
        StorageMode.DRIVE -> when (surface) {
            // An index over a smaller set of things is still an index. Drive-only
            // is in fact the mode that gains the most from having a front door:
            // it is the one whose bar has the fewest tabs.
            BtSurface.HOME -> SurfaceAvailability.FULL
            BtSurface.PORTFOLIO -> SurfaceAvailability.FULL
            BtSurface.HISTORY -> SurfaceAvailability.FULL
            // Quotes need a price source this mode does not have yet (W6).
            BtSurface.MARKET -> SurfaceAvailability.DEGRADED
            // No `watchlist` kind in VAULT_ENTITY_KINDS — device-local, labelled.
            BtSurface.WATCHLISTS -> SurfaceAvailability.DEGRADED
            BtSurface.CONGLOMERATES -> SurfaceAvailability.ABSENT
            BtSurface.SOCIAL -> SurfaceAvailability.ABSENT
            BtSurface.ALERTS_NOTIFICATIONS -> SurfaceAvailability.ABSENT
            BtSurface.TAX_MODES -> SurfaceAvailability.ABSENT
            BtSurface.APP_LOCK -> SurfaceAvailability.FULL
            BtSurface.ACCOUNT_SETTINGS -> SurfaceAvailability.ABSENT
            BtSurface.VAULT_SETTINGS -> SurfaceAvailability.FULL
            // The queue is a local-apply journal here (plan §1.2) — the screen
            // still reads it, and a domain refusal still needs somewhere to land.
            BtSurface.PENDING_SYNC -> SurfaceAvailability.FULL
        }

        // SERVER and BOTH render identically: BOTH is server-authoritative with an
        // encrypted mirror (plan §1.5), so nothing about the UI is reduced — it
        // only gains the vault section.
        StorageMode.BOTH -> when (surface) {
            BtSurface.VAULT_SETTINGS -> SurfaceAvailability.FULL
            else -> SurfaceAvailability.FULL
        }

        else -> when (surface) {
            // A server-only install has no vault, so there is nothing to show.
            BtSurface.VAULT_SETTINGS -> SurfaceAvailability.ABSENT
            else -> SurfaceAvailability.FULL
        }
    }

/** Convenience: is this surface rendered at all in [mode]? */
fun StorageMode.shows(surface: BtSurface): Boolean =
    surfaceAvailability(this, surface).isVisible

/**
 * The bottom-navigation tabs this mode may show, in bar order.
 *
 * Bar order is the R-arc mandate's verbatim order — Home · Portfolio ·
 * Workbench · Markets · People — mapped onto the surfaces that gate them.
 * [BtSurface.CONGLOMERATES] is the Workbench tab's surface: the constant keeps
 * its storage-plan name (§4.5) because renaming it would drift this table from
 * the document it mirrors for no user-visible gain; the shell's `TabSpec`
 * documents the mapping instead.
 *
 * Returned as [BtSurface] rather than the navigation `BtTab` so the rule stays in
 * the data layer and unit-tests without Compose or the nav graph; the shell maps
 * these onto its own tab specs.
 */
fun visibleTabSurfaces(mode: StorageMode): List<BtSurface> =
    listOf(
        BtSurface.HOME,
        BtSurface.PORTFOLIO,
        BtSurface.CONGLOMERATES,
        BtSurface.MARKET,
        BtSurface.SOCIAL,
    ).filter { mode.shows(it) }

// ── The root gate (plan §4.1) ───────────────────────────────────────────────

/** What `BtRoot` renders. */
enum class RootGate {
    /**
     * Nothing yet — the grandfathering pass has not finished.
     *
     * Not a loading spinner: it is the same neutral background `AuthState.Unknown`
     * uses, for the same reason. A visible spinner here would advertise a wait
     * that is normally a few milliseconds long.
     */
    WAITING,

    /** The first-run storage wizard. */
    WIZARD,

    /** Today's `AuthState` branch, unchanged. */
    AUTH,

    /** The vault unlock gate, then the app. */
    VAULT_UNLOCK,
}

/**
 * The §4.1 gate decision, as a pure function.
 *
 * ## The ordering this exists to make provable
 *
 * Until W5, `StorageMode.UNSET` was inert: it *behaved* as SERVER everywhere, so
 * nothing observed it and the grandfathering pass (plan §4.3) could resolve
 * lazily, at its leisure, after an IO probe of the Room owner key. W5 gives UNSET
 * a meaning — it selects the first-run wizard — and that turns the leisurely
 * resolve into a race: for the few milliseconds before the probe answers, an
 * install that has been in daily use for months still reads UNSET.
 *
 * Hence [resolved]. The gate refuses to decide anything until grandfathering has
 * run, so **no install that was going to be grandfathered can ever be shown the
 * wizard**, not even for one frame. That is the property this function is here to
 * let a test assert, rather than something a reviewer has to trace through a
 * coroutine launch and a `collectAsStateWithLifecycle`.
 *
 * @param gatedMode the stored mode AFTER the debug Drive-mode gate, with UNSET
 *   deliberately preserved — collapsing it to SERVER (which every behavioural
 *   rule does) is exactly the distinction the wizard needs.
 */
fun rootGate(resolved: Boolean, gatedMode: StorageMode): RootGate = when {
    !resolved -> RootGate.WAITING
    gatedMode == StorageMode.UNSET -> RootGate.WIZARD
    gatedMode == StorageMode.DRIVE -> RootGate.VAULT_UNLOCK
    else -> RootGate.AUTH
}
