package at.bettertrack.app.data.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (RFC 7636, S256) + opaque `state` generation for the Authorization Code
 * flow (spec §4). All values are cryptographically random (SecureRandom) and
 * base64url-encoded without padding, which is a valid PKCE `code_verifier`
 * charset ([A-Za-z0-9-_]).
 */
object Pkce {

    private val random = SecureRandom()

    /** A 43–128 char code_verifier. 48 random bytes → 64 base64url chars. */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(48)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    /** code_challenge = base64url(SHA-256(ASCII(code_verifier))). */
    fun codeChallengeFor(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    /** Opaque anti-forgery state (16 random bytes → 22 base64url chars). */
    fun generateState(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
