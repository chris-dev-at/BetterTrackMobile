package at.bettertrack.app.data.net

/**
 * The common, platform-neutral result the app consumes off an HTTP call — the
 * KMP/iOS port's replacement for handing repositories a `retrofit2.Response<T>`.
 *
 * It carries EXACTLY the four things the app reads off Retrofit's `Response<T>`
 * today, no more: a survey of the 14 repositories found ZERO uses of
 * `headers()`, `message()` or `raw()`, so those are deliberately absent. Keeping
 * the surface minimal is what lets both an Android Retrofit adapter and the iOS
 * Ktor client satisfy it without leaking either stack's response type upward.
 *
 * Contract, mirroring `apiCall`'s read of a Retrofit response:
 *  - [isSuccessful] / [code]: the HTTP status, split exactly as OkHttp does
 *    (`isSuccessful` == code in 200..299).
 *  - [body]: the DECODED payload, present ONLY on a successful response. On an
 *    error response it is null — the app never decodes an error body into `T`.
 *  - [errorBody]: the RAW error payload text (what `response.errorBody()?.string()`
 *    returns), present only on a NON-successful response, for the error mapper to
 *    read a server error code out of.
 */
data class ApiResponse<out T>(
    val isSuccessful: Boolean,
    val code: Int,
    val body: T?,
    val errorBody: String?,
)
