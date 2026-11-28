package io.github.yhzion.velo.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.robolectric.Shadows.shadowOf
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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

    /** A screen reader is on, before anything is composed: Compose gives the tree to a service only then. */
    @Before fun screenReaderOn() {
        val manager = ApplicationProvider.getApplicationContext<Application>().getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    private fun show(width: Dp, height: () -> Dp, notices: (@Composable () -> Unit)? = null) = compose.setContent {
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

    /** Puts a screen reader's focus on the node tagged [tag] (merged), as TalkBack does; returns its id. */
    private fun focus(tag: String): Int {
        val id = compose.onNodeWithTag(tag).fetchSemanticsNode().id
        compose.runOnIdle { view.accessibilityNodeProvider.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        compose.waitForIdle()
        return id
    }

    private fun focused(id: Int) = compose.runOnIdle { view.accessibilityNodeProvider.createAccessibilityNodeInfo(id)?.isAccessibilityFocused == true }

    private val filterField get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sidebar-filter")))

    @Test fun aTallSidebarKeepsItsHeadAboveTheList() {
        show(393.dp, { 800.dp })
        compose.waitForIdle()
        assertEquals(compose.onNodeWithTag("sidebar").getBoundsInRoot().top, compose.onNodeWithTag("sidebar-toolbar").getBoundsInRoot().top)
        assertTheFilterHasItsRow()
        assertTrue("the head is not in the list", !folded())
    }

    @Test fun landscapeWithTheKeyboardUpKeepsTheFilterRowAndTheToolbar() {
        // The Velvet in landscape with the keyboard up: about 130 dp, less than the head and the band.
        show(800.dp, { 130.dp })
        compose.waitForIdle()
        assertTheFilterHasItsRow()
        // The toolbar opens the list, so Toggle Sidebar is the first thing a screen reader reaches.
        val toggle = info("sidebar-toggle")
        assertNotNull("Toggle Sidebar is in what a screen reader sees", toggle)
        assertEquals(listOf("Toggle Sidebar"), Speech.said(view, toggle!!))
        assertEquals(1, compose.onAllNodesWithTag("sidebar-toggle").fetchSemanticsNodes().size)
        assertTrue("the head is folded into the list", folded())
    }

    @Test fun theFilterKeepsTheScreenReaderFocusAsTheKeyboardShortensTheSidebar() {
        var height by mutableStateOf(330.dp)
        show(800.dp, { height })
        compose.waitForIdle()
        val field = filterField.fetchSemanticsNode().id
        compose.runOnIdle { view.accessibilityNodeProvider.performAction(field, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        compose.waitForIdle()
        assertTrue("the Filter field has the focus at first", focused(field))
        height = 130.dp
        compose.waitForIdle()
        assertTrue("the Filter field keeps the screen reader's focus", focused(field))
        assertTheFilterHasItsRow()
        // And back: the keyboard goes down, the head comes out of the list, and the field keeps the focus.
        height = 330.dp
        compose.waitForIdle()
        assertTrue("the Filter field keeps the focus as the keyboard goes", focused(field))
    }

    @Test fun aShortSidebarHasItsNoticesUnderTheFilterBand() {
        // Upright, about 240 dp tall (#209's comment, 237 to 300 dp): with the head above the list, the notices ended
        // just above the Filter band, over This Device.
        show(393.dp, { 240.dp }, notices = { ToastSurface("Uploading x…") })
        compose.waitForIdle()
        val toast = compose.onNodeWithTag("toast").getBoundsInRoot()
        val filter = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        assertTrue("the notice $toast lies under the Filter band $filter", toast.top >= filter.bottom)
        val toggle = compose.onNodeWithTag("sidebar-toggle").getBoundsInRoot()
        assertTrue("Toggle Sidebar $toggle is clear of the notice $toast", toggle.bottom <= toast.top)
        assertTheFilterHasItsRow()
    }

    @Test fun theToolbarKeepsTheScreenReaderFocusAsANoticeFoldsTheHead() {
        // 280 dp: a notice folds the head into the list. The head is moved there, nodes and all, so the focus on one of
        // its controls stays (#209's review). Its going moves the head back above the list, and the focus stays (#239).
        var notice by mutableStateOf(false)
        show(393.dp, { 280.dp }, notices = { if (notice) Box(Modifier.fillMaxWidth().height(60.dp)) })
        compose.waitForIdle()
        val toggle = focus("sidebar-toggle")
        assertTrue("Toggle Sidebar has the focus at first", focused(toggle))
        notice = true
        compose.waitForIdle()
        assertEquals("the same Toggle Sidebar", toggle, compose.onNodeWithTag("sidebar-toggle").fetchSemanticsNode().id)
        assertTrue("Toggle Sidebar keeps the focus as the head folds", focused(toggle))
        assertTrue("the head is folded into the list", folded())
        notice = false
        compose.waitForIdle()
        assertTrue("the head is above the list again", !folded())
        assertEquals("the same Toggle Sidebar as the head unfolds", toggle, compose.onNodeWithTag("sidebar-toggle").fetchSemanticsNode().id)
        assertTrue("Toggle Sidebar keeps the focus as the head unfolds", focused(toggle))
        // Back before the list, in the order a keyboard's focus follows.
        val order = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        assertEquals("the toolbar first, among $order", listOf("Toggle Sidebar"), order.first())
        // Again, so the list's row for the head serves the next fold too.
        notice = true
        compose.waitForIdle()
        assertTrue("the head folds again", folded())
        assertEquals("the same Toggle Sidebar as the head folds again", toggle, compose.onNodeWithTag("sidebar-toggle").fetchSemanticsNode().id)
        assertTrue("Toggle Sidebar keeps the focus as the head folds again", focused(toggle))
        notice = false
        compose.waitForIdle()
        assertTrue("Toggle Sidebar keeps the focus as the head unfolds again", focused(toggle))
    }

    @Test fun thisDeviceKeepsTheScreenReaderFocusAsANoticeComesAndGoes() {
        var notice by mutableStateOf(false)
        show(393.dp, { 280.dp }, notices = { if (notice) Box(Modifier.fillMaxWidth().height(60.dp)) })
        compose.waitForIdle()
        val device = focus("this-device-row")
        notice = true
        compose.waitForIdle()
        assertTrue("This Device keeps the focus as the head folds", focused(device))
        notice = false
        compose.waitForIdle()
        assertEquals("the same This Device", device, compose.onNodeWithTag("this-device-row").fetchSemanticsNode().id)
        assertTrue("This Device keeps the focus as the head unfolds", focused(device))
    }

    @Test fun landscapeWithTheKeyboardUpKeepsTheFilterFieldWholeUnderTwoNotices() {
        // 130 dp, with the upload's notice and then an error's (#240): the two are taller than the room above the Filter
        // band, so the older waits behind the newer, as the pane column's do (#192).
        var error by mutableStateOf(false)
        show(800.dp, { 130.dp }, notices = {
            ToastSurface("Uploading photo.png…", Modifier.testTag("upload-notice").waitsIn(rememberNoticeWait(null, "upload")))
            if (error) ToastSurface("The remote has no file at file:///tmp/a.txt.", Modifier.testTag("error-notice")
                .waitsIn(rememberNoticeWait(null, "error")))
        })
        compose.waitForIdle()
        val field = filterField.fetchSemanticsNode().id
        compose.runOnIdle { view.accessibilityNodeProvider.performAction(field, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        compose.waitForIdle()
        assertTrue("the Filter field has the focus at first", focused(field))
        error = true
        compose.waitForIdle()
        // The field itself, not only its line of text: the real notices are in NoticeSlotTest.
        val fieldBox = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        val shown = listOf("upload-notice", "error-notice").filter { info(it)?.isVisibleToUser == true }
        for (tag in shown) {
            val n = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
            assertTrue("the notice $tag $n is clear of the Filter field $fieldBox", n.bottom <= fieldBox.top + 0.5.dp || n.top >= fieldBox.bottom)
        }
        assertEquals("the newer notice shows, the older waits", listOf("error-notice"), shown)
        assertTheFilterHasItsRow()
        assertTrue("the Filter field keeps the screen reader's focus", focused(field))
        // The error goes, and the upload's notice comes back.
        error = false
        compose.waitForIdle()
        assertTrue("the upload's notice is back", info("upload-notice")?.isVisibleToUser == true)
        assertTrue("the Filter field keeps the focus as it comes back", focused(field))
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
