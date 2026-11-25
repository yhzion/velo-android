package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTest {
    private val eps = 1e-4f

    @Test fun theFirstFrameIsAnEmptyScreen() {
        val f = splashFrame(0)
        assertEquals(0f, f.trace, eps)
        assertEquals(0f, f.seam, eps)
        assertEquals(0f, f.panes, eps)
        assertEquals(0f, f.cursor, eps)
        assertEquals(0f, f.word, eps)
    }

    @Test fun theBorderTracesFromTheTopLeftInTheFirst480Ms() {
        assertTrue(splashFrame(120).trace in 0.01f..0.99f)
        // Standard easing (0.4, 0, 0.2, 1) is past half way at half the time.
        assertTrue(splashFrame(240).trace > 0.5f)
        assertEquals(1f, splashFrame(480).trace, eps)
        assertEquals(1f, splashFrame(479).trace, 0.01f)
    }

    @Test fun theSeamDropsFrom380To560Ms() {
        assertEquals(0f, splashFrame(380).seam, eps)
        assertTrue(splashFrame(470).seam in 0.01f..0.99f)
        assertEquals(1f, splashFrame(560).seam, eps)
    }

    @Test fun theOutlineGivesWayToTwoPanesFrom560Ms() {
        assertEquals(1f, splashFrame(560).outline, eps)
        assertEquals(0.5f, splashFrame(600).outline, eps)
        assertEquals(0.5f, splashFrame(600).panes, eps)
        assertEquals(0f, splashFrame(640).outline, eps)
        assertEquals(1f, splashFrame(640).panes, eps)
    }

    @Test fun thePanesMoveApartFrom560To760Ms() {
        assertEquals(0f, splashFrame(560).split, eps)
        assertTrue(splashFrame(660).split in 0.5f..0.99f)
        assertEquals(1f, splashFrame(760).split, eps)
        // 4 units of the 136 wide mark at the start, none at the end.
        assertEquals(4f, splashFrame(560).paneShift, eps)
        assertEquals(0f, splashFrame(760).paneShift, eps)
    }

    @Test fun theLeftPaneTakesTheHighlightAndTheCursorAppearsFrom760Ms() {
        assertEquals(0f, splashFrame(760).focus, eps)
        assertEquals(1f, splashFrame(1060).focus, eps)
        assertEquals(0f, splashFrame(940).cursor, eps)
        assertEquals(0.5f, splashFrame(1020).cursor, eps)
        assertEquals(1f, splashFrame(1100).cursor, eps)
    }

    @Test fun theWordmarkRisesFrom1000To1400Ms() {
        assertEquals(0f, splashFrame(1000).word, eps)
        assertEquals(6f, splashFrame(1000).wordDrop, eps)
        assertTrue(splashFrame(1200).word in 0.01f..0.99f)
        assertEquals(1f, splashFrame(1400).word, eps)
        assertEquals(0f, splashFrame(1400).wordDrop, eps)
    }

    @Test fun theLastFrameHoldsAndIsTheReducedMotionFrame() {
        assertEquals(SplashFrame.End, splashFrame(SplashDurationMs))
        assertEquals(SplashFrame.End, splashFrame(5000))
        assertEquals(splashFrame(0), splashFrame(-1))
        with(SplashFrame.End) {
            assertEquals(0f, outline, eps)
            assertEquals(1f, panes, eps)
            assertEquals(1f, focus, eps)
            assertEquals(1f, cursor, eps)
            assertEquals(1f, word, eps)
        }
    }

    @Test fun nothingLoopsOrBounces() {
        // Every part moves one way only and stays in 0..1: no loop, no overshoot.
        var last = splashFrame(0)
        for (t in 1..1600L) {
            val f = splashFrame(t)
            for ((now, was) in f.parts().zip(last.parts())) {
                assertTrue("t=$t $now", now in 0f..1f)
                assertTrue("t=$t", now >= was - eps)
            }
            assertTrue("t=$t", f.outline <= last.outline + eps)
            last = f
        }
    }

    private fun SplashFrame.parts() = listOf(trace, seam, panes, split, focus, cursor, word)

    @Test fun eachDeviceGetsTheBoardsSizes() {
        assertEquals(SplashSize(136f, 96f, 30f, 24f, 60f), splashSize(393f))
        assertEquals(SplashSize(144f, 102f, 32f, 24f, 50f), splashSize(475f))
        assertEquals(SplashSize(176f, 124f, 38f, 30f, 72f), splashSize(821f))
        // A phone held sideways is as wide as the open fold.
        assertEquals(splashSize(821f), splashSize(894f))
    }

    @Test fun reducedMotionWhenAnimationsAreOff() {
        assertTrue(splashReduced(animatorScale = 0f))
        assertFalse(splashReduced(animatorScale = 1f))
        assertFalse(splashReduced(animatorScale = 0.5f))
    }

    @Test fun theAnimatorScaleStretchesTheTimeline() {
        assertEquals(700L, splashTime(elapsedMs = 700, animatorScale = 1f))
        assertEquals(350L, splashTime(elapsedMs = 700, animatorScale = 2f))
        assertEquals(1400L, splashTime(elapsedMs = 700, animatorScale = 0.5f))
    }

    @Test fun onlyAColdStartShowsTheSplash() {
        assertTrue(showSplash(shownInProcess = false, restored = false, deepLink = false))
        // The activity made again (a change it does not handle itself) or the task brought back: no splash.
        assertFalse(showSplash(shownInProcess = true, restored = false, deepLink = false))
        assertFalse(showSplash(shownInProcess = false, restored = true, deepLink = false))
        // Opened from a notification for a pane or an SSH question: straight to it.
        assertFalse(showSplash(shownInProcess = false, restored = false, deepLink = true))
    }

    @Test fun androidsOwnSplashIsTheColourTheSplashStartsOn() {
        val themes = java.io.File("src/main/res/values/themes.xml").readText()
        val colour = Regex("""<color name="velo_splash_background">#([0-9A-F]{6})</color>""").find(themes)!!.groupValues[1]
        val surface = io.github.yhzion.velo.ui.theme.VeloPalettes.dark.sidebar.surface
        assertEquals(androidx.compose.ui.graphics.Color(0xFF000000 or colour.toLong(16)), surface)
        assertTrue(themes.contains("""android:windowSplashScreenBackground">@color/velo_splash_background"""))
    }

    @Test fun theSplashLeavesAfterItsTimelineOrAShortHoldWhenReduced() {
        assertFalse(splashDone(elapsedMs = 1399, reduced = false))
        assertTrue(splashDone(elapsedMs = SplashDurationMs + SplashFadeMs, reduced = false))
        assertFalse(splashDone(elapsedMs = SplashReducedHoldMs - 1, reduced = true))
        assertTrue(splashDone(elapsedMs = SplashReducedHoldMs, reduced = true))
    }

    @Test fun theSplashFadesOutAfterItsTimelineButNotWhenReduced() {
        assertEquals(1f, splashAlpha(elapsedMs = SplashDurationMs, reduced = false), eps)
        assertEquals(0.5f, splashAlpha(elapsedMs = SplashDurationMs + SplashFadeMs / 2, reduced = false), eps)
        assertEquals(1f, splashAlpha(elapsedMs = SplashReducedHoldMs - 1, reduced = true), eps)
    }

    @Test fun theStrokeColoursMeetTheBoardsAtBothEnds() {
        assertEquals(SplashGrey, splashPaneColor(SplashActive, 0f))
        assertEquals(SplashActive, splashPaneColor(SplashActive, 1f))
        assertEquals(SplashOther, splashPaneColor(SplashOther, 1f))
    }
}
