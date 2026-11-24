package io.github.yhzion.velo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a pane view that goes away is covered or closed, and which panes come back from under a cover. */
class PaneCoverTest {

    @Test
    fun aPaneThatGoesAwayUnderACoverIsNotClosedAndComesBackOnce() {
        val cover = PaneCover()
        cover.draws(setOf("t", "u"))
        // The sidebar takes the panes' place, or Settings opens: the views go.
        cover.begin()
        assertTrue(cover.leaves("t"))
        assertTrue(cover.leaves("u"))
        // The same tab is drawn again: each pane is back, for the first view that shows it.
        cover.draws(setOf("t", "u"))
        assertTrue(cover.returns("t"))
        assertTrue(cover.returns("u"))
        assertFalse("a return is used once", cover.returns("t"))
    }

    @Test
    fun aPaneThatGoesAwayWithoutACoverIsClosed() {
        // A tab switch, a zoom, another remote, the pane's own end: the view reports no surface.
        val cover = PaneCover()
        cover.draws(setOf("t"))
        assertFalse(cover.leaves("t"))
        cover.draws(setOf("t"))
        assertFalse(cover.returns("t"))
    }

    @Test
    fun aPaneThatWasNeverCoveredDoesNotComeBack() {
        // A pane made while the workspace was covered is shown by the usual rules.
        val cover = PaneCover()
        cover.draws(setOf("t"))
        cover.begin()
        assertTrue(cover.leaves("t"))
        cover.draws(setOf("t", "new"))
        assertFalse(cover.returns("new"))
        assertTrue(cover.returns("t"))
    }

    @Test
    fun nothingComesBackWhileTheCoverLasts() {
        val cover = PaneCover()
        cover.begin()
        assertTrue(cover.leaves("t"))
        assertFalse(cover.returns("t"))
        // Told again by every composition of a covering screen: still one cover.
        cover.begin()
        cover.draws(setOf("t"))
        assertTrue(cover.returns("t"))
    }

    @Test
    fun aPaneThatClosedWhileItWasCoveredIsNotKept() {
        // Its block left the connection's state: the same block ID later is another pane.
        val gone = PaneCover()
        gone.begin()
        assertTrue(gone.leaves("t"))
        assertTrue(gone.leaves("u"))
        gone.keep(setOf("u"))
        gone.keep(setOf("t", "u"))
        gone.draws(setOf("t", "u"))
        assertFalse(gone.returns("t"))
        assertTrue(gone.returns("u"))

        // It left the tab that is drawn when the cover ends: the same.
        val closed = PaneCover()
        closed.begin()
        assertTrue(closed.leaves("t"))
        assertTrue(closed.leaves("u"))
        closed.draws(setOf("u"))
        closed.draws(setOf("t", "u"))
        assertFalse(closed.returns("t"))
        assertTrue(closed.returns("u"))
    }

    @Test
    fun anotherTabDrawnAfterTheCoverIsNoReturn() {
        // A tab or a session picked in the sidebar: its panes are shown by the take rules.
        val cover = PaneCover()
        cover.draws(setOf("t"))
        cover.begin()
        assertTrue(cover.leaves("t"))
        cover.draws(setOf("x"))
        assertFalse(cover.returns("x"))
        // And the way back to the first tab is a tab switch like any other.
        cover.draws(setOf("t"))
        assertFalse(cover.returns("t"))
    }

    @Test
    fun aPaneWhoseNewViewNeverGotASurfaceStillComesBack() {
        // The sidebar was closed and opened again at once: the new view told the core nothing.
        val cover = PaneCover()
        cover.begin()
        assertTrue(cover.leaves("t"))
        cover.draws(setOf("t"))
        cover.begin()
        cover.draws(setOf("t"))
        assertTrue(cover.returns("t"))
    }

    @Test
    fun aRemoteThatIsLeftForAnotherForgetsItsCover() {
        // Selecting a remote shows its panes by the usual rules, also from the covering sidebar.
        val cover = PaneCover()
        cover.begin()
        assertTrue(cover.leaves("t"))
        cover.forget()
        assertFalse("its views that still go are closed", cover.leaves("u"))
        cover.draws(setOf("t", "u"))
        assertFalse(cover.returns("t"))
        assertFalse(cover.returns("u"))
    }
}
