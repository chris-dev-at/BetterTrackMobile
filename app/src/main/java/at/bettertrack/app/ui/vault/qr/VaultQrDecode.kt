package at.bettertrack.app.ui.vault.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * The QR decoder, kept free of CameraX and of Android.
 *
 * Separating it from [VaultQrImageAnalyzer] is what lets `VaultQrRoundTripTest`
 * render the real §13 payload through the real encoder and read it back through
 * the real decoder — the two ends of the transfer, proven to agree, in a plain
 * JVM unit test with no device and no camera.
 *
 * **zxing-core, not ML Kit**: ML Kit's barcode model ships through Google Play
 * services, and the `github` flavor is the Play-services-free build. ZXing is
 * pure Java, already on the classpath as the QR *encoder*, and therefore reads
 * exactly what it writes.
 */
private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
    // A phone pointed at another phone's screen is a hard read — moiré, glare, a
    // slight angle. TRY_HARDER costs a few ms per frame and buys a noticeably
    // faster lock, which shortens the window in which the secret is on display.
    DecodeHintType.TRY_HARDER to true,
)

/**
 * Decode one greyscale frame, or `null` when it holds no readable QR code.
 *
 * Two binarizers on purpose: [HybridBinarizer] is the right default for camera
 * frames with uneven lighting, while [GlobalHistogramBinarizer] wins on the
 * high-contrast, evenly-lit case this flow is actually made of — one phone
 * screen photographed by another. The cheap second pass only runs when the first
 * misses, so it costs nothing on the frame that succeeds.
 *
 * @param data the luma plane.
 * @param dataWidth the row stride of [data] (may exceed [cropWidth]).
 * @param cropWidth the visible width inside each row.
 * @param cropHeight the visible number of rows.
 */
internal fun decodeQrLuminance(
    data: ByteArray,
    dataWidth: Int,
    dataHeight: Int,
    cropWidth: Int,
    cropHeight: Int,
): String? {
    if (dataWidth <= 0 || dataHeight <= 0 || cropWidth <= 0 || cropHeight <= 0) return null
    if (cropWidth > dataWidth || cropHeight > dataHeight) return null
    if (data.size < dataWidth * dataHeight) return null
    val source = PlanarYUVLuminanceSource(
        data,
        dataWidth,
        dataHeight,
        0,
        0,
        cropWidth,
        cropHeight,
        false,
    )
    val reader = QRCodeReader()
    for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
        val text = runCatching {
            reader.decode(BinaryBitmap(binarizer), DECODE_HINTS).text
        }.getOrNull()
        reader.reset()
        if (text != null) return text
    }
    return null
}
