package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rail's clock follows the system's font scale only as far as its widest text still fits the rail ([clockFontScale]). */
class RailClockTest {

    @Test
    fun belowTheLimitTheClockUsesTheSystemsScale() {
        assertEquals(1f, clockFontScale(fontScale = 1f, limit = 1.4f), 0f)
        assertEquals(1.3f, clockFontScale(fontScale = 1.3f, limit = 1.4f), 0f)
    }

    @Test
    fun aboveTheLimitTheClockStopsAtTheLimit() {
        assertEquals(1.4f, clockFontScale(fontScale = 2f, limit = 1.4f), 0f)
    }

    @Test
    fun atTheLimitBothAgree() {
        assertEquals(1.4f, clockFontScale(fontScale = 1.4f, limit = 1.4f), 0f)
    }
}
