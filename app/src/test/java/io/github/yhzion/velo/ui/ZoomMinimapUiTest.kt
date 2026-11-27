package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.OverlayLayer
import io.github.yhzion.velo.ui.theme.OverlayLayerBox
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The caption of a zoomed tab shows its minimap (#141); the picker and a swipe zoom another pane. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class ZoomMinimapUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val layout = LayoutNode(dir = "row", ratio = 0.56f, first = LayoutNode(block = "a"),
        second = LayoutNode(dir = "column", first = LayoutNode(block = "b"), second = LayoutNode(block = "c")))
    private var zoomed by mutableStateOf<String?>("b")
    private var connected by mutableStateOf(true)
    private var tab by mutableStateOf("w")
    private val zoomedTo = mutableListOf<String>()

    private fun show(layout: LayoutNode = this.layout, rtl: Boolean = false) = compose.setContent {
        val window = WindowInfo(tab, layout = layout, zoomed = zoomed)
        val state = UiState(
            status = "connected", session = "s", window = tab, windows = listOf(window),
            sessions = listOf(SessionInfo("s", tabs = listOf(TabInfo(tab, label = "velo", program = "zsh", panes = 3)))),
            blocks = mapOf("a" to BlockInfo(title = "claude"), "b" to BlockInfo(title = "build"), "c" to BlockInfo(title = "logcat")),
        )
        val row = sessionGroups(state, false, emptySet(), false, "").single().rows.single()
        val map = zoomMap(window, rtl) { block -> TabIndicator.Done.takeIf { block == "c" } }
        val layer = remember { OverlayLayer() }
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            VeloTheme(UiSize.Medium) {
                OverlayLayerBox(layer, Modifier.fillMaxSize()) {
                    PaneCaption(row, connected, ActivityReadState(), host = "studio", zoom = map,
                        paneName = { zoomPaneName(state, window, it) }, onZoom = { zoomedTo += it })
                }
            }
        }
    }

    @Test fun aZoomedTabShowsTheMinimapAndItsLabel() {
        show()
        compose.onNodeWithTag("caption-minimap").assertIsDisplayed()
            .assertContentDescriptionEquals("Full screen, pane 2 of 3. Show panes")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Pane 3, Completed"))
        // The label's words are the minimap's to say ([zoomLabel]): a screen reader does not hear them twice.
        compose.onNodeWithTag("caption-zoom-label", useUnmergedTree = true).assertIsDisplayed()
        zoomed = null
        compose.waitForIdle()
        compose.onNodeWithTag("caption-minimap").assertDoesNotExist()
        compose.onNodeWithTag("caption-zoom-label", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aPickerTapZoomsThatPane() {
        show()
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker").assertIsDisplayed()
        compose.onNodeWithTag("minimap-picker-cell-c").assertIsDisplayed()
            .assertContentDescriptionEquals("Pane 3 of 3, logcat, Completed")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("minimap-picker-cell-c").performClick()
        assertEquals(listOf("c"), zoomedTo)
        compose.onNodeWithTag("minimap-picker").assertDoesNotExist()
    }

    @Test fun thePickersZoomedCellOnlyClosesIt() {
        show()
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker-cell-b").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .performClick()
        assertEquals(emptyList<String>(), zoomedTo)
        compose.onNodeWithTag("minimap-picker").assertDoesNotExist()
    }

    @Test fun aSwipeOnTheMinimapZoomsTheNextOrPreviousPane() {
        show()
        compose.onNodeWithTag("caption-minimap").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("caption-minimap").performTouchInput { swipeRight() }
        assertEquals(listOf("c", "a"), zoomedTo)
        // A swipe is no tap: the picker stays closed.
        compose.onNodeWithTag("minimap-picker").assertDoesNotExist()
    }

    @Test fun thePickerKeepsTheLayoutsPlacesInRtl() {
        show(rtl = true)
        compose.onNodeWithTag("caption-minimap").performClick()
        // The layout's first pane is on the right in RTL, as the panes are drawn, and the minimap mirrors them once.
        val a = compose.onNodeWithTag("minimap-picker-cell-a").getBoundsInRoot()
        val b = compose.onNodeWithTag("minimap-picker-cell-b").getBoundsInRoot()
        assertTrue("a ${a.left} right of b ${b.left}", a.left > b.left)
        val picker = compose.onNodeWithTag("minimap-picker").getBoundsInRoot()
        assertTrue("a inside the picker", a.right <= picker.right && b.left >= picker.left)
    }

    @Test fun everyPickerCellIsAFingersTarget() {
        // Four side by side: a quarter of the board's 194 is less than 48 and the room around each cell.
        val four = LayoutNode(dir = "row", ratio = 0.25f, first = LayoutNode(block = "a"), second = LayoutNode(dir = "row",
            ratio = 1f / 3, first = LayoutNode(block = "b"), second = LayoutNode(dir = "row", first = LayoutNode(block = "c"),
                second = LayoutNode(block = "d"))))
        show(four)
        compose.onNodeWithTag("caption-minimap").performClick()
        for (block in listOf("a", "b", "c", "d")) {
            compose.onNodeWithTag("minimap-picker-cell-$block").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun withoutTheServerTheMinimapSendsNothing() {
        connected = false
        show()
        compose.onNodeWithTag("caption-minimap").assertIsDisplayed().assertIsNotEnabled()
            .assertContentDescriptionEquals("Full screen, pane 2 of 3. Show panes")
        compose.onNodeWithTag("caption-minimap").performTouchInput { click() }
        compose.onNodeWithTag("minimap-picker").assertDoesNotExist()
        compose.onNodeWithTag("caption-minimap").performTouchInput { swipeLeft() }
        assertEquals(emptyList<String>(), zoomedTo)
        // An open picker closes when the connection goes.
        connected = true
        compose.waitForIdle()
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker").assertIsDisplayed()
        connected = false
        compose.waitForIdle()
        compose.onNodeWithTag("minimap-picker").assertDoesNotExist()
    }

    @Test fun anotherTabClosesThePicker() {
        show()
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker").assertIsDisplayed()
        // A zoomed tab of the same layout: the caption keeps its minimap, but the picker was for the other tab.
        tab = "w2"
        compose.waitForIdle()
        compose.onNodeWithTag("minimap-picker").assertDoesNotExist()
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker").assertIsDisplayed()
    }
}
