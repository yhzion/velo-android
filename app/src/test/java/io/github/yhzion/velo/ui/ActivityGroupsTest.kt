package io.github.yhzion.velo.ui

import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun groups(read: ActivityReadState, query: String = "") =
        activityGroups(state, active = true, query) { read.activity(it, connected = true) }.map { group -> group.priority to group.rows.map { it.name } }

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
    fun aFinishLeavesDoneOnceSeenWhileWaitingAndFailureStay() {
        val read = ActivityReadState()
        see(read, "new")
        see(read, "claude")
        see(read, "test")
        assertEquals(listOf(
            ActivityPriority.Blocked to listOf("claude"),
            ActivityPriority.Failed to listOf("test"),
            ActivityPriority.Working to listOf("codex", "make"),
            ActivityPriority.Done to listOf("old"),
        ), groups(read))
        see(read, "old")
        assertEquals(listOf(ActivityPriority.Blocked, ActivityPriority.Failed, ActivityPriority.Working), groups(read).map { it.first })
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
}
