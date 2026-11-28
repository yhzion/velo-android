package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.TileBrand
import org.junit.Assert.assertEquals
import org.junit.Test

/** The layers behind a split tab's face show its panes' programs, and its label says the make-up (#217). */
class TabTileStackTest {
    private val c = TileBrand.Claude
    private val o = TileBrand.OpenCode
    private val x = TileBrand.Codex
    private val t = TileBrand.Terminal

    /** A column of [n] panes, `b0` on top: the layout order is their numbers'. */
    private fun layout(n: Int): LayoutNode =
        if (n == 1) LayoutNode(block = "b0") else (1 until n).fold(LayoutNode(block = "b0")) { first, i ->
            LayoutNode(dir = "column", first = first, second = LayoutNode(block = "b$i"))
        }

    /** The selected session `s` with one tab `w` whose panes run [programs] in layout order, [focus] focused. */
    private fun state(programs: List<String>, focus: Int = 0): UiState {
        val blocks = programs.indices.map { "b$it" }
        return UiState(
            status = "connected", session = "s", window = "w", focused = blocks[focus],
            sessions = listOf(SessionInfo("s", tabs = listOf(TabInfo("w", panes = programs.size, block = blocks[focus],
                terminalBlocks = blocks, program = programs[focus])))),
            windows = listOf(WindowInfo("w", layout = layout(programs.size))),
            blocks = blocks.zip(programs).associate { (b, p) -> b to BlockInfo(program = p) },
        )
    }

    private fun row(state: UiState): TabRow = sessionGroups(state, true, emptySet(), false, "").single().rows.single()
    private fun tile(state: UiState): TabTileLook = row(state).let { tabTile(it, TabActivity(it.icon, it.indicator, null, it.since)) }
    private fun said(state: UiState): String = row(state).let { tabTileDescription(tabTile(it, TabActivity(it.icon, null, null, null)), it.icon) }

    @Test fun mixedTabsStackTheirPanesPrograms() {
        assertEquals(listOf(c), tile(state(listOf("bash", "claude"))).behind)
        assertEquals(listOf(c, o), tile(state(listOf("bash", "claude", "opencode"))).behind)
        assertEquals(listOf(c, o, x), tile(state(listOf("bash", "claude", "opencode", "codex"))).behind)
        // From five, one layer per kind other than the face's, the most panes first.
        assertEquals(listOf(c, o, x), tile(state(listOf("bash", "opencode", "claude", "codex", "claude"))).behind)
        assertEquals(listOf(c, o, x), tile(state(listOf("bash", "claude", "opencode", "claude", "codex", "claude", "opencode", "claude"))).behind)
        // The face is the focused pane's program; its own pane is not a layer.
        val focusedOnClaude = tile(state(listOf("bash", "claude", "opencode"), focus = 1))
        assertEquals(c, focusedOnClaude.brand)
        assertEquals(listOf(t, o), focusedOnClaude.behind)
    }

    @Test fun aPaneIsNamedByTheAgentTheCoreReportsElseByItsProgram() {
        val s = state(listOf("bash", "2.1.329", "node", "2.1.329"))
        // The core names each pane's program as it names a tab's (`icon`): Claude Code installed natively runs as a
        // version, and Codex from npm as node.
        val named = s.copy(blocks = s.blocks + mapOf("b1" to BlockInfo(icon = "claude", program = "2.1.329"), "b2" to BlockInfo(icon = "codex", program = "node")))
        assertEquals(listOf(c, x, t), tile(named).behind)
        assertEquals("bash, 4 panes: 1 Claude Code, 1 Codex, 1 bash, 1 unknown", said(named))
        // Without the core's `icon`, a version says nothing and node is no brand: the tool look.
        assertEquals(listOf(t, t, t), tile(s).behind)
        assertEquals("bash, 4 panes: 1 node, 1 bash, 2 unknown", said(s))
    }

    @Test fun theStackKeepsTheLayoutOrderWhenFocusMoves() {
        val programs = listOf("claude", "bash", "opencode", "codex")
        // Focus on the last pane: the others still stack in layout order, the first nearest.
        assertEquals(listOf(c, t, o), tile(state(programs, focus = 3)).behind)
        assertEquals(listOf(t, o, x), tile(state(programs, focus = 0)).behind)
        assertEquals(listOf(c, o, x), tile(state(programs, focus = 1)).behind)
    }

    @Test fun aTabOfAnotherSessionDrawsItsUnknownPanesAsTools() {
        // The core follows only the lead pane of a session other than the selected one.
        val other = UiState(status = "connected", session = "s",
            sessions = listOf(SessionInfo("s", tabs = emptyList()), SessionInfo("u", tabs = listOf(TabInfo("w", panes = 3, program = "claude")))))
        val r = sessionGroups(other, true, emptySet(), false, "").flatMap { it.rows }.single()
        val tile = tabTile(r, TabActivity(r.icon, null, null, null))
        assertEquals(listOf(t, t), tile.behind)
        assertEquals("Claude Code, 3 panes", tabTileDescription(tile, r.icon))
    }

    @Test fun theLabelSaysTheMakeUpWhenThePanesRunMoreThanOneProgram() {
        assertEquals("Claude Code, 3 panes", said(state(listOf("claude", "claude", "claude"))))
        assertEquals("bash, 2 panes: 1 Claude Code, 1 bash", said(state(listOf("bash", "claude"))))
        assertEquals("bash, 8 panes: 4 Claude Code, 2 OpenCode, 1 Codex, 1 bash",
            said(state(listOf("bash", "claude", "opencode", "claude", "codex", "claude", "opencode", "claude"))))
        // A tie lists the other panes first, in layout order; a pane whose program is not known is counted last.
        assertEquals("Claude Code, 3 panes: 1 OpenCode, 1 Claude Code, 1 unknown",
            tabTileDescription(TabTileLook(c, 3, null, others = listOf("opencode", null)), "claude"))
        // Only unknown panes besides the face: one program known, no make-up.
        assertEquals("Claude Code, 3 panes", tabTileDescription(TabTileLook(c, 3, null, others = listOf(null)), "claude"))
    }
}
