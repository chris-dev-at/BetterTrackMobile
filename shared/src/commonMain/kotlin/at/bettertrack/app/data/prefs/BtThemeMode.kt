package at.bettertrack.app.data.prefs

/**
 * Which colour table the app renders in (B2 design spec §1.3).
 *
 * Three-valued on purpose. AMOLED true-black is a **boolean under [Dark]**
 * (`DevicePrefs.trueBlack`), not a fourth entry — it overrides two neutrals and
 * nothing else, and keeping the enum at three means every `when` in the app
 * stays exhaustive when the toggle lands.
 *
 * Moved out of `DevicePrefs.kt` into `:shared/commonMain` by the web port,
 * Phase W1, with its package unchanged so no `import` in `:app` moved. It is the
 * single non-Compose type `BetterTrackTheme` takes, and the theme now compiles
 * for three platforms; the rest of `DevicePrefs.kt` — `SharedPreferences`, the
 * decoder helpers, the `StateFlow`s — stays in `:app` because it is Android
 * storage, which the web replaces with `multiplatform-settings` in W6 (D11).
 */
enum class BtThemeMode { System, Light, Dark }
