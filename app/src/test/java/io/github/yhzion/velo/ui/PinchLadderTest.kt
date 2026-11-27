package io.github.yhzion.velo.ui

import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.Feel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/** The pinch ladder (#140): split tab ⇄ one pane full screen, decided when the fingers lift. */
class PinchLadderTest {
    private val near = 0.001f
    private fun assertBox(expected: CardBox, actual: CardBox?) {
        val box = actual ?: throw AssertionError("no box, expected $expected")
        listOf(expected.left to box.left, expected.top to box.top, expected.right to box.right, expected.bottom to box.bottom)
            .forEach { (e, a) -> assertEquals("$expected, was $box", e, a, 0.01f) }
    }

    private val split = WindowInfo("w", layout = LayoutNode(dir = "row", first = LayoutNode(block = "a"), second = LayoutNode(block = "b")))

    /**
     * A pinch, as the gesture loop drives it: one event every [frame] ms, the fingers' ln ratio going at [rates] (ln per
     * second) for the ms each lasts. What it gives: the intent after each event and what was felt.
     */
    private class Pinch(val toward: LayoutPinch, start: Long = 1000L, val frame: Long = 8L) {
        val speed = PinchSpeed()
        var intent = LayoutIntent()
        var time = start
        var ln = 0f
        val felt = mutableListOf<Feel>()

        fun step() {
            val next = layoutIntent(intent, toward, ln, speed.perSecond(time), time)
            layoutIntentFeel(intent, next)?.let(felt::add)
            intent = next
        }

        fun move(perSecond: Float, ms: Long): Pinch {
            var left = ms
            while (left > 0) {
                time += frame
                left -= frame
                ln += perSecond * frame / 1000f
                speed.add(time, ln)
                step()
            }
            return this
        }

        /** The fingers rest for [ms] and send no event: the loop looks again when [layoutIntentWait] says. */
        fun rest(ms: Long): Pinch {
            val until = time + ms
            while (true) {
                val wait = layoutIntentWait(intent, time) ?: break
                if (time + wait > until) break
                time += wait
                step()
            }
            time = until
            return this
        }
    }

    @Test fun aFastSpreadOnASplitTabGoesFullScreenWhenTheFingersLift() {
        val pinch = Pinch(LayoutPinch.Zoom).move(4f, 120L)
        assertTrue(pinch.intent.on)
        assertEquals(listOf(Feel.ThresholdOn), pinch.felt)
        assertEquals("zoom", liftOp(pinch.toward, pinch.intent, connected = true, cut = false))
    }

    @Test fun aSlowSpreadOnlyScalesTheText() {
        val pinch = Pinch(LayoutPinch.Zoom).move(1f, 600L)
        assertFalse(pinch.intent.on)
        assertTrue(pinch.felt.isEmpty())
        assertNull(liftOp(pinch.toward, pinch.intent, connected = true, cut = false))
    }

    @Test fun aTwitchOfFastFingersIsNoStep() {
        // Fast, but the fingers are not yet INTENT_MIN_TRAVEL apart.
        assertFalse(layoutIntent(LayoutIntent(), LayoutPinch.Zoom, INTENT_MIN_TRAVEL - 0.01f, 10f, 0L).on)
        assertTrue(layoutIntent(LayoutIntent(), LayoutPinch.Zoom, INTENT_MIN_TRAVEL + 0.01f, 10f, 0L).on)
    }

    @Test fun fingersThatSlowDownBeforeTheyLiftLetTheIntentGo() {
        val pinch = Pinch(LayoutPinch.Zoom).move(4f, 120L).move(0.3f, 400L)
        assertFalse(pinch.intent.on)
        assertEquals(listOf(Feel.ThresholdOn, Feel.ThresholdOff), pinch.felt)
        assertNull(liftOp(pinch.toward, pinch.intent, connected = true, cut = false))
    }

    @Test fun fingersAtRestLetTheIntentGoThoughTheySendNoEvent() {
        val pinch = Pinch(LayoutPinch.Zoom).move(4f, 120L).rest(400L)
        assertFalse(pinch.intent.on)
        assertEquals(listOf(Feel.ThresholdOn, Feel.ThresholdOff), pinch.felt)
    }

