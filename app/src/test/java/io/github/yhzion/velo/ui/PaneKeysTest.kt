package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun leaf(block: String) = LayoutNode(block = block)
private fun row(a: LayoutNode, b: LayoutNode, ratio: Float = 0.5f) = LayoutNode(dir = "row", ratio = ratio, first = a, second = b)
private fun column(a: LayoutNode, b: LayoutNode, ratio: Float = 0.5f) = LayoutNode(dir = "column", ratio = ratio, first = a, second = b)

private fun tab(layout: LayoutNode, zoomed: String? = null, floating: List<FloatingLayer> = emptyList()) =
    WindowInfo("w1", layout = layout, zoomed = zoomed, floating = floating)

private fun WindowInfo.toward(block: String, direction: PaneDirection, rtl: Boolean = false) = paneToward(this, block, direction, rtl)?.block

class PaneKeysTest {
    @Test
    fun `the labels are the palette strings`() {
        assertEquals(
            listOf("Move Pane Left", "Move Pane Right", "Move Pane Up", "Move Pane Down"),
            PaneDirection.entries.map { it.label },
        )
    }

    @Test
    fun `a row has neighbours to the left and right only`() {
        val w = tab(row(leaf("a"), leaf("b")))
        assertEquals("b", w.toward("a", PaneDirection.Right))
        assertEquals("a", w.toward("b", PaneDirection.Left))
        assertNull(w.toward("a", PaneDirection.Left))
        assertNull(w.toward("b", PaneDirection.Right))
        assertNull(w.toward("a", PaneDirection.Up))
        assertNull(w.toward("a", PaneDirection.Down))
    }

    @Test
    fun `a column has neighbours above and below only`() {
        val w = tab(column(leaf("a"), leaf("b")))
        assertEquals("b", w.toward("a", PaneDirection.Down))
        assertEquals("a", w.toward("b", PaneDirection.Up))
        assertNull(w.toward("a", PaneDirection.Up))
        assertNull(w.toward("a", PaneDirection.Left))
    }

    @Test
    fun `a tie on the overlap goes to the smaller top`() {
        val w = tab(row(leaf("a"), column(leaf("b"), leaf("c"))))
        assertEquals("b", w.toward("a", PaneDirection.Right))
        assertEquals("a", w.toward("c", PaneDirection.Left))
        assertEquals("b", w.toward("c", PaneDirection.Up))
        assertEquals("c", w.toward("b", PaneDirection.Down))
    }

    @Test
    fun `a tie on the overlap going up or down goes to the smaller left`() {
        val w = tab(column(row(leaf("a"), leaf("b")), leaf("c")))
        assertEquals("a", w.toward("c", PaneDirection.Up))
    }

    @Test
    fun `the larger overlap wins`() {
        val w = tab(row(column(leaf("a"), leaf("b"), 0.3f), leaf("c")))
        assertEquals("b", w.toward("c", PaneDirection.Left))
    }

    @Test
    fun `right to left mirrors the sides`() {
        val w = tab(row(leaf("a"), leaf("b")))
        assertEquals("b", w.toward("a", PaneDirection.Left, rtl = true))
        assertNull(w.toward("a", PaneDirection.Right, rtl = true))
        assertEquals("a", w.toward("b", PaneDirection.Right, rtl = true))
    }

    @Test
    fun `a zoomed tab has no neighbours`() {
        val w = tab(row(leaf("a"), leaf("b")), zoomed = "a")
        PaneDirection.entries.forEach { assertNull(w.toward("a", it)) }
    }

    @Test
    fun `a floating pane, a stranger and a lone pane have no neighbours`() {
        val w = tab(row(leaf("a"), leaf("b")), floating = listOf(FloatingLayer("f", 0.1f, 0.1f, 0.4f, 0.4f)))
        PaneDirection.entries.forEach { assertNull(w.toward("f", it)) }
        PaneDirection.entries.forEach { assertNull(w.toward("zz", it)) }
        val lone = tab(leaf("a"))
        PaneDirection.entries.forEach { assertNull(lone.toward("a", it)) }
    }

    private val rect = PaneRect("b", 0.5f, 0f, 1f, 1f)
    private val ab = listOf(tab(row(leaf("a"), leaf("b"))))
    private val ba = listOf(tab(row(leaf("b"), leaf("a"))))
    private val swap = MovePlace.Swap("w1", "b", rect)
    private fun pending(sentAt: Long = 1000L) = PendingMove("a", swap, ab, sentAt)

    @Test
    fun `a sent move that has not landed holds the next one`() {
        assertTrue(moveBusy(pending(), 1000L, ab))
        assertTrue(moveBusy(pending(), 1000L + LandWaitMillis - 1, ab))
    }

    @Test
    fun `a landed move frees the next one`() {
        assertFalse(moveBusy(pending(), 1500L, ba))
    }

    @Test
    fun `a move that never lands frees the next one when the wait is over`() {
        assertFalse(moveBusy(pending(), 1000L + LandWaitMillis, ab))
        assertFalse(moveBusy(pending(), 1000L + LandWaitMillis + 1, ab))
    }

    @Test
    fun `no sent move holds nothing`() {
        assertFalse(moveBusy(null, 1000L, ab))
    }

    @Test
    fun `a busy chord is taken even without a neighbour in the old layout`() {
        // The layout on screen is the old one: Right has no neighbour there, but the chord must not reach the terminal.
        val w = ab.first()
        assertFalse(movePaneChord(true, false, true, w, "b", PaneDirection.Right, false, busy = false))
        assertTrue(movePaneChord(true, false, true, w, "b", PaneDirection.Right, false, busy = true))
        // A busy chord is still taken only while the workspace takes keys.
        assertFalse(movePaneChord(false, false, true, w, "b", PaneDirection.Right, false, busy = true))
        assertFalse(movePaneChord(true, true, true, w, "b", PaneDirection.Right, false, busy = true))
        assertFalse(movePaneChord(true, false, false, w, "b", PaneDirection.Right, false, busy = true))
    }
}
