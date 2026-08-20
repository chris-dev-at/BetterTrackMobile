package at.bettertrack.app.data.api

import androidx.annotation.StringRes
import at.bettertrack.app.R

/**
 * The error-code -> string-resource catalog (S6 P0-4).
 *
 * The platform speaks one error currency: `{ error: { code, message } }`. The
 * `message` half is written for the web app, in English, and sometimes carries
 * server vocabulary ("mirror", "baseSeq", "derived row") or a raw JVM exception
 * string. Showing it to a phone user was the single largest source of English
 * text on German devices.
 *
 * So the app owns the copy. [CATALOG] maps every code the app can receive onto
 * an app-authored, translated sentence; the server's own words are demoted to
 * [BtApiError.diagnostic] and shown only as a dim secondary line, and only for
 * codes the catalog does not cover (so a new server code still tells the user
 * something concrete instead of vanishing).
 *
 * Codes prefixed `APP_` are app-local refusals that no server ever sends — the
 * offline queue's own park reasons and the transport fallbacks. They live in the
 * same namespace so there is exactly ONE resolution path for user-facing copy.
 *
 * Adding a code: add it here AND to both `strings.xml` files. `BtErrorCopyTest`
 * and `StringParityTest` fail if either half is missing.
 */
object BtErrorCopy {

    /**
     * App-local pseudo-codes. They are stored in the sync queue exactly like a
     * server code, which is what lets a parked row resolve its message at RENDER
     * time (translated to the device language of the moment) instead of at PARK
     * time (frozen into whatever language was active weeks ago).
     */
    object AppCodes {
        /** Non-2xx whose body was not a parseable error envelope. */
        const val HTTP_FAILED = "APP_HTTP_FAILED"
        /** 2xx with no body where the caller required one. */
        const val EMPTY_RESPONSE = "APP_EMPTY_RESPONSE"
        /** Any other exception; the JVM message rides along as the diagnostic. */
        const val UNEXPECTED = "APP_UNEXPECTED"
        /** Generic "the server said no" for a queued entry with no better code. */
        const val REJECTED = "APP_REJECTED"
        /** Queue op whose payload will not decode for the API executor. */
        const val OP_MALFORMED_SUBMIT = "APP_OP_MALFORMED_SUBMIT"
        /** Queue op whose payload will not decode for the vault executor. */
        const val OP_MALFORMED_VAULT = "APP_OP_MALFORMED_VAULT"
        /** In-flight streak outlived the server's dedupe TTL; replay is unsafe. */
        const val OP_REPLAY_WINDOW_EXPIRED = "APP_OP_REPLAY_WINDOW_EXPIRED"
        /** Attempt AND its automatic replay both blew the per-attempt cap. */
        const val OP_ATTEMPT_TIMED_OUT = "APP_OP_ATTEMPT_TIMED_OUT"
        /** Vault-bound op with no vault available to apply it to. */
        const val OP_NO_VAULT = "APP_OP_NO_VAULT"
        /** Cash-coupled op in a portfolio that has no cash source yet. */
        const val OP_NO_CASH_SOURCE = "APP_OP_NO_CASH_SOURCE"
        /** Amount rounds to zero once the engine has quantised it. */
        const val OP_ZERO_AMOUNT = "APP_OP_ZERO_AMOUNT"
        /** No FX rate cached on-device for the trade's currency. Takes the currency as its argument. */
        const val OP_NO_RATE = "APP_OP_NO_RATE"
        /** Server returned an alert kind this app version cannot render. */
        const val UNKNOWN_ALERT_KIND = "APP_UNKNOWN_ALERT_KIND"
    }

