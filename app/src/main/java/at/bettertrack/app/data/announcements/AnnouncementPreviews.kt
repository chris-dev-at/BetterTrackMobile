package at.bettertrack.app.data.announcements

import kotlinx.serialization.json.Json

/**
 * Debug-preview samples (Settings → Developer → "Preview announcement: …").
 *
 * Built from WIRE JSON through [parseAnnouncement] — the same parser the server's
 * items take — so a preview exercises the decode, the typed-tree renderer and the
 * three surfaces exactly as a real item would, without a server that has any.
 * Every sample carries `preview = true`: seen, dismiss and acknowledge are local
 * fake receipts and never reach the network.
 *
 * EN and DE are both authored so the gate's language switch can be shown.
 */
object AnnouncementPreviews {

    const val NOTICE_ID = "00000000-0000-4000-8000-0000000000a1"
    const val ONCE_ID = "00000000-0000-4000-8000-0000000000a2"
    const val ACK_ID = "00000000-0000-4000-8000-0000000000a3"

    private val json = Json { ignoreUnknownKeys = true }

    fun sample(kind: AnnouncementKind, locale: String): Announcement {
        val de = locale == LOCALE_DE
        val raw = when (kind) {
            AnnouncementKind.NOTICE -> notice(de)
            AnnouncementKind.ONCE -> once(de)
            AnnouncementKind.ACK_REQUIRED -> ack(de)
        }
        return parseAnnouncement(json.parseToJsonElement(raw))!!.copy(preview = true)
    }

    private fun item(
        id: String,
        kind: String,
        severity: String,
        locale: String,
        title: String,
        body: String,
        bodyDoc: String,
    ) = """
        {"id":"$id","kind":"$kind","severity":"$severity","title":${q(title)},"body":${q(body)},
         "publishedAt":"2026-10-30T08:00:00Z","bodyFormat":"markdown","bodyDoc":$bodyDoc,
         "contentLocale":"$locale","availableLocales":["en","de"],
         "contentSha256":"${"0".repeat(63)}${if (locale == LOCALE_DE) "d" else "e"}",
         "startsAt":null,"endsAt":null,"dismissible":${kind != "ack_required"},"status":"active",
         "receipt":{"seenAt":null,"dismissedAt":null,"acknowledgedAt":null,"lapsedAt":null,"lapseReason":null}}
    """.trimIndent()

    private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private fun text(v: String) = """{"t":"text","v":${q(v)}}"""
    private fun p(vararg inlines: String) = """{"t":"p","c":[${inlines.joinToString(",")}]}"""
    private fun strong(vararg c: String) = """{"t":"strong","c":[${c.joinToString(",")}]}"""
    private fun em(vararg c: String) = """{"t":"em","c":[${c.joinToString(",")}]}"""
    private fun link(href: String, label: String) = """{"t":"link","href":${q(href)},"c":[${text(label)}]}"""
    private fun doc(vararg blocks: String) = """{"v":1,"blocks":[${blocks.joinToString(",")}]}"""

    private fun notice(de: Boolean) = if (de) {
        item(
            NOTICE_ID, "notice", "info", LOCALE_DE,
            "Wartung am Sonntag, 06:00–07:00",
            "Kurse können in diesem Zeitfenster verzögert sein.",
            doc(p(text("Kurse können in diesem Zeitfenster "), strong(text("verzögert")), text(" sein."))),
        )
    } else {
        item(
            NOTICE_ID, "notice", "info", LOCALE_EN,
            "Maintenance on Sunday, 06:00–07:00",
            "Prices may be delayed during this window.",
            doc(p(text("Prices may be "), strong(text("delayed")), text(" during this window."))),
        )
    }

    private fun once(de: Boolean) = if (de) {
        item(
            ONCE_ID, "once", "info", LOCALE_DE,
            "Neu: Budgets pro Kategorie",
            "Lege für jede Kategorie ein Monatsbudget fest.",
            doc(
                p(text("Lege für jede Kategorie ein "), em(text("Monatsbudget")), text(" fest – BetterTrack zeigt dir, wann du dich der Grenze näherst.")),
                """{"t":"ul","items":[[${text("Pro Portfolio")}],[${text("Mit Benachrichtigung beim Überschreiten")}]]}""",
            ),
        )
    } else {
        item(
            ONCE_ID, "once", "info", LOCALE_EN,
            "New: budgets per category",
            "Set a monthly budget for every category.",
            doc(
                p(text("Set a "), em(text("monthly budget")), text(" for every category — BetterTrack tells you when you are getting close.")),
                """{"t":"ul","items":[[${text("Per portfolio")}],[${text("With a notification when you go over")}]]}""",
            ),
        )
    }

    private fun ack(de: Boolean) = if (de) {
        item(
            ACK_ID, "ack_required", "warning", LOCALE_DE,
            "Aktualisierte Nutzungsbedingungen",
            "Wir haben unsere Nutzungsbedingungen aktualisiert.",
            doc(
                """{"t":"h","level":2,"c":[${text("Was sich ändert")}]}""",
                p(text("Ab dem "), strong(text("1. Dezember 2026")), text(" gelten unsere aktualisierten Nutzungsbedingungen. Die wichtigsten Punkte:")),
                """{"t":"ol","start":1,"items":[[${text("Exporte bleiben jederzeit kostenlos.")}],[${text("Gelöschte Konten werden nach 30 Tagen endgültig entfernt.")}],[${text("Neue Kontakt-Adresse: ")},{"t":"code","v":"support@bettertrack.at"}]]}""",
                p(text("Den vollständigen Text findest du unter "), link("https://bettertrack.at/terms", "bettertrack.at/terms"), text(".")),
                p(text("Wenn du nicht zustimmst, kannst du deine Daten exportieren oder dein Konto löschen.")),
            ),
        )
    } else {
        item(
            ACK_ID, "ack_required", "warning", LOCALE_EN,
            "Updated terms of service",
            "We have updated our terms of service.",
            doc(
                """{"t":"h","level":2,"c":[${text("What changes")}]}""",
                p(text("From "), strong(text("1 December 2026")), text(" our updated terms of service apply. The main points:")),
                """{"t":"ol","start":1,"items":[[${text("Exports stay free, at any time.")}],[${text("Deleted accounts are removed for good after 30 days.")}],[${text("New contact address: ")},{"t":"code","v":"support@bettertrack.at"}]]}""",
                p(text("Read the full text at "), link("https://bettertrack.at/terms", "bettertrack.at/terms"), text(".")),
                p(text("If you do not agree, you can export your data or delete your account.")),
            ),
        )
    }
}
