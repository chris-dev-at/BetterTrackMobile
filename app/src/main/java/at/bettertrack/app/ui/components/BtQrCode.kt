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
import com.google.zxing.qrcode.QRCodeWriter

/**
 * An offline QR code for the 2FA authenticator (TOTP) enrollment URI (spec §6.12).
 * Encoded on-device with ZXing (no network / no external assets). Rendered on a
 * WHITE rounded card with near-black modules — QR readers need a light quiet-zone,
 * so this deliberately breaks the dark theme for scannability, framed as a card.
 */
@Composable
fun BtQrCode(
    data: String,
    modifier: Modifier = Modifier,
    size: Dp = 208.dp,
) {
    val pxDensity = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(pxDensity) { size.roundToPx() }.coerceAtLeast(160)

    val painter = remember(data, sizePx) {
        runCatching { encodeQr(data, sizePx) }
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

private const val QR_DARK = 0xFF0B0E14.toInt() // brand near-black modules
private const val QR_LIGHT = 0xFFFFFFFF.toInt()

private fun encodeQr(data: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
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
