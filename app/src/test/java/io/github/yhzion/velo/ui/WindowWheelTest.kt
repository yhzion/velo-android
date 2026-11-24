package io.github.yhzion.velo.ui

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rail's window wheel (#304): ticks, ends, its speed limit, and the strip's finger. */
class WindowWheelTest {

    /** Turns a held wheel by [windows] in steps of [step] windows, one frame of [frame] s each; the ticks it felt. */
    private fun WindowWheel.turn(windows: Float, step: Float = 0.05f, frame: Float = 1f / 60): Int {
        var ticks = 0
        var left = windows
        while (kotlin.math.abs(left) > 1e-6f) {
            val d = if (kotlin.math.abs(left) < step) left else step * kotlin.math.sign(left)
            drag(d)
            ticks += advance(frame)
            left -= d
        }
        return ticks
    }

    @Test
    fun eachWindowTurnedPastTicksOnce() {
        val wheel = WindowWheel(12, start = 2)
        assertEquals(3, wheel.turn(3f))
        assertEquals(5, wheel.index)
        assertEquals(3, wheel.turn(-3f))
        assertEquals(2, wheel.index)
    }

    @Test
    fun aFingerRestingOnTheLineBetweenTwoWindowsTicksOnce() {
        val wheel = WindowWheel(5, start = 1)
        // To just past the line, then back and forth across it within the hysteresis.
        var ticks = wheel.turn(0.62f)
        assertEquals(1, ticks)
        repeat(20) { ticks += wheel.turn(-0.08f) + wheel.turn(0.08f) }
        assertEquals(1, ticks)
        assertEquals(2, wheel.index)
    }

    @Test
    fun theNextWindowComesOnlyPastTheLineAndATenth() {
        val wheel = WindowWheel(5, start = 0)
        assertEquals(0, wheel.turn(0.55f))
        assertEquals(0, wheel.index)
        assertEquals(1, wheel.turn(0.1f))
        assertEquals(1, wheel.index)
    }

    @Test
    fun theWheelTurnsNoFasterThanItsLimitAndCatchesUp() {
        val wheel = WindowWheel(30, start = 0)
        // Ten windows in one frame of a 60 Hz screen: the wheel moves its limit, two thirds of a window.
        wheel.drag(10f)
        var ticks = wheel.advance(1f / 60)
        assertEquals(WheelMaxSpeed / 60, wheel.position, 1e-4f)
        // It goes on toward the finger frame by frame, every window ticked, never two in one frame.
        var most = ticks
        repeat(40) { val t = wheel.advance(1f / 60); ticks += t; most = maxOf(most, t) }
        assertEquals(10, ticks)
        assertEquals(1, most)
        assertEquals(10f, wheel.position, 1e-4f)
    }

    @Test
    fun anEndHoldsAndIsFeltOnceUntilTheFingerComesBack() {
        val wheel = WindowWheel(3, start = 2)
        assertNull(wheel.drag(0.3f))
        assertEquals(WheelTick.End, wheel.drag(0.1f))
        assertNull(wheel.drag(0.5f))
        // Held back: never three quarters of a window past the end.
        repeat(30) { wheel.advance(1f / 60) }
        assertTrue(wheel.position < 2.75f)
        assertTrue(wheel.position > 2f)
        assertEquals(2, wheel.index)
        // Back within the end, then past it again: felt again.
        assertNull(wheel.drag(-1.5f))
        assertEquals(WheelTick.End, wheel.drag(1.2f))
    }

    @Test
    fun theStartingEndIsFeltToo() {
        val wheel = WindowWheel(4, start = 0)
        assertEquals(WheelTick.End, wheel.drag(-0.4f))
        repeat(20) { wheel.advance(1f / 60) }
        assertEquals(0, wheel.index)
        assertTrue(wheel.position < 0f)
    }

    @Test
    fun letGoTheWheelRestsOnItsWindowWithoutATick() {
        val wheel = WindowWheel(6, start = 1)
        wheel.turn(1.58f)
        assertEquals(2, wheel.index)
        wheel.release()
        var ticks = 0
        repeat(60) { ticks += wheel.advance(1f / 60) }
        assertEquals(0, ticks)
        assertTrue(wheel.resting)
        assertEquals(2f, wheel.position, 0f)
        // A let-go wheel takes no more pull.
        assertNull(wheel.drag(3f))
        assertEquals(2, wheel.index)
    }

