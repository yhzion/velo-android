package io.github.yhzion.velo.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrDecoderTest {
    private val decoder = PairingQrDecoder()
    private val valid = "https://workstation.example"
    private val invalid = "workstation.example"

    @Test fun cropsPaddedRowsAndRotatesClockwiseWithoutMovingThePlanePosition() {
        val plane = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9)).apply { position(1) }
        val cases = listOf(
            Triple(0, 2, byteArrayOf(2, 3, 5, 6, 8, 9)),
            Triple(90, 3, byteArrayOf(8, 5, 2, 9, 6, 3)),
            Triple(180, 2, byteArrayOf(9, 8, 6, 5, 3, 2)),
            Triple(270, 3, byteArrayOf(3, 6, 9, 2, 5, 8)),
        )
        for ((rotation, expectedWidth, expectedPixels) in cases) {
            val source = requireNotNull(decoder.luminanceSource(plane, 3, 3, 4, 1, 0, 3, 3, rotation))
            assertEquals(expectedWidth, source.width)
            assertEquals(expectedPixels.size / expectedWidth, source.height)
            assertArrayEquals("rotation $rotation", expectedPixels, source.matrix)
            assertEquals(1, plane.position())
        }
    }

    @Test fun readsOffsetCropsAtAllFourRotations() {
        val frame = LumaFrame().apply {
            draw(valid, BarcodeFormat.QR_CODE, 24, 18, 120, 120)
            draw(invalid, BarcodeFormat.QR_CODE, 186, 44, 120, 120)
        }
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals("left crop at $rotation", listOf(valid), frame.decode(8, 6, 154, 148, rotation))
            assertEquals("right crop at $rotation", listOf(invalid), frame.decode(175, 30, 320, 176, rotation))
            assertEquals("empty crop at $rotation", emptyList<String>(), frame.decode(0, 0, 18, 170, rotation))
        }
    }

    @Test fun decodesAfterCameraReleasesThePlane() {
        val frame = LumaFrame().apply { draw(valid, BarcodeFormat.QR_CODE, 24, 18, 120, 120) }
        val source = requireNotNull(frame.copyLuminance())
        frame.plane.limit(frame.plane.position())
        assertEquals(listOf(valid), decoder.decode(source))
    }

    @Test fun ignoresOtherBarcodeFormats() {
        val frame = LumaFrame().apply { draw("123456789", BarcodeFormat.CODE_128, 12, 30, 280, 130) }
        assertEquals(emptyList<String>(), frame.decode())
    }

    @Test fun ignoresTruncatedLuminanceData() {
        val frame = LumaFrame().apply { draw(valid, BarcodeFormat.QR_CODE, 24, 18, 120, 120) }
        // The QR is intact, but the last requested pixel is missing and stale capacity is readable.
        frame.plane.limit(frame.plane.limit() - 1)
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(emptyList<String>(), frame.decode(rotation = rotation))
        }
    }

    @Test fun passesAllCodesToTheExistingPairingValidation() {
        val frame = LumaFrame().apply {
            draw(invalid, BarcodeFormat.QR_CODE, 24, 18, 120, 120)
            draw(valid, BarcodeFormat.QR_CODE, 186, 44, 120, 120)
        }
        val payloads = frame.decode()
        assertEquals(setOf(invalid, valid), payloads.toSet())
        val scanned = RemoteAddressInput().scan(payloads)
        assertEquals(valid, scanned.text)
        assertEquals(valid, scanned.acceptedUrl)
        assertNull(scanned.problem)
    }

    @Test fun rejectsNonHttpsQrWithoutSubmittingOrReplacingTheDraft() {
        val frame = LumaFrame().apply { draw(invalid, BarcodeFormat.QR_CODE, 24, 18, 120, 120) }
        val draft = "https://draft.example"
        val scanned = RemoteAddressInput(draft).scan(frame.decode(rotation = 90))
        assertEquals(draft, scanned.text)
        assertNull(scanned.acceptedUrl)
        assertEquals("Scan a QR code that holds an HTTPS address", scanned.problem)
    }

    private inner class LumaFrame {
        private val width = 320
        private val height = 176
        private val stride = 336
        private val offset = 7
        private val sampleEnd = offset + stride * (height - 1) + width
        val plane: ByteBuffer = ByteBuffer.allocateDirect(sampleEnd + 12).apply {
            for (y in 0 until height) {
                for (x in 0 until width) put(offset + y * stride + x, 255.toByte())
            }
            position(offset)
            limit(sampleEnd) // Camera buffers need not include the last row's padding.
        }

        fun draw(text: String, format: BarcodeFormat, x: Int, y: Int, w: Int, h: Int) {
            val matrix = MultiFormatWriter().encode(text, format, w, h)
            for (row in 0 until h) {
                for (column in 0 until w) {
                    if (matrix[column, row]) plane.put(offset + (y + row) * stride + x + column, 0)
                }
            }
        }

        fun decode(left: Int = 0, top: Int = 0, right: Int = width, bottom: Int = height, rotation: Int = 0): List<String> {
            return copyLuminance(left, top, right, bottom, rotation)?.let(decoder::decode).orEmpty()
        }

        fun copyLuminance(left: Int = 0, top: Int = 0, right: Int = width, bottom: Int = height, rotation: Int = 0) =
            decoder.luminanceSource(plane, width, height, stride, left, top, right, bottom, rotation)
    }
}
