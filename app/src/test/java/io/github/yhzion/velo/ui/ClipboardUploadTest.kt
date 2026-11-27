package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A clipboard file's paste waits for its pane's focus, 2 s at most (#106 review, finding 1). */
class ClipboardUploadTest {
    @Test fun focusedPaneIsTakenAtOnce() {
        assertEquals(FocusWait.Take, focusWait(focused = true, waitedMs = 0))
    }

    @Test fun focusThatArrivesAtTheBoundIsStillTaken() {
        assertEquals(FocusWait.Take, focusWait(focused = true, waitedMs = FOCUS_WAIT_MS + 10))
    }

    @Test fun unfocusedPaneWaitsUntilTheBound() {
        assertEquals(FocusWait.Wait, focusWait(focused = false, waitedMs = 0))
        assertEquals(FocusWait.Wait, focusWait(focused = false, waitedMs = FOCUS_WAIT_MS - 1))
        assertEquals(FocusWait.GiveUp, focusWait(focused = false, waitedMs = FOCUS_WAIT_MS))
    }

    @Test fun waitIsTwoSeconds() {
        assertEquals(2_000L, FOCUS_WAIT_MS)
        assertEquals(60_000L, READ_LIMIT_MS)
    }
}
