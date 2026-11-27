package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
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
import org.robolectric.annotation.GraphicsMode

/**
 * Where the notices are drawn (#165): the sidebar's slot under its Filter field while it covers the panes, and
 * [PaneNotices] over the pane column, which measures them at their own height and keeps them inside the column.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                    PaneNotices({ pane }, keyBar = { null }, padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
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
                        PaneNotices({ Rect(0f, 0f, 1000f, 88f) }, keyBar = { null }, padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
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

    // #169: the Velvet's pane column in landscape with the keyboard up, in dp: 786 wide and 137 tall, the pane one
    // line (32 dp) at its top and the key bar from 83 dp. A notice is 48 dp, so two stacked would run into it.
    private fun shortColumn(vararg notices: @Composable () -> Unit) {
        compose.setContent {
            VeloTheme {
                val px = LocalDensity.current.density
                Box(Modifier.testTag("column").width(786.dp).height(137.dp)) {
                    PaneNotices({ Rect(0f, 0f, 786 * px, 32 * px) }, keyBar = { Rect(0f, 83 * px, 786 * px, 120 * px) },
                        padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                        notices.forEach { it() }
                    }
                }
            }
        }
    }

    private fun assertOneRowAboveTheKeyBar(nodes: List<DpRect>) {
        val keyBarTop = 83.dp
        nodes.forEach { n ->
            assertTrue("notice $n ends above the key bar ($keyBarTop)", n.bottom <= keyBarTop + 0.5.dp)
            assertTrue("notice $n starts in the column", n.top >= (-0.5).dp)
            assertTrue("notice $n has a control's height", n.bottom - n.top >= 48.dp - 0.5.dp)
            assertTrue("notice $n inside the column's padding", n.left >= 16.dp - 0.5.dp && n.right <= 786.dp - 16.dp + 0.5.dp)
        }
        nodes.zipWithNext { a, b -> assertTrue("$a then $b, side by side in order", b.left >= a.right + 8.dp - 0.5.dp) }
    }

    @Config(qualifiers = "w894dp-h393dp-land-420dpi")
    @Test fun twoNoticesUnderAOneLinePaneLieSideBySideAboveTheKeyBar() {
        shortColumn({ ToastSurface("Uploading 17957913871976106988482846832927.png…") }, { ToastSurface("Another send is still in progress.") })
        val toasts = compose.onAllNodesWithTag("toast").fetchSemanticsNodes().indices.map { compose.onAllNodesWithTag("toast")[it].getBoundsInRoot() }
        assertEquals(2, toasts.size)
        assertOneRowAboveTheKeyBar(toasts)
        // Each shows its whole line: the error's text is one line (about 40 dp with its padding), not cut or wrapped.
        compose.onAllNodesWithTag("toast-message")[1].getBoundsInRoot().let { assertTrue("one line $it", it.bottom - it.top < 48.dp) }
    }

    @Config(qualifiers = "w894dp-h393dp-land-420dpi")
    @Test fun threeNoticesUnderAOneLinePaneLieSideBySideAboveTheKeyBar() {
        shortColumn({ ToastSurface("Moved Pane 1", actionLabel = "Undo", onAction = {}) },
            { ToastSurface("Uploading 17957915015754169069198214825020.png…") },
            { Snackbar(Modifier.testTag("snackbar")) { Text("Another send is still in progress.") } })
        val nodes = listOf(compose.onAllNodesWithTag("toast")[0], compose.onAllNodesWithTag("toast")[1], compose.onNodeWithTag("snackbar"))
            .map { it.getBoundsInRoot() }
        assertOneRowAboveTheKeyBar(nodes)
    }

    @Config(qualifiers = "w894dp-h393dp-land-420dpi")
    @Test fun oneNoticeUnderAOneLinePaneKeepsItsPlace() {
        // #165's place: from the pane's top down, padding below the column's top, over the caption.
        shortColumn({ ToastSurface("Uploading x…") })
        val toast = compose.onNodeWithTag("toast").getBoundsInRoot()
        assertEquals(16f, toast.top.value, 0.5f)
        assertEquals(786f, (toast.left + toast.right).value, 1f)
    }

    /** Two toasts (104 dp with the gap) over a short pane under a search bar (from 20 dp to 52), the key bar from [keyBarTop] dp. */
    private fun underTheSearchBar(keyBarTop: Int): Pair<DpRect, DpRect> {
        compose.setContent {
            VeloTheme {
                val px = LocalDensity.current.density
                Box(Modifier.width(380.dp).height(200.dp)) {
                    PaneNotices({ Rect(0f, 20 * px, 380 * px, 52 * px) }, keyBar = { Rect(0f, keyBarTop * px, 380 * px, (keyBarTop + 40) * px) },
                        padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                        ToastSurface("Moved")
                        ToastSurface("Uploading x…")
                    }
                }
            }
        }
        return compose.onAllNodesWithTag("toast")[0].getBoundsInRoot() to compose.onAllNodesWithTag("toast")[1].getBoundsInRoot()
    }

    @Test fun twoNoticesThatFitAboveTheKeyBarFromThePanesTopStayStackedAboveIt() {
        val (first, last) = underTheSearchBar(keyBarTop = 150)
        assertTrue("stacked: $first over $last", last.top >= first.bottom)
        assertTrue("last toast $last ends 16 dp above the key bar", last.bottom <= 150.dp - 16.dp + 0.5.dp)
        assertTrue("first toast $first under the search bar", first.top >= 20.dp - 0.5.dp)
    }

    @Test fun twoNoticesThatWouldStackOverTheSearchBarGoSideBySide() {
        // From the column's top they would fit above the key bar, but only over the search field (#169 review, N3).
        val (first, last) = underTheSearchBar(keyBarTop = 130)
        listOf(first, last).forEach {
            assertTrue("toast $it under the search bar", it.top >= 20.dp - 0.5.dp)
            assertTrue("toast $it ends above the key bar", it.bottom <= 130.dp + 0.5.dp)
        }
        assertTrue("side by side: $first then $last", last.left >= first.right)
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