    /** code -> app-authored, translated sentence. */
    private val CATALOG: Map<String, Int> = mapOf(

        // ── Generic envelope + transport ──
        "UNKNOWN" to R.string.bt_err_unknown,
        "INTERNAL" to R.string.bt_err_internal,
        "NETWORK_ERROR" to R.string.bt_err_network_error,
        "VALIDATION_ERROR" to R.string.bt_err_validation_error,
        "BAD_REQUEST" to R.string.bt_err_bad_request,
        "CONFLICT" to R.string.bt_err_conflict,
        "UNPROCESSABLE" to R.string.bt_err_unprocessable,
        "UNAUTHENTICATED" to R.string.bt_err_unauthenticated,
        "FORBIDDEN" to R.string.bt_err_forbidden,
        "NOT_FOUND" to R.string.bt_err_not_found,
        "RATE_LIMITED" to R.string.bt_err_rate_limited,
        "UPSTREAM_UNAVAILABLE" to R.string.bt_err_upstream_unavailable,
        "PAYLOAD_TOO_LARGE" to R.string.bt_err_payload_too_large,
        "FEATURE_DISABLED" to R.string.bt_err_feature_disabled,
        "CSRF_HEADER_REQUIRED" to R.string.bt_err_csrf_header_required,
        "CSRF_ORIGIN_REJECTED" to R.string.bt_err_csrf_origin_rejected,

        // ── Authentication: credentials + account state ──
        "INVALID_CREDENTIALS" to R.string.bt_err_invalid_credentials,
        "ACCOUNT_DISABLED" to R.string.bt_err_account_disabled,
        "USER_DISABLED" to R.string.bt_err_user_disabled,
        "ADMIN_ACCOUNT_KIND" to R.string.bt_err_admin_account_kind,
        "PASSWORD_CHANGE_REQUIRED" to R.string.bt_err_password_change_required,
        "ADMIN_2FA_SETUP_REQUIRED" to R.string.bt_err_admin_2fa_setup_required,
        "WEAK_PASSWORD" to R.string.bt_err_weak_password,
        "CONFIRMATION_MISMATCH" to R.string.bt_err_confirmation_mismatch,
        "EMAIL_TAKEN" to R.string.bt_err_email_taken,
        "USERNAME_TAKEN" to R.string.bt_err_username_taken,
        "USER_NOT_FOUND" to R.string.bt_err_user_not_found,
        "INVALID_RESET" to R.string.bt_err_invalid_reset,
        "INVALID_INVITE" to R.string.bt_err_invalid_invite,
        "INVALID_REGISTRATION_TOKEN" to R.string.bt_err_invalid_registration_token,
        "REGISTRATION_TOKEN_REQUIRED" to R.string.bt_err_registration_token_required,
        "REGISTRATION_CLOSED" to R.string.bt_err_registration_closed,

        // ── Authentication: two-factor, PIN, passkeys, Google ──
        "TWO_FACTOR_PENDING_INVALID" to R.string.bt_err_two_factor_pending_invalid,
        "TWO_FACTOR_INVALID_CODE" to R.string.bt_err_two_factor_invalid_code,
        "TWO_FACTOR_ALREADY_ENABLED" to R.string.bt_err_two_factor_already_enabled,
        "TWO_FACTOR_NOT_ENABLED" to R.string.bt_err_two_factor_not_enabled,
        "TWO_FACTOR_NOT_PENDING" to R.string.bt_err_two_factor_not_pending,
        "TWO_FACTOR_EMAIL_UNAVAILABLE" to R.string.bt_err_two_factor_email_unavailable,
        "INVALID_PIN" to R.string.bt_err_invalid_pin,
        "PIN_NOT_ENABLED" to R.string.bt_err_pin_not_enabled,
        "PIN_FALLBACK_LOGIN" to R.string.bt_err_pin_fallback_login,
        "REMEMBER_DEVICE_UNKNOWN" to R.string.bt_err_remember_device_unknown,
        "PASSKEY_CHALLENGE_INVALID" to R.string.bt_err_passkey_challenge_invalid,
        "PASSKEY_VERIFICATION_FAILED" to R.string.bt_err_passkey_verification_failed,
        "PASSKEY_ALREADY_REGISTERED" to R.string.bt_err_passkey_already_registered,
        "PASSKEY_NOT_FOUND" to R.string.bt_err_passkey_not_found,
        "PASSKEY_COUNTER_REGRESSION" to R.string.bt_err_passkey_counter_regression,
        "GOOGLE_FAILED" to R.string.bt_err_google_failed,
        "GOOGLE_VERIFY_FAILED" to R.string.bt_err_google_verify_failed,
        "GOOGLE_ADMIN_UNSUPPORTED" to R.string.bt_err_google_admin_unsupported,
        "GOOGLE_ALREADY_LINKED" to R.string.bt_err_google_already_linked,
        "GOOGLE_EMAIL_MISMATCH" to R.string.bt_err_google_email_mismatch,
        "GOOGLE_NOT_LINKED" to R.string.bt_err_google_not_linked,
        "GOOGLE_ONLY_SIGN_IN" to R.string.bt_err_google_only_sign_in,
        "GOOGLE_REGISTER_TICKET_INVALID" to R.string.bt_err_google_register_ticket_invalid,
        "API_KEY_INVALID" to R.string.bt_err_api_key_invalid,
        "API_KEY_FORBIDDEN" to R.string.bt_err_api_key_forbidden,
        "INSUFFICIENT_SCOPE" to R.string.bt_err_insufficient_scope,

        // ── Portfolios, transactions, dividends, tax ──
        "PORTFOLIO_NOT_FOUND" to R.string.bt_err_portfolio_not_found,
        "PORTFOLIO_NAME_TAKEN" to R.string.bt_err_portfolio_name_taken,
        "PORTFOLIO_ALREADY_ARCHIVED" to R.string.bt_err_portfolio_already_archived,
        "PORTFOLIO_NOT_ARCHIVED" to R.string.bt_err_portfolio_not_archived,
        "LAST_ACTIVE_PORTFOLIO" to R.string.bt_err_last_active_portfolio,
        "PORTFOLIO_VISIBILITY_GUARD_REQUIRED" to R.string.bt_err_portfolio_visibility_guard_required,
        "TRANSACTION_NOT_FOUND" to R.string.bt_err_transaction_not_found,
        "TRANSACTION_TAXED" to R.string.bt_err_transaction_taxed,
        "TRANSACTION_AFFECTS_TAXED" to R.string.bt_err_transaction_affects_taxed,
        "TRANSACTION_CASH_LINKED" to R.string.bt_err_transaction_cash_linked,
        "OVERSELL" to R.string.bt_err_oversell,
        "EMPTY_BATCH" to R.string.bt_err_empty_batch,
        "DIVIDEND_NOT_FOUND" to R.string.bt_err_dividend_not_found,
        "DIVIDEND_ASSET_NOT_HELD" to R.string.bt_err_dividend_asset_not_held,
        "DIVIDEND_AMOUNT_TOO_SMALL" to R.string.bt_err_dividend_amount_too_small,
        "TAX_ENTRY_INVALID" to R.string.bt_err_tax_entry_invalid,
        "TAX_ENTRY_NOT_ALLOWED" to R.string.bt_err_tax_entry_not_allowed,
        "TAX_FX_UNAVAILABLE" to R.string.bt_err_tax_fx_unavailable,
        "BASE_FX_UNAVAILABLE" to R.string.bt_err_base_fx_unavailable,

        // ── Cash: sources, movements, tags, budgets, rules ──
        "CASH_SOURCE_NOT_FOUND" to R.string.bt_err_cash_source_not_found,
        "CASH_SOURCE_NAME_TAKEN" to R.string.bt_err_cash_source_name_taken,
        "CASH_SOURCE_ARCHIVED" to R.string.bt_err_cash_source_archived,
        "CASH_SOURCE_ALREADY_ARCHIVED" to R.string.bt_err_cash_source_already_archived,
        "CASH_SOURCE_NOT_ARCHIVED" to R.string.bt_err_cash_source_not_archived,
        "CASH_SOURCE_IS_MAIN" to R.string.bt_err_cash_source_is_main,
        "CASH_SOURCE_NOT_EMPTY" to R.string.bt_err_cash_source_not_empty,
        "CASH_MOVEMENT_NOT_FOUND" to R.string.bt_err_cash_movement_not_found,
        "CASH_MOVEMENT_NOT_EDITABLE" to R.string.bt_err_cash_movement_not_editable,
        "CASH_LEDGER_WOULD_GO_NEGATIVE" to R.string.bt_err_cash_ledger_would_go_negative,
        "INSUFFICIENT_CASH" to R.string.bt_err_insufficient_cash,
        "CASH_FLAG_MISMATCH" to R.string.bt_err_cash_flag_mismatch,
        "CASH_FX_UNAVAILABLE" to R.string.bt_err_cash_fx_unavailable,
        "CASH_TRANSFER_SAME_SOURCE" to R.string.bt_err_cash_transfer_same_source,
        "CASH_TRANSFER_INVALID" to R.string.bt_err_cash_transfer_invalid,
        "CASH_TAG_NOT_FOUND" to R.string.bt_err_cash_tag_not_found,
        "CASH_TAG_NAME_TAKEN" to R.string.bt_err_cash_tag_name_taken,
        "CASH_TAG_SYSTEM_PROTECTED" to R.string.bt_err_cash_tag_system_protected,
        "CASH_TAG_REF_NOT_FOUND" to R.string.bt_err_cash_tag_ref_not_found,
        "CASH_RULE_NOT_FOUND" to R.string.bt_err_cash_rule_not_found,
        "CASH_RULE_REGEX_UNSUPPORTED" to R.string.bt_err_cash_rule_regex_unsupported,
        "CASH_BUDGET_NOT_FOUND" to R.string.bt_err_cash_budget_not_found,
        "CASH_BUDGET_EXISTS" to R.string.bt_err_cash_budget_exists,
        "STANDING_ORDER_ASSET_NOT_FOUND" to R.string.bt_err_standing_order_asset_not_found,
        "STANDING_ORDER_END_BEFORE_START" to R.string.bt_err_standing_order_end_before_start,
        "STANDING_ORDER_INSUFFICIENT_CASH" to R.string.bt_err_standing_order_insufficient_cash,

        // ── Assets, baskets, watchlists, workboard, alerts, AI ──
        "ASSET_NOT_FOUND" to R.string.bt_err_asset_not_found,
        "CUSTOM_ASSET_NOT_FOUND" to R.string.bt_err_custom_asset_not_found,
        "DUPLICATE_VALUE_POINT" to R.string.bt_err_duplicate_value_point,
        "MANUAL_ASSET_NOT_FOUND" to R.string.bt_err_manual_asset_not_found,
        "MANUAL_ASSET_EMPTY" to R.string.bt_err_manual_asset_empty,
        "QUOTE_UNAVAILABLE" to R.string.bt_err_quote_unavailable,
        "NO_QUOTE" to R.string.bt_err_no_quote,
        "CONGLOMERATE_NOT_FOUND" to R.string.bt_err_conglomerate_not_found,
        "CONGLOMERATE_NAME_TAKEN" to R.string.bt_err_conglomerate_name_taken,
        "CONGLOMERATE_IN_USE" to R.string.bt_err_conglomerate_in_use,
        "CONGLOMERATE_VISIBILITY_GUARD_REQUIRED" to R.string.bt_err_conglomerate_visibility_guard_required,
        "DUPLICATE_ASSET" to R.string.bt_err_duplicate_asset,
        "DUPLICATE_CHILD" to R.string.bt_err_duplicate_child,
        "NESTING_CYCLE" to R.string.bt_err_nesting_cycle,
        "NESTING_TOO_DEEP" to R.string.bt_err_nesting_too_deep,
        "TOO_MANY_POSITIONS" to R.string.bt_err_too_many_positions,
        "ACTIVATION_INVALID" to R.string.bt_err_activation_invalid,
        "ALLOCATION_NO_POSITIONS" to R.string.bt_err_allocation_no_positions,
        "ALLOCATION_INVALID" to R.string.bt_err_allocation_invalid,
        "BACKTEST_UNAVAILABLE" to R.string.bt_err_backtest_unavailable,
        "NO_PRICE_HISTORY" to R.string.bt_err_no_price_history,
        "FX_UNAVAILABLE" to R.string.bt_err_fx_unavailable,
        "SANDBOX_PRIVATE_ASSET" to R.string.bt_err_sandbox_private_asset,
        "SANDBOX_POSITIONS_MISMATCH" to R.string.bt_err_sandbox_positions_mismatch,
        "WATCHLIST_NOT_FOUND" to R.string.bt_err_watchlist_not_found,
        "WATCHLIST_NAME_TAKEN" to R.string.bt_err_watchlist_name_taken,
        "WATCHLIST_DEFAULT_LOCKED" to R.string.bt_err_watchlist_default_locked,
        "ALREADY_WATCHING" to R.string.bt_err_already_watching,
        "ITEM_NOT_FOUND" to R.string.bt_err_item_not_found,
        "IDEA_CONGLOMERATE_NOT_FOUND" to R.string.bt_err_idea_conglomerate_not_found,
        "ALERT_NOT_FOUND" to R.string.bt_err_alert_not_found,
        "ALERT_SHARING_ACK_REQUIRED" to R.string.bt_err_alert_sharing_ack_required,
        "AI_NO_DATA" to R.string.bt_err_ai_no_data,
        "AI_UNAVAILABLE" to R.string.bt_err_ai_unavailable,
        "AI_CAP_EXCEEDED" to R.string.bt_err_ai_cap_exceeded,
        "AI_PROVIDER_ERROR" to R.string.bt_err_ai_provider_error,

        // ── Group portfolios (mirror seam) ──
        "MIRROR_CHAIN_NOT_FOUND" to R.string.bt_err_mirror_chain_not_found,
        "MIRROR_FORBIDDEN" to R.string.bt_err_mirror_forbidden,
        "MIRROR_MEMBER_NOT_FOUND" to R.string.bt_err_mirror_member_not_found,
        "MIRROR_ALREADY_MEMBER" to R.string.bt_err_mirror_already_member,
        "MIRROR_ALREADY_SYNCED" to R.string.bt_err_mirror_already_synced,
        "MIRROR_NO_MEMBERS" to R.string.bt_err_mirror_no_members,
        "MIRROR_NOT_FRIENDS" to R.string.bt_err_mirror_not_friends,
        "MIRROR_CANNOT_INVITE_SELF" to R.string.bt_err_mirror_cannot_invite_self,
        "MIRROR_MEMBER_CAP_REACHED" to R.string.bt_err_mirror_member_cap_reached,
        "MIRROR_INVITE_EXISTS" to R.string.bt_err_mirror_invite_exists,
        "MIRROR_INVITE_NOT_FOUND" to R.string.bt_err_mirror_invite_not_found,
        "MIRROR_CONFLICT" to R.string.bt_err_mirror_conflict,
        "MIRROR_ROW_DELETED" to R.string.bt_err_mirror_row_deleted,
        "MIRROR_BUSY" to R.string.bt_err_mirror_busy,
        "MIRROR_SYNC_STALLED" to R.string.bt_err_mirror_sync_stalled,
        "MIRROR_ASSET_NOT_SYNCABLE" to R.string.bt_err_mirror_asset_not_syncable,
        "MIRROR_OWNER_TRANSFER_REQUIRED" to R.string.bt_err_mirror_owner_transfer_required,

        // ── Social, sharing and chat ──
        "FRIEND_REQUEST_NOT_FOUND" to R.string.bt_err_friend_request_not_found,
        "FRIENDSHIP_NOT_FOUND" to R.string.bt_err_friendship_not_found,
        "FRIEND_GROUP_NOT_FOUND" to R.string.bt_err_friend_group_not_found,
        "GROUP_MEMBER_NOT_FRIEND" to R.string.bt_err_group_member_not_friend,
        "GROUP_AUDIENCE_INVALID" to R.string.bt_err_group_audience_invalid,
        "FOLLOW_NOT_FOUND" to R.string.bt_err_follow_not_found,
        "CANNOT_FOLLOW_SELF" to R.string.bt_err_cannot_follow_self,
        "ITEM_FOLLOW_NOT_FOUND" to R.string.bt_err_item_follow_not_found,
        "CANNOT_FOLLOW_OWN_ITEM" to R.string.bt_err_cannot_follow_own_item,
        "LINK_NOT_FOUND" to R.string.bt_err_link_not_found,
        "PROFILE_NOT_FOUND" to R.string.bt_err_profile_not_found,
        "INVALID_PROFILE_ICON" to R.string.bt_err_invalid_profile_icon,
        "PUBLIC_PROFILE_ACK_REQUIRED" to R.string.bt_err_public_profile_ack_required,
        "PUBLIC_LINK_ACK_REQUIRED" to R.string.bt_err_public_link_ack_required,
        "COMMENT_NOT_FOUND" to R.string.bt_err_comment_not_found,
        "CHAT_BANNED" to R.string.bt_err_chat_banned,
        "CHAT_CHIP_NOT_OWNED" to R.string.bt_err_chat_chip_not_owned,

        // ── Notifications and delivery ──
        "NOTIFICATION_NOT_FOUND" to R.string.bt_err_notification_not_found,
        "WEB_PUSH_ENDPOINT_UNSAFE" to R.string.bt_err_web_push_endpoint_unsafe,
        "WEB_PUSH_SUBSCRIPTION_LIMIT_REACHED" to R.string.bt_err_web_push_subscription_limit_reached,
        "WEBHOOK_NOT_FOUND" to R.string.bt_err_webhook_not_found,
        "WEBHOOK_LIMIT_REACHED" to R.string.bt_err_webhook_limit_reached,

        // ── In-app feedback ──
        // The open-submission cap (platform #1400): 20 open requests at once, and
        // the copy names the two ways out — wait for triage, or delete one from
        // "My submissions". It is catalogued rather than left to the generic
        // fallback precisely because the fallback appends the server's ENGLISH
        // sentence, which on a German phone is the failure P0-4 exists to prevent.
        "FEEDBACK_OPEN_LIMIT" to R.string.bt_err_feedback_open_limit,

        // ── Data export ──
        "EXPORT_TOKEN_REQUIRED" to R.string.bt_err_export_token_required,
        "EXPORT_NOT_FOUND" to R.string.bt_err_export_not_found,
        "EXPORT_RATE_LIMITED" to R.string.bt_err_export_rate_limited,

        // ── Offline queue: idempotency keys ──
        "IDEMPOTENCY_KEY_INVALID" to R.string.bt_err_idempotency_key_invalid,
        "IDEMPOTENCY_KEY_MISMATCH" to R.string.bt_err_idempotency_key_mismatch,
        "IDEMPOTENCY_IN_PROGRESS" to R.string.bt_err_idempotency_in_progress,

        // ── Paranoid mode and the vault ──
        "PARANOID_MODE" to R.string.bt_err_paranoid_mode,
        "PARANOID_NOT_ENABLED" to R.string.bt_err_paranoid_not_enabled,
        "PARANOID_MEDIA_NOT_READY" to R.string.bt_err_paranoid_media_not_ready,
        "PARANOID_MIRRORCHAIN_ACTIVE" to R.string.bt_err_paranoid_mirrorchain_active,
        "PARANOID_IMPORT_IN_FLIGHT" to R.string.bt_err_paranoid_import_in_flight,
        "PARANOID_EXPORT_IN_FLIGHT" to R.string.bt_err_paranoid_export_in_flight,
        "PARANOID_TRANSITION_CONFLICT" to R.string.bt_err_paranoid_transition_conflict,
        "PARANOID_NORMAL_DATA_CHANGED" to R.string.bt_err_paranoid_normal_data_changed,
        "PARANOID_REHYDRATION_INVALID" to R.string.bt_err_paranoid_rehydration_invalid,
        "VAULT_NOT_FOUND" to R.string.bt_err_vault_not_found,
        "VAULT_PARANOID_MODE_REQUIRED" to R.string.bt_err_vault_paranoid_mode_required,
        "VAULT_PRECONDITION_REQUIRED" to R.string.bt_err_vault_precondition_required,
        "VAULT_PRECONDITION_FAILED" to R.string.bt_err_vault_precondition_failed,
        "VAULT_TOO_LARGE" to R.string.bt_err_vault_too_large,
        "VAULT_MALFORMED" to R.string.bt_err_vault_malformed,
        "VAULT_SERVER_MEDIUM_INACTIVE" to R.string.bt_err_vault_server_medium_inactive,
        "VAULT_MEDIA_STATE_CONFLICT" to R.string.bt_err_vault_media_state_conflict,
        "VAULT_MEDIA_VERIFICATION_FAILED" to R.string.bt_err_vault_media_verification_failed,
        "VAULT_RETIRED_SERVER_PROOF_REQUIRED" to R.string.bt_err_vault_retired_server_proof_required,
        "VAULT_RETIRED_SERVER_PROOF_INVALID" to R.string.bt_err_vault_retired_server_proof_invalid,
        "VAULT_RETIRED_SERVER_CONFLICT" to R.string.bt_err_vault_retired_server_conflict,
        "VAULT_RETIRED_SERVER_RETENTION" to R.string.bt_err_vault_retired_server_retention,

        // ── App-local refusals (never sent by the server) ──
        "APP_HTTP_FAILED" to R.string.bt_err_app_http_failed,
        "APP_EMPTY_RESPONSE" to R.string.bt_err_app_empty_response,
        "APP_UNEXPECTED" to R.string.bt_err_app_unexpected,
        "APP_REJECTED" to R.string.bt_err_app_rejected,
        "APP_OP_MALFORMED_SUBMIT" to R.string.bt_err_app_op_malformed_submit,
        "APP_OP_MALFORMED_VAULT" to R.string.bt_err_app_op_malformed_vault,
        "APP_OP_REPLAY_WINDOW_EXPIRED" to R.string.bt_err_app_op_replay_window_expired,
        "APP_OP_ATTEMPT_TIMED_OUT" to R.string.bt_err_app_op_attempt_timed_out,
        "APP_OP_NO_VAULT" to R.string.bt_err_app_op_no_vault,
        "APP_OP_NO_CASH_SOURCE" to R.string.bt_err_app_op_no_cash_source,
        "APP_OP_ZERO_AMOUNT" to R.string.bt_err_app_op_zero_amount,
        "APP_OP_NO_RATE" to R.string.bt_err_app_op_no_rate,
        "NO_LIVE_PRICES" to R.string.bt_err_no_live_prices,
        "DIRECT_PROVIDER_DISABLED" to R.string.bt_err_direct_provider_disabled,
        "NO_EXCHANGE_RATE" to R.string.bt_err_no_exchange_rate,
        "VAULT_DERIVATION_FAILED" to R.string.bt_err_vault_derivation_failed,
        "DELETE_DISABLED" to R.string.bt_err_delete_disabled,
        "APP_UNKNOWN_ALERT_KIND" to R.string.bt_err_app_unknown_alert_kind,
    )

