package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finger on the panes "a" and "b" of a fake workspace. The test controls the clock and records
 * pane taps separately from keys: even with the IME already open, a gesture must not request it.
 */
private class Fingers {
    var clock = 0L
    val sent = mutableListOf<Pair<String, TapKey>>()
    private var waiting: Pair<Runnable, Long>? = null
    var target: String? = "a"
        set(value) {
            // The workspace counts each change of the input target ([HostConnection.focusMoves]).
            if (value != field) moves++
            field = value
        }
    var moves = 0L
    /** A selection in the pane, which has priority over tap gestures. */
    var selecting = false
    var double = TapKey.Tab
    var triple = TapKey.Enter
    var on = true
    /** The taps that were the pane's own (focus, keyboard). */
    val ownTaps = mutableListOf<String>()

    val input = TapGestureInput<String>(300, { 100f },
        post = { r, ms -> waiting = r to clock + ms },
        unpost = { r -> if (waiting?.first === r) waiting = null },
        now = { clock },
    ).also {
        it.keys = { if (on) double to triple else null }
        it.target = { target }
        it.paneSelects = { selecting }
        it.moves = { moves }
        it.send = { key -> sent += target!! to key }
    }

    /** A finger down at [at] on [pane], up 50 ms later as a tap. */
    fun tap(pane: String, at: Long, x: Float = 10f) {
        until(at)
        input.down(pane, at, x, 10f)
        clock = at + 50
        val ownTap = {
            ownTaps += pane
            // The pane's own tap moves the input to it.
            target = pane
        }
        if (!input.tap(pane, clock, ownTap)) ownTap()
    }

    /**
     * The app sees the new input target as the view does: it keeps the run (it does not end it),
     * so the run b's first tap began goes on making no key.
     */
    fun focusArrived() {
        moves++
    }

    /** The time goes on to [at]: the wait for a third tap may end. */
    fun until(at: Long) {
        clock = at
        waiting?.let { (r, due) -> if (due <= at) { waiting = null; r.run() } }
    }
}

class TapGesturesTest {

    @Test fun aLoneTapBecomesThePanesOwnExactlyAtTheDeadline() {
        val f = Fingers()
        f.tap("a", 0)
        f.until(349)
        assertTrue(f.ownTaps.isEmpty())
        f.until(350)
        assertEquals(listOf("a"), f.ownTaps)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
    }

    @Test fun twoTapsSendTheDoubleKeyOnceAfterTheWaitForAThird() {
        val f = Fingers()
        f.tap("a", 0)
        f.tap("a", 150)
        // The triple key is set: the double waits for the time of a third finger.
        f.until(150 + 50 + 299)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
        f.until(2000)
        assertEquals(listOf("a" to TapKey.Tab), f.sent)
        assertTrue(f.ownTaps.isEmpty())
    }

    @Test fun threeTapsSendOnlyTheTripleKey() {
        val f = Fingers()
        f.tap("a", 0)
        f.tap("a", 150)
        f.tap("a", 300)
        f.until(2000)
        assertEquals(listOf("a" to TapKey.Enter), f.sent)
        assertTrue(f.ownTaps.isEmpty())
    }

    @Test fun aThirdFingerThatGoesDownInTimeDecidesEvenWhenItLiftsLate() {
        // Android counts a tap from the last up to the next down: a slow third tap is still one.
        val f = Fingers()
        f.tap("a", 0)
        f.tap("a", 150)
        f.clock = 450
        f.input.down("a", 450, 10f, 10f)
        // Held past the wait: no double goes while the finger is down.
        f.until(900)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
        f.clock = 950
        assertTrue(f.input.tap("a", 950) { error("third tap is not a single") })
        assertEquals(listOf("a" to TapKey.Enter), f.sent)
    }

