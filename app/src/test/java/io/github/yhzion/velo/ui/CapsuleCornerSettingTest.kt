package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapsuleCornerSettingTest {

    @Test
    fun pickingAnotherCornerSavesIt() {
        assertEquals(CapsuleCorner.TopRight, pickedCorner(CapsuleCorner.TopRight, shown = CapsuleCorner.BottomLeft))
        assertEquals(CapsuleCorner.BottomLeft, pickedCorner(CapsuleCorner.BottomLeft, shown = CapsuleCorner.BottomRight))
    }

    @Test
    fun pickingTheShownCornerSavesNothing() {
        // A user who never chose and picks the default keeps following the default.
        assertNull(pickedCorner(DefaultCapsuleCorner, shown = shownCorner(null)))
        for (corner in CapsuleCorner.entries) assertNull(pickedCorner(corner, shown = corner))
    }
}
