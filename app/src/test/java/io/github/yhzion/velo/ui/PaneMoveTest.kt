package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun leaf(block: String) = LayoutNode(block = block)
private fun row(a: LayoutNode, b: LayoutNode, ratio: Float = 0.5f) = LayoutNode(dir = "row", ratio = ratio, first = a, second = b)
private fun column(a: LayoutNode, b: LayoutNode, ratio: Float = 0.5f) = LayoutNode(dir = "column", ratio = ratio, first = a, second = b)
private fun float(block: String) = FloatingLayer(block, 0.1f, 0.1f, 0.4f, 0.4f)

/** A row of leaf `a` and a column of `b` over `c`, as in the issue's 1000x800 example. */
private val nested = WindowInfo("w1", layout = row(leaf("a"), column(leaf("b"), leaf("c"), 0.55f)))

private fun assertRect(rect: PaneRect, left: Float, top: Float, right: Float, bottom: Float) {
    assertEquals("left of ${rect.block}", left, rect.left, 0.01f)
    assertEquals("top of ${rect.block}", top, rect.top, 0.01f)
    assertEquals("right of ${rect.block}", right, rect.right, 0.01f)
    assertEquals("bottom of ${rect.block}", bottom, rect.bottom, 0.01f)
}

class PaneMoveTest {
    @Test
    fun `removePane collapses the emptied split`() {
        assertEquals(column(leaf("b"), leaf("c"), 0.55f), removePane(nested.layout, "a"))
        assertEquals(row(leaf("a"), leaf("c")), removePane(nested.layout, "b"))
        assertNull(removePane(leaf("a"), "a"))
        assertNull(removePane(null, "a"))
        // A block that is not in the tree leaves it as it was.
        assertEquals(nested.layout, removePane(nested.layout, "zz"))
    }

    @Test
    fun `lifted window drops the pane, its floating layer and its zoom`() {
        val window = nested.copy(zoomed = "b", floating = listOf(float("f"), float("g")))
        val lifted = liftedWindow(window, "b")
        assertEquals(listOf("a", "c"), lifted.layout?.blocks())
        assertNull(lifted.zoomed)
        assertEquals(listOf("f", "g"), lifted.floating.map { it.block })
        assertEquals(listOf("f"), liftedWindow(window, "g").floating.map { it.block })
        // The zoom of another pane stays.
        assertEquals("b", liftedWindow(window, "a").zoomed)
    }

    @Test
    fun `rectangles follow LayoutTree for nested splits and gaps`() {
        val rects = paneRects(nested, 1000f, 800f, 8f, rtl = false).associateBy { it.block }
        assertRect(rects.getValue("a"), 0f, 0f, 496f, 800f)
        assertRect(rects.getValue("b"), 504f, 0f, 1000f, 435.6f)
        assertRect(rects.getValue("c"), 504f, 443.6f, 1000f, 800f)
        // The server's ratio is clamped as LayoutTree clamps it.
        val skewed = paneRects(WindowInfo("w", layout = row(leaf("a"), leaf("b"), ratio = 2f)), 1000f, 800f, 0f, false)
        assertRect(skewed[0], 0f, 0f, 950f, 800f)
        assertRect(skewed[1], 950f, 0f, 1000f, 800f)
        assertTrue(paneRects(WindowInfo("w"), 100f, 100f, 8f, false).isEmpty())
    }

    @Test
    fun `rtl mirrors rectangles and wire directions`() {
        val rects = paneRects(nested, 1000f, 800f, 8f, rtl = true).associateBy { it.block }
        assertRect(rects.getValue("a"), 504f, 0f, 1000f, 800f)
        assertRect(rects.getValue("b"), 0f, 0f, 496f, 435.6f)
        assertRect(rects.getValue("c"), 0f, 443.6f, 496f, 800f)
        assertEquals("left", wireDir(PaneEdge.Left, rtl = false))
        assertEquals("right", wireDir(PaneEdge.Right, rtl = false))
        assertEquals("right", wireDir(PaneEdge.Left, rtl = true))
        assertEquals("left", wireDir(PaneEdge.Right, rtl = true))
        assertEquals("above", wireDir(PaneEdge.Above, rtl = true))
        assertEquals("below", wireDir(PaneEdge.Below, rtl = true))
    }

    private val square = listOf(PaneRect("p", 100f, 100f, 200f, 200f))

    private fun edgeAt(fx: Float, fy: Float): PaneEdge? =
        (placeAt(square, 100f + fx * 100f, 100f + fy * 100f, "t", "dragged") as? MovePlace.Beside)?.edge

