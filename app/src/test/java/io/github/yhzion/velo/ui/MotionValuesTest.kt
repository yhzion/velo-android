package io.github.yhzion.velo.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #347: what composition reads of the overview's motion changes when what it decides changes, not on each frame; #373: so
 * does what it reads of a finger that moves a card or a pane. Checked as the recomposer sees it: the state objects a read
 * subscribes to, and those a write changes.
 */
class MotionValuesTest {
    /** About the Fold8's unfolded pane area, in px, as [OverviewMotionTest] has it. */
    private val row = Stage(width = 2200f, height = 1500f, cardGap = 54f, pageGap = 54f, labelRoom = 90f,
        noteRoom = 225f, peek = 45f, rowPad = 36f)

    /** A grid of [places] on a pane area of 900×1300 dp at density 2, as [OverviewGridTest] builds one. */
    private fun grid(places: Int): Stage {
        val d = 2f
        val w = 900f * d
        val h = 1300f * d
        return Stage(w, h, cardGap = 24f * d, pageGap = 24f * d, labelRoom = 36f * d,
            stripRoom = stripRoomFor(h, 44f * d, 96f * d), noteRoom = 100f * d, peek = 20f * d, rowPad = 16f * d,
            grid = GridSpec(gridColumns(w, 13f, 16f * d, 12f * d), 16f * d, 12f * d, 8f * d, places))
    }

    /** The state objects [read] subscribes to, as a composition that ran it would. */
    private fun reads(read: () -> Unit): Set<Any> {
        val seen = HashSet<Any>()
        Snapshot.observe(readObserver = { seen += it }) { read() }
        return seen
    }

    /** Whether [write] changes any of the states in [read]: a recomposition the recomposer would schedule. */
    private fun invalidates(read: Set<Any>, write: () -> Unit): Boolean {
        val changed = HashSet<Any>()
        val handle = Snapshot.registerApplyObserver { set, _ -> changed.addAll(set) }
        try {
            write()
            Snapshot.sendApplyNotifications()
        } finally {
            handle.dispose()
        }
        return changed.any { it in read }
    }

    private fun values(progress: Float = 0f) = MotionValues().also {
        it.progress = progress
        Snapshot.sendApplyNotifications()
    }

    @Test fun whetherTheOverviewShowsChangesOnlyWhenItComesOrGoes() {
        val values = values()
        val read = reads { values.showing }
        val steps = listOf(0.1f, 0.4f, 1f, 0.6f, 0f, 0f, 0.2f)
        val flips = steps.map { p -> invalidates(read) { values.progress = p } }
        assertEquals(listOf(true, false, false, false, true, false, true), flips)
        assertTrue(values.showing)
        // The least step open shows it.
        values.progress = 0f
        values.progress = 0.001f
        assertTrue(values.showing)
    }

    @Test fun theTabsRolesAreThoseOfTheMotionsNumbers() {
        // As TabStage worked them out before #347, from progress and scroll, and as it reads them now, step by step of an
        // opening, a scroll across the row and a closing.
        val tabs = (0 until 12).map { "t$it" }
        val values = values()
        val steps = (0..20).map { it / 20f to 0f } + (1..40).map { 1f to it * 61f } + (19 downTo 0).map { it / 20f to 2440f }
        for ((p, s) in steps) {
            values.progress = p
            values.scroll = s
            val before = tabRoles(tabs, "t5", 5, keptPlaces(tabs.size, 5, p, s, row), closed = p == 0f, pinching = false)
            val now = tabRoles(tabs, "t5", 5, values.keptAt(tabs.size, 5, row), closed = !values.showing, pinching = false)
            assertEquals("progress $p, scroll $s", before, now)
        }
    }

    @Test fun theKeptPlacesAreKeptPlacesAtTheMotionsValues() {
        for ((stage, count) in listOf(row to 12, grid(30) to 30)) {
            val values = values()
            for (p in listOf(0f, 0.3f, 1f)) for (s in listOf(0f, 400f, -900f, 2500f)) {
                values.progress = p
                values.scroll = s
                assertEquals("$count places at progress $p, scroll $s", keptPlaces(count, 4, p, s, stage), values.keptAt(count, 4, stage))
            }
        }
    }

