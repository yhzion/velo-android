package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.fidelity.Speech
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A sidebar row keeps its name while the Filter field covers its words (#202): TalkBack said "unlabeled" for "Add
 * Remote…" half under the field, as it did for a radio button half under a header (#203). Compose leaves out a node
 * that a later one covers, and the row's name was only in its words. The row now carries its name. A remote's row and a
 * tab's row carry all they say (#238): the remote's name and connection, the tab's tile, title and status line. So do
 * a session's header and an Activity row (#279), Activity's group titles and This Device (#287).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class SidebarRowNameTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)
    private lateinit var view: View

    @Test fun addRemoteHalfUnderTheFilterFieldKeepsItsName() = assertHalfCoveredRowKeepsItsName("add-remote-row", "Add Remote…")

    @Test fun aFoundServerHalfUnderTheFilterFieldKeepsItsName() = assertHalfCoveredRowKeepsItsName("found-row-studio", "studio")

    @Test fun aRemoteRowHalfUnderTheFilterFieldKeepsItsWords() =
        assertScrolledRowKeepsItsWords("remote-row-host5", "remote-name-host5", listOf("Remote, host5, Connected"))

    @Test fun aTabRowHalfUnderTheFilterFieldKeepsItsWords() =
        assertScrolledRowKeepsItsWords("tab-row-t5", "tab-title-t5", listOf("htop, Build release 5, ~/src/api"))

    @Test fun aSessionHeaderHalfUnderTheFilterFieldKeepsItsName() =
        assertScrolledRowKeepsItsWords("session-header-s5", "session-name-s5", listOf("s5"))

    /**
     * A working row of Activity with a progress pie and its program's words (#279): the percent is said with the session
     * (#251). Each tab has a pie, so no row has working dots, which never rest. With its capsule the row is tall enough
     * that the list's edge cuts it here, and Compose keeps all a merging node cut by its scrolling parent's edge holds, covered
     * or not (#287): the row kept its words before #279 too. [aFinishedActivityRowHalfUnderTheFilterFieldKeepsItsWords]
     * is the one that lost them.
     */
    @Test fun anActivityRowHalfUnderTheFilterFieldKeepsItsWords() {
        val id = "remote:a/session:s/t5"
        assertScrolledRowKeepsItsWords("activity-row-$id", "activity-title-$id", listOf("htop, Build release 5, 30% · Build, 2m, Reading part 5"),
            listOf(activityHost(10) { "working" }), activity = true)
    }

    /** A finished tab's row of Activity has no line under it: short, it stays inside the list and lost all but its tile (#287). */
    @Test fun aFinishedActivityRowHalfUnderTheFilterFieldKeepsItsWords() {
        val id = "remote:a/session:s/t10"
        assertScrolledRowKeepsItsWords("activity-row-$id", "activity-title-$id", listOf("htop, Unread, Completed, Build release 10, Build, 2m"),
            listOf(activityHost(16) { "done" }), activity = true)
    }

    /** The title of Activity's Done group, under six working rows, was a heading with no name there (#287). */
    @Test fun anActivityGroupTitleHalfUnderTheFilterFieldKeepsItsName() =
        assertScrolledRowKeepsItsWords("activity-group-Done", "activity-group-title-Done", listOf("Done"),
            listOf(activityHost(12) { n -> if (n <= 6) "working" else "done" }), activity = true)

    /**
     * This Device in a sidebar too short for its head, which folds into the list (#209): the list's foot, the Filter
     * field, covers the row's dot: the field's own node, inside its band's padding, from about 15 dp above the dot's top,
     * the row's top above that node. The row lost the dot's word, the connection's state, there (#287); it says it, once,
     * as shown whole.
     */
    @Test fun thisDeviceHalfUnderTheFilterFieldKeepsItsState() {
        var height by mutableStateOf(800.dp)
        compose.setContent {
            view = LocalView.current
            VeloTheme {
                Box(Modifier.width(393.dp).height(height)) {
                    Sidebar(DeviceRow("velo-phone", "100.64.0.9 · Running", "Running", TailnetChip("Connected", Status.Ok)), emptyList(),
                        Modifier.fillMaxSize(), actions())
                }
            }
        }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("this-device-row")
        fun saidNow() = compose.runOnIdle { Speech.saidAt(view, row.fetchSemanticsNode().id) }
        val whole = saidNow()
        val dot = compose.onNodeWithTag("this-device-dot", useUnmergedTree = true).getBoundsInRoot()
        val band = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot().let { it.bottom - it.top }
        height = dot.top - 1.dp + band
        compose.waitForIdle()
        compose.onNode(hasTestTag("this-device-row") and hasAnyAncestor(hasScrollAction())).assertExists("the head folds into the list")
        val now = compose.onNodeWithTag("this-device-dot", useUnmergedTree = true).getBoundsInRoot()
        val filter = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        assertTrue("the Filter band $filter covers the dot $now", filter.top < now.top && filter.bottom > now.bottom)
        assertTrue("the row's top is in sight", row.getBoundsInRoot().top < filter.top - 2.dp)
        assertEquals("half under the Filter field as shown whole", whole, saidNow())
        assertEquals("each part once, in the row's own words", listOf("This Device, velo-phone, 100.64.0.9, Connected"), whole)
    }

    /**
     * Eight connected remotes with one tab each, in a sidebar 600 dp tall (#238), unless [hosts] gives others, and with
     * Activity on where [activity] says so (#279). The list is scrolled until the words tagged [wordsTag] of the row
     * tagged [tag] start 1 dp under the Filter field's top: they are covered, the row's top is not. The row says [said]
     * there, as it does shown whole.
     */
    private fun assertScrolledRowKeepsItsWords(tag: String, wordsTag: String, said: List<String>, hosts: List<SidebarHost> = remotes(),
        activity: Boolean = false) {
        compose.setContent {
            view = LocalView.current
            VeloTheme {
                Box(Modifier.width(393.dp).height(600.dp)) {
                    Sidebar(DeviceRow("velo-phone", "Running", "Running", TailnetChip("Connected", Status.Ok)), hosts,
                        Modifier.fillMaxSize(), actions(), state = remember { SidebarState().apply { activityOnly = activity } })
                }
            }
        }
        compose.waitForIdle()
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        val row = compose.onNodeWithTag(tag)
        // Shown whole: 200 dp under the list's top, well above the band.
        val top = with(compose.density) { (row.getBoundsInRoot().top - list.getBoundsInRoot().top - 200.dp).toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, top) }
        compose.waitForIdle()
        fun saidNow() = compose.runOnIdle { Speech.saidAt(view, row.fetchSemanticsNode().id) }
        val whole = saidNow()
        val words = compose.onNodeWithTag(wordsTag, useUnmergedTree = true).getBoundsInRoot()
        val filter = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        val by = with(compose.density) { (words.top - filter.top - 1.dp).toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }
        compose.waitForIdle()
        val now = compose.onNodeWithTag(wordsTag, useUnmergedTree = true).getBoundsInRoot()
        val band = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        assertTrue("the Filter band $band covers the words $now", band.top < now.top && band.bottom > now.bottom)
        assertTrue("the row's top is in sight", row.getBoundsInRoot().top < band.top - 2.dp)
        assertEquals("half under the Filter field as shown whole", whole, saidNow())
        assertEquals("each part once, in the row's own words", said, whole)
    }

    /**
     * Shortens the sidebar until the Filter field's own node, inside its band's padding, covers the words of the row
     * tagged [tag] from about 15 dp above their top: the height leaves out the band's padding under the field. The row's
     * top stays above that node, by about 2 dp for the rows here, though under the band's painted padding; a screen
     * reader goes by the field's node. The row still says [name], once.
     */
    private fun assertHalfCoveredRowKeepsItsName(tag: String, name: String) {
        var height by mutableStateOf(800.dp)
        compose.setContent {
            view = LocalView.current
            VeloTheme {
                Box(Modifier.width(393.dp).height(height)) {
                    Sidebar(DeviceRow("velo-phone", "Running", "Running", TailnetChip("Connected", Status.Ok)), emptyList(),
                        Modifier.fillMaxSize(), actions())
                }
            }
        }
        compose.waitForIdle()
        val row = compose.onNodeWithTag(tag)
        val words = compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).getBoundsInRoot()
        val band = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("the row reaches above its words", row.getBoundsInRoot().top < words.top - 2.dp)
        // The field's node starts about 15 dp above the words, just under the row's top: they are covered, the top is not.
        height = words.top - 1.dp + band
        compose.waitForIdle()
        val filter = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        assertTrue("the Filter band $filter covers the words $words", filter.top < words.top && filter.bottom > words.bottom)
        val said = compose.runOnIdle { Speech.saidAt(view, row.fetchSemanticsNode().id) }
        assertEquals("$name said once, among $said", 1, said.count { it == name })
    }

    /**
     * One remote whose one session has [count] tabs, each in Activity's group [state] says for its number, its program's
     * words and, working, a progress pie, so that no row has working dots, which never rest.
     */
    private fun activityHost(count: Int, state: (Int) -> String): SidebarHost {
        val tabs = (1..count).map { n ->
            val activity = state(n)
            TabInfo("t$n", label = "Build release $n", program = "htop", cwd = "~/src/api", block = "block:t$n", active = n == 1,
                activity = activity, since = System.currentTimeMillis() - 120_000 - n * 1_000L, progress = if (activity == "working") 30 else null,
                statusSummary = "Reading part $n")
        }
        return SidebarHost(Remote("remote:a", "a", "https://a.example.ts.net"),
            UiState(status = "connected", server = "a", sessions = listOf(SessionInfo("session:s", "Build", tabs)), session = "session:s", window = "t1"),
            ActivityReadState(), active = true, removed = false)
    }

    private fun remotes() = (1..8).map { n ->
        val tab = TabInfo("t$n", label = "Build release $n", program = "htop", cwd = "~/src/api", block = "block:t$n", active = true)
        SidebarHost(Remote("remote:host$n", "host$n", "https://host$n.example.ts.net"),
            UiState(status = "connected", server = "host$n", sessions = listOf(SessionInfo("session:$n", "s$n", listOf(tab))),
                session = "session:$n", window = tab.id),
            ActivityReadState(), active = n == 1, removed = false)
    }

    private fun actions() = SidebarActions(
        toggleSidebar = {}, openSettings = {}, openPalette = {}, openDevice = {},
        selectRemote = {}, newSession = {}, renameRemote = {}, removeRemote = {}, sshDetails = {}, addRemote = {},
        found = listOf(DiscoveredServer("studio", "http://100.64.0.2:7777")), addFound = {},
        selectSession = { _, _ -> }, newTab = { _, _ -> }, renameSession = { _, _ -> }, closeSession = { _, _ -> },
        selectTab = { _, _ -> }, renameTab = { _, _ -> }, moveTab = { _, _, _ -> }, closeTab = { _, _ -> },
        toggleDock = {},
    )
}
