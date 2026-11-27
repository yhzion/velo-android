package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
import androidx.compose.ui.unit.height
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.fidelity.Speech
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
import org.robolectric.shadows.ShadowDialog

/** The caption of a zoomed tab shows its minimap (#141); the picker and a swipe zoom another pane. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class ZoomMinimapUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val layout = LayoutNode(dir = "row", ratio = 0.56f, first = LayoutNode(block = "a"),
        second = LayoutNode(dir = "column", first = LayoutNode(block = "b"), second = LayoutNode(block = "c")))
    private var zoomed by mutableStateOf<String?>("b")
    private var floating by mutableStateOf(emptyList<FloatingLayer>())
    private var connected by mutableStateOf(true)
    private var tab by mutableStateOf("w")
    private var compact by mutableStateOf(false)
    private var size by mutableStateOf(UiSize.Medium)
    private val zoomedTo = mutableListOf<String>()
    private lateinit var view: View

    // [layered]: overlays open over the caption, for the test to see them; else in a window of their own, as in the app.
    private fun show(layout: LayoutNode = this.layout, rtl: Boolean = false, layered: Boolean = true) = compose.setContent {
        val window = WindowInfo(tab, layout = layout, zoomed = zoomed, floating = floating)
        val state = UiState(
            status = "connected", session = "s", window = tab, windows = listOf(window),
            sessions = listOf(SessionInfo("s", tabs = listOf(TabInfo(tab, label = "velo", program = "zsh", panes = 3)))),
            blocks = mapOf("a" to BlockInfo(title = "claude"), "b" to BlockInfo(title = "build"), "c" to BlockInfo(title = "logcat"),
                "f" to BlockInfo(title = "htop")),
        )
        val row = sessionGroups(state, false, emptySet(), false, "").single().rows.single()
        val map = zoomMap(window, rtl) { block -> TabIndicator.Done.takeIf { block == "c" } }
        val layer = remember { OverlayLayer() }
        view = LocalView.current
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            VeloTheme(size) {
                val caption = @Composable {
                    PaneCaption(row, connected, ActivityReadState(), host = "studio", compact = compact, zoom = map,
                        paneName = { zoomPaneName(state, window, it) }, onZoom = { zoomedTo += it })
                }
                if (layered) OverlayLayerBox(layer, Modifier.fillMaxSize()) { caption() } else caption()
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

    @Test fun aFloatingPaneMadeFullScreenSaysSo() {
        // The menu made the floating pane full screen (#158): the caption says so, and the minimap leads back to the layout.
        floating = listOf(FloatingLayer("f", x = 0.1f, y = 0.1f, w = 0.4f, h = 0.4f))
        zoomed = "f"
        show()
        compose.onNodeWithTag("caption-zoom-label", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("caption-minimap").assertIsDisplayed()
            .assertContentDescriptionEquals("Full screen, pane 4 of 4. Show panes")
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker-cell-f").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertContentDescriptionEquals("Pane 4 of 4, htop")
        compose.onNodeWithTag("minimap-picker-cell-a").performClick()
        assertEquals(listOf("a"), zoomedTo)
    }

    @Test fun aFloatingCellIsTouchedOverTheTiledOne() {
        // The floating pane lies over the layout's first pane: a tap on it picks the floating pane, as on the panes.
        floating = listOf(FloatingLayer("f", x = 0.1f, y = 0.1f, w = 0.4f, h = 0.4f))
        show()
        compose.onNodeWithTag("caption-minimap").performClick()
        val f = compose.onNodeWithTag("minimap-picker-cell-f").getBoundsInRoot()
        val a = compose.onNodeWithTag("minimap-picker-cell-a").getBoundsInRoot()
        assertTrue("f over a", f.left > a.left && f.top > a.top && f.right < a.right && f.bottom < a.bottom)
        // The floating cell leaves the tiled one a finger's target: it needs no place in the row under the map.
        compose.onNodeWithTag("minimap-picker-covered").assertDoesNotExist()
        compose.onNodeWithTag("minimap-picker-cell-f").performClick()
        assertEquals(listOf("f"), zoomedTo)
    }

    @Test fun aTiledPaneUnderAFloatingOneIsSaidAndPickedInTheRowUnderTheMap() {
        // A floating pane as high as the pane area over claude (#171) covers its cell: a finger could not pick it.
        floating = listOf(FloatingLayer("f", x = 0f, y = 0f, w = 0.6f, h = 1f))
        show()
        compose.onNodeWithTag("caption-minimap").performClick()
        // What a screen reader gets: every pane once, the covered one from the row under the map and not from the map.
        val said = compose.runOnIdle { Speech.focusableNodes(view) }.map { it.second.joinToString(" | ") }
        for (n in 1..4) assertEquals("$said", 1, said.count { "Pane $n of 4," in it })
        assertTrue("$said", said.none { Speech.saysTwice(listOf(it)) })
        compose.onNodeWithTag("minimap-picker-covered-a").assertIsDisplayed()
            .assertContentDescriptionEquals("Pane 1 of 4, claude")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("minimap-picker-covered-b").assertDoesNotExist()
        compose.onNodeWithTag("minimap-picker-covered-c").assertDoesNotExist()
        compose.onNodeWithTag("minimap-picker-covered-a").performClick()
        assertEquals(listOf("a"), zoomedTo)
        compose.onNodeWithTag("minimap-picker").assertDoesNotExist()
    }

    @Test fun theStripLeftOfACoveredCellStillTakesATap() {
        floating = listOf(FloatingLayer("f", x = 0.2f, y = 0f, w = 0.4f, h = 1f))
        show()
        compose.onNodeWithTag("caption-minimap").performClick()
        // claude is left a strip narrower than a finger: it is in the row, and its cell on the map is no button to a screen reader.
        compose.onNodeWithTag("minimap-picker-covered-a").assertExists()
        compose.onNodeWithTag("minimap-picker-cell-a").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        // The strip on the floating cell's left is claude's: a tap there picks it, as it would on the map without the row.
        compose.onNodeWithTag("minimap-picker-cell-a").performTouchInput { click(Offset(8.dp.toPx(), height / 2f)) }
        assertEquals(listOf("a"), zoomedTo)
    }

    @Test fun theRowIsNoWiderThanTheMap() {
        // Six panes, three by two, under a floating pane as large as the pane area: six capsules are wider than the map.
        val column = { a: String, b: String -> LayoutNode(dir = "column", first = LayoutNode(block = a), second = LayoutNode(block = b)) }
        val six = LayoutNode(dir = "row", ratio = 1f / 3, first = column("p0", "p1"),
            second = LayoutNode(dir = "row", first = column("p2", "p3"), second = column("p4", "p5")))
        floating = listOf(FloatingLayer("f", x = 0f, y = 0f, w = 1f, h = 1f))
        zoomed = "f"
        show(six)
        compose.onNodeWithTag("caption-minimap").performClick()
        for (i in 0..5) compose.onNodeWithTag("minimap-picker-covered-p$i").assertExists()
        // The popover keeps its end at the minimap's (#144): the row scrolls inside the map's width.
        val row = compose.onNodeWithTag("minimap-picker-covered").getBoundsInRoot()
        val map = compose.onNodeWithTag("minimap-picker-map").getBoundsInRoot()
        assertTrue("row ${row.left}..${row.right} in map ${map.left}..${map.right}", row.right <= map.right + 0.5.dp)
        compose.onNodeWithTag("minimap-picker-covered-p5").assertIsNotDisplayed()
    }

    @Test fun theRowOpensOnACoveredZoomedPane() {
        // Nine side by side under a floating pane as large as the pane area, the last full screen: its capsule shows.
        floating = listOf(FloatingLayer("f", x = 0f, y = 0f, w = 1f, h = 1f))
        zoomed = "p8"
        show(line(9))
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker-covered-p8").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithTag("minimap-picker-covered-p0").assertIsNotDisplayed()
    }

    @Test fun theRowOpensOnACoveredZoomedPaneInRtl() {
        floating = listOf(FloatingLayer("f", x = 0f, y = 0f, w = 1f, h = 1f))
        zoomed = "p8"
        show(line(9), rtl = true)
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker-covered-p8").assertIsDisplayed()
        compose.onNodeWithTag("minimap-picker-covered-p0").assertIsNotDisplayed()
    }

    @Test fun aCoveredZoomedPaneIsSelectedInTheRow() {
        floating = listOf(FloatingLayer("f", x = 0.5f, y = 0f, w = 0.5f, h = 1f))
        show()
        compose.onNodeWithTag("caption-minimap").performClick()
        // build and logcat lie under the floating pane: build, the full screen one, is selected, and a tap only closes.
        compose.onNodeWithTag("minimap-picker-covered-c").assertContentDescriptionEquals("Pane 3 of 4, logcat, Completed")
        compose.onNodeWithTag("minimap-picker-covered-b").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .performClick()
        assertEquals(emptyList<String>(), zoomedTo)
        compose.onNodeWithTag("minimap-picker").assertDoesNotExist()
    }

    @Test fun zoomingLeavesTheCaptionAsHighAsItWas() {
        // The pane above the caption keeps its rows: neither the minimap nor its label makes the caption higher.
        show()
        for (style in listOf(false, true)) for (ui in UiSize.entries) {
            compact = style
            size = ui
            zoomed = null
            compose.waitForIdle()
            val split = compose.onNodeWithTag("pane-caption").getBoundsInRoot().height
            zoomed = "b"
            compose.waitForIdle()
            compose.onNodeWithTag("caption-minimap").assertIsDisplayed()
            assertEquals("$ui, compact $style", split, compose.onNodeWithTag("pane-caption").getBoundsInRoot().height)
        }
    }

    @Test fun thePickerLeavesTheKeyboardToTheTerminal() {
        // As a pane menu: the picker's window edits no text, so the keyboard stays up and the pane keeps its size.
        show(layered = false)
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker").assertIsDisplayed()
        val flags = ShadowDialog.getLatestDialog().window!!.attributes.flags
        assertTrue("FLAG_ALT_FOCUSABLE_IM", flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
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

    /** [n] panes p0, p1, … of one width, side by side ([dir] row) or one over another (column). */
    private fun line(n: Int, dir: String = "row", from: Int = 0): LayoutNode =
        if (n == 1) LayoutNode(block = "p$from")
        else LayoutNode(dir = dir, ratio = 1f / n, first = LayoutNode(block = "p$from"), second = line(n - 1, dir, from + 1))

    /**
     * Scrolls the picker's map by [dx], [dy] px at a time, with the scroll action a screen reader uses, until
     * [block]'s cell shows. The map scrolls sideways, and up and down in a node of its own.
     */
    private fun scrollTo(block: String, dx: Float, dy: Float = 0f) {
        val tag = if (dx != 0f) "minimap-picker-map" else "minimap-picker-rows"
        repeat(20) {
            if (compose.onNodeWithTag("minimap-picker-cell-$block").fetchSemanticsNode().boundsInRoot.width > 0f) return
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.ScrollBy) { it(dx, dy) }
        }
    }

    @Test fun nineSideBySideScrollAndStayFingersTargets() {
        // Nine 48 dp cells and the room around them are wider than the popover (#144): the map scrolls in it.
        zoomed = "p8"
        show(line(9))
        compose.onNodeWithTag("caption-minimap").performClick()
        val root = compose.onRoot().getBoundsInRoot()
        val picker = compose.onNodeWithTag("minimap-picker").getBoundsInRoot()
        assertTrue("the picker ${picker.left}..${picker.right} on the screen", picker.left >= root.left && picker.right <= root.right)
        // It opens on the zoomed pane, the last; the first is off the map until it scrolls there.
        compose.onNodeWithTag("minimap-picker-cell-p8").assertIsDisplayed()
        compose.onNodeWithTag("minimap-picker-cell-p0").assertIsNotDisplayed()
        for (i in 8 downTo 0) {
            scrollTo("p$i", -100f)
            compose.onNodeWithTag("minimap-picker-cell-p$i").assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        scrollTo("p0", -100f)
        compose.onNodeWithTag("minimap-picker-cell-p0").performClick()
        assertEquals(listOf("p0"), zoomedTo)
    }

    @Config(qualifiers = "w900dp-h700dp-420dpi")
    @Test fun aScrollingPickerEndsAtTheMinimapOnAWideScreen() {
        // The map is wider than the popover, which scrolls it: the popover's end stays at the minimap's, as with fewer panes.
        zoomed = "p8"
        show(line(9))
        compose.onNodeWithTag("caption-minimap").performClick()
        val mini = compose.onNodeWithTag("caption-minimap").getBoundsInRoot()
        val picker = compose.onNodeWithTag("minimap-picker").getBoundsInRoot()
        assertEquals(mini.right.value, picker.right.value, 1f)
    }

    @Test fun twelveSideBySideScrollInRtl() {
        // The layout's first pane is on the right in RTL: the map opens on the zoomed one, the first, at its start.
        zoomed = "p0"
        show(line(12), rtl = true)
        compose.onNodeWithTag("caption-minimap").performClick()
        val picker = compose.onNodeWithTag("minimap-picker").getBoundsInRoot()
        val first = compose.onNodeWithTag("minimap-picker-cell-p0").assertIsDisplayed().getBoundsInRoot()
        assertTrue("p0 at the picker's right", first.right <= picker.right && first.left > (picker.left + picker.right) / 2)
        compose.onNodeWithTag("minimap-picker-cell-p11").assertIsNotDisplayed()
        scrollTo("p11", 100f)
        compose.onNodeWithTag("minimap-picker-cell-p11").assertIsDisplayed().performClick()
        assertEquals(listOf("p11"), zoomedTo)
    }

    @Test fun aRtlMapOpensOnAZoomedPaneAtItsEnd() {
        // The last pane is on the left in RTL, past what shows first: the map opens there.
        zoomed = "p11"
        show(line(12), rtl = true)
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker-cell-p11").assertIsDisplayed()
        compose.onNodeWithTag("minimap-picker-cell-p0").assertIsNotDisplayed()
    }

    @Config(qualifiers = "w900dp-h700dp-420dpi")
    @Test fun aScrollingPickerStartsAtTheMinimapInRtl() {
        zoomed = "p8"
        show(line(9), rtl = true)
        compose.onNodeWithTag("caption-minimap").performClick()
        val mini = compose.onNodeWithTag("caption-minimap").getBoundsInRoot()
        val picker = compose.onNodeWithTag("minimap-picker").getBoundsInRoot()
        assertEquals(mini.left.value, picker.left.value, 1f)
    }

    @Config(qualifiers = "w894dp-h393dp-420dpi")
    @Test fun aStackedMapOpensOnTheZoomedPane() {
        zoomed = "p8"
        show(line(9, "column"))
        compose.onNodeWithTag("caption-minimap").performClick()
        compose.onNodeWithTag("minimap-picker-cell-p8").assertIsDisplayed()
        compose.onNodeWithTag("minimap-picker-cell-p0").assertIsNotDisplayed()
    }

    @Config(qualifiers = "w894dp-h393dp-420dpi")
    @Test fun nineStackedScrollOnALowScreen() {
        // Nine rows of 48 dp are higher than a phone held sideways: the map scrolls up and down too.
        zoomed = "p0"
        show(line(9, "column"))
        compose.onNodeWithTag("caption-minimap").performClick()
        val root = compose.onRoot().getBoundsInRoot()
        val picker = compose.onNodeWithTag("minimap-picker").getBoundsInRoot()
        assertTrue("the picker ${picker.top}..${picker.bottom} on the screen", picker.top >= root.top && picker.bottom <= root.bottom)
        compose.onNodeWithTag("minimap-picker-cell-p8").assertIsNotDisplayed()
        scrollTo("p8", 0f, 100f)
        compose.onNodeWithTag("minimap-picker-cell-p8").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("p8"), zoomedTo)
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