    @Test fun aScrolledRowInvalidatesTheStageOnlyWhenItsKeptPlacesChange() {
        val values = values(progress = 1f)
        var read = reads { values.keptAt(12, 5, row) }
        var last = keptPlaces(12, 5, 1f, 0f, row)
        var changes = 0
        for (step in -150..150) {
            val s = step * 23f
            val invalidated = invalidates(read) { values.scroll = s }
            val now = keptPlaces(12, 5, 1f, s, row)
            assertEquals("scroll $s", now != last, invalidated)
            if (invalidated) {
                changes++
                // The stage composes again and reads them anew.
                read = reads { assertEquals(now, values.keptAt(12, 5, row)) }
            }
            last = now
        }
        // The row crossed cards, but most of its frames kept the same places.
        assertTrue("$changes changes", changes in 2..30)
    }

    @Test fun anOpeningGridInvalidatesTheStageOnlyWhenItsKeptPlacesChange() {
        val stage = grid(40)
        val values = values()
        values.scroll = 1800f
        var read = reads { values.keptAt(40, 22, stage); values.showing }
        var last = keptPlaces(40, 22, 0f, 1800f, stage)
        var changes = 0
        for (step in 1..60) {
            val p = step / 60f
            val invalidated = invalidates(read) { values.progress = p }
            val now = keptPlaces(40, 22, p, 1800f, stage)
            // The first frame also shows the overview.
            assertEquals("progress $p", now != last || step == 1, invalidated)
            if (invalidated) {
                changes++
                read = reads { values.keptAt(40, 22, stage); values.showing }
            }
            last = now
        }
        assertTrue("$changes changes", changes in 1..3)
    }

    @Test fun anotherStageIsReadAtOnceAndAMotionComparesWithIt() {
        val values = values(progress = 1f)
        assertEquals(keptPlaces(12, 5, 1f, 0f, row), values.keptAt(12, 5, row))
        // A tab closed and the row built around another card: the stage reads the places for them in the same composition.
        val read = reads { assertEquals(keptPlaces(6, 2, 1f, 0f, row), values.keptAt(6, 2, row)) }
        // A motion compares with what the stage read last: one that keeps those places is no change.
        val s = 10f
        assertEquals(keptPlaces(6, 2, 1f, 0f, row), keptPlaces(6, 2, 1f, s, row))
        assertFalse(invalidates(read) { values.scroll = s })
    }

    // #373: what composition decides while a finger moves a card or a pane. The finger moves on each frame too, and what
    // composition decides from it changes rarely: it is worked out as the finger, the row or the drop moves ([MotionValues.moved]).

    /** The keys of decisions, as OverviewState and PaneDrag.kt keep theirs. */
    private object Place
    private object Other

    @Test fun aDraggedCardsPlaceInvalidatesTheStageOnlyWhenItChanges() {
        // As TabStage reads the place a card dragged from place 3 would drop on ([OverviewState.dragPlace]): the card under
        // the finger as it was held, at its place's height, its middle against the places of the row built around 3.
        val values = values(progress = 1f)
        val anchor = 3
        val start = cardBox(anchor, anchor, 1f, 0f, row)
        val grab = Offset(300f, 200f)
        val drag = TabDrag("t3", anchor, grab, values::moved)
        drag.finger = Offset(start.left, start.top) + grab
        fun formula(): Int {
            val place = cardBox(anchor, anchor, values.progress, values.scroll, row)
            val middle = cardCentre(Offset(drag.finger.x - grab.x, place.top), values.progress, row)
            return dropPlace(middle.x, middle.y, 12, anchor, values.progress, values.scroll, row)
        }
        fun read() = values.decided(Place) { formula() }
        var read = reads { assertEquals(anchor, read()) }
        var last = anchor
        var changes = 0
        fun step(move: () -> Unit) {
            val invalidated = invalidates(read, move)
            val now = formula()
            assertEquals("finger ${drag.finger}, scroll ${values.scroll}", now != last, invalidated)
            if (invalidated) {
                changes++
                // The stage composes again and reads the new place.
                read = reads { assertEquals(now, read()) }
            }
            last = now
        }
        // The finger carries the card three places to the right and back past its own, a step of a frame each.
        for (i in (0..180) + (180 downTo -120)) step { drag.finger = Offset(start.left + grab.x + i * 23f, start.top + grab.y) }
        // Held at the edge, it has the row scroll under it.
        for (i in 1..120) step { values.scroll = i * 31f }
        assertTrue("$changes changes", changes in 6..20)
    }