    @Test fun aThirdFingerThatScrollsOrPressesLongSendsNothing() {
        val f = Fingers()
        f.tap("a", 0)
        f.tap("a", 150)
        f.clock = 400
        f.input.down("a", 400, 10f, 10f)
        // The pane ends the run for a scroll, a long press, more fingers.
        f.input.cancel()
        f.until(5000)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
    }

    @Test fun withoutATripleKeyTheSecondTapSendsTheDoubleKeyAtOnce() {
        val f = Fingers().apply { triple = TapKey.None }
        f.tap("a", 0)
        f.tap("a", 150)
        assertEquals(listOf("a" to TapKey.Tab), f.sent)
        // With no triple key, the extra tap of a quick triple must not show the keyboard.
        f.tap("a", 300)
        assertTrue(f.ownTaps.isEmpty())
        f.until(650)
        assertTrue(f.ownTaps.isEmpty())
        assertEquals(listOf("a" to TapKey.Tab), f.sent)
        f.tap("a", 800)
        f.until(1150)
        assertEquals(listOf("a"), f.ownTaps)
    }

    @Test fun aDoubleTapSetToNoneSendsNothingAndATripleStillCounts() {
        val f = Fingers().apply { double = TapKey.None }
        f.tap("a", 0)
        f.tap("a", 150)
        f.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
        f.tap("a", 3000)
        f.tap("a", 3150)
        f.tap("a", 3300)
        assertEquals(listOf("a" to TapKey.Enter), f.sent)
        assertTrue(f.ownTaps.isEmpty())
    }

    @Test fun tapsThatBeginOnAnotherPaneSendNoKeyToEither() {
        // "a" takes keys; the first tap on "b" moves the input there and is b's own tap. The view
        // hears of the new input target between the taps, as when the server answers at once.
        val f = Fingers()
        f.tap("b", 0)
        f.focusArrived()
        f.tap("b", 150)
        f.tap("b", 300)
        f.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
        // Every tap of the run b's first tap began is b's own (focus, keyboard): no key for "a" or "b".
        assertEquals(listOf("b", "b", "b"), f.ownTaps)
    }

    @Test fun aSwitchAcrossPanesEndsTheRun() {
        // a, then b (b's own tap), then b again: no run of b began on a pane that took keys.
        val f = Fingers()
        f.tap("a", 0)
        f.tap("b", 150)
        f.tap("b", 300)
        f.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
        assertEquals(listOf("b", "b"), f.ownTaps)
    }

    @Test fun aTapTooLateOrTooFarStartsANewRun() {
        val late = Fingers()
        late.tap("a", 0)
        late.tap("a", 50 + 301)
        late.until(5000)
        assertEquals(emptyList<Pair<String, TapKey>>(), late.sent)
        assertEquals(listOf("a", "a"), late.ownTaps)

        val far = Fingers()
        far.tap("a", 0)
        far.tap("a", 150, x = 10f + 101)
        far.until(5000)
        assertEquals(emptyList<Pair<String, TapKey>>(), far.sent)
        // The new down takes the first single out of the queue, also its click in a program that
        // takes the mouse (#307): only the second tap's single is made.
        assertEquals(listOf("a"), far.ownTaps)
    }

    @Test fun aLateTapWithoutItsOwnDownStaysOutOfTheRun() {
        // A tap on a path that names no file comes back as the pane's tap once the server says so
        // (#225): it has no down of its own and its time is long past. It counts in no run, and the
        // run begun since keeps its single; the caller makes the late tap's own at once.
        for (triple in listOf(TapKey.Enter, TapKey.None)) {
            val f = Fingers().apply { this.triple = triple }
            f.tap("a", 1000)
            var late = 0
            assertFalse(f.input.tap("a", 0) { late++ })
            f.until(5000)
            assertEquals(0, late)
            assertEquals(listOf("a"), f.ownTaps)
            assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
        }
    }