    /** Every code the catalog covers — the contract `BtErrorCopyTest` asserts on. */
    val codes: Set<String> get() = CATALOG.keys

    /**
     * Codes whose string carries a `%1$s` placeholder and therefore needs the
     * stored argument to format. Kept as an explicit set rather than sniffed
     * from the resource at runtime: a formatting contract that the tests can
     * assert on beats one that only fails on a user's phone.
     */
    val ARGUMENT_CODES: Set<String> = setOf(AppCodes.OP_NO_RATE, "NO_EXCHANGE_RATE")

    /** True when [code]'s copy needs one format argument. */
    fun takesArgument(code: String?): Boolean = code in ARGUMENT_CODES

    /** The resource for [code], or null when the catalog does not cover it. */
    @StringRes
    fun resFor(code: String?): Int? = code?.let { CATALOG[it] }

    /** True when the app owns translated copy for [code]. */
    fun isKnown(code: String?): Boolean = resFor(code) != null

    /**
     * The resource for [code], falling back to a generic translated sentence.
     * The fallback is never a dead end: callers pair it with the server's own
     * words via [BtApiError.diagnostic].
     */
    @StringRes
    fun resOrGeneric(code: String?): Int = resFor(code) ?: R.string.bt_err_unknown
}

/**
 * A user-facing message the APP owns: a string resource, plus an optional dim
 * diagnostic line for the cases where the app has no specific copy.
 *
 * This type exists to make the P0-4 contract compiler-enforced. Screens used to
 * hold `String?` error state and fill it from `error.userMessage`, which meant a
 * raw server string was always one assignment away. Holding a [BtMessage]
 * instead makes that assignment a type error.
 */