    @Test
    fun aFastFingerTurnsTheWheelFarther() {
        assertEquals(1f, wheelGain(0.1f), 0f)
        assertEquals(1f, wheelGain(0.35f), 0f)
        assertEquals(2.6f, wheelGain(1.6f), 1e-5f)
        assertEquals(2.6f, wheelGain(5f), 1e-5f)
        assertTrue(wheelGain(0.9f) in 1.5f..2.2f)
    }

    @Test
    fun aPullPastAnEndGivesLessAndLess() {
        assertEquals(0f, wheelRubber(0f), 0f)
        assertTrue(wheelRubber(0.5f) < 0.5f)
        assertTrue(wheelRubber(1f) > wheelRubber(0.5f))
        assertTrue(wheelRubber(100f) < 0.75f)
    }

    @Test
    fun theRingFillsAfterTheScrollsChanceUntilTheLongPress() {
        assertEquals(0f, pressCharge(50, 400), 0f)
        assertEquals(0f, pressCharge(ChargeDelayMs, 400), 0f)
        assertEquals(0.5f, pressCharge(ChargeDelayMs + 145, 400), 1e-4f)
        assertEquals(1f, pressCharge(400, 400), 0f)
        assertEquals(1f, pressCharge(900, 400), 0f)
        // A long press shorter than the ring's wait is full at once.
        assertEquals(1f, pressCharge(10, 100), 0f)
    }

    @Test
    fun aFingerScrollsOncePastTheSlopAndIsGoneWhenLiftedOrJoined() {
        assertEquals(StripFinger.Holding, stripFinger(2f, 3f, slop = 8f, pressed = true, fingers = 1))
        assertEquals(StripFinger.Scrolls, stripFinger(0f, -9f, slop = 8f, pressed = true, fingers = 1))
        assertEquals(StripFinger.Scrolls, stripFinger(9f, 0f, slop = 8f, pressed = true, fingers = 1))
        assertEquals(StripFinger.Gone, stripFinger(0f, 0f, slop = 8f, pressed = false, fingers = 1))
        assertEquals(StripFinger.Gone, stripFinger(0f, 0f, slop = 8f, pressed = true, fingers = 2))
    }

    @Test
    fun theTickIsTheDialsWhereTheSystemHasIt() {
        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, wheelTickFeedback(34))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, wheelTickFeedback(33))
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE, wheelEndFeedback(37))
        assertNull(wheelEndFeedback(33))
    }

    @Test
    fun theDrumShowsThreeTitlesEachSideTurnedLessThanARightAngle() {
        val titles = drumTitles(position = 5f, count = 12, open = 1f)
        assertEquals((3..7).toList(), titles.map { it.index })
        assertEquals(0f, titles.first { it.index == 5 }.angle, 0f)
        assertEquals(WheelTitleAngle, titles.first { it.index == 6 }.angle, 1e-4f)
        // Near an end there are fewer; a closed drum shows none.
        assertEquals(listOf(0, 1, 2), drumTitles(0f, 12, 1f).map { it.index })
        assertTrue(drumTitles(5f, 12, 0f).isEmpty())
        // Opening, the titles unroll from the front.
        assertTrue(drumTitles(5f, 12, 0.2f).first { it.index == 6 }.angle < WheelTitleAngle)
    }

    @Test
    fun aScreenReadersStepMovesAtLeastOneWindow() {
        // Two windows: the reader moves the slider by a twentieth, and still reaches the other window.
        assertEquals(1, wheelStepTo(0, 1.05f, 2))
        assertEquals(0, wheelStepTo(1, 1.95f, 2))
        // Steps of one, and a set value far away, go where they were asked.
        assertEquals(4, wheelStepTo(3, 5f, 12))
        assertEquals(9, wheelStepTo(3, 10f, 12))
        assertEquals(3, wheelStepTo(3, 4f, 12))
        // Never past an end.
        assertEquals(11, wheelStepTo(11, 12.4f, 12))
        assertEquals(0, wheelStepTo(0, 0.6f, 12))
    }

    @Test
    fun aScreenReaderHearsTheWindowAndItsPlace() {
        assertEquals("build, 3 of 12", wheelValueText("build", 2, 12))
    }

    @Test
    fun aWheelOfOneWindowStaysOnIt() {
        val wheel = WindowWheel(1, start = 0)
        assertEquals(0, wheel.turn(2f))
        assertEquals(0, wheel.index)
        assertFalse(wheel.resting)
    }
}
