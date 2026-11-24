package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionHandlesTest {
    private val handles = SelectionHandles()

    @Test fun closestKnobWinsEvenWhereHitTargetsOverlap() {
        handles.place(intArrayOf(30, 40, 40, 40), 100, 100, 1f)
        assertEquals(SelectionEnd.Start, handles.hit(28f, 48f))
        assertEquals(SelectionEnd.End, handles.hit(42f, 48f))
        assertEquals(null, handles.hit(80f, 80f))
    }

    @Test fun clippedKnobsRemainGrabbableInsideThePane() {
        handles.place(intArrayOf(0, 10, 100, 90), 100, 90, 1f)
        assertEquals(SelectionEnd.Start, handles.hit(8f, 18f))
        assertEquals(SelectionEnd.End, handles.hit(92f, 82f))
    }

    @Test fun draggingFromOffCenterMovesTheOriginalCellByFingerDisplacement() {
        handles.place(intArrayOf(30, 40, 60, 80), 150, 150, 1f)
        handles.grab(SelectionEnd.End, 72f, 93f)
        // Right/bottom are exclusive. No displacement hits inside the original end cell.
        assertEquals(59f, handles.cellX(72f), 0.01f)
        assertEquals(79f, handles.cellY(93f), 0.01f)
        assertEquals(49f, handles.cellX(62f), 0.01f)
        assertEquals(99f, handles.cellY(113f), 0.01f)
        handles.grab(SelectionEnd.Start, 18f, 52f)
        assertEquals(31f, handles.cellX(18f), 0.01f)
        assertEquals(39f, handles.cellY(52f), 0.01f)
    }
}
