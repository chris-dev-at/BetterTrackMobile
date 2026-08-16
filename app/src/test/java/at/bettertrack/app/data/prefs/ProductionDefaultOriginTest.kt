package at.bettertrack.app.data.prefs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Production is the default backend, for every build type — owner ruling
 * 2026-08-17: *"make sure the default server is better track normal again since
 * we develop on the main web.bettertrack.at right now and no longer on some dev
 * server."*
 *
 * ## Why this is asserted against the BUILD FILE and not against `BuildConfig`
 *
 * A developer pointing their own build at a local stack does it with
 * `-PbtApiOrigin=…`, which is exactly the explicit, deliberate choice the ruling
 * still allows. Asserting the effective `BuildConfig.API_ORIGIN` would fail that
 * legitimate build and teach the next person to delete the test. What must never
 * drift is the CHECKED-IN DEFAULT — what a clone, a CI job, or anyone who does
 * not pass a property gets — so that is what is pinned here.
 *
 * The failure this prevents is not hypothetical: the debug default was a dev
 * stack for months, with `gradle.properties` quietly correcting it back to
 * production. That is one deleted line away from shipping the owner an APK
 * pointed at a machine that does not exist — which is precisely how his phone
 * ended up on an unreachable `192.168.0.114` and unable to sign in.
 */
class ProductionDefaultOriginTest {

    private fun buildFile(): String {
        val candidates = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("app/build.gradle.kts not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    /** The `getOrElse("…")` default attached to gradle property [property]. */
    private fun defaultFor(property: String): String {
        val match = Regex("""gradleProperty\("$property"\)\.getOrElse\("([^"]*)"\)""")
            .find(buildFile())
            ?: error("no default found for -P$property — was the origin wiring rewritten?")
        return match.groupValues[1]
    }

    @Test
    fun `the api and web defaults are production`() {
        assertTrue(
            "the default API origin is not production: ${defaultFor("btApiOrigin")}",
            defaultFor("btApiOrigin") == "https://api.bettertrack.at",
        )
        assertTrue(
            "the default WEB origin is not production: ${defaultFor("btWebOrigin")}",
            defaultFor("btWebOrigin") == "https://web.bettertrack.at",
        )
    }

    @Test
    fun `no default origin is a private address`() {
        // The general form of the rule, so the next dev machine cannot be baked
        // in under a different number.
        listOf("btApiOrigin", "btWebOrigin", "btProductOrigin").forEach { property ->
            val value = defaultFor(property)
            assertTrue(
                "-P$property defaults to a local/LAN address ($value)",
                value.isBlank() || !isLocalHost(hostOf(value)),
            )
            assertTrue("-P$property defaults to cleartext ($value)", value.startsWith("https://"))
        }
    }

    @Test
    fun `the local-dev preset ships unarmed`() {
        // Empty, not a dead LAN box: an unarmed preset is not offered by the
        // Server screen at all, so nobody can one-tap their way onto a server
        // that does not answer.
        listOf("btDevPresetApiOrigin", "btDevPresetWebOrigin").forEach { property ->
            assertTrue(
                "-$property still ships a baked-in dev address: ${defaultFor(property)}",
                defaultFor(property).isEmpty(),
            )
        }
    }

    @Test
    fun `nothing falls back to a dev origin at runtime`() {
        // The resolution rule is override-or-compiled-default and has no third
        // branch — in particular no "the server did not answer, try the dev one".
        assertTrue(effectiveOrigin(null, "https://api.bettertrack.at", enabled = true) == "https://api.bettertrack.at")
        assertTrue(effectiveOrigin("", "https://api.bettertrack.at", enabled = true) == "https://api.bettertrack.at")
        assertTrue(effectiveOrigin("   ", "https://api.bettertrack.at", enabled = true) == "https://api.bettertrack.at")
        // A play build ignores any stored override entirely.
        assertTrue(
            effectiveOrigin("http://10.0.0.4:3000", "https://api.bettertrack.at", enabled = false) ==
                "https://api.bettertrack.at",
        )
    }
}
