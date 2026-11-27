package io.github.yhzion.velo.ui

import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.SeenReport
import io.github.yhzion.velo.core.BlockInfo
import org.junit.Assert.*
import org.junit.Test

class ActivityReadStateTest {
    private val lead = PaneAddress("s", "w", "one")
    private val split = lead.copy(block = "two")
    private val row = TabRow("s", "w", "Thread", "", "claude", "claude", 2, false, "idle", "", false,
        block = lead.block, terminalBlocks = listOf(lead.block, split.block))
    private val time = "2026-11-21T01:00:00.123456Z"
    private val later = "2026-11-21T01:00:00.123789Z"

    @Test fun splitPromotesItsOwnIconAndOnlyTheActuallyVisiblePanesAreRead() {
        val state = ActivityReadState()
        state.update(lead, mapOf("work" to SeenReport("working", time)))
        state.update(split, mapOf("error" to SeenReport("error", time, agent = "codex")))
        assertEquals(setOf(lead), state.show(1, mapOf(lead to null)))
        assertEquals(TabIndicator.Error, state.activity(row, true).badge)
        assertEquals("codex", state.activity(row, true).icon)
        assertEquals(setOf(lead, split), state.show(2, mapOf(split to null)))
        assertEquals(setOf(split), state.hide(1))
        assertNull(state.activity(row, true).badge)
        assertEquals(TabIndicator.Working, state.activity(row, true).indicator)
        state.hide(2)
        assertNull(state.activity(row, true).badge)
    }

    @Test fun readReceiptSurvivesRestartAndRepeatedStatusesUsePreciseTimestampsAndRecordIDs() {
        var saved: String? = null
        val original = ActivityReadState { saved = it }
        val report = SeenReport("blocked", time, kind = "permission")
        original.update(split, mapOf("first" to report))
        assertEquals(TabIndicator.Permission, original.activity(row, true).badge)
        original.show(1, mapOf(lead to null, split to null))
        original.hide(1)
        val restored = ActivityReadState(saved)
        restored.update(split, mapOf("first" to report))
        assertNull(restored.activity(row, true).badge)
        restored.update(split, mapOf("first" to report.copy(updatedAt = later)))
        assertEquals(TabIndicator.Permission, restored.activity(row, true).badge)
        restored.show(1, mapOf(split to null))
        restored.hide(1)
        restored.update(split, mapOf("second" to report.copy(updatedAt = later)))
        assertEquals(TabIndicator.Permission, restored.activity(row, true).badge)
        restored.update(split, emptyMap())
        assertNull(restored.activity(row, true).badge)
    }

    @Test fun olderReportsWithNoTimestampBecomeUnreadAfterTheNextStateTransition() {
        val state = ActivityReadState()
        val finished = mapOf("turn" to SeenReport("done"))
        state.update(split, finished)
        state.show(1, mapOf(split to null))
        state.hide(1)
        assertNull(state.activity(row, true).badge)
        state.update(split, mapOf("turn" to SeenReport("working")))
        state.update(split, finished)
        assertEquals(TabIndicator.Done, state.activity(row, true).badge)
    }

    @Test fun viewingASnapshotBeforeRecordsArriveAlsoReadsTheMatchingInitialRecord() {
        val state = ActivityReadState()
        val ms = java.time.Instant.parse(time).toEpochMilli()
        val finished = row.copy(activity = "done", since = ms, indicator = TabIndicator.Done)
        assertEquals(TabIndicator.Done, state.activity(finished, true).badge)
        state.show(1, mapOf(lead to BlockInfo(activity = "done", since = ms)))
        state.hide(1)
        assertNull(state.activity(finished, true).badge)
        state.update(lead, mapOf("x" to SeenReport("done", time)))
        assertNull(state.activity(finished, true).badge)
        state.update(lead, mapOf("x" to SeenReport("done", later)))
        assertEquals(TabIndicator.Done, state.activity(finished, true).badge)
        assertNull(state.activity(finished, false).badge)
    }

    /** A `node` tab whose pane runs Claude: the tile is Claude only because a report names it. */
    private val node = row.copy(icon = "node", program = "node")

    @Test fun aSeenReportKeepsTheAgentItNamesButLosesItsBadge() {
        val state = ActivityReadState()
        state.update(lead, mapOf("turn" to SeenReport("done", time, agent = "claude")))
        state.show(1, mapOf(lead to null))
        state.hide(1)
        val seen = state.activity(node, true)
        assertEquals("claude", seen.icon)
        assertNull(seen.badge)
    }

    @Test fun anUnseenReportShowsItsBadgeAndTheAgentItNames() {
        val state = ActivityReadState()
        state.update(lead, mapOf("turn" to SeenReport("done", time, agent = "claude")))
        val unseen = state.activity(node, true)
        assertEquals("claude", unseen.icon)
        assertEquals(TabIndicator.Done, unseen.badge)
    }

    @Test fun aSeenReportThatLeavesThePaneTakesItsAgentWithIt() {
        val state = ActivityReadState()
        state.update(lead, mapOf("turn" to SeenReport("done", time, agent = "claude")))
        state.show(1, mapOf(lead to null))
        state.hide(1)
        state.update(lead, emptyMap())
        assertEquals("node", state.activity(node, true).icon)
    }

    @Test fun offlineShowsTheProgramEvenWhileAReportNamesAnAgent() {
        val state = ActivityReadState()
        state.update(lead, mapOf("turn" to SeenReport("done", time, agent = "claude")))
        assertEquals("node", state.activity(node, false).icon)
        state.show(1, mapOf(lead to null))
        state.hide(1)
        assertEquals("node", state.activity(node, false).icon)
    }

    @Test fun amongSeenReportsTheAgentFollowsTheShownReportsRanking() {
        val state = ActivityReadState()
        state.update(lead, mapOf("turn" to SeenReport("done", time, agent = "claude")))
        state.update(split, mapOf("turn" to SeenReport("error", later, agent = "codex")))
        state.show(1, mapOf(lead to null, split to null))
        state.hide(1)
        // Error outranks Done, as it would if both were unseen.
        assertEquals("codex", state.activity(node, true).icon)
        assertNull(state.activity(node, true).badge)
    }
}
