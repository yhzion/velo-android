package io.github.yhzion.velo.ui

import androidx.compose.ui.geometry.Offset
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.Feel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewMotionTest {
    /** About the Fold8's unfolded pane area, in px. */
    private val stage = Stage(width = 2200f, height = 1500f, cardGap = 54f, pageGap = 54f, labelRoom = 90f,
        noteRoom = 225f, peek = 45f, rowPad = 36f)
    private val near = 0.01f

    /** The LG Velvet's pane area in portrait, in px: 411×840 dp with the strip. */
    private val velvet = Stage(width = 1079f, height = 2205f, cardGap = 63f, pageGap = 63f, labelRoom = 115.5f,
        stripRoom = 252f, noteRoom = 262.5f, peek = 52.5f, rowPad = 42f)
    /** The same phone in landscape: 860×340 dp. */
    private val velvetLow = velvet.copy(width = 2257.5f, height = 892.5f)

    @Test fun theRowFillsTheHeightTheStripAndTheNoteLeave() {
        val own = cardBox(1, 1, 1f, 0f, velvet)
        assertEquals(0.7476f, (own.right - own.left) / velvet.width, 0.001f)
        assertEquals(velvet.stripRoom + velvet.rowPad, own.top, 1f)
        assertTrue(own.bottom <= velvet.height - velvet.noteRoom + near)
    }

    @Test fun neighboursStillPeekInAtBothSides() {
        assertTrue(cardBox(0, 1, 1f, 0f, velvet).right >= velvet.peek - near)
        assertTrue(cardBox(2, 1, 1f, 0f, velvet).left <= velvet.width - velvet.peek + near)
    }

    @Test fun aLowPaneAreaKeepsTodaysCard() {
        val own = cardBox(1, 1, 1f, 0f, velvetLow)
        assertEquals(0.55f, (own.right - own.left) / velvetLow.width, 0.001f)
        // Today's place: centred in what the strip and the name leave.
        val top = velvetLow.stripRoom + (velvetLow.height * 0.45f - velvetLow.labelRoom - velvetLow.stripRoom) / 2
        assertEquals(top, own.top, 1f)
    }

    @Test fun aRowLimitedByItsWidthIsCentredBetweenTheStripAndTheNote() {
        val tall = velvet.copy(width = 1000f, height = 3000f)
        val own = cardBox(1, 1, 1f, 0f, tall)
        assertEquals(own.top - tall.stripRoom, tall.height - tall.noteRoom - own.bottom, 1f)
    }

    @Test fun pageSwipeSettlesByDistanceOrSpeedAndCancelsOnReversal() {
        assertEquals(1, pageStep(-0.4f, 0f))
        assertEquals(-1, pageStep(0.4f, 0f))
        assertEquals(1, pageStep(-0.1f, -2f))
        assertEquals(0, pageStep(0.2f, 0f))
        assertEquals(0, pageStep(-0.6f, 2f))
        assertEquals(0, pageStep(0f, -3f))
    }

    private fun centreX(b: CardBox) = (b.left + b.right) / 2

    // ---------------------------------------------------------------- the transform (P1)

    @Test fun inTheWorkspaceTheSelectedTabFillsThePaneAreaAndItsNeighboursAreOffScreen() {
        assertEquals(TabTransform(1f, 0f, 0f), tabTransform(1, 1, 0f, 0f, stage))
        assertEquals(CardBox(0f, 0f, stage.width, stage.height), cardBox(1, 1, 0f, 0f, stage))
        assertTrue(cardBox(0, 1, 0f, 0f, stage).right < 0f)
        assertTrue(cardBox(2, 1, 0f, 0f, stage).left > stage.width)
    }

    @Test fun inTheOverviewTheSelectedCardIsCentredWithRoomForItsNameAndItsNeighboursShow() {
        val own = cardBox(1, 1, 1f, 0f, stage)
        assertEquals(stage.width / 2, centreX(own), near)
        assertTrue(own.top >= 0f)
        assertTrue(own.bottom + stage.labelRoom <= stage.height + near)
        // Same shape as the pane area: a tab is scaled as a whole.
        assertEquals(stage.width / stage.height, (own.right - own.left) / (own.bottom - own.top), near)
        val left = cardBox(0, 1, 1f, 0f, stage)
        val right = cardBox(2, 1, 1f, 0f, stage)
        assertTrue("cards do not overlap", left.right < own.left && right.left > own.right)
        assertTrue("a neighbour is seen on each side", left.right > 0f && right.left < stage.width)
    }

    @Test fun everyStepBetweenIsTheBlendOfTheTwoEnds() {
        for (index in 0..2) {
            val from = tabTransform(index, 1, 0f, 300f, stage)
            val to = tabTransform(index, 1, 1f, 300f, stage)
            for (step in 1..9) {
                val p = step / 10f
                val at = tabTransform(index, 1, p, 300f, stage)
                assertEquals(from.scale + (to.scale - from.scale) * p, at.scale, near)
                assertEquals(from.dx + (to.dx - from.dx) * p, at.dx, 0.5f)
                assertEquals(from.dy + (to.dy - from.dy) * p, at.dy, 0.5f)
            }
        }
    }

    @Test fun openingShrinksTheSelectedTabStepByStepAndBringsTheNeighbourIn() {
        val steps = (0..20).map { it / 20f }
        val scales = steps.map { tabTransform(1, 1, it, 0f, stage).scale }
        val neighbourLeft = steps.map { cardBox(2, 1, it, 0f, stage).left }
        assertTrue(scales.zipWithNext().all { (a, b) -> b < a })
        assertTrue(neighbourLeft.zipWithNext().all { (a, b) -> b < a })
    }

    @Test fun reversingTheFingersGivesTheSamePictureBack() {
        val up = (0..20).map { it / 20f }
        val down = up.reversed()
        for (index in 0..3) {
            assertEquals(up.map { tabTransform(index, 1, it, 120f, stage) }, down.map { tabTransform(index, 1, it, 120f, stage) }.reversed())
        }
    }

    // ---------------------------------------------------------------- the row of cards

    @Test fun aDragMovesTheRowOnlyInTheOverview() {
        val pitch = cardBox(2, 1, 1f, 0f, stage).left - cardBox(1, 1, 1f, 0f, stage).left
        assertEquals(centreX(cardBox(1, 1, 1f, 0f, stage)) - pitch, centreX(cardBox(1, 1, 1f, pitch, stage)), near)
        assertEquals(tabTransform(1, 1, 0f, 0f, stage), tabTransform(1, 1, 0f, pitch, stage))
    }

    @Test fun theRowStopsWithItsFirstAndItsLastCardCentred() {
        val bounds = scrollBounds(count = 5, selected = 1, stage = stage)
        assertEquals(stage.width / 2, centreX(cardBox(0, 1, 1f, bounds.start, stage)), near)
        assertEquals(stage.width / 2, centreX(cardBox(4, 1, 1f, bounds.endInclusive, stage)), near)
        assertEquals(0, centredTab(5, 1, 1f, bounds.start, stage))
        assertEquals(4, centredTab(5, 1, 1f, bounds.endInclusive, stage))
        assertEquals(1, centredTab(5, 1, 1f, 0f, stage))
        // In the workspace the row's position shows nothing: the tab in view is the centre.
        assertEquals(1, centredTab(5, 1, 0f, bounds.endInclusive, stage))
    }

    @Test fun aReleasedRowRestsWithACardInTheCentre() {
        val pitch = cardBox(2, 1, 1f, 0f, stage).left - cardBox(1, 1, 1f, 0f, stage).left
        // Let go slowly: the nearer card.
        assertEquals(0f, restingScroll(pitch * 0.4f, 0f, 5, 1, stage), near)
        assertEquals(pitch, restingScroll(pitch * 0.6f, 0f, 5, 1, stage), near)
        // Thrown: on to the next card in that direction, and one card is not skipped by a nudge.
        assertEquals(pitch, restingScroll(pitch * 0.2f, pitch * 4, 5, 1, stage), near)
        assertEquals(-pitch, restingScroll(-pitch * 0.2f, -pitch * 4, 5, 1, stage), near)
        // Never past the first or the last card.
        assertEquals(-pitch, restingScroll(-pitch, -pitch * 50, 5, 1, stage), near)
        assertEquals(3 * pitch, restingScroll(3 * pitch, pitch * 50, 5, 1, stage), near)
    }

    @Test fun centringAnotherCardBringsAnOffscreenTabIntoViewWithoutRebasingTheRow() {
        val selected = 1
        val offscreen = 3
        assertTrue(cardBox(offscreen, selected, 1f, 0f, stage).left > stage.width)

        val previous = cardScroll(offscreen - 1, selected, stage)
        assertTrue(cardBox(offscreen, selected, 1f, previous, stage).left < stage.width)

        val target = cardScroll(offscreen, selected, stage)
        assertEquals(stage.width / 2, centreX(cardBox(offscreen, selected, 1f, target, stage)), near)
        assertEquals(offscreen, centredTab(5, selected, 1f, target, stage))
        assertEquals(-target, cardScroll(selected, offscreen, stage), near)
    }

    @Test fun withoutTheConnectionOnlyTheShownTabsCardClosesTheOverview() {
        // Connected: any card selects its tab.
        assertTrue(selectsTab("b", shown = "a", connected = true))
        assertTrue(selectsTab("a", shown = "a", connected = true))
        // Down: no tab can be selected; the shown tab's own card still closes the overview on it.
        assertTrue(selectsTab("a", shown = "a", connected = false))
        assertFalse(selectsTab("b", shown = "a", connected = false))
    }

    @Test fun aTappedCardGrowsFromWhereItStands() {
        // Four tabs, the first selected, the row dragged a little; the third card is tapped.
        val scroll = 200f
        for (p in listOf(1f, 0.6f)) {
            val moved = rebasedScroll(scroll, from = 0, to = 2, progress = p, stage = stage)
            for (index in 0..3) {
                val before = cardBox(index, 0, p, scroll, stage)
                val after = cardBox(index, 2, p, moved, stage)
                assertEquals(before.left, after.left, 0.5f)
                assertEquals(before.right, after.right, 0.5f)
                assertEquals(before.top, after.top, 0.5f)
            }
            // …and at the end of the way it fills the pane area.
            assertEquals(TabTransform(1f, 0f, 0f), tabTransform(2, 2, 0f, moved, stage))
        }
    }

    @Test fun aCardHalfInViewHasItsNameUnderThePartThatShows() {
        val own = cardBox(1, 1, 1f, 0f, stage)
        assertEquals(own.left..own.right, labelSpan(own, stage))
        val left = labelSpan(cardBox(0, 1, 1f, 0f, stage), stage)!!
        assertEquals(0f, left.start)
        assertEquals(cardBox(0, 1, 1f, 0f, stage).right, left.endInclusive)
        val right = labelSpan(cardBox(2, 1, 1f, 0f, stage), stage)!!
        assertEquals(stage.width, right.endInclusive)
        // Out of view: no name hangs at the edge.
        assertNull(labelSpan(cardBox(4, 1, 1f, 0f, stage), stage))
        assertNull(labelSpan(cardBox(0, 1, 0f, 0f, stage), stage))
    }

    @Test fun aTapFindsTheCardUnderTheFingerAndNothingBetweenCards() {
        val second = cardBox(1, 1, 1f, 0f, stage)
        val third = cardBox(2, 1, 1f, 0f, stage)
        val y = (second.top + second.bottom) / 2
        assertEquals(1, cardAt(centreX(second), y, 4, 1, 1f, 0f, stage))
        assertEquals(2, cardAt(third.left + 10f, y, 4, 1, 1f, 0f, stage))
        assertNull(cardAt((second.right + third.left) / 2, y, 4, 1, 1f, 0f, stage))
        // The name under a card belongs to it.
        assertEquals(1, cardAt(centreX(second), second.bottom + stage.labelRoom / 2, 4, 1, 1f, 0f, stage))
        assertNull(cardAt(centreX(second), second.top - 5f, 4, 1, 1f, 0f, stage))
    }

    // ---------------------------------------------------------------- settling (P2)

    @Test fun aSlowReleaseSettlesToTheNearerEnd() {
        assertEquals(0f, settleTarget(0.49f, 0f))
        assertEquals(1f, settleTarget(0.51f, 0f))
        assertEquals(0f, settleTarget(0.3f, FLING_PER_SECOND * 0.5f))
        assertEquals(1f, settleTarget(0.7f, -FLING_PER_SECOND * 0.5f))
    }

    @Test fun aFastShortSwipeSettlesByItsDirection() {
        assertEquals(1f, settleTarget(0.1f, FLING_PER_SECOND * 2))
        assertEquals(0f, settleTarget(0.9f, -FLING_PER_SECOND * 2))
    }

    @Test fun progressNeverLeavesItsEnds() {
        assertEquals(1f, settleTarget(1.7f, 0f))
        assertEquals(0f, settleTarget(-0.4f, 0f))
        assertEquals(1f, swipeProgress(0.8f, -stage.height, stage))
        assertEquals(0f, swipeProgress(0.2f, stage.height, stage))
    }

    @Test fun threeFingersUpOpenAndDownCloseByTheSameWay() {
        val up = swipeProgress(0f, -200f, stage)
        assertTrue(up > 0f && up < 1f)
        assertEquals(2 * up, swipeProgress(0f, -400f, stage), near)
        assertEquals(0f, swipeProgress(up, 200f, stage), near)
        assertEquals(0f, swipeProgress(0f, 200f, stage))
    }

    @Test fun spreadingTwoFingersOnTheOverviewClosesItAndBringingThemBackReopensIt() {
        val steps = listOf(1f, 1.1f, 1.3f, 1.5f).map { spreadProgress(1f, it) }
        assertEquals(1f, steps.first())
        assertTrue(steps.zipWithNext().all { (a, b) -> b < a })
        assertEquals(0f, spreadProgress(1f, 3f))
        assertEquals(steps[1], spreadProgress(1f, 1.1f))
        // Pinching further in does not go past the overview.
        assertEquals(1f, spreadProgress(1f, 0.5f))
    }

    @Test fun theSpeedAtReleaseIsThatOfTheLastMoment() {
        val moving = ReleaseSpeed()
        for (i in 0..6) moving.add(1000L + i * 16, i * 0.05f)
        assertEquals(0.05f / 0.016f, moving.perSecond(1100L), 0.2f)
        // A slow start does not hide a fast end.
        val late = ReleaseSpeed()
        for (i in 0..20) late.add(1000L + i * 16, i * 0.001f)
        for (i in 1..6) late.add(1320L + i * 16, 0.02f + i * 0.05f)
        assertTrue(late.perSecond(1420L) > FLING_PER_SECOND)

        // Fingers that rested before they lifted have no speed left.
        val rested = ReleaseSpeed()
        for (i in 0..6) rested.add(1000L + i * 16, i * 0.05f)
        assertEquals(0f, rested.perSecond(1096L + 300L))
        val stopped = ReleaseSpeed()
        for (i in 0..6) stopped.add(1000L + i * 16, i * 0.05f)
        for (i in 1..10) stopped.add(1096L + i * 16, 0.3f)
        assertEquals(0f, stopped.perSecond(1260L), near)

        val down = ReleaseSpeed()
        for (i in 0..6) down.add(1000L + i * 16, 1f - i * 0.05f)
        assertTrue(down.perSecond(1100L) < -FLING_PER_SECOND)
        assertEquals(0f, ReleaseSpeed().perSecond(0L))
    }

    @Test fun fingersThatLiftOneAfterAnotherKeepTheSpeedTheyHadWhenTheFirstLifted() {
        fun swipe() = ReleaseSpeed().also { for (i in 0..6) it.add(1000L + i * 16, i * 0.05f) } // the last move at 1096

        val flick = swipe()
        flick.lift(1150L)
        assertTrue("the last finger leaves 140 ms after the first", flick.perSecond(1290L) > FLING_PER_SECOND)

        val rested = swipe()
        rested.lift(1096L + 300L)
        assertEquals(0f, rested.perSecond(1096L + 350L))

        // One finger lifted and the others went on, then stopped: the lift is forgotten.
        val wentOn = swipe()
        wentOn.lift(1100L)
        for (i in 1..40) wentOn.add(1100L + i * 16, 0.3f)
        assertEquals(0f, wentOn.perSecond(1100L + 41 * 16), near)
    }

    // ---------------------------------------------------------------- whose fingers (P6)

    @Test fun oneFingerInTheWorkspaceIsThePanes() {
        assertEquals(Touch.Pane, touchMode(null, fingers = 1, progress = 0f))
        assertEquals(Touch.Pane, touchMode(Touch.Pane, fingers = 1, progress = 0f))
    }

    @Test fun aSecondFingerTakesTheGestureFromThePane() {
        assertEquals(Touch.Pinch, touchMode(Touch.Pane, fingers = 2, progress = 0f))
        // One finger lifted: still the pinch, never a scroll of the terminal.
        assertEquals(Touch.Pinch, touchMode(Touch.Pinch, fingers = 1, progress = 0f))
    }

    @Test fun threeFingersAreNeverThePanesHoweverTheyLandOrLift() {
        for (before in listOf(null, Touch.Pane, Touch.Pinch, Touch.Row)) {
            assertEquals(Touch.Swipe, touchMode(before, fingers = 3, progress = 0f))
            assertEquals(Touch.Swipe, touchMode(before, fingers = 4, progress = 1f))
        }
        assertEquals(Touch.Swipe, touchMode(Touch.Swipe, fingers = 2, progress = 0f))
        assertEquals(Touch.Swipe, touchMode(Touch.Swipe, fingers = 1, progress = 0f))
    }

    @Test fun whileTheOverviewShowsAtAllNoFingerIsThePanes() {
        for (p in listOf(0.01f, 0.5f, 1f)) {
            assertEquals(Touch.Row, touchMode(null, fingers = 1, progress = p))
            assertEquals(Touch.Row, touchMode(Touch.Row, fingers = 2, progress = p))
        }
        // A second finger beside one that rests on a pane while the overview moves under it.
        assertEquals(Touch.Row, touchMode(Touch.Pane, fingers = 2, progress = 0.4f))
        // The overview closed under a finger that began on a card: it stays the row's.
        assertEquals(Touch.Row, touchMode(Touch.Row, fingers = 1, progress = 0f))
    }

    @Test fun `a pane drag keeps every finger`() {
        // A second finger cancels the drag, and the gesture stays the drag's until every finger has lifted.
        assertEquals(Touch.PaneDrag, touchMode(Touch.PaneDrag, fingers = 2, progress = 1f))
        assertEquals(Touch.PaneDrag, touchMode(Touch.PaneDrag, fingers = 3, progress = 1f))
        // The overview closed under the finger after a cancel: still no pane takes it.
        assertEquals(Touch.PaneDrag, touchMode(Touch.PaneDrag, fingers = 1, progress = 0f))
    }

    @Test fun aFingerJoinedByOthersLeavesNothingInTheTerminal() {
        val finger = FirstFinger()
        finger.down()
        assertNull("held back", finger.drag(0f, -30f))
        finger.joined()
        assertNull("no use of the pane, and nothing to scroll by", finger.alone())
        assertNull("nor by what the fingers do next", finger.drag(0f, -200f))
        assertNull(finger.alone())
    }

    @Test fun aFingerThatStaysAloneGetsItsWholeMovement() {
        val finger = FirstFinger()
        finger.down()
        assertNull(finger.drag(2f, -30f))
        assertNull(finger.drag(1f, -10f))
        assertEquals(3f to -40f, finger.alone())
        assertNull("the pane is used once", finger.alone())
        assertEquals(0f to -5f, finger.drag(0f, -5f))
        // The next touch starts afresh.
        finger.down()
        assertEquals(0f to 0f, finger.alone())
    }

    // ---------------------------------------------------------------- the pinch (P2)

    private val min = HostConnection.MIN_SCALE

    @Test fun aPinchAboveTheSmallestTextSizeOnlyChangesTheTextSize() {
        assertEquals(PinchStep(0.8f, 0f), pinchStep(1f, 0.8f))
        assertEquals(PinchStep(1.5f, 0f), pinchStep(1f, 1.5f))
        assertEquals(PinchStep(HostConnection.MAX_SCALE, 0f), pinchStep(2f, 4f))
        assertEquals(PinchStep(min, 0f), pinchStep(1f, min))
    }

    @Test fun aPinchIsFeltWhereItTurnsToTheOverviewAndBack() {
        // Down to the smallest text the pinch only scales; past it the overview starts to open.
        assertEquals(Feel.ThresholdOn, pinchFeel(PinchStep(min, 0f), PinchStep(min, 0.05f)))
        assertEquals(Feel.ThresholdOff, pinchFeel(PinchStep(min, 0.05f), PinchStep(min, 0f)))
        assertNull(pinchFeel(PinchStep(0.6f, 0.1f), PinchStep(0.55f, 0.2f)))
        assertNull(pinchFeel(PinchStep(1f, 0f), PinchStep(1.2f, 0f)))
    }

    @Test fun theLargestTextIsFeltOnceWhenThePinchReachesIt() {
        val max = HostConnection.MAX_SCALE
        assertEquals(Feel.End, pinchFeel(PinchStep(max - 0.1f, 0f), PinchStep(max, 0f)))
        // Held there, or a pinch that began there: nothing more on each move.
        assertNull(pinchFeel(PinchStep(max, 0f), PinchStep(max, 0f)))
        assertNull(pinchFeel(PinchStep(max, 0f), PinchStep(max - 0.2f, 0f)))
        // Opening the overview brings the text back to where the pinch began, the largest size too: that is no end.
        assertNull(pinchFeel(PinchStep(max - 0.01f, 0.99f), PinchStep(max, 1f)))
    }

    @Test fun aPinchThatGoesOnBelowTheSmallestTextSizeOpensTheOverview() {
        val ratios = (1..12).map { min * (1f - it / 12f * 0.6f) }
        val progress = ratios.map { pinchStep(1f, it).progress }
        assertTrue(progress.first() > 0f)
        assertTrue("further in is further open", progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, progress.last())
        assertEquals(1f, pinchStep(1f, 0.001f).progress)
    }

    @Test fun theTextSizeComesBackToWhereThePinchBeganAsTheOverviewOpens() {
        for (start in listOf(1f, 1.4f, 0.7f)) {
            val open = pinchStep(start, 0.001f)
            assertEquals(1f, open.progress)
            assertEquals(start, open.scale, 0.0001f)
            assertEquals(start, pinchTextScale(start, 1f), 0.0001f)
        }
    }

    @Test fun theHandOverDoesNotJump() {
        val handOver = min / 1.4f
        val before = pinchStep(1.4f, handOver * 1.001f)
        val after = pinchStep(1.4f, handOver * 0.999f)
        assertEquals(0f, before.progress)
        assertTrue(after.progress > 0f && after.progress < 0.01f)
        assertEquals(before.scale, after.scale, 0.01f)
        assertEquals(min, pinchTextScale(1.4f, 0f))
    }

    @Test fun reversingThePinchClosesTheOverviewAndThenScalesTheTextAgain() {
        val start = 1f
        val inwards = listOf(0.9f, 0.6f, 0.45f, 0.35f, 0.3f)
        val there = inwards.map { pinchStep(start, it) }
        val back = inwards.reversed().map { pinchStep(start, it) }
        assertEquals(there.reversed(), back)
        assertEquals(PinchStep(0.9f, 0f), back.last())
    }

    @Test fun atTheSmallestTextSizeAPinchInOpensAtOnce() {
        val step = pinchStep(min, 0.9f)
        assertTrue(step.progress > 0f)
        assertEquals(min, step.scale)
    }

    // ---------------------------------------------------------------- surfaces (P3, P5)

    @Test fun theSelectedTabAndTwoNeighboursOnEachSideKeepSurfaces() {
        assertEquals(setOf(2, 3, 4, 5, 6), keptTabs(10, selected = 4, centred = 4))
        assertEquals(setOf(0, 1, 2), keptTabs(10, selected = 0, centred = 0))
        assertEquals(setOf(7, 8, 9), keptTabs(10, selected = 9, centred = 9))
        assertEquals(setOf(0, 1), keptTabs(2, selected = 1, centred = 1))
        assertEquals(setOf(0), keptTabs(1, selected = 0, centred = 0))
        assertEquals(emptySet<Int>(), keptTabs(0, selected = 0, centred = 0))
    }

    @Test fun aCardThatScrollsNearAttachesAndOneThatLeavesDetachesButNeverTheSelectedTab() {
        assertEquals(setOf(1, 5, 6, 7, 8, 9), keptTabs(10, selected = 1, centred = 7))
        assertEquals(setOf(0, 1, 2, 3, 4), keptTabs(10, selected = 1, centred = 2))
        assertEquals(setOf(0, 1, 2, 9), keptTabs(10, selected = 9, centred = 0))
    }

    private val tabs = listOf("a", "b", "c", "d", "e", "f")
    private val shown = PaneRole.Shown
    private val preview = PaneRole.Preview
    private val detached = PaneRole.Detached

    @Test fun onlyTheTabInTheWorkspaceHasShownPanes() {
        assertEquals(listOf(preview, shown, preview, preview, detached, detached),
            tabRoles(tabs, selected = "b", anchor = 1, kept = keptTabs(tabs.size, 1, 1), closed = true, pinching = false))
    }

    @Test fun everyPaneIsAPreviewAsSoonAsTheOverviewOpensAtAll() {
        // Open at all is not closed: MotionValuesTest checks that the least progress shows the overview.
        assertEquals(listOf(preview, preview, preview, preview, detached, detached),
            tabRoles(tabs, selected = "b", anchor = 1, kept = keptTabs(tabs.size, 1, 1), closed = false, pinching = false))
    }

    @Test fun aConfirmedSwitchFillsAndShowsTheNewPageBeforeMotionCatchesUp() {
        // The server switched b -> e, but the selection effect still has the old row anchor.
        val anchor = tabs.indexOf(displayAnchor(selected = "e", lastConfirmed = "b", anchor = "b", rowActive = false))
        val centred = centredTab(tabs.size, anchor, 0f, 0f, stage)
        assertEquals(TabTransform(1f, 0f, 0f), tabTransform(4, anchor, 0f, 0f, stage))
        assertEquals(listOf(detached, detached, preview, preview, shown, preview),
            tabRoles(tabs, selected = "e", anchor = anchor, kept = keptTabs(tabs.size, anchor, centred), closed = true, pinching = false))
    }

    @Test fun anOutstandingPickOrSwipeFillsTheRequestedPageButDoesNotShowIt() {
        // d is full size; b is still the last and current server selection.
        val anchor = tabs.indexOf(displayAnchor(selected = "b", lastConfirmed = "b", anchor = "d", rowActive = false))
        val centred = centredTab(tabs.size, anchor, 0f, 0f, stage)
        assertEquals(TabTransform(1f, 0f, 0f), tabTransform(3, anchor, 0f, 0f, stage))
        assertEquals(listOf(detached, preview, preview, preview, preview, preview),
            tabRoles(tabs, selected = "b", anchor = anchor, kept = keptTabs(tabs.size, anchor, centred), closed = true, pinching = false))
        // A new server selection can confirm the request or supersede it.
        assertEquals("d", displayAnchor("d", lastConfirmed = "b", anchor = "d", rowActive = false))
        assertEquals("e", displayAnchor("e", lastConfirmed = "b", anchor = "d", rowActive = false))
    }

    @Test fun anActiveRowKeepsItsGeometryUntilTheSelectionEffectRebasesItsScroll() {
        assertEquals("b", displayAnchor("e", lastConfirmed = "b", anchor = "b", rowActive = true))
    }

    @Test fun aPinchThatCameBackToTheWorkspaceShowsNothingUntilTheFingersLift() {
        // Shown again, the pane would be sized for a text size the fingers are still changing.
        assertEquals(listOf(preview, preview, preview, preview, detached, detached),
            tabRoles(tabs, selected = "b", anchor = 1, kept = keptTabs(tabs.size, 1, 1), closed = true, pinching = true))
    }

    @Test fun aScrolledRowKeepsTheSelectedTabAndTheCardsInView() {
        assertEquals(listOf(preview, detached, detached, preview, preview, preview),
            tabRoles(tabs, selected = "a", anchor = 0, kept = keptTabs(tabs.size, 0, 5), closed = false, pinching = false))
    }

    // ---------------------------------------------------------------- back on the same tab

    private fun tabA(vararg blocks: String) = ShownTab("s1", "a", blocks.toSet())
    private val tabB = ShownTab("s1", "b", setOf("x"))

    @Test fun backOnTheTabTheOverviewWasOpenedFromItsPanesAreShownAsTheyWere() {
        val mark = ReturnMark()
        assertEquals(emptySet<String>(), mark.step(tabA("t", "u"), "s1"))
        assertEquals(emptySet<String>(), mark.step(null, "s1"))
        // Cancelled, reversed, or its own card tapped: all the same to the terminals.
        assertEquals(setOf("t", "u"), mark.step(tabA("t", "u"), "s1"))
        assertEquals("drawing it again changes nothing", setOf("t", "u"), mark.step(tabA("t", "u"), "s1"))
    }

    @Test fun anotherCardIsShownByTheTakeRules() {
        val mark = ReturnMark()
        mark.step(tabA("t"), "s1")
        mark.step(null, "s1")
        assertEquals(emptySet<String>(), mark.step(tabB, "s1"))
        // And the way back to the first tab is a tab switch like any other.
        mark.step(null, "s1")
        assertEquals(emptySet<String>(), mark.step(tabA("t"), "s1"))
    }

    @Test fun aTabSwitchWithoutTheOverviewIsNoReturn() {
        val direct = ReturnMark()
        direct.step(tabA("t"), "s1")
        assertEquals(emptySet<String>(), direct.step(tabB, "s1"))
        assertEquals(emptySet<String>(), direct.step(tabA("t"), "s1"))
    }

    @Test fun aReturnIsUsedOnce() {
        val mark = ReturnMark()
        mark.step(tabA("t"), "s1")
        mark.step(null, "s1")
        assertEquals(setOf("t"), mark.step(tabA("t"), "s1"))
        // To another tab and back without opening the overview from this one again.
        assertEquals(emptySet<String>(), mark.step(tabB, "s1"))
        mark.step(null, "s1")
        assertEquals(emptySet<String>(), mark.step(tabA("t"), "s1"))
    }

    @Test fun aPaneMadeWhileTheOverviewWasOpenIsNew() {
        val mark = ReturnMark()
        mark.step(tabA("t"), "s1")
        mark.step(null, "s1")
        assertEquals(setOf("t"), mark.step(tabA("t", "u"), "s1"))
    }

    @Test fun aPaneThatWasUsedMeanwhileIsShownByTheTakeRules() {
        // A pinch changed its text size on the way: that is a use of its terminal.
        val mark = ReturnMark()
        mark.step(tabA("t", "u"), "s1")
        mark.step(null, "s1")
        mark.used("t")
        assertEquals(setOf("u"), mark.step(tabA("t", "u"), "s1"))
    }

    @Test fun anotherSessionMeanwhileEndsTheReturn() {
        val mark = ReturnMark()
        mark.step(tabA("t"), "s1")
        mark.step(null, "s1")
        // The session was switched and switched back while no tab was shown.
        mark.step(null, "s2")
        mark.step(null, "s1")
        assertEquals(emptySet<String>(), mark.step(tabA("t"), "s1"))

        // A tab of another session with the same IDs is not the tab the overview was opened from.
        val other = ReturnMark()
        other.step(tabA("t"), "s1")
        other.step(null, "s1")
        assertEquals(emptySet<String>(), other.step(ShownTab("s2", "a", setOf("t")), "s2"))
    }

    @Test fun anOverviewThatTheSidebarCoveredStillComesBackToTheTabItWasOpenedFrom() {
        // The overview is open from tab a; the sidebar takes the panes' place and the views go.
        val mark = ReturnMark()
        mark.step(tabA("t", "u"), "s1")
        mark.step(null, "s1")
        mark.interrupted(covered = true)
        // The sidebar closes with the overview still open, then the overview closes on tab a.
        mark.step(null, "s1")
        assertEquals(setOf("t", "u"), mark.step(tabA("t", "u"), "s1"))
    }

    @Test fun aCoverThatEndsOnAnotherTabIsNoReturn() {
        val mark = ReturnMark()
        mark.step(tabA("t"), "s1")
        mark.step(null, "s1")
        mark.interrupted(covered = true)
        // The same block in both tabs: only the tab, not the blocks, tells the return apart.
        assertEquals(emptySet<String>(), mark.step(ShownTab("s1", "b", setOf("t")), "s1"))
    }

    @Test fun aPaneAZoomHidWhileCoveredIsNoReturnWhenTheZoomEnds() {
        // Tab a draws t and u; the sidebar covers the overview, and meanwhile t is zoomed.
        val split = LayoutNode(dir = "column", ratio = .5f, first = LayoutNode(block = "t"), second = LayoutNode(block = "u"))
        val drawn = WindowInfo("a", layout = split)
        val mark = ReturnMark()
        mark.step(shownTab("s1", drawn), "s1")
        mark.step(null, "s1")
        mark.interrupted(covered = true)
        mark.step(null, "s1")
        // The overview closes on tab a, which draws t alone.
        assertEquals(setOf("t"), mark.step(shownTab("s1", drawn.copy(zoomed = "t")), "s1"))
        // The zoom ends: u is drawn again with a new view, and that is no return.
        assertEquals(setOf("t"), mark.step(shownTab("s1", drawn), "s1"))
    }

    @Test fun panesThatWentWithoutACoverAreNotComeBackTo() {
        // No tab left to draw, or another remote: the views went and the panes are not hidden.
        val mark = ReturnMark()
        mark.step(tabA("t"), "s1")
        mark.step(null, "s1")
        mark.interrupted(covered = false)
        assertEquals(emptySet<String>(), mark.step(tabA("t"), "s1"))
    }

    // ---------------------------------------------------------------- closing a tab by lifting its card (#216)

    @Test fun aCardLiftedByItsWholeWayHasLeftThePaneAreaWithItsName() {
        val box = cardBox(1, 1, 1f, 0f, stage)
        val way = liftDistance(1f, stage)
        val nameBottom = box.bottom + stage.labelRoom - way
        assertTrue("out of the pane area", nameBottom <= near)
        assertTrue("not further than that", nameBottom >= -near)
    }

    @Test fun aCardFollowsAFingerUpOneToOne() {
        assertEquals(300f, cardLift(-300f, 90f, closable = true), near)
    }

    @Test fun aCardPulledDownMovesLessAndLessAndNeverPastItsLimit() {
        val some = cardLift(100f, 90f, closable = true)
        val more = cardLift(1000f, 90f, closable = true)
        assertTrue(some < 0f && some > -100f)
        assertTrue(more < some)
        assertTrue(more > -90f)
    }

    @Test fun aCardThatCannotCloseIsHeldBackGoingUpToo() {
        val lift = cardLift(-300f, 90f, closable = false)
        assertTrue(lift > 0f && lift < 90f)
    }

    @Test fun aCardLetGoHalfWayOrFlungUpCloses() {
        assertTrue(dismisses(0.5f, 0f))
        assertFalse(dismisses(0.49f, 0f))
        assertTrue(dismisses(0.1f, FLING_PER_SECOND))
    }

    @Test fun aCardMovingDownWhenLetGoStaysEvenHighUp() {
        assertFalse(dismisses(0.8f, -FLING_PER_SECOND))
    }

    @Test fun aCardFlyingAwayNeverSlowsDown() {
        val way = 1000f
        var flight = Flight(lift = 100f, speed = 4000f)
        repeat(5) {
            val next = flightStep(flight, 1 / 60f, way)
            assertTrue(next.speed >= flight.speed)
            assertTrue(next.lift >= flight.lift + flight.speed / 60f - near)
            flight = next
        }
    }

    @Test fun aCardLetGoWithoutSpeedIsGoneWithinAThirdOfASecond() {
        val way = 1000f
        var flight = Flight(lift = way / 2, speed = 0f)
        var frames = 0
        while (flight.lift < way) {
            flight = flightStep(flight, 1 / 60f, way)
            frames++
        }
        assertTrue("took $frames frames", frames <= 20)
    }

    @Test fun whenACardLeavesTheRowTheCardsAfterItSlideFromWhereTheyWere() {
        val before = listOf("a", "b", "c", "d")
        val after = listOf("a", "b", "d")
        fun lefts(tabs: List<String>) = tabs.withIndex().associate { (i, id) -> id to Offset(cardBox(i, 1, 1f, 0f, stage).left, 0f) }
        val shifts = rowShifts(lefts(before), lefts(after))
        val pitch = cardBox(2, 1, 1f, 0f, stage).left - cardBox(1, 1, 1f, 0f, stage).left
        assertEquals(setOf("d"), shifts.keys)
        assertEquals(pitch, shifts.getValue("d").x, near)
        assertEquals(0f, shifts.getValue("d").y, 0f)
    }

    @Test fun aLiftThatPassesHalfItsWayIsFeltOnceEachWay() {
        assertEquals(true, liftCrossing(0.49f, 0.5f))
        assertEquals(false, liftCrossing(0.6f, 0.4f))
        assertNull(liftCrossing(0.1f, 0.4f))
        assertNull(liftCrossing(0.6f, 0.9f))
    }

    @Test fun aClosedTabIsNamedWhenItIsGone() {
        assertEquals("Closed alpha", closedTabNote("alpha"))
        assertEquals("Closed tab", closedTabNote(""))
    }

    @Test fun aTabBeingClosedLeavesTheRowBeforeTheServerTakesItAway() {
        val slots = rowSlots(listOf("a", "b", "c", "d"), setOf("b"))
        // c takes b's place at once, d moves one place to the left.
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 1, "d" to 2), slots)
    }

    @Test fun theRowIsBuiltAroundTheCardThatTakesTheClosingTabsPlace() {
        val tabs = listOf("a", "b", "c", "d")
        assertEquals(1, rowAnchor(tabs, setOf("b"), "b"))
        // The last card closing: the one before it is centred.
        assertEquals(2, rowAnchor(tabs, setOf("d"), "d"))
        assertEquals(2, rowAnchor(tabs, setOf("b"), "d"))
        assertEquals(0, rowAnchor(listOf("a"), setOf("a"), "a"))
    }

    // ---------------------------------------------------------------- taking a close back (#228)

    @Test fun aShellTabClosedUnaskedCanBeTakenBack() {
        assertTrue(undoable(asked = false, lastTab = false, inSight = true))
    }

    @Test fun aTabThatWasAskedAboutOrIsTheLastClosesAtOnce() {
        assertFalse(undoable(asked = true, lastTab = false, inSight = true))
        assertFalse(undoable(asked = false, lastTab = true, inSight = true))
    }

    @Test fun theLastTabCanBeTakenBackWhereTheOverviewStays() {
        // #247: with the strip the overview stays after the last tab, so its Undo note has a place.
        assertTrue(undoable(asked = false, lastTab = true, inSight = true, stays = true))
        assertFalse(undoable(asked = true, lastTab = true, inSight = true, stays = true))
        assertFalse(undoable(asked = false, lastTab = true, inSight = false, stays = true))
    }

    @Test fun aCloseWhoseUndoCouldNotBeSeenIsSentAtOnce() {
        // The overview was left while the card flew: no note would show its Undo.
        assertFalse(undoable(asked = false, lastTab = false, inSight = false))
    }

    @Test fun anotherCloseSendsTheOneThatWaited() {
        val slot = UndoSlot<String>()
        assertNull(slot.hold("a"))
        assertEquals("a", slot.hold("b"))
        assertEquals("b", slot.waiting)
    }

    @Test fun undoOrTimeTakesTheWaitingCloseOnce() {
        val slot = UndoSlot<String>()
        slot.hold("a")
        assertEquals("a", slot.take())
        assertNull(slot.take())
        assertNull(slot.waiting)
    }

    @Test fun aGoneTabTakesOnlyItsOwnWaitingClose() {
        val slot = UndoSlot<String>()
        slot.hold("a")
        assertNull(slot.takeIf { it == "b" })
        assertEquals("a", slot.takeIf { it == "a" })
        assertNull(slot.waiting)
    }

    // ---------------------------------------------------------------- the session strip (#229)

    private val withStrip = stage.copy(stripRoom = 200f)

    @Test fun theCardsAndTheirNamesStandBelowTheSessionStrip() {
        val box = cardBox(0, 0, 1f, 0f, withStrip)
        assertTrue(box.top >= 200f)
        assertTrue(box.bottom + withStrip.labelRoom <= withStrip.height + near)
        // The card fills the height: it starts a row padding under the strip and ends above the Undo note.
        assertEquals(withStrip.rowPad, box.top - 200f, near)
        assertTrue(box.bottom <= withStrip.height - withStrip.noteRoom + near)
    }

    @Test fun theWorkspaceTakesNoRoomForTheStrip() {
        assertEquals(TabTransform(1f, 0f, 0f), tabTransform(0, 0, 0f, 0f, withStrip))
    }

    @Test fun aTallStripMakesTheCardsSmaller() {
        val tall = stage.copy(stripRoom = 700f)
        val box = cardBox(0, 0, 1f, 0f, tall)
        assertEquals(tall.height - tall.labelRoom - 700f, box.bottom - box.top, near)
        assertEquals(700f, box.top, near)
    }

    @Test fun theStripTakesRoomOnlyWhereTheCardsStayLargeEnough() {
        // The Fold8 unfolded: room for it.
        assertEquals(250f, stripRoomFor(height = 1500f, labelRoom = 90f, room = 250f), near)
        // A pane area squeezed by the keyboard: the cards keep the room, the strip is left out.
        assertEquals(0f, stripRoomFor(height = 400f, labelRoom = 90f, room = 250f), near)
    }

    @Test fun aSessionSwitchIsSeenFromTheRowOrFromTheRequest() {
        // Chosen elsewhere (the sidebar): the row was arranged for another session.
        assertTrue(switchedSession(arranged = "a", askedFrom = null, session = "b"))
        // Chosen on the strip through a session without tabs, and back: the row never saw the middle one.
        assertTrue(switchedSession(arranged = "a", askedFrom = "c", session = "a"))
        // Nothing changed, the first row, a request the server turned down.
        assertFalse(switchedSession(arranged = "a", askedFrom = null, session = "a"))
        assertFalse(switchedSession(arranged = null, askedFrom = null, session = "a"))
        assertFalse(switchedSession(arranged = "a", askedFrom = "a", session = "a"))
    }

    @Test fun theOverviewClosesAfterTheLastTabOnlyWithoutTheStrip() {
        // #247: with the strip there are other sessions, New Session and New Tab to go on to.
        assertFalse(closesAfterFlight(allAway = true, strip = true))
        assertTrue(closesAfterFlight(allAway = true, strip = false))
        assertFalse(closesAfterFlight(allAway = false, strip = false))
        assertFalse(closesAfterFlight(allAway = false, strip = true))
    }

    @Test fun aRowWaitedForDropsInOnceItHasTabs() {
        assertTrue(rowArrived(awaited = null, session = "a", row = listOf("t")))
        assertFalse(rowArrived(awaited = null, session = "a", row = emptyList()))
    }

    @Test fun anotherRemotesRowDropsInOnlyForTheSessionChosen() {
        // The remote showed another session when its chip was tapped: that row is not the one asked for (#236).
        assertFalse(rowArrived(awaited = "b", session = "a", row = listOf("t")))
        assertFalse(rowArrived(awaited = "b", session = "b", row = emptyList()))
        assertTrue(rowArrived(awaited = "b", session = "b", row = listOf("t")))
    }

    @Test fun anotherSessionsRowDropsInFromAbove() {
        assertTrue(dropOffset(1f, withStrip) < 0f)
        assertEquals(0f, dropOffset(0f, withStrip), near)
        // Halfway down, half the way.
        assertEquals(dropOffset(1f, withStrip) / 2, dropOffset(0.5f, withStrip), near)
    }

    // ---------------------------------------------------------------- the new-tab card (#230)

    @Test fun theNewTabCardIsOneMorePlaceAtTheEndOfTheRow() {
        val places = rowPlaces(3)
        assertEquals(4, places)
        // At the end of the row the new-tab card stands in the middle.
        val end = cardScroll(3, 1, stage)
        assertEquals(end, scrollBounds(places, 1, stage).endInclusive, near)
        // A tap on it is a tap on its place, after the last tab's.
        val box = cardBox(3, 1, 1f, end, stage)
        assertEquals(3, cardAt((box.left + box.right) / 2, (box.top + box.bottom) / 2, places, 1, 1f, end, stage))
    }

    @Test fun theNewTabTheServerSelectsIsTheOneToOpen() {
        assertTrue(opensNewTab(setOf("a", "b"), "s1", "s1", "c"))
    }

    @Test fun aTabThatWasThereBeforeIsNotOpened() {
        assertFalse(opensNewTab(setOf("a", "b"), "s1", "s1", "b"))
    }

    @Test fun anotherSessionsTabIsNotTheNewTab() {
        // A session chosen elsewhere while New Tab waited: its tabs were never in the earlier set.
        assertFalse(opensNewTab(setOf("a", "b"), "s1", "s2", "x"))
    }

    @Test fun nothingIsOpenedWhileNoNewTabIsAwaited() {
        assertFalse(opensNewTab(null, null, "s1", "c"))
    }

    @Test fun aScreenReaderScrollsOntoTheNewTabCard() {
        // Three tabs and the new-tab card: from the last tab one more step reaches the card.
        assertEquals(3, nextPlace(2, forward = true, places = rowPlaces(3)))
        assertNull(nextPlace(3, forward = true, places = rowPlaces(3)))
        assertEquals(2, nextPlace(3, forward = false, places = rowPlaces(3)))
        assertNull(nextPlace(0, forward = false, places = rowPlaces(3)))
    }

    @Test fun aCardNewToTheRowHasNoShift() {
        assertEquals(emptyMap<String, Offset>(), rowShifts(mapOf("a" to Offset(0f, 0f)), mapOf("a" to Offset(0f, 0f), "b" to Offset(500f, 0f))))
    }

    @Test fun shiftsCarryBothAxes() {
        val drawn = mapOf("a" to Offset(555f, 273f), "b" to Offset(42f, 1383f))
        val now = mapOf("a" to Offset(42f, 273f), "b" to Offset(555f, 273f))
        assertEquals(mapOf("a" to Offset(513f, 0f), "b" to Offset(-513f, 1110f)), rowShifts(drawn, now))
    }

    @Test fun rowShiftsStayHorizontal() {
        val shifts = rowShifts(mapOf("a" to Offset(300f, 0f)), mapOf("a" to Offset(100f, 0f)))
        assertEquals(Offset(200f, 0f), shifts["a"])
    }

    @Test fun aShiftIsKeptByItsLengthInBothAxes() {
        // Each axis alone is under half a px: a (about 0.57 px long) is kept, b (about 0.42 px) is not.
        val drawn = mapOf("a" to Offset(0.4f, 0.4f), "b" to Offset(0.3f, 0.3f))
        val shifts = rowShifts(drawn, mapOf("a" to Offset.Zero, "b" to Offset.Zero))
        assertEquals(mapOf("a" to Offset(0.4f, 0.4f)), shifts)
    }

    @Test fun aLiftWithoutASideGoesUp() {
        assertEquals(Offset(0f, -120f), liftOffset(120f, 0f))
    }

    @Test fun aSidewaysLiftGoesToItsSide() {
        assertEquals(Offset(120f, 0f), liftOffset(120f, 1f))
        assertEquals(Offset(-120f, 0f), liftOffset(120f, -1f))
    }

    @Test fun noLiftMovesACardNowhere() {
        for (side in listOf(0f, 1f, -1f)) {
            val offset = liftOffset(0f, side)
            assertEquals(0f, offset.x, 0f)
            assertEquals(0f, offset.y, 0f)
        }
    }

    // ---------------------------------------------------------------- dragging a card to reorder tabs (#218)

    @Test fun aTabDragKeepsEveryFinger() {
        // The fingers that cancel it must not become a pinch, a swipe or a scroll of the row.
        assertEquals(Touch.TabDrag, touchMode(Touch.TabDrag, fingers = 1, progress = 1f))
        assertEquals(Touch.TabDrag, touchMode(Touch.TabDrag, fingers = 2, progress = 1f))
        assertEquals(Touch.TabDrag, touchMode(Touch.TabDrag, fingers = 3, progress = 1f))
    }

    @Test fun aStillFingerOnACardPicksItUpAfterTheLongPressTimeout() {
        assertEquals(RowFinger.Pickup, rowFinger(3f, 3f, 8f, held = true, liftable = true, pickable = true))
        assertEquals(RowFinger.Undecided, rowFinger(3f, 3f, 8f, held = false, liftable = true, pickable = true))
    }

    @Test fun upOrDownFirstStillLiftsAndSidewaysFirstStillScrolls() {
        assertEquals(RowFinger.Lift, rowFinger(0f, -20f, 8f, false, true, true))
        assertEquals(RowFinger.Scroll, rowFinger(20f, 5f, 8f, false, true, true))
        // Beside the cards nothing lifts: the finger scrolls once it has gone sideways far enough.
        assertEquals(RowFinger.Scroll, rowFinger(20f, -30f, 8f, false, liftable = false, pickable = false))
        assertEquals(RowFinger.Undecided, rowFinger(0f, -20f, 8f, false, false, false))
    }

    @Test fun aLongPressPicksUpOnlyACardThatCanMove() {
        assertEquals(RowFinger.Undecided, rowFinger(3f, 3f, 8f, held = true, liftable = true, pickable = false))
        assertEquals(RowFinger.Undecided, rowFinger(3f, 3f, 8f, held = true, liftable = false, pickable = false))
    }

    @Test fun onlyALoneFingerOnACardThatCanGoPicksItUpWhileTheServerHears() {
        assertTrue(mayPickUp(liftable = true, fingers = 1, connected = true, paneMoving = false))
        assertFalse(mayPickUp(liftable = false, fingers = 1, connected = true, paneMoving = false))
        assertFalse(mayPickUp(liftable = true, fingers = 2, connected = true, paneMoving = false))
        assertFalse(mayPickUp(liftable = true, fingers = 1, connected = false, paneMoving = false))
        assertFalse(mayPickUp(liftable = true, fingers = 1, connected = true, paneMoving = true))
    }

    @Test fun theLongPressIsAwaitedOnlyForAStillLoneFingerOnACardThatCanGo() {
        assertEquals(380L, longPressLeft(500L, 120L, pickable = true, held = false, fingers = 1, decided = false))
        // Events came late: the long press is over already.
        assertEquals(-100L, longPressLeft(500L, 600L, pickable = true, held = false, fingers = 1, decided = false))
        assertNull(longPressLeft(500L, 120L, pickable = false, held = false, fingers = 1, decided = false))
        assertNull(longPressLeft(500L, 120L, pickable = true, held = true, fingers = 1, decided = false))
        assertNull(longPressLeft(500L, 120L, pickable = true, held = false, fingers = 2, decided = false))
        assertNull(longPressLeft(500L, 120L, pickable = true, held = false, fingers = 1, decided = true))
    }

    @Test fun theDropPlaceIsThePlaceNearestTheCardsMiddle() {
        // Card width 1817.2, pitch 1871.2: around the card at 1 the places' middles are -771.2, 1100, 2971.2 and 4842.4.
        assertEquals(1, dropPlace(1100f, 0f, 4, 1, 1f, 0f, stage))
        assertEquals(1, dropPlace(2035f, 0f, 4, 1, 1f, 0f, stage))
        assertEquals(2, dropPlace(2037f, 0f, 4, 1, 1f, 0f, stage))
        // The row moved one card to the left: the place in the middle is the next one.
        assertEquals(2, dropPlace(1100f, 0f, 4, 1, 1f, 1871.2f, stage))
        for (centre in listOf(-5000f, 0f, 1100f, 10_000f)) assertEquals(0, dropPlace(centre, 0f, 1, 0, 1f, 0f, stage))
    }

    @Test fun dropPlaceClampsToTheEnds() {
        assertEquals(0, dropPlace(-5000f, 0f, 4, 1, 1f, 0f, stage))
        assertEquals(3, dropPlace(10_000f, 0f, 4, 1, 1f, 0f, stage))
        // Over the new-tab card: the end of the row.
        assertEquals(3, dropPlace(6713.6f, 0f, 4, 1, 1f, 0f, stage))
    }

    @Test fun aDroppedRowRestsOnThePlaceNearestTheMiddle() {
        // An edge scroll left the row 200 px past the card at 1; the drop builds the row around 2 instead.
        val rest = restAfterDrop(200f, from = 1, to = 2, places = rowPlaces(3), progress = 1f, stage = stage)
        assertEquals(-1871.2f, rest, near)
        // The place in the middle is the one that was nearest it before the drop.
        assertEquals(1, centredTab(rowPlaces(3), 1, 1f, 200f, stage))
        assertEquals(1, centredTab(rowPlaces(3), 2, 1f, rest, stage))
        // A row at rest stays where it is on screen, whatever it is built around.
        assertEquals(0f, restAfterDrop(0f, 1, 1, rowPlaces(3), 1f, stage), near)
        assertEquals(-1871.2f, restAfterDrop(0f, 1, 2, rowPlaces(3), 1f, stage), near)
        // It never rests past the new-tab card.
        assertEquals(3742.4f, restAfterDrop(5000f, 1, 1, rowPlaces(3), 1f, stage), near)
    }

    @Test fun aTabMovedByAScreenReaderIsNamedWithItsPlace() {
        assertEquals("Moved build to 3 of 5", movedTabNote("build", 3, 5))
        assertEquals("Moved tab to 1 of 2", movedTabNote("", 1, 2))
    }

    @Test fun aDropKeepsEveryPlaceWhereItStands() {
        // The row built around another place after a drop: every place stays where it was on screen.
        for (i in 0..4) {
            assertEquals(cardBox(i, 1, 1f, 200f, stage).left, cardBox(i, 3, 1f, rebasedScroll(200f, 1, 3, 1f, stage), stage).left, near)
        }
    }
}