data class BtMessage(
    @StringRes val res: Int,
    /**
     * The server's / JVM's own words. Never the primary line — a secondary,
     * de-emphasised diagnostic, and only populated when [res] is the generic
     * fallback. Null whenever the app has real copy for the code.
     */
    val diagnostic: String? = null,
    /**
     * Single format argument, for the handful of codes in
     * [BtErrorCopy.ARGUMENT_CODES] whose copy names a currency. Null otherwise.
     */
    val formatArg: String? = null,
) {
    companion object {
        /** "Something went wrong." — the honest default. */
        val generic: BtMessage get() = BtMessage(R.string.bt_err_unknown)
    }
}

/**
 * The one conversion from wire error to user-facing copy.
 *
 * A code the catalog covers yields translated copy and NO diagnostic — the
 * server's English would only add noise next to a sentence that already says
 * the same thing. An unknown code yields the generic sentence PLUS the server's
 * own words, so a code shipped by the platform after this build still conveys
 * something concrete.
 */
fun BtApiError.asMessage(): BtMessage {
    val known = BtErrorCopy.resFor(code) ?: return BtMessage(
        R.string.bt_err_unknown,
        diagnostic = diagnostic?.takeIf { it.isNotBlank() },
    )
    // The few codes whose copy names a currency take the stored text as their
    // format argument rather than as a diagnostic.
    return if (BtErrorCopy.takesArgument(code)) {
        BtMessage(known, formatArg = diagnostic)
    } else {
        BtMessage(known)
    }
}

