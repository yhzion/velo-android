package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.Feel
import io.github.yhzion.velo.ui.theme.TileBrand
import io.github.yhzion.velo.ui.theme.tileBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mac server's sessions when the owner compared the two sidebars (issue #21): one session
 * with three tabs, one with a single tab. The second tab of the first is a split whose focused
 * pane runs Claude and waits for an answer.
 */
private val savanna = SessionInfo("session:savanna", "bold-savanna", listOf(
    TabInfo("window:a", "Window 0a0a0a0a", block = "block:a", title = "alice@mac:~", program = "zsh", cwd = "~"),
    TabInfo("window:b", "Window 0b0b0b0b", icon = "claude", panes = 2, active = true, block = "block:b2",
        title = "claude", program = "node", cwd = "~/src/app", activity = "blocked"),
    TabInfo("window:c", "notes", block = "block:c", title = "vim notes", program = "vim", cwd = "~/notes", activity = "working"),
))
private val lagoon = SessionInfo("session:lagoon", "drifting-lagoon", listOf(
    TabInfo("window:l", "Window 01010101", active = true, block = "block:l", title = "alice@mac:/etc", program = "bash", cwd = "/etc"),
))
private val both = UiState(status = "connected", sessions = listOf(savanna, lagoon), session = savanna.id, window = "window:b")

private fun groups(
    state: UiState = both, active: Boolean = true, collapsed: Set<String> = emptySet(), activityOnly: Boolean = false, query: String = "",
) = sessionGroups(state, active, collapsed, activityOnly, query)

class SidebarModelTest {

    @Test
    fun sessionTabOrderListsTheServersOrder() {
        val reversed = SessionInfo("session:r", "r", listOf(TabInfo("w2", "two"), TabInfo("w1", "one")))
        val state = both.copy(sessions = both.sessions + reversed + SessionInfo("session:none", "none", null))
        assertEquals(listOf("w2", "w1"), sessionTabOrder(state, "session:r"))
        assertEquals(emptyList<String>(), sessionTabOrder(state, "session:unknown"))
        assertEquals(emptyList<String>(), sessionTabOrder(state, "session:none"))
    }

    @Test
    fun everySessionIsAGroupOfItsTabsInTheServersOrder() {
        val g = groups()

        assertEquals(listOf("bold-savanna", "drifting-lagoon"), g.map { it.label })
        assertEquals(listOf("window:a", "window:b", "window:c"), g[0].rows.map { it.tab })
        assertEquals(listOf("window:l"), g[1].rows.map { it.tab })
        // Every row knows the session it selects, the one that is not shown included.
        assertEquals(setOf(savanna.id), g[0].rows.map { it.session }.toSet())
        assertEquals(lagoon.id, g[1].rows.single().session)
    }

    @Test
    fun theCoresStateTextBecomesTheGroups() {
        // The text the core's own test pins for a session (native/src/rex/test_model.zig), a session
        // that really has no tabs (`[]`) and one whose tabs are not known (`tabs` left out).
        val state = UiState.decode(
            """{"status":"connected","sessions":[{"id":"session:one","label":"one","tabs":[""" +
                """{"id":"window:1","label":"Window 11111111","panes":1,"active":true,"block":"block:1",""" +
                """"title":"alice@mac:~","program":"zsh","cwd":"~","activity":"idle","attention":false},""" +
                """{"id":"window:c","label":"empty","panes":0,"active":false,"title":"","program":"","cwd":"","activity":"idle","attention":false}]},""" +
                """{"id":"session:none","label":"none","tabs":[]},""" +
                """{"id":"session:new","label":"new"}],"session":"session:one","window":"window:1"}""",
        )

        val g = groups(state)

        assertEquals(listOf("one" to 2, "none" to 0, "new" to null), g.map { it.label to it.tabs })
        assertEquals(listOf("alice@mac:~" to "~", "empty" to ""), g[0].rows.map { it.name to it.folder })
        assertEquals(listOf(true, false), g[0].rows.map { it.shown })
    }

    @Test
    fun decodedTabIconsAgreeAcrossSidebarAndWindowsWithoutChangingNames() {
        val state = UiState.decode(
            """{
                "session":"s","window":"w","focused":"b",
                "sessions":[
                    {"id":"s","tabs":[
                        {"id":"w","label":"Remote shell","icon":"SSH","program":"bash"},
                        {"id":"e","label":"Window 22222222","icon":"nvim","program":"nvim"}
                    ]},
                    {"id":"t","tabs":[
                        {"id":"c","label":"Assistant","icon":"claude","program":"node"},
                        {"id":"u","icon":"custom-tool","program":"custom-tool"},
                        {"id":"m","title":"Waiting"}
                    ]}
                ],
                "windows":[
                    {"id":"w","label":"Remote shell","icon":"SSH","layout":{"block":"b"}},
                    {"id":"e","label":"Window 22222222","icon":"nvim","layout":{"block":"editor"}}
                ],
                "blocks":{"b":{"program":"bash"},"editor":{"program":"nvim"}}
            }""",
        )

        val (selected, followed) = groups(state)
        val selectedTiles = selected.rows.map { tileBrand(it.icon) }
        val selectedNames = selected.rows.map { it.name }
        assertEquals(listOf(TileBrand.Terminal, TileBrand.Neovim), selectedTiles)
        assertEquals(selectedTiles, state.windows.map { tileBrand(it.icon) })
        assertEquals(listOf("Remote shell", "nvim"), selectedNames)
        assertEquals(selectedNames, state.windows.map { tabTitle(state, it) })
        assertEquals(listOf(TileBrand.Claude, TileBrand.Terminal, TileBrand.Terminal), followed.rows.map { tileBrand(it.icon) })
        assertEquals(listOf("Assistant", "custom-tool", "Waiting"), followed.rows.map { it.name })
    }