    @Test
    fun `edges win by relative distance`() {
        assertEquals(PaneEdge.Left, edgeAt(0.1f, 0.5f))
        assertEquals(PaneEdge.Right, edgeAt(0.9f, 0.5f))
        assertEquals(PaneEdge.Above, edgeAt(0.5f, 0.1f))
        assertEquals(PaneEdge.Below, edgeAt(0.5f, 0.9f))
        // Near each corner the nearer side wins: both diagonals of the square.
        assertEquals(PaneEdge.Above, edgeAt(0.2f, 0.1f))
        assertEquals(PaneEdge.Left, edgeAt(0.1f, 0.2f))
        assertEquals(PaneEdge.Above, edgeAt(0.8f, 0.1f))
        assertEquals(PaneEdge.Right, edgeAt(0.9f, 0.2f))
        assertEquals(PaneEdge.Below, edgeAt(0.2f, 0.9f))
        assertEquals(PaneEdge.Left, edgeAt(0.1f, 0.8f))
        assertEquals(PaneEdge.Below, edgeAt(0.8f, 0.9f))
        assertEquals(PaneEdge.Right, edgeAt(0.9f, 0.8f))
        // The distance is relative to the pane's size, not in pixels: in a wide pane a point
        // 40 px from the left is 4 % across, while 40 px from the top is 50 % down.
        val wide = listOf(PaneRect("p", 0f, 0f, 1000f, 80f))
        assertEquals(PaneEdge.Left, (placeAt(wide, 40f, 40f, "t", "d") as MovePlace.Beside).edge)
    }

    @Test
    fun `edge places highlight their half`() {
        val place = placeAt(square, 110f, 150f, "t", "d") as MovePlace.Beside
        assertEquals("t", place.tab)
        assertEquals("p", place.anchor)
        assertRect(place.highlight, 100f, 100f, 150f, 200f)
        assertRect((placeAt(square, 190f, 150f, "t", "d") as MovePlace.Beside).highlight, 150f, 100f, 200f, 200f)
        assertRect((placeAt(square, 150f, 110f, "t", "d") as MovePlace.Beside).highlight, 100f, 100f, 200f, 150f)
        assertRect((placeAt(square, 150f, 190f, "t", "d") as MovePlace.Beside).highlight, 100f, 150f, 200f, 200f)
    }

    @Test
    fun `the centre box is a swap`() {
        val place = placeAt(square, 150f, 150f, "t", "d") as MovePlace.Swap
        assertEquals("p", place.other)
        assertEquals("t", place.tab)
        assertRect(place.highlight, 100f, 100f, 200f, 200f)
        assertTrue(placeAt(square, 131f, 169f, "t", "d") is MovePlace.Swap)
        // Outside the 30..70 % box in either axis is an edge.
        assertTrue(placeAt(square, 129f, 150f, "t", "d") is MovePlace.Beside)
        assertTrue(placeAt(square, 150f, 171f, "t", "d") is MovePlace.Beside)
    }

    @Test
    fun `the dragged pane and gaps are no place`() {
        val rects = paneRects(nested, 1000f, 800f, 8f, false)
        // The gap between a and the column, and the gap between b and c.
        assertNull(placeAt(rects, 500f, 400f, "t", "x"))
        assertNull(placeAt(rects, 750f, 439f, "t", "x"))
        assertNull(placeAt(rects, 2000f, 400f, "t", "x"))
        // The dragged pane is no place even when its rectangle is still in the list.
        assertNull(placeAt(rects, 250f, 400f, "t", "a"))
        assertNotNull(placeAt(rects, 250f, 400f, "t", "b"))
    }

    @Test
    fun `places in a single card`() {
        val one = paneRects(WindowInfo("w", layout = leaf("a")), 400f, 300f, 8f, false)
        assertEquals(1, one.size)
        assertRect(one[0], 0f, 0f, 400f, 300f)
        val place = placeAt(one, 10f, 150f, "w", "x") as MovePlace.Beside
        assertEquals(PaneEdge.Left, place.edge)
        assertRect(place.highlight, 0f, 0f, 200f, 300f)
        // The lifted card of a one-pane tab has no places at all.
        val lifted = liftedWindow(WindowInfo("w", layout = leaf("a")), "a")
        assertNull(placeAt(paneRects(lifted, 400f, 300f, 8f, false), 200f, 150f, "w", "a"))
    }

