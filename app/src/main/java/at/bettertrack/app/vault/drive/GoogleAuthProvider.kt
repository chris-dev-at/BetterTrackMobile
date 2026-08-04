package at.bettertrack.app.vault.drive

/**
 * The Google access-token seam for [DriveDataHome] — the Kotlin counterpart of
 * the web's `GoogleDriveTokenClient` (`apps/web/src/user/vault/drive/gisTokenClient.ts`).
 *
 * ## Why an interface, and why the app ships a stub behind it
 *
 * The Google Cloud OAuth client for `at.bettertrack.app` **does not exist yet** —
 * creating it (release + debug SHA-1s in project `bettertrackapp-c6996`) is an
 * owner action, tracked as plan §6.8 and the gate on W4's device tests. So this
 * package is written the only way that is honest about that: the *protocol* is
 * an interface with a fake in tests, and the real token source is a thin,
 * separately-reviewable class that compiles but is not yet exercised anywhere.
 *
 * Everything in [DriveDataHome] — find, create, multipart upload, the
 * approximated CAS, duplicate detection, quota and token-expiry mapping — is
 * therefore fully proven on the JVM against MockWebServer before a single real
 * Google credential exists. When the OAuth client lands, the only new code is a
 * [GoogleAuthProvider] implementation; nothing above it changes.
 *
 * ## Scope
 *
 * Implementations MUST request `https://www.googleapis.com/auth/drive.appdata`
 * and nothing else. Least privilege is binding (plan §2.3): `appDataFolder` is
 * invisible to the user's Drive UI and to every other app, and a broader Drive
 * scope would let a bug in this client touch documents that are none of its
 * business. Never widen it "temporarily".
 */
interface GoogleAuthProvider {

    /**
     * A currently-valid bearer token for `drive.appdata`, or **`null` when the
     * user must sign in / re-consent**.
     *
     * `null` is a first-class, expected answer, not an error: Google's access
     * tokens last about an hour and re-minting one can need a user gesture. Plan
     * §2.6 is explicit that this must never become a silent stall — the caller
     * turns `null` into a visible "Sign in to Google to sync" chip while local
     * writes keep succeeding.
     *
     * Implementations must not block the calling thread.
     */
    suspend fun accessToken(): String?

    /**
     * Told by [DriveDataHome] when Drive answered 401 — the token the provider
     * handed out is dead even if its own expiry clock disagrees.
     *
     * Mirrors `markExpired` on the web token client. The default no-op keeps
     * trivial implementations (tests, a fixed-token debug provider) to one
     * method.
     */
    suspend fun markExpired() = Unit
}

/**
 * The Drive OAuth scope this client is allowed to request. Public so the
 * eventual sign-in implementation and its review can reference one constant
 * rather than re-typing the string.
 */
const val DRIVE_APPDATA_SCOPE: String = "https://www.googleapis.com/auth/drive.appdata"

/**
 * The not-yet-connected provider.
 *
 * Until the OAuth client exists this is what the object graph wires in, and it
 * answers exactly what is true: nobody is signed in. Drive-mode writes still
 * land locally and the UI shows the "sign in to sync" state, which is the
 * designed behaviour for an expired/absent token anyway (plan §4.4) — so the
 * app is coherent in this state rather than broken by it.
 */
object SignedOutGoogleAuthProvider : GoogleAuthProvider {
    override suspend fun accessToken(): String? = null
}
