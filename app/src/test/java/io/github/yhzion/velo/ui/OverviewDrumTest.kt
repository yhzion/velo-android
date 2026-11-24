package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The drum the rail's window wheel turns (#304): its cards, their names, and the overview's motion on it. */
class OverviewDrumTest {
    /** About the Fold8's unfolded pane area, in px at 2.25 px per dp: 990 × 708 dp. */
    private val plain = Stage(width = 2228f, height = 1593f, cardGap = 54f, pageGap = 22.5f, labelRoom = 0f)
    private val drum = drumSpec(plain.width, plain.height, labelMin = 270f, labelMax = 562f, labelGap = 50f, margin = 158f, eye = 2925f)
    private val stage = plain.copy(drum = drum)
    private val near = 0.01f

    private fun middle(b: CardBox) = (b.left + b.right) / 2 to (b.top + b.bottom) / 2

    @Test
    fun theShownTabIsTheWholePaneAreaBeforeTheWheelOpens() {
        assertEquals(TabTransform(1f, 0f, 0f), tabTransform(3, 3, 0f, 0f, stage))
        // Its neighbours wait above and below it, a page away, as the row's wait at its sides.
        val next = tabTransform(4, 3, 0f, 0f, stage)
        assertEquals(stage.height + stage.pageGap, next.dy, near)
        assertEquals(0f, next.dx, near)
        assertEquals(-(stage.height + stage.pageGap), tabTransform(2, 3, 0f, 0f, stage).dy, near)
    }

    @Test
    fun openTheFrontCardStandsLevelInTheMiddleRightOfItsName() {
        val t = tabTransform(3, 3, 1f, 0f, stage)
        assertEquals(1f, t.squash, 0f)
        assertEquals(1f, t.alpha, 0f)
        // A third of the pane area, a fifth larger in front.
        assertEquals(1.2f / 3.15f, t.scale, 0.001f)
        val (x, y) = middle(cardBox(3, 3, 1f, 0f, stage))
        assertEquals(stage.height / 2, y, 1f)
        assertEquals(stage.width / 2 + drum.shift, x, 1f)
        // The card and its name stand centred together.
        val box = cardBox(3, 3, 1f, 0f, stage)
        val name = drumLabelAt(box, drum)
        assertEquals(stage.width / 2, (name.x + box.right) / 2, 1f)
    }

    @Test
    fun laterTabsStandBelowPressedSmallerAndDimmer() {
        val front = cardBox(3, 3, 1f, 0f, stage)
        val next = tabTransform(4, 3, 1f, 0f, stage)
        val nextBox = cardBox(4, 3, 1f, 0f, stage)
        val before = tabTransform(2, 3, 1f, 0f, stage)
        assertTrue(middle(nextBox).second > middle(front).second)
        assertTrue(middle(cardBox(2, 3, 1f, 0f, stage)).second < middle(front).second)
        assertTrue(next.scale < tabTransform(3, 3, 1f, 0f, stage).scale)
        assertTrue(next.alpha < 1f)
        // Turned away, a card is pressed in height as on a drum, the same above as below.
        assertTrue(next.squash < 1f)
        assertEquals(next.squash, before.squash, 1e-5f)
        assertTrue((nextBox.bottom - nextBox.top) / (nextBox.right - nextBox.left) < (front.bottom - front.top) / (front.right - front.left))
    }

