package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.TileBrand
import io.github.yhzion.velo.ui.theme.TileDot
import io.github.yhzion.velo.ui.theme.tileLayers
import io.github.yhzion.velo.ui.theme.tilePaneCountShown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tab's tile and the mark at its row's end, from what the tab reports (TabTiles and Sidebar boards). */
class TabTilesTest {
    private fun row(tab: TabInfo): TabRow {
        val state = UiState(status = "connected", sessions = listOf(SessionInfo("s", tabs = listOf(tab))))
        return sessionGroups(state, false, emptySet(), false, "").single().rows.single()
    }

    /** What the row draws for [row] with nothing seen and nothing reported beyond the row itself. */
    private fun drawn(row: TabRow, badge: TabIndicator? = null) = TabActivity(row.icon, row.indicator, badge, row.since)

    @Test fun anAgentOrAProgramGetsItsBrand() {
        for ((tab, brand) in listOf(
            TabInfo("w", program = "claude") to TileBrand.Claude,
            TabInfo("w", icon = "claude-code", program = "2.1.329") to TileBrand.Claude,
            TabInfo("w", program = "/usr/local/bin/codex") to TileBrand.Codex,
            TabInfo("w", program = "gemini") to TileBrand.Gemini,
            // Rex names OpenCode's process opencode.exe, its npm package's file; the native core names the agent.
            TabInfo("w", icon = "opencode", program = "opencode.exe") to TileBrand.OpenCode,
            TabInfo("w", program = "nvim") to TileBrand.Neovim,
            TabInfo("w", program = "docker") to TileBrand.Docker,
            // A login shell is named with a dash before it.
            TabInfo("w", program = "-zsh") to TileBrand.Zsh,
            TabInfo("w", program = "zsh") to TileBrand.Zsh,
            TabInfo("w", program = "htop") to TileBrand.Htop,
            TabInfo("w", program = "omp") to TileBrand.Omp,
            TabInfo("w", program = "pi") to TileBrand.Pi,
            TabInfo("w", program = "node") to TileBrand.Terminal,
            // A version for a program name says nothing about the program.
            TabInfo("w", program = "2.1.329") to TileBrand.Terminal,
            TabInfo("w") to TileBrand.Terminal,
        )) assertEquals(tab.toString(), brand, tabTile(row(tab), drawn(row(tab))).brand)
    }

    @Test fun theAgentAPaneReportsNamesTheTile() {
        val r = row(TabInfo("w", program = "node", activity = "working"))
        // The report's agent (Codex running under node) wins over the program.
        assertEquals(TileBrand.Codex, tabTile(r, TabActivity("codex", TabIndicator.Working, null, null)).brand)
    }

    @Test fun aSplitTabStacksALayerPerPaneAndCountsFromFive() {
        for (panes in listOf(1, 2, 3, 4, 5, 6, 12)) {
            val r = row(TabInfo("w", program = "zsh", panes = panes))
            val tile = tabTile(r, drawn(r))
            assertEquals(panes, tile.panes)
            assertEquals(minOf(panes, 4), tileLayers(tile.panes))
            assertEquals(panes >= 5, tilePaneCountShown(tile.panes))
        }
        // A tab that draws no pane (every pane minimized) still has its one tile.
        val minimized = row(TabInfo("w", program = "zsh", panes = 0, minimized = true))
        assertEquals(1, tabTile(minimized, drawn(minimized)).panes)
    }

    @Test fun anUnseenReportPutsItsDotOnTheTile() {
        val r = row(TabInfo("w", program = "claude"))
        fun dot(badge: TabIndicator?) = tabTile(r, drawn(r, badge)).dot
        assertNull(dot(null))
        assertEquals(TileDot.Unread, dot(TabIndicator.Done))
        assertEquals(TileDot.Failed, dot(TabIndicator.Error))
        for (waiting in listOf(TabIndicator.Waiting, TabIndicator.Permission, TabIndicator.Question, TabIndicator.Auth)) {
            assertEquals(waiting.toString(), TileDot.Waiting, dot(waiting))
        }
    }

