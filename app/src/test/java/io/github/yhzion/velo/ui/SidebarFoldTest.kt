package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.fidelity.Speech
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.ToastSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A sidebar too short for its head (the toolbar and This Device) and the Filter's band folds the head into its list
 * (#209): in landscape with the keyboard up the Filter field keeps a row and stays in what a screen reader sees, and a
 * short sidebar's notices stay off the toolbar's buttons. Each reads the AccessibilityNodeInfo tree a service gets ([Speech]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class SidebarFoldTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)
    private lateinit var view: View

    private fun show(width: Dp, height: () -> Dp, notices: (@Composable ColumnScope.() -> Unit)? = null) = compose.setContent {
        view = LocalView.current
        VeloTheme {
            Box(Modifier.width(width).height(height())) {
                Sidebar(DeviceRow("velo-phone", "100.64.0.1 · Running", "Running", TailnetChip("Connected", Status.Ok)), emptyList(),
                    Modifier.fillMaxSize(), actions(), notices = notices)
            }
        }
    }

    // The list sets its collection info: a head folded into it lies under that node.
    private val inList = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo))

    private fun folded() = compose.onAllNodesWithTag("sidebar-toolbar").fetchSemanticsNodes().single().let {
        inList.matches(it)
    }

    /** The AccessibilityNodeInfo a service gets for the node tagged [tag], or null where it is left out. */
    private fun info(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().id.let { id ->
        compose.runOnIdle { view.accessibilityNodeProvider.createAccessibilityNodeInfo(id) }
    }

    private fun assertTheFilterHasItsRow() {
        val field = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        val sidebar = compose.onNodeWithTag("sidebar").getBoundsInRoot()
        assertTrue("the Filter band $field has its height", field.bottom - field.top >= 44.dp)
        assertTrue("the Filter band $field at the sidebar's $sidebar foot", field.bottom <= sidebar.bottom + 0.5.dp && field.top >= sidebar.top)
        val input = info("sidebar-filter-input")
        assertNotNull("the Filter field is in what a screen reader sees", input)
        assertTrue("the Filter field is shown to a screen reader", input!!.isVisibleToUser)
    }

    @Test fun aTallSidebarKeepsItsHeadAboveTheList() {
        show(393.dp, { 800.dp })
        compose.waitForIdle()
        assertTrue("the head is not in the list", !folded())
        assertEquals(compose.onNodeWithTag("sidebar").getBoundsInRoot().top, compose.onNodeWithTag("sidebar-toolbar").getBoundsInRoot().top)
        assertTheFilterHasItsRow()
    }

    @Test fun landscapeWithTheKeyboardUpFoldsTheHeadAndKeepsTheFilterRow() {
        // The Velvet in landscape with the keyboard up: about 130 dp, less than the head and the band.
        show(800.dp, { 130.dp })
        compose.waitForIdle()
        assertTrue("the head is folded into the list", folded())
        assertTheFilterHasItsRow()
        // The toolbar opens the list, so Toggle Sidebar is the first thing a screen reader reaches.
        val toggle = info("sidebar-toggle")
        assertNotNull("Toggle Sidebar is in what a screen reader sees", toggle)
        assertEquals(listOf("Toggle Sidebar"), Speech.said(view, toggle!!))
        assertEquals(1, compose.onAllNodesWithTag("sidebar-toggle").fetchSemanticsNodes().size)
    }

    @Test fun theFilterKeepsItsFocusAsTheKeyboardShortensTheSidebar() {
        var height by mutableStateOf(330.dp)
        show(800.dp, { height })
        compose.waitForIdle()
        assertTrue("the head is above the list at first", !folded())
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sidebar-filter")))
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val before = field.fetchSemanticsNode().id
        height = 130.dp
        compose.waitForIdle()
        assertTrue("the head is folded into the list", folded())
        field.assertIsFocused()
        assertEquals("the same field, not a new one", before, field.fetchSemanticsNode().id)
        assertTheFilterHasItsRow()
        // And back: the keyboard goes down, the head comes out of the list, and the field keeps its focus.
        height = 330.dp
        compose.waitForIdle()
        assertTrue("the head is above the list again", !folded())
        field.assertIsFocused()
        assertEquals(before, field.fetchSemanticsNode().id)
    }

    @Test fun aShortSidebarWithANoticeKeepsTheToolbarClearOfIt() {
        // Upright, about 240 dp tall (#209's comment, 237 to 300 dp): with the head above the list, the notice ended
        // just above the Filter band and lay over Toggle Sidebar, which left what a screen reader sees.
        show(393.dp, { 240.dp }, notices = { ToastSurface("Uploading x…") })
        compose.waitForIdle()
        assertTrue("the head is folded into the list", folded())
        val toast = compose.onNodeWithTag("toast").getBoundsInRoot()
        val toggle = compose.onNodeWithTag("sidebar-toggle").getBoundsInRoot()
        assertTrue("Toggle Sidebar $toggle is clear of the notice $toast", toggle.bottom <= toast.top)
        assertNotNull("Toggle Sidebar is in what a screen reader sees", info("sidebar-toggle"))
        assertTheFilterHasItsRow()
        val filter = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        assertTrue("the notice $toast lies under the Filter band $filter", toast.top >= filter.bottom)
    }

    private fun actions() = SidebarActions(
        toggleSidebar = {}, openSettings = {}, openPalette = {}, openDevice = {},
        selectRemote = {}, newSession = {}, renameRemote = {}, removeRemote = {}, sshDetails = {}, addRemote = {},
        found = emptyList(), addFound = {},
        selectSession = { _, _ -> }, newTab = { _, _ -> }, renameSession = { _, _ -> }, closeSession = { _, _ -> },
        selectTab = { _, _ -> }, renameTab = { _, _ -> }, moveTab = { _, _, _ -> }, closeTab = { _, _ -> },
        toggleDock = {},
    )
}
