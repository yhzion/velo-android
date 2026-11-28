package io.github.yhzion.velo.ui

import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Activity's groups and the dot on the eye, as Rex for Mac draws them. */
class ActivityGroupsTest {
    private val build = SessionInfo("session:build", "build", listOf(
        TabInfo("window:make", "make", block = "block:make", program = "make", activity = "working", since = 3_000),
        TabInfo("window:shell", "shell", block = "block:shell", program = "zsh"),
        TabInfo("window:test", "test", block = "block:test", program = "gradle", activity = "error", since = 2_000),
    ))
    private val agents = SessionInfo("session:agents", "agents", listOf(
        TabInfo("window:claude", "claude", icon = "claude", block = "block:claude", program = "node", activity = "blocked", since = 1_000),
        TabInfo("window:old", "old", block = "block:old", program = "make", activity = "done", since = 1_000),
        TabInfo("window:new", "new", block = "block:new", program = "make", activity = "done", since = 5_000),
        TabInfo("window:codex", "codex", block = "block:codex", program = "node", activity = "working", since = 4_000),
    ))
    private val state = UiState(status = "connected", sessions = listOf(build, agents), session = build.id)
    private val rows = sessionGroups(state, true, emptySet(), false, "").flatMap { it.rows }

    private fun source(s: UiState, read: ActivityReadState, host: String = "remote:a", active: Boolean = true) =
        ActivitySource(host, s, active, read.entered) { read.activity(it, connected = true) }

    /** Activity's list of [s] as the sidebar draws it, after the connection noted the state at [now]. */
    private fun list(s: UiState, read: ActivityReadState, query: String = "", now: Long = 10_000): List<ActivityGroup> {
        observeActivity(s, read, now)
        return activityGroups(listOf(source(s, read)), query)
    }

    private fun groups(read: ActivityReadState, query: String = "") =
        list(state, read, query).map { group -> group.priority to group.entries.map { it.row.name } }

    /** The user looks at [name]: its pane is shown, so its report is seen. */
    private fun see(read: ActivityReadState, name: String) {
        val row = rows.single { it.name == name }
        val tab = (build.tabs!! + agents.tabs!!).single { it.id == row.tab }
        read.show(this, mapOf(PaneAddress(row.session, row.tab, row.block!!) to BlockInfo(activity = tab.activity, since = tab.since)))
    }

    private fun eyeDot(read: ActivityReadState) = rows.any { wantsAttention(read.activity(it, connected = true)) }

    @Test
    fun activityGroupsTabsOfEverySessionByPriorityInRexForMacsOrderNewestFirst() {
        assertEquals(listOf(
            ActivityPriority.Blocked to listOf("claude"),
            ActivityPriority.Failed to listOf("test"),
            ActivityPriority.Working to listOf("codex", "make"),
            ActivityPriority.Done to listOf("new", "old"),
        ), groups(ActivityReadState()))
        assertEquals(listOf("Blocked", "Failed", "Working", "Done"), ActivityPriority.entries.map { it.title })
    }

    @Test
    fun aSeenFinishStaysInDoneAsInRexForMac2185WhileWaitingAndFailureStayToo() {
        val read = ActivityReadState()
        for (name in listOf("new", "claude", "test", "old")) see(read, name)
        assertEquals(listOf(
            ActivityPriority.Blocked to listOf("claude"),
            ActivityPriority.Failed to listOf("test"),
            ActivityPriority.Working to listOf("codex", "make"),
            ActivityPriority.Done to listOf("new", "old"),
        ), groups(read))
    }

    @Test
    fun theEyesDotStaysWhileAnyReportIsUnseenAndWorkNeverLightsIt() {
        val read = ActivityReadState()
        assertTrue(eyeDot(read))
        for (name in listOf("claude", "test", "old")) {
            see(read, name)
            assertTrue("$name seen, \"new\" is not", eyeDot(read))
        }
        see(read, "new")
        // Only work is left: make and codex still run, and the eye has no dot.
        assertFalse(eyeDot(read))
        assertEquals(listOf("codex", "make"), groups(read).single { it.first == ActivityPriority.Working }.second)
    }

    @Test
    fun anIdleReportJoinsDoneAndATabWithNoReportStaysOut() {
        val idle = SessionInfo("session:idle", "idle", listOf(
            TabInfo("window:idle", "rested", block = "block:idle", program = "node", reportedIdle = true),
            TabInfo("window:plain", "plain", block = "block:plain", program = "zsh"),
        ))
        val s = UiState(status = "connected", sessions = listOf(idle), session = idle.id)
        val listed = list(s, ActivityReadState(), now = 0)
        assertEquals(listOf(ActivityPriority.Done to listOf("rested")), listed.map { g -> g.priority to g.entries.map { it.row.name } })
    }

