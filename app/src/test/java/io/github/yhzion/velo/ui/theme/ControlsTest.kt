package io.github.yhzion.velo.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsTest {
    private val fontSizes = 8f..24f

    @Test fun theSliderEndsAreTheRangeEnds() {
        assertEquals(8f, sliderValue(0f, fontSizes, steps = 15), 0f)
        assertEquals(24f, sliderValue(1f, fontSizes, steps = 15), 0f)
        assertEquals(8f, sliderValue(-0.3f, fontSizes, steps = 15), 0f)
        assertEquals(24f, sliderValue(1.7f, fontSizes, steps = 15), 0f)
    }

    @Test fun theSliderStopsAtTheNearestStep() {
        // 15 steps between 8 and 24: one stop per whole size.
        assertEquals(16f, sliderValue(0.5f, fontSizes, steps = 15), 0.0001f)
        assertEquals(16f, sliderValue(0.53f, fontSizes, steps = 15), 0.0001f)
        assertEquals(17f, sliderValue(0.54f, fontSizes, steps = 15), 0.0001f)
    }

    @Test fun onlyASteppedSliderTicksAndOnlyWhenItsStopChanges() {
        assertTrue(sliderTicks(steps = 15, from = 12f, to = 13f))
        assertFalse(sliderTicks(steps = 15, from = 13f, to = 13f))
        // Without steps the value changes on every move: there is no stop to feel.
        assertFalse(sliderTicks(steps = 0, from = 0.5f, to = 0.6f))
    }

    @Test fun withoutStepsEveryValueIsOne() {
        assertEquals(12.8f, sliderValue(0.3f, fontSizes, steps = 0), 0.0001f)
    }

    @Test fun theKnobsCentreTravelsBetweenItsInsets() {
        // A track 300 px wide with a knob 28 px across: the centre goes from 14 to 286.
        assertEquals(0f, sliderFraction(14f, 300f, 14f), 0f)
        assertEquals(1f, sliderFraction(286f, 300f, 14f), 0f)
        assertEquals(0.5f, sliderFraction(150f, 300f, 14f), 0.0001f)
        assertEquals(0f, sliderFraction(-40f, 300f, 14f), 0f)
        assertEquals(1f, sliderFraction(400f, 300f, 14f), 0f)
        // A track no wider than the knob has nowhere to go.
        assertEquals(0f, sliderFraction(10f, 20f, 14f), 0f)
    }

    @Test fun cellsThatFitShareTheRoomLeftOver() {
        assertEquals(listOf(10, 20), segmentWidths(listOf(10, 20), min = 0, max = 100))
        assertEquals(listOf(15, 25), segmentWidths(listOf(10, 20), min = 40, max = 100))
        assertEquals(listOf(16, 25), segmentWidths(listOf(10, 20), min = 41, max = 100))
    }

    @Test fun cellsThatDoNotFitAreCutByOneShare() {
        val widths = segmentWidths(listOf(60, 40), min = 0, max = 50)
        assertEquals(listOf(30, 20), widths)
        val odd = segmentWidths(listOf(33, 33, 34), min = 0, max = 50)
        assertEquals(true, odd.sum() <= 50)
    }

    @Test fun noCellsNoWidths() {
        assertEquals(emptyList<Int>(), segmentWidths(emptyList(), min = 10, max = 100))
    }
}