    @Test
    fun `a zoomed tab offers only the zoomed pane`() {
        val rects = paneRects(nested.copy(zoomed = "c"), 1000f, 800f, 8f, false)
        assertEquals(1, rects.size)
        assertEquals("c", rects[0].block)
        assertRect(rects[0], 0f, 0f, 1000f, 800f)
        assertEquals("c", (placeAt(rects, 500f, 400f, "t", "a") as MovePlace.Swap).other)
    }

    @Test
    fun `new tab is refused for the only tiled pane but allowed for a lone floating pane`() {
        assertFalse(canMoveToNewTab(WindowInfo("w", layout = leaf("a")), "a"))
        assertTrue(canMoveToNewTab(nested, "a"))
        assertTrue(canMoveToNewTab(WindowInfo("w", layout = leaf("a"), floating = listOf(float("f"))), "a"))
        assertTrue(canMoveToNewTab(WindowInfo("w", layout = leaf("a"), minimizedBlocks = listOf("m")), "a"))
        assertTrue(canMoveToNewTab(WindowInfo("w", floating = listOf(float("f"))), "f"))
        // A block that is not in the tab cannot move out of it.
        assertFalse(canMoveToNewTab(nested, "zz"))
    }

    @Test
    fun `handle shows unless one tab holds one pane`() {
        assertFalse(showsMoveHandle(listOf(WindowInfo("w", layout = leaf("a")))))
        assertTrue(showsMoveHandle(listOf(nested)))
        assertTrue(showsMoveHandle(listOf(WindowInfo("w1", layout = leaf("a")), WindowInfo("w2", layout = leaf("b")))))
        assertTrue(showsMoveHandle(listOf(WindowInfo("w", layout = leaf("a"), floating = listOf(float("f"))))))
        assertTrue(showsMoveHandle(listOf(WindowInfo("w", layout = leaf("a"), minimizedBlocks = listOf("m")))))
    }

    private val rect = PaneRect("x", 0f, 0f, 1f, 1f)
    private val own: (WindowInfo) -> String = { it.label }

    @Test
    fun `undo of a swap swaps again`() {
        assertEquals(UndoMove.Swap("a", "c"), undoFor(listOf(nested), "a", MovePlace.Swap("w1", "c", rect), own))
    }

    @Test
    fun `undo of a move beside a leaf sibling keeps the ratio`() {
        val move = MovePlace.Beside("w2", "z", PaneEdge.Right, rect)
        val w2 = WindowInfo("w2", layout = leaf("z"))
        val rowTab = WindowInfo("w1", layout = row(leaf("a"), leaf("b"), 0.3f))
        val columnTab = WindowInfo("w1", layout = column(leaf("a"), leaf("b"), 0.7f))
        // The first side of a row is left, the second side right.
        assertEquals(UndoMove.Beside("a", "b", "left", 0.3f), undoFor(listOf(rowTab, w2), "a", move, own))
        assertEquals(UndoMove.Beside("b", "a", "right", 0.3f), undoFor(listOf(rowTab, w2), "b", move, own))
        // The first side of a column is above, the second side below.
        assertEquals(UndoMove.Beside("a", "b", "above", 0.7f), undoFor(listOf(columnTab, w2), "a", move, own))
        assertEquals(UndoMove.Beside("b", "a", "below", 0.7f), undoFor(listOf(columnTab, w2), "b", move, own))
        // The pane may also have moved within its own tab.
        val within = MovePlace.Beside("w1", "b", PaneEdge.Left, rect)
        assertEquals(UndoMove.Beside("a", "b", "left", 0.3f), undoFor(listOf(rowTab), "a", within, own))
    }

    @Test
    fun `undo of a lone pane rebuilds its tab`() {
        val w1 = WindowInfo("w1", label = "logs", layout = leaf("a"))
        val w2 = WindowInfo("w2", layout = leaf("b"))
        val w3 = WindowInfo("w3", layout = leaf("c"))
        val move = MovePlace.Beside("w2", "b", PaneEdge.Right, rect)
        // The owner named the tab, and a tab follows it.
        assertEquals(UndoMove.Retab("a", "logs", "w2"), undoFor(listOf(w1, w2, w3), "a", move, own))
        // An automatic label is not restored, and the last tab has no tab after it.
        assertEquals(UndoMove.Retab("c", null, null), undoFor(listOf(w1, w2, w3), "c", MovePlace.Beside("w1", "a", PaneEdge.Below, rect), { "" }))
        assertEquals(UndoMove.Retab("b", null, "w3"), undoFor(listOf(w1, w2, w3), "b", MovePlace.Beside("w1", "a", PaneEdge.Right, rect), { "" }))
        // A move out to a new tab restores the old position as well.
        assertEquals(UndoMove.Retab("a", "logs", "w2"), undoFor(listOf(w1, w2), "a", MovePlace.NewTab, own))
    }