    @Test
    fun theHostIsNamedAfterTheAddressTheCoreConnectedTo() {
        assertEquals("alice@workstation", hostName(UiState(server = "https://workstation.tailnet.example", account = "alice")))
        assertEquals("rex", hostName(UiState(server = "/data/user/0/io.github.yhzion.velo/files/rex.sock")))
        assertEquals("198.51.100.29", hostName(UiState(server = "http://198.51.100.29:7890")))
        assertEquals("Rex", hostName(UiState()))
    }

    @Test
    fun reportAgeUsesThePublishedTimeAndKeepsTheServersTabLabel() {
        val state = UiState.decode(
            """{"server":"studio.example","account":"alice","session":"s","window":"w","sessions":[{"id":"s","tabs":[
                {"id":"w","label":"Production logs","program":"tail","activity":"working","since":1795168800000}
            ]}],"windows":[{"id":"w","label":"Production logs","layout":{"block":"b"}}],
            "blocks":{"b":{"program":"tail","activity":"working","since":1795168800000}}}""",
        )
        val reportMs = 1_795_168_800_000L
        val row = groups(state).single().rows.single()
        assertEquals("alice@studio", hostName(state))
        assertEquals("Production logs", row.name)
        assertEquals("Production logs", row.ownLabel)
        assertEquals("1m 3s since report", row.reportAge(reportMs + 63_000))
        assertEquals("1d 2h since report", row.reportAge(reportMs + 26 * 3_600_000L))
        assertEquals("1m 3s since report", shownTab(state)?.reportAge(reportMs + 63_000))
        assertEquals("1m 3s since report", tabOf(state.copy(sessions = listOf(SessionInfo("s"))), "s", "w")
            ?.reportAge(reportMs + 63_000))
        assertNull(row.reportAge(reportMs - 1))
        assertEquals("0s since report", row.reportAge(reportMs))
        assertNull(row.copy(activity = "idle").reportAge(reportMs))
        assertNull(row.copy(since = null).reportAge(reportMs))
    }

    /** The second line of [row] as the sidebar draws it on a remote that is [connected] or not: the words of the mark beside it. */
    private fun secondLine(row: TabRow, connected: Boolean, age: String? = null) =
        tabRowSubtitle(row.indicatorWhile(connected), age, row.folder)

    @Test
    fun aTabsSecondLineStartsWithWhatItsMarkSays() {
        val reportMs = 1_795_168_800_000L
        val rows = groups()[0].rows
        val working = rows[2]
        val waiting = rows[1].copy(since = reportMs)
        val age = waiting.reportAge(reportMs + 192_000)

        assertEquals("Working · ~/notes", secondLine(working, connected = true))
        assertEquals("Waiting for input · 3m 12s since report · ~/src/app", secondLine(waiting, connected = true, age))
        // The line keeps its shape whatever else is there.
        assertEquals("Error · 5s since report", tabRowSubtitle(TabIndicator.Error, "5s since report", ""))
        assertEquals("Working", tabRowSubtitle(TabIndicator.Working, null, ""))
    }

    @Test
    fun aTabsSecondLineAndItsMarkSayTheSameOnARemoteThatIsDown() {
        // The row still holds its last report, and the mark of a remote that is down says Disconnected: so does the line.
        val working = groups()[0].rows[2]

        assertEquals("working", working.activity)
        assertEquals(TabIndicator.Offline, working.indicatorWhile(false))
        assertEquals("Disconnected · ~/notes", secondLine(working, connected = false))
    }

    @Test
    fun aTabsSecondLineNamesWhatItWaitsForAndThatItIsCompleted() {
        fun row(tab: TabInfo) = groups(both.copy(sessions = listOf(SessionInfo(savanna.id, "bold-savanna", listOf(tab)))))[0].rows.single()
        val approval = row(TabInfo("window:p", icon = "claude", cwd = "~/src/app", activity = "blocked", kind = "permission"))
        val completed = row(TabInfo("window:d", icon = "claude", cwd = "~/src/app", activity = "done"))
        // A Codex spinner in the title is a mark without a reported activity: the line says it too.
        val spinner = row(TabInfo("window:x", icon = "codex", title = "⠋ Fix login", cwd = "~"))

        assertEquals("Waiting for approval · ~/src/app", secondLine(approval, connected = true))
        assertEquals("Completed · ~/src/app", secondLine(completed, connected = true))
        assertEquals(TabIndicator.CodexActive, spinner.indicator)
        assertEquals("Codex is active · ~", secondLine(spinner, connected = true))
        for (r in listOf(approval, completed, spinner)) {
            assertEquals(r.indicatorWhile(true)?.description, secondLine(r, connected = true)?.substringBefore(" · "))
        }
    }

    @Test
    fun aTabWithoutAMarkKeepsItsLineAsItWas() {
        val quiet = groups()[0].rows[0]

        // No activity: no mark, and no word, on a connected remote and on one that is down.
        assertNull(quiet.indicatorWhile(true))
        assertEquals("~", secondLine(quiet, connected = true))
        assertEquals("~", secondLine(quiet, connected = false))
        assertEquals("1m 3s since report · ~", tabRowSubtitle(null, "1m 3s since report", "~"))
        assertEquals("1m 3s since report", tabRowSubtitle(null, "1m 3s since report", ""))
        assertNull(tabRowSubtitle(null, null, ""))
    }

    @Test
    fun aTabWithSeveralPanesIsOneRowNamedAfterItsLeadPane() {
        val split = groups()[0].rows[1]

        // The focused pane's program and folder; the other pane has no row.
        assertEquals("claude", split.name)
        assertEquals("~/src/app", split.folder)
        assertEquals("claude", split.icon)
        assertEquals("blocked", split.activity)
    }

    @Test
    fun aTabsOwnLabelNamesItAndTheServersAutomaticLabelDoesNot() {
        val rows = groups()[0].rows

        assertEquals(listOf("alice@mac:~", "claude", "notes"), rows.map { it.name })
        // The folder still shows beside a label the user gave.
        assertEquals("~/notes", rows[2].folder)
        // Rename… starts from the user's label, and from nothing for an automatic one.
        assertEquals(listOf("", "", "notes"), rows.map { it.ownLabel })
    }

