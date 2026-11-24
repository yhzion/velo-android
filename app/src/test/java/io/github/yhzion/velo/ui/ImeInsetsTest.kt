package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The room the app makes for the input method ([imeRoom], #325): none for an inset that covers the whole window. */
class ImeInsetsTest {

    @Test
    fun anInputMethodAsTallAsTheWindowTakesNoRoom() {
        // The emulator's LatinIME with a hardware keyboard: frame [0,0][720,1280] over a 720x1280 window.
        assertEquals(0, imeRoom(imeBottom = 1280, windowHeight = 1280))
        assertEquals(0, imeRoom(imeBottom = 1848, windowHeight = 1848))
        assertEquals(0, imeRoom(imeBottom = 1300, windowHeight = 1280))
    }

    @Test
    fun aKeyboardKeepsItsRoom() {
        assertEquals(800, imeRoom(imeBottom = 800, windowHeight = 1280))
        assertEquals(1279, imeRoom(imeBottom = 1279, windowHeight = 1280))
        assertEquals(0, imeRoom(imeBottom = 0, windowHeight = 1280))
    }

    @Test
    fun onlyAnInsetThatReachesTheWindowsHeightCoversIt() {
        assertEquals(true, wholeWindow(imeBottom = 1280, windowHeight = 1280))
        assertEquals(false, wholeWindow(imeBottom = 1279, windowHeight = 1280))
        assertEquals(false, wholeWindow(imeBottom = 0, windowHeight = 0))
    }

    @Test
    fun aWindowOfUnknownHeightKeepsTheInset() {
        // A WindowInfo that knows no window yet (or a test's) reports 0 x 0.
        assertEquals(800, imeRoom(imeBottom = 800, windowHeight = 0))
    }
}
