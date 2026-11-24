package io.github.yhzion.velo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun leaf(block: String) = LayoutNode(block = block)
private fun row(a: LayoutNode, b: LayoutNode) = LayoutNode(dir = "row", ratio = 0.5f, first = a, second = b)
private fun column(a: LayoutNode, b: LayoutNode) = LayoutNode(dir = "column", ratio = 0.5f, first = a, second = b)
private fun float(block: String) = FloatingLayer(block, 0.1f, 0.1f, 0.4f, 0.4f)

class PaneDragTest {
    private val stage = Stage(width = 1000f, height = 800f, cardGap = 20f, pageGap = 20f, labelRoom = 40f)
    private val gap = 8f

    /** Tab t1 holds a beside b; tab t2 holds c over d. */
    private val t1 = WindowInfo("t1", layout = row(leaf("a"), leaf("b")))
    private val t2 = WindowInfo("t2", layout = column(leaf("c"), leaf("d")))

    /** The point at ([x], [y]) of the unscaled card [index] (the overview fully open, the row on card 0), in the pane area. */
    private fun at(index: Int, x: Float, y: Float): Pair<Float, Float> {
        val box = cardBox(index, 0, 1f, 0f, stage)
        val scale = (box.right - box.left) / stage.width
        return box.left + x * scale to box.top + y * scale
    }

    private fun placeAtPoint(point: Pair<Float, Float>, cards: List<WindowInfo>, block: String = "zz", newTab: Boolean = true, rtl: Boolean = false) =
        stagePlaceAt(point.first, point.second, cards, block, newTab, anchor = 0, progress = 1f, scroll = 0f, stage = stage, gap = gap, rtl = rtl)

    private fun assertBox(expected: CardBox, actual: CardBox) {
        assertEquals("left", expected.left, actual.left, 0.01f)
        assertEquals("top", expected.top, actual.top, 0.01f)
        assertEquals("right", expected.right, actual.right, 0.01f)
        assertEquals("bottom", expected.bottom, actual.bottom, 0.01f)
    }

    @Test
    fun `the row scrolls towards the edge the finger is near`() {
        assertEquals(-12f, edgeScroll(10f, 1000f, edge = 48f, step = 12f))
        assertEquals(12f, edgeScroll(990f, 1000f, edge = 48f, step = 12f))
        assertEquals(0f, edgeScroll(500f, 1000f, edge = 48f, step = 12f))
        // The edge itself is outside the band, on both sides.
        assertEquals(0f, edgeScroll(48f, 1000f, edge = 48f, step = 12f))
        assertEquals(0f, edgeScroll(952f, 1000f, edge = 48f, step = 12f))
    }

    @Test
    fun `the row scrolls only once the finger has rested in the edge band`() {
        // A tap near an edge chooses what is under it: the row stands still until the finger stays.
        assertFalse(edgeScrolls(heldMillis = 0L))
        assertFalse(edgeScrolls(heldMillis = 299L))
        assertTrue(edgeScrolls(heldMillis = 300L))
        assertTrue(edgeScrolls(heldMillis = 2000L))
    }

    @Test
    fun `a lift moves the pane over a place, and pick mode stays over none`() {
        assertEquals(MoveLift.Move, moveLift(pick = false, overPlace = true, overCancel = false, scrolled = false))
        assertEquals(MoveLift.Move, moveLift(pick = true, overPlace = true, overCancel = false, scrolled = false))
        // A drag let go over no place leaves the pane where it was.
        assertEquals(MoveLift.Cancel, moveLift(pick = false, overPlace = false, overCancel = false, scrolled = false))
        // Pick mode is left with Cancel or Back only: a press beside the places is no choice.
        assertEquals(MoveLift.Stay, moveLift(pick = true, overPlace = false, overCancel = false, scrolled = false))
        assertEquals(MoveLift.Cancel, moveLift(pick = true, overPlace = false, overCancel = true, scrolled = false))
        // A press that scrolled the row chose nothing.
        assertEquals(MoveLift.Stay, moveLift(pick = true, overPlace = false, overCancel = false, scrolled = true))
    }