    @Test
    fun aTabWhoseProgramIsUnknownShowsItsTitleAndOneWithNothingIsATab() {
        val s = SessionInfo("session:s", "s", listOf(
            TabInfo("window:1", block = "block:1", title = "alice@mac:~"),
            TabInfo("window:2", "Window 22222222", panes = 0),
        ))
        val rows = groups(UiState(status = "connected", sessions = listOf(s), session = s.id))[0].rows

        assertEquals(listOf("alice@mac:~", "Tab"), rows.map { it.name })
        assertEquals(listOf("", ""), rows.map { it.folder })
    }

    @Test
    fun onlyTheTabThePanesShowIsTheShownRowAndCarriesTheX() {
        val rows = groups().flatMap { it.rows }

        assertEquals(listOf("window:b"), rows.filter { it.shown }.map { it.tab })
        // The X sits on that row; every other row closes by a swipe.
        assertEquals(listOf(CloseControl.Swipe, CloseControl.X, CloseControl.Swipe, CloseControl.Swipe), rows.map { it.closeControl })
        // The other session's active tab is not what the panes show.
        assertFalse(groups()[1].rows.single().shown)
    }

    @Test
    fun anotherRemotesRowsAreNeverTheShownTab() {
        // Its panes are not on the screen, whatever its own session and tab are.
        assertTrue(groups(active = false).flatMap { it.rows }.none { it.shown })
        assertTrue(groups(active = false).flatMap { it.rows }.all { it.closeControl == CloseControl.Swipe })
    }

    @Test
    fun theShownRowFollowsTheStatesTabNotTheSessionsOwnMark() {
        // The top level is ahead of `tabs`: the user went to the third tab, the list still marks the second.
        val moved = both.copy(window = "window:c")
        assertEquals(listOf("window:c"), groups(moved).flatMap { it.rows }.filter { it.shown }.map { it.tab })
        assertEquals("window:c", shownTabId(moved))
        // Before the top level names a tab, the session's own mark decides.
        val early = both.copy(window = null)
        assertEquals(listOf("window:b"), groups(early).flatMap { it.rows }.filter { it.shown }.map { it.tab })
        // No session is shown: no row is the shown tab.
        assertTrue(groups(both.copy(session = null, window = null)).flatMap { it.rows }.none { it.shown })
        assertNull(shownTabId(both.copy(session = null, window = null)))
    }

    @Test
    fun theRowAndThePaletteAgreeOnTheShownTab() {
        val w = WindowInfo("window:b", "Window 0b0b0b0b", layout = LayoutNode(block = "block:b2"))
        val state = both.copy(windows = listOf(WindowInfo("window:a"), w), focused = "block:b2")

        assertEquals(groups(state).flatMap { it.rows }.single { it.shown }.tab, shownTab(state)!!.tab)
        assertTrue(shownTab(state)!!.shown)
    }

    @Test
    fun aCollapsedGroupKeepsItsHeaderAndHidesItsRows() {
        val g = groups(collapsed = setOf(savanna.id))

        assertEquals(listOf("bold-savanna", "drifting-lagoon"), g.map { it.label })
        assertTrue(g[0].collapsed)
        assertTrue(g[0].rows.isEmpty())
        // Close Session still names every tab of it.
        assertEquals(3, g[0].tabs)
        assertEquals(1, g[1].rows.size)
    }

    @Test
    fun theActivityFilterKeepsTheRowsThatWorkOrWaitAndHidesASessionWithoutOne() {
        val g = groups(activityOnly = true)

        assertEquals(listOf("bold-savanna"), g.map { it.label })
        assertEquals(listOf("window:b", "window:c"), g[0].rows.map { it.tab })
    }

    @Test
    fun theTextFilterMatchesNameOrFolderAndHidesASessionWithoutAMatch() {
        assertEquals(listOf("window:l"), groups(query = "ETC").flatMap { it.rows }.map { it.tab })
        assertEquals(listOf("drifting-lagoon"), groups(query = "etc").map { it.label })
        // A name: the program of one tab, the label of another.
        assertEquals(listOf("window:b"), groups(query = "node").flatMap { it.rows }.map { it.tab })
        assertEquals(listOf("window:c"), groups(query = " notes ").flatMap { it.rows }.map { it.tab })
        assertTrue(groups(query = "no such thing").isEmpty())
        // Both filters at once: a row must pass both.
        assertEquals(listOf("window:c"), groups(activityOnly = true, query = "~/n").flatMap { it.rows }.map { it.tab })
        // Searching by an agent's label finds a tab whose icon names that agent even under node.
        val gemini = SessionInfo("session:g", "g", listOf(
            TabInfo("window:g", block = "block:g", icon = "gemini-cli", title = "Draft plan", program = "node"),
        ))
        assertEquals(listOf("window:g"), groups(both.copy(sessions = listOf(gemini)), query = "Gemini").flatMap { it.rows }.map { it.tab })
        // OpenCode runs as its own executable, so its process name and its label both find it.
        val openCode = SessionInfo("session:o", "o", listOf(
            TabInfo("window:o", block = "block:o", icon = "opencode", title = "Draft plan", program = "opencode.exe"),
        ))
        assertEquals(listOf("window:o"), groups(both.copy(sessions = listOf(openCode)), query = "OpenCode").flatMap { it.rows }.map { it.tab })
    }

    @Test
    fun aCollapsedGroupWithAMatchStillShowsItsHeaderWhileFiltering() {
        val g = groups(collapsed = setOf(savanna.id), query = "node")

        assertEquals(listOf("bold-savanna"), g.map { it.label })
        assertTrue(g.single().rows.isEmpty())
    }

    @Test
    fun aSessionWhoseTabsAreNotKnownIsAHeaderAloneAndNothingCountsItsTabs() {
        // Its view has not arrived, the request for it failed, or the connection is parked.
        val fresh = SessionInfo("session:new", "quiet-meadow")
        val state = both.copy(sessions = both.sessions + fresh)

        assertEquals(listOf("bold-savanna", "drifting-lagoon", "quiet-meadow"), groups(state).map { it.label })
        assertTrue(groups(state).last().rows.isEmpty())
        assertNull(groups(state).last().tabs)
        // No row of it can match: it hides while a filter is on.
        assertEquals(listOf("bold-savanna"), groups(state, activityOnly = true).map { it.label })
        assertEquals(listOf("drifting-lagoon"), groups(state, query = "etc").map { it.label })
    }