    @Test fun secondFingerHoldsTheSingleUntilItDecidesOrCancels() {
        val f = Fingers()
        f.tap("a", 0)
        f.input.down("a", 349, 10f, 10f)
        f.until(450)
        assertTrue(f.ownTaps.isEmpty())
        f.input.cancel() // a drag, long press, additional finger or cancelled motion
        f.until(1000)
        assertTrue(f.ownTaps.isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test fun aSecondTapWithALateUpReplacesTheSingleRatherThanOpeningTheIme() {
        val f = Fingers()
        f.tap("a", 0)
        f.input.down("a", 349, 10f, 10f)
        f.until(450)
        assertTrue(f.input.tap("a", 450) { error("second tap is not a single") })
        f.until(750)
        assertTrue(f.ownTaps.isEmpty())
        assertEquals(listOf("a" to TapKey.Tab), f.sent)
    }

    @Test fun deferredSingleCannotReopenKeyboardAfterLosingEligibility() {
        val interruptions = listOf<Fingers.() -> Unit>(
            { input.cancel() }, { target = null }, { target = "b"; target = "a" },
            { selecting = true }, { on = false },
        )
        for (interrupt in interruptions) {
            val f = Fingers()
            f.tap("a", 0)
            f.interrupt()
            f.until(500)
            assertTrue(f.ownTaps.isEmpty())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun aWaitingKeyIsDroppedWhenThePaneStopsTakingKeys() {
        // A menu or a dialog opened, the sidebar covered the pane, the connection was lost.
        val f = Fingers()
        f.tap("a", 0)
        f.tap("a", 150)
        f.target = null
        f.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)

        // Taken away and given back before the wait ends: the old key is gone too, as the
        // workspace ends the run when the pane stops taking keys.
        val g = Fingers()
        g.tap("a", 0)
        g.tap("a", 150)
        g.input.cancel()
        g.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), g.sent)
    }

    @Test fun theKeyGoesToThePaneThatMadeItOrNowhere() {
        // The input moved to "b" while a's double waited: nothing goes to b.
        val f = Fingers()
        f.tap("a", 0)
        f.tap("a", 150)
        f.target = "b"
        f.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
    }

    @Test fun aFocusThatLeavesThePaneAndComesBackDropsItsWaitingKey() {
        // Another client moves the focus to "b" and back to "a" while a's double waits.
        val f = Fingers()
        f.tap("a", 0)
        f.tap("a", 150)
        f.target = "b"
        f.target = "a"
        f.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
        // The next run on "a" makes keys again.
        f.tap("a", 3000)
        f.tap("a", 3150)
        f.until(5000)
        assertEquals(listOf("a" to TapKey.Tab), f.sent)
    }

    @Test fun aSelectionKeepsItsTaps() {
        val f = Fingers().apply { selecting = true }
        f.tap("a", 0)
        f.tap("a", 150)
        f.tap("a", 300)
        f.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), f.sent)
        assertEquals(listOf("a", "a", "a"), f.ownTaps)

        // A selection made while the double waited: it is not sent.
        val g = Fingers()
        g.tap("a", 0)
        g.tap("a", 150)
        g.selecting = true
        g.until(2000)
        assertEquals(emptyList<Pair<String, TapKey>>(), g.sent)
    }

    @Test fun offEveryTapIsThePanesOwn() {
        val f = Fingers().apply { on = false }
        f.tap("a", 0)
        f.tap("a", 150)
        f.tap("a", 300)
        assertEquals(listOf("a", "a", "a"), f.ownTaps)
        assertFalse(f.sent.isNotEmpty())
    }

    @Test fun theSavedKeysReadBackAndAnUnknownOneFallsBack() {
        for (key in TapKey.entries) assertEquals(key, TapKey.parse(key.saved, TapKey.Esc))
        assertEquals(TapKey.Tab, TapKey.parse(null, TapKey.Tab))
        assertEquals(TapKey.Enter, TapKey.parse("Gone", TapKey.Enter))
    }
}
