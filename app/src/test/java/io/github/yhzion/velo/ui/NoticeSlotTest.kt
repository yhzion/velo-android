package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.ToastSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the notices are drawn (#165): the sidebar's slot under its Filter field while it covers the panes, and
 * [PaneNotices] over the pane column, which measures them at their own height and keeps them inside the column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class NoticeSlotTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theSidebarHoldsTheNoticesUnderItsFilterFieldAndInsideItself() {
        compose.setContent {
            VeloTheme {
                Sidebar(DeviceRow("velo-phone", "Running", "Running", TailnetChip("Connected", Status.Ok)), emptyList(),
                    Modifier.fillMaxSize(), actions(), notices = { ToastSurface("Uploading x…") })
            }
        }
        val toast = compose.onNodeWithTag("toast").getBoundsInRoot()
        val filter = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        val sidebar = compose.onNodeWithTag("sidebar").getBoundsInRoot()
        assertTrue("toast $toast under the filter $filter", toast.top >= filter.bottom)
        assertTrue("toast $toast in the sidebar $sidebar", toast.left >= sidebar.left && toast.right <= sidebar.right && toast.bottom <= sidebar.bottom)
        assertTrue("toast $toast has its height", toast.bottom - toast.top >= 48.dp)
    }

    @Test fun aShortPaneAreaHasTheToastAtPaddingWithItsWholeHeight() {
        // In px of the column: first a tall pane area, which shows the toast's own height, then a one-line one.
        var pane by mutableStateOf(Rect(0f, 0f, 1000f, 2000f))
        compose.setContent {
            VeloTheme {
                Box(Modifier.width(380.dp).height(800.dp)) {
                    PaneNotices({ pane }, padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                        ToastSurface("Uploading x…")
                    }
                }
            }
        }
        val tall = compose.onNodeWithTag("toast").getBoundsInRoot()
        pane = Rect(0f, 0f, 1000f, 88f)
        compose.waitForIdle()
        val short = compose.onNodeWithTag("toast").getBoundsInRoot()
        assertEquals(16f, short.top.value, 0.5f)
        assertEquals((tall.bottom - tall.top).value, (short.bottom - short.top).value, 0.5f)
        // Not cut to the pane's 88 px: a toast is a control's height at least.
        assertTrue("toast $short", short.bottom - short.top >= 48.dp)
    }

    @Test fun aStackTallerThanTheSpaceUnderTheShortPaneEndsInsideTheColumn() {
        // A column that ends at the keyboard, 130 dp tall, under a one-line pane: two toasts (48 dp each and a gap of 8)
        // started 16 dp down would end past 114 dp.
        compose.setContent {
            VeloTheme {
                Box(Modifier.width(380.dp).height(200.dp)) {
                    Box(Modifier.testTag("column").width(380.dp).height(130.dp)) {
                        PaneNotices({ Rect(0f, 0f, 1000f, 88f) }, padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                            ToastSurface("Moved")
                            ToastSurface("Uploading x…")
                        }
                    }
                }
            }
        }
        val column = compose.onNodeWithTag("column").getBoundsInRoot()
        val last = compose.onAllNodesWithTag("toast")[1].getBoundsInRoot()
        assertTrue("last toast $last ends 16 dp inside the column $column", last.bottom <= column.bottom - 16.dp + 0.5.dp)
        assertTrue("first toast still in the column", compose.onAllNodesWithTag("toast")[0].getBoundsInRoot().top >= column.top - 0.5.dp)
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
