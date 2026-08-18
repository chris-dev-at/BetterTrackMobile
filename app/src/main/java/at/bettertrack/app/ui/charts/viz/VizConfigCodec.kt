package at.bettertrack.app.ui.charts.viz

/**
 * The on-disk form of a [BtVizConfig].
 *
 * A tiny hand-rolled codec rather than JSON because the payload is five scalars
 * and this is a `SharedPreferences` value read on the main thread before the
 * first frame. What matters far more than the format is the **decode contract**:
 *
 * > Anything unrecognised falls back to the safe automatic value, and never
 * > throws.
 *
 * A preference written by a build that knew a form this one does not — a
 * downgrade, a sideload, a half-finished migration — must degrade to
 * `Automatisch`, not crash the insights page or lock a user out of a screen.
 * Enum **names** are stored, never ordinals, so reordering [BtVizForm] can not
 * silently reinterpret someone's saved treemap as a donut.
 */

private const val SEPARATOR = "|"
private const val FIELDS = 5
private const val NONE = "-"

/** Encode [config] for [VizPrefs]. Returns null for a pristine config, which means "forget it". */
fun vizConfigEncode(config: BtVizConfig): String? {
    if (config == BtVizConfig()) return null
    return listOf(
        config.form.name,
        config.labels.name,
        config.scope.name,
        if (config.showCash) "1" else "0",
        config.focusKey?.takeIf { it.isNotBlank() && !it.contains(SEPARATOR) } ?: NONE,
    ).joinToString(SEPARATOR)
}

/** Decode a stored string. Unknown or malformed input yields the default config. */
fun vizConfigDecode(raw: String?): BtVizConfig {
    if (raw.isNullOrBlank()) return BtVizConfig()
    val parts = raw.split(SEPARATOR)
    if (parts.size != FIELDS) return BtVizConfig()
    return BtVizConfig(
        form = BtVizForm.entries.firstOrNull { it.name == parts[0] } ?: BtVizForm.AUTO,
        labels = BtVizLabels.entries.firstOrNull { it.name == parts[1] } ?: BtVizLabels.AUTO,
        scope = BtVizScope.entries.firstOrNull { it.name == parts[2] } ?: BtVizScope.AUTO,
        // Absent or garbage means "show it": hiding cash is the destructive
        // reading of the denominator, so it is never the fallback.
        showCash = parts[3] != "0",
        focusKey = parts[4].takeIf { it != NONE && it.isNotBlank() },
    )
}