    @Test fun theRowEndShowsWorkWaitingOrOffline() {
        assertEquals(RowMark.Working, rowMark(TabIndicator.Working))
        assertEquals(RowMark.Working, rowMark(TabIndicator.CodexActive))
        for (waiting in listOf(TabIndicator.Waiting, TabIndicator.Permission, TabIndicator.Question, TabIndicator.Auth)) {
            assertEquals(waiting.toString(), RowMark.Waiting, rowMark(waiting))
        }
        assertEquals(RowMark.Offline, rowMark(TabIndicator.Offline))
        // A finish and a failure are the tile's dot and the second line's words, not a mark at the end.
        for (none in listOf(null, TabIndicator.Done, TabIndicator.Error, TabIndicator.Idle)) assertNull(none.toString(), rowMark(none))
    }

    @Test fun theDotAndTheRowMarkFollowTheSameReport() {
        // A waiting program unseen: the tile's dot and the row's end both say it waits.
        val r = row(TabInfo("w", program = "claude", activity = "blocked", kind = "permission"))
        val activity = drawn(r, r.indicator)
        assertEquals(TileDot.Waiting, tabTile(r, activity).dot)
        assertEquals(RowMark.Waiting, rowMark(activity.indicator))
        // Seen, the dot goes; the row still says it waits.
        assertNull(tabTile(r, drawn(r)).dot)
        assertEquals(RowMark.Waiting, rowMark(drawn(r).indicator))
    }

    @Test fun aScreenReaderHearsTheProgramAndThePanes() {
        fun said(tab: TabInfo): String = row(tab).let { tabTileDescription(tabTile(it, drawn(it)), it.icon) }
        assertEquals("Claude Code", said(TabInfo("w", program = "claude")))
        assertEquals("Codex, 2 panes", said(TabInfo("w", program = "codex", panes = 2)))
        // A program without a brand keeps its own name, as the mark said it before.
        assertEquals("node, 3 panes", said(TabInfo("w", program = "node", panes = 3)))
        assertEquals("Terminal", said(TabInfo("w")))
        assertFalse(said(TabInfo("w", program = "htop", panes = 6)).isEmpty())
        assertTrue(said(TabInfo("w", program = "htop", panes = 6)).endsWith("6 panes"))
    }

    @Test fun aRowSaysItsTileAndItsNameOnce() {
        val claude = TabTileLook(TileBrand.Claude, 1, null)
        val zsh = TabTileLook(TileBrand.Zsh, 4, null)
        // What the program does now is the second line's words, not the tile's state.
        assertEquals(RowTileSpeech("Claude Code", null, true), tabRowTileSpeech(claude, null, "Refactor the sync queue", null, TabIndicator.Working))
        // A row named after its program: the tile says it, the name is not said again.
        assertEquals(RowTileSpeech("zsh, 4 panes", null, false), tabRowTileSpeech(zsh, null, "zsh", null, null))
        assertEquals(RowTileSpeech("zsh, 4 panes", null, false), tabRowTileSpeech(zsh, null, " ZSH ", null, null))
        assertEquals(RowTileSpeech("zsh", "Unread, Error", false), tabRowTileSpeech(TabTileLook(TileBrand.Zsh, 1, null), null, "zsh", TabIndicator.Error, null))
        // A report not yet seen: "Unread", and its words only when the second line does not say them.
        assertEquals(RowTileSpeech("Claude Code", "Unread", true), tabRowTileSpeech(claude, null, "fix", TabIndicator.Waiting, TabIndicator.Waiting))
        assertEquals(RowTileSpeech("Claude Code", "Unread, Completed", true), tabRowTileSpeech(claude, null, "fix", TabIndicator.Done, null))
        // A program without a brand is named by its hint, and the name the same way.
        assertEquals(RowTileSpeech("node, 2 panes", null, false), tabRowTileSpeech(TabTileLook(TileBrand.Terminal, 2, null), "node", "node", null, null))
        // The hint is trimmed as the name is: the tile says "node", and the name "node" is not said again.
        assertEquals(RowTileSpeech("node", null, false), tabRowTileSpeech(TabTileLook(TileBrand.Terminal, 1, null), " node ", "node", null, null))
        // A tab named by its program's command, which the tile calls by its brand's name: the name is not said again.
        assertEquals(RowTileSpeech("Neovim", null, false), tabRowTileSpeech(TabTileLook(TileBrand.Neovim, 1, null), null, "nvim", null, null))
        assertEquals(RowTileSpeech("Python", null, false), tabRowTileSpeech(TabTileLook(TileBrand.Python, 1, null), null, "python3", null, null))
        assertEquals(RowTileSpeech("Claude Code", null, false), tabRowTileSpeech(claude, null, "claude", null, null))
        // Any other name maps to Terminal, so a Terminal tile's name is said unless it is the program's.
        assertEquals(RowTileSpeech("Terminal", null, true), tabRowTileSpeech(TabTileLook(TileBrand.Terminal, 1, null), null, "notes", null, null))
    }

