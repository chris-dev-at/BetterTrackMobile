package at.bettertrack.app.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BtQrCode] gained two encoding parameters so the paranoid §13 transfer code
 * can be byte mode / UTF-8 / EC-M. This test is the proof that the 2FA
 * enrollment QR did not move a single module in the process, and that the new
 * parameters do what §13 asks.
 *
 * It works on the [BitMatrix], not a Bitmap: the matrix IS the code (the bitmap
 * step is a pure per-module colour lookup), and a matrix needs no Android
 * runtime, so this runs as a plain JVM unit test in both flavors' suites.
 */
class BtQrCodeEncodingTest {

    /** The real shape of the string [BtQrCode]'s existing call site encodes. */
    private val otpauthUri =
        "otpauth://totp/BetterTrack:chris%40example.com?secret=JBSWY3DPEHPK3PXP" +
            "&issuer=BetterTrack&algorithm=SHA1&digits=6&period=30"

    /** Exactly the hint map `BtQrCode` used before it was parameterised. */
    private val legacyHints: Map<EncodeHintType, Any> = mapOf(EncodeHintType.MARGIN to 1)

    private fun legacyMatrix(data: String, size: Int): BitMatrix =
        QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, legacyHints)

    /** First differing module, or `null` when the two matrices are identical. */
    private fun firstDifference(expected: BitMatrix, actual: BitMatrix): String? {
        if (expected.width != actual.width || expected.height != actual.height) {
            return "size ${expected.width}x${expected.height} vs ${actual.width}x${actual.height}"
        }
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (expected[x, y] != actual[x, y]) return "module ($x,$y)"
            }
        }
        return null
    }

    private fun assertSameMatrix(expected: BitMatrix, actual: BitMatrix) {
        assertNull(firstDifference(expected, actual))
    }

    // ── the regression the parameterisation had to not cause ────────────────

    @Test
    fun `the TOTP call site encodes byte-identically to the pre-change code`() {
        // BtQrCode's defaults are ErrorCorrectionLevel.L + no CHARACTER_SET, and
        // TwoFactorScreen passes neither, so this compares the exact matrices the
        // shipped app produced before and after.
        listOf(208, 320, 512).forEach { size ->
            assertSameMatrix(
                legacyMatrix(otpauthUri, size),
                btQrMatrix(otpauthUri, size, ErrorCorrectionLevel.L, null),
            )
        }
    }

    @Test
    fun `an explicit L is the same input as an absent error-correction hint`() {
        // QRCodeWriter initialises the level to L before it reads the hints, which
        // is why the new parameter can carry a plain typed default.
        assertSameMatrix(
            QRCodeWriter().encode("BetterTrack", BarcodeFormat.QR_CODE, 256, 256, legacyHints),
            QRCodeWriter().encode(
                "BetterTrack",
                BarcodeFormat.QR_CODE,
                256,
                256,
                mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
            ),
        )
    }

    @Test
    fun `the default hint map is the legacy hint map plus the redundant level`() {
        val hints = btQrHints(ErrorCorrectionLevel.L, null)
        assertEquals(1, hints[EncodeHintType.MARGIN])
        assertEquals(ErrorCorrectionLevel.L, hints[EncodeHintType.ERROR_CORRECTION])
        assertFalse(
            "an absent CHARACTER_SET is what keeps the TOTP bytes identical",
            hints.containsKey(EncodeHintType.CHARACTER_SET),
        )
    }

    @Test
    fun `a CHARACTER_SET hint really does change the bits, so null must stay the default`() {
        // This is the reason `characterSet` is nullable rather than defaulted to a
        // charset name: a present hint makes ZXing emit an ECI header, which is a
        // different bit stream for the very same text. If this test ever stops
        // failing to differ, the nullable default has become pointless — but
        // until then, defaulting it would have silently re-encoded the 2FA QR.
        val withoutHint = btQrMatrix(otpauthUri, 320, ErrorCorrectionLevel.L, null)
        val withHint = btQrMatrix(otpauthUri, 320, ErrorCorrectionLevel.L, BT_QR_CHARSET_UTF8)
        assertNotNull(
            "a CHARACTER_SET hint must not be a no-op",
            firstDifference(withoutHint, withHint),
        )
    }

    // ── what §13 asks for ───────────────────────────────────────────────────

    @Test
    fun `the transfer payload encodes as byte mode, UTF-8, error correction M`() {
        val payload = "btvault1:m=abandon+abandon+abandon+abandon+abandon+abandon+abandon+" +
            "abandon+abandon+abandon+abandon+about&v=018f3c2a-7b41-7c3e-9f21-0a1b2c3d4e5f"
        val code = Encoder.encode(
            payload,
            ErrorCorrectionLevel.M,
            btQrHints(ErrorCorrectionLevel.M, BT_QR_CHARSET_UTF8),
        )
        assertEquals(Mode.BYTE, code.getMode())
        assertEquals(ErrorCorrectionLevel.M, code.getECLevel())
        // ~200 characters at EC-M lands in the low teens of QR versions; the point
        // of the assertion is that it stays a hand-scannable code, not version 40.
        val version = code.getVersion().getVersionNumber()
        assertTrue("QR version got large: $version", version <= 15)
    }

    @Test
    fun `the quiet zone stays one module wide in every configuration`() {
        // MARGIN survives the parameterisation. The white card around the code is
        // BtQrCode's own padding; this is the encoder-side quiet zone.
        assertEquals(1, btQrHints(ErrorCorrectionLevel.M, BT_QR_CHARSET_UTF8)[EncodeHintType.MARGIN])
    }
}