    @Test
    fun aSessionThatReallyHasNoTabsIsAHeaderAloneWithZeroTabs() {
        val bare = SessionInfo("session:bare", "bare-ridge", tabs = emptyList())
        val state = both.copy(sessions = both.sessions + bare)

        val group = groups(state).last()

        assertEquals("bare-ridge", group.label)
        assertTrue(group.rows.isEmpty())
        assertEquals(0, group.tabs)
        assertEquals(listOf("bold-savanna"), groups(state, activityOnly = true).map { it.label })
    }

    @Test
    fun aParkedRemoteListsItsSessionsAsHeadersWithoutCounts() {
        // Every session of a parked connection: no session is shown and no tabs are known.
        val state = UiState(status = "connected", sessions = listOf(SessionInfo(savanna.id, "bold-savanna"), SessionInfo(lagoon.id, "drifting-lagoon")))

        assertEquals(listOf("bold-savanna" to null, "drifting-lagoon" to null), groups(state, active = false).map { it.label to it.tabs })
    }

    @Test
    fun aHeaderWithTabsUnderItCollapsesAndOneWithoutSelectsItsSession() {
        val state = both.copy(sessions = both.sessions + SessionInfo("session:new", "quiet-meadow") + SessionInfo("session:bare", "bare-ridge", emptyList()))
        val g = groups(state)

        // Tabs to fold away: the tap folds them, and unfolds them again.
        assertEquals(HeaderTap.Collapse, headerTap(g[0]))
        assertEquals(HeaderTap.Collapse, headerTap(groups(state, collapsed = setOf(savanna.id))[0]))
        // No row to tap: the header is the only way to this session.
        assertEquals(HeaderTap.Select, headerTap(g[2]))
        assertEquals(HeaderTap.Select, headerTap(g[3]))
    }

    @Test
    fun sessionsWithoutALabelStayApartByTheirIds() {
        val state = UiState(status = "connected", sessions = listOf(SessionInfo("session:a"), SessionInfo("session:b")))

        assertEquals(listOf("session:a", "session:b"), groups(state).map { it.id })
        assertEquals(listOf("Session", "Session"), groups(state).map { it.label })
    }

    @Test
    fun aTabKnownToHoldOnlyOneShellClosesAtOnce() {
        for (shell in listOf("zsh", "bash", "sh", "fish", "-zsh", "/bin/bash", "ZSH")) {
            assertTrue(shell, isShell(shell))
            assertNull(shell, closeTabQuestion("work", shell, panes = 1, minimized = false))
        }
    }

    @Test
    fun aTabThatRunsAProgramAsksAndNamesIt() {
        for (program in listOf("sleep", "vim", "node", "ssh", "zshx", "bash.py")) {
            assertFalse(program, isShell(program))
            val question = closeTabQuestion("work", program, panes = 1, minimized = false)
            assertNotNull(program, question)
            assertTrue(question, question!!.contains("work") && question.contains(program))
        }
        assertEquals("Close sleep? It is still running.", closeTabQuestion("sleep", "sleep", panes = 1, minimized = false))
    }

    @Test
    fun aTabWhoseProgramIsNotKnownAsks() {
        // The core publishes "" while the request for a followed session's process is in flight or failed.
        assertFalse(isShell(""))
        assertEquals("Close work? It may be running a program.", closeTabQuestion("work", "", panes = 1, minimized = false))
    }

    @Test
    fun aTabWithSeveralPanesAsksHoweverItsLeadPaneLooks() {
        // Only the lead pane's program is known: the others may run anything.
        assertEquals("Close work? Its 2 panes will end.", closeTabQuestion("work", "zsh", panes = 2, minimized = false))
        assertEquals("Close work? Its 3 panes will end.", closeTabQuestion("work", "", panes = 3, minimized = false))
        // A known program is still named.
        val running = closeTabQuestion("work", "vim", panes = 2, minimized = false)!!
        assertTrue(running, running.contains("vim") && running.contains("2 panes"))
    }

    @Test
    fun aTabWithNoVisiblePaneCanStillHaveARunningProgram() {
        assertEquals("Close work? It may be running a program.", closeTabQuestion("work", "", panes = 0, minimized = false))
    }

    // A minimized pane is not drawn and not counted in `panes`; its program keeps running.

    @Test
    fun aShellBesideAMinimizedPaneAsksAndClaimsNoPaneCount() {
        val question = closeTabQuestion("work", "zsh", panes = 1, minimized = true)

        assertEquals("Close work? Its minimized panes are still running.", question)
        // How many are minimized is not known: no number of panes is claimed, with one visible pane or several.
        for (panes in listOf(1, 2, 3)) {
            val q = closeTabQuestion("work", "zsh", panes, minimized = true)!!
            assertFalse(q, q.any { it.isDigit() })
        }
        // The same tab without the minimized pane is the plain shell that closes at once.
        assertNull(closeTabQuestion("work", "zsh", panes = 1, minimized = false))
    }

    @Test
    fun aTabWhosePanesAreAllMinimizedAsks() {
        // Nothing of it is drawn (`panes` 0, no lead pane), and all of it runs.
        assertEquals("Close work? Its minimized panes are still running.", closeTabQuestion("work", "", panes = 0, minimized = true))
    }

    @Test
    fun aProgramBesideAMinimizedPaneIsNamedAndNoCountIsClaimed() {
        assertEquals("Close work? vim is still running, and so are its minimized panes.", closeTabQuestion("work", "vim", panes = 1, minimized = true))
        assertEquals("Close vim? It is still running, and so are its minimized panes.", closeTabQuestion("vim", "vim", panes = 2, minimized = true))
        // Unknown lead program: the minimized panes are what is known to run.
        assertEquals("Close work? Its minimized panes are still running.", closeTabQuestion("work", "", panes = 1, minimized = true))
    }

