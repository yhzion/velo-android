package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneStillTest {
    @Test fun theFirstChangeStartsAMotionAndTheNextOnesDoNot() {
        val clock = RowMotionClock(quietMs = 120)
        assertFalse(clock.moving)
        assertTrue(clock.changed(1_000))
        assertTrue(clock.moving)
        assertFalse(clock.changed(1_016))
        assertFalse(clock.changed(1_032))
        assertTrue(clock.moving)
    }

    @Test fun eachChangeMovesTheEndOfTheMotionOn() {
        val clock = RowMotionClock(quietMs = 120)
        clock.changed(1_000)
        assertEquals(120L, clock.quietIn(1_000))
        clock.changed(1_100)
        // Quiet since 1 100: the motion ends at 1 220, not at 1 120.
        assertEquals(100L, clock.quietIn(1_120))
        assertTrue(clock.moving)
    }

    @Test fun theMotionEndsOnceTheRowWasQuietForTheWholeTime() {
        val clock = RowMotionClock(quietMs = 120)
        clock.changed(1_000)
        assertEquals(0L, clock.quietIn(1_120))
        assertFalse(clock.moving)
        // The next change starts another motion.
        assertTrue(clock.changed(2_000))
    }

    @Test fun aStillIsDrawnAgainWhileThePaneTakesNoFrame() {
        val last = LastStill<String>()
        assertNull(last.unchanged(0, 10 to 20))
        last.keep("copy", 7, 10 to 20)
        assertEquals("copy", last.unchanged(7, 10 to 20))
        // A frame came: copy again.
        assertNull(last.unchanged(8, 10 to 20))
        // Another scale (the grid, the drum): another size.
        assertNull(last.unchanged(7, 12 to 24))
    }

    @Test fun aPaneWithoutSwapsOrAForgottenStillIsCopiedAgain() {
        val last = LastStill<String>()
        last.keep("copy", -1, 10 to 20)
        // No pane to ask (the view lost its block): never the same.
        assertNull(last.unchanged(-1, 10 to 20))
        last.keep("copy", 3, 10 to 20)
        last.forget()
        assertNull(last.unchanged(3, 10 to 20))
    }

    @Test fun aRowNotMovedIsAtRest() {
        val clock = RowMotionClock(quietMs = 120)
        assertEquals(0L, clock.quietIn(5_000))
        assertFalse(clock.moving)
    }
}
