package at.bettertrack.app.ui.vault.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * The camera → text half of the receiver leg: a CameraX [ImageAnalysis.Analyzer]
 * that hands each frame's luma plane to [decodeQrLuminance].
 *
 * This file holds only the CameraX-shaped glue; the decoding itself lives in
 * `VaultQrDecode.kt` so it stays testable without a device (see its KDoc for why
 * the reader is zxing-core and not ML Kit).
 */
internal class VaultQrImageAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val text = decodeQrLuminance(
                data = image.luminanceBytes() ?: return,
                dataWidth = image.luminanceRowStride(),
                dataHeight = image.height,
                cropWidth = image.width,
                cropHeight = image.height,
            )
            if (text != null) onDecoded(text)
        } catch (_: Throwable) {
            // A decoder must never take the camera down. Frames are disposable:
            // the next one arrives in ~33 ms.
        } finally {
            image.close()
        }
    }
}

/**
 * The Y (luma) plane as one contiguous byte array, row stride included.
 *
 * YUV_420_888's luma plane is byte-per-pixel with `pixelStride == 1` on every
 * device that matters, but the format's contract permits otherwise, so the
 * padded case is compacted rather than assumed away — a wrong stride does not
 * fail loudly, it simply never decodes, which is the worst kind of bug to chase
 * on a phone.
 */
private fun ImageProxy.luminanceBytes(): ByteArray? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    buffer.rewind()
    if (plane.pixelStride == 1) {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
    val rowStride = plane.rowStride
    val out = ByteArray(width * height)
    val row = ByteArray(rowStride)
    var offset = 0
    for (y in 0 until height) {
        val available = minOf(rowStride, buffer.remaining())
        if (available <= 0) break
        buffer.get(row, 0, available)
        var x = 0
        var i = 0
        while (x < width && i < available) {
            out[offset + x] = row[i]
            x++
            i += plane.pixelStride
        }
        offset += width
    }
    return out
}

/** With a compacted plane the effective stride is the image width. */
private fun ImageProxy.luminanceRowStride(): Int =
    planes.firstOrNull()?.let { if (it.pixelStride == 1) it.rowStride else width } ?: width