    private val mixedTab = TabInfo("window:m", "Window 0d0d0d0d", panes = 1, active = true, minimized = true, block = "block:m", title = "alice@mac:~", program = "zsh", cwd = "~")
    private val plainTab = TabInfo("window:p", "Window 0e0e0e0e", panes = 1, block = "block:p", title = "alice@mac:~", program = "zsh", cwd = "~")

    @Test
    fun theSidebarsRowOfAMixedTabAsksAndThePlainShellBesideItDoesNot() {
        // The session's own list: the path of every row, also of sessions that are not shown.
        val followed = SessionInfo("session:f", "followed", listOf(mixedTab, plainTab))
        val rows = groups(UiState(status = "connected", sessions = listOf(followed)))[0].rows
        fun ask(row: TabRow) = closeTabQuestion(row.name, row.program, row.panes, row.minimized)

        assertEquals(listOf(true, false), rows.map { it.minimized })
        assertEquals("Close alice@mac:~? Its minimized panes are still running.", ask(rows[0]))
        assertNull(ask(rows[1]))
        // The dialog's live question reads the same row.
        val state = UiState(status = "connected", sessions = listOf(followed))
        assertEquals("Close alice@mac:~? Its minimized panes are still running.", closeQuestion(state, CloseTarget.Tab("session:f", "window:m")))
    }

    @Test
    fun thePalettesShownTabOfAMixedTabAsksAndThePlainShellDoesNot() {
        // The state's top level: the path of the palette's Close Tab, and of the shown session before its list arrives.
        fun state(minimized: Boolean) = UiState(
            status = "connected", sessions = listOf(SessionInfo("session:s", "shown")), session = "session:s",
            windows = listOf(WindowInfo("window:m", "Window 0d0d0d0d", layout = LayoutNode(block = "block:m"), minimized = minimized)),
            window = "window:m", focused = "block:m", blocks = mapOf("block:m" to BlockInfo(title = "alice@mac:~", program = "zsh", cwd = "~")),
        )
        fun ask(row: TabRow) = closeTabQuestion(row.name, row.program, row.panes, row.minimized)

        val mixed = shownTab(state(minimized = true))!!
        assertTrue(mixed.minimized)
        assertEquals(1, mixed.panes)
        assertEquals("Close alice@mac:~? Its minimized panes are still running.", ask(mixed))
        assertEquals("Close alice@mac:~? Its minimized panes are still running.", closeQuestion(state(minimized = true), CloseTarget.Tab("session:s", "window:m")))

        val plain = shownTab(state(minimized = false))!!
        assertFalse(plain.minimized)
        assertNull(ask(plain))
    }

    @Test
    fun bothPathsAgreeOnTheSameTab() {
        // Once the shown session's list has arrived, its row and the top level describe one tab.
        val state = UiState(
            status = "connected", sessions = listOf(SessionInfo("session:s", "shown", listOf(mixedTab, plainTab))), session = "session:s",
            windows = listOf(
                WindowInfo("window:m", "Window 0d0d0d0d", layout = LayoutNode(block = "block:m"), minimized = true),
                WindowInfo("window:p", "Window 0e0e0e0e", layout = LayoutNode(block = "block:p")),
            ),
            window = "window:m", focused = "block:m",
            blocks = mapOf("block:m" to BlockInfo(title = mixedTab.title, program = "zsh"), "block:p" to BlockInfo(title = plainTab.title, program = "zsh")),
        )
        val row = groups(state)[0].rows[0]
        val top = shownTab(state)!!

        assertEquals(listOf(row.tab, row.panes, row.minimized, row.program), listOf(top.tab, top.panes, top.minimized, top.program))
        assertEquals(
            closeTabQuestion(row.name, row.program, row.panes, row.minimized),
            closeTabQuestion(top.name, top.program, top.panes, top.minimized),
        )
    }

    @Test
    fun theCoresTextCarriesMinimizedOnBothPathsAndItsAbsenceMeansNone() {
        val state = UiState.decode(
            """{"status":"connected","sessions":[{"id":"session:s","label":"s","tabs":[""" +
                """{"id":"window:m","label":"Window 0d0d0d0d","panes":1,"active":true,"minimized":true,"block":"block:m","title":"t","program":"zsh","cwd":"~","activity":"idle","attention":false},""" +
                """{"id":"window:all","label":"hidden","panes":0,"active":false,"minimized":true,"title":"","program":"","cwd":"","activity":"idle","attention":false},""" +
                """{"id":"window:old","label":"Window 0f0f0f0f","panes":1,"active":false,"block":"block:o","title":"t","program":"zsh","cwd":"~","activity":"idle","attention":false}]}],""" +
                """"session":"session:s","window":"window:m","focused":"block:m",""" +
                """"windows":[{"id":"window:m","label":"Window 0d0d0d0d","layout":{"block":"block:m"},"minimized":true,"floating":[]},""" +
                """{"id":"window:old","label":"Window 0f0f0f0f","layout":{"block":"block:o"},"floating":[]}],""" +
                """"blocks":{"block:m":{"title":"t","program":"zsh","cwd":"~","activity":"idle","attention":false}}}""",
        )

        assertEquals(listOf(true, true, false), groups(state)[0].rows.map { it.minimized })
        assertEquals(listOf(1, 0, 1), groups(state)[0].rows.map { it.panes })
        assertTrue(shownTab(state)!!.minimized)
        // A core that does not write the field: nothing is minimized.
        assertFalse(shownTab(state.copy(window = "window:old", focused = "block:o"))!!.minimized)
    }

