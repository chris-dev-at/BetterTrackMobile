package at.bettertrack.app.data.i18n

import java.util.Locale

/**
 * The `Accept-Language` value the app sends on `GET /auth/me`: the in-app
 * language when one is chosen, else the device language (platform ruling,
 * landing with PR #2094: `/auth/me` localises `pendingAcknowledgements[].title`
 * from this header). `null` — header omitted — when the device reports no
 * language at all (`und`).
 */
fun acceptLanguageFor(selected: AppLanguage, device: Locale): String? =
    selected.tag.ifEmpty { device.toLanguageTag().takeUnless { it.isBlank() || it == "und" } }