    @Test
    fun `the capsule says what a lift there does`() {
        val name = { block: String -> mapOf("a" to "vim", "zz" to "psql")[block] ?: block }
        val tab = { _: String -> "logs" }
        val swap = MovePlace.Swap("w1", "a", PaneRect("a", 0f, 0f, 10f, 10f))
        fun caption(pick: Boolean, place: MovePlace?, overCancel: Boolean) = moveCaption(pick, place, overCancel, "zz", name, tab, null)
        // Over a place both modes name it, the Cancel button's bounds aside.
        assertEquals("Swap with vim", caption(pick = false, place = swap, overCancel = false))
        assertEquals("Swap with vim", caption(pick = true, place = swap, overCancel = false))
        // A drag let go over no place ends.
        assertEquals("Release here to cancel", caption(pick = false, place = null, overCancel = false))
        // Pick mode goes on off a place, the empty source card and the capsule's text too: it keeps asking.
        assertEquals("Choose where “psql” goes", caption(pick = true, place = null, overCancel = false))
        // Only on Cancel does a lift end pick mode, and only there does the capsule say so.
        assertEquals("Release here to cancel", caption(pick = true, place = null, overCancel = true))
        // The tab the move empties is named, as the place's line names it.
        assertEquals("Move to a new tab · tab “logs” closes", moveCaption(false, MovePlace.NewTab, false, "zz", name, tab, "w1"))
    }

    @Test
    fun `in pick mode a sideways swipe begun off every place scrolls the row`() {
        assertTrue(pickScrolls(onPlace = false, travelX = -20f, travelY = 4f, slop = 8f))
        assertTrue(pickScrolls(onPlace = false, travelX = 20f, travelY = -4f, slop = 8f))
        // On a place the finger aims; not yet past the slop, or more up and down than sideways, it aims too.
        assertFalse(pickScrolls(onPlace = true, travelX = -20f, travelY = 4f, slop = 8f))
        assertFalse(pickScrolls(onPlace = false, travelX = 6f, travelY = 0f, slop = 8f))
        assertFalse(pickScrolls(onPlace = false, travelX = 12f, travelY = 20f, slop = 8f))
    }

    @Test
    fun `while a pane is moved the row rests on the New Tab card too`() {
        // The move's New Tab card stands in the place of the row's own new-tab card (#230): the row has no more places.
        val tabs = listOf(t1, t2)
        assertEquals(MovePlace.NewTab, placeAtPoint(at(rowPlaces(tabs.size) - 1, stage.width / 2, stage.height / 2), tabs)?.place)
        // Let go at the New Tab card, the row stays there instead of going back to the last tab.
        val atNewTab = cardScroll(2, 0, stage)
        assertEquals(atNewTab, restingScroll(atNewTab, 0f, rowPlaces(tabs.size), 0, stage), 0.01f)
    }

    @Test
    fun `a pane move's overview shows no session strip`() {
        // A pane moves only within its session, and the move's capsule with Cancel stands where the strip would.
        assertTrue(showsStrip(stripRoom = 96f, forMove = false))
        assertFalse(showsStrip(stripRoom = 96f, forMove = true))
        assertFalse(showsStrip(stripRoom = 0f, forMove = false))
    }

    @Test
    fun `the overview is a move's from the move's start until it has closed with no move`() {
        // The move begins: the overview opens as a move's.
        assertTrue(movesOverview(was = false, moving = true, progress = 0f, opening = true))
        // The first frame of that opening is at 0 too: the move is under way, so it stays a move's.
        assertTrue(movesOverview(was = true, moving = true, progress = 0f, opening = false))
        // After the move it closes as a move's, to the end, so nothing pops in on the way out.
        assertTrue(movesOverview(was = true, moving = false, progress = 0.4f, opening = false))
        assertFalse(movesOverview(was = true, moving = false, progress = 0f, opening = false))
        // Opened again while it closes after a move, it is the plain overview at once.
        assertFalse(movesOverview(was = true, moving = false, progress = 0.4f, opening = true))
        assertFalse(movesOverview(was = false, moving = false, progress = 0f, opening = true))
    }

