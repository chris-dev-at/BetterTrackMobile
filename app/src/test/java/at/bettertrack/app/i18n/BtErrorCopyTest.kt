package at.bettertrack.app.i18n

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.ParkReason
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.parkReasonFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The S6 P0-4 contract: every error code the app can receive resolves to
 * app-authored copy that exists in BOTH languages, and an unknown code degrades
 * to a translated sentence rather than to a raw server string.
 *
 * These tests read the resource XML directly (the same trick [StringParityTest]
 * uses) because the unit-test JVM has no resource table to resolve ids against.
 * That is a feature here: it checks the two halves of the catalog — the Kotlin
 * map and the XML — against each other, which is exactly the pair that drifts.
 */
class BtErrorCopyTest {

    private fun resFile(qualifier: String): File {
        val name = "src/main/res/values$qualifier/strings.xml"
        return listOf(File(name), File("app/$name")).firstOrNull { it.isFile }
            ?: error("strings.xml not found for values$qualifier")
    }

    private fun stringNames(qualifier: String): Set<String> =
        Regex("""<string\s+name="([^"]+)"""")
            .findAll(resFile(qualifier).readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun strings(qualifier: String): Map<String, String> =
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(resFile(qualifier).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun resName(code: String) = "bt_err_${code.lowercase()}"

    @Test
    fun `catalog is not empty`() {
        // A silently-empty catalog would make every error fall back to the
        // generic sentence and every other test here vacuously pass.
        assertTrue("catalog looks empty: ${BtErrorCopy.codes.size}", BtErrorCopy.codes.size > 150)
    }

    @Test
    fun `every catalogued code has an english string`() {
        val en = stringNames("")
        val missing = BtErrorCopy.codes.filter { resName(it) !in en }.sorted()
        assertTrue("codes with no EN string: $missing", missing.isEmpty())
    }

    @Test
    fun `every catalogued code has a german string`() {
        val de = stringNames("-de")
        val missing = BtErrorCopy.codes.filter { resName(it) !in de }.sorted()
        assertTrue("codes with no DE string: $missing", missing.isEmpty())
    }

    @Test
    fun `no orphaned bt_err strings without a code`() {
        // The other direction: a string left behind after a code was removed is
        // dead weight that still has to be translated and reviewed forever.
        val expected = BtErrorCopy.codes.map { resName(it) }.toSet()
        val orphans = stringNames("").filter { it.startsWith("bt_err_") && it !in expected }.sorted()
        assertTrue("bt_err_* strings with no catalogue entry: $orphans", orphans.isEmpty())
    }

    @Test
    fun `every catalogued code maps to a real resource id`() {
        val zero = BtErrorCopy.codes.filter { (BtErrorCopy.resFor(it) ?: 0) == 0 }.sorted()
        assertTrue("codes mapping to resource id 0: $zero", zero.isEmpty())
    }

    @Test
    fun `codes taking an argument carry a placeholder in both languages`() {
        val en = strings("")
        val de = strings("-de")
        BtErrorCopy.ARGUMENT_CODES.forEach { code ->
            val key = resName(code)
            assertTrue("EN $key must contain %1\$s", en.getValue(key).contains("%1\$s"))
            assertTrue("DE $key must contain %1\$s", de.getValue(key).contains("%1\$s"))
        }
    }

    @Test
    fun `codes not taking an argument carry no placeholder`() {
        // A stray %1$s in a string nobody formats crashes at render time.
        val en = strings("")
        val stray = BtErrorCopy.codes
            .filter { it !in BtErrorCopy.ARGUMENT_CODES }
            .filter { en.getValue(resName(it)).contains("%") }
            .sorted()
        assertTrue("unexpected format placeholder in: $stray", stray.isEmpty())
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    @Test
    fun `known code resolves to its own copy and hides the server text`() {
        val err = BtApiError(409, BtApiError.Codes.MIRROR_CONFLICT, "baseSeq 41 != 42")
        val msg = err.asMessage()
        assertEquals(BtErrorCopy.resFor(BtApiError.Codes.MIRROR_CONFLICT), msg.res)
        // The server's vocabulary must not reach the user next to copy that
        // already explains the same thing in their language.
        assertNull(msg.diagnostic)
    }

    @Test
    fun `unknown code falls back to generic copy and keeps the server text`() {
        val err = BtApiError(400, "SHIPPED_AFTER_THIS_BUILD", "Widget quota exceeded.")
        val msg = err.asMessage()
        assertEquals(BtErrorCopy.resOrGeneric("SHIPPED_AFTER_THIS_BUILD"), msg.res)
        assertEquals(BtErrorCopy.resFor("UNKNOWN"), msg.res)
        // The fallback is never a dead end: the server's own words still ride along.
        assertEquals("Widget quota exceeded.", msg.diagnostic)
    }

    @Test
    fun `unknown code with no server text still resolves to something translated`() {
        val msg = BtApiError(500, "MYSTERY").asMessage()
        assertNotEquals(0, msg.res)
        assertNull(msg.diagnostic)
    }

    @Test
    fun `argument-taking code carries the stored text as a format argument`() {
        val err = BtApiError(0, "NO_EXCHANGE_RATE", "USD → EUR")
        val msg = err.asMessage()
        assertEquals("USD → EUR", msg.formatArg)
        // It is an argument, not a diagnostic — it belongs INSIDE the sentence.
        assertNull(msg.diagnostic)
    }

    @Test
    fun `resFor is null for an unknown code but resOrGeneric never is`() {
        assertNull(BtErrorCopy.resFor("NOPE"))
        assertNull(BtErrorCopy.resFor(null))
        assertNotEquals(0, BtErrorCopy.resOrGeneric("NOPE"))
        assertNotEquals(0, BtErrorCopy.resOrGeneric(null))
    }

    @Test
    fun `app-local queue codes are catalogued`() {
        // These never come from the server, so nothing else would catch their
        // absence until a parked op rendered a blank line on someone's phone.
        listOf(
            BtErrorCopy.AppCodes.HTTP_FAILED,
            BtErrorCopy.AppCodes.EMPTY_RESPONSE,
            BtErrorCopy.AppCodes.UNEXPECTED,
            BtErrorCopy.AppCodes.REJECTED,
            BtErrorCopy.AppCodes.OP_MALFORMED_SUBMIT,
            BtErrorCopy.AppCodes.OP_MALFORMED_VAULT,
            BtErrorCopy.AppCodes.OP_REPLAY_WINDOW_EXPIRED,
            BtErrorCopy.AppCodes.OP_ATTEMPT_TIMED_OUT,
            BtErrorCopy.AppCodes.OP_NO_VAULT,
            BtErrorCopy.AppCodes.OP_NO_CASH_SOURCE,
            BtErrorCopy.AppCodes.OP_ZERO_AMOUNT,
            BtErrorCopy.AppCodes.OP_NO_RATE,
            BtErrorCopy.AppCodes.UNKNOWN_ALERT_KIND,
        ).forEach { assertTrue("$it not catalogued", it in BtErrorCopy.codes) }
    }

    // ── Parked-op render policy (the Room migration contract) ────────────────

    @Test
    fun `parked op with a known code renders catalogued copy`() {
        val reason = parkReasonFor(BtErrorCopy.AppCodes.OP_ATTEMPT_TIMED_OUT, null)
        assertEquals(
            ParkReason.Copy(BtErrorCopy.resFor(BtErrorCopy.AppCodes.OP_ATTEMPT_TIMED_OUT)!!),
            reason,
        )
    }

    @Test
    fun `parked op with an argument code passes the stored detail through`() {
        val reason = parkReasonFor(BtErrorCopy.AppCodes.OP_NO_RATE, "CHF")
        assertEquals(
            ParkReason.Copy(BtErrorCopy.resFor(BtErrorCopy.AppCodes.OP_NO_RATE)!!, "CHF"),
            reason,
        )
    }

    @Test
    fun `parked op with an unknown code keeps the server diagnostic`() {
        assertEquals(
            ParkReason.Unmapped("Widget quota exceeded."),
            parkReasonFor("SHIPPED_LATER", "Widget quota exceeded."),
        )
    }

    @Test
    fun `legacy parked row renders its stored english verbatim`() {
        // The migration back-fills nothing: a pre-v10 row has no code, only the
        // sentence it was parked with. Showing it as-is is the honest fallback.
        val legacy = "This change timed out (the server didn't respond). Retry, or remove it."
        assertEquals(ParkReason.Legacy(legacy), parkReasonFor(null, legacy))
    }

    @Test
    fun `legacy parked row with no stored text degrades to the generic sentence`() {
        assertEquals(ParkReason.Unmapped(null), parkReasonFor(null, null))
        assertEquals(ParkReason.Unmapped(null), parkReasonFor(null, "   "))
    }
}
