package at.bettertrack.app.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The opt-in **"Use BetterTrack for prices only"** switch (S3/S4 plan §5 W6).
 *
 * ## What it offers
 *
 * A Drive-mode user keeps every portfolio number on their own device, but that
 * costs them live quotes. This toggle buys the quotes back at a stated, narrow
 * price: search and quote calls go to the BetterTrack server, while **portfolio
 * data keeps routing to the vault**. The seam that makes that honest already
 * exists — [ModeRoutingMarketDataSource] and
 * [ModeRoutingPortfolioBackend] are separate routers, so turning this on moves
 * the market seam and touches nothing on the write path. The exact promise made
 * to the user (plan §5 W6) is therefore literally true:
 *
 * > BetterTrack would see which assets you look up, never what you own.
 *
 * ## Why it needs an account — the W6 decision
 *
 * Server quotes are **not available unauthenticated**. `GET /search` and the
 * `GET /assets` routes sit behind the OAuth bearer with `market:read`
 * (`PLATFORM_ASKS.md`: allowed scopes include `market:read`; a garbage bearer on
 * `/search` returns `401 API_KEY_INVALID`, i.e. live bearer middleware). There is
 * no anonymous quote endpoint to point this at.
 *
 * A pure DRIVE install has no account and therefore no bearer, so the toggle
 * **cannot** be honoured there. The designed answer is not to hide the option —
 * a user who wants live prices deserves to know the shape of the trade — but to
 * show it in the honest disabled state ([PriceLookupAvailability.NEEDS_ACCOUNT])
 * with the reason and the route to fix it: attaching a BetterTrack account is
 * the existing DRIVE → BOTH transition (plan §1.4).
 *
 * Consequently the toggle only *acts* once a session exists. In practice that
 * means BOTH mode — where the server is already the market source anyway — so the
 * stored flag is best read as a standing consent that survives the account being
 * detached again, not as a live routing switch for pure DRIVE. It is stored, and
 * [priceLookupActive] is the one place the distinction is made.
 *
 * ## Storage
 *
 * Plain [SharedPreferences], for the same three reasons [StorageModeStore]
 * documents: it must survive logout (Room is wiped), it must be readable
 * synchronously while the object graph wires the market seam, and it carries no
 * secret — one boolean. **Default OFF**, per the plan; a privacy default that
 * needs no explanation is the only kind worth shipping.
 */
class PriceLookupStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    /** The RAW stored consent. Routing wants [priceLookupActive]. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Synchronous read — the market router resolves per call, off any flow. */
    fun enabledNow(): Boolean = _enabled.value

    fun set(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    private companion object {
        const val PREFS = "bt_price_lookup"
        const val KEY_ENABLED = "server_price_lookups"
    }
}

/** How the toggle may be presented in the current mode + session state. */
enum class PriceLookupAvailability {

    /**
     * SERVER (and UNSET, which behaves as SERVER): prices already come from
     * BetterTrack because everything does. Offering to "use BetterTrack for
     * prices" would be nonsense, so the row is absent — plan §4.5's
     * "absent, not greyed".
     */
    NOT_APPLICABLE,

    /** A session exists, so the switch is real and reversible. */
    AVAILABLE,

    /**
     * Drive-only with no BetterTrack account: shown, disabled, with the reason.
     * Greyed rather than absent **on purpose** — here the control is genuinely
     * applicable and one step (attach an account) away, which is information the
     * user needs. "Absent, not greyed" governs features this mode does not have;
     * this is a feature it could have.
     */
    NEEDS_ACCOUNT,
}

/**
 * Whether the toggle may be offered.
 *
 * Pure — [hasSession] is passed in rather than read from the token manager, so
 * the rule is a unit test rather than an integration one.
 */
fun priceLookupAvailability(mode: StorageMode, hasSession: Boolean): PriceLookupAvailability =
    when (mode.effective) {
        // Collapsed by `effective`; listed so the `when` stays exhaustive.
        StorageMode.UNSET, StorageMode.SERVER -> PriceLookupAvailability.NOT_APPLICABLE
        StorageMode.BOTH -> if (hasSession) {
            PriceLookupAvailability.AVAILABLE
        } else {
            PriceLookupAvailability.NEEDS_ACCOUNT
        }

        StorageMode.DRIVE -> if (hasSession) {
            PriceLookupAvailability.AVAILABLE
        } else {
            PriceLookupAvailability.NEEDS_ACCOUNT
        }
    }

/**
 * Whether server price lookups are actually in effect right now.
 *
 * Three conditions, all required: the user opted in, a bearer exists to make the
 * call with, and the mode is one where the market seam would otherwise be
 * offline. A stored `true` with no session is consent without capability — it
 * routes nowhere and must never make the app attempt a 401-generating call, which
 * is the exact failure the per-call router in [ModeRoutingMarketDataSource] was
 * introduced to stop.
 */
fun priceLookupActive(mode: StorageMode, hasSession: Boolean, enabled: Boolean): Boolean =
    enabled && hasSession && mode.isDriveOnly