    @Test
    fun everyRemoteIsInOneListOrderedByWhenATabCameIntoItsGroup() {
        val other = UiState(status = "connected", session = "session:x", sessions = listOf(SessionInfo("session:x", "X", listOf(
            TabInfo("window:late", "late", block = "block:late", program = "make", activity = "working", since = 3_500),
        ))))
        val a = ActivityReadState()
        val b = ActivityReadState()
        observeActivity(state, a, 10_000)
        observeActivity(other, b, 10_000)
        val working = activityGroups(listOf(source(state, a), source(other, b, "remote:b", false)), "").single { it.priority == ActivityPriority.Working }
        assertEquals(listOf("codex", "late", "make"), working.entries.map { it.row.name })
        assertEquals(listOf("remote:a", "remote:b", "remote:a"), working.entries.map { it.host })
        assertEquals(listOf("X", "build"), working.entries.drop(1).map { it.sessionLabel })
    }

    @Test
    fun aNewReportInTheSameStateKeepsItsPlaceAndAMoveTakesTheTopOfItsGroup() {
        val entered = ActivityEntered()
        assertEquals(3_000L, entered.note("s", "k", ActivityPriority.Working, 3_000, 9_000))
        // New words, the same state: the report's time moves, the place does not.
        assertEquals(3_000L, entered.note("s", "k", ActivityPriority.Working, 8_000, 9_000))
        // Finished: the finish's report time.
        assertEquals(9_500L, entered.note("s", "k", ActivityPriority.Done, 9_500, 9_600))
        // Idle after a failure has no report time: the clock's.
        entered.note("s", "j", ActivityPriority.Failed, 9_000, 9_000)
        assertEquals(9_700L, entered.note("s", "j", ActivityPriority.Done, null, 9_700))
        // A stale time does not put a move below what came before it.
        assertEquals(12_000L, entered.note("s", "k", ActivityPriority.Working, 9_000, 12_000))
        assertEquals(12_000L, entered.at("s", "k"))
        entered.retain { _, tab -> tab != "k" }
        assertNull(entered.at("s", "k"))
        assertEquals(1_000L, entered.note("s", "k", ActivityPriority.Working, 1_000, 12_000))
    }

    @Test
    fun theTimesSurviveAFilterAndADroppedConnectionAndFollowTheStateWhileNothingShowsThem() {
        val read = ActivityReadState()
        fun withCodex(since: Long, activity: String = "working", words: String = "") = state.copy(sessions = listOf(build,
            agents.copy(tabs = agents.tabs!!.map { if (it.id == "window:codex") it.copy(activity = activity, since = since, statusSummary = words) else it })))
        fun codexAt(s: UiState, query: String = "") = activityGroups(listOf(source(s, read)), query).flatMap { it.entries }.single { it.row.name == "codex" }.enteredAt
        observeActivity(withCodex(4_000), read, 4_000)
        // Its program reports new words often; a filter hides it and shows it again; the remote drops and comes back.
        observeActivity(withCodex(20_000, words = "Reading"), read, 20_000)
        assertEquals(listOf<String>(), activityGroups(listOf(source(withCodex(20_000), read)), "nothing").flatMap { it.entries }.map { it.row.name })
        observeActivity(withCodex(30_000, words = "Writing"), read, 30_000)
        assertEquals(4_000L, codexAt(withCodex(30_000, words = "Writing")))
        // While nothing shows it, it finishes and starts again: the list it opens to has the new start.
        observeActivity(withCodex(40_000, "done"), read, 40_000)
        observeActivity(withCodex(50_000), read, 50_000)
        assertEquals(50_000L, codexAt(withCodex(50_000)))
        // A tab that went is forgotten; one of a session whose tabs are not known is kept.
        observeActivity(state.copy(sessions = listOf(build, agents.copy(tabs = null))), read, 60_000)
        assertEquals(50_000L, read.entered.at("session:agents", "window:codex"))
        observeActivity(state.copy(sessions = listOf(build)), read, 60_000)
        assertNull(read.entered.at("session:agents", "window:codex"))
    }

    @Test
    fun aTabWhoseGroupComesFromAnotherPaneDoesNotShowTheLeadPanesWords() {
        // The lead pane finished with words; another pane of the tab waits for approval.
        val tab = TabInfo("window:two", "two", icon = "claude", block = "block:lead", terminalBlocks = listOf("block:lead", "block:other"),
            program = "node", activity = "done", since = 1_000, statusSummary = "All tests passed")
        val s = UiState(status = "connected", session = "session:s", sessions = listOf(SessionInfo("session:s", "s", listOf(tab))))
        val read = ActivityReadState()
        read.update(PaneAddress("session:s", "window:two", "block:other"),
            mapOf("r" to io.github.yhzion.velo.SeenReport(state = "blocked", updatedAt = "1970-01-01T00:00:02Z", kind = "permission")))
        val entry = list(s, read).single().entries.single()
        assertEquals(ActivityPriority.Blocked, entry.priority)
        assertNull("the lead pane's finish is not what it waits for", entry.message)
    }

