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
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.DiscoveredServer
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
 * that a later one covers, and the row's name was only in its words. The row now carries its name.
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
