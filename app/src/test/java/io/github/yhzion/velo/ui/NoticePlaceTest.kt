package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticePlaceTest {
    // Pixels at the Velvet's density (2.75): padding 16 dp, a notice 48 dp, the capsule row's lift 58 dp.
    private val padding = 44
    private val notice = 132
    private val lift = 160

    @Test
    fun aTallPaneHoldsTheNoticesAtItsLowerEdge() {
        // Portrait without the keyboard: the pane area runs from 0 to 1900 of its column.
        assertEquals(1900 - padding - notice, noticeTop(paneTop = 0, paneBottom = 1900, notices = notice, padding = padding, lift = 0))
        // Over the capsules at a bottom corner, as before.
        assertEquals(1900 - padding - lift - notice, noticeTop(0, 1900, notice, padding, lift))
    }

    @Test
    fun aPaneThatJustHoldsTheNoticesStillHasThemAtItsLowerEdge() {
        val height = notice + padding * 2
        assertEquals(padding, noticeTop(0, height, notice, padding, 0))
        assertEquals(50 + padding, noticeTop(50, 50 + height, notice, padding, 0))
    }

    @Test
    fun aPaneTooShortForTheNoticesHasThemFromItsTopDown() {
        // Landscape with the keyboard up: the pane is one line (88 px) and the caption and key bar lie below it.
        assertEquals(19 + padding, noticeTop(paneTop = 19, paneBottom = 107, notices = notice, padding = padding, lift = 0))
        // Two notices that a pane holds one of: the whole stack goes down from the top, not one squeezed in.
        assertEquals(padding, noticeTop(0, notice + padding * 2 + 1, notice * 2, padding, 0))
    }

    @Test
    fun theCapsulesLiftCountsInWhetherThePaneHoldsTheNotices() {
        val height = notice + padding * 2
        assertEquals(padding, noticeTop(0, height + lift, notice, padding, lift))
        assertEquals(padding, noticeTop(0, height + lift - 1, notice, padding, lift))
        assertEquals(padding + 1, noticeTop(0, height + lift + 1, notice, padding, lift))
    }
}
