package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether the panes take input, which pane the terminal input sends to, and what the panes say while they take none. */
class PaneInputTest {

    @Test
    fun onlyAConnectedRemoteTakesInput() {
        assertFalse(paneInputPaused("connected"))
        assertTrue(paneInputPaused("connecting"))
        assertTrue(paneInputPaused("reconnecting"))
        assertTrue(paneInputPaused("error"))
        assertTrue(paneInputPaused("idle"))
        // A status this app does not know yet is not taken for a working connection.
        assertTrue(paneInputPaused("handshaking"))
    }

    @Test
    fun theKeysGoToThePaneTheWorkspaceTakesInputFor() {
        assertEquals("b2", inputTarget(panesHidden = false, paused = false, pane = "b2"))
        // No tab, or a tab without a pane: nothing to type into.
        assertNull(inputTarget(panesHidden = false, paused = false, pane = null))
    }

    @Test
    fun theFirstPaneIsTheOneOfTheTabThatIsDrawn() {
        val one = WindowInfo("window:1", layout = LayoutNode(block = "b1"))
        val two = WindowInfo("window:2", layout = LayoutNode(block = "b2"))
        val state = UiState(windows = listOf(one, two))

        assertEquals(two, shownWindow(state.copy(window = "window:2")))
        assertEquals("b2", shownFirstPane(state.copy(window = "window:2")))
        // The state names no tab, or one that is gone: the first tab is drawn, and its first pane takes the keys.
        assertEquals(one, shownWindow(state))
        assertEquals("b1", shownFirstPane(state))
        assertEquals(one, shownWindow(state.copy(window = "window:closed")))
        assertEquals("b1", shownFirstPane(state.copy(window = "window:closed")))
        // A tab whose panes all float is drawn too: its first floating pane is the first pane.
        assertEquals("f1", shownFirstPane(UiState(windows = listOf(WindowInfo("window:4", floating = listOf(FloatingLayer("f1")))))))
        // No tab, or a tab without a pane: nothing to type into.
        assertNull(shownFirstPane(UiState()))
        assertNull(shownFirstPane(UiState(windows = listOf(WindowInfo("window:3")))))
    }

    @Test
    fun noKeyGoesToAPaneTheSidebarCovers() {
        assertNull(inputTarget(panesHidden = true, paused = false, pane = "b2"))
    }

    @Test
    fun noKeyGoesToAPaneWhileItsConnectionIsDown() {
        // A key typed then would be dropped without a word, or reach the shell in part after the reconnect.
        assertNull(inputTarget(panesHidden = false, paused = true, pane = "b2"))
    }

    @Test
    fun onlyShownPanesOfAWorkingConnectionAreATarget() {
        val targets = mutableListOf<List<Boolean>>()
        for (panesHidden in listOf(false, true)) for (paused in listOf(false, true)) {
            if (inputTarget(panesHidden, paused, pane = "b2") != null) targets += listOf(panesHidden, paused)
        }
        assertEquals(listOf(listOf(false, false)), targets)
    }

    @Test
    fun pausedPanesAreOnScreenOnlyWhenTheTabHasAPaneThatIsNotCovered() {
        assertTrue(panesPaused(panesHidden = false, paused = true, firstPane = "b1"))
        // The connection works: nothing is paused.
        assertFalse(panesPaused(panesHidden = false, paused = false, firstPane = "b1"))
        // The sidebar covers the panes: the keys belong to the sidebar.
        assertFalse(panesPaused(panesHidden = true, paused = true, firstPane = "b1"))
        // The empty pane area (no tab, a removed remote): no pane is on screen.
        assertFalse(panesPaused(panesHidden = false, paused = true, firstPane = null))
    }

    @Test
    fun onlyShownPanesOfADownConnectionArePaused() {
        val pausedRows = mutableListOf<List<Boolean>>()
        for (panesHidden in listOf(false, true)) for (paused in listOf(false, true)) for (hasPane in listOf(false, true)) {
            if (panesPaused(panesHidden, paused, if (hasPane) "b1" else null)) pausedRows += listOf(panesHidden, paused, hasPane)
        }
        assertEquals(listOf(listOf(false, true, true)), pausedRows)
    }

    @Test
    fun theCapsuleSaysTheStatusAndThatInputIsPaused() {
        assertEquals("Reconnecting… · Input is paused", inputPausedText("reconnecting"))
        assertEquals("Connecting… · Input is paused", inputPausedText("connecting"))
        assertEquals("Connection failed · Input is paused", inputPausedText("error"))
        // Before the core's first report.
        assertEquals("Connecting… · Input is paused", inputPausedText("idle"))
        assertEquals("Offline · Input is paused", inputPausedText("handshaking"))
    }

    @Test
    fun theCapsuleNamesTheStatusInTheWordsOfTheEmptyPaneArea() {
        for (status in listOf("connecting", "reconnecting", "error", "idle", "handshaking")) {
            val empty = emptyText(UiState(status = status), "mac")
            assertEquals("$empty · Input is paused", inputPausedText(status))
        }
    }
}
