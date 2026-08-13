package at.bettertrack.app.data.net

/**
 * The allowlist of endpoints the platform ETags — reproduced VERBATIM from
 * Android's `ConditionalGetInterceptor.isConditionalGetTarget` so the iOS Ktor
 * plugin conditionalises EXACTLY the same three GETs and no others:
 *   `/api/v1/portfolios/{id}`, `/api/v1/portfolios/{id}/history`, `/api/v1/search`.
 *
 * Kept as a pure common function: it is the one piece of the conditional-GET
 * behaviour that is platform-independent, so it lives once in commonMain and is
 * cross-checked by the same assertions on both sides. Matches on the encoded
 * path exactly as the OkHttp interceptor does (the `/api/v1` prefix included).
 */
fun isConditionalGetTarget(encodedPath: String): Boolean {
    val path = encodedPath.removeSuffix("/")
    if (path.endsWith("/search")) return true
    val idx = path.indexOf("/portfolios/")
    if (idx < 0) return false
    val rest = path.substring(idx + "/portfolios/".length)
    if (rest.isEmpty()) return false
    val segments = rest.split("/")
    return when (segments.size) {
        1 -> segments[0].isNotEmpty() // /portfolios/{id}
        2 -> segments[0].isNotEmpty() && segments[1] == "history"
        else -> false
    }
}
