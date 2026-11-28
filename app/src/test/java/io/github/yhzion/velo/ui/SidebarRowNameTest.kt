package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * tab's row carry all they say (#238): the remote's name and connection, the tab's tile, title and status line.
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

    /**
     * Eight connected remotes with one tab each, in a sidebar 600 dp tall (#238). The list is scrolled until the words
     * tagged [wordsTag] of the row tagged [tag] start 1 dp under the Filter field's top: they are covered, the row's top
     * is not. The row says [said] there, as it does shown whole.
     */
    private fun assertScrolledRowKeepsItsWords(tag: String, wordsTag: String, said: List<String>) {
        val hosts = (1..8).map { n ->
            val tab = TabInfo("t$n", label = "Build release $n", program = "htop", cwd = "~/src/api", block = "block:t$n", active = true)
            SidebarHost(Remote("remote:host$n", "host$n", "https://host$n.example.ts.net"),
                UiState(status = "connected", server = "host$n", sessions = listOf(SessionInfo("session:$n", "s$n", listOf(tab))),
                    session = "session:$n", window = tab.id),
                ActivityReadState(), active = n == 1, removed = false)
        }
        compose.setContent {
            view = LocalView.current
            VeloTheme {
                Box(Modifier.width(393.dp).height(600.dp)) {
                    Sidebar(DeviceRow("velo-phone", "Running", "Running", TailnetChip("Connected", Status.Ok)), hosts,
                        Modifier.fillMaxSize(), actions())
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
     * Shortens the sidebar until its Filter band covers the words of the row tagged [tag] from just above their top,
     * leaving the row's top in sight, as a list scrolled to it does. The row still says [name], once.
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
        // The band's top 1 dp above the words: they are covered, the row's top is not.
        height = words.top - 1.dp + band
        compose.waitForIdle()
        val filter = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        assertTrue("the Filter band $filter covers the words $words", filter.top < words.top && filter.bottom > words.bottom)
        val said = compose.runOnIdle { Speech.saidAt(view, row.fetchSemanticsNode().id) }
        assertEquals("$name said once, among $said", 1, said.count { it == name })
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
