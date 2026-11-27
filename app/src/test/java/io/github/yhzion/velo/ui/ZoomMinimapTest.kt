package io.github.yhzion.velo.ui

import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.SeenReport
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun leaf(block: String) = LayoutNode(block = block)
private fun row(a: LayoutNode, b: LayoutNode, ratio: Float = 0.5f) = LayoutNode(dir = "row", ratio = ratio, first = a, second = b)
private fun column(a: LayoutNode, b: LayoutNode, ratio: Float = 0.5f) = LayoutNode(dir = "column", ratio = ratio, first = a, second = b)

/** The mockup's tab: claude on the left, build over logcat on the right, build zoomed. */
private val tab = WindowInfo("w", layout = row(leaf("a"), column(leaf("b"), leaf("c")), 0.56f), zoomed = "b")
private val state = UiState(
    status = "connected", session = "s", window = "w", windows = listOf(tab),
    blocks = mapOf(
        "a" to BlockInfo(title = "claude", program = "zsh"),
        "b" to BlockInfo(title = "build", program = "zsh"),
        "c" to BlockInfo(title = "logcat", program = "zsh"),
    ),
)

private fun assertRect(rect: PaneRect, left: Float, top: Float, right: Float, bottom: Float) {
    assertEquals("left of ${rect.block}", left, rect.left, 0.001f)
    assertEquals("top of ${rect.block}", top, rect.top, 0.001f)
    assertEquals("right of ${rect.block}", right, rect.right, 0.001f)
    assertEquals("bottom of ${rect.block}", bottom, rect.bottom, 0.001f)
}

class ZoomMinimapTest {
    @Test
    fun `cells are the layout's rectangles without the zoom, in a unit square`() {
        val cells = zoomCells(tab, rtl = false)
        assertEquals(listOf("a", "b", "c"), cells.map { it.block })
        assertRect(cells[0], 0f, 0f, 0.56f, 1f)
        assertRect(cells[1], 0.56f, 0f, 1f, 0.5f)
        assertRect(cells[2], 0.56f, 0.5f, 1f, 1f)
    }

    @Test
    fun `cells are mirrored in RTL, as the panes are drawn`() {
        val cells = zoomCells(tab, rtl = true).associateBy { it.block }
        assertRect(cells.getValue("a"), 0.44f, 0f, 1f, 1f)
        assertRect(cells.getValue("b"), 0f, 0f, 0.44f, 0.5f)
    }

    @Test
    fun `a tab without a zoom on a tiled pane has no minimap`() {
        assertNull(zoomMap(tab.copy(zoomed = null), false) { null })
        val floating = tab.copy(zoomed = "f", floating = listOf(FloatingLayer("f")))
        assertNull(zoomMap(floating, false) { null })
    }

    @Test
    fun `the zoomed pane is numbered in layout order, as the pane menu numbers it`() {
        val map = zoomMap(tab, false) { null }!!
        assertEquals("b", map.zoomed)
        assertEquals(2, map.number)
        assertEquals(3, map.count)
        assertEquals("Full Screen 2/3", zoomLabel(map))
        assertEquals("Full screen, pane 2 of 3. Show panes", zoomMinimapDescription(map))
        // A floating pane is no cell, but the tiled ones keep their numbers: they come first in the tab.
        val withFloating = zoomMap(tab.copy(floating = listOf(FloatingLayer("f"))), false) { null }!!
        assertEquals(listOf("a", "b", "c"), withFloating.cells.map { it.block })
        assertEquals(paneLabel(tabBlocks(tab), "b"), "Pane ${withFloating.number}")
    }

    @Test
    fun `next and previous go round the layout order`() {
        val map = zoomMap(tab, false) { null }!!
        assertEquals("c", map.step(1))
        assertEquals("a", map.step(-1))
        val last = map.copy(zoomed = "c")
        assertEquals("a", last.step(1))
        assertEquals("b", last.step(-1))
        assertEquals("c", map.copy(zoomed = "a").step(-1))
    }

    @Test
    fun `a swipe toward the line's start brings the next pane, mirrored in RTL`() {
        val map = zoomMap(tab, false) { null }!!
        assertEquals("c", map.swiped(-30f, rtl = false))
        assertEquals("a", map.swiped(30f, rtl = false))
        assertEquals("a", map.swiped(-30f, rtl = true))
        assertEquals("c", map.swiped(30f, rtl = true))
    }

    @Test
    fun `cells are named after their own pane and marked with its unseen report, the zoomed one never`() {
        val map = zoomMap(tab, false) { block -> TabIndicator.Done.takeIf { block != "a" } }!!
        assertEquals("w", map.tab)
        assertEquals(listOf("claude", "build", "logcat"), map.cells.map { zoomPaneName(state, tab, it.block) })
        assertEquals(listOf(null, null, TabIndicator.Done), map.cells.map { it.alert })
        assertEquals("Pane 3 of 3, logcat, Completed", zoomCellDescription(map, 2, map.cells[2], "logcat"))
        assertEquals("Pane 1 of 3, claude", zoomCellDescription(map, 0, map.cells[0], "claude"))
        assertEquals("Pane 3, Completed", zoomMinimapState(map))
        assertNull(zoomMinimapState(zoomMap(tab, false) { null }!!))
    }

    @Test
    fun `a hidden pane's report not yet seen marks its cell`() {
        val reads = ActivityReadState()
        val time = "2026-11-27T10:00:00Z"
        reads.update(PaneAddress("s", "w", "c"), mapOf("turn" to SeenReport("done", time)))
        reads.update(PaneAddress("s", "w", "a"), mapOf("turn" to SeenReport("blocked", time, kind = "question")))
        // The zoomed pane is on screen: its report is read.
        reads.show(Any(), mapOf(PaneAddress("s", "w", "b") to state.blocks["b"]))
        reads.update(PaneAddress("s", "w", "b"), mapOf("turn" to SeenReport("done", time)))
        val map = zoomMap(tab, false) { block -> reads.activity(tab.paneRow(state, "s", block), true).badge }!!
        assertEquals(listOf(TabIndicator.Question, null, TabIndicator.Done), map.cells.map { it.alert })
    }

    @Test
    fun `a pane's row is its own, without the tab's label`() {
        val labelled = tab.copy(label = "work")
        val pane = labelled.paneRow(state, "s", "c")
        assertEquals("logcat", pane.name)
        assertEquals("c", pane.block)
        assertEquals(listOf("c"), pane.terminalBlocks)
    }

    @Test
    fun `the picker grows until its smallest cell is a finger's target`() {
        val cells = zoomCells(tab, false)
        // The board's 194 by 128 holds the mockup's cells: the shortest is 64 high, the narrowest 85 wide.
        assertEquals(194f to 128f, zoomPickerSize(cells, 194f, 128f, 48f))
        // Four rows of a quarter each need 192 high.
        val four = WindowInfo("w", layout = column(leaf("a"), column(leaf("b"), column(leaf("c"), leaf("d"), 0.5f), 1f / 3), 0.25f))
        val (w, h) = zoomPickerSize(zoomCells(four, false), 194f, 128f, 48f)
        assertEquals(194f, w, 0.01f)
        assertEquals(192f, h, 0.01f)
    }
}
