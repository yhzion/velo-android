package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.fidelity.Speech
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Activity's list as a screen reader gets it (#247, #248, #249): its groups' titles are headings, a working row's
 * capsule says its words once, also while new words roll in, and a finished row has no third line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class ActivitySpeechTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val actions = SidebarActions(
        toggleSidebar = {}, openSettings = {}, openPalette = {}, openDevice = {},
        selectRemote = {}, newSession = {}, renameRemote = {}, removeRemote = {}, sshDetails = {}, addRemote = {},
        found = emptyList(), addFound = {},
        selectSession = { _, _ -> }, newTab = { _, _ -> }, renameSession = { _, _ -> }, closeSession = { _, _ -> },
        selectTab = { _, _ -> }, renameTab = { _, _ -> }, moveTab = { _, _, _ -> }, closeTab = { _, _ -> },
    )

    private fun state(words: String) = UiState(status = "connected", session = "session:s", sessions = listOf(SessionInfo("session:s", "Build", listOf(
        TabInfo("w1", "make", block = "b1", program = "make", activity = "working", since = 1_000, statusSummary = words),
        TabInfo("w2", "tests", block = "b2", program = "gradle", activity = "blocked", kind = "question", since = 2_000, statusSummary = "Run them all?"),
        TabInfo("w3", "deploy", block = "b3", program = "make", activity = "done", since = 3_000, statusSummary = "Deployed"),
    ))))

    @Test fun groupsAreHeadingsTheCapsuleIsSaidOnceAndAFinishHasNoThirdLine() {
        var ui by mutableStateOf(state("Reading the code"))
        val read = ActivityReadState()
        lateinit var view: View
        // The working dots and a running capsule never rest: the clock moves only when the test moves it.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            VeloTheme(UiSize.Medium) {
                observeActivity(ui, read, 10_000)
                Sidebar(deviceRow(TailnetState.Running("velo-phone", listOf("100.101.12.7")), paused = false),
                    listOf(SidebarHost(Remote("remote:a", "a", "https://a.example.ts.net"), ui, read, active = true, removed = false)),
                    Modifier, actions, state = remember { SidebarState().apply { activityOnly = true } })
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
            .map { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString() }
        assertEquals(listOf("Blocked", "Working", "Done"), headings.filter { it in ActivityPriority.entries.map(ActivityPriority::title) })

        val working = "remote:a/session:s/w1"
        fun said() = compose.runOnIdle { Speech.saidAt(view, compose.onNodeWithTag("activity-row-$working").fetchSemanticsNode().id) }
        assertEquals(1, said().count { "Reading the code" in it })

        // New words roll in: halfway, the row says the new words once and the old ones no more.
        ui = state("Writing the capsule")
        compose.mainClock.advanceTimeBy(160)
        val midway = said()
        assertEquals(midway.toString(), 1, midway.count { "Writing the capsule" in it })
        assertFalse(midway.toString(), midway.any { "Reading the code" in it })
        compose.mainClock.advanceTimeBy(1_000)

        val done = "remote:a/session:s/w3"
        assertTrue(compose.onAllNodesWithTag("activity-capsule-$done", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("activity-well-$done", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals(1, compose.onAllNodesWithTag("activity-well-remote:a/session:s/w2", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("activity-capsule-$working", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