    /** Tab t1 holds a beside b; tab t2 holds c over d, as in [PaneDragTest]. */
    private val t1 = WindowInfo("t1", layout = LayoutNode(dir = "row", ratio = 0.5f, first = LayoutNode(block = "a"), second = LayoutNode(block = "b")))
    private val t2 = WindowInfo("t2", layout = LayoutNode(dir = "column", ratio = 0.5f, first = LayoutNode(block = "c"), second = LayoutNode(block = "d")))

    @Test fun aMovedPanesPlaceAndCancelInvalidateTheOverlayOnlyWhenTheyChange() {
        // As PaneMoveOverlay reads the place under the finger ([PaneMoves.target]) and whether the finger is on pick mode's
        // Cancel button ([PaneMoves.onCancel]): a finger written as OverviewState's paneFinger is, a move each time.
        val values = values(progress = 1f)
        val finger = mutableStateOf<Offset?>(null)
        fun moveTo(at: Offset?) {
            finger.value = at
            values.moved()
        }
        val cancel = CancelBounds().apply {
            drawn = Rect(900f, 40f, 1100f, 120f)
            press(pick = true)
        }
        fun place() = finger.value?.let { stagePlaceAt(it.x, it.y, listOf(t1, t2), "zz", true, 0, values.progress, values.scroll, row, 8f, false) }?.place
        fun onCancel() = cancel.contains(finger.value)
        fun read() = values.decided(Place) { place() } to values.decided(Other) { onCancel() }
        var read = reads { read() }
        var last = place() to onCancel()
        var changes = 0
        val box = cardBox(0, 0, 1f, 0f, row)
        // Across both cards at their middle's height, then up onto the Cancel button and off it.
        val path = (0..200).map { Offset(box.left + it * 15f, (box.top + box.bottom) / 2) } +
            (0..40).map { Offset(1000f, (box.top + box.bottom) / 2 - it * 15f) } + (0..20).map { Offset(1000f + it * 15f, 80f) }
        for (at in path) {
            val invalidated = invalidates(read) { moveTo(at) }
            val now = place() to onCancel()
            assertEquals("finger $at", now != last, invalidated)
            if (invalidated) {
                changes++
                read = reads { assertEquals(now, read()) }
            }
            last = now
        }
        assertTrue("$changes changes", changes in 6..30)
        assertTrue("the finger was on Cancel", path.any { cancel.contains(it) })
    }

    @Test fun theCardsInSightInvalidatePickModeOnlyWhenTheyChange() {
        // As PickNodes reads the cards in the pane area ([inView]) while the overview opens and the row scrolls under a
        // finger held at its edge.
        val values = values()
        fun formula() = (0 until 12).filter { inView(cardBox(it, 4, values.progress, values.scroll, row), row) }
        fun read() = values.decided(Place) { formula() }
        var read = reads { read() }
        var last = formula()
        var changes = 0
        val steps = (1..30).map { { values.progress = it / 30f } } + (1..200).map { { values.scroll = it * 31f } }
        for (move in steps) {
            val invalidated = invalidates(read, move)
            val now = formula()
            assertEquals("progress ${values.progress}, scroll ${values.scroll}", now != last, invalidated)
            if (invalidated) {
                changes++
                read = reads { assertEquals(now, read()) }
            }
            last = now
        }
        assertTrue("$changes changes", changes in 3..20)
    }

    @Test fun decisionsAreKeptApartAndAForgottenOneIsWorkedOutNoMore() {
        val values = values(progress = 1f)
        var asked = 0
        // Typed, as a composable's read is: in a lambda that returns nothing, the answer would be taken as Unit.
        fun place(): Boolean = values.decided(Place) { asked++; values.scroll > 100f }
        fun other(): Boolean = values.decided(Other) { values.scroll > 500f }
        val place = reads { place() }
        val other = reads { other() }
        // Place changes, Other does not: Place's reader hears it, Other's nothing.
        assertTrue(invalidates(place) { values.scroll = 200f })
        values.scroll = 0f
        assertFalse(invalidates(other) { values.scroll = 200f })
        val worked = asked
        values.forget(Place)
        values.scroll = 50f
        values.scroll = 300f
        assertEquals("a forgotten decision is not worked out", worked, asked)
        assertTrue(invalidates(other) { values.scroll = 600f })
    }
}