    @Test fun aShortSlowDownKeepsTheIntent() {
        // Below OFF for less than INTENT_HOLD_MS, then fast again.
        val slowed = Pinch(LayoutPinch.Zoom).move(4f, 120L).move(0.5f, 136L)
        assertTrue(slowed.intent.slowSince != null)
        val pinch = slowed.move(3f, 80L)
        assertTrue(pinch.intent.on)
        assertEquals(listOf(Feel.ThresholdOn), pinch.felt)
        assertNull(pinch.intent.slowSince)
    }

    @Test fun betweenTheTwoThresholdsTheIntentStaysAsItIs() {
        // Off and between OFF and ON: it does not turn on.
        assertFalse(Pinch(LayoutPinch.Zoom).move(1.8f, 500L).intent.on)
        // On and between them: it does not turn off, however long.
        val on = Pinch(LayoutPinch.Zoom).move(4f, 120L).move(1.8f, 500L)
        assertTrue(on.intent.on)
        assertEquals(listOf(Feel.ThresholdOn), on.felt)
    }

    @Test fun fingersThatGoBackPastWhereTheyBeganDropTheIntentAtOnce() {
        val pinch = Pinch(LayoutPinch.Zoom).move(4f, 120L)
        pinch.ln = -0.01f
        pinch.step()
        assertFalse(pinch.intent.on)
        assertEquals(Feel.ThresholdOff, pinch.felt.last())
    }

    @Test fun aFastPinchInOnAFullScreenPaneSplitsTheTabAgain() {
        val pinch = Pinch(LayoutPinch.Unzoom).move(-4f, 120L)
        assertTrue(pinch.intent.on)
        assertEquals("unzoom", liftOp(pinch.toward, pinch.intent, connected = true, cut = false))
        // A fast spread on it only scales the text.
        assertFalse(Pinch(LayoutPinch.Unzoom).move(4f, 120L).intent.on)
        // And a fast pinch in on a split tab is the overview's, as before.
        assertFalse(Pinch(LayoutPinch.Zoom).move(-4f, 120L).intent.on)
    }

    @Test fun aPinchThatIsCutOffOrHasNoServerSendsNothing() {
        val on = LayoutIntent(on = true)
        assertEquals("zoom", liftOp(LayoutPinch.Zoom, on, connected = true, cut = false))
        assertNull(liftOp(LayoutPinch.Zoom, on, connected = true, cut = true))
        assertNull(liftOp(LayoutPinch.Zoom, on, connected = false, cut = false))
        assertNull(liftOp(null, on, connected = true, cut = false))
        assertNull(liftOp(LayoutPinch.Unzoom, LayoutIntent(), connected = true, cut = false))
    }

    @Test fun aTabThatChangedWhileTheFingersWereDownTakesNoStepAsTheyLift() {
        val shown = UiState(windows = listOf(split, WindowInfo("v", layout = LayoutNode(block = "c"))), window = "w")
        assertEquals(LayoutPinch.Zoom, stepAtLift(LayoutPinch.Zoom, shown, "w", "a", connected = true))
        assertNull("another device zoomed the tab", stepAtLift(LayoutPinch.Zoom, shown.copy(windows = listOf(split.copy(zoomed = "b"))), "w", "a", connected = true))
        assertNull("the pane closed", stepAtLift(LayoutPinch.Zoom, shown.copy(windows = listOf(split.copy(layout = LayoutNode(block = "b")))), "w", "a", connected = true))
        assertNull("another tab is shown", stepAtLift(LayoutPinch.Zoom, shown.copy(window = "v"), "w", "a", connected = true))
        assertNull("the tab closed", stepAtLift(LayoutPinch.Zoom, shown.copy(windows = shown.windows.drop(1)), "w", "a", connected = true))
        assertNull("no server", stepAtLift(LayoutPinch.Zoom, shown, "w", "a", connected = false))
        val zoomed = shown.copy(windows = listOf(split.copy(zoomed = "a")))
        assertEquals(LayoutPinch.Unzoom, stepAtLift(LayoutPinch.Unzoom, zoomed, "w", "a", connected = true))
        assertNull("another device split it again", stepAtLift(LayoutPinch.Unzoom, shown, "w", "a", connected = true))
        assertNull(stepAtLift(null, shown, "w", "a", connected = true))
    }

