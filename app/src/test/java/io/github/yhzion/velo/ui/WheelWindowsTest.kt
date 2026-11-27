package io.github.yhzion.velo.ui

import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.SeenReport
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wheel's tiles follow the tab's activity, as the sidebar's do (#62): a hand-built [WheelWindow] cannot show that. */
class WheelWindowsTest {
    // Two `node` tabs: Claude Code runs under node, so only its report says the tab is Claude's.
    private val state = UiState(
        session = "s", window = "w1", focused = "a",
        windows = listOf(WindowInfo("w1", layout = LayoutNode(block = "a")), WindowInfo("w2", layout = LayoutNode(block = "b"))),
        blocks = mapOf("a" to BlockInfo(title = "Thread", program = "node"), "b" to BlockInfo(title = "Build", program = "node")),
    )
    private val reads = ActivityReadState().apply {
        update(PaneAddress("s", "w1", "a"), mapOf("turn" to SeenReport("working", "2026-11-21T01:00:00.123456Z", agent = "claude")))
    }

    @Test fun aReportedAgentOutranksTheProgramAndATabWithNoReportShowsItsProgram() {
        assertEquals(listOf(WheelWindow("w1", "Thread", "claude"), WheelWindow("w2", "Build", "node")),
            wheelWindows(state, reads, connected = true))
    }

    @Test fun offlineEveryTileShowsItsProgram() {
        assertEquals(listOf(WheelWindow("w1", "Thread", "node"), WheelWindow("w2", "Build", "node")),
            wheelWindows(state, reads, connected = false))
    }
}