    @Test
    fun `pick mode's Cancel is where the press found it, until the press ends`() {
        val cancel = CancelBounds()
        cancel.drawn = Rect(800f, 10f, 900f, 60f)
        cancel.press(pick = true)
        // The capsule's words got shorter under the finger, and the centred capsule drew the button further left.
        cancel.drawn = Rect(775f, 10f, 875f, 60f)
        // Its right quarter as the press found it is still Cancel, for the capsule's words and for the lift.
        val right = Offset(890f, 30f)
        assertTrue(cancel.contains(right))
        assertEquals("Release here to cancel", moveCaption(true, null, cancel.contains(right), "zz", { it }, { it }, null))
        assertEquals(MoveLift.Cancel, moveLift(pick = true, overPlace = false, overCancel = cancel.contains(right), scrolled = false))
        assertFalse(cancel.contains(null))
        // The next press finds the button where it is drawn then.
        cancel.release()
        assertFalse(cancel.contains(right))
        cancel.press(pick = true)
        assertFalse(cancel.contains(right))
        assertTrue(cancel.contains(Offset(780f, 30f)))
        // A drag has no Cancel button, whatever was drawn before.
        cancel.press(pick = false)
        assertFalse(cancel.contains(Offset(780f, 30f)))
    }

    @Test
    fun `the place is taken from the card under the finger and drawn back in the pane area`() {
        // In c, the top pane of t2: nearer its left edge than its top, so the left half of c.
        val found = placeAtPoint(at(1, 100f, 99f), listOf(t1, t2))!!
        val c = paneRects(t2, stage.width, stage.height, gap, false).first { it.block == "c" }
        assertEquals(MovePlace.Beside("t2", "c", PaneEdge.Left, c.copy(right = 500f)), found.place)
        val box = cardBox(1, 0, 1f, 0f, stage)
        val scale = (box.right - box.left) / stage.width
        assertBox(CardBox(box.left, box.top, box.left + 500f * scale, box.top + c.bottom * scale), found.box)
    }

    @Test
    fun `a swap is drawn as the whole pane`() {
        val found = placeAtPoint(at(1, 500f, 600f), listOf(t1, t2))!!
        val d = paneRects(t2, stage.width, stage.height, gap, false).first { it.block == "d" }
        assertEquals(MovePlace.Swap("t2", "d", d), found.place)
        val box = cardBox(1, 0, 1f, 0f, stage)
        assertBox(onStage(d, box, stage), found.box)
    }

    @Test
    fun `the source card shows its tab without the dragged pane, which is no place`() {
        // Over where a was: its card is lifted, so b fills it and takes the place.
        val lifted = listOf(cardWindow(t1, "a", "t1"), t2)
        val found = placeAtPoint(at(0, 100f, 400f), lifted, block = "a")!!.place
        assertEquals("b", (found as MovePlace.Beside).anchor)
        assertEquals(PaneEdge.Left, found.edge)
        // Drawn as it was, the dragged pane would be no place to drop it on.
        assertNull(placeAtPoint(at(0, 100f, 400f), listOf(t1, t2), block = "a"))
    }

    @Test
    fun `the New Tab card follows the last tab and takes the pane only when it can go there`() {
        val box = cardBox(2, 0, 1f, 0f, stage)
        val centre = (box.left + box.right) / 2 to (box.top + box.bottom) / 2
        val found = placeAtPoint(centre, listOf(t1, t2))!!
        assertSame(MovePlace.NewTab, found.place)
        assertBox(box, found.box)
        assertNull(placeAtPoint(centre, listOf(t1, t2), newTab = false))
    }

