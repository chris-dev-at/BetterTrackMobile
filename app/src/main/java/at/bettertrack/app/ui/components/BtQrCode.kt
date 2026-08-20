package at.bettertrack.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * An offline QR code, encoded on-device with ZXing (no network / no external
 * assets). Rendered on a WHITE rounded card with near-black modules — QR readers
 * need a light quiet-zone, so this deliberately breaks the dark theme for
 * scannability, framed as a card. (`BtThemeDisciplineTest` pins that exemption.)
 *
 * ## Two call sites, two encodings
 *
 * 1. **2FA authenticator (TOTP) enrollment** (spec §6.12,
 *    `ui/settings/TwoFactorScreen.kt`) — an `otpauth://` URI. It keeps ZXing's
 *    defaults, which is what it has always shipped with.
 * 2. **Paranoid-vault seed-phrase transfer** (paranoid §13,
 *    `ui/vault/qr/VaultQrShowScreen.kt`) — the `btvault1:` payload, which the
 *    spec pins to **byte mode, UTF-8, error correction M**.
 *
 * [errorCorrection] and [characterSet] exist so the second one can say that
 * without moving a single module of the first.
 *
 * ### Why [characterSet] is nullable rather than defaulted to a charset name
 *
 * Absent is not the same as "ISO-8859-1". ZXing's `Encoder` only emits an **ECI
 * header** (the segment that declares the text encoding) when a `CHARACTER_SET`
 * hint is present and differs from its default byte-mode charset — so passing
 * any value changes the encoded bit stream, while passing none reproduces
 * today's bytes exactly. `null` therefore means *omit the hint*, and that is the
 * default. [errorCorrection] can be a plain typed default because ZXing's own
 * `QRCodeWriter` initialises the level to `L` before it reads the hints, so an
 * explicit `L` and an absent hint are the same input. `BtQrCodeEncodingTest`
 * proves both claims against the real otpauth URI shape rather than trusting
 * this paragraph.
 */
@Composable
fun BtQrCode(
    data: String,
    modifier: Modifier = Modifier,
    size: Dp = 208.dp,
    errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.L,
    characterSet: String? = null,
) {
    val pxDensity = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(pxDensity) { size.roundToPx() }.coerceAtLeast(160)

    val painter = remember(data, sizePx, errorCorrection, characterSet) {
        runCatching { encodeQr(data, sizePx, errorCorrection, characterSet) }
            .getOrNull()
            ?.let { BitmapPainter(it.asImageBitmap()) }
    }

    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null, // decorative; the manual secret is the a11y path
            contentScale = ContentScale.Fit,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp)
                .size(size),
        )
    }
}

/** ISO 18004's UTF-8 declaration, spelled the way ZXing wants it. Used by the §13 payload. */
const val BT_QR_CHARSET_UTF8: String = "UTF-8"

private const val QR_DARK = 0xFF0A0D12.toInt() // brand near-black modules (BtDarkColors.bg)
private const val QR_LIGHT = 0xFFFFFFFF.toInt()

/**
 * The ZXing hint map. Built in one place so the encoding contract is one
 * readable expression and the unit test can compare it with the literal map the
 * TOTP path used before this became parameterised.
 */
internal fun btQrHints(
    errorCorrection: ErrorCorrectionLevel,
    characterSet: String?,
): Map<EncodeHintType, Any> = buildMap {
    put(EncodeHintType.MARGIN, 1)
    put(EncodeHintType.ERROR_CORRECTION, errorCorrection)
    if (characterSet != null) put(EncodeHintType.CHARACTER_SET, characterSet)
}

/** The module matrix — pure ZXing, no Android, so it is directly unit-testable. */
internal fun btQrMatrix(
    data: String,
    sizePx: Int,
    errorCorrection: ErrorCorrectionLevel,
    characterSet: String?,
): BitMatrix = QRCodeWriter().encode(
    data,
    BarcodeFormat.QR_CODE,
    sizePx,
    sizePx,
    btQrHints(errorCorrection, characterSet),
)

private fun encodeQr(
    data: String,
    sizePx: Int,
    errorCorrection: ErrorCorrectionLevel,
    characterSet: String?,
): Bitmap {
    val matrix = btQrMatrix(data, sizePx, errorCorrection, characterSet)
    val w = matrix.width
    val h = matrix.height
    val bmp = createBitmap(w, h)
    val row = IntArray(w)
    for (y in 0 until h) {
        for (x in 0 until w) {
            row[x] = if (matrix[x, y]) QR_DARK else QR_LIGHT
        }
        bmp.setPixels(row, 0, w, 0, y, w, 1)
    }
    return bmp
}