    @Test
    fun theRowsOfTheModelDecideTheirOwnConfirmation() {
        val rows = groups()[0].rows
        fun ask(row: TabRow) = closeTabQuestion(row.name, row.program, row.panes, row.minimized)

        // One pane, zsh.
        assertNull(ask(rows[0]))
        // Two panes, node.
        assertNotNull(ask(rows[1]))
        assertEquals(2, rows[1].panes)
        // A tab the user named still closes at once while only its shell runs.
        val named = SessionInfo("session:n", "n", listOf(TabInfo("window:n", "deploy", program = "zsh")))
        assertNull(ask(groups(UiState(sessions = listOf(named)))[0].rows.single()))
        // A followed session's tab before its process is known: one pane, no program.
        val pending = SessionInfo("session:p", "p", listOf(TabInfo("window:p", "Window 0f0f0f0f", block = "block:p", title = "shell")))
        assertEquals("Close shell? It may be running a program.", ask(groups(UiState(sessions = listOf(pending)))[0].rows.single()))
    }

    @Test
    fun closingASessionAlwaysAsksAndNamesHowManyTabsEnd() {
        assertEquals("Close bold-savanna? 3 tabs will end.", closeSessionQuestion("bold-savanna", 3))
        assertEquals("Close drifting-lagoon? 1 tab will end.", closeSessionQuestion("drifting-lagoon", 1))
        // The number is every tab of the session, whatever the filter shows.
        assertEquals(3, groups(query = "node").single().tabs)
    }

    @Test
    fun closingASessionWhoseTabsAreNotKnownClaimsNoNumber() {
        // It may hold any number of tabs: the question says so instead of a count.
        assertEquals("Close quiet-meadow? Every tab in it will end.", closeSessionQuestion("quiet-meadow", null))
    }

    @Test
    fun closingASessionWithoutTabsSaysItHasNone() {
        assertEquals("Close bare-ridge? It has no tabs.", closeSessionQuestion("bare-ridge", 0))
    }

    @Test
    fun theQuestionForEachGroupComesFromWhatIsKnownOfIt() {
        val state = both.copy(sessions = both.sessions + SessionInfo("session:new", "quiet-meadow") + SessionInfo("session:bare", "bare-ridge", emptyList()))

        assertEquals(
            listOf(
                "Close bold-savanna? 3 tabs will end.", "Close drifting-lagoon? 1 tab will end.",
                "Close quiet-meadow? Every tab in it will end.", "Close bare-ridge? It has no tabs.",
            ),
            groups(state).map { closeSessionQuestion(it.label, it.tabs) },
        )
    }

    @Test
    fun theShownSessionCountsItsTabsFromThePanesUntilItsOwnListArrives() {
        // The palette's Close Session acts on the shown session, whose top level arrives first.
        val w = listOf(WindowInfo("window:a"), WindowInfo("window:b"))
        val unknown = UiState(sessions = listOf(SessionInfo(savanna.id, "bold-savanna"), SessionInfo(lagoon.id)), session = savanna.id)

        assertEquals(3, sessionTabs(both, savanna.id))
        assertEquals(2, sessionTabs(unknown.copy(windows = w), savanna.id))
        // The top level says nothing about a session that is not the shown one.
        assertNull(sessionTabs(unknown.copy(windows = w), lagoon.id))
        // Neither is known: no number is claimed.
        assertNull(sessionTabs(unknown, savanna.id))
        // Known to be empty, and nothing is shown.
        assertEquals(0, sessionTabs(unknown.copy(sessions = listOf(SessionInfo(savanna.id, "bold-savanna", emptyList()))), savanna.id))
        assertNull(sessionTabs(both, "session:gone"))
    }

    @Test
    fun aLeftSwipePastTheThresholdShowsCloseAndARightSwipeHidesIt() {
        assertTrue(closeRevealed(shown = false, dragPx = -120f, thresholdPx = 80f))
        // Too short to be meant, either way.
        assertFalse(closeRevealed(shown = false, dragPx = -40f, thresholdPx = 80f))
        assertTrue(closeRevealed(shown = true, dragPx = 40f, thresholdPx = 80f))
        assertFalse(closeRevealed(shown = true, dragPx = 120f, thresholdPx = 80f))
        assertFalse(closeRevealed(shown = false, dragPx = 120f, thresholdPx = 80f))
    }

    @Test
    fun aSwipeIsFeltWhereLettingGoWouldShowOrHideClose() {
        assertEquals(Feel.ThresholdOn, revealFeel(shown = false, beforePx = -70f, afterPx = -90f, thresholdPx = 80f))
        // Back short of the threshold before lifting: Close would not show, and that is felt too.
        assertEquals(Feel.ThresholdOff, revealFeel(shown = false, beforePx = -90f, afterPx = -70f, thresholdPx = 80f))
        assertNull(revealFeel(shown = false, beforePx = -10f, afterPx = -50f, thresholdPx = 80f))
        assertNull(revealFeel(shown = false, beforePx = -90f, afterPx = -150f, thresholdPx = 80f))
        // Shown: a right swipe past the threshold would hide it.
        assertEquals(Feel.ThresholdOff, revealFeel(shown = true, beforePx = 70f, afterPx = 90f, thresholdPx = 80f))
    }

    @Test
    fun theShownTabIsNamedAndConfirmedByItsFocusedPane() {
        val w = WindowInfo("window:b", "Window 0b0b0b0b", layout = LayoutNode(dir = "row", first = LayoutNode(block = "block:b1"), second = LayoutNode(block = "block:b2")))
        val state = both.copy(windows = listOf(WindowInfo("window:a", "notes", layout = LayoutNode(block = "block:a")), w), focused = "block:b2",
            blocks = mapOf(
                "block:a" to BlockInfo(program = "vim"),
                "block:b1" to BlockInfo(title = "alice@mac:~", program = "zsh", cwd = "~"),
                "block:b2" to BlockInfo(title = "sleep 600", program = "sleep", cwd = "~/src"),
            ))

        val tab = shownTab(state)!!

        assertEquals(listOf(savanna.id, "window:b", "sleep 600", "~/src", ""), listOf(tab.session, tab.tab, tab.name, tab.folder, tab.ownLabel))
        // The palette's Close Tab asks: the focused pane runs a program, and the tab has two panes.
        assertEquals(2, tab.panes)
        assertNotNull(closeTabQuestion(tab.name, tab.program, tab.panes, tab.minimized))
        // Its focused pane is a shell, but the other pane is not known to be: it still asks.
        val onShell = shownTab(state.copy(focused = "block:b1"))!!
        assertEquals("Close alice@mac:~? Its 2 panes will end.", closeTabQuestion(onShell.name, onShell.program, onShell.panes, onShell.minimized))
        // A floating pane counts as a pane.
        val floating = w.copy(layout = LayoutNode(block = "block:b1"), floating = listOf(FloatingLayer("block:b2")))
        assertEquals(2, shownTab(state.copy(windows = listOf(floating)))!!.panes)
        // No tab on the screen: nothing for a tab command to act on.
        assertNull(shownTab(state.copy(windows = emptyList(), window = null)))
        assertNull(shownTab(state.copy(session = null)))
    }

