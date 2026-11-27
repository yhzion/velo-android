package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun aRowNotMovedIsAtRest() {
        val clock = RowMotionClock(quietMs = 120)
        assertEquals(0L, clock.quietIn(5_000))
        assertFalse(clock.moving)
    }
}