    @Test
    fun noTwoCardsOverlapOpenOrOnTheWay() {
        // A pane's surface stacks by its own order: an overlapped card would show through the one in front.
        // Those in sight on the drum, that is: the ones turned farther are black and keep no surface ([keptPlaces]).
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) for (scroll in listOf(0f, 0.3f, 0.5f, 1.7f)) {
            val seen = (0 until 9).filter { tabTransform(it, 3, 1f, scroll, stage).alpha > 0f }
            assertTrue(seen.size >= 4)
            val boxes = seen.map { cardBox(it, 3, progress, scroll, stage) }
            for (i in 0 until boxes.size - 1) assertTrue("progress $progress scroll $scroll card ${seen[i]}", boxes[i].bottom <= boxes[i + 1].top + near)
        }
    }

    @Test
    fun cardsTurnedARightAngleAwayAreOutOfSight() {
        assertEquals(0f, tabTransform(7, 3, 1f, 0f, stage).alpha, 0f)
        assertEquals(0f, tabTransform(6, 3, 1f, 0f, stage).alpha, 0f)
        assertTrue(tabTransform(5, 3, 1f, 0f, stage).alpha > 0f)
    }

    @Test
    fun fiftyTabsEveryAlphaIsANumberAndCardsThreeAwayAreGone() {
        // Many tabs, the drum turned anywhere: a card is either drawn (a number in 0..1) or gone (0) — never a
        // NaN alpha, which Compose draws anyway and which carries the card's name with it.
        for (selected in listOf(0, 7, 25, 49)) for (k in 0 until 200) {
            val scroll = k * 0.25f
            for (index in 0 until 50) {
                val d = drumOffset(index, selected, scroll)
                val t = tabTransform(index, selected, 1f, scroll, stage)
                assertTrue("index $index selected $selected scroll $scroll: alpha ${t.alpha} is not a number", !t.alpha.isNaN())
                assertTrue("index $index selected $selected scroll $scroll: alpha ${t.alpha} out of 0..1", t.alpha in 0f..1f)
                val name = drumLabelAlpha(d) * t.alpha
                assertTrue("index $index selected $selected scroll $scroll: name alpha $name is not a number", !name.isNaN())
                assertTrue("index $index selected $selected scroll $scroll: name alpha $name out of 0..1", name in 0f..1f)
                if (abs(d) >= 3f) assertEquals("index $index selected $selected scroll $scroll: $d cards from the front must be gone", 0f, t.alpha, 0f)
            }
        }
    }

    @Test
    fun aCardTheDrumKeepsIsSeenUnlessItIsTheAnchorItOpenedOn() {
        // The drum keeps surfaces for the cards about the front and for the tab the wheel opened on. All but that
        // anchor stand within 2.5 cards of the front, so their alpha never reaches 0: a card that draws nothing
        // never keeps a surface — unless it is the anchor, which the draw covers opaque.
        for (k in (scrollBounds(50, 5, stage).start * 4).toInt()..(scrollBounds(50, 5, stage).endInclusive * 4).toInt()) {
            val scroll = k * 0.25f
            for (place in keptPlaces(50, 5, 1f, scroll, stage)) {
                if (place == 5) continue // the anchor: it may sit anywhere the wheel has turned from
                val alpha = tabTransform(place, 5, 1f, scroll, stage).alpha
                assertTrue("scroll $scroll place $place: kept card alpha $alpha", alpha > 0f)
            }
        }
    }

    @Test
    fun turningTheDrumByACardBringsTheNextOneToTheFront() {
        val turned = tabTransform(4, 3, 1f, 1f, stage)
        assertEquals(tabTransform(3, 3, 1f, 0f, stage).copy(), turned.copy())
        assertEquals(4, centredTab(12, 3, 1f, 1.4f, stage))
        assertEquals(5, centredTab(12, 3, 1f, 1.6f, stage))
        assertEquals(11, centredTab(12, 3, 1f, 20f, stage))
    }

    @Test
    fun rebuiltAroundThePickedCardNothingMoves() {
        // Turned 2.3 cards from tab 3, the picked tab 5 becomes the anchor: every card stays where it was.
        val scroll = rebasedScroll(2.3f, from = 3, to = 5, progress = 1f, stage)
        assertEquals(0.3f, scroll, 1e-5f)
        for (i in 0 until 9) assertEquals(cardBox(i, 3, 1f, 2.3f, stage).top, cardBox(i, 5, 1f, scroll, stage).top, 0.5f)
    }

    @Test
    fun theDrumTurnsByWholeCardsFromItsFirstToItsLast() {
        assertEquals(-3f..8f, scrollBounds(12, 3, stage))
        assertEquals(2f, cardScroll(5, 3, stage), 0f)
        assertEquals(1f, restingScroll(1.4f, 900f, 12, 3, stage), 0f)
        assertEquals(8f, restingScroll(9.6f, 0f, 12, 3, stage), 0f)
    }

    @Test
    fun theCardsAroundTheFrontKeepTheirSurfaces() {
        assertEquals(setOf(3, 4, 5, 6, 7), keptPlaces(12, 3, 1f, 2f, stage))
        assertEquals(setOf(0, 1, 2), keptPlaces(3, 1, 1f, 0f, stage))
    }

    @Test
    fun theNamesFadeAwayFromTheFront() {
        assertEquals(1f, drumLabelAlpha(0f), 0f)
        assertTrue(drumLabelAlpha(1f) in 0.9f..1f)
        assertTrue(drumLabelAlpha(2f) < drumLabelAlpha(1f))
        assertEquals(0f, drumLabelAlpha(4f), 0f)
    }

    @Test
    fun aNarrowPaneAreaKeepsTheNamesTheirLeastWidth() {
        // The folded screen: 457 × 763 dp.
        val folded = drumSpec(1028f, 1717f, labelMin = 270f, labelMax = 562f, labelGap = 50f, margin = 158f, eye = 2925f)
        assertTrue(folded.labelWidth >= 270f)
        val st = Stage(1028f, 1717f, 54f, 22.5f, 0f, drum = folded)
        val box = cardBox(2, 2, 1f, 0f, st)
        // The name and the card both stay in the pane area.
        assertTrue(drumLabelAt(box, folded).x >= 0f)
        assertTrue(box.right <= st.width)
    }

    @Test
    fun theRowAndTheGridAreUnchanged() {
        // Without a drum the transform keeps its old shape: no lean, fully drawn.
        val row = tabTransform(2, 3, 1f, 0f, plain)
        assertEquals(1f, row.squash, 0f)
        assertEquals(1f, row.alpha, 0f)
        assertTrue(abs(row.dy - tabTransform(3, 3, 1f, 0f, plain).dy) < near)
    }
}