    @Test
    fun `no undo when the sibling was a split`() {
        val before = listOf(
            WindowInfo("w1", layout = row(leaf("a"), column(leaf("b"), leaf("c")))),
            WindowInfo("w2", layout = leaf("z")),
        )
        val move = MovePlace.Beside("w2", "z", PaneEdge.Left, rect)
        assertNull(undoFor(before, "a", move, own))
        // A floating pane has no tiled position to restore.
        assertNull(undoFor(listOf(WindowInfo("w1", layout = leaf("a"), floating = listOf(float("f"))), before[1]), "f", move, own))
        assertNull(undoFor(listOf(WindowInfo("w1", floating = listOf(float("f"))), before[1]), "f", move, own))
        // A lone tiled pane whose tab holds other panes keeps its tab: there is no anchor to restore against.
        assertNull(undoFor(listOf(WindowInfo("w1", layout = leaf("a"), floating = listOf(float("f"))), before[1]), "a", move, own))
        // A pane that is nowhere has no inverse.
        assertNull(undoFor(before, "zz", move, own))
    }

    @Test
    fun `landed checks the destination for each place kind`() {
        val before = listOf(
            WindowInfo("w1", layout = row(leaf("a"), leaf("b"))),
            WindowInfo("w2", layout = leaf("z")),
        )
        // Beside: the pane and its anchor are siblings in the destination tab, and the tab changed.
        val beside = MovePlace.Beside("w2", "z", PaneEdge.Right, rect)
        val movedOut = listOf(WindowInfo("w1", layout = leaf("b")), WindowInfo("w2", layout = row(leaf("z"), leaf("a"))))
        assertFalse(moveLanded(before, before, "a", beside))
        assertTrue(moveLanded(before, movedOut, "a", beside))
        assertFalse(moveLanded(before, listOf(WindowInfo("w1", layout = leaf("b")), WindowInfo("w2", layout = row(leaf("a"), leaf("q")))), "a", beside))
        // Swap: the two panes exchanged their places.
        val swap = MovePlace.Swap("w2", "z", rect)
        val swapped = listOf(WindowInfo("w1", layout = row(leaf("z"), leaf("b"))), WindowInfo("w2", layout = leaf("a")))
        assertFalse(moveLanded(before, before, "a", swap))
        assertTrue(moveLanded(before, swapped, "a", swap))
        assertTrue(moveLanded(listOf(before[0]), listOf(WindowInfo("w1", layout = row(leaf("b"), leaf("a")))), "a", MovePlace.Swap("w1", "b", rect)))
        // New tab: a tab that did not exist holds the pane.
        val out = listOf(WindowInfo("w1", layout = leaf("b")), before[1], WindowInfo("w3", layout = leaf("a")))
        assertFalse(moveLanded(before, before, "a", MovePlace.NewTab))
        assertTrue(moveLanded(before, out, "a", MovePlace.NewTab))
        assertFalse(moveLanded(before, listOf(before[0], WindowInfo("w2", layout = row(leaf("z"), leaf("a")))), "a", MovePlace.NewTab))
    }

    @Test
    fun `landed confirms a swap that moves a floating pane`() {
        // The floating pane takes the tiled pane's slot and the tiled pane floats in its place.
        val tab = WindowInfo("w1", layout = row(leaf("a"), leaf("b")), floating = listOf(float("f")))
        val swap = MovePlace.Swap("w1", "a", rect)
        val swapped = WindowInfo("w1", layout = row(leaf("f"), leaf("b")), floating = listOf(float("a")))
        assertFalse(moveLanded(listOf(tab), listOf(tab), "f", swap))
        assertTrue(moveLanded(listOf(tab), listOf(swapped), "f", swap))
        // Across tabs: the floating pane of one tab and a tiled pane of another.
        val home = WindowInfo("w1", layout = leaf("a"), floating = listOf(float("f")))
        val other = WindowInfo("w2", layout = row(leaf("b"), leaf("c")))
        val across = MovePlace.Swap("w2", "c", rect)
        val homeAfter = WindowInfo("w1", layout = leaf("a"), floating = listOf(float("c")))
        val otherAfter = WindowInfo("w2", layout = row(leaf("b"), leaf("f")))
        assertFalse(moveLanded(listOf(home, other), listOf(home, other), "f", across))
        assertTrue(moveLanded(listOf(home, other), listOf(homeAfter, otherAfter), "f", across))
        // Only one of the two moving is not a swap yet.
        assertFalse(moveLanded(listOf(home, other), listOf(home, otherAfter), "f", across))
    }

