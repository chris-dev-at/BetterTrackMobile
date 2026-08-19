package at.bettertrack.app.data.net

import io.ktor.client.engine.js.Js

/**
 * The BROWSER half of the network seam (web port, Phase W0) — the whole of it.
 *
 * The client itself, its two session-critical plugins and the synthetic-call
 * machinery live in `nonAndroidMain` and are compiled verbatim for Kotlin/Wasm;
 * the only thing a platform still owes them is an `HttpClientEngine`. On iOS that
 * is `Darwin.create()`, here it is Ktor's `Js` engine, which on the wasmJs target
 * is the browser `fetch` API. So the entire platform-specific surface of the
 * browser network layer is the two factory calls below.
 *
 * THREE browser-only truths that are NOT visible from this file and that the
 * production wiring (W4) has to answer — they are properties of the origin, not
 * of Kotlin:
 *
 *  - **CORS preflight.** `Authorization`, `Idempotency-Key`, `X-Bt-No-Reauth` and
 *    `If-None-Match` are all non-simple request headers, so every call from
 *    `mobile-dev.bettertrack.at` to `api.bettertrack.at` costs an `OPTIONS`
 *    preflight and the API must name each of them in `Access-Control-Allow-Headers`.
 *  - **ETag readability.** [BtConditionalGetPlugin] reads the `ETag` off a
 *    response. Cross-origin JavaScript cannot see a response header unless the
 *    server lists it in `Access-Control-Expose-Headers`, so without `ETag` there
 *    the plugin silently degrades to an unconditional GET — correct, but with the
 *    bandwidth win gone and no error to notice it by.
 *  - **Token custody.** There is no Keychain and no EncryptedSharedPreferences in
 *    a tab. [TokenRefresher] stays the seam it already is, but what backs it in a
 *    browser is a security decision, not an implementation detail — see
 *    docs/KMP_PLAN.md §14 (W5).
 */
fun btBrowserApiClient(
    refresher: TokenRefresher,
    baseUrl: String = BtKtorApiClient.DEFAULT_BASE_URL,
): BtApiClient = BtKtorApiClient(engine = Js.create(), refresher = refresher, baseUrl = baseUrl)

/** The BARE token client (no auth plugin), on the same browser engine. */
fun btBrowserTokenClient(
    baseUrl: String = BtKtorApiClient.DEFAULT_BASE_URL,
): BtTokenClient = BtKtorTokenClient(engine = Js.create(), baseUrl = baseUrl)
