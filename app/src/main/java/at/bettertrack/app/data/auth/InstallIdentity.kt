package at.bettertrack.app.data.auth

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The `instance_id` this install sends on the OAuth code exchange (platform
 * PR-B, #2085, live since 2026-10-30).
 *
 * Every `authorization_code` exchange starts a token FAMILY, and a re-login that
 * presents the same `instance_id` retires that install's previous family. So the
 * id has to name the INSTALL, not the session:
 *
 *  - generated once, on first use, from [SecureRandom] — 24 bytes as unpadded
 *    base64url, 32 characters, inside the server's `^[A-Za-z0-9._~-]{16,64}$`;
 *  - persisted in its own app-private file, NOT in [SecureStore]: the token store
 *    is wiped on every sign-out, and this id must survive one (the next sign-in
 *    has to retire the old family, not start a parallel one). A Keystore fault
 *    cannot take it with it either;
 *  - excluded from cloud backup AND device-to-device transfer
 *    (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`): a
 *    restored id on a second device would make that device's first sign-in
 *    retire the FIRST device's family — signing out a phone from a tablet.
 *    Clearing the app's data or reinstalling makes a new one, which is right:
 *    that is a new install.
 *
 * Sent ONLY in [at.bettertrack.app.data.api.dto.TokenExchangeRequest]: the
 * refresh schema is strict, so an `instance_id` on a refresh is
 * `400 VALIDATION_ERROR`. It is not a secret (it cannot mint anything without a
 * code), but it is a stable device identifier, so it is never logged — only its
 * presence, length or [instanceIdFingerprint].
 */
class InstallIdentity(
    private val read: () -> String?,
    private val write: (String) -> Boolean,
    private val generate: () -> String = ::newInstanceId,
) {
    @Volatile
    private var cached: String? = null

    /**
     * This install's id: the stored one, or a new one written down first. A
     * stored value that does not match the server's pattern (a damaged file) is
     * replaced, because sending it would fail the sign-in with a 400.
     */
    @Synchronized
    fun instanceId(): String {
        cached?.let { return it }
        val stored = read()?.takeIf { INSTANCE_ID_PATTERN.matches(it) }
        val id = stored ?: generate().also { fresh ->
            // A write that did not reach disk still lets THIS sign-in carry the
            // id (the process keeps it in memory); the next process makes a new
            // one, which costs one parallel family until it ages out — never a
            // failed sign-in.
            write(fresh)
        }
        cached = id
        return id
    }

    companion object {
        /** The app-private prefs file the id lives in — named in the backup exclusions. */
        const val FILE = "bt_install_identity"
        private const val KEY = "instance_id"

        /** The production store: a plain `SharedPreferences` file of its own. */
        fun forContext(context: Context): InstallIdentity {
            val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return InstallIdentity(
                read = { prefs.getString(KEY, null) },
                write = { prefs.edit().putString(KEY, it).commit() },
            )
        }
    }
}

/** The server's `instance_id` pattern (`POST /oauth/token`, authorization_code branch). */
internal val INSTANCE_ID_PATTERN = Regex("^[A-Za-z0-9._~-]{16,64}$")

/** 24 random bytes, unpadded base64url: 32 characters, all inside [INSTANCE_ID_PATTERN]. */
internal fun newInstanceId(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(INSTANCE_ID_BYTES).also(random::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private const val INSTANCE_ID_BYTES = 24

/**
 * What a log line may say about an `instance_id`: the first 8 hex characters of
 * its SHA-256. Enough to tell "the same id as last time" from "a new one" on a
 * device check, and nothing that identifies the install to anyone reading logs.
 */
internal fun instanceIdFingerprint(id: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { "%02x".format(it) }