    @Test
    fun `captions fall back to the pane label`() {
        val panes = listOf("a", "b", "c")
        val titles = mapOf("a" to "vim")
        val name: (String) -> String = { titles[it] ?: paneLabel(panes, it) ?: it }
        val tab: (String) -> String = { if (it == "w1") "logs" else "tab $it" }
        assertEquals("Left of vim · logs", placeCaption(MovePlace.Beside("w1", "a", PaneEdge.Left, rect), name, tab, null))
        assertEquals("Right of Pane 2 · logs", placeCaption(MovePlace.Beside("w1", "b", PaneEdge.Right, rect), name, tab, null))
        assertEquals("Above Pane 3 · tab w2", placeCaption(MovePlace.Beside("w2", "c", PaneEdge.Above, rect), name, tab, null))
        assertEquals("Below vim · logs", placeCaption(MovePlace.Beside("w1", "a", PaneEdge.Below, rect), name, tab, null))
        assertEquals("Swap with Pane 2", placeCaption(MovePlace.Swap("w1", "b", rect), name, tab, null))
        assertEquals("Move to a new tab", placeCaption(MovePlace.NewTab, name, tab, null))
        assertEquals("Release here to cancel", placeCaption(null, name, tab, null))
        assertEquals("Right of vim · logs · tab “logs” closes", placeCaption(MovePlace.Beside("w1", "a", PaneEdge.Right, rect), name, tab, "w1"))
        assertEquals("Move to a new tab · tab “tab w9” closes", placeCaption(MovePlace.NewTab, name, tab, "w9"))
        assertEquals("Release here to cancel", placeCaption(null, name, tab, "w1"))
        assertEquals("Moved vim", undoCaption(MovePlace.Beside("w1", "b", PaneEdge.Left, rect), "a", name))
        assertEquals("Moved Pane 3", undoCaption(MovePlace.NewTab, "c", name))
        assertEquals("Swapped vim and Pane 2", undoCaption(MovePlace.Swap("w1", "b", rect), "a", name))
    }

    @Test
    fun `move op names the core op and its fields for each place`() {
        val op: (String, List<Pair<String, Any?>>) -> Pair<String, List<Pair<String, Any?>>> = { name, args -> name to args }
        assertEquals(
            op("movePane", listOf("block" to "a", "anchor" to "b", "dir" to "left")),
            moveOp(MovePlace.Beside("w1", "b", PaneEdge.Left, rect), "a", rtl = false),
        )
        assertEquals(
            op("movePane", listOf("block" to "a", "anchor" to "b", "dir" to "below")),
            moveOp(MovePlace.Beside("w1", "b", PaneEdge.Below, rect), "a", rtl = false),
        )
        // In RTL the half on the screen's left is the server's right side of the anchor.
        assertEquals(
            op("movePane", listOf("block" to "a", "anchor" to "b", "dir" to "right")),
            moveOp(MovePlace.Beside("w1", "b", PaneEdge.Left, rect), "a", rtl = true),
        )
        assertEquals(op("swapPanes", listOf("block" to "a", "other" to "b")), moveOp(MovePlace.Swap("w1", "b", rect), "a", rtl = true))
        assertEquals(op("moveToNewTab", listOf("block" to "a")), moveOp(MovePlace.NewTab, "a", rtl = false))
    }

    @Test
    fun `undo op carries the fields of each inverse`() {
        assertEquals(
            listOf<Pair<String, Any?>>("kind" to "swap", "block" to "a", "other" to "b"),
            undoOp(UndoMove.Swap("a", "b")),
        )
        assertEquals(
            listOf<Pair<String, Any?>>("kind" to "beside", "block" to "a", "anchor" to "b", "dir" to "left", "ratio" to 0.35f),
            undoOp(UndoMove.Beside("a", "b", "left", 0.35f)),
        )
        assertEquals(
            listOf<Pair<String, Any?>>("kind" to "retab", "block" to "a", "label" to "logs", "before" to "w2"),
            undoOp(UndoMove.Retab("a", "logs", "w2")),
        )
        // A null label or anchor stays in the list: HostConnection.op leaves null values out of the message.
        assertEquals(
            listOf<Pair<String, Any?>>("kind" to "retab", "block" to "a", "label" to null, "before" to null),
            undoOp(UndoMove.Retab("a", null, null)),
        )
    }
}
