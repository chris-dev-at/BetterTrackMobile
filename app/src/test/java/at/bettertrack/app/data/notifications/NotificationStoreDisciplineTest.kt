package at.bettertrack.app.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tripwire for a specific, recurring bug: a **destructive write hidden inside
 * a save path**.
 *
 * ## What happened
 *
 * `NotificationSettingsStore.persist()` — the method that writes ONE type's channel
 * routing — carried this, unconditionally, on every single call:
 *
 * ```
 * .remove("$k.muted")
 * ```
 *
 * The stated reason was that a per-type mute stored by an older build "would sit in
 * SharedPreferences forever with no UI able to clear it". So saving a change to,
 * say, the email column of `friend.request` also deleted a *different* stored
 * choice — and it did it for every type, on every write, silently and forever.
 *
 * This is the same shape as the true-black "healer" the owner had removed a round
 * earlier: code that quietly repairs a user's stored state on their behalf, where
 * the repair is indistinguishable from data loss. The right answer to "no UI can
 * clear this" is to restore the UI, which is what the 2026-08-17 parity rebuild
 * did — the per-type mute is a real control again, and server-backed this time
 * (the platform contract defines a muted type as all-channels-false).
 *
 * ## Why a source scan
 *
 * The store needs an Android `Context` and this module has no Robolectric
 * (`app/build.gradle.kts` — junit, mockwebserver, coroutines-test, sqlite-jdbc, and
 * nothing else), so there is no way to instantiate it in a unit test and assert on
 * real SharedPreferences. The pure logic it delegates to IS tested, in
 * `NotificationCatalogTest`; what cannot be reached that way is the shape of the
 * persistence method itself, and that is exactly where the bug lived.
 *
 * ## Why it is not a blanket ban on `remove`
 *
 * `persist` legitimately removes two keys: the telegram and discord cells are
 * TRI-STATE, and "the server does not model this channel" is stored as the absence
 * of the key. Removing them is how a `null` is written — it is the encoding, not a
 * deletion of anything the user chose. So the rule is precise: inside `persist`,
 * every `remove` must be guarded by a null check on the value it encodes, and none
 * may target the mute or the pre-mute snapshot.
 */
class NotificationStoreDisciplineTest {

    private fun source(path: String): String {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("source not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    private val store: String
        get() = source("at/bettertrack/app/data/notifications/NotificationSettingsStore.kt")

    /** The body of [function], by brace matching from its signature. */
    private fun bodyOf(src: String, function: String): String {
        val at = src.indexOf("fun $function(")
        require(at >= 0) { "$function not found — did it get renamed?" }
        val open = src.indexOf('{', at)
        require(open >= 0) { "$function has no body" }
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        error("$function body is unbalanced")
    }

    @Test
    fun `the per-type mute key is never deleted again`() {
        // THE guard. Any reappearance of the blanket removal fails here, whatever
        // it is renamed to, because the assertion is on the KEY not the call site.
        assertFalse(
            "persist() must not delete a stored per-type mute — that is the 2026-08 data-loss bug",
            store.contains(""".remove("${'$'}k.muted")"""),
        )
        assertFalse(store.contains("""remove("${'$'}type.muted")"""))
        assertFalse(
            "no write path may remove a pre-mute snapshot as a side effect",
            bodyOf(store, "persist").contains("premuteKey"),
        )
    }

    /** Strip KDoc/line comments so a sentence ABOUT `remove()` is not read as one. */
    private fun code(src: String): String = src
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `every remove inside persist is a tri-state encoding, not a deletion`() {
        val body = code(bodyOf(store, "persist"))
        val removes = Regex("""\.?remove\(([^)]*)\)""").findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        // Exactly two: the telegram and discord cells, each the `else` of a null
        // check. If a third appears, it is being added for a reason this test's
        // KDoc argues against, and the author should have to justify it here.
        assertEquals("persist() should remove exactly the two tri-state keys, got $removes", 2, removes.size)
        assertEquals(listOf("tg", "dc"), removes)
        assertTrue(
            "the telegram removal must be the else-branch of a null check",
            body.contains("if (p.telegram != null)") && body.contains("else e.remove(tg)"),
        )
        assertTrue(
            "the discord removal must be the else-branch of a null check",
            body.contains("if (p.discord != null)") && body.contains("else e.remove(dc)"),
        )
    }

    @Test
    fun `the store no longer heals legacy keys on upgrade`() {
        // The enum-named keys from the NotifKind-keyed era are left where they are:
        // inert under the current `m.<type>.<channel>` naming, and deleting a user's
        // stored data on upgrade is the very behaviour being avoided.
        val code = code(store)
        assertFalse(
            "no sweep over the legacy enum-named keys",
            code.contains("FriendRequest.") || code.contains("clear()"),
        )
    }

    @Test
    fun `matrix keys are namespaced so a wire type cannot collide with bookkeeping`() {
        // `delivery.qh.start`, `avail.telegram` and a hypothetical wire type called
        // `delivery` all live in one SharedPreferences file. The `m.` prefix is what
        // keeps them apart.
        assertTrue(store.contains("""fun cellKey(type: String, channel: NotifChannel) = "m.${'$'}type.${'$'}{channel.wire}""""))
        assertTrue(store.contains("""fun premuteKey(type: String) = "pm.${'$'}type""""))
    }

    @Test
    fun `the pre-mute snapshot is written and read through the tested codec`() {
        // The snapshot is the thing the old bug would have destroyed, so it must not
        // acquire a second, untested serialisation.
        val body = bodyOf(store, "setPremuteSnapshot")
        assertTrue(body.contains("encodeRouting(snapshot)"))
        assertTrue(store.contains("decodeRouting(prefs.getString(premuteKey(type), null))"))
    }
}
