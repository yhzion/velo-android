package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticePlaceTest {
    // Pixels at the Velvet's density (2.75): padding 16 dp, a notice 48 dp, the capsule row's lift 58 dp.
    private val padding = 44
    private val notice = 132
    private val lift = 160
    // A column that holds any stack these tests make: only the last test's column is short.
    private val TALL = 4000

    @Test
    fun aTallPaneHoldsTheNoticesAtItsLowerEdge() {
        // Portrait without the keyboard: the pane area runs from 0 to 1900 of its column.
        assertEquals(1900 - padding - notice, noticeTop(paneTop = 0, paneBottom = 1900, column = 2200, notices = notice, padding = padding, lift = 0))
        // Over the capsules at a bottom corner, as before.
        assertEquals(1900 - padding - lift - notice, noticeTop(0, 1900, TALL, notice, padding, lift))
    }

    @Test
    fun aPaneThatJustHoldsTheNoticesStillHasThemAtItsLowerEdge() {
        val height = notice + padding * 2
        assertEquals(padding, noticeTop(0, height, TALL, notice, padding, 0))
        assertEquals(50 + padding, noticeTop(50, 50 + height, TALL, notice, padding, 0))
    }

    @Test
    fun aPaneTooShortForTheNoticesHasThemFromItsTopDown() {
        // Landscape with the keyboard up: the pane is one line (88 px) and the caption and key bar lie below it.
        assertEquals(19 + padding, noticeTop(paneTop = 19, paneBottom = 107, column = 4000, notices = notice, padding = padding, lift = 0))
        // Two notices that a pane holds one of: the whole stack goes down from the top, not one squeezed in.
        assertEquals(padding, noticeTop(0, notice + padding * 2 + 1, TALL, notice * 2, padding, 0))
    }

    @Test
    fun theCapsulesLiftCountsInWhetherThePaneHoldsTheNotices() {
        val height = notice + padding * 2
        assertEquals(padding, noticeTop(0, height + lift, TALL, notice, padding, lift))
        assertEquals(padding, noticeTop(0, height + lift - 1, TALL, notice, padding, lift))
        assertEquals(padding + 1, noticeTop(0, height + lift + 1, TALL, notice, padding, lift))
    }

    @Test
    fun aStackTallerThanTheSpaceUnderAShortPaneMovesUpToEndInsideTheColumn() {
        // Landscape with the keyboard up on the Velvet: the column ends at the keyboard (349 px), the pane is one
        // line from 19. Undo, toast and error notice (143 + 130 + 130 and two gaps of 22) are taller than the whole
        // column: they start at its top, and only the last runs past it.
        val stack = 143 + 130 + 130 + 22 * 2
        assertEquals(0, noticeTop(paneTop = 19, paneBottom = 107, column = 349, notices = stack, padding = padding, lift = 0))
        // A stack that ends inside the column from the pane's top down stays there, as before.
        assertEquals(19 + padding, noticeTop(19, 107, 349, 349 - padding - 19 - padding, padding, 0))
        // One that would end past the column's bottom moves up just enough to end padding above it.
        assertEquals(349 - padding - 260, noticeTop(19, 107, 349, 260, padding, 0))
    }
}
