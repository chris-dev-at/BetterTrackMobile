package at.bettertrack.app.data.net

/**
 * HTTP header names shared by the Ktor network plugins — the same string values
 * the Android middleware uses, so the wire contract is byte-identical.
 */
internal object BtNetHeaders {
    /** Opts a request out of 401→refresh→retry (a 401 is a domain answer here). */
    const val NO_REAUTH = "X-Bt-No-Reauth"

    /** Marks a response the ConditionalGet plugin replayed from a stored 304. */
    const val FROM_ETAG_CACHE = "X-BT-From-ETag-Cache"
}