    @Test fun theLadderHasAStepForATiledPaneOfASplitTabAndForTheFullScreenPane() {
        assertEquals(LayoutPinch.Zoom, layoutPinch(split, "a", connected = true))
        assertEquals(LayoutPinch.Unzoom, layoutPinch(split.copy(zoomed = "b"), "b", connected = true))
        assertNull("no server, no step", layoutPinch(split, "a", connected = false))
        assertNull(layoutPinch(null, "a", connected = true))
    }

    @Test fun aTabWithOneTiledPaneAndAFloatingPaneAreNotOnTheLadder() {
        val one = WindowInfo("w", layout = LayoutNode(block = "a"), floating = listOf(FloatingLayer("f")))
        assertNull("one tiled pane has no full screen", layoutPinch(one, "a", connected = true))
        assertNull("a floating pane stays in the menu", layoutPinch(one, "f", connected = true))
        val withFloat = split.copy(floating = listOf(FloatingLayer("f")))
        assertNull(layoutPinch(withFloat, "f", connected = true))
        // A floating pane over a full screen one: not the full screen pane's step either.
        assertNull(layoutPinch(withFloat.copy(zoomed = "a"), "f", connected = true))
    }

    @Test fun thePreviewWaitsOnlyUntilTheTabsFullScreenChanges() {
        assertFalse("not yet zoomed", stepAnswered(LayoutPinch.Zoom, "a", null))
        assertTrue(stepAnswered(LayoutPinch.Zoom, "a", "a"))
        assertTrue("another device zoomed another pane", stepAnswered(LayoutPinch.Zoom, "a", "b"))
        assertFalse("still full screen", stepAnswered(LayoutPinch.Unzoom, "a", "a"))
        assertTrue(stepAnswered(LayoutPinch.Unzoom, "a", null))
        // The minimap zoomed another pane before the server split the tab: the outline would stand over that pane.
        assertTrue("the minimap zoomed another pane", stepAnswered(LayoutPinch.Unzoom, "a", "b"))
    }

    @Test fun aPinchInOnAFullScreenPaneScalesTheTextButOpensNoOverview() {
        val min = HostConnection.MIN_SCALE
        assertEquals(PinchStep(0.8f, 0f), ladderStep(1f, 0.8f, LayoutPinch.Unzoom))
        assertEquals(PinchStep(min, 0f), ladderStep(1f, 0.01f, LayoutPinch.Unzoom))
        assertEquals(PinchStep(HostConnection.MAX_SCALE, 0f), ladderStep(2f, 4f, LayoutPinch.Unzoom))
    }

    @Test fun aSplitTabsPinchInStillOpensTheOverview() {
        for (ratio in listOf(1.6f, 1f, 0.7f, 0.4f, 0.2f, 0.01f)) {
            assertEquals(pinchStep(1f, ratio), ladderStep(1f, ratio, LayoutPinch.Zoom))
            assertEquals(pinchStep(1f, ratio), ladderStep(1f, ratio, null))
        }
        assertEquals(1f, ladderStep(1f, 0.01f, LayoutPinch.Zoom).progress)
    }

    @Test fun theSpeedIsTheChangeOfTheLnRatioOverTheLastMoment() {
        val speed = PinchSpeed()
        for (i in 0..20) speed.add(1000L + i * 8, ln(1f + i * 0.02f))
        val now = 1160L
        val expected = (ln(1.4f) - ln(1f + 10 * 0.02f)) * 1000f / 80f
        assertEquals(expected, speed.perSecond(now), 0.01f)
        // At rest the speed falls away, and none is left once the window has passed the last move.
        assertTrue(speed.perSecond(now + 40L) < speed.perSecond(now))
        assertEquals(0f, speed.perSecond(now + 80L), near)
        // Two samples in one frame are no measure.
        assertEquals(0f, PinchSpeed().apply { add(0L, 0f); add(8L, 0.5f) }.perSecond(8L))
        assertEquals(0f, PinchSpeed().perSecond(0L))
    }

