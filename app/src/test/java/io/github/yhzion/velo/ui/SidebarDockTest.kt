package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.veloSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the open sidebar goes on each device shape (WsBarSidebar, WsClosedSidebar, WsOpenDocked boards). */
class SidebarDockTest {
    private class Shape(val w: Float, val h: Float)

    private val bar = Shape(393f, 894f)
    private val closed = Shape(475f, 751f)
    private val open = Shape(821f, 1088f)
    private val openSideways = Shape(1088f, 821f)

    /** The default pane style at [size]: the gap between the sidebar and the panes is also the outer margin. */
    private fun place(shape: Shape, docked: Boolean, size: UiSize = UiSize.Medium): SidebarPlace {
        val s = veloSizes(size)
        return sidebarPlace(shape.w, shape.h, s.sidebar.value, s.rail.value, s.gap.value, s.gap.value, docked)
    }

    private fun dockable(shape: Shape, size: UiSize = UiSize.Medium): Boolean {
        val s = veloSizes(size)
        return sidebarDockable(shape.w, shape.h, s.sidebar.value, s.rail.value, s.gap.value, s.gap.value)
    }

    @Test fun theBarPhoneAndTheFoldedScreenAlwaysCoverThePanes() {
        for (shape in listOf(bar, closed, Shape(751f, 475f))) {
            assertFalse(dockable(shape))
            assertEquals(SidebarPlace.Covers, place(shape, docked = false))
            // A docking chosen on the unfolded screen does not reach the folded one.
            assertEquals(SidebarPlace.Covers, place(shape, docked = true))
        }
    }

    @Test fun theUnfoldedScreenUprightDocksOnlyWhenChosen() {
        assertTrue(dockable(open))
        assertEquals(SidebarPlace.Covers, place(open, docked = false))
        assertEquals(SidebarPlace.Beside, place(open, docked = true))
    }

    @Test fun docked_thePanesKeepTheBoardsWidth() {
        // WsOpenDocked: 8, the sidebar 320, 8, the panes 409, 8, the rail 60, 8.
        val s = veloSizes(UiSize.Medium)
        assertEquals(320f, s.sidebar.value, 0.01f)
        assertEquals(60f, s.rail.value, 0.01f)
        assertEquals(409f, dockedPanesWidth(open.w, s.sidebar.value, s.rail.value, s.gap.value, s.gap.value), 0.01f)
    }

    @Test fun aWindowWideEnoughHasTheSidebarBesideThePanesAnyway() {
        // Nothing to choose there: the sidebar never covers the panes.
        assertFalse(dockable(openSideways))
        assertEquals(SidebarPlace.Beside, place(openSideways, docked = false))
        assertEquals(SidebarPlace.Beside, place(openSideways, docked = true))
    }

    @Test fun aWindowTooNarrowForTwoColumnsNeverDocks() {
        // Not compact (600 on its short side), but docking would leave the panes a strip.
        val narrow = Shape(640f, 900f)
        assertFalse(dockable(narrow))
        assertEquals(SidebarPlace.Covers, place(narrow, docked = true))
        // The least the docked panes keep is the bar phone's pane column (309 at Medium).
        val s = veloSizes(UiSize.Medium)
        val edge = MinPanesDockedDp + s.sidebar.value + s.rail.value + 4 * s.gap.value
        assertTrue(dockable(Shape(edge + 0.01f, 900f)))
        assertFalse(dockable(Shape(edge - 1f, 900f)))
    }

    @Test fun thePaletteOffersTheChoiceOnlyWhereItChangesSomething() {
        assertEquals(DockSidebarCommand, dockCommand(dockable = true, docked = false))
        assertEquals(UndockSidebarCommand, dockCommand(dockable = true, docked = true))
        assertEquals(null, dockCommand(dockable = false, docked = false))
        assertEquals(null, dockCommand(dockable = false, docked = true))
    }

    @Test fun dockingFromThePaletteLeavesTheSidebarOpen() {
        // Run while the sidebar covers the panes: it is to move beside them, not to close.
        var closes = 0
        val wrapped = closingSidebarAfter(listOf(PaletteCommand(DockSidebarCommand) {})) { closes++ }
        wrapped.single().run()
        assertEquals(0, closes)
    }

    @Test fun everySizeCanDockOnTheUnfoldedScreen() {
        for (size in UiSize.entries) {
            assertTrue("$size", dockable(open, size))
            assertEquals("$size", SidebarPlace.Beside, place(open, docked = true, size))
        }
    }
}