    @Test
    fun theOverviewsTabTitleAgreesWithTheRowsName() {
        // `tabTitle` names a tab for the caption and the overview from the top-level state.
        val w = WindowInfo("window:b", "Window 0b0b0b0b", layout = LayoutNode(block = "block:b2"))
        val state = both.copy(windows = listOf(w), focused = "block:b2",
            blocks = mapOf("block:b2" to BlockInfo(title = "claude", program = "node", cwd = "~/src/app")))

        assertEquals(groups(state)[0].rows[1].name, tabTitle(state, w))
        assertEquals("notes", tabTitle(state, w.copy(label = "notes")))
        assertEquals("Tab", tabTitle(state.copy(blocks = emptyMap()), w))
    }

    // What a close dialog asks is read from the state as it is now.

    @Test
    fun theSessionQuestionFollowsTheCountWhileTheDialogIsOpen() {
        val target = CloseTarget.Session(savanna.id)
        assertEquals("Close bold-savanna? 3 tabs will end.", closeQuestion(both, target))

        // Another client closed two of its tabs.
        val fewer = both.copy(sessions = listOf(savanna.copy(tabs = savanna.tabs!!.take(1)), lagoon))
        assertEquals("Close bold-savanna? 1 tab will end.", closeQuestion(fewer, target))
        // Its tabs are not known any more (the connection was parked).
        val unknown = both.copy(sessions = listOf(savanna.copy(tabs = null), lagoon), session = null, window = null)
        assertEquals("Close bold-savanna? Every tab in it will end.", closeQuestion(unknown, target))
        // It was renamed meanwhile: the question names what would be closed now.
        assertEquals("Close prod? 3 tabs will end.", closeQuestion(both.copy(sessions = listOf(savanna.copy(label = "prod"), lagoon)), target))
    }

    @Test
    fun aSessionThatIsGoneHasNoQuestion() {
        assertNull(closeQuestion(both.copy(sessions = listOf(lagoon), session = lagoon.id, window = "window:l"), CloseTarget.Session(savanna.id)))
        // The connection itself went away: nothing is listed.
        assertNull(closeQuestion(UiState(), CloseTarget.Session(savanna.id)))
    }

    @Test
    fun theTabQuestionFollowsWhatTheTabRunsWhileTheDialogIsOpen() {
        val target = CloseTarget.Tab(savanna.id, "window:c")
        assertEquals("Close notes? vim is still running.", closeQuestion(both, target))

        fun with(tab: TabInfo) = both.copy(sessions = listOf(savanna.copy(tabs = savanna.tabs!!.map { if (it.id == tab.id) tab else it }), lagoon))
        val notes = savanna.tabs!![2]
        // It was split meanwhile.
        assertEquals("Close notes? vim is still running, and its 2 panes will end.", closeQuestion(with(notes.copy(panes = 2)), target))
        // vim was quit: the dialog was opened to ask, so it still asks, and claims nothing that is no longer true.
        assertEquals("Close notes?", closeQuestion(with(notes.copy(program = "zsh")), target))
    }

    @Test
    fun aTabThatIsGoneHasNoQuestion() {
        val target = CloseTarget.Tab(savanna.id, "window:c")

        // Closed by another client, or with its whole session.
        assertNull(closeQuestion(both.copy(sessions = listOf(savanna.copy(tabs = savanna.tabs!!.take(2)), lagoon)), target))
        assertNull(closeQuestion(both.copy(sessions = listOf(lagoon), session = lagoon.id, window = "window:l"), target))
        // A tab of that id in another session is not the target.
        assertNull(closeQuestion(both, CloseTarget.Tab(lagoon.id, "window:c")))
    }

    @Test
    fun theShownTabIsFoundBeforeItsSessionsListArrives() {
        // The palette's Close Tab: the top level describes the shown session first.
        val w = WindowInfo("window:b", "deploy", layout = LayoutNode(block = "block:b"))
        val early = UiState(status = "connected", sessions = listOf(SessionInfo(savanna.id, "bold-savanna")), session = savanna.id,
            windows = listOf(w), window = "window:b", focused = "block:b", blocks = mapOf("block:b" to BlockInfo(program = "sleep")))

        assertEquals("Close deploy? sleep is still running.", closeQuestion(early, CloseTarget.Tab(savanna.id, "window:b")))
        assertEquals("Close bold-savanna? 1 tab will end.", closeQuestion(early, CloseTarget.Session(savanna.id)))
        assertNull(closeQuestion(early, CloseTarget.Tab(savanna.id, "window:x")))
    }

