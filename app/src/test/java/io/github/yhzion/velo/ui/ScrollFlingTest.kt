package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The terminal's scroll fling (#384): its length, its cap, its stop, and the speed a leaving finger had. */
class ScrollFlingTest {

    @Test fun theLengthFollowsTheSpeedAndTheTimeConstant() {
        val fling = ScrollFling(0f, 1000f, density = 1f)
        var y = 0f
        repeat(1200) { val step = fling.step(1f / 120f); y += step.second }
        assertEquals(395f, y, 3f)                        // 1000 · 0.4 · (1 − 12/1000)
    }

    @Test fun theSpeedIsCappedInDp() {
        val fling = ScrollFling(0f, 50_000f, density = 2f)
        assertEquals(12_000f, fling.step(1f / 120f).second * 120, 100f)   // 6000 dp · 2
    }

    @Test fun itStopsBelowTwelveDpASecond() {
        val fling = ScrollFling(0f, 300f, density = 1f)
        var frames = 0
        while (fling.moving) { fling.step(1f / 120f); frames++ }
        assertTrue(frames in 140..170)                   // 120 Hz · 0.4 s · ln(300/12) ≈ 155 frames
    }

    @Test fun aStillFingerGivesNoFling() {
        assertFalse(ScrollFling(0f, 0f, 1f).moving)
        assertEquals(0f, flingVelocity(listOf(ScrollSample(900L, 0f), ScrollSample(1000L, -40f)), 1100L), 0f)
        assertEquals(-400f, flingVelocity(listOf(ScrollSample(900L, 0f), ScrollSample(1000L, -40f)), 1040L), 0.01f)
        // One place in the last 100 ms: the two last, up to 260 ms apart.
        assertEquals(-200f, flingVelocity(listOf(ScrollSample(850L, 0f), ScrollSample(1000L, -30f)), 1010L), 0.01f)
    }
}
