package at.bettertrack.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes the "Save as file" action writes.
 *
 * Recovery codes are the last way back into a locked-out account, so the file's
 * contract is narrow on purpose: the codes, in order, one per line, verbatim,
 * and nothing a human has to read past. These assertions are what stops a
 * well-meaning header line or a de-duplicating `toSet()` from creeping in later.
 */
class RecoveryCodesFileTest {

    private val codes = listOf("ABCD-1234", "EFGH-5678", "IJKL-9012")

    @Test
    fun `one code per line, in the order shown`() {
        assertEquals("ABCD-1234\nEFGH-5678\nIJKL-9012\n", recoveryCodesFileBody(codes))
    }

    @Test
    fun `the file is only the codes`() {
        val body = recoveryCodesFileBody(codes)
        val lines = body.trimEnd('\n').lines()
        assertEquals(codes, lines)
    }

    @Test
    fun `the file ends with a newline`() {
        // A text file without one reads as truncated in half the editors that
        // will ever open this.
        assertTrue(recoveryCodesFileBody(codes).endsWith("\n"))
    }

    @Test
    fun `no code is dropped, reordered or de-duplicated`() {
        // A server that returns the same code twice is a server bug, but the
        // file must still say what the dialog showed.
        val dupes = listOf("AAAA", "BBBB", "AAAA")
        assertEquals("AAAA\nBBBB\nAAAA\n", recoveryCodesFileBody(dupes))
    }

    @Test
    fun `an empty list writes an empty file, not a lone newline`() {
        assertEquals("", recoveryCodesFileBody(emptyList()))
    }

    @Test
    fun `a single code still gets its newline`() {
        assertEquals("ONLY-ONE\n", recoveryCodesFileBody(listOf("ONLY-ONE")))
    }

    @Test
    fun `the offered name and type are a plain text file`() {
        assertEquals("text/plain", RECOVERY_CODES_MIME)
        assertTrue(RECOVERY_CODES_FILENAME.endsWith(".txt"))
        // No timestamp: regenerating replaces the codes wholesale, and two files
        // differing only by date would be a set of near-identical files of which
        // exactly one still works.
        assertEquals("bettertrack-recovery-codes.txt", RECOVERY_CODES_FILENAME)
    }
}
