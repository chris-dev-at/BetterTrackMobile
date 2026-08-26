package at.bettertrack.app.ui.vault.qr

import at.bettertrack.app.ui.components.BT_QR_CHARSET_UTF8
import at.bettertrack.app.ui.components.btQrMatrix
import at.bettertrack.app.vault.pv.VaultQrParseResult
import at.bettertrack.app.vault.pv.buildVaultQrPayload
import at.bettertrack.app.vault.pv.parseVaultQrPayload
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sender → receiver, end to end, with no device in the loop.
 *
 * The §13 spec exists because two independently written implementations have to
 * agree; the cheapest possible proof that *our* two ends agree is to run the
 * real encoder ([btQrMatrix], the one `BtQrCode` paints) into the real decoder
 * ([decodeQrLuminance], the one the CameraX analyzer calls) and check the string
 * that comes out is the string that went in. Both are pure Java, so this is a
 * plain JVM unit test in both flavors' suites — no emulator, no camera, no
 * screenshot review.
 */
class VaultQrRoundTripTest {

    private val words =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon about"
    private val vaultId = "018f3c2a-7b41-7c3e-9f21-0a1b2c3d4e5f"

    /**
     * Paint a matrix into a greyscale buffer the way a camera would see it on a
     * white surface: dark modules black, everything else white, plus a generous
     * quiet zone. (The encoder's own MARGIN is one module; a real scan always has
     * more page around the code, and so does this.)
     */
    private fun luminance(matrix: BitMatrix, pad: Int = 48): Triple<ByteArray, Int, Int> {
        val w = matrix.width + pad * 2
        val h = matrix.height + pad * 2
        val out = ByteArray(w * h) { 0xFF.toByte() }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) out[(y + pad) * w + (x + pad)] = 0
            }
        }
        return Triple(out, w, h)
    }

    private fun scan(payload: String, size: Int = 480): String? {
        val (data, w, h) = luminance(
            btQrMatrix(payload, size, ErrorCorrectionLevel.M, BT_QR_CHARSET_UTF8),
        )
        return decodeQrLuminance(data, w, h, w, h)
    }

    @Test
    fun `a minimal payload survives encode and decode`() {
        val payload = buildVaultQrPayload(words, vaultId)
        assertEquals(payload, scan(payload))
    }

    @Test
    fun `the full payload with a name and a fingerprint survives`() {
        val payload = buildVaultQrPayload(
            mnemonic = words,
            vaultId = vaultId,
            name = "Familie & Co",
            fingerprint = "Zm9vYmFy_ab-cdEF",
        )
        assertEquals(payload, scan(payload))
    }

    @Test
    fun `a non-ascii name survives the UTF-8 declaration`() {
        // The CHARACTER_SET hint makes ZXing emit an ECI header; this proves our
        // own reader consumes it correctly rather than handing back mojibake.
        val payload = buildVaultQrPayload(words, vaultId, name = "Öl & Gas – Depot")
        val decoded = scan(payload)
        assertEquals(payload, decoded)
        val parsed = parseVaultQrPayload(decoded!!)
        assertTrue(parsed is VaultQrParseResult.Ok)
        assertEquals("Öl & Gas – Depot", (parsed as VaultQrParseResult.Ok).payload.name)
    }

    @Test
    fun `the longest legal payload still decodes`() {
        val payload = buildVaultQrPayload(
            mnemonic = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            vaultId = vaultId,
            name = "x".repeat(64),
            fingerprint = "A".repeat(16),
        )
        assertEquals(payload, scan(payload))
    }

    @Test
    fun `the scanned string parses back into the payload it was built from`() {
        val built = buildVaultQrPayload(words, vaultId, name = "Depot", fingerprint = "AbCdEfGhIjKlMn_o")
        val result = parseVaultQrPayload(scan(built)!!)
        assertTrue("expected Ok, got $result", result is VaultQrParseResult.Ok)
        val payload = (result as VaultQrParseResult.Ok).payload
        assertEquals(words, payload.mnemonic)
        assertEquals(vaultId, payload.vaultId)
        assertEquals("Depot", payload.name)
        assertEquals("AbCdEfGhIjKlMn_o", payload.fingerprint)
    }

    @Test
    fun `a blank frame decodes to nothing rather than throwing`() {
        assertNull(decodeQrLuminance(ByteArray(200 * 200) { 0xFF.toByte() }, 200, 200, 200, 200))
    }

    @Test
    fun `nonsense dimensions are refused instead of read out of bounds`() {
        val data = ByteArray(64)
        assertNull(decodeQrLuminance(data, 0, 8, 8, 8))
        assertNull(decodeQrLuminance(data, 8, 0, 8, 8))
        assertNull(decodeQrLuminance(data, 8, 8, 0, 8))
        assertNull(decodeQrLuminance(data, 8, 8, 8, 0))
        // A buffer smaller than the declared frame.
        assertNull(decodeQrLuminance(data, 100, 100, 100, 100))
        // A crop bigger than the plane.
        assertNull(decodeQrLuminance(data, 8, 8, 16, 8))
    }

    @Test
    fun `a padded row stride is honoured`() {
        // The camera's luma plane is often wider than the image; decoding must
        // read the crop, not the padding.
        val matrix = btQrMatrix(
            buildVaultQrPayload(words, vaultId),
            480,
            ErrorCorrectionLevel.M,
            BT_QR_CHARSET_UTF8,
        )
        val (tight, w, h) = luminance(matrix)
        val stride = w + 37 // a deliberately odd amount of padding
        val padded = ByteArray(stride * h) { 0x7F } // mid-grey junk in the pad
        for (y in 0 until h) {
            tight.copyInto(padded, y * stride, y * w, y * w + w)
        }
        assertNotNull(decodeQrLuminance(padded, stride, h, w, h))
    }
}
