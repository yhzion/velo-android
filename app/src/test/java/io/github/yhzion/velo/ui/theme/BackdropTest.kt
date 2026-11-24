package io.github.yhzion.velo.ui.theme

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackdropTest {
    private val screen = IntRect(0, 0, 2448, 1848)

    @Test fun aViewThatCoversTheTargetFillsTheBitmapWithWhatIsUnderTheTarget() {
        val target = IntRect(800, 400, 1600, 1200)
        val part = backdropPart(target, view = screen, bufferWidth = 2448, bufferHeight = 1848, scale = 8)!!
        assertEquals(target, part.source)
        assertEquals(IntRect(0, 0, 100, 100), part.dest)
    }

    @Test fun aViewBesideTheTargetGivesNothing() {
        assertNull(backdropPart(IntRect(0, 0, 400, 400), view = IntRect(400, 0, 800, 400), bufferWidth = 400, bufferHeight = 400, scale = 8))
        assertNull(backdropPart(IntRect(0, 0, 400, 400), view = IntRect(0, 0, 0, 0), bufferWidth = 0, bufferHeight = 0, scale = 8))
    }

    @Test fun aViewUnderOneCornerLandsInThatCornerOfTheBitmap() {
        // The target's right half and lower half are over the view.
        val target = IntRect(1000, 1000, 1800, 1800)
        val view = IntRect(1400, 1400, 2400, 1848)
        val part = backdropPart(target, view, bufferWidth = 1000, bufferHeight = 448, scale = 8)!!
        assertEquals(IntRect(0, 0, 400, 400), part.source)
        assertEquals(IntRect(50, 50, 100, 100), part.dest)
    }

    @Test fun aBufferSmallerThanItsViewIsReadInItsOwnPixels() {
        // A surface view of 2448x1848 px that draws into a buffer of half that size.
        val part = backdropPart(IntRect(800, 400, 1600, 1200), view = screen, bufferWidth = 1224, bufferHeight = 924, scale = 8)!!
        assertEquals(IntRect(400, 200, 800, 600), part.source)
        assertEquals(IntRect(0, 0, 100, 100), part.dest)
    }

    @Test fun aSliverStillCopiesAtLeastOnePixel() {
        val part = backdropPart(IntRect(0, 0, 800, 800), view = IntRect(797, 0, 2000, 800), bufferWidth = 1203, bufferHeight = 800, scale = 8)!!
        assertEquals(IntRect(0, 0, 3, 800), part.source)
        assertEquals(IntRect(99, 0, 100, 100), part.dest)
    }

    @Test fun theBitmapHoldsTheWholeTarget() {
        assertEquals(IntSize(100, 100), backdropSize(IntRect(0, 0, 800, 800), scale = 8))
        assertEquals(IntSize(101, 13), backdropSize(IntRect(10, 10, 811, 110), scale = 8))
        assertEquals(IntSize(1, 1), backdropSize(IntRect(0, 0, 3, 3), scale = 8))
    }

    private val window = IntSize(2448, 1848)
    private val menu = IntSize(640, 500)
    private val margin = 18

    @Test fun aMenuOpensUnderItsAnchorFromTheAnchorsStart() {
        val anchor = IntRect(300, 200, 408, 308)
        assertEquals(IntOffset(300, 308 + margin), menuPosition(anchor, menu, window, margin))
    }

    @Test fun aMenuThatDoesNotFitBelowOpensAbove() {
        val anchor = IntRect(300, 1600, 408, 1708)
        assertEquals(IntOffset(300, 1600 - margin - 500), menuPosition(anchor, menu, window, margin))
    }

    @Test fun aMenuAtTheRightEdgeStaysInTheWindow() {
        // The rail's "…" button: its menu ends at the window's margin, not past it.
        val anchor = IntRect(2320, 300, 2428, 408)
        assertEquals(IntOffset(2448 - margin - 640, 408 + margin), menuPosition(anchor, menu, window, margin))
    }

    @Test fun aMenuTallerThanBothSidesStaysInTheWindow() {
        val tall = IntSize(640, 1700)
        val at = menuPosition(IntRect(300, 900, 408, 1008), tall, window, margin)
        assertEquals(IntOffset(300, 1848 - margin - 1700), at)
    }

    @Test fun aMenuLargerThanTheWindowStartsAtItsMargin() {
        val at = menuPosition(IntRect(300, 900, 408, 1008), IntSize(3000, 2000), window, margin)
        assertEquals(IntOffset(margin, margin), at)
    }
}
