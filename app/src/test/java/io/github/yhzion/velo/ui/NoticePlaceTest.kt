package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // #169: several notices under a one-line pane. In px of the Velvet's column in landscape with the keyboard up:
    // the pane from 0 to 88, the key bar from 228. A notice is 130 tall and the gap 22.
    private val keyBar = 228

    @Test
    fun twoOrThreeNoticesUnderAOneLinePaneGoSideBySide() {
        // Two (282) and three (about 440) stacked would run into the key bar, even from the column's top.
        assertEquals(NoticeArrangement.Row, noticeArrangement(paneTop = 0, paneBottom = 88, keyBarTop = keyBar, stack = 130 * 2 + 22, count = 2, padding = padding, lift = 0))
        assertEquals(NoticeArrangement.Row, noticeArrangement(0, 88, keyBar, 125 + 130 * 2 + 22 * 2, 3, padding, 0))
    }

    @Test
    fun oneNoticeNeverGoesSideBySide() {
        // However tall: one notice keeps the #165 place, from the pane's top down to the column's bottom.
        assertEquals(NoticeArrangement.Stack, noticeArrangement(0, 88, keyBar, 130, 1, padding, 0))
        assertEquals(NoticeArrangement.Stack, noticeArrangement(0, 88, keyBar, 400, 1, padding, 0))
    }

    @Test
    fun noticesThatAPaneHoldsStayStacked() {
        // Portrait, with or without the keyboard: the pane holds the stack at its lower edge.
        assertEquals(NoticeArrangement.Stack, noticeArrangement(0, 1900, 2000, 125 + 130 * 2 + 22 * 2, 3, padding, lift))
    }

    @Test
    fun aStackThatEndsAboveTheKeyBarFromThePanesTopStaysStackedAboveIt() {
        // A short pane, but the stack ends padding above the key bar when it starts at the pane's top.
        assertEquals(NoticeArrangement.AboveKeyBar, noticeArrangement(0, 88, keyBarTop = 282 + padding, stack = 282, count = 2, padding = padding, lift = 0))
        assertEquals(NoticeArrangement.Row, noticeArrangement(0, 88, keyBarTop = 282 + padding - 1, stack = 282, count = 2, padding = padding, lift = 0))
    }

    @Test
    fun aStackAboveTheKeyBarNeverGoesOverTheSearchBar() {
        // A short pane under the search bar (from 55 px): the stack would fit from the column's top, but not from the
        // pane's. Stacked above the key bar it would start over the search field, so the notices go side by side.
        assertEquals(NoticeArrangement.Row, noticeArrangement(paneTop = 55, paneBottom = 143, keyBarTop = 282 + padding, stack = 282, count = 2, padding = padding, lift = 0))
        assertEquals(NoticeArrangement.AboveKeyBar, noticeArrangement(55, 143, 55 + 282 + padding, 282, 2, padding, 0))
    }

    @Test
    fun aRowThatHoldsTheNoticesKeepsTheirOwnWidths() {
        assertEquals(listOf(495, 975), noticeRowWidths(own = listOf(495, 975), space = 2075, gap = 22, least = 440))
        // Exactly the room: own widths still.
        assertEquals(listOf(500, 500), noticeRowWidths(listOf(500, 500), 1022, 22, 440))
    }

    @Test
    fun aRowTooNarrowCutsTheWidestToAnEqualShare() {
        // The Velvet's own widths in landscape with the keyboard up: Undo 486, toast 976 and error notice 611 px
        // (its text and the Snackbar's padding), 2073 of own width in 2031 of room. The toast alone is cut.
        val widths = noticeRowWidths(listOf(486, 976, 611), 2075, 22, 440)!!
        assertEquals(listOf(486, 934, 611), widths)
        // Two wide ones share what the narrow one leaves.
        assertEquals(listOf(300, 840, 840), noticeRowWidths(listOf(300, 1500, 1200), 2024, 22, 200))
        assertTrue(widths.sum() + 22 * 2 <= 2075)
    }

    @Test
    fun aColumnWithoutRoomForAShareEachHasNoRow() {
        // Three shares of 440 need 1364 with the gaps.
        assertNull(noticeRowWidths(listOf(600, 600, 600), 1363, 22, 440))
        assertEquals(listOf(440, 440, 440), noticeRowWidths(listOf(600, 600, 600), 1364, 22, 440))
    }
}
