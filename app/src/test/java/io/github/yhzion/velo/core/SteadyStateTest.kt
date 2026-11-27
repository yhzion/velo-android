package io.github.yhzion.velo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** [steady]: the state a screen that draws no title reads, so that a new title does not compose it again. */
class SteadyStateTest {
    private fun state(title: String, activity: String = "working", status: String = "", summary: String = "") = UiState(
        status = "connected",
        session = "s",
        window = "w",
        sessions = listOf(SessionInfo("s", "work", listOf(TabInfo("w", label = "build", block = "b", title = title,
            activity = activity, statusTitle = status, statusSummary = summary)))),
        windows = listOf(WindowInfo("w", layout = LayoutNode(block = "b"))),
        blocks = mapOf("b" to BlockInfo(title = title, program = "claude", activity = activity, statusTitle = status,
            statusSummary = summary)),
    )

    @Test fun aNewTitleOrReportTextLeavesItAsItWas() {
        val before = state("⠂ Building").steady()
        assertEquals(before, state("⠐ Building").steady())
        assertEquals(before, state("⠂ Building", status = "Tests", summary = "3 of 9").steady())
    }

    @Test fun whatTheWorkspaceDrawsStays() {
        val steady = state("⠂ Building").steady()
        assertEquals("", steady.blocks.getValue("b").title)
        assertEquals("", steady.sessions.single().tabs!!.single().title)
        assertEquals("claude", steady.blocks.getValue("b").program)
        assertEquals("build", steady.sessions.single().tabs!!.single().label)
        assertEquals(state("x").windows, steady.windows)
        assertEquals("w", steady.window)
    }

    @Test fun anActivityChangesIt() {
        assertNotEquals(state("x", activity = "working").steady(), state("x", activity = "idle").steady())
    }

    @Test fun aSessionWhoseTabsAreUnknownStaysUnknown() {
        val unknown = UiState(sessions = listOf(SessionInfo("s", "work", tabs = null)))
        assertEquals(unknown, unknown.steady())
    }
}
