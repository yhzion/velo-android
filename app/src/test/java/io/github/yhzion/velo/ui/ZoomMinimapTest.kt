package io.github.yhzion.velo.ui

import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.SeenReport
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `the zoomed cell is drawn last, over a floating cell too`() {
        // A floating pane over the zoomed one (#183): the zoomed cell's fill goes over it, the others keep their order.
        val window = tab.copy(floating = listOf(FloatingLayer("f", x = 0.5f, y = 0f, w = 0.5f, h = 1f)))
        val map = zoomMap(window, false) { null }!!
        assertEquals(listOf("a", "b", "c", "f"), map.cells.map { it.block })
        assertEquals(listOf("a", "c", "f", "b"), map.drawOrder.map { it.block })
        // A floating pane made full screen is last already.
        assertEquals(listOf("a", "b", "c", "f"), zoomMap(window.copy(zoomed = "f"), false) { null }!!.drawOrder.map { it.block })
    }

    @Test
    fun `a tab without a zoom on one of its panes has no minimap`() {
        assertNull(zoomMap(tab.copy(zoomed = null), false) { null })
        assertNull("a pane the tab does not have", zoomMap(tab.copy(zoomed = "x"), false) { null })
    }

    @Test
    fun `floating panes are cells over the layout, in their own boxes`() {
        val floating = tab.copy(floating = listOf(FloatingLayer("f", x = 0.1f, y = 0.2f, w = 0.3f, h = 0.4f)))
        val cells = zoomCells(floating, rtl = false)
        assertEquals(listOf("a", "b", "c", "f"), cells.map { it.block })
        assertRect(cells[3], 0.1f, 0.2f, 0.4f, 0.6f)
        // Mirrored in RTL, as its offset is ([splitPlace]).
        assertRect(zoomCells(floating, rtl = true).last(), 0.6f, 0.2f, 0.9f, 0.6f)
        // A box reaching past the pane area stays inside the minimap.
        val out = tab.copy(floating = listOf(FloatingLayer("f", x = 0.8f, y = -0.1f, w = 0.5f, h = 0.5f)))
        assertRect(zoomCells(out, rtl = false).last(), 0.8f, 0f, 1f, 0.4f)
        val map = zoomMap(floating, false) { null }!!
        assertEquals(listOf(false, false, false, true), map.cells.map { it.floating })
    }

    @Test
    fun `a floating pane made full screen has the minimap and its label`() {
        val floating = tab.copy(zoomed = "f", floating = listOf(FloatingLayer("f")))
        val map = zoomMap(floating, false) { null }!!
        assertEquals("f", map.zoomed)
        assertEquals("Full Screen 4/4", zoomLabel(map))
        assertEquals(paneLabel(tabBlocks(floating), "f"), "Pane ${map.number}")
        // Next goes round to the layout's first pane, previous to its last.
        assertEquals("a", map.step(1))
        assertEquals("c", map.step(-1))
        // A tab whose panes all float has no layout, and still a minimap.
        val loose = WindowInfo("w", zoomed = "g", floating = listOf(FloatingLayer("f"), FloatingLayer("g", x = 0.4f)))
        val all = zoomMap(loose, false) { null }!!
        assertEquals(listOf("f", "g"), all.cells.map { it.block })
        assertEquals("Full Screen 2/2", zoomLabel(all))
    }

    @Test
    fun `the zoomed pane is numbered in layout order, as the pane menu numbers it`() {
        val map = zoomMap(tab, false) { null }!!
        assertEquals("b", map.zoomed)
        assertEquals(2, map.number)
        assertEquals(3, map.count)
        assertEquals("Full Screen 2/3", zoomLabel(map))
        assertEquals("Full screen, pane 2 of 3. Show panes", zoomMinimapDescription(map))
        // A floating pane comes after the tiled ones, as in the pane menu: they keep their numbers.
        val withFloating = zoomMap(tab.copy(floating = listOf(FloatingLayer("f"))), false) { null }!!
        assertEquals(listOf("a", "b", "c", "f"), withFloating.cells.map { it.block })
        assertEquals(paneLabel(tabBlocks(tab), "b"), "Pane ${withFloating.number}")
        assertEquals("Full Screen 2/4", zoomLabel(withFloating))
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
    fun `a floating box neither grows the picker nor goes below a finger's target`() {
        // A sliver past the pane area: 0.03 wide once kept inside the map.
        val sliver = tab.copy(floating = listOf(FloatingLayer("f", x = 0.97f, y = 0.9f, w = 0.5f, h = 0.5f)))
        val map = zoomMap(sliver, false) { null }!!
        assertEquals(194f to 128f, zoomPickerSize(map, 194f, 128f, 52f))
        val box = zoomPickerBox(map.cells.last(), 194f, 128f, 52f)
        assertEquals(PickerBox(142f, 76f, 52f, 52f), box)
        // A tiled cell is its rectangle scaled.
        assertEquals(PickerBox(0f, 0f, 194f * 0.56f, 128f), zoomPickerBox(map.cells[0], 194f, 128f, 52f))
        // Grown about its middle, so its RTL box is still the mirror of its LTR one.
        val small = tab.copy(floating = listOf(FloatingLayer("f", x = 0.25f, y = 0.25f, w = 0.125f, h = 0.5f)))
        val ltr = zoomPickerBox(zoomMap(small, false) { null }!!.cells.last(), 194f, 128f, 52f)
        val rtl = zoomPickerBox(zoomMap(small, true) { null }!!.cells.last(), 194f, 128f, 52f)
        assertEquals(52f, ltr.width, 0.001f)
        assertEquals(194f - ltr.left - ltr.width, rtl.left, 0.001f)
        // A floating box larger than the least keeps its size and place.
        val wide = zoomMap(tab.copy(floating = listOf(FloatingLayer("f", x = 0.1f, y = 0.25f, w = 0.5f, h = 0.5f))), false) { null }!!
        val kept = zoomPickerBox(wide.cells.last(), 194f, 128f, 52f)
        assertEquals(19.4f, kept.left, 0.001f)
        assertEquals(32f, kept.top, 0.001f)
        assertEquals(97f, kept.width, 0.001f)
        assertEquals(64f, kept.height, 0.001f)
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

    @Test
    fun `a box keeps a finger's target only where no box over it reaches`() {
        val box = PickerBox(0f, 0f, 100f, 100f)
        assertTrue(keepsTarget(box, emptyList(), 52f))
        assertFalse("covered whole", keepsTarget(box, listOf(PickerBox(-10f, -10f, 200f, 200f)), 52f))
        // A strip 40 wide is left: less than a finger's target.
        assertFalse("a narrow strip", keepsTarget(box, listOf(PickerBox(40f, 0f, 60f, 100f)), 52f))
        assertTrue("a wide strip", keepsTarget(box, listOf(PickerBox(55f, 0f, 60f, 100f)), 52f))
        // Two boxes leave free strips at the top and on the left, each 60 by 40: none is 52 both ways.
        assertFalse("two boxes", keepsTarget(box, listOf(PickerBox(40f, 0f, 60f, 60f), PickerBox(0f, 60f, 100f, 40f)), 52f))
        // The free corner is a target when it is large enough, though the boxes cut it into squares.
        assertTrue("a free corner", keepsTarget(box, listOf(PickerBox(60f, 0f, 40f, 30f), PickerBox(0f, 60f, 100f, 40f), PickerBox(70f, 30f, 30f, 30f)), 52f))
        // A box beside it covers nothing.
        assertTrue("beside it", keepsTarget(box, listOf(PickerBox(100f, 0f, 50f, 100f)), 52f))
    }

    @Test
    fun `a floating pane over a row of narrow panes covers their cells`() {
        // Four side by side under a floating pane as high as the area and half as wide (#171).
        val four = WindowInfo("w", layout = row(leaf("a"), row(leaf("b"), row(leaf("c"), leaf("d")), 1f / 3), 0.25f), zoomed = "d",
            floating = listOf(FloatingLayer("f", x = 0f, y = 0f, w = 0.5f, h = 1f)))
        val map = zoomMap(four, false) { null }!!
        val (w, h) = zoomPickerSize(map, 194f, 128f, 52f)
        assertEquals(setOf("a", "b"), zoomCoveredCells(map, w, h, 52f))
        // In RTL the panes and the floating one are mirrored together: the same cells are covered.
        val rtl = zoomMap(four, true) { null }!!
        assertEquals(setOf("a", "b"), zoomCoveredCells(rtl, w, h, 52f))
        // A floating pane that leaves a finger's target of a cell covers none, and a tab without one has none.
        val small = four.copy(floating = listOf(FloatingLayer("f", x = 0f, y = 0f, w = 0.125f, h = 0.5f)))
        assertEquals(emptySet<String>(), zoomCoveredCells(zoomMap(small, false) { null }!!, w, h, 52f))
        assertEquals(emptySet<String>(), zoomCoveredCells(zoomMap(four.copy(floating = emptyList()), false) { null }!!, w, h, 52f))
    }

    @Test
    fun `a floating pane can cover another floating one`() {
        // The later floating pane lies over the earlier one, as it floats over it; it is itself on top. It leaves claude
        // strips 39 wide and 26 high, and build and logcat a target each on its right.
        val two = tab.copy(floating = listOf(FloatingLayer("f", x = 0.3f, y = 0.3f, w = 0.3f, h = 0.4f),
            FloatingLayer("g", x = 0.2f, y = 0.2f, w = 0.5f, h = 0.6f)))
        val map = zoomMap(two, false) { null }!!
        assertEquals(setOf("a", "f"), zoomCoveredCells(map, 194f, 128f, 52f))
    }
}
