package at.bettertrack.app.ui.applock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Thin BiometricPrompt wrapper for the app lock (spec §5). AndroidX
 * BiometricPrompt smooths fingerprint/face across API 28→33 and needs a
 * [FragmentActivity] host (MainActivity is one). We ask for BIOMETRIC_WEAK so
 * both strong fingerprint and OEM face sensors qualify — biometric is only a
 * convenience gate here; the security root is the Keystore-hashed PIN, which is
 * always the fallback (the negative button is "Use PIN").
 */
enum class BiometricAvailability {
    /** A biometric is enrolled and usable right now. */
    AVAILABLE,

    /** Hardware exists but nothing is enrolled (offer "enrol in system settings"). */
    NONE_ENROLLED,

    /** No biometric hardware, or it's permanently/temporarily unusable. */
    UNAVAILABLE,
}

/** What [BiometricManager] reports for weak-or-stronger biometrics on this device. */
fun biometricAvailability(context: Context): BiometricAvailability =
    when (BiometricManager.from(context).canAuthenticate(Authenticators.BIOMETRIC_WEAK)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
        else -> BiometricAvailability.UNAVAILABLE
    }

/**
 * Live biometric availability that RE-EVALUATES on every ON_RESUME (spec §5,
 * Step-17 refinement). Enrollment status changes outside the app (the user leaves
 * to Android Settings, adds a fingerprint, returns), so the Security toggle must
 * re-read it on return — that's how a greyed toggle enables itself the moment a
 * biometric becomes usable, with no need to leave and re-enter the screen.
 */
@Composable
fun rememberBiometricAvailability(): BiometricAvailability {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var availability by remember { mutableStateOf(biometricAvailability(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) availability = biometricAvailability(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return availability
}

/** Walk the ContextWrapper chain to the hosting Activity (for BiometricPrompt). */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Show the system biometric sheet.
 * @param onSuccess biometric matched → unlock.
 * @param onError   terminal error / user chose "Use PIN" / cancelled — fall back
 *                  to the PIN pad. [isUserCancel] flags the benign cases so the
 *                  UI doesn't shout an error at a deliberate dismissal.
 */
fun promptBiometric(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeText: String,
    onSuccess: () -> Unit,
    onError: (isUserCancel: Boolean) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val userCancel = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onError(userCancel)
            }
            // onAuthenticationFailed (single mismatch) intentionally left to the
            // prompt: it stays open for a retry; only terminal errors fall back.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText(negativeText)
        .setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK)
        .setConfirmationRequired(false)
        .build()
    prompt.authenticate(info)
}
