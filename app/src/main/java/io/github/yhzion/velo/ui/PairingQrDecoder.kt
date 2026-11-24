package io.github.yhzion.velo.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import java.nio.ByteBuffer

/** Confined to the analyzer thread; the luminance buffers are reused between frames. */
internal class PairingQrDecoder {
    private val reader = QRCodeMultiReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    private var pixels = ByteArray(0)
    private var row = ByteArray(0)

    fun decode(source: PlanarYUVLuminanceSource): List<String> {
        return try {
            // Keep every QR in the frame so a non-pairing QR cannot mask a valid one.
            reader.decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints).map { it.text }
        } catch (_: NotFoundException) {
            emptyList()
        }
    }

    /** Packs the crop in sensor coordinates and applies CameraX's clockwise rotation. */
    fun luminanceSource(
        plane: ByteBuffer, width: Int, height: Int, rowStride: Int,
        left: Int, top: Int, right: Int, bottom: Int, rotation: Int,
    ): PlanarYUVLuminanceSource? {
        if (left < 0 || top < 0 || right <= left || bottom <= top ||
            right > width || bottom > height || rowStride < width ||
            rotation !in 0..270 || rotation % 90 != 0
        ) return null
        // A camera plane may end at the last pixel, without padding after its last row.
        if ((bottom - 1).toLong() * rowStride + right > plane.remaining()) return null
        val cropWidth = right - left
        val cropHeight = bottom - top
        val size = cropWidth * cropHeight
        if (pixels.size != size) pixels = ByteArray(size)
        val originalPosition = plane.position()
        val base = originalPosition + top * rowStride + left
        try {
            if (rotation == 0) {
                if (cropWidth == rowStride) {
                    plane.position(base)
                    plane.get(pixels, 0, size)
                } else {
                    for (y in 0 until cropHeight) {
                        plane.position(base + y * rowStride)
                        plane.get(pixels, y * cropWidth, cropWidth)
                    }
                }
            } else {
                if (row.size < cropWidth) row = ByteArray(cropWidth)
                val step = when (rotation) {
                    90 -> cropHeight
                    180 -> -1
                    else -> -cropHeight
                }
                for (y in 0 until cropHeight) {
                    plane.position(base + y * rowStride)
                    plane.get(row, 0, cropWidth)
                    var dest = when (rotation) {
                        90 -> cropHeight - 1 - y
                        180 -> size - 1 - y * cropWidth
                        else -> (cropWidth - 1) * cropHeight + y
                    }
                    for (x in 0 until cropWidth) {
                        pixels[dest] = row[x]
                        dest += step
                    }
                }
            }
        } finally {
            plane.position(originalPosition)
        }
        val sideways = rotation == 90 || rotation == 270
        val outWidth = if (sideways) cropHeight else cropWidth
        val outHeight = if (sideways) cropWidth else cropHeight
        return PlanarYUVLuminanceSource(pixels, outWidth, outHeight, 0, 0, outWidth, outHeight, false)
    }
}