    @Test
    fun `between the cards and under a card's name there is no place`() {
        val first = cardBox(0, 0, 1f, 0f, stage)
        val second = cardBox(1, 0, 1f, 0f, stage)
        assertNull(placeAtPoint((first.right + second.left) / 2 to (first.top + first.bottom) / 2, listOf(t1, t2)))
        assertNull(placeAtPoint((first.left + first.right) / 2 to first.bottom + stage.labelRoom / 2, listOf(t1, t2)))
        // Above the row, where no card is.
        assertNull(placeAtPoint((first.left + first.right) / 2 to first.top / 2, listOf(t1, t2)))
    }

    @Test
    fun `in RTL the first pane of a row is on the right`() {
        // The visual right edge of the card, at mid height: a is drawn there in RTL.
        val found = placeAtPoint(at(0, 980f, 400f), listOf(t1, t2), rtl = true)!!.place as MovePlace.Beside
        assertEquals("a", found.anchor)
        assertEquals(PaneEdge.Right, found.edge)
        assertEquals("left", wireDir(found.edge, rtl = true))
    }

    @Test
    fun `while the overview opens the place follows the cards where they are drawn then`() {
        // Half open, the cards are larger and further apart: the place is read from the card at that size.
        val box = cardBox(0, 0, 0.5f, 0f, stage)
        val scale = (box.right - box.left) / stage.width
        val found = stagePlaceAt(box.left + 900f * scale, box.top + 400f * scale, listOf(t1, t2), "zz", true, 0, 0.5f, 0f, stage, gap, false)!!
        val b = paneRects(t1, stage.width, stage.height, gap, false).first { it.block == "b" }
        assertEquals(MovePlace.Beside("t1", "b", PaneEdge.Right, b.copy(left = (b.left + b.right) / 2)), found.place)
        assertTrue(found.box.right - found.box.left > (b.right - b.left) / 2 * cardBox(0, 0, 1f, 0f, stage).let { (it.right - it.left) / stage.width })
    }

    @Test
    fun `a card shows its source tab lifted and no floating pane anywhere`() {
        val source = t1.copy(floating = listOf(float("f"), float("g")))
        assertEquals(WindowInfo("t1", layout = leaf("b")), cardWindow(source, "a", "t1"))
        assertEquals(WindowInfo("t2", layout = t2.layout), cardWindow(t2.copy(floating = listOf(float("h"))), "a", "t1"))
        // A floating dragged pane leaves its own tab's layout as it is.
        assertEquals(t1, cardWindow(source, "f", "t1"))
    }

    @Test
    fun `the source tab closes only when the move takes its last pane to another tab`() {
        val alone = WindowInfo("t3", layout = leaf("e"))
        val beside = MovePlace.Beside("t2", "c", PaneEdge.Left, PaneRect("c", 0f, 0f, 1f, 1f))
        assertEquals("t3", closingTab(alone, "e", beside))
        assertNull(closingTab(alone, "e", MovePlace.Swap("t2", "c", PaneRect("c", 0f, 0f, 1f, 1f))))
        assertNull(closingTab(alone, "e", null))
        assertNull(closingTab(t1, "a", beside))
        // A minimized pane still runs in the tab, so the tab stays.
        assertNull(closingTab(alone.copy(minimized = true, minimizedBlocks = listOf("m")), "e", beside))
        // A floating pane alone in its tab goes out to a new one, and its old tab closes.
        assertEquals("t4", closingTab(WindowInfo("t4", floating = listOf(float("f"))), "f", MovePlace.NewTab))
    }

    @Test
    fun `the source card says its tab closes when the move empties it`() {
        val alone = WindowInfo("t3", layout = leaf("e"))
        assertEquals("Tab closes", sourceCardNote(alone, "e", "t3"))
        // A floating pane alone in its tab empties it too.
        assertEquals("Tab closes", sourceCardNote(WindowInfo("t4", floating = listOf(float("f"))), "f", "t4"))
        // A tab that keeps a pane, tiled, floating or minimized, says nothing.
        assertNull(sourceCardNote(t1, "a", "t1"))
        assertNull(sourceCardNote(alone.copy(floating = listOf(float("f"))), "e", "t3"))
        assertNull(sourceCardNote(alone.copy(minimized = true, minimizedBlocks = listOf("m")), "e", "t3"))
        // Only the source tab's card speaks of the move.
        assertNull(sourceCardNote(alone, "a", "t1"))
    }

