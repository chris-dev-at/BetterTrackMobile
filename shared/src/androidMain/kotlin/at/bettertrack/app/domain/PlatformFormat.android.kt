package at.bettertrack.app.domain

import java.util.Locale

/**
 * Android/JVM actual of [formatScientific]: delegates straight to
 * `String.format`, so the migrated `jsNumberToString` emits **byte-identical**
 * output to the pre-migration `app`-local code (which was this exact call at
 * `DomainTypes.kt:104`). That identity is what keeps the 2727-test JVM suite
 * unaffected by the domain move — by construction, not by luck.
 */
internal actual fun formatScientific(value: Double, fractionDigits: Int): String =
    String.format(Locale.ROOT, "%.${fractionDigits}e", value)
