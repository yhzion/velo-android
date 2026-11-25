package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.ui.theme.VeloSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactLayoutTest {
    /** px at the Fold8's 360 dpi (`wm density`), in dp. */
    private fun dp(px: Int) = px * 160f / 360f

    @Test fun boundaryIsSixHundredDpOnTheShortSide() {
        assertTrue(isCompact(599.9f, 900f))
        assertFalse(isCompact(600f, 900f))
    }

    @Test fun coverScreenIsCompactInBothOrientations() {
        // 1248×1972 px → 554.7×876.4 dp
        assertTrue(isCompact(dp(1248), dp(1972)))
        assertTrue(isCompact(dp(1972), dp(1248)))
    }

    @Test fun pairingStacksTheCameraOnlyOnACompactUprightWindow() {
        // The cover screen upright: the camera goes under the form.
        assertTrue(pairingStacks(dp(1248), dp(1972)))
        // Held sideways it is compact too, but stacking would leave the camera a strip.
        assertFalse(pairingStacks(dp(1972), dp(1248)))
        // The unfolded screen is not compact, either way round.
        assertFalse(pairingStacks(dp(1848), dp(2448)))
        assertFalse(pairingStacks(dp(2448), dp(1848)))
    }

    @Test fun unfoldedScreenIsNotCompact() {
        // 2448×1848 px → 1088×821.3 dp
        assertFalse(isCompact(dp(2448), dp(1848)))
    }

    /** The default pane style: the gap between the sidebar and the panes is also the outer margin. */
    private fun covers(widthDp: Float, heightDp: Float): Boolean {
        val s = VeloSizes()
        return sidebarCoversPanes(widthDp, heightDp, s.sidebar.value, s.rail.value, s.gap.value, s.gap.value)
    }

    @Test fun theSidebarCoversThePanesOnTheCoverScreenInBothOrientations() {
        assertTrue(covers(dp(1248), dp(1972)))
        assertTrue(covers(dp(1972), dp(1248)))
    }

    @Test fun theSidebarCoversThePanesOnTheUnfoldedScreenUprightButNotSideways() {
        // Upright it leaves 347dp (41 columns); sideways 614dp.
        assertTrue(covers(dp(1848), dp(2448)))
        assertFalse(covers(dp(2448), dp(1848)))
    }

    @Test fun theSidebarCoversThePanesWhenLessThanFourHundredEightyDpWouldBeLeft() {
        assertTrue(covers(600f, 900f))
        // The sidebar, the rail and a gap on each side of both, and exactly 480 left: enough.
        val s = VeloSizes()
        val edge = MinPanesBesideSidebarDp + s.sidebar.value + s.rail.value + 4 * s.gap.value
        assertFalse(covers(edge + 0.01f, 700f))
        assertTrue(covers(edge - 1f, 700f))
    }

    @Test fun everyPaletteCommandButTheToggleAndDialogsClosesTheSidebar() {
        val ran = mutableListOf<String>()
        val toggle = PaletteCommand(ToggleSidebarCommand) { ran += "toggle" }
        val add = PaletteCommand(AddRemoteCommand, enabled = false) { ran += "add" }
        val commands = listOf(
            PaletteCommand("Go to Tab: b") { ran += "tab" },
            toggle,
            PaletteCommand("Split Right", enabled = false) { ran += "split" },
            add,
            PaletteCommand("Go to Remote: demo_user") { ran += "host" },
        )
        var closes = 0
        val wrapped = closingSidebarAfter(commands) { closes++; ran += "close" }

        assertEquals(commands.map { it.title }, wrapped.map { it.title })
        assertEquals(commands.map { it.enabled }, wrapped.map { it.enabled })
        wrapped[0].run()
        assertEquals(listOf("tab", "close"), ran)
        wrapped[1].run()
        assertEquals(1, closes)
        wrapped[2].run()
        assertEquals(2, closes)
        wrapped[3].run()
        assertEquals(2, closes)
        wrapped[4].run()
        assertEquals(listOf("tab", "close", "toggle", "split", "close", "add", "host", "close"), ran)
    }

    @Test fun closePaneClosesTheSidebar() {
        var closes = 0
        val wrapped = closingSidebarAfter(listOf(PaletteCommand("Close Pane") {}, PaletteCommand(RemoveRemoteCommand) {})) { closes++ }

        wrapped.single { it.title == "Close Pane" }.run()
        assertEquals(1, closes)
        wrapped.single { it.title == RemoveRemoteCommand }.run()
        assertEquals(1, closes)
    }

    @Test fun aCommandThatOpensADialogAboutASidebarRowLeavesTheSidebarOpen() {
        // The palette's own titles, from the model the palette is built from.
        val units = PaletteUnits(saved = emptyList(), removable = true, connected = true, session = true, tab = true, pane = true,
            paneCount = 2, floating = false, zoomed = false)
        var closes = 0
        val wrapped = closingSidebarAfter(paletteItems(units).map { PaletteCommand(it.label) {} }) { closes++ }
        fun closesAfter(action: PaletteAction): Boolean {
            val before = closes
            wrapped[paletteItems(units).indexOfFirst { it.action == action }].run()
            return closes > before
        }

        // The row the dialog is about stays in sight behind it.
        for (dialog in listOf(PaletteAction.AddRemote, PaletteAction.RemoveRemote, PaletteAction.RenameSession, PaletteAction.RenameTab)) {
            assertFalse(dialog.toString(), closesAfter(dialog))
        }
        // What changes the panes shows its result there.
        for (shown in listOf(PaletteAction.NewSession, PaletteAction.NewTab, PaneSplit.Right, PaletteAction.TextLarger, PaletteAction.CloseTab)) {
            assertTrue(shown.toString(), closesAfter(shown))
        }
    }

    // Closing from the sidebar: the folded sidebar goes when what the panes show changes.
    private val work = SessionInfo("session:work", "work", listOf(TabInfo("window:1", active = false), TabInfo("window:2", active = true)))
    private val keep = SessionInfo("session:keep", "keep", listOf(TabInfo("window:9", active = true)))
    private val shown = UiState(status = "connected", sessions = listOf(work, keep), session = work.id, window = "window:2")

    @Test fun closingTheShownTabOrTheShownSessionChangesThePanes() {
        assertTrue(closeChangesThePanes(shown, active = true, CloseTarget.Tab(work.id, "window:2")))
        assertTrue(closeChangesThePanes(shown, active = true, CloseTarget.Session(work.id)))
        // A pane is always one of the shown tab's.
        assertTrue(closeChangesThePanes(shown, active = true, CloseTarget.Pane("block:1")))
    }

    @Test fun closingATabOrASessionThatIsNotShownOnlyEditsTheList() {
        // Another tab of the shown session, a tab of another session, another session.
        assertFalse(closeChangesThePanes(shown, active = true, CloseTarget.Tab(work.id, "window:1")))
        assertFalse(closeChangesThePanes(shown, active = true, CloseTarget.Tab(keep.id, "window:9")))
        assertFalse(closeChangesThePanes(shown, active = true, CloseTarget.Session(keep.id)))
        // A tab id of the shown tab, in another session.
        assertFalse(closeChangesThePanes(shown, active = true, CloseTarget.Tab(keep.id, "window:2")))
    }

    @Test fun closingOnARemoteThatIsNotDrawnNeverChangesThePanes() {
        // The same ids on another remote's connection: its panes are not on the screen.
        assertFalse(closeChangesThePanes(shown, active = false, CloseTarget.Tab(work.id, "window:2")))
        assertFalse(closeChangesThePanes(shown, active = false, CloseTarget.Session(work.id)))
    }

    @Test fun withNoSessionShownNoCloseChangesThePanes() {
        val parked = shown.copy(session = null, window = null)
        assertFalse(closeChangesThePanes(parked, active = true, CloseTarget.Tab(work.id, "window:2")))
        assertFalse(closeChangesThePanes(parked, active = true, CloseTarget.Session(work.id)))
    }

    @Test fun aStickyKeyIsArmedByATapAndTakenBackByAnother() {
        assertEquals(setOf("Alt"), toggleSticky(emptySet(), "Alt"))
        assertEquals(emptySet<String>(), toggleSticky(setOf("Alt"), "Alt"))
        // Ctrl and Alt arm apart and together: Ctrl+Alt+key is one chord.
        assertEquals(setOf("Ctrl", "Alt"), toggleSticky(setOf("Ctrl"), "Alt"))
        assertEquals(setOf("Alt"), toggleSticky(setOf("Ctrl", "Alt"), "Ctrl"))
    }

    @Test fun theNextKeyCarriesExactlyTheArmedModifiers() {
        val ctrl = stickyMeta(setOf("Ctrl"))
        val alt = stickyMeta(setOf("Alt"))
        // Nothing armed sends a plain key; each modifier has bits of its own.
        assertEquals(0, stickyMeta(emptySet()))
        assertTrue(ctrl != 0 && alt != 0)
        assertEquals(0, ctrl and alt)
        // Both armed: one chord with both, and nothing else.
        assertEquals(ctrl or alt, stickyMeta(setOf("Ctrl", "Alt")))
        // Alt taken back while Ctrl waits: the key is sent with Ctrl only.
        assertEquals(ctrl, stickyMeta(toggleSticky(setOf("Ctrl", "Alt"), "Alt")))
    }

    @Test fun compactBarsScrollOnlyWhenEveryKeyIsAtItsLeastWidth() {
        val least = List(8) { 32 }
        assertEquals(least, keyCapWidths(least, 32, 8 * 32))
        assertNull(keyCapWidths(least, 32, 8 * 32 - 1))
    }
}