    @Test fun anIntentIsLookedAtAgainOnlyWhileItIsOn() {
        assertNull(layoutIntentWait(LayoutIntent(), 1000L))
        assertTrue(layoutIntentWait(LayoutIntent(on = true), 1000L)!! in 1L..INTENT_HOLD_MS)
        assertEquals(INTENT_HOLD_MS + 1 - 50L, layoutIntentWait(LayoutIntent(on = true, slowSince = 950L), 1000L))
        assertEquals(1L, layoutIntentWait(LayoutIntent(on = true, slowSince = 0L), 1000L))
    }

    @Test fun thePreviewTakesThePaneALittleTowardsItsOtherPlace() {
        assertEquals(0.22f, previewReach(LayoutPinch.Zoom, INTENT_MIN_TRAVEL), near)
        assertTrue(previewReach(LayoutPinch.Zoom, 0.5f) > previewReach(LayoutPinch.Zoom, 0.2f))
        assertEquals(0.4f, previewReach(LayoutPinch.Zoom, 3f), near)
        assertEquals(0.4f, previewReach(LayoutPinch.Unzoom, -3f), near)
        assertEquals(0.22f, previewReach(LayoutPinch.Unzoom, 0f), near)

        val place = CardBox(1000f, 0f, 2000f, 800f)
        assertBox(place, previewBox(LayoutPinch.Zoom, place, 2000f, 1600f, 0f))
        assertBox(CardBox(0f, 0f, 2000f, 1600f), previewBox(LayoutPinch.Zoom, place, 2000f, 1600f, 1f))
        assertBox(CardBox(0f, 0f, 2000f, 1600f), previewBox(LayoutPinch.Unzoom, place, 2000f, 1600f, 0f))
        assertBox(place, previewBox(LayoutPinch.Unzoom, place, 2000f, 1600f, 1f))
        assertBox(CardBox(780f, 0f, 2000f, 976f), previewBox(LayoutPinch.Zoom, place, 2000f, 1600f, 0.22f))
    }

    @Test fun theOtherPanesDimAsThePreviewComesIn() {
        assertEquals(0f, previewDim(0f), near)
        assertEquals(0.55f, previewDim(0.22f), near)
        assertTrue(previewDim(0.4f) in 0.55f..1f)
        assertEquals(1f, previewDim(1f), near)
    }

    @Test fun theTextGoesBackToItsSizeFromBeforeThePinchAsThePreviewComesIn() {
        assertEquals(1.8f, previewTextScale(1.8f, 1f, 0f), near)
        assertEquals(1f, previewTextScale(1.8f, 1f, 1f), near)
        assertEquals(1.4f, previewTextScale(1.8f, 1f, 0.5f), near)
    }

    @Test fun thePreviewsPlaceIsThePanesPlaceInTheSplitTab() {
        val zoomed = split.copy(zoomed = "b")
        assertBox(CardBox(1020f, 0f, 2000f, 1000f), splitPlace(zoomed, "b", 2000f, 1000f, 40f, rtl = false))
        assertBox(CardBox(0f, 0f, 980f, 1000f), splitPlace(zoomed, "b", 2000f, 1000f, 40f, rtl = true))
        val float = split.copy(floating = listOf(FloatingLayer("f", x = 0.1f, y = 0.2f, w = 0.5f, h = 0.5f)), zoomed = "f")
        assertBox(CardBox(200f, 200f, 1200f, 700f), splitPlace(float, "f", 2000f, 1000f, 40f, rtl = false))
        assertBox(CardBox(800f, 200f, 1800f, 700f), splitPlace(float, "f", 2000f, 1000f, 40f, rtl = true))
        assertNull(splitPlace(split, "gone", 2000f, 1000f, 40f, rtl = false))
    }
}