    @Test
    fun aWorkingTabStartedAgainMovesToTheTopOfWorking() {
        val read = ActivityReadState()
        fun working(s: UiState, now: Long) = list(s, read, now = now).single { it.priority == ActivityPriority.Working }.entries.map { it.row.name }
        assertEquals(listOf("codex", "make"), working(state, 10_000))
        val tabs = agents.tabs!!.map { if (it.id == "window:new") it.copy(activity = "working", since = 11_000) else it }
        val again = state.copy(sessions = listOf(build, agents.copy(tabs = tabs)))
        assertEquals(listOf("new", "codex", "make"), working(again, 11_000))
        // codex reports again with new words: it stays where it is.
        val words = again.copy(sessions = listOf(build, agents.copy(tabs = tabs.map { if (it.id == "window:codex") it.copy(since = 12_000, statusSummary = "Reading") else it })))
        assertEquals(listOf("new", "codex", "make"), working(words, 12_000))
    }

    @Test
    fun aWorkingRowSaysItsProgramsWordsElseItsTitleAndADoneRowSaysNothing() {
        fun tab(vararg extra: Pair<String, String>) = TabInfo("window:w", "w", block = "block:w", program = "node", activity = "working",
            statusSummary = extra.toMap()["msg"].orEmpty(), statusTitle = extra.toMap()["title"].orEmpty())
        fun message(t: TabInfo): String? {
            val s = UiState(status = "connected", session = "session:s", sessions = listOf(SessionInfo("session:s", "s", listOf(t))))
            return list(s, ActivityReadState(), now = 0).single().entries.single().message
        }
        assertEquals("1 shell still running", message(tab("msg" to "1 shell still running", "title" to "Fix it")))
        assertEquals("Fix the sidebar", message(tab("title" to "Fix the sidebar").copy(label = "alpha")))
        assertNull("neither", message(tab()))
        assertNull("only the name it shows", message(tab("title" to "alpha").copy(label = "alpha")))
        assertEquals("words that are the name give way to the title", "Running tests",
            message(tab("msg" to "Build", "title" to "Running tests").copy(label = "Build")))
        assertEquals("one line, no controls that turn it around", "a b", message(tab("msg" to "a\n\u202eb")))
        assertNull("done", message(tab("msg" to "Finished").copy(activity = "done")))
        assertEquals("Tests failed", message(tab("msg" to "Tests failed").copy(activity = "error")))
        // A waiting tab with no title of its own is named by its words; its well still says them, in two lines.
        val unnamed = tab("msg" to "Allow writing the notes?").copy(label = "", program = "bash", activity = "blocked", kind = "permission")
        assertEquals("Allow writing the notes?", message(unnamed))
    }

    @Test
    fun theSecondLineSaysAShortAge() {
        assertEquals("now", shortAge(0, 59_999))
        assertEquals("now", shortAge(30_000, 0))
        assertNull("far ahead of the clock", shortAge(120_000, 0))
        assertEquals("7m", shortAge(0, 7 * 60_000 + 59_000))
        assertEquals("1h", shortAge(0, 3_600_000 + 59 * 60_000))
        assertEquals("19h", shortAge(0, 19 * 3_600_000L))
        assertEquals("1d", shortAge(0, 47 * 3_600_000L))
    }

    @Test
    fun anAgeChangesAtTheNextWholeMinute() {
        assertEquals(60_000L, nextAgeChangeMs(0, 0))
        assertEquals(1_000L, nextAgeChangeMs(0, 59_000))
        assertEquals(59_000L, nextAgeChangeMs(0, 61_000))
        assertEquals(30_000L, nextAgeChangeMs(30_000, 0))
    }

    @Test
    fun anEmptyListSaysWhy() {
        assertEquals("No matching tab", activityEmptyNote("x", connected = true))
        assertEquals("No remote is connected.", activityEmptyNote("", connected = false))
        assertEquals("Nothing is working or waiting.", activityEmptyNote("", connected = true))
    }

    @Test
    fun aWaitingWellGivesItsReasonAndAFailedOneNone() {
        assertEquals("Waiting for an answer", wellReason(TabIndicator.Question))
        assertEquals("Waiting for approval", wellReason(TabIndicator.Permission))
        assertNull(wellReason(TabIndicator.Error))
    }
}
