package at.bettertrack.app.data.auth

import android.os.Process
import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Invocation
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * DEBUG BUILDS ONLY — what the next token refresh should suffer, for verifying
 * the refresh rail on a device without waiting for a bad network.
 *
 * Armed from Settings → Developer, persisted in `DevicePrefs` under a debug key,
 * and consumed (one-shot) by the first refresh that runs. [AuthFaultInterceptor]
 * is added to the token client only when `BuildConfig.DEBUG`; a release build
 * never wires it.
 */
enum class AuthFault {
    NONE,

    /** The request reaches the server (which ROTATES), the answer is dropped: a lost answer. */
    LOST_ANSWER,

    /** A synthetic `429 RATE_LIMITED` with `Retry-After: 20`; nothing is sent. */
    RATE_LIMITED,

    /** A connect failure; nothing is sent. */
    TRANSPORT,

    /**
     * The request reaches the server (which ROTATES), then the process dies
     * before the answer is processed — the write-ahead record is all that is left.
     */
    KILL_AFTER_SEND,
}

internal fun authFaultFromName(raw: String?): AuthFault =
    AuthFault.entries.firstOrNull { it.name == raw } ?: AuthFault.NONE

/**
 * Applies the armed [AuthFault] to the next REFRESH call (identified by its
 * Retrofit [Invocation], so the code exchange is never touched) and disarms it
 * before acting — synchronously, so [AuthFault.KILL_AFTER_SEND] cannot kill the
 * relaunched process too.
 */
class AuthFaultInterceptor(
    private val armed: () -> AuthFault,
    private val disarm: () -> Unit,
    private val killProcess: () -> Unit = { Process.killProcess(Process.myPid()) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(Invocation::class.java)?.method()?.name != "refresh") return chain.proceed(request)
        val fault = armed()
        if (fault == AuthFault.NONE) return chain.proceed(request)
        disarm()
        Log.w(TAG, "Debug auth fault applied to this refresh: $fault")
        return when (fault) {
            AuthFault.NONE -> chain.proceed(request)
            AuthFault.LOST_ANSWER -> {
                chain.proceed(request).close()
                throw SocketTimeoutException("debug fault: the refresh answer was dropped")
            }
            AuthFault.KILL_AFTER_SEND -> {
                chain.proceed(request).close()
                killProcess()
                throw SocketTimeoutException("debug fault: process killed after send")
            }
            AuthFault.TRANSPORT -> throw ConnectException("debug fault: connection refused")
            AuthFault.RATE_LIMITED ->
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .header("Retry-After", DEBUG_RETRY_AFTER_SECONDS.toString())
                    .body(
                        (
                            """{"error":{"code":"RATE_LIMITED","message":"debug fault",""" +
                                """"details":{"retryAfter":$DEBUG_RETRY_AFTER_SECONDS}}}"""
                            ).toResponseBody("application/json".toMediaType()),
                    )
                    .build()
        }
    }

    private companion object {
        const val TAG = "BtAuthFault"
        const val DEBUG_RETRY_AFTER_SECONDS = 20
    }
}