    @Test
    fun `a New Tab card that takes no pane says why`() {
        assertNull(newTabNote(t1, "a"))
        assertNull(newTabNote(WindowInfo("t4", floating = listOf(float("f"))), "f"))
        assertEquals("Already alone in its tab", newTabNote(WindowInfo("t3", layout = leaf("e")), "e"))
    }

    @Test
    fun `pick mode offers each half and the swap, as a finger near each edge finds them`() {
        val rect = PaneRect("c", 100f, 50f, 500f, 450f)
        val places = placesOf(rect, "t2")
        val rects = listOf(rect)
        assertEquals(
            listOf(
                placeAt(rects, 110f, 250f, "t2", "zz"),
                placeAt(rects, 490f, 250f, "t2", "zz"),
                placeAt(rects, 300f, 60f, "t2", "zz"),
                placeAt(rects, 300f, 440f, "t2", "zz"),
                placeAt(rects, 300f, 250f, "t2", "zz"),
            ),
            places,
        )
    }

    @Test
    fun `a scrolled row takes the place from the card that stands under the finger`() {
        // The row centres card 1: the point where card 0 stands at rest is on card 1 now, in c's left half.
        val scroll = cardScroll(1, 0, stage)
        val box = cardBox(1, 0, 1f, scroll, stage)
        assertBox(cardBox(0, 0, 1f, 0f, stage), box)
        val scale = (box.right - box.left) / stage.width
        val found = stagePlaceAt(box.left + 100f * scale, box.top + 99f * scale, listOf(t1, t2), "zz", true, 0, 1f, scroll, stage, gap, false)!!
        val c = paneRects(t2, stage.width, stage.height, gap, false).first { it.block == "c" }
        assertEquals(MovePlace.Beside("t2", "c", PaneEdge.Left, c.copy(right = 500f)), found.place)
        assertBox(onStage(c.copy(right = 500f), box, stage), found.box)
    }

    @Test
    fun `a move sent before the last one has shown offers no Undo`() {
        val label: (WindowInfo) -> String = { "" }
        val below = MovePlace.Beside("t2", "c", PaneEdge.Below, PaneRect("c", 0f, 0f, 1f, 1f))
        val first = moveNoticeAfter(null, "a", below, listOf(t1, t2), label)
        assertEquals(UndoMove.Beside("a", "b", "left", 0.5f), first.undo)
        // The next move leaves before the first shows: the tabs it was made from are not the server's, so no inverse is exact.
        val swap = MovePlace.Swap("t2", "d", PaneRect("d", 0f, 0f, 1f, 1f))
        val early = moveNoticeAfter(first, "b", swap, listOf(t1, t2), label)
        assertNull(early.undo)
        assertEquals(swap, early.place)
        // Once the first move shows, the next one has its Undo again.
        val landed = listOf(WindowInfo("t1", layout = leaf("b")), WindowInfo("t2", layout = column(column(leaf("c"), leaf("a")), leaf("d"))))
        assertEquals(UndoMove.Swap("b", "d"), moveNoticeAfter(first, "b", swap, landed, label).undo)
    }

    @Test
    fun `the overview closes onto the tab the pane went to`() {
        val rect = PaneRect("c", 0f, 0f, 1f, 1f)
        assertEquals("t2", destinationTab(MovePlace.Beside("t2", "c", PaneEdge.Below, rect)))
        assertEquals("t2", destinationTab(MovePlace.Swap("t2", "c", rect)))
        assertNull(destinationTab(MovePlace.NewTab))
    }
}