/** Convenience for the many call sites that only ever show the generic line. */
fun BtResult.Err.asMessage(): BtMessage = error.asMessage()

/**
 * How a PARKED sync op's reason should be rendered.
 *
 * Kept as a pure function returning a pure verdict — rather than as logic inside
 * the composable that draws it — because this is the one branch in the P0-4 work
 * with a persistence contract behind it, and a persistence contract deserves
 * unit tests that do not need a Compose runtime to run.
 */
sealed interface ParkReason {
    /** Catalogued code: translated copy, plus a format argument for the few that take one. */
    data class Copy(@StringRes val res: Int, val formatArg: String? = null) : ParkReason

    /**
     * A code this build has no copy for — a code the platform shipped after this
     * app version. Generic sentence, with the server's own words appended so the
     * row still says something specific.
     */
    data class Unmapped(val diagnostic: String?) : ParkReason

    /**
     * A row parked BEFORE the DB v10 migration: no code was stored, only English
     * prose. It renders verbatim. Deriving a code by pattern-matching the
     * sentence was considered and rejected — this text describes a pending change
     * to the user's money, and a wrong guess is worse than untranslated truth.
     * The row self-heals on its next retry, which re-parks it with a real code.
     */
    data class Legacy(val text: String) : ParkReason
}

/** The park-reason policy. See [ParkReason] for why each branch exists. */
fun parkReasonFor(errorCode: String?, storedDetail: String?): ParkReason {
    val detail = storedDetail?.takeIf { it.isNotBlank() }
    val res = BtErrorCopy.resFor(errorCode)
    return when {
        res != null && BtErrorCopy.takesArgument(errorCode) -> ParkReason.Copy(res, detail.orEmpty())
        res != null -> ParkReason.Copy(res)
        errorCode != null -> ParkReason.Unmapped(detail)
        detail != null -> ParkReason.Legacy(detail)
        // Pre-v10 row whose prose was also empty — nothing to fall back to.
        else -> ParkReason.Unmapped(null)
    }
}
