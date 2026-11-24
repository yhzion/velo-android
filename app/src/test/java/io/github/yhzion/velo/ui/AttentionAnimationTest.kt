package io.github.yhzion.velo.ui

import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.SeenReport
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionAnimationTest {
    private val near = 1e-4f
    private val pane = PaneAddress("s", "w", "one")
    private val row = TabRow("s", "w", "Thread", "", "claude", "claude", 1, false, "idle", "", false, block = pane.block)
    private val time = "2026-11-21T01:00:00.123456Z"

    /** What the row draws after its pane reports [state], while the pane is not on screen. */
    private fun unread(state: String, kind: String? = null): TabActivity {
        val read = ActivityReadState()
        read.update(pane, mapOf("r" to SeenReport(state, time, kind = kind)))
        return read.activity(row, true)
    }

    @Test fun anUnseenWaitingOrFailedReportBouncesTheIconAndDoneOrWorkingDoesNot() {
        for (kind in listOf(null, "permission", "question", "auth")) assertTrue(kind.toString(), bouncesIcon(unread("blocked", kind).badge))
        assertTrue(bouncesIcon(unread("error").badge))
        assertFalse(bouncesIcon(unread("done").badge))
        assertFalse(bouncesIcon(unread("working").badge))
        assertFalse(bouncesIcon(unread("idle").badge))
    }

    @Test fun seeingTheTabStopsTheBounceAndANewReportStartsItAgain() {
        val read = ActivityReadState()
        read.update(pane, mapOf("r" to SeenReport("blocked", time, kind = "question")))
        assertTrue(bouncesIcon(read.activity(row, true).badge))
        read.show(1, mapOf(pane to null))
        assertFalse("seen while shown", bouncesIcon(read.activity(row, true).badge))
        read.hide(1)
        assertFalse("still seen after it is hidden", bouncesIcon(read.activity(row, true).badge))
        read.update(pane, mapOf("r" to SeenReport("error", "2026-11-21T01:00:05Z")))
        assertTrue("a new report is unread again", bouncesIcon(read.activity(row, true).badge))
    }

    @Test fun leavingTheWaitingStateStopsTheBounce() {
        val read = ActivityReadState()
        read.update(pane, mapOf("r" to SeenReport("blocked", time, kind = "permission")))
        assertTrue(bouncesIcon(read.activity(row, true).badge))
        read.update(pane, mapOf("r" to SeenReport("working", "2026-11-21T01:00:01Z")))
        assertFalse(bouncesIcon(read.activity(row, true).badge))
    }

    @Test fun aBounceTakesSevenTenthsAndRestsThreeSecondsInALoop() {
        assertEquals(0f, bounceProgress(0), near)
        assertEquals(0.5f, bounceProgress(350), near)
        assertEquals(1f, bounceProgress(700), near)
        assertEquals(1f, bounceProgress(3699), near)
        assertEquals(0f, bounceProgress(3700), near)
        assertEquals(0.5f, bounceProgress(3700 + 350), near)
    }

    @Test fun theIconJumpsOnceAndReboundsTwiceThenRestsInPlace() {
        // Rex for Mac: (0.12 × 0.35 + 0.82) × 0.5 of the icon's height at the top of the jump.
        assertEquals(0.431f, bounceLift(0.3f), near)
        assertEquals(0f, bounceLift(0f), near)
        assertEquals(0f, bounceLift(0.6f), near)
        val rebound = 0.12f * 0.2f + 0.28f * 0.32f + 0.08f
        assertEquals(0.431f * rebound, bounceLift(0.72f), near)
        assertEquals(0.431f * rebound * rebound, bounceLift(0.92f), near)
        assertEquals(0f, bounceLift(1f), near)
        for (step in 0..100) assertTrue(bounceLift(step / 100f) >= -near)
    }

    @Test fun theIconFlattensWhenItLands() {
        assertEquals(0.05f, bounceSquash(0.6f), 0.001f)
        assertEquals(0.05f * 0.35f, bounceSquash(0.84f), 0.001f)
        assertTrue(bounceSquash(0.3f) < 0.001f)
        assertEquals(0f, bounceSquash(1f), near)
    }

    @Test fun onlyAnUnseenWaitingReportWithWordsRunsInTheTitle() {
        val words = "Allow edits to build.gradle?"
        for (kind in listOf(null, "permission", "question", "auth")) assertTrue(showsTicker(unread("blocked", kind).badge, words))
        assertFalse("error has no message", showsTicker(unread("error").badge, words))
        assertFalse(showsTicker(unread("done").badge, words))
        assertFalse(showsTicker(unread("working").badge, words))
        assertFalse("no words", showsTicker(unread("blocked").badge, " "))
        assertFalse(showsTicker(unread("blocked").badge, null))
        val read = ActivityReadState()
        read.update(pane, mapOf("r" to SeenReport("blocked", time, kind = "question")))
        read.show(1, mapOf(pane to null))
        assertFalse("seen", showsTicker(read.activity(row, true).badge, words))
    }

    @Test fun theMessageIsTheLeadPanesWordsWhileItWaits() {
        val waiting = TabInfo("w", block = "one", activity = "blocked", statusSummary = "Approve deploy?", kind = "permission")
        val state = UiState(status = "connected", sessions = listOf(SessionInfo("s", "s", listOf(waiting))), session = "s")
        val blocked = row.copy(activity = "blocked")
        assertEquals("Approve deploy?", tabMessage(state, blocked))
        assertNull("not waiting", tabMessage(state, row.copy(activity = "done")))
        assertNull("the words are already its name", tabMessage(state, blocked.copy(name = "Approve deploy?")))
        val silent = state.copy(sessions = listOf(SessionInfo("s", "s", listOf(waiting.copy(statusSummary = "")))))
        assertNull(tabMessage(silent, blocked))
    }

    @Test fun aMessageThatFitsStandsAndOneThatDoesNotRunsLeftAfterAPause() {
        assertFalse(tickerScrolls(100f, 120f))
        assertTrue(tickerScrolls(130f, 120f))
        assertEquals(0f, tickerOffset(0, 200f), near)
        assertEquals(0f, tickerOffset(1499, 200f), near)
        assertEquals(-34f, tickerOffset(2500, 200f), near)
        // One run moves the message by its width and the gap, then it starts over at its start.
        val run = tickerRunMs(200f)
        assertEquals(1500L + (228f / 34f * 1000).toLong(), run)
        assertEquals(0f, tickerOffset(run, 200f), near)
    }

    @Test fun theMessageShowsAtLeastFiveSecondsAndLongerWhileItRuns() {
        assertEquals(5000L, tickerMessageMs(100f, 120f))
        assertEquals((158f / 34f * 1000).toLong() + 3000, tickerMessageMs(130f, 120f))
        assertEquals((372f / 34f * 1000).toLong() + 3000, tickerMessageMs(344f, 120f))
        assertEquals(4000L, TickerTitleMs)
        assertEquals(300, TickerFadeMs)
    }

    @Test fun animationsOffStopsTheMotionAndTheScaleStretchesIt() {
        assertFalse(animates(0f))
        assertEquals(0L, motionMs(10_000, 0f))
        assertEquals(1000L, motionMs(1000, 1f))
        assertEquals(500L, motionMs(1000, 2f))
        assertEquals(0f, bounceLift(bounceProgress(motionMs(300, 0f))), near)
    }
}
