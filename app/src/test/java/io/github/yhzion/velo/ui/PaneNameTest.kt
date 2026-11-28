package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pane is named by one rule in every place that names it alone (#208): its row's name ([paneRow], [paneName]). The
 * issue's panes are a zsh pane and a pane with no program, both labelled by the server from their IDs, "Block 9ac42573",
 * which #198 made the minimap's picker and the palette call zsh and Terminal; the other places said the label.
 */
class PaneNameTest {
    private val auto = BlockInfo(title = "Block 9ac42573", titleIsLabel = true)

    /**
     * The panes of the issue, a pane a user labelled whose program is not reported, a zsh pane a user labelled
     * "server" (#236: the label names it before its program, while the core sends it in the title's place), the same
     * pane once its shell sets a title (the core sends the title, so the label is gone), a zsh pane the server
     * labelled from another ID, a program that reports a title, an agent, an agent a user labelled, and a pane the
     * core knows nothing of.
     */
    private val panes = mapOf(
        "zsh" to auto.copy(program = "/bin/zsh"),
        "bare" to auto,
        "labelled" to BlockInfo(title = "notes", titleIsLabel = true),
        "server" to BlockInfo(title = "server", titleIsLabel = true, program = "/bin/zsh"),
        "serverTitled" to BlockInfo(title = "~/src/velo", program = "/bin/zsh"),
        "otherId" to BlockInfo(title = "Block 0bb89469", titleIsLabel = true, program = "/bin/zsh"),
        "titled" to BlockInfo(title = "vim README.md", program = "vim"),
        "agent" to auto.copy(icon = "claude", program = "/usr/bin/claude"),
        "labelledAgent" to BlockInfo(title = "review", titleIsLabel = true, icon = "claude", program = "/usr/bin/claude"),
        "nothing" to BlockInfo(),
    )
    private val expected = mapOf(
        "zsh" to "zsh", "bare" to "Terminal", "labelled" to "notes", "server" to "server",
        "serverTitled" to "~/src/velo", "otherId" to "zsh", "titled" to "vim README.md",
        "agent" to "Claude Code", "labelledAgent" to "review", "nothing" to "Terminal",
    )

    /** A tab of every pane above but the last, tiled, and the last floating, the shown tab of a connected remote. */
    private val window = WindowInfo("window:w", label = "Window 0b0b0b0b",
        layout = panes.keys.toList().dropLast(1).fold<String, LayoutNode?>(null) { left, b ->
            val leaf = LayoutNode(block = b)
            if (left == null) leaf else LayoutNode(dir = "row", first = left, second = leaf)
        },
        floating = listOf(FloatingLayer(panes.keys.last())))
    private val state = UiState(status = "connected", session = "s", window = window.id, windows = listOf(window), blocks = panes)

    @Test fun theRuleIsThePanesRowName() {
        for ((block, info) in panes) {
            assertEquals(block, expected.getValue(block), paneName(info))
            assertEquals(block, window.paneRow(state, "s", block).name, paneName(info))
            assertEquals(block, paneName(info), zoomPaneName(state, window, block))
        }
        assertEquals("Terminal", paneName(null))
    }

    @Test fun theCloseQuestionNamesThePaneByTheRule() {
        for (block in panes.keys) {
            assertEquals(block, "Close ${expected.getValue(block)}? Its process will end.", closeQuestion(state, CloseTarget.Pane(block)))
        }
        // The question of #236: the zsh pane a user labelled is asked about by its label again, as before #208.
        assertEquals("Close server? Its process will end.", closeQuestion(state, CloseTarget.Pane("server")))
    }

    @Test fun theKeyboardsPanePickerNamesThePaneByTheRule() {
        val tabPanes = tabBlocks(window)
        for ((i, block) in tabPanes.withIndex()) {
            val focused = if (i == 0) " (focused)" else ""
            assertEquals(block, "Pane ${i + 1}$focused · ${expected.getValue(block)}", keyboardPaneLabel(tabPanes, block, tabPanes.first(), panes[block]))
        }
        assertEquals(null, keyboardPaneLabel(tabPanes, "elsewhere", null, auto))
        // The palette's Focus Pane names the same panes the same way.
        for (command in focusPaneCommands(window, state, focused = tabPanes.first())) {
            assertEquals("Focus Pane: " + keyboardPaneLabel(tabPanes, command.block, null, panes[command.block]), command.label)
        }
    }

    @Test fun aMovesGhostNamesThePaneByTheRule() {
        for (block in panes.keys) assertEquals(block, "Pane 2 · ${expected.getValue(block)}", ghostHeader("Pane 2", panes[block]))
    }

    @Test fun aMinimizedPanesCapsuleNamesThePaneByTheRule() {
        val minimized = WindowInfo("window:m", minimizedLayers = panes.keys.map { listOf(it) })
        assertEquals(panes.keys.map(expected::getValue), capsules(minimized, panes).map { it.name })
    }
}