    @Test
    fun thePaneQuestionNamesThePaneAndEndsWithIt() {
        val tab = WindowInfo("window:b", layout = LayoutNode(block = "block:b1"), floating = listOf(FloatingLayer("block:b2")))
        val state = both.copy(
            windows = listOf(tab), focused = "block:b1",
            blocks = mapOf("block:b2" to BlockInfo(title = "claude"), "block:b1" to BlockInfo()),
        )

        val tapped = CloseTarget.Pane("block:b2")
        assertEquals("Close claude? Its process will end.", closeQuestion(state, tapped))
        // Named as everywhere else (#208): a pane whose program is not reported is a terminal.
        assertEquals("Close Terminal? Its process will end.", closeQuestion(state, CloseTarget.Pane("block:b1")))
        // The server's label made from an ID is not a name: the pane is named by its program, else it is a terminal.
        val auto = BlockInfo(title = "Block 9ac42573", titleIsLabel = true)
        assertEquals("Close Terminal? Its process will end.",
            closeQuestion(state.copy(blocks = state.blocks + ("block:b2" to auto)), tapped))
        assertEquals("Close zsh? Its process will end.",
            closeQuestion(state.copy(blocks = state.blocks + ("block:b2" to auto.copy(program = "/bin/zsh"))), tapped))
        // A block may still be in the snapshot when its pane has left the tab.
        val otherTab = WindowInfo("window:c", layout = LayoutNode(block = "block:c1"))
        assertNull(closeQuestion(state.copy(windows = listOf(tab.copy(floating = emptyList()), otherTab)), tapped))
        // With its sibling gone, the tapped pane is now the last pane of its own tab.
        val onlyTapped = tab.copy(layout = LayoutNode(block = tapped.block), floating = emptyList())
        assertNull(closeQuestion(state.copy(windows = listOf(onlyTapped, otherTab)), tapped))
        // A lost connection could close nothing: the question ends, as the last pane's does.
        assertNull(closeQuestion(state.copy(status = "reconnecting"), tapped))
    }

    @Test
    fun theLastPaneQuestionOnlyRemainsForTheSameSolePane() {
        val block = "block:a1"
        val tab = WindowInfo("window:a", layout = LayoutNode(block = block))
        val target = CloseTarget.LastPane("session:a", tab.id, block)
        val state = UiState(status = "connected", session = target.session, windows = listOf(tab))

        assertEquals("Close this tab? Its pane and process will end.", closeQuestion(state, target))
        assertNull(closeQuestion(state.copy(session = "session:b"), target))
        assertNull(closeQuestion(state.copy(status = "connecting"), target))
        assertNull(closeQuestion(state.copy(windows = listOf(tab.copy(id = "window:b"))), target))
        assertNull(closeQuestion(state.copy(windows = listOf(tab.copy(layout = LayoutNode(block = "block:a2")))), target))
        assertNull(closeQuestion(state.copy(windows = listOf(tab.copy(minimized = true))), target))
        val split = tab.copy(floating = listOf(FloatingLayer("block:a2")))
        assertNull(closeQuestion(state.copy(windows = listOf(split)), target))
    }

    // What a rename dialog starts with: the name the unit really has, from every door.

    @Test
    fun aRemotesRenameStartsWithItsLabel() {
        val saved = listOf(Remote("remote:1", "workstation", "https://workstation.tailnet.example"), Remote("remote:2", "", "https://mac.tailnet.example"))

        assertEquals("workstation", renameText(RenameTarget.OfRemote("remote:1"), saved, UiState()))
        // The row draws "Remote" for a remote without a label; that word is not its name.
        assertEquals("", renameText(RenameTarget.OfRemote("remote:2"), saved, UiState()))
        assertEquals("", renameText(RenameTarget.OfRemote("remote:gone"), saved, UiState()))
    }

    @Test
    fun aSessionsRenameStartsWithItsOwnLabelNotTheStandIn() {
        val bare = SessionInfo("session:bare")
        val state = both.copy(sessions = both.sessions + bare)

        assertEquals("bold-savanna", renameText(RenameTarget.OfSession(savanna.id), emptyList(), state))
        // Its header reads "Session": saving the dialog unchanged must not name it that.
        assertEquals("Session", groups(state).last().label)
        assertEquals("", renameText(RenameTarget.OfSession(bare.id), emptyList(), state))
    }

    @Test
    fun aTabsRenameStartsWithTheLabelTheUserGaveAndEmptyForAnAutomaticOne() {
        fun text(tab: String) = renameText(RenameTarget.OfTab(savanna.id, tab), emptyList(), both)

        assertEquals("notes", text("window:c"))
        // "Window 0a0a0a0a" is the server's own; the row shows "zsh". Neither is a name to keep.
        assertEquals("", text("window:a"))
        assertEquals("", text("window:b"))
        // The shown tab before its session's list arrives (the palette's door).
        val early = UiState(sessions = listOf(SessionInfo(savanna.id)), session = savanna.id, windows = listOf(WindowInfo("window:z", "deploy")), window = "window:z")
        assertEquals("deploy", renameText(RenameTarget.OfTab(savanna.id, "window:z"), emptyList(), early))
    }

    @Test
    fun aSessionsButtonsNameTheSessionTheyAct() {
        assertEquals("New Tab in bold-savanna", newTabDescription("bold-savanna"))
        assertEquals("Menu of session bold-savanna", sessionMenuDescription("bold-savanna"))
    }

    @Test
    fun aSessionWithoutALabelIsNamedAsThePaletteNamesIt() {
        // The palette says "Switch to Session: Session" for it, and the header shows "Session".
        assertEquals("New Tab in Session", newTabDescription(""))
        assertEquals("Menu of session Session", sessionMenuDescription(""))
    }

    @Test
    fun aTabsCloseButtonNamesTheTabAsItsRowShowsIt() {
        // "zsh" is what the row shows for a tab with no label of its own ([TabRow.name]).
        assertEquals("Close tab zsh", closeTabDescription("zsh"))
        assertEquals("Close tab notes", closeTabDescription("notes"))
        assertEquals("Close tab Tab", closeTabDescription(""))
    }

    @Test
    fun aRemotesButtonsNameTheRemoteAndSayWhichWayTheChevronGoes() {
        assertEquals("Collapse workstation", remoteFoldDescription(expanded = true, remote = "workstation"))
        assertEquals("Expand workstation", remoteFoldDescription(expanded = false, remote = "workstation"))
        assertEquals("Menu of remote workstation", remoteMenuDescription("workstation"))
    }

    @Test
    fun aRemoteWithoutALabelIsNamedAsTheRowAndThePaletteNameIt() {
        assertEquals("Expand Remote", remoteFoldDescription(expanded = false, remote = ""))
        assertEquals("Menu of remote Remote", remoteMenuDescription(""))
    }
}