    @Test fun aWorkingProgramsProgressIsAPieInPlaceOfItsLogo() {
        val working = row(TabInfo("w", program = "claude", activity = "working", progress = 30))
        assertEquals(30, working.progress)
        assertEquals(30, tabTile(working, drawn(working)).progress)
        // No progress said: the logo stays.
        val plain = row(TabInfo("w", program = "claude", activity = "working"))
        assertNull(tabTile(plain, drawn(plain)).progress)
        // A program that waits goes back to its logo and dot even with a progress (report 242, section 6); so does
        // one that finished, failed, or a tab that is offline.
        for (tab in listOf(
            TabInfo("w", program = "claude", activity = "blocked", kind = "question", progress = 50),
            TabInfo("w", program = "claude", activity = "done", progress = 80),
            TabInfo("w", program = "claude", activity = "error", progress = 80),
        )) {
            val r = row(tab)
            assertNull(tab.activity, tabTile(r, drawn(r, r.indicator.takeUnless { it == TabIndicator.Working })).progress)
        }
        assertNull(tabTile(working, TabActivity(working.icon, TabIndicator.Offline, null, null)).progress)
        // Another pane's report not yet seen marks the tile: the dot shows, not the pie.
        val other = tabTile(working, TabActivity(working.icon, TabIndicator.Working, TabIndicator.Done, null))
        assertNull(other.progress)
        assertEquals(TileDot.Unread, other.dot)
        // Another pane waits while the focused one works: the tab shows the wait, so no pie.
        assertNull(tabTile(working, TabActivity(working.icon, TabIndicator.Question, TabIndicator.Question, null)).progress)
    }

    @Test fun aTabOrAPaneMadeFromTheCoresBlocksKeepsItsProgress() {
        val state = UiState(
            status = "connected", session = "s", window = "w", focused = "b1",
            sessions = listOf(SessionInfo("s", tabs = listOf(TabInfo("w", panes = 2, block = "b1", terminalBlocks = listOf("b0", "b1"))))),
            windows = listOf(WindowInfo("w", layout = LayoutNode(dir = "row", first = LayoutNode(block = "b0"), second = LayoutNode(block = "b1")))),
            blocks = mapOf("b0" to BlockInfo(activity = "working", progress = 90), "b1" to BlockInfo(activity = "working", progress = 30)),
        )
        // A tab is its focused pane's; the caption's minimap reads each pane on its own.
        val w = state.windows.single()
        assertEquals(30, w.row(state, "s", shown = true).progress)
        assertEquals(90, w.paneRow(state, "s", "b0").progress)
    }

    @Test fun aPieTakesTheWorkingDotsPlace() {
        val pie = TabTileLook(TileBrand.Claude, 1, null, progress = 30)
        val logo = TabTileLook(TileBrand.Claude, 1, null)
        assertNull(rowMark(TabIndicator.Working, pie))
        assertEquals(RowMark.Working, rowMark(TabIndicator.Working, logo))
        assertEquals(RowMark.Waiting, rowMark(TabIndicator.Question, pie))
        // The percent is no state of the tile, which a screen reader would say at each new percent (#251).
        assertEquals(RowTileSpeech("Claude Code", null, true), tabRowTileSpeech(pie, null, "Index", null, TabIndicator.Working))
    }

    @Test fun aPiesPercentIsSaidWithTheRowsWordsNotDrawn() {
        val pie = TabTileLook(TileBrand.Claude, 1, null, progress = 30)
        val logo = TabTileLook(TileBrand.Claude, 1, null)
        assertEquals("Working · 30% · 4s since report · ~/src", progressSaid(pie, "Working · 4s since report · ~/src", PercentAt.AfterState))
        assertEquals("Working · 30%", progressSaid(pie, "Working", PercentAt.AfterState))
        assertEquals("30% · T250", progressSaid(pie, "T250", PercentAt.First))
        assertEquals("studio · build · 30%", progressSaid(pie, "studio · build", PercentAt.Last))
        assertEquals("30%", progressSaid(pie, null, PercentAt.AfterState))
        // Without a pie the line says what it draws.
        assertNull(progressSaid(logo, "Working · 4s since report", PercentAt.AfterState))
    }
}
