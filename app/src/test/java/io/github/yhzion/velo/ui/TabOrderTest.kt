package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabOrderTest {
    private val row = listOf("a", "b", "c", "d")

    @Test
    fun aTabTakesThePlaceItIsDroppedOn() {
        assertEquals(TabMove("b", null), tabMoveTo(row, "b", 3))
        assertEquals(TabMove("d", "a"), tabMoveTo(row, "d", 0))
        assertEquals(TabMove("b", "d"), tabMoveTo(row, "b", 2))
        assertEquals(TabMove("c", "b"), tabMoveTo(row, "c", 1))
    }

    @Test
    fun aDropOnItsOwnPlaceMovesNothing() {
        assertNull(tabMoveTo(row, "b", 1))
        assertNull(tabMoveTo(row, "x", 0))
    }

    @Test
    fun aPlaceOutsideTheRowIsItsNearestEnd() {
        assertEquals(TabMove("b", null), tabMoveTo(row, "b", 9))
        assertEquals(TabMove("b", "a"), tabMoveTo(row, "b", -1))
    }

    @Test
    fun aClosingTabIsNoPlaceToDropBefore() {
        val tabs = listOf("a", "x", "b", "c")
        val shown = listOf("a", "b", "c")
        val back = tabMoveTo(shown, "c", 1)
        assertEquals(TabMove("c", "b"), back)
        assertEquals(listOf("a", "x", "c", "b"), movedTabs(tabs, back!!))
        val last = tabMoveTo(shown, "a", 2)
        assertEquals(TabMove("a", null), last)
        assertEquals(listOf("x", "b", "c", "a"), movedTabs(tabs, last!!))
    }

    @Test
    fun forwardAndBackwardMoveOnePlaceAndStopAtTheEnds() {
        val order = listOf("a", "b", "c")
        assertEquals(TabMove("b", null), tabStep(order, "b", true))
        assertEquals(TabMove("b", "a"), tabStep(order, "b", false))
        assertEquals(TabMove("a", "c"), tabStep(order, "a", true))
        assertEquals(TabMove("c", "b"), tabStep(order, "c", false))
        assertNull(tabStep(order, "c", true))
        assertNull(tabStep(order, "a", false))
        assertNull(tabStep(listOf("a"), "a", true))
        assertNull(tabStep(listOf("a"), "a", false))
        assertNull(tabStep(order, "x", true))
        assertNull(tabStep(order, "x", false))
    }

    @Test
    fun movedTabsPutsTheTabBeforeItsNeighbourOrLast() {
        val tabs = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedTabs(tabs, TabMove("a", "c")))
        assertEquals(listOf("a", "c", "b"), movedTabs(tabs, TabMove("b", null)))
        assertEquals(listOf("b", "c", "a"), movedTabs(tabs, TabMove("a", "gone")))
    }

    @Test
    fun heldOrderFollowsTheServerWhenTabsComeOrGo() {
        assertEquals(listOf("b", "a", "c"), heldOrder(listOf("a", "b", "c"), listOf("b", "a", "c")))
        assertNull(heldOrder(listOf("b", "a", "c"), listOf("b", "a", "c")))
        // A tab the held order lacks: the server's order is the one to show at once.
        assertNull(heldOrder(listOf("a", "b", "c", "d"), listOf("b", "a", "c")))
        // A tab that went keeps the held order of the tabs that stay.
        assertEquals(listOf("c", "a"), heldOrder(listOf("a", "c"), listOf("c", "a", "b")))
        assertNull(heldOrder(listOf("a", "b"), null))
    }

    @Test
    fun aDroppedOrderStaysWhileAClosedTabLeaves() {
        // c's close was sent and a was dropped at the end: the server takes c away before it moves a.
        assertEquals(listOf("b", "a"), heldOrder(listOf("a", "b"), listOf("b", "c", "a")))
        // Then its view matches what is left of the held order: the hold has done its work.
        assertNull(heldOrder(listOf("b", "a"), listOf("b", "c", "a")))
        // A tab came that the held order never had.
        assertNull(heldOrder(listOf("a", "b", "d"), listOf("b", "a")))
    }

    @Test
    fun aCardOffersOnlyTheMovesItCanMake() {
        val order = listOf("a", "b", "c")
        assertEquals(
            listOf("Move Tab Forward" to TabMove("b", null), "Move Tab Backward" to TabMove("b", "a")),
            tabCardMoves(order, "b", true),
        )
        assertEquals(listOf("Move Tab Forward" to TabMove("a", "c")), tabCardMoves(order, "a", true))
        assertEquals(listOf("Move Tab Backward" to TabMove("c", "b")), tabCardMoves(order, "c", true))
        assertEquals(emptyList<Pair<String, TabMove>>(), tabCardMoves(listOf("a"), "a", true))
        assertEquals(emptyList<Pair<String, TabMove>>(), tabCardMoves(order, "b", false))
    }
}
