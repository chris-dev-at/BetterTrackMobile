package at.bettertrack.app.domain

/**
 * Forces the IANA time-zone database into the browser/Node runtime.
 *
 * kotlinx-datetime's Kotlin/Wasm implementation is js-joda, and js-joda ships
 * with **no** zone rules: kotlinx-datetime declares only `@js-joda/core`, so
 * `TimeZone.of("Europe/Vienna")` has nothing to resolve against. It does not
 * throw — it takes the whole wasm module down with
 * `RuntimeError: dereferencing a null pointer` inside `toLocalDateTime`. That is
 * on the path of [viennaYearOf], i.e. every taxable transaction.
 *
 * `@js-joda/timezone` is a SIDE-EFFECT module: importing it registers a
 * `ZoneRulesProvider`. Declaring the npm dependency is therefore only half the
 * fix — something has to actually import it, which is what the external
 * declaration below is for.
 */
@JsModule("@js-joda/timezone")
private external val jsJodaTimeZoneModule: JsAny

private val jsJodaTimeZoneDatabase: JsAny = jsJodaTimeZoneModule

/**
 * Installs the zone database, and must be called by the browser host BEFORE any
 * domain code runs — see :webApp's `main()`.
 *
 * It exists because a `private val` alone is not enough: the DEVELOPMENT
 * executable (what `:shared:wasmJsNodeTest` links) keeps unreferenced
 * declarations, but the PRODUCTION bundle runs DCE and would drop both the val
 * and, with it, the `import` statement that has the side effect. The only thing
 * that reliably survives is a live call from the entry point, so the requirement
 * is made explicit rather than left to a linker's mood.
 */
fun btInstallTimeZoneDatabase(): JsAny = jsJodaTimeZoneDatabase
